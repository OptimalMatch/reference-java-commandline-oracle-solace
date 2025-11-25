package com.example.solace;

import com.solacesystems.jcsmp.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.File;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

@Command(
    name = "copy-queue",
    aliases = {"copy", "cp"},
    description = "Copy messages from one queue to another (optionally moving them)",
    mixinStandardHelpOptions = true
)
public class CopyQueueCommand implements Callable<Integer> {

    @Mixin
    ConnectionOptions connection;

    @Option(names = {"--dest", "-d"},
            description = "Destination queue name",
            required = true)
    String destinationQueue;

    @Option(names = {"--count", "-c"},
            description = "Maximum number of messages to copy (0 = all available)",
            defaultValue = "0")
    int maxCount;

    @Option(names = {"--timeout", "-t"},
            description = "Timeout in seconds waiting for messages (0 = no wait)",
            defaultValue = "5")
    int timeout;

    @Option(names = {"--move"},
            description = "Move messages (acknowledge after copying) instead of just browsing")
    boolean moveMessages;

    @Option(names = {"--preserve-properties"},
            description = "Preserve message properties (correlation ID, TTL, etc.)")
    boolean preserveProperties;

    @Option(names = {"--delivery-mode"},
            description = "Delivery mode for destination: PERSISTENT or DIRECT (default: preserve original)",
            defaultValue = "")
    String deliveryMode;

    @Option(names = {"--dry-run"},
            description = "Show what would be copied without actually copying")
    boolean dryRun;

    @Option(names = {"--exclude-file"},
            description = "File containing patterns to exclude (checks content and correlation ID)")
    File excludeFile;

    @Option(names = {"--exclude-content"},
            description = "Also check message content against exclusion patterns")
    boolean excludeByContent;

    private ExclusionList exclusionList;

    @Override
    public Integer call() {
        JCSMPSession session = null;
        XMLMessageProducer producer = null;
        FlowReceiver consumer = null;

        try {
            // Load exclusion list if specified
            if (excludeFile != null) {
                if (!excludeFile.exists()) {
                    System.err.println("Error: Exclude file not found: " + excludeFile.getAbsolutePath());
                    return 1;
                }
                exclusionList = ExclusionList.fromFile(excludeFile);
                System.out.println("Loaded " + exclusionList.size() + " exclusion pattern(s) from " + excludeFile.getName());
            }

            System.out.println("Connecting to " + connection.host + "...");
            session = SolaceConnection.createSession(connection);
            System.out.println("Connected successfully");

            Queue sourceQueue = JCSMPFactory.onlyInstance().createQueue(connection.queue);
            Queue destQueue = JCSMPFactory.onlyInstance().createQueue(destinationQueue);

            if (dryRun) {
                System.out.println("\n=== DRY RUN MODE - Messages will not be copied ===\n");
            }

            // Create producer for destination queue
            if (!dryRun) {
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
            }

            // Create consumer for source queue
            ConsumerFlowProperties flowProps = new ConsumerFlowProperties();
            flowProps.setEndpoint(sourceQueue);
            // Use AUTO_ACK if moving, CLIENT_ACK otherwise (for browse-like behavior)
            flowProps.setAckMode(moveMessages ? JCSMPProperties.SUPPORTED_MESSAGE_ACK_AUTO : JCSMPProperties.SUPPORTED_MESSAGE_ACK_CLIENT);

            final AtomicInteger messageCount = new AtomicInteger(0);
            final AtomicInteger excludedCount = new AtomicInteger(0);
            final AtomicBoolean done = new AtomicBoolean(false);
            final CountDownLatch latch = new CountDownLatch(1);
            final XMLMessageProducer finalProducer = producer;
            final int maxMessages = maxCount > 0 ? maxCount : Integer.MAX_VALUE;
            final ExclusionList finalExclusionList = exclusionList;
            final boolean checkContent = excludeByContent;

            consumer = session.createFlow(new XMLMessageListener() {
                @Override
                public void onReceive(BytesXMLMessage message) {
                    if (done.get()) return;

                    int count = -1;
                    try {
                        String content = "";
                        if (message instanceof TextMessage) {
                            content = ((TextMessage) message).getText();
                        } else if (message instanceof BytesMessage) {
                            byte[] data = ((BytesMessage) message).getData();
                            content = new String(data);
                        }

                        // Check exclusion
                        String correlationId = message.getCorrelationId();
                        if (shouldExcludeMessage(finalExclusionList, content, correlationId, checkContent)) {
                            excludedCount.incrementAndGet();
                            return;
                        }

                        count = messageCount.incrementAndGet();
                        if (count > maxMessages) {
                            done.set(true);
                            latch.countDown();
                            return;
                        }

                        if (dryRun) {
                            System.out.println("Message " + count + ":");
                            System.out.println("  Content: " + truncate(content, 80));
                            if (message.getCorrelationId() != null) {
                                System.out.println("  Correlation ID: " + message.getCorrelationId());
                            }
                            System.out.println("  Delivery Mode: " + message.getDeliveryMode());
                        } else {
                            // Create new message for destination
                            TextMessage newMsg = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
                            newMsg.setText(content);

                            // Set delivery mode
                            if (!deliveryMode.isEmpty()) {
                                newMsg.setDeliveryMode("DIRECT".equalsIgnoreCase(deliveryMode)
                                    ? DeliveryMode.DIRECT
                                    : DeliveryMode.PERSISTENT);
                            } else {
                                newMsg.setDeliveryMode(message.getDeliveryMode());
                            }

                            // Preserve properties if requested
                            if (preserveProperties) {
                                if (message.getCorrelationId() != null) {
                                    newMsg.setCorrelationId(message.getCorrelationId());
                                }
                                if (message.getTimeToLive() > 0) {
                                    newMsg.setTimeToLive(message.getTimeToLive());
                                }
                            }

                            finalProducer.send(newMsg, destQueue);
                            System.out.println("Copied message " + count + " to '" + destinationQueue + "'");
                        }

                        // If not moving, we need to manually ack to continue receiving
                        // but we won't actually remove the message from source
                        if (!moveMessages && !dryRun) {
                            // Don't ack - message stays in source queue
                        }

                    } catch (Exception e) {
                        System.err.println("Error processing message: " + e.getMessage());
                    }

                    if (count >= 0 && count >= maxMessages) {
                        done.set(true);
                        latch.countDown();
                    }
                }

                @Override
                public void onException(JCSMPException e) {
                    System.err.println("Consumer exception: " + e.getMessage());
                    done.set(true);
                    latch.countDown();
                }
            }, flowProps);

            System.out.println("Copying from '" + connection.queue + "' to '" + destinationQueue + "'...");
            if (moveMessages) {
                System.out.println("Mode: MOVE (messages will be removed from source)");
            } else {
                System.out.println("Mode: COPY (messages remain in source queue)");
            }

            consumer.start();

            // Wait for messages or timeout
            if (timeout > 0) {
                Thread.sleep(timeout * 1000L);
            } else if (maxCount > 0) {
                // Wait until we've processed maxCount messages or a reasonable timeout
                long waitTime = Math.min(maxCount * 100L, 60000L);
                Thread.sleep(waitTime);
            }

            done.set(true);
            consumer.stop();

            int copied = Math.min(messageCount.get(), maxMessages);
            int excluded = excludedCount.get();
            String excludedInfo = excluded > 0 ? " (" + excluded + " excluded)" : "";
            if (dryRun) {
                System.out.println("\nDry run complete. Found " + copied + " message(s) to copy" + excludedInfo + ".");
            } else {
                String action = moveMessages ? "moved" : "copied";
                System.out.println("\nSuccessfully " + action + " " + copied + " message(s) from '" + connection.queue + "' to '" + destinationQueue + "'" + excludedInfo);
            }

            return 0;

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            return 1;
        } finally {
            if (consumer != null) {
                consumer.close();
            }
            if (producer != null) {
                producer.close();
            }
            if (session != null) {
                session.closeSession();
            }
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "null";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    /**
     * Check if a message should be excluded based on correlation ID and optionally content.
     */
    private static boolean shouldExcludeMessage(ExclusionList list, String content, String correlationId, boolean checkContent) {
        if (list == null || list.isEmpty()) {
            return false;
        }
        // Check correlation ID
        if (correlationId != null && list.isExcluded(correlationId)) {
            return true;
        }
        // Check content if enabled
        if (checkContent && content != null && list.containsExcluded(content)) {
            return true;
        }
        return false;
    }
}
