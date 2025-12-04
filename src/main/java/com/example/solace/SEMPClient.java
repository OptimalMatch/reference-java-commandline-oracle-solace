package com.example.solace;

import javax.net.ssl.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client for Solace SEMP v2 Monitor API.
 * Uses Java's built-in HttpURLConnection to avoid external dependencies.
 */
public class SEMPClient {

    private final SEMPOptions options;
    private final String basicAuth;

    public SEMPClient(SEMPOptions options) {
        this.options = options;
        this.basicAuth = createBasicAuth(options.sempUser, options.sempPassword);

        if (options.skipCertValidation) {
            disableCertificateValidation();
        }
    }

    /**
     * List queues matching a name pattern with detailed information.
     *
     * @param namePattern Queue name pattern (supports * wildcard, e.g., "GAO.Q_T2*")
     * @return List of QueueInfo objects with queue details
     */
    public List<QueueInfo> listQueues(String namePattern) throws IOException {
        List<QueueInfo> queues = new ArrayList<>();
        String cursor = null;

        do {
            String url = buildQueueListUrl(namePattern, cursor);
            String response = executeGet(url);

            // Parse the JSON response
            List<Map<String, Object>> data = parseJsonArray(response, "data");
            for (Map<String, Object> queueData : data) {
                queues.add(QueueInfo.fromSempResponse(queueData));
            }

            // Check for pagination
            cursor = extractNextPageCursor(response);

        } while (cursor != null);

        return queues;
    }

    /**
     * Get detailed information for a specific queue.
     */
    public QueueInfo getQueue(String queueName) throws IOException {
        String url = options.getMonitorApiUrl() + "/queues/" + urlEncode(queueName);
        String response = executeGet(url);

        Map<String, Object> data = parseJsonObject(response, "data");
        return QueueInfo.fromSempResponse(data);
    }

    private String buildQueueListUrl(String namePattern, String cursor) {
        StringBuilder url = new StringBuilder(options.getMonitorApiUrl());
        url.append("/queues");

        // Build query parameters
        List<String> params = new ArrayList<>();

        // Add select fields for the information we want
        params.add("select=" + urlEncode(
            "queueName,enabled,accessType,permission,partitionCount,partitionRebalanceDelay," +
            "msgSpoolUsage,msgVpnName,bindCount,maxMsgSpoolUsage,msgCount,spooledMsgCount," +
            "highestMsgSpoolUsage,consumerAckPropagationEnabled,respectMsgPriorityEnabled," +
            "egressEnabled,ingressEnabled,maxBindCount,maxMsgSize,eventMsgSpoolUsageThreshold"
        ));

        // Add name filter using wildcard pattern
        if (namePattern != null && !namePattern.isEmpty()) {
            // SEMP uses comma-separated list with * wildcards
            params.add("where=" + urlEncode("queueName==" + namePattern));
        }

        // Add pagination cursor if present
        if (cursor != null) {
            params.add("cursor=" + urlEncode(cursor));
        }

        // Add count to get more results per page
        params.add("count=100");

        if (!params.isEmpty()) {
            url.append("?").append(String.join("&", params));
        }

        return url.toString();
    }

    private String executeGet(String urlString) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        try {
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Basic " + basicAuth);
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);

            int responseCode = conn.getResponseCode();

            if (responseCode >= 200 && responseCode < 300) {
                return readStream(conn.getInputStream());
            } else {
                String errorBody = "";
                if (conn.getErrorStream() != null) {
                    errorBody = readStream(conn.getErrorStream());
                }
                throw new IOException("SEMP API request failed: HTTP " + responseCode +
                    (errorBody.isEmpty() ? "" : " - " + extractErrorMessage(errorBody)));
            }
        } finally {
            conn.disconnect();
        }
    }

    private String readStream(InputStream is) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    private String createBasicAuth(String username, String password) {
        String credentials = username + ":" + (password != null ? password : "");
        return Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    /**
     * Simple JSON array parser - extracts an array from a JSON response.
     * This avoids adding a JSON library dependency.
     */
    private List<Map<String, Object>> parseJsonArray(String json, String arrayName) {
        List<Map<String, Object>> result = new ArrayList<>();

        // Find the array in the JSON
        String arrayPattern = "\"" + arrayName + "\"\\s*:\\s*\\[";
        Pattern pattern = Pattern.compile(arrayPattern);
        Matcher matcher = pattern.matcher(json);

        if (!matcher.find()) {
            return result;
        }

        int start = matcher.end();
        int bracketCount = 1;
        int objStart = -1;
        int objBracketCount = 0;
        boolean inString = false;
        char prevChar = 0;

        for (int i = start; i < json.length() && bracketCount > 0; i++) {
            char c = json.charAt(i);

            // Handle string escaping
            if (c == '"' && prevChar != '\\') {
                inString = !inString;
            }

            if (!inString) {
                if (c == '[') {
                    bracketCount++;
                } else if (c == ']') {
                    bracketCount--;
                } else if (c == '{') {
                    if (objBracketCount == 0) {
                        objStart = i;
                    }
                    objBracketCount++;
                } else if (c == '}') {
                    objBracketCount--;
                    if (objBracketCount == 0 && objStart >= 0) {
                        String objJson = json.substring(objStart, i + 1);
                        result.add(parseSimpleObject(objJson));
                        objStart = -1;
                    }
                }
            }
            prevChar = c;
        }

        return result;
    }

    /**
     * Parse a JSON object from the response.
     */
    private Map<String, Object> parseJsonObject(String json, String objectName) {
        String pattern = "\"" + objectName + "\"\\s*:\\s*\\{";
        Pattern p = Pattern.compile(pattern);
        Matcher m = p.matcher(json);

        if (!m.find()) {
            return new HashMap<>();
        }

        int start = m.end() - 1; // Include the opening brace
        int bracketCount = 0;
        boolean inString = false;
        char prevChar = 0;

        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);

            if (c == '"' && prevChar != '\\') {
                inString = !inString;
            }

            if (!inString) {
                if (c == '{') {
                    bracketCount++;
                } else if (c == '}') {
                    bracketCount--;
                    if (bracketCount == 0) {
                        return parseSimpleObject(json.substring(start, i + 1));
                    }
                }
            }
            prevChar = c;
        }

        return new HashMap<>();
    }

    /**
     * Parse a simple flat JSON object (handles nested objects minimally).
     */
    private Map<String, Object> parseSimpleObject(String json) {
        Map<String, Object> result = new HashMap<>();

        // Remove outer braces
        json = json.trim();
        if (json.startsWith("{")) json = json.substring(1);
        if (json.endsWith("}")) json = json.substring(0, json.length() - 1);

        // Pattern to match key-value pairs
        Pattern kvPattern = Pattern.compile("\"([^\"]+)\"\\s*:\\s*");
        Matcher kvMatcher = kvPattern.matcher(json);

        while (kvMatcher.find()) {
            String key = kvMatcher.group(1);
            int valueStart = kvMatcher.end();
            Object value = parseValue(json, valueStart);
            if (value != null) {
                result.put(key, value);
            }
        }

        return result;
    }

    /**
     * Parse a JSON value starting at the given position.
     */
    private Object parseValue(String json, int start) {
        if (start >= json.length()) return null;

        // Skip whitespace
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }

        if (start >= json.length()) return null;

        char c = json.charAt(start);

        if (c == '"') {
            // String value
            int end = start + 1;
            while (end < json.length()) {
                if (json.charAt(end) == '"' && json.charAt(end - 1) != '\\') {
                    break;
                }
                end++;
            }
            return json.substring(start + 1, end).replace("\\\"", "\"");
        } else if (c == '{' || c == '[') {
            // Object or array - skip for now
            return null;
        } else if (c == 't' && json.substring(start).startsWith("true")) {
            return true;
        } else if (c == 'f' && json.substring(start).startsWith("false")) {
            return false;
        } else if (c == 'n' && json.substring(start).startsWith("null")) {
            return null;
        } else if (Character.isDigit(c) || c == '-') {
            // Number
            int end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end)) ||
                    json.charAt(end) == '.' || json.charAt(end) == '-' ||
                    json.charAt(end) == 'e' || json.charAt(end) == 'E' || json.charAt(end) == '+')) {
                end++;
            }
            String numStr = json.substring(start, end);
            try {
                if (numStr.contains(".") || numStr.contains("e") || numStr.contains("E")) {
                    return Double.parseDouble(numStr);
                } else {
                    return Long.parseLong(numStr);
                }
            } catch (NumberFormatException e) {
                return numStr;
            }
        }

        return null;
    }

    /**
     * Extract the cursor for the next page from the response.
     */
    private String extractNextPageCursor(String json) {
        // Look for "paging":{"cursorQuery":"..."}
        Pattern pattern = Pattern.compile("\"cursorQuery\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            // The cursor is URL-encoded in the response, decode it
            try {
                return java.net.URLDecoder.decode(matcher.group(1), "UTF-8");
            } catch (UnsupportedEncodingException e) {
                return matcher.group(1);
            }
        }
        return null;
    }

    /**
     * Extract error message from SEMP error response.
     */
    private String extractErrorMessage(String json) {
        Pattern pattern = Pattern.compile("\"description\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return json.length() > 200 ? json.substring(0, 200) : json;
    }

    /**
     * Disable SSL certificate validation (for development/testing only).
     */
    private void disableCertificateValidation() {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) { }
                    public void checkServerTrusted(X509Certificate[] certs, String authType) { }
                }
            };

            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAll, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
        } catch (Exception e) {
            System.err.println("Warning: Failed to disable certificate validation: " + e.getMessage());
        }
    }
}
