package com.example.solace;

import com.solacesystems.jcsmp.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.File;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.example.solace.AuditLogger.maskSensitive;

@Command(
    name = "perf-test",
    aliases = {"perf", "benchmark"},
    description = "Run performance tests against Solace broker",
    mixinStandardHelpOptions = true
)
public class PerfTestCommand implements Callable<Integer> {

    @Mixin
    ConnectionOptions connection;

    @Mixin
    AuditOptions auditOptions;

    @Option(names = {"--mode", "-m"},
            description = "Test mode: publish, consume, or both (default: publish)",
            defaultValue = "publish")
    String mode;

    @Option(names = {"--count", "-c"},
            description = "Number of messages to send/receive (default: 1000)",
            defaultValue = "1000")
    int messageCount;

    @Option(names = {"--size", "-s"},
            description = "Message size in bytes (default: 100)",
            defaultValue = "100")
    int messageSize;

    @Option(names = {"--rate", "-r"},
            description = "Target messages per second (0 = unlimited)",
            defaultValue = "0")
    int targetRate;

    @Option(names = {"--warmup"},
            description = "Number of warmup messages before measuring (default: 100)",
            defaultValue = "100")
    int warmupCount;

    @Option(names = {"--threads", "-t"},
            description = "Number of publisher threads (default: 1)",
            defaultValue = "1")
    int threadCount;

    @Option(names = {"--delivery-mode"},
            description = "Delivery mode: PERSISTENT or DIRECT (default: PERSISTENT)",
            defaultValue = "PERSISTENT")
    String deliveryMode;

    @Option(names = {"--report-interval"},
            description = "Progress report interval in seconds (default: 5)",
            defaultValue = "5")
    int reportInterval;

    @Option(names = {"--latency"},
            description = "Measure end-to-end latency (requires both publish and consume)")
    boolean measureLatency;

    @Option(names = {"--exclude-file"},
            description = "File containing exclusion patterns (tests exclusion filtering overhead)")
    File excludeFile;

    @Option(names = {"--exclude-rate"},
            description = "Percentage of messages that should match exclusion patterns (0-100, default: 10)",
            defaultValue = "10")
    int excludeRate;

    @Option(names = {"--exclude-content"},
            description = "Also check message content against exclusion patterns")
    boolean excludeByContent;

    private ExclusionList exclusionList;

    // Statistics
    private final AtomicInteger sentCount = new AtomicInteger(0);
    private final AtomicInteger receivedCount = new AtomicInteger(0);
    private final AtomicInteger excludedCount = new AtomicInteger(0);
    private final AtomicInteger errorCount = new AtomicInteger(0);
    private final AtomicLong totalSendTime = new AtomicLong(0);
    private final AtomicLong totalExclusionCheckTime = new AtomicLong(0);
    private final List<Long> latencies = Collections.synchronizedList(new ArrayList<Long>());

    // For generating excludable messages
    private static final String EXCLUDE_MARKER = "EXCLUDE-TEST-";

    private volatile boolean running = true;
    private long testStartTime;
    private long testEndTime;

    private static final DecimalFormat df = new DecimalFormat("#,###.##");

    @Override
    public Integer call() {
        AuditLogger audit = AuditLogger.create(auditOptions, "perf-test");

        // Log parameters (mask sensitive values)
        audit.addParameter("host", connection.host)
             .addParameter("vpn", connection.vpn)
             .addParameter("username", connection.username)
             .addParameter("password", maskSensitive(connection.password))
             .addParameter("queue", connection.queue)
             .addParameter("mode", mode)
             .addParameter("messageCount", messageCount)
             .addParameter("messageSize", messageSize)
             .addParameter("targetRate", targetRate)
             .addParameter("threadCount", threadCount)
             .addParameter("deliveryMode", deliveryMode);

        System.out.println(repeatChar('=', 60));
        System.out.println("Solace Performance Test");
        System.out.println(repeatChar('=', 60));
        System.out.println();

        printTestConfiguration();

        try {
            // Load exclusion list if specified
            if (excludeFile != null) {
                if (!excludeFile.exists()) {
                    System.err.println("Error: Exclude file not found: " + excludeFile.getAbsolutePath());
                    audit.setError("Exclude file not found").logCompletion(1);
                    return 1;
                }
                exclusionList = ExclusionList.fromFile(excludeFile);
                System.out.println("Loaded " + exclusionList.size() + " exclusion pattern(s)");
                System.out.println("Exclude rate: " + excludeRate + "% of messages will match patterns");
                System.out.println();
            }

            int result;
            switch (mode.toLowerCase()) {
                case "publish":
                    result = runPublishTest();
                    break;
                case "consume":
                    result = runConsumeTest();
                    break;
                case "both":
                    result = runBidirectionalTest();
                    break;
                default:
                    System.err.println("Unknown mode: " + mode);
                    System.err.println("Valid modes: publish, consume, both");
                    audit.setError("Unknown mode: " + mode).logCompletion(1);
                    return 1;
            }

            // Log results
            long durationMs = (testEndTime - testStartTime) / 1_000_000;
            audit.addResult("messagesSent", sentCount.get())
                 .addResult("messagesReceived", receivedCount.get())
                 .addResult("messagesExcluded", excludedCount.get())
                 .addResult("errors", errorCount.get())
                 .addResult("durationMs", durationMs);
            audit.logCompletion(result);
            return result;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            audit.setError(e.getMessage()).logCompletion(1);
            return 1;
        }
    }

    private void printTestConfiguration() {
        System.out.println("Configuration:");
        System.out.println("  Mode:           " + mode);
        System.out.println("  Messages:       " + df.format(messageCount));
        System.out.println("  Message size:   " + df.format(messageSize) + " bytes");
        System.out.println("  Delivery mode:  " + deliveryMode);
        System.out.println("  Target rate:    " + (targetRate == 0 ? "unlimited" : df.format(targetRate) + " msg/s"));
        System.out.println("  Threads:        " + threadCount);
        System.out.println("  Warmup:         " + df.format(warmupCount) + " messages");
        System.out.println("  Host:           " + connection.host);
        System.out.println("  Queue:          " + connection.queue);
        if (excludeFile != null) {
            System.out.println("  Exclusion:      " + excludeFile.getName() + " (" + excludeRate + "% match rate)");
            System.out.println("  Check content:  " + excludeByContent);
        }
        System.out.println();
    }

    private int runPublishTest() throws Exception {
        System.out.println("Starting publish performance test...");
        System.out.println();

        JCSMPSession session = SolaceConnection.createSession(connection);
        System.out.println("Connected to Solace");

        XMLMessageProducer producer = session.getMessageProducer(new JCSMPStreamingPublishCorrelatingEventHandler() {
            @Override
            public void responseReceivedEx(Object key) {
                // Message acknowledged
            }

            @Override
            public void handleErrorEx(Object key, JCSMPException cause, long timestamp) {
                errorCount.incrementAndGet();
            }
        });

        Queue queue = JCSMPFactory.onlyInstance().createQueue(connection.queue);
        DeliveryMode delMode = "DIRECT".equalsIgnoreCase(deliveryMode)
            ? DeliveryMode.DIRECT
            : DeliveryMode.PERSISTENT;

        // Generate test payload
        String payload = generatePayload(messageSize);
        String excludePayload = EXCLUDE_MARKER + payload;
        Random random = new Random();

        // Warmup phase
        if (warmupCount > 0) {
            System.out.println("Warmup: sending " + warmupCount + " messages...");
            for (int i = 0; i < warmupCount; i++) {
                TextMessage msg = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
                msg.setText(payload);
                msg.setDeliveryMode(delMode);
                producer.send(msg, queue);
            }
            System.out.println("Warmup complete");
            System.out.println();
        }

        // Start progress reporter
        Thread reporter = startProgressReporter("Sent");

        // Run test
        System.out.println("Starting measurement...");
        testStartTime = System.nanoTime();

        long intervalNanos = targetRate > 0 ? 1_000_000_000L / targetRate : 0;
        long nextSendTime = System.nanoTime();

        for (int i = 0; i < messageCount && running; i++) {
            // Rate limiting
            if (intervalNanos > 0) {
                while (System.nanoTime() < nextSendTime) {
                    // Busy wait for precise timing
                }
                nextSendTime += intervalNanos;
            }

            long sendStart = System.nanoTime();
            TextMessage msg = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);

            // Add exclusion marker based on excludeRate
            boolean shouldMark = exclusionList != null && random.nextInt(100) < excludeRate;
            String msgPayload = shouldMark ? excludePayload : payload;
            msg.setText(msgPayload);
            msg.setDeliveryMode(delMode);

            if (measureLatency) {
                msg.setCorrelationId(String.valueOf(sendStart));
            } else if (shouldMark) {
                msg.setCorrelationId(EXCLUDE_MARKER + i);
            }

            producer.send(msg, queue);
            totalSendTime.addAndGet(System.nanoTime() - sendStart);
            sentCount.incrementAndGet();
        }

        testEndTime = System.nanoTime();
        running = false;
        reporter.join(1000);

        // Print results
        printPublishResults();

        producer.close();
        session.closeSession();

        return errorCount.get() > 0 ? 1 : 0;
    }

    private int runConsumeTest() throws Exception {
        System.out.println("Starting consume performance test...");
        System.out.println("Waiting for " + messageCount + " messages...");
        System.out.println();

        JCSMPSession session = SolaceConnection.createSession(connection);
        System.out.println("Connected to Solace");

        CountDownLatch completionLatch = new CountDownLatch(1);
        Queue queue = JCSMPFactory.onlyInstance().createQueue(connection.queue);

        ConsumerFlowProperties flowProps = new ConsumerFlowProperties();
        flowProps.setEndpoint(queue);
        flowProps.setAckMode(JCSMPProperties.SUPPORTED_MESSAGE_ACK_AUTO);

        // Start progress reporter
        Thread reporter = startProgressReporter("Received");

        testStartTime = System.nanoTime();

        final ExclusionList finalExclusionList = exclusionList;
        final boolean checkContent = excludeByContent;

        FlowReceiver flowReceiver = session.createFlow(new XMLMessageListener() {
            @Override
            public void onReceive(BytesXMLMessage message) {
                // Perform exclusion check if enabled
                if (finalExclusionList != null) {
                    long checkStart = System.nanoTime();
                    boolean excluded = shouldExcludeMessage(finalExclusionList, message, checkContent);
                    totalExclusionCheckTime.addAndGet(System.nanoTime() - checkStart);

                    if (excluded) {
                        int excl = excludedCount.incrementAndGet();
                        // Check if we've processed all messages (received + excluded)
                        if (receivedCount.get() + excl >= messageCount) {
                            testEndTime = System.nanoTime();
                            running = false;
                            completionLatch.countDown();
                        }
                        return;
                    }
                }

                int count = receivedCount.incrementAndGet();

                if (measureLatency && message.getCorrelationId() != null) {
                    try {
                        long sendTime = Long.parseLong(message.getCorrelationId());
                        long latency = System.nanoTime() - sendTime;
                        latencies.add(latency);
                    } catch (NumberFormatException e) {
                        // Ignore invalid correlation IDs
                    }
                }

                // Check if we've processed all messages (received + excluded)
                if (count + excludedCount.get() >= messageCount) {
                    testEndTime = System.nanoTime();
                    running = false;
                    completionLatch.countDown();
                }
            }

            @Override
            public void onException(JCSMPException e) {
                errorCount.incrementAndGet();
            }
        }, flowProps);

        flowReceiver.start();

        // Wait for completion or timeout
        int timeoutSeconds = Math.max(60, messageCount / 100);
        boolean completed = completionLatch.await(timeoutSeconds, TimeUnit.SECONDS);

        running = false;
        reporter.join(1000);

        if (!completed) {
            System.out.println("\nTimeout waiting for messages");
            testEndTime = System.nanoTime();
        }

        // Print results
        printConsumeResults();

        flowReceiver.stop();
        flowReceiver.close();
        session.closeSession();

        return errorCount.get() > 0 || !completed ? 1 : 0;
    }

    private int runBidirectionalTest() throws Exception {
        System.out.println("Starting bidirectional performance test...");
        System.out.println();

        // Create two sessions - one for publishing, one for consuming
        JCSMPSession pubSession = SolaceConnection.createSession(connection);
        JCSMPSession subSession = SolaceConnection.createSession(connection);
        System.out.println("Connected to Solace (2 sessions)");

        CountDownLatch completionLatch = new CountDownLatch(1);
        Queue queue = JCSMPFactory.onlyInstance().createQueue(connection.queue);

        // Set up consumer first
        ConsumerFlowProperties flowProps = new ConsumerFlowProperties();
        flowProps.setEndpoint(queue);
        flowProps.setAckMode(JCSMPProperties.SUPPORTED_MESSAGE_ACK_AUTO);

        final ExclusionList finalExclusionList = exclusionList;
        final boolean checkContent = excludeByContent;

        FlowReceiver flowReceiver = subSession.createFlow(new XMLMessageListener() {
            @Override
            public void onReceive(BytesXMLMessage message) {
                // Perform exclusion check if enabled
                if (finalExclusionList != null) {
                    long checkStart = System.nanoTime();
                    boolean excluded = shouldExcludeMessage(finalExclusionList, message, checkContent);
                    totalExclusionCheckTime.addAndGet(System.nanoTime() - checkStart);

                    if (excluded) {
                        int excl = excludedCount.incrementAndGet();
                        // Check if we've processed all messages (received + excluded)
                        if (receivedCount.get() + excl >= messageCount) {
                            testEndTime = System.nanoTime();
                            running = false;
                            completionLatch.countDown();
                        }
                        return;
                    }
                }

                int count = receivedCount.incrementAndGet();

                if (measureLatency && message.getCorrelationId() != null) {
                    try {
                        long sendTime = Long.parseLong(message.getCorrelationId());
                        long latency = System.nanoTime() - sendTime;
                        latencies.add(latency);
                    } catch (NumberFormatException e) {
                        // Ignore
                    }
                }

                // Check if we've processed all messages (received + excluded)
                if (count + excludedCount.get() >= messageCount) {
                    testEndTime = System.nanoTime();
                    running = false;
                    completionLatch.countDown();
                }
            }

            @Override
            public void onException(JCSMPException e) {
                errorCount.incrementAndGet();
            }
        }, flowProps);

        flowReceiver.start();

        // Set up producer
        XMLMessageProducer producer = pubSession.getMessageProducer(new JCSMPStreamingPublishCorrelatingEventHandler() {
            @Override
            public void responseReceivedEx(Object key) {}

            @Override
            public void handleErrorEx(Object key, JCSMPException cause, long timestamp) {
                errorCount.incrementAndGet();
            }
        });

        DeliveryMode delMode = "DIRECT".equalsIgnoreCase(deliveryMode)
            ? DeliveryMode.DIRECT
            : DeliveryMode.PERSISTENT;

        String payload = generatePayload(messageSize);

        // Warmup
        if (warmupCount > 0) {
            System.out.println("Warmup: " + warmupCount + " messages...");
            for (int i = 0; i < warmupCount; i++) {
                TextMessage msg = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
                msg.setText(payload);
                msg.setDeliveryMode(delMode);
                producer.send(msg, queue);
            }
            // Wait for warmup messages to be consumed
            Thread.sleep(1000);
            receivedCount.set(0);
            latencies.clear();
            System.out.println("Warmup complete");
            System.out.println();
        }

        // Start progress reporter
        Thread reporter = startBidirectionalProgressReporter();

        // Start test
        System.out.println("Starting measurement...");
        testStartTime = System.nanoTime();

        long intervalNanos = targetRate > 0 ? 1_000_000_000L / targetRate : 0;
        long nextSendTime = System.nanoTime();

        // Random for generating excludable messages
        Random random = new Random();

        for (int i = 0; i < messageCount && running; i++) {
            if (intervalNanos > 0) {
                while (System.nanoTime() < nextSendTime) {
                    // Busy wait
                }
                nextSendTime += intervalNanos;
            }

            TextMessage msg = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);

            // Generate payload that may be excluded based on excludeRate
            String msgPayload = payload;
            String correlationId = String.valueOf(System.nanoTime());
            if (exclusionList != null && random.nextInt(100) < excludeRate) {
                // Make this message excludable by adding marker
                msgPayload = EXCLUDE_MARKER + payload;
                correlationId = EXCLUDE_MARKER + correlationId;
            }

            msg.setText(msgPayload);
            msg.setDeliveryMode(delMode);
            msg.setCorrelationId(correlationId);

            producer.send(msg, queue);
            sentCount.incrementAndGet();
        }

        // Wait for all messages to be consumed
        int timeoutSeconds = Math.max(60, messageCount / 100);
        boolean completed = completionLatch.await(timeoutSeconds, TimeUnit.SECONDS);

        running = false;
        reporter.join(1000);

        if (!completed) {
            System.out.println("\nTimeout waiting for messages");
            testEndTime = System.nanoTime();
        }

        // Print results
        printBidirectionalResults();

        producer.close();
        flowReceiver.stop();
        flowReceiver.close();
        pubSession.closeSession();
        subSession.closeSession();

        return errorCount.get() > 0 || !completed ? 1 : 0;
    }

    private String generatePayload(int size) {
        StringBuilder sb = new StringBuilder(size);
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        for (int i = 0; i < size; i++) {
            sb.append(chars.charAt(i % chars.length()));
        }
        return sb.toString();
    }

    private Thread startProgressReporter(String label) {
        Thread reporter = new Thread(() -> {
            int lastCount = 0;
            while (running) {
                try {
                    Thread.sleep(reportInterval * 1000L);
                    if (!running) break;

                    int currentCount = label.equals("Sent") ? sentCount.get() : receivedCount.get();
                    int delta = currentCount - lastCount;
                    double rate = delta / (double) reportInterval;

                    System.out.printf("%s: %s messages (%.0f msg/s)%n",
                        label, df.format(currentCount), rate);
                    lastCount = currentCount;
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        reporter.setDaemon(true);
        reporter.start();
        return reporter;
    }

    private Thread startBidirectionalProgressReporter() {
        Thread reporter = new Thread(() -> {
            int lastSent = 0;
            int lastReceived = 0;
            while (running) {
                try {
                    Thread.sleep(reportInterval * 1000L);
                    if (!running) break;

                    int sent = sentCount.get();
                    int received = receivedCount.get();
                    double sentRate = (sent - lastSent) / (double) reportInterval;
                    double recvRate = (received - lastReceived) / (double) reportInterval;

                    System.out.printf("Sent: %s (%.0f/s) | Received: %s (%.0f/s) | In-flight: %d%n",
                        df.format(sent), sentRate,
                        df.format(received), recvRate,
                        sent - received);

                    lastSent = sent;
                    lastReceived = received;
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        reporter.setDaemon(true);
        reporter.start();
        return reporter;
    }

    private void printPublishResults() {
        System.out.println();
        System.out.println(repeatChar('=', 60));
        System.out.println("PUBLISH TEST RESULTS");
        System.out.println(repeatChar('=', 60));

        long durationNanos = testEndTime - testStartTime;
        double durationSeconds = durationNanos / 1_000_000_000.0;
        double throughput = sentCount.get() / durationSeconds;
        double avgSendTimeUs = (totalSendTime.get() / (double) sentCount.get()) / 1000.0;
        double dataThroughputMB = (sentCount.get() * (long) messageSize) / (durationSeconds * 1024 * 1024);

        System.out.println();
        System.out.println("Messages sent:     " + df.format(sentCount.get()));
        System.out.println("Errors:            " + df.format(errorCount.get()));
        System.out.println("Duration:          " + df.format(durationSeconds) + " seconds");
        System.out.println("Throughput:        " + df.format(throughput) + " msg/s");
        System.out.println("Data throughput:   " + df.format(dataThroughputMB) + " MB/s");
        System.out.println("Avg send time:     " + df.format(avgSendTimeUs) + " μs");
        System.out.println();
    }

    private void printConsumeResults() {
        System.out.println();
        System.out.println(repeatChar('=', 60));
        System.out.println("CONSUME TEST RESULTS");
        System.out.println(repeatChar('=', 60));

        long durationNanos = testEndTime - testStartTime;
        double durationSeconds = durationNanos / 1_000_000_000.0;
        double throughput = receivedCount.get() / durationSeconds;

        System.out.println();
        System.out.println("Messages received: " + df.format(receivedCount.get()));
        if (excludedCount.get() > 0) {
            System.out.println("Messages excluded: " + df.format(excludedCount.get()));
        }
        System.out.println("Errors:            " + df.format(errorCount.get()));
        System.out.println("Duration:          " + df.format(durationSeconds) + " seconds");
        System.out.println("Throughput:        " + df.format(throughput) + " msg/s");

        // Print exclusion statistics if exclusion was tested
        if (exclusionList != null) {
            printExclusionStats();
        }

        if (!latencies.isEmpty()) {
            printLatencyStats();
        }

        System.out.println();
    }

    private void printBidirectionalResults() {
        System.out.println();
        System.out.println(repeatChar('=', 60));
        System.out.println("BIDIRECTIONAL TEST RESULTS");
        System.out.println(repeatChar('=', 60));

        long durationNanos = testEndTime - testStartTime;
        double durationSeconds = durationNanos / 1_000_000_000.0;
        double pubThroughput = sentCount.get() / durationSeconds;
        double subThroughput = receivedCount.get() / durationSeconds;
        double dataThroughputMB = (receivedCount.get() * (long) messageSize) / (durationSeconds * 1024 * 1024);

        System.out.println();
        System.out.println("Messages sent:     " + df.format(sentCount.get()));
        System.out.println("Messages received: " + df.format(receivedCount.get()));
        if (excludedCount.get() > 0) {
            System.out.println("Messages excluded: " + df.format(excludedCount.get()));
        }
        System.out.println("Errors:            " + df.format(errorCount.get()));
        System.out.println("Duration:          " + df.format(durationSeconds) + " seconds");
        System.out.println("Publish rate:      " + df.format(pubThroughput) + " msg/s");
        System.out.println("Consume rate:      " + df.format(subThroughput) + " msg/s");
        System.out.println("Data throughput:   " + df.format(dataThroughputMB) + " MB/s");

        // Print exclusion statistics if exclusion was tested
        if (exclusionList != null) {
            printExclusionStats();
        }

        if (!latencies.isEmpty()) {
            printLatencyStats();
        }

        System.out.println();
    }

    private void printLatencyStats() {
        System.out.println();
        System.out.println("Latency Statistics:");

        List<Long> sortedLatencies = new ArrayList<>(latencies);
        Collections.sort(sortedLatencies);

        int size = sortedLatencies.size();
        if (size == 0) return;

        long min = sortedLatencies.get(0);
        long max = sortedLatencies.get(size - 1);
        long median = sortedLatencies.get(size / 2);
        long p95 = sortedLatencies.get((int) (size * 0.95));
        long p99 = sortedLatencies.get((int) (size * 0.99));

        long sum = 0;
        for (Long l : sortedLatencies) {
            sum += l;
        }
        double avg = sum / (double) size;

        System.out.println("  Min:     " + df.format(min / 1000.0) + " μs");
        System.out.println("  Max:     " + df.format(max / 1000.0) + " μs");
        System.out.println("  Avg:     " + df.format(avg / 1000.0) + " μs");
        System.out.println("  Median:  " + df.format(median / 1000.0) + " μs");
        System.out.println("  P95:     " + df.format(p95 / 1000.0) + " μs");
        System.out.println("  P99:     " + df.format(p99 / 1000.0) + " μs");
    }

    private void printExclusionStats() {
        System.out.println();
        System.out.println("Exclusion Statistics:");

        int totalChecked = receivedCount.get() + excludedCount.get();
        double excludePercent = totalChecked > 0 ? (excludedCount.get() * 100.0 / totalChecked) : 0;
        double avgCheckTimeUs = totalChecked > 0 ? (totalExclusionCheckTime.get() / (double) totalChecked) / 1000.0 : 0;

        System.out.println("  Total checked:   " + df.format(totalChecked));
        System.out.println("  Excluded:        " + df.format(excludedCount.get()) + " (" + df.format(excludePercent) + "%)");
        System.out.println("  Avg check time:  " + df.format(avgCheckTimeUs) + " μs/msg");
        System.out.println("  Total overhead:  " + df.format(totalExclusionCheckTime.get() / 1_000_000.0) + " ms");
    }

    /**
     * Check if a message should be excluded based on correlation ID and optionally content.
     */
    private static boolean shouldExcludeMessage(ExclusionList list, BytesXMLMessage message, boolean checkContent) {
        if (list == null || list.isEmpty()) {
            return false;
        }

        // Check correlation ID
        String correlationId = message.getCorrelationId();
        if (correlationId != null && list.isExcluded(correlationId)) {
            return true;
        }

        // Check content if enabled
        if (checkContent) {
            String content = extractContent(message);
            if (content != null && list.containsExcluded(content)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Extract text content from a message.
     */
    private static String extractContent(BytesXMLMessage message) {
        if (message instanceof TextMessage) {
            return ((TextMessage) message).getText();
        } else if (message instanceof BytesMessage) {
            byte[] data = ((BytesMessage) message).getData();
            return data != null ? new String(data) : null;
        }
        return null;
    }

    /**
     * Java 8 compatible string repeat function.
     */
    private static String repeatChar(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
