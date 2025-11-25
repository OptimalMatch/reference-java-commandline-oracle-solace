package com.example.solace;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.Callable;

@Command(
    name = "oracle-export",
    aliases = {"ora-export", "ora-exp"},
    description = "Query Oracle database and export each row as a file in a folder",
    mixinStandardHelpOptions = true
)
public class OracleExportCommand implements Callable<Integer> {

    @Mixin
    OracleOptions oracleConnection;

    @Option(names = {"--sql", "-s"},
            description = "SQL SELECT statement to execute (use --sql-file for multiline queries)")
    String sqlQuery;

    @Option(names = {"--sql-file", "-f"},
            description = "File containing SQL SELECT statement (supports multiline queries)")
    File sqlFile;

    @Option(names = {"--message-column", "-m"},
            description = "Column name containing the message content (default: first column)",
            defaultValue = "")
    String messageColumn;

    @Option(names = {"--filename-column"},
            description = "Column name to use as filename (without extension). If not specified, uses sequential numbering.")
    String filenameColumn;

    @Option(names = {"--output-folder", "-o"},
            description = "Output folder for exported files",
            required = true)
    File outputFolder;

    @Option(names = {"--extension", "-e"},
            description = "File extension for exported files (default: .txt)",
            defaultValue = ".txt")
    String fileExtension;

    @Option(names = {"--prefix"},
            description = "Prefix for generated filenames when not using filename-column",
            defaultValue = "message_")
    String filenamePrefix;

    @Option(names = {"--overwrite"},
            description = "Overwrite existing files (default: skip)")
    boolean overwrite;

    @Option(names = {"--dry-run"},
            description = "Query database but don't write files")
    boolean dryRun;

    @Override
    public Integer call() {
        Connection dbConnection = null;

        try {
            // Resolve SQL query from --sql or --sql-file
            String effectiveSql = resolveSqlQuery();
            if (effectiveSql == null) {
                return 1;
            }

            // Validate and create output folder
            if (!dryRun) {
                if (!outputFolder.exists()) {
                    System.out.println("Creating output folder: " + outputFolder.getAbsolutePath());
                    if (!outputFolder.mkdirs()) {
                        System.err.println("Error: Failed to create output folder: " + outputFolder.getAbsolutePath());
                        return 1;
                    }
                }
                if (!outputFolder.isDirectory()) {
                    System.err.println("Error: Output path is not a directory: " + outputFolder.getAbsolutePath());
                    return 1;
                }
            }

            // Normalize file extension
            if (!fileExtension.startsWith(".")) {
                fileExtension = "." + fileExtension;
            }

            // Connect to Oracle
            System.out.println("Connecting to Oracle at " + oracleConnection.getJdbcUrl() + "...");
            dbConnection = DriverManager.getConnection(
                oracleConnection.getJdbcUrl(),
                oracleConnection.dbUser,
                oracleConnection.dbPassword
            );
            System.out.println("Connected to Oracle successfully");

            // Execute query
            System.out.println("Executing query: " + formatQueryForDisplay(effectiveSql));
            Statement stmt = dbConnection.createStatement();
            ResultSet rs = stmt.executeQuery(effectiveSql);

            // Get column metadata
            int messageColIndex = 1;
            int filenameColIndex = -1;

            if (!messageColumn.isEmpty()) {
                messageColIndex = rs.findColumn(messageColumn);
            }
            if (filenameColumn != null && !filenameColumn.isEmpty()) {
                filenameColIndex = rs.findColumn(filenameColumn);
            }

            if (dryRun) {
                System.out.println("\n=== DRY RUN MODE - Files will not be written ===\n");
            }

            int fileCount = 0;
            int skippedCount = 0;

            // Start progress reporter (will print every 2 seconds if running long)
            ProgressReporter progress = new ProgressReporter("Exporting", 2);
            if (!dryRun) {
                progress.start();
            }

            while (rs.next()) {
                String content = rs.getString(messageColIndex);

                // Generate filename
                String filename;
                if (filenameColIndex > 0) {
                    String filenameValue = rs.getString(filenameColIndex);
                    // Sanitize filename - remove invalid characters
                    filename = sanitizeFilename(filenameValue) + fileExtension;
                } else {
                    filename = filenamePrefix + String.format("%06d", fileCount + 1) + fileExtension;
                }

                File outputFile = new File(outputFolder, filename);

                if (dryRun) {
                    System.out.println("Would write file: " + outputFile.getAbsolutePath());
                    System.out.println("  Content length: " + (content != null ? content.length() : 0) + " characters");
                    System.out.println("  Preview: " + truncate(content, 80));
                    fileCount++;
                } else {
                    if (outputFile.exists() && !overwrite) {
                        skippedCount++;
                        continue;
                    }

                    // Write file
                    try (Writer writer = new OutputStreamWriter(
                            new FileOutputStream(outputFile), StandardCharsets.UTF_8)) {
                        if (content != null) {
                            writer.write(content);
                        }
                    }

                    progress.increment();
                    fileCount++;
                }
            }

            if (!dryRun) {
                progress.stop();
            }

            rs.close();
            stmt.close();

            System.out.println();
            if (dryRun) {
                System.out.println("Dry run complete. Would export " + fileCount + " file(s) to " + outputFolder.getAbsolutePath());
            } else {
                progress.printSummary();
                if (skippedCount > 0) {
                    System.out.println("  Skipped: " + skippedCount + " (already exist)");
                }
                System.out.println("  Output folder: " + outputFolder.getAbsolutePath());

                if (fileCount > 0) {
                    System.out.println("\nTo publish these files to Solace, run:");
                    System.out.println("  java -jar solace-cli.jar folder-publish \\");
                    System.out.println("    --folder " + outputFolder.getAbsolutePath() + " \\");
                    System.out.println("    --pattern \"*" + fileExtension + "\" \\");
                    System.out.println("    -H <solace-host> -v <vpn> -u <user> -p <password> -q <queue>");
                }
            }

            return 0;

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            return 1;
        } finally {
            if (dbConnection != null) {
                try {
                    dbConnection.close();
                } catch (Exception e) {
                    // Ignore close errors
                }
            }
        }
    }

    /**
     * Sanitize a string to be used as a filename.
     * Removes or replaces characters that are invalid in filenames.
     */
    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "unnamed";
        }
        // Replace invalid characters with underscore
        String sanitized = filename.replaceAll("[\\\\/:*?\"<>|\\s]", "_");
        // Remove leading/trailing dots and spaces
        sanitized = sanitized.replaceAll("^[.\\s]+|[.\\s]+$", "");
        // Limit length
        if (sanitized.length() > 200) {
            sanitized = sanitized.substring(0, 200);
        }
        return sanitized.isEmpty() ? "unnamed" : sanitized;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "null";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    /**
     * Resolve SQL query from either --sql option or --sql-file option.
     * Returns null if neither is provided or if there's an error reading the file.
     */
    private String resolveSqlQuery() {
        if (sqlQuery != null && !sqlQuery.isEmpty() && sqlFile != null) {
            System.err.println("Error: Cannot specify both --sql and --sql-file. Use one or the other.");
            return null;
        }

        if (sqlFile != null) {
            if (!sqlFile.exists()) {
                System.err.println("Error: SQL file not found: " + sqlFile.getAbsolutePath());
                return null;
            }
            if (!sqlFile.isFile()) {
                System.err.println("Error: SQL file path is not a file: " + sqlFile.getAbsolutePath());
                return null;
            }
            try {
                String content = new String(Files.readAllBytes(sqlFile.toPath()), StandardCharsets.UTF_8);
                if (content.trim().isEmpty()) {
                    System.err.println("Error: SQL file is empty: " + sqlFile.getAbsolutePath());
                    return null;
                }
                System.out.println("Loaded SQL from file: " + sqlFile.getAbsolutePath());
                return content.trim();
            } catch (Exception e) {
                System.err.println("Error reading SQL file: " + e.getMessage());
                return null;
            }
        }

        if (sqlQuery == null || sqlQuery.isEmpty()) {
            System.err.println("Error: Must specify either --sql or --sql-file option.");
            return null;
        }

        return sqlQuery;
    }

    /**
     * Format query for display - truncate long queries and handle multiline.
     */
    private String formatQueryForDisplay(String sql) {
        if (sql == null) return "null";
        // Replace newlines with spaces for single-line display
        String oneLine = sql.replaceAll("\\s+", " ").trim();
        if (oneLine.length() > 100) {
            return oneLine.substring(0, 100) + "...";
        }
        return oneLine;
    }
}
