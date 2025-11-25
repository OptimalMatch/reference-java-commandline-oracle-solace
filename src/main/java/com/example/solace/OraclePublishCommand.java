package com.example.solace;

import com.solacesystems.jcsmp.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.Callable;

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

    @Option(names = {"--sql", "-s"},
            description = "SQL SELECT statement to execute",
            required = true)
    String sqlQuery;

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

    // Example query for reference
    private static final String EXAMPLE_QUERY =
        "SELECT message_id, message_content, correlation_id FROM outbound_messages WHERE status = 'PENDING'";

    @Override
    public Integer call() {
        Connection dbConnection = null;
        JCSMPSession solaceSession = null;
        XMLMessageProducer producer = null;

        try {
            // Connect to Oracle
            System.out.println("Connecting to Oracle at " + oracleConnection.getJdbcUrl() + "...");
            dbConnection = DriverManager.getConnection(
                oracleConnection.getJdbcUrl(),
                oracleConnection.dbUser,
                oracleConnection.dbPassword
            );
            System.out.println("Connected to Oracle successfully");

            // Execute query
            System.out.println("Executing query: " + sqlQuery);
            Statement stmt = dbConnection.createStatement();
            ResultSet rs = stmt.executeQuery(sqlQuery);

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
            DeliveryMode mode = "DIRECT".equalsIgnoreCase(deliveryMode)
                ? DeliveryMode.DIRECT
                : DeliveryMode.PERSISTENT;

            int messageCount = 0;
            while (rs.next()) {
                String content = rs.getString(messageColIndex);
                String correlationId = correlationColIndex > 0 ? rs.getString(correlationColIndex) : null;

                messageCount++;

                if (dryRun) {
                    System.out.println("Message " + messageCount + ":");
                    System.out.println("  Content: " + truncate(content, 100));
                    if (correlationId != null) {
                        System.out.println("  Correlation ID: " + correlationId);
                    }
                } else {
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
                    System.out.println("Published message " + messageCount + " to queue '" + solaceConnection.queue + "'");
                }
            }

            rs.close();
            stmt.close();

            if (dryRun) {
                System.out.println("\nDry run complete. Found " + messageCount + " message(s) to publish.");
            } else {
                System.out.println("\nSuccessfully published " + messageCount + " message(s) from Oracle to Solace");
            }

            return 0;

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
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
}
