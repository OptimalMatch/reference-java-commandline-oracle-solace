package com.example.solace;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Audit logger for tracking CLI command executions.
 * Writes JSON-formatted log entries to a file with details about
 * what was run, when, by whom, and what was processed.
 */
public class AuditLogger {

    private static final DateTimeFormatter ISO_FORMATTER =
        DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.systemDefault());

    private final File logFile;
    private final String command;
    private final Instant startTime;
    private Instant endTime;
    private int exitCode = -1;
    private final Map<String, Object> parameters = new LinkedHashMap<>();
    private final Map<String, Object> results = new LinkedHashMap<>();
    private String errorMessage;

    /**
     * Create a new audit logger for a command execution.
     *
     * @param logFile The file to append audit entries to
     * @param command The name of the command being executed
     */
    public AuditLogger(File logFile, String command) {
        this.logFile = logFile;
        this.command = command;
        this.startTime = Instant.now();
    }

    /**
     * Add a parameter that was used in the command execution.
     * Sensitive values like passwords should be masked.
     */
    public AuditLogger addParameter(String name, Object value) {
        parameters.put(name, value);
        return this;
    }

    /**
     * Add a result metric from the command execution.
     */
    public AuditLogger addResult(String name, Object value) {
        results.put(name, value);
        return this;
    }

    /**
     * Set the error message if the command failed.
     */
    public AuditLogger setError(String message) {
        this.errorMessage = message;
        return this;
    }

    /**
     * Log the completion of the command execution.
     *
     * @param exitCode The exit code (0 for success, non-zero for failure)
     */
    public void logCompletion(int exitCode) {
        this.exitCode = exitCode;
        this.endTime = Instant.now();
        writeEntry();
    }

    /**
     * Write the audit log entry to the file.
     */
    private void writeEntry() {
        if (logFile == null) {
            return;
        }

        try {
            // Ensure parent directory exists
            File parentDir = logFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            try (PrintWriter writer = new PrintWriter(new FileWriter(logFile, true))) {
                writer.println(toJson());
            }
        } catch (IOException e) {
            System.err.println("Warning: Failed to write audit log: " + e.getMessage());
        }
    }

    /**
     * Convert the audit entry to a JSON string.
     */
    private String toJson() {
        StringBuilder json = new StringBuilder();
        json.append("{");

        // Metadata
        json.append("\"timestamp\":\"").append(ISO_FORMATTER.format(startTime)).append("\",");
        json.append("\"command\":\"").append(escapeJson(command)).append("\",");
        json.append("\"user\":\"").append(escapeJson(getUsername())).append("\",");
        json.append("\"hostname\":\"").append(escapeJson(getHostname())).append("\",");
        json.append("\"workingDir\":\"").append(escapeJson(System.getProperty("user.dir"))).append("\",");

        // Timing
        json.append("\"startTime\":\"").append(ISO_FORMATTER.format(startTime)).append("\",");
        if (endTime != null) {
            json.append("\"endTime\":\"").append(ISO_FORMATTER.format(endTime)).append("\",");
            long durationMs = endTime.toEpochMilli() - startTime.toEpochMilli();
            json.append("\"durationMs\":").append(durationMs).append(",");
        }

        // Status
        json.append("\"exitCode\":").append(exitCode).append(",");
        json.append("\"success\":").append(exitCode == 0).append(",");

        // Error if present
        if (errorMessage != null) {
            json.append("\"error\":\"").append(escapeJson(errorMessage)).append("\",");
        }

        // Parameters
        json.append("\"parameters\":{");
        appendMap(json, parameters);
        json.append("},");

        // Results
        json.append("\"results\":{");
        appendMap(json, results);
        json.append("}");

        json.append("}");
        return json.toString();
    }

    /**
     * Append a map as JSON key-value pairs.
     */
    private void appendMap(StringBuilder json, Map<String, Object> map) {
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) {
                json.append(",");
            }
            first = false;
            json.append("\"").append(escapeJson(entry.getKey())).append("\":");
            appendValue(json, entry.getValue());
        }
    }

    /**
     * Append a value as JSON.
     */
    private void appendValue(StringBuilder json, Object value) {
        if (value == null) {
            json.append("null");
        } else if (value instanceof Number) {
            json.append(value);
        } else if (value instanceof Boolean) {
            json.append(value);
        } else if (value instanceof List) {
            json.append("[");
            List<?> list = (List<?>) value;
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) json.append(",");
                appendValue(json, list.get(i));
            }
            json.append("]");
        } else {
            json.append("\"").append(escapeJson(value.toString())).append("\"");
        }
    }

    /**
     * Escape special characters for JSON strings.
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
     * Get the current username.
     */
    private String getUsername() {
        String user = System.getProperty("user.name");
        return user != null ? user : "unknown";
    }

    /**
     * Get the hostname.
     */
    private String getHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Mask sensitive values like passwords.
     */
    public static String maskSensitive(String value) {
        if (value == null || value.isEmpty()) {
            return "<empty>";
        }
        return "****";
    }

    /**
     * Create an audit logger if audit logging is enabled.
     *
     * @param auditOptions The audit options from CLI
     * @param command The command name
     * @return An AuditLogger instance, or a no-op logger if disabled
     */
    public static AuditLogger create(AuditOptions auditOptions, String command) {
        if (auditOptions == null || auditOptions.auditFile == null) {
            return new NoOpAuditLogger();
        }
        return new AuditLogger(auditOptions.auditFile, command);
    }

    /**
     * A no-op implementation for when audit logging is disabled.
     */
    private static class NoOpAuditLogger extends AuditLogger {
        NoOpAuditLogger() {
            super(null, null);
        }

        @Override
        public AuditLogger addParameter(String name, Object value) {
            return this;
        }

        @Override
        public AuditLogger addResult(String name, Object value) {
            return this;
        }

        @Override
        public AuditLogger setError(String message) {
            return this;
        }

        @Override
        public void logCompletion(int exitCode) {
            // No-op
        }
    }
}
