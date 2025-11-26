package com.example.solace;

import com.solacesystems.jcsmp.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static com.example.solace.AuditLogger.maskSensitive;

@Command(
    name = "oracle-publish",
    aliases = {"ora-pub"},
    description = "Query Oracle database and publish results as messages to Solace",
    mixinStandardHelpOptions = true
)
public class OraclePublishCommand implements Callable<Integer> {

    @Mixin
    ConnectionOptions solaceConnection;

    @Mixin
    OracleOptions oracleConnection;

    @Mixin
    AuditOptions auditOptions;

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

    @Option(names = {"--correlation-column"},
            description = "Column name to use as correlation ID")
    String correlationColumn;

    @Option(names = {"--delivery-mode"},
            description = "Delivery mode: PERSISTENT or DIRECT",
            defaultValue = "PERSISTENT")
    String deliveryMode;

    @Option(names = {"--ttl"},
            description = "Time to live in milliseconds (0 = no expiry)",
            defaultValue = "0")
    long ttl;

    @Option(names = {"--dry-run"},
            description = "Query database but don't publish messages")
    boolean dryRun;

    @Option(names = {"--second-queue", "-Q"},
            description = "Also publish to this second queue (fan-out)")
    String secondQueue;

    @Option(names = {"--exclude-file"},
            description = "File containing patterns to exclude (checks content and correlation ID)")
    File excludeFile;

    @Option(names = {"--failed-dir"},
            description = "Directory to save failed messages for later retry")
    File failedDir;

    private ExclusionList exclusionList;

    // Example query for reference
    private static final String EXAMPLE_QUERY =
        "SELECT message_id, message_content, correlation_id FROM outbound_messages WHERE status = 'PENDING'";

    @Override
    public Integer call() {
        Connection dbConnection = null;
        JCSMPSession solaceSession = null;
        XMLMessageProducer producer = null;
        AuditLogger audit = AuditLogger.create(auditOptions, "oracle-publish");
        FailedMessageHandler failedHandler = new FailedMessageHandler(failedDir, null);
        List<String> failedMessageIds = new ArrayList<>();
        int messageCount = 0;
        int excludedCount = 0;
        int failedCount = 0;

        // Log parameters (mask sensitive values)
        audit.addParameter("solaceHost", solaceConnection.host)
             .addParameter("solaceVpn", solaceConnection.vpn)
             .addParameter("solaceUsername", solaceConnection.username)
             .addParameter("solacePassword", maskSensitive(solaceConnection.password))
             .addParameter("solaceQueue", solaceConnection.queue)
             .addParameter("oracleJdbcUrl", oracleConnection.getJdbcUrl())
             .addParameter("oracleUser", oracleConnection.dbUser)
             .addParameter("oraclePassword", maskSensitive(oracleConnection.dbPassword))
             .addParameter("sqlFile", sqlFile != null ? sqlFile.getAbsolutePath() : null)
             .addParameter("messageColumn", messageColumn)
             .addParameter("correlationColumn", correlationColumn)
             .addParameter("deliveryMode", deliveryMode)
             .addParameter("ttl", ttl)
             .addParameter("dryRun", dryRun)
             .addParameter("secondQueue", secondQueue)
             .addParameter("failedDir", failedDir != null ? failedDir.getAbsolutePath() : null);

        try {
            // Initialize failed message directory if specified
            if (!failedHandler.initFailedDir()) {
                audit.setError("Failed to initialize failed message directory");
                audit.logCompletion(1);
                return 1;
            }
            // Resolve SQL query from --sql or --sql-file
            String effectiveSql = resolveSqlQuery();
            if (effectiveSql == null) {
                audit.setError("Failed to resolve SQL query").logCompletion(1);
                return 1;
            }

            // Load exclusion list if specified
            if (excludeFile != null) {
                if (!excludeFile.exists()) {
                    System.err.println("Error: Exclude file not found: " + excludeFile.getAbsolutePath());
                    audit.setError("Exclude file not found").logCompletion(1);
                    return 1;
                }
                exclusionList = ExclusionList.fromFile(excludeFile);
                System.out.println("Loaded " + exclusionList.size() + " exclusion pattern(s) from " + excludeFile.getName());
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
            int correlationColIndex = -1;

            if (!messageColumn.isEmpty()) {
                messageColIndex = rs.findColumn(messageColumn);
            }
            if (correlationColumn != null && !correlationColumn.isEmpty()) {
                correlationColIndex = rs.findColumn(correlationColumn);
            }

            if (dryRun) {
                System.out.println("\n=== DRY RUN MODE - Messages will not be published ===\n");
            } else {
                // Connect to Solace
                System.out.println("Connecting to Solace at " + solaceConnection.host + "...");
                solaceSession = SolaceConnection.createSession(solaceConnection);
                System.out.println("Connected to Solace successfully");

                producer = solaceSession.getMessageProducer(new JCSMPStreamingPublishCorrelatingEventHandler() {
                    @Override
                    public void responseReceivedEx(Object key) {
                        // Message acknowledged
                    }

                    @Override
                    public void handleErrorEx(Object key, JCSMPException cause, long timestamp) {
                        System.err.println("Error publishing message: " + cause.getMessage());
                    }
                });
            }

            Queue queue = JCSMPFactory.onlyInstance().createQueue(solaceConnection.queue);
            Queue queue2 = (secondQueue != null) ? JCSMPFactory.onlyInstance().createQueue(secondQueue) : null;
            DeliveryMode mode = "DIRECT".equalsIgnoreCase(deliveryMode)
                ? DeliveryMode.DIRECT
                : DeliveryMode.PERSISTENT;

            // Start progress reporter (will print every 2 seconds if running long)
            ProgressReporter progress = new ProgressReporter("Publishing", 2);
            if (!dryRun) {
                progress.start();
            }

            int rowIndex = 0;
            while (rs.next()) {
                rowIndex++;
                String content = rs.getString(messageColIndex);
                String correlationId = correlationColIndex > 0 ? rs.getString(correlationColIndex) : null;

                // Check exclusion
                if (shouldExclude(content, correlationId)) {
                    excludedCount++;
                    continue;
                }

                if (dryRun) {
                    messageCount++;
                    System.out.println("Message " + messageCount + ":");
                    System.out.println("  Content: " + truncate(content, 100));
                    if (correlationId != null) {
                        System.out.println("  Correlation ID: " + correlationId);
                    }
                } else {
                    try {
                        TextMessage msg = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
                        msg.setText(content);
                        msg.setDeliveryMode(mode);

                        if (correlationId != null) {
                            msg.setCorrelationId(correlationId);
                        }

                        if (ttl > 0) {
                            msg.setTimeToLive(ttl);
                        }

                        producer.send(msg, queue);
                        messageCount++;
                        progress.increment();

                        if (queue2 != null) {
                            producer.send(msg, queue2);
                            progress.increment();
                        }
                    } catch (Exception e) {
                        failedCount++;
                        String msgId = correlationId != null ? correlationId : "row-" + rowIndex;
                        failedMessageIds.add(msgId);
                        progress.incrementError();

                        // Save failed message if directory specified
                        failedHandler.saveFailedMessage(content, correlationId, solaceConnection.queue,
                            e.getMessage(), rowIndex);
                    }
                }
            }

            if (!dryRun) {
                progress.stop();
            }

            rs.close();
            stmt.close();

            String excludedInfo = excludedCount > 0 ? " (" + excludedCount + " excluded)" : "";
            String failedInfo = failedCount > 0 ? " (" + failedCount + " failed)" : "";
            if (dryRun) {
                System.out.println("\nDry run complete. Found " + messageCount + " message(s) to publish" + excludedInfo + ".");
            } else {
                progress.printSummary();
                if (excludedCount > 0) {
                    System.out.println("  Excluded: " + excludedCount);
                }
                if (failedCount > 0) {
                    System.out.println("  Failed: " + failedCount);
                    if (failedHandler.isSaveEnabled()) {
                        System.out.println("Failed messages saved to: " + failedDir.getAbsolutePath());
                    }
                }
            }

            // Log results with partial progress
            audit.addResult("rowsQueried", messageCount + excludedCount + failedCount)
                 .addResult("messagesPublished", dryRun ? 0 : messageCount)
                 .addResult("messagesFailed", failedCount)
                 .addResult("messagesExcluded", excludedCount)
                 .addResult("dryRun", dryRun)
                 .addResult("queuesUsed", (secondQueue != null && !dryRun) ? 2 : 1);

            if (!failedMessageIds.isEmpty()) {
                audit.addResult("failedMessageIds", failedMessageIds);
            }
            if (failedHandler.isSaveEnabled() && failedHandler.getSavedCount() > 0) {
                audit.addResult("failedMessagesSaved", failedHandler.getSavedCount());
                audit.addResult("failedDir", failedDir.getAbsolutePath());
            }

            int exitCode = failedCount > 0 ? 1 : 0;
            if (failedCount > 0) {
                audit.setError(failedCount + " message(s) failed to publish");
            }
            audit.logCompletion(exitCode);
            return exitCode;

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();

            // Log partial progress even on exception
            audit.addResult("messagesPublished", messageCount)
                 .addResult("messagesFailed", failedCount)
                 .addResult("messagesExcluded", excludedCount);

            if (!failedMessageIds.isEmpty()) {
                audit.addResult("failedMessageIds", failedMessageIds);
            }

            audit.setError(e.getMessage()).logCompletion(1);
            return 1;
        } finally {
            if (producer != null) {
                producer.close();
            }
            if (solaceSession != null) {
                solaceSession.closeSession();
            }
            if (dbConnection != null) {
                try {
                    dbConnection.close();
                } catch (Exception e) {
                    // Ignore close errors
                }
            }
        }
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

    /**
     * Check if a message should be excluded based on content or correlation ID.
     */
    private boolean shouldExclude(String content, String correlationId) {
        if (exclusionList == null || exclusionList.isEmpty()) {
            return false;
        }
        // Check correlation ID
        if (correlationId != null && exclusionList.isExcluded(correlationId)) {
            return true;
        }
        // Check content
        if (content != null && exclusionList.containsExcluded(content)) {
            return true;
        }
        return false;
    }
}
