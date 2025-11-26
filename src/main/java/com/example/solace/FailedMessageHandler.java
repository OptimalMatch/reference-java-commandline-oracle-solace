package com.example.solace;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles saving failed messages for later retry and loading messages from retry directory.
 *
 * Failed messages are saved as individual files with metadata in the filename:
 * - Format: {timestamp}_{correlationId}_{index}.failed
 * - Content: The message payload
 * - Metadata file: {timestamp}_{correlationId}_{index}.meta (JSON with queue, error, etc.)
 */
public class FailedMessageHandler {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    private final File failedDir;
    private final File retryDir;
    private final List<String> failedMessageIds = new ArrayList<>();
    private int savedCount = 0;

    /**
     * Create a handler with optional failed and retry directories.
     */
    public FailedMessageHandler(File failedDir, File retryDir) {
        this.failedDir = failedDir;
        this.retryDir = retryDir;
    }

    /**
     * Check if failed message saving is enabled.
     */
    public boolean isSaveEnabled() {
        return failedDir != null;
    }

    /**
     * Check if retry loading is enabled.
     */
    public boolean isRetryEnabled() {
        return retryDir != null;
    }

    /**
     * Initialize the failed directory if saving is enabled.
     * @return true if successful or not enabled, false if directory creation failed
     */
    public boolean initFailedDir() {
        if (failedDir == null) {
            return true;
        }
        if (!failedDir.exists()) {
            if (!failedDir.mkdirs()) {
                System.err.println("Error: Failed to create failed message directory: " + failedDir.getAbsolutePath());
                return false;
            }
            System.out.println("Created failed message directory: " + failedDir.getAbsolutePath());
        }
        if (!failedDir.isDirectory()) {
            System.err.println("Error: Failed message path is not a directory: " + failedDir.getAbsolutePath());
            return false;
        }
        return true;
    }

    /**
     * Validate retry directory exists if retry is enabled.
     * @return true if successful or not enabled, false if directory doesn't exist
     */
    public boolean validateRetryDir() {
        if (retryDir == null) {
            return true;
        }
        if (!retryDir.exists()) {
            System.err.println("Error: Retry directory does not exist: " + retryDir.getAbsolutePath());
            return false;
        }
        if (!retryDir.isDirectory()) {
            System.err.println("Error: Retry path is not a directory: " + retryDir.getAbsolutePath());
            return false;
        }
        return true;
    }

    /**
     * Save a failed message to the failed directory.
     *
     * @param content The message content
     * @param correlationId The correlation ID (can be null)
     * @param queue The target queue name
     * @param error The error message
     * @param index The message index in the batch
     */
    public void saveFailedMessage(String content, String correlationId, String queue, String error, int index) {
        if (failedDir == null) {
            return;
        }

        try {
            String timestamp = TIMESTAMP_FORMAT.format(Instant.now());
            String safeCorrelationId = sanitizeFilename(correlationId != null ? correlationId : "no-correlation");
            String baseFilename = timestamp + "_" + safeCorrelationId + "_" + index;

            // Save message content
            File contentFile = new File(failedDir, baseFilename + ".msg");
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(contentFile), StandardCharsets.UTF_8)) {
                writer.write(content);
            }

            // Save metadata as JSON
            File metaFile = new File(failedDir, baseFilename + ".meta");
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(metaFile), StandardCharsets.UTF_8)) {
                writer.write("{\n");
                writer.write("  \"timestamp\": \"" + Instant.now().toString() + "\",\n");
                writer.write("  \"queue\": \"" + escapeJson(queue) + "\",\n");
                writer.write("  \"correlationId\": " + (correlationId != null ? "\"" + escapeJson(correlationId) + "\"" : "null") + ",\n");
                writer.write("  \"index\": " + index + ",\n");
                writer.write("  \"error\": \"" + escapeJson(error) + "\",\n");
                writer.write("  \"contentFile\": \"" + contentFile.getName() + "\"\n");
                writer.write("}\n");
            }

            savedCount++;
            String msgId = correlationId != null ? correlationId : String.valueOf(index);
            failedMessageIds.add(msgId);

        } catch (Exception e) {
            System.err.println("Warning: Failed to save failed message: " + e.getMessage());
        }
    }

    /**
     * Load messages from the retry directory.
     *
     * @return List of FailedMessage objects to retry
     */
    public List<FailedMessage> loadRetryMessages() {
        List<FailedMessage> messages = new ArrayList<>();

        if (retryDir == null) {
            return messages;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(retryDir.toPath(), "*.msg")) {
            for (Path msgPath : stream) {
                try {
                    FailedMessage msg = loadMessage(msgPath.toFile());
                    if (msg != null) {
                        messages.add(msg);
                    }
                } catch (Exception e) {
                    System.err.println("Warning: Failed to load retry message " + msgPath.getFileName() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("Error reading retry directory: " + e.getMessage());
        }

        return messages;
    }

    /**
     * Load a single message and its metadata.
     */
    private FailedMessage loadMessage(File msgFile) throws Exception {
        // Read content
        String content = new String(Files.readAllBytes(msgFile.toPath()), StandardCharsets.UTF_8);

        // Try to read metadata
        String metaFilename = msgFile.getName().replace(".msg", ".meta");
        File metaFile = new File(msgFile.getParentFile(), metaFilename);

        String correlationId = null;
        String originalQueue = null;

        if (metaFile.exists()) {
            String metaContent = new String(Files.readAllBytes(metaFile.toPath()), StandardCharsets.UTF_8);
            correlationId = extractJsonValue(metaContent, "correlationId");
            originalQueue = extractJsonValue(metaContent, "queue");
        }

        return new FailedMessage(content, correlationId, originalQueue, msgFile);
    }

    /**
     * Mark a retry message as successfully processed by moving/deleting its files.
     */
    public void markRetrySuccess(FailedMessage message) {
        if (message.getSourceFile() != null) {
            File msgFile = message.getSourceFile();
            File metaFile = new File(msgFile.getParentFile(),
                msgFile.getName().replace(".msg", ".meta"));

            // Delete processed files
            if (msgFile.exists()) {
                msgFile.delete();
            }
            if (metaFile.exists()) {
                metaFile.delete();
            }
        }
    }

    /**
     * Get the count of saved failed messages.
     */
    public int getSavedCount() {
        return savedCount;
    }

    /**
     * Get list of failed message IDs (correlation IDs or indices).
     */
    public List<String> getFailedMessageIds() {
        return new ArrayList<>(failedMessageIds);
    }

    /**
     * Sanitize a string for use in a filename.
     */
    private String sanitizeFilename(String name) {
        if (name == null || name.isEmpty()) {
            return "unknown";
        }
        String sanitized = name.replaceAll("[\\\\/:*?\"<>|\\s]", "_");
        if (sanitized.length() > 50) {
            sanitized = sanitized.substring(0, 50);
        }
        return sanitized;
    }

    /**
     * Escape special characters for JSON.
     */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Simple JSON value extraction (handles string and null values).
     */
    private String extractJsonValue(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*";
        int keyIndex = json.indexOf("\"" + key + "\"");
        if (keyIndex == -1) return null;

        int colonIndex = json.indexOf(":", keyIndex);
        if (colonIndex == -1) return null;

        // Skip whitespace
        int valueStart = colonIndex + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }

        if (valueStart >= json.length()) return null;

        // Check for null
        if (json.substring(valueStart).startsWith("null")) {
            return null;
        }

        // Extract quoted string
        if (json.charAt(valueStart) == '"') {
            int valueEnd = json.indexOf("\"", valueStart + 1);
            if (valueEnd == -1) return null;
            return json.substring(valueStart + 1, valueEnd);
        }

        return null;
    }

    /**
     * Represents a failed message loaded for retry.
     */
    public static class FailedMessage {
        private final String content;
        private final String correlationId;
        private final String originalQueue;
        private final File sourceFile;

        public FailedMessage(String content, String correlationId, String originalQueue, File sourceFile) {
            this.content = content;
            this.correlationId = correlationId;
            this.originalQueue = originalQueue;
            this.sourceFile = sourceFile;
        }

        public String getContent() {
            return content;
        }

        public String getCorrelationId() {
            return correlationId;
        }

        public String getOriginalQueue() {
            return originalQueue;
        }

        public File getSourceFile() {
            return sourceFile;
        }
    }
}
