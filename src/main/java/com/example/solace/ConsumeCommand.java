package com.example.solace;

import com.solacesystems.jcsmp.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Command(
    name = "consume",
    aliases = {"sub", "receive"},
    description = "Consume messages from a Solace queue",
    mixinStandardHelpOptions = true
)
public class ConsumeCommand implements Callable<Integer> {

    @Mixin
    ConnectionOptions connection;

    @Option(names = {"-n", "--count"},
            description = "Number of messages to consume (0 = unlimited)",
            defaultValue = "0")
    int maxMessages;

    @Option(names = {"-t", "--timeout"},
            description = "Timeout in seconds to wait for messages (0 = wait forever)",
            defaultValue = "0")
    int timeout;

    @Option(names = {"--browse"},
            description = "Browse messages without consuming (non-destructive)")
    boolean browseOnly;

    @Option(names = {"--no-ack"},
            description = "Don't acknowledge messages (use with caution)")
    boolean noAck;

    @Option(names = {"--verbose", "-V"},
            description = "Show message metadata")
    boolean verbose;

    @Option(names = {"--output-dir", "-o"},
            description = "Write message payloads to files in this directory")
    File outputDir;

    @Option(names = {"--extension", "-e"},
            description = "File extension for output files (default: .txt)",
            defaultValue = ".txt")
    String fileExtension;

    @Option(names = {"--prefix"},
            description = "Prefix for output filenames (default: message_)",
            defaultValue = "message_")
    String filenamePrefix;

    @Option(names = {"--use-correlation-id"},
            description = "Use correlation ID as filename when available")
    boolean useCorrelationId;

    @Option(names = {"--use-message-id"},
            description = "Use message ID as filename when available")
    boolean useMessageId;

    @Option(names = {"--exclude-file"},
            description = "File containing patterns to exclude (one per line)")
    File excludeFile;

    @Option(names = {"--exclude-content"},
            description = "Exclude messages whose content matches patterns in exclude file")
    boolean excludeByContent;

    private final AtomicInteger messageCount = new AtomicInteger(0);
    private final AtomicInteger excludedCount = new AtomicInteger(0);
    private volatile boolean running = true;
    private CountDownLatch completionLatch;
    private ExclusionList exclusionList;

    @Override
    public Integer call() {
        JCSMPSession session = null;
        FlowReceiver flowReceiver = null;
        Browser browser = null;

        try {
            // Validate and create output directory if specified
            if (outputDir != null) {
                if (!outputDir.exists()) {
                    System.out.println("Creating output directory: " + outputDir.getAbsolutePath());
                    if (!outputDir.mkdirs()) {
                        System.err.println("Error: Failed to create output directory: " + outputDir.getAbsolutePath());
                        return 1;
                    }
                }
                if (!outputDir.isDirectory()) {
                    System.err.println("Error: Output path is not a directory: " + outputDir.getAbsolutePath());
                    return 1;
                }
            }

            // Normalize file extension
            if (!fileExtension.startsWith(".")) {
                fileExtension = "." + fileExtension;
            }

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

            Queue queue = JCSMPFactory.onlyInstance().createQueue(connection.queue);

            if (browseOnly) {
                return browsMessages(session, queue);
            }

            completionLatch = new CountDownLatch(1);
            
            ConsumerFlowProperties flowProps = new ConsumerFlowProperties();
            flowProps.setEndpoint(queue);
            flowProps.setAckMode(noAck 
                ? JCSMPProperties.SUPPORTED_MESSAGE_ACK_CLIENT 
                : JCSMPProperties.SUPPORTED_MESSAGE_ACK_AUTO);

            final JCSMPSession finalSession = session;
            
            flowReceiver = session.createFlow(new XMLMessageListener() {
                @Override
                public void onReceive(BytesXMLMessage message) {
                    // Check exclusion before processing
                    if (shouldExclude(message)) {
                        excludedCount.incrementAndGet();
                        if (!noAck) {
                            message.ackMessage();
                        }
                        return;
                    }

                    processMessage(message);

                    if (!noAck) {
                        message.ackMessage();
                    }

                    int count = messageCount.incrementAndGet();
                    if (maxMessages > 0 && count >= maxMessages) {
                        running = false;
                        completionLatch.countDown();
                    }
                }

                @Override
                public void onException(JCSMPException e) {
                    System.err.println("Consumer error: " + e.getMessage());
                    running = false;
                    completionLatch.countDown();
                }
            }, flowProps);

            flowReceiver.start();
            System.out.println("Consuming from queue '" + connection.queue + "'...");
            System.out.println("Press Ctrl+C to stop\n");

            // Set up shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running = false;
                completionLatch.countDown();
            }));

            // Start progress reporter for continuous feedback
            ProgressReporter progress = (maxMessages > 0)
                ? new ProgressReporter("Consuming", maxMessages, 5)
                : new ProgressReporter("Consuming", 5);
            progress.start();

            // Update progress in a background thread
            Thread progressUpdater = new Thread(() -> {
                int lastCount = 0;
                while (running) {
                    try {
                        Thread.sleep(100);
                        int currentCount = messageCount.get();
                        if (currentCount > lastCount) {
                            progress.incrementBy(currentCount - lastCount);
                            lastCount = currentCount;
                        }
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            });
            progressUpdater.setDaemon(true);
            progressUpdater.start();

            // Wait for completion
            if (timeout > 0) {
                boolean completed = completionLatch.await(timeout, TimeUnit.SECONDS);
                if (!completed) {
                    System.out.println("\nTimeout reached");
                }
            } else {
                completionLatch.await();
            }

            running = false;
            progress.stop();

            System.out.println("\nConsumed " + messageCount.get() + " message(s)");
            if (excludedCount.get() > 0) {
                System.out.println("Excluded " + excludedCount.get() + " message(s)");
            }
            return 0;

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            return 1;
        } finally {
            if (flowReceiver != null) {
                flowReceiver.stop();
                flowReceiver.close();
            }
            if (browser != null) {
                browser.close();
            }
            if (session != null) {
                session.closeSession();
            }
        }
    }

    private int browsMessages(JCSMPSession session, Queue queue) throws JCSMPException {
        BrowserProperties browserProps = new BrowserProperties();
        browserProps.setEndpoint(queue);
        browserProps.setWaitTimeout(timeout > 0 ? timeout * 1000 : 5000);

        Browser browser = session.createBrowser(browserProps);
        System.out.println("Browsing queue '" + connection.queue + "' (non-destructive)...\n");

        BytesXMLMessage message;
        int count = 0;
        int excluded = 0;
        while ((message = browser.getNext()) != null) {
            if (shouldExclude(message)) {
                excluded++;
                continue;
            }
            processMessage(message);
            count++;
            if (maxMessages > 0 && count >= maxMessages) {
                break;
            }
        }

        System.out.println("\nBrowsed " + count + " message(s)");
        if (excluded > 0) {
            System.out.println("Excluded " + excluded + " message(s)");
        }
        browser.close();
        return 0;
    }

    private void processMessage(BytesXMLMessage message) {
        System.out.println("------------------------------------------------------------");
        
        if (verbose) {
            System.out.println("Message ID: " + message.getMessageId());
            System.out.println("Correlation ID: " + message.getCorrelationId());
            System.out.println("Delivery Mode: " + message.getDeliveryMode());
            System.out.println("Priority: " + message.getPriority());
            System.out.println("Redelivered: " + message.getRedelivered());
            System.out.println("Timestamp: " + message.getSenderTimestamp());
            if (message.getExpiration() > 0) {
                System.out.println("Expiration: " + message.getExpiration());
            }
            System.out.println();
        }

        String content = extractContent(message);
        System.out.println("Payload:");
        System.out.println(content);

        if (outputDir != null) {
            writeToFile(message, content);
        }
    }

    private String extractContent(BytesXMLMessage message) {
        if (message instanceof TextMessage) {
            return ((TextMessage) message).getText();
        } else if (message instanceof BytesMessage) {
            byte[] data = ((BytesMessage) message).getData();
            return data != null ? new String(data) : "<empty>";
        } else if (message instanceof StreamMessage) {
            return "<stream message>";
        } else if (message instanceof MapMessage) {
            return "<map message>";
        } else if (message instanceof XMLContentMessage) {
            return ((XMLContentMessage) message).getXMLContent();
        }
        return "<unknown message type>";
    }

    private void writeToFile(BytesXMLMessage message, String content) {
        try {
            String filename = generateFilename(message);
            File outputFile = new File(outputDir, filename);

            try (Writer writer = new OutputStreamWriter(
                    new FileOutputStream(outputFile), StandardCharsets.UTF_8)) {
                if (content != null) {
                    writer.write(content);
                }
            }

            System.out.println("Written to: " + outputFile.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("Failed to write file: " + e.getMessage());
        }
    }

    private String generateFilename(BytesXMLMessage message) {
        String baseName = null;

        // Try to use correlation ID if requested
        if (useCorrelationId && message.getCorrelationId() != null && !message.getCorrelationId().isEmpty()) {
            baseName = sanitizeFilename(message.getCorrelationId());
        }
        // Try to use message ID if requested
        else if (useMessageId && message.getMessageId() != null) {
            baseName = sanitizeFilename(message.getMessageId().toString());
        }

        // Fall back to sequential numbering
        if (baseName == null || baseName.isEmpty()) {
            baseName = filenamePrefix + String.format("%06d", messageCount.get() + 1);
        }

        return baseName + fileExtension;
    }

    /**
     * Sanitize a string to be used as a filename.
     * Removes or replaces characters that are invalid in filenames.
     */
    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isEmpty()) {
            return null;
        }
        // Replace invalid characters with underscore
        String sanitized = filename.replaceAll("[\\\\/:*?\"<>|\\s]", "_");
        // Remove leading/trailing dots and spaces
        sanitized = sanitized.replaceAll("^[.\\s]+|[.\\s]+$", "");
        // Limit length
        if (sanitized.length() > 200) {
            sanitized = sanitized.substring(0, 200);
        }
        return sanitized.isEmpty() ? null : sanitized;
    }

    /**
     * Check if a message should be excluded based on exclusion patterns.
     */
    private boolean shouldExclude(BytesXMLMessage message) {
        if (exclusionList == null || exclusionList.isEmpty()) {
            return false;
        }

        // Check correlation ID against exclusion patterns
        String correlationId = message.getCorrelationId();
        if (correlationId != null && exclusionList.isExcluded(correlationId)) {
            return true;
        }

        // Check content if --exclude-content is specified
        if (excludeByContent) {
            String content = extractContent(message);
            if (content != null && exclusionList.containsExcluded(content)) {
                return true;
            }
        }

        return false;
    }
}
