package com.example.solace;

import com.solacesystems.jcsmp.*;
import com.solacesystems.jcsmp.Destination;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static com.example.solace.AuditLogger.maskSensitive;

@Command(
    name = "publish",
    aliases = {"pub", "send"},
    description = "Publish a message to a Solace queue or topic",
    mixinStandardHelpOptions = true
)
public class PublishCommand implements Callable<Integer> {

    @Mixin
    ConnectionOptions connection;

    @Mixin
    AuditOptions auditOptions;

    @Parameters(index = "0", arity = "0..1",
            description = "Message content (reads from stdin if not provided)")
    String message;

    @Option(names = {"-f", "--file"},
            description = "Read message content from file")
    String inputFile;

    @Option(names = {"-c", "--count"},
            description = "Number of messages to send",
            defaultValue = "1")
    int count;

    @Option(names = {"--correlation-id"},
            description = "Correlation ID for the message")
    String correlationId;

    @Option(names = {"--delivery-mode"},
            description = "Delivery mode: PERSISTENT or DIRECT",
            defaultValue = "PERSISTENT")
    String deliveryMode;

    @Option(names = {"--ttl"},
            description = "Time to live in milliseconds (0 = no expiry)",
            defaultValue = "0")
    long ttl;

    @Option(names = {"--second-queue", "-Q"},
            description = "Also publish to this second queue (fan-out)")
    String secondQueue;

    @Option(names = {"-T", "--topic"},
            description = "Publish to a topic instead of the queue specified by -q")
    String topic;

    @Option(names = {"--failed-dir"},
            description = "Directory to save failed messages for later retry")
    File failedDir;

    @Option(names = {"--retry-dir"},
            description = "Directory containing previously failed messages to retry")
    File retryDir;

    @Override
    public Integer call() {
        JCSMPSession session = null;
        XMLMessageProducer producer = null;
        AuditLogger audit = AuditLogger.create(auditOptions, "publish");
        FailedMessageHandler failedHandler = new FailedMessageHandler(failedDir, retryDir);

        // Track progress for partial failure reporting
        int successCount = 0;
        int failedCount = 0;
        int retryCount = 0;
        int retrySuccessCount = 0;
        List<String> failedMessageIds = new ArrayList<>();

        // Log parameters (mask sensitive values)
        audit.addParameter("host", connection.host)
             .addParameter("vpn", connection.vpn)
             .addParameter("username", connection.username)
             .addParameter("password", maskSensitive(connection.password))
             .addParameter("queue", connection.queue)
             .addParameter("topic", topic)
             .addParameter("count", count)
             .addParameter("deliveryMode", deliveryMode)
             .addParameter("ttl", ttl)
             .addParameter("inputFile", inputFile)
             .addParameter("secondQueue", secondQueue)
             .addParameter("correlationId", correlationId)
             .addParameter("failedDir", failedDir != null ? failedDir.getAbsolutePath() : null)
             .addParameter("retryDir", retryDir != null ? retryDir.getAbsolutePath() : null);

        try {
            // Initialize failed message directory if specified
            if (!failedHandler.initFailedDir()) {
                audit.setError("Failed to initialize failed message directory");
                audit.logCompletion(1);
                return 1;
            }

            // Validate retry directory if specified
            if (!failedHandler.validateRetryDir()) {
                audit.setError("Invalid retry directory");
                audit.logCompletion(1);
                return 1;
            }

            // Load retry messages if retry directory specified
            List<FailedMessageHandler.FailedMessage> retryMessages = failedHandler.loadRetryMessages();
            if (!retryMessages.isEmpty()) {
                System.out.println("Found " + retryMessages.size() + " message(s) to retry from " + retryDir.getAbsolutePath());
                retryCount = retryMessages.size();
            }

            String content = resolveMessageContent();
            boolean hasNewContent = content != null && !content.isEmpty();

            // Need either new content or retry messages
            if (!hasNewContent && retryMessages.isEmpty()) {
                System.err.println("Error: No message content provided and no retry messages found");
                audit.setError("No message content provided");
                audit.logCompletion(1);
                return 1;
            }

            System.out.println("Connecting to " + connection.host + "...");
            session = SolaceConnection.createSession(connection);
            System.out.println("Connected successfully");

            // Determine primary destination: topic takes precedence if specified
            Destination destination;
            String destinationName;
            String destinationType;
            if (topic != null) {
                destination = JCSMPFactory.onlyInstance().createTopic(topic);
                destinationName = topic;
                destinationType = "topic";
            } else {
                destination = JCSMPFactory.onlyInstance().createQueue(connection.queue);
                destinationName = connection.queue;
                destinationType = "queue";
            }
            Queue queue2 = (secondQueue != null) ? JCSMPFactory.onlyInstance().createQueue(secondQueue) : null;

            producer = session.getMessageProducer(new JCSMPStreamingPublishCorrelatingEventHandler() {
                @Override
                public void responseReceivedEx(Object key) {
                    // Message acknowledged
                }

                @Override
                public void handleErrorEx(Object key, JCSMPException cause, long timestamp) {
                    System.err.println("Error publishing message: " + cause.getMessage());
                }
            });

            DeliveryMode mode = "DIRECT".equalsIgnoreCase(deliveryMode)
                ? DeliveryMode.DIRECT
                : DeliveryMode.PERSISTENT;

            // Calculate total operations for progress
            int totalNewMessages = hasNewContent ? count : 0;
            int totalOps = totalNewMessages + retryMessages.size();
            if (queue2 != null) {
                totalOps *= 2;
            }

            // Use progress reporter for larger batches
            ProgressReporter progress = null;
            boolean showProgress = totalOps > 10;
            if (showProgress) {
                progress = new ProgressReporter("Publishing", totalOps, 2);
                progress.start();
            }

            // First, process retry messages
            for (FailedMessageHandler.FailedMessage retryMsg : retryMessages) {
                String msgCorrelationId = retryMsg.getCorrelationId();
                String msgContent = retryMsg.getContent();

                try {
                    TextMessage msg = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
                    msg.setText(msgContent);
                    msg.setDeliveryMode(mode);

                    if (msgCorrelationId != null) {
                        msg.setCorrelationId(msgCorrelationId);
                    }

                    if (ttl > 0) {
                        msg.setTimeToLive(ttl);
                    }

                    producer.send(msg, destination);
                    retrySuccessCount++;

                    if (showProgress) {
                        progress.increment();
                    } else {
                        System.out.println("Retried message to " + destinationType + " '" + destinationName + "'" +
                            (msgCorrelationId != null ? " [" + msgCorrelationId + "]" : ""));
                    }

                    if (queue2 != null) {
                        producer.send(msg, queue2);
                        if (showProgress) {
                            progress.increment();
                        }
                    }

                    // Mark as successfully retried (delete from retry dir)
                    failedHandler.markRetrySuccess(retryMsg);

                } catch (Exception e) {
                    failedCount++;
                    String msgId = msgCorrelationId != null ? msgCorrelationId : "retry-" + failedCount;
                    failedMessageIds.add(msgId);
                    System.err.println("Failed to retry message: " + e.getMessage());

                    // Save to failed dir if enabled (message stays for next retry attempt)
                    failedHandler.saveFailedMessage(msgContent, msgCorrelationId, destinationName,
                        e.getMessage(), failedCount);
                }
            }

            // Then, process new messages
            if (hasNewContent) {
                for (int i = 0; i < count; i++) {
                    String msgCorrelationId = correlationId;
                    // Generate unique correlation ID for batch messages if not specified
                    if (msgCorrelationId == null && count > 1) {
                        msgCorrelationId = "msg-" + System.currentTimeMillis() + "-" + i;
                    }

                    try {
                        TextMessage msg = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
                        msg.setText(content);
                        msg.setDeliveryMode(mode);

                        if (msgCorrelationId != null) {
                            msg.setCorrelationId(msgCorrelationId);
                        }

                        if (ttl > 0) {
                            msg.setTimeToLive(ttl);
                        }

                        producer.send(msg, destination);
                        successCount++;

                        if (showProgress) {
                            progress.increment();
                        } else {
                            String msgId = (count > 1) ? " [" + (i + 1) + "/" + count + "]" : "";
                            System.out.println("Published message to " + destinationType + " '" + destinationName + "'" + msgId);
                        }

                        if (queue2 != null) {
                            producer.send(msg, queue2);
                            if (showProgress) {
                                progress.increment();
                            } else {
                                String msgId = (count > 1) ? " [" + (i + 1) + "/" + count + "]" : "";
                                System.out.println("Published message to queue '" + secondQueue + "'" + msgId);
                            }
                        }

                    } catch (Exception e) {
                        failedCount++;
                        String msgId = msgCorrelationId != null ? msgCorrelationId : String.valueOf(i);
                        failedMessageIds.add(msgId);

                        if (showProgress) {
                            progress.incrementError();
                        } else {
                            System.err.println("Failed to publish message [" + (i + 1) + "]: " + e.getMessage());
                        }

                        // Save failed message if directory specified
                        failedHandler.saveFailedMessage(content, msgCorrelationId, destinationName,
                            e.getMessage(), i);
                    }
                }
            }

            if (showProgress) {
                progress.stop();
                progress.printSummary();
            }

            // Print summary
            int totalSuccess = successCount + retrySuccessCount;
            int totalMessages = (queue2 != null) ? totalSuccess * 2 : totalSuccess;
            String destInfo = (queue2 != null) ? " to " + destinationType + " and second queue" : "";

            if (failedCount == 0) {
                System.out.println("Successfully published " + totalMessages + " message(s)" + destInfo);
            } else {
                System.out.println("Published " + totalMessages + " message(s)" + destInfo +
                    ", " + failedCount + " failed");
                if (failedHandler.isSaveEnabled()) {
                    System.out.println("Failed messages saved to: " + failedDir.getAbsolutePath());
                }
            }

            if (retryCount > 0) {
                System.out.println("Retry results: " + retrySuccessCount + "/" + retryCount + " succeeded");
            }

            // Log results with partial progress
            audit.addResult("messagesAttempted", (hasNewContent ? count : 0) + retryCount)
                 .addResult("messagesPublished", totalSuccess)
                 .addResult("messagesFailed", failedCount)
                 .addResult("retryAttempted", retryCount)
                 .addResult("retrySucceeded", retrySuccessCount)
                 .addResult("destinationType", destinationType)
                 .addResult("destinationsUsed", (queue2 != null) ? 2 : 1);

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
            audit.addResult("messagesPublished", successCount + retrySuccessCount)
                 .addResult("messagesFailed", failedCount)
                 .addResult("retrySucceeded", retrySuccessCount);

            if (!failedMessageIds.isEmpty()) {
                audit.addResult("failedMessageIds", failedMessageIds);
            }

            audit.setError(e.getMessage());
            audit.logCompletion(1);
            return 1;
        } finally {
            if (producer != null) {
                producer.close();
            }
            if (session != null) {
                session.closeSession();
            }
        }
    }

    private String resolveMessageContent() throws Exception {
        if (inputFile != null) {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(inputFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(line);
                }
            }
            return sb.toString();
        }

        if (message != null) {
            return message;
        }

        // Read from stdin if available
        if (System.in.available() > 0) {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(line);
                }
            }
            return sb.toString();
        }

        return null;
    }
}
