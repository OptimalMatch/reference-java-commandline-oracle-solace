package com.example.solace;

import com.solacesystems.jcsmp.*;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Integration tests using Solace PubSub+ broker.
 *
 * Can use either:
 * 1. An existing Solace broker (set SOLACE_HOST environment variable, e.g., "tcp://localhost:55555")
 * 2. A Testcontainers-managed Solace container (if SOLACE_HOST is not set and Docker is available)
 *
 * Tests are automatically SKIPPED if:
 * - No SOLACE_HOST is provided AND Docker is not available
 *
 * Environment variables:
 * - SOLACE_HOST: Solace broker URL (e.g., tcp://localhost:55555)
 * - SOLACE_VPN: VPN name (default: "default")
 * - SOLACE_USERNAME: Username (default: "admin")
 * - SOLACE_PASSWORD: Password (default: "admin")
 */
public class SolaceContainerIT {

    private static final String SOLACE_IMAGE = "solace/solace-pubsub-standard:latest";

    // Configuration - can be overridden via environment variables
    private static String VPN_NAME = System.getenv("SOLACE_VPN") != null ? System.getenv("SOLACE_VPN") : "default";
    private static String USERNAME = System.getenv("SOLACE_USERNAME") != null ? System.getenv("SOLACE_USERNAME") : "admin";
    private static String PASSWORD = System.getenv("SOLACE_PASSWORD") != null ? System.getenv("SOLACE_PASSWORD") : "admin";
    private static final String QUEUE_NAME = "test-queue-" + System.currentTimeMillis();

    // External broker URL (if provided, Testcontainers won't be used)
    private static final String EXTERNAL_SOLACE_HOST = System.getenv("SOLACE_HOST");
    private static boolean useExternalBroker = EXTERNAL_SOLACE_HOST != null && !EXTERNAL_SOLACE_HOST.isEmpty();

    // Check if Docker is available
    private static boolean dockerAvailable = false;
    static {
        if (!useExternalBroker) {
            try {
                dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
            } catch (Exception e) {
                dockerAvailable = false;
            }
        }
    }

    // Skip all tests if no broker available
    private static boolean shouldSkip = !useExternalBroker && !dockerAvailable;

    @ClassRule
    public static TestRule skipRule = new TestRule() {
        @Override
        public Statement apply(Statement base, Description description) {
            if (shouldSkip) {
                return new Statement() {
                    @Override
                    public void evaluate() {
                        Assume.assumeTrue("Skipping: No SOLACE_HOST provided and Docker is not available", false);
                    }
                };
            }
            return base;
        }
    };

    @ClassRule
    @SuppressWarnings("resource")
    public static GenericContainer<?> solaceContainer = (useExternalBroker || !dockerAvailable) ? null :
            new GenericContainer<>(DockerImageName.parse(SOLACE_IMAGE))
            .withExposedPorts(55555)
            .withSharedMemorySize(1024L * 1024L * 1024L) // 1GB shared memory for Solace
            .withEnv("username_admin_globalaccesslevel", "admin")
            .withEnv("username_admin_password", PASSWORD)
            .waitingFor(Wait.forLogMessage(".*Primary Virtual Router is now active.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    private ByteArrayOutputStream outContent;
    private ByteArrayOutputStream errContent;
    private PrintStream originalOut;
    private PrintStream originalErr;
    private File tempDir;

    @Before
    public void setUp() throws Exception {
        originalOut = System.out;
        originalErr = System.err;
        outContent = new ByteArrayOutputStream();
        errContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));

        tempDir = createTempDirectory();

        // Create the queue via SEMP or JCSMP
        createQueue();
    }

    @After
    public void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        deleteDirectory(tempDir);
    }

    private File createTempDirectory() throws Exception {
        File temp = File.createTempFile("solace-test", "dir");
        temp.delete();
        temp.mkdir();
        return temp;
    }

    private void deleteDirectory(File dir) {
        if (dir != null && dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            dir.delete();
        }
    }

    private String getSolaceHost() {
        if (useExternalBroker) {
            return EXTERNAL_SOLACE_HOST;
        }
        return "tcp://" + solaceContainer.getHost() + ":" + solaceContainer.getMappedPort(55555);
    }

    private void createQueue() throws Exception {
        JCSMPProperties properties = new JCSMPProperties();
        properties.setProperty(JCSMPProperties.HOST, getSolaceHost());
        properties.setProperty(JCSMPProperties.VPN_NAME, VPN_NAME);
        properties.setProperty(JCSMPProperties.USERNAME, USERNAME);
        properties.setProperty(JCSMPProperties.PASSWORD, PASSWORD);

        JCSMPSession session = JCSMPFactory.onlyInstance().createSession(properties);
        session.connect();

        // Provision the queue
        Queue queue = JCSMPFactory.onlyInstance().createQueue(QUEUE_NAME);
        EndpointProperties endpointProps = new EndpointProperties();
        endpointProps.setPermission(EndpointProperties.PERMISSION_CONSUME);
        endpointProps.setAccessType(EndpointProperties.ACCESSTYPE_EXCLUSIVE);

        try {
            session.provision(queue, endpointProps, JCSMPSession.FLAG_IGNORE_ALREADY_EXISTS);
            // Drain any existing messages from the queue
            drainQueue(session, queue);
        } finally {
            session.closeSession();
        }
    }

    private void drainQueue(JCSMPSession session, Queue queue) throws Exception {
        ConsumerFlowProperties flowProps = new ConsumerFlowProperties();
        flowProps.setEndpoint(queue);
        flowProps.setAckMode(JCSMPProperties.SUPPORTED_MESSAGE_ACK_CLIENT);

        final CountDownLatch drainLatch = new CountDownLatch(1);
        final int[] drainedCount = {0};

        FlowReceiver receiver = session.createFlow(new XMLMessageListener() {
            @Override
            public void onReceive(BytesXMLMessage message) {
                message.ackMessage();
                drainedCount[0]++;
            }

            @Override
            public void onException(JCSMPException e) {
                drainLatch.countDown();
            }
        }, flowProps);

        receiver.start();
        // Wait briefly to drain any existing messages
        Thread.sleep(500);
        receiver.stop();
        receiver.close();

        if (drainedCount[0] > 0) {
            System.out.println("Drained " + drainedCount[0] + " existing message(s) from queue");
        }
    }

    private File createFile(File dir, String name, String content) throws Exception {
        File file = new File(dir, name);
        FileWriter writer = new FileWriter(file);
        writer.write(content);
        writer.close();
        return file;
    }

    @Test
    public void testPublishSingleMessage() throws Exception {
        String testMessage = "Hello from Testcontainers!";

        String[] args = {
            "publish",
            testMessage,
            "-H", getSolaceHost(),
            "-v", VPN_NAME,
            "-u", USERNAME,
            "-p", PASSWORD,
            "-q", QUEUE_NAME
        };

        int exitCode = new CommandLine(new SolaceCli()).execute(args);

        String output = outContent.toString();
        assertEquals(0, exitCode);
        assertTrue(output.contains("Published message"));
        assertTrue(output.contains("Successfully published 1 message"));

        // Verify message was actually published by consuming it
        String receivedMessage = consumeOneMessage();
        assertEquals(testMessage, receivedMessage);
    }

    @Test
    public void testPublishMultipleMessages() throws Exception {
        String testMessage = "Repeated message";
        int count = 5;

        String[] args = {
            "publish",
            testMessage,
            "-H", getSolaceHost(),
            "-v", VPN_NAME,
            "-u", USERNAME,
            "-p", PASSWORD,
            "-q", QUEUE_NAME,
            "-c", String.valueOf(count)
        };

        int exitCode = new CommandLine(new SolaceCli()).execute(args);

        assertEquals(0, exitCode);
        String output = outContent.toString();
        assertTrue(output.contains("Successfully published " + count + " message"));

        // Consume all messages and verify count
        List<String> messages = consumeAllMessages(count, 5);
        assertEquals(count, messages.size());
        for (String msg : messages) {
            assertEquals(testMessage, msg);
        }
    }

    @Test
    public void testPublishWithCorrelationId() throws Exception {
        // Drain any existing messages first to ensure test isolation
        drainQueue();

        String testMessage = "Message with correlation";
        String correlationId = "CORR-12345";

        String[] args = {
            "publish",
            testMessage,
            "-H", getSolaceHost(),
            "-v", VPN_NAME,
            "-u", USERNAME,
            "-p", PASSWORD,
            "-q", QUEUE_NAME,
            "--correlation-id", correlationId
        };

        int exitCode = new CommandLine(new SolaceCli()).execute(args);

        assertEquals(0, exitCode);

        // Verify correlation ID
        String receivedCorrelationId = consumeOneMessageAndGetCorrelationId();
        assertEquals(correlationId, receivedCorrelationId);
    }

    @Test
    public void testFolderPublish() throws Exception {
        // Create test files
        createFile(tempDir, "message1.txt", "First message from folder");
        createFile(tempDir, "message2.txt", "Second message from folder");
        createFile(tempDir, "message3.txt", "Third message from folder");

        String[] args = {
            "folder-publish",
            tempDir.getAbsolutePath(),
            "-H", getSolaceHost(),
            "-v", VPN_NAME,
            "-u", USERNAME,
            "-p", PASSWORD,
            "-q", QUEUE_NAME,
            "--pattern", "*.txt"
        };

        int exitCode = new CommandLine(new SolaceCli()).execute(args);

        assertEquals(0, exitCode);
        String output = outContent.toString();
        assertTrue(output.contains("Found 3 file(s)"));
        assertTrue(output.contains("Completed: 3 published, 0 failed"));

        // Verify all messages were published
        List<String> messages = consumeAllMessages(3, 5);
        assertEquals(3, messages.size());
    }

    @Test
    public void testFolderPublishWithFilenameAsCorrelation() throws Exception {
        createFile(tempDir, "order-001.xml", "<order id='1'/>");
        createFile(tempDir, "order-002.xml", "<order id='2'/>");

        String[] args = {
            "folder-publish",
            tempDir.getAbsolutePath(),
            "-H", getSolaceHost(),
            "-v", VPN_NAME,
            "-u", USERNAME,
            "-p", PASSWORD,
            "-q", QUEUE_NAME,
            "--pattern", "*.xml",
            "--use-filename-as-correlation"
        };

        int exitCode = new CommandLine(new SolaceCli()).execute(args);

        assertEquals(0, exitCode);

        // Consume and verify correlation IDs are filenames without extension
        List<String> correlationIds = consumeAllMessagesAndGetCorrelationIds(2, 5);
        assertTrue(correlationIds.contains("order-001"));
        assertTrue(correlationIds.contains("order-002"));
    }

    @Test
    public void testPublishDirectMode() throws Exception {
        String testMessage = "Direct mode message";

        String[] args = {
            "publish",
            testMessage,
            "-H", getSolaceHost(),
            "-v", VPN_NAME,
            "-u", USERNAME,
            "-p", PASSWORD,
            "-q", QUEUE_NAME,
            "--delivery-mode", "DIRECT"
        };

        int exitCode = new CommandLine(new SolaceCli()).execute(args);

        assertEquals(0, exitCode);
        assertTrue(outContent.toString().contains("Published message"));
    }

    @Test
    public void testConnectionFailure() {
        String[] args = {
            "publish",
            "test",
            "-H", "tcp://invalid-host:55555",
            "-v", VPN_NAME,
            "-u", USERNAME,
            "-p", PASSWORD,
            "-q", QUEUE_NAME
        };

        int exitCode = new CommandLine(new SolaceCli()).execute(args);

        assertNotEquals(0, exitCode);
    }

    // Helper methods to consume messages for verification

    private String consumeOneMessage() throws Exception {
        JCSMPProperties properties = new JCSMPProperties();
        properties.setProperty(JCSMPProperties.HOST, getSolaceHost());
        properties.setProperty(JCSMPProperties.VPN_NAME, VPN_NAME);
        properties.setProperty(JCSMPProperties.USERNAME, USERNAME);
        properties.setProperty(JCSMPProperties.PASSWORD, PASSWORD);

        JCSMPSession session = JCSMPFactory.onlyInstance().createSession(properties);
        session.connect();

        try {
            Queue queue = JCSMPFactory.onlyInstance().createQueue(QUEUE_NAME);
            ConsumerFlowProperties flowProps = new ConsumerFlowProperties();
            flowProps.setEndpoint(queue);
            flowProps.setAckMode(JCSMPProperties.SUPPORTED_MESSAGE_ACK_CLIENT);

            final String[] receivedMessage = new String[1];
            final CountDownLatch latch = new CountDownLatch(1);

            FlowReceiver receiver = session.createFlow(new XMLMessageListener() {
                @Override
                public void onReceive(BytesXMLMessage message) {
                    if (message instanceof TextMessage) {
                        receivedMessage[0] = ((TextMessage) message).getText();
                    }
                    message.ackMessage();
                    latch.countDown();
                }

                @Override
                public void onException(JCSMPException e) {
                    latch.countDown();
                }
            }, flowProps);

            receiver.start();
            latch.await(10, TimeUnit.SECONDS);
            receiver.stop();
            receiver.close();

            return receivedMessage[0];
        } finally {
            session.closeSession();
        }
    }

    private String consumeOneMessageAndGetCorrelationId() throws Exception {
        JCSMPProperties properties = new JCSMPProperties();
        properties.setProperty(JCSMPProperties.HOST, getSolaceHost());
        properties.setProperty(JCSMPProperties.VPN_NAME, VPN_NAME);
        properties.setProperty(JCSMPProperties.USERNAME, USERNAME);
        properties.setProperty(JCSMPProperties.PASSWORD, PASSWORD);

        JCSMPSession session = JCSMPFactory.onlyInstance().createSession(properties);
        session.connect();

        try {
            Queue queue = JCSMPFactory.onlyInstance().createQueue(QUEUE_NAME);
            ConsumerFlowProperties flowProps = new ConsumerFlowProperties();
            flowProps.setEndpoint(queue);
            flowProps.setAckMode(JCSMPProperties.SUPPORTED_MESSAGE_ACK_CLIENT);

            final String[] correlationId = new String[1];
            final CountDownLatch latch = new CountDownLatch(1);

            FlowReceiver receiver = session.createFlow(new XMLMessageListener() {
                @Override
                public void onReceive(BytesXMLMessage message) {
                    correlationId[0] = message.getCorrelationId();
                    message.ackMessage();
                    latch.countDown();
                }

                @Override
                public void onException(JCSMPException e) {
                    latch.countDown();
                }
            }, flowProps);

            receiver.start();
            latch.await(10, TimeUnit.SECONDS);
            receiver.stop();
            receiver.close();

            return correlationId[0];
        } finally {
            session.closeSession();
        }
    }

    private List<String> consumeAllMessages(int expectedCount, int timeoutSeconds) throws Exception {
        JCSMPProperties properties = new JCSMPProperties();
        properties.setProperty(JCSMPProperties.HOST, getSolaceHost());
        properties.setProperty(JCSMPProperties.VPN_NAME, VPN_NAME);
        properties.setProperty(JCSMPProperties.USERNAME, USERNAME);
        properties.setProperty(JCSMPProperties.PASSWORD, PASSWORD);

        JCSMPSession session = JCSMPFactory.onlyInstance().createSession(properties);
        session.connect();

        try {
            Queue queue = JCSMPFactory.onlyInstance().createQueue(QUEUE_NAME);
            ConsumerFlowProperties flowProps = new ConsumerFlowProperties();
            flowProps.setEndpoint(queue);
            flowProps.setAckMode(JCSMPProperties.SUPPORTED_MESSAGE_ACK_CLIENT);

            final List<String> messages = new ArrayList<String>();
            final CountDownLatch latch = new CountDownLatch(expectedCount);

            FlowReceiver receiver = session.createFlow(new XMLMessageListener() {
                @Override
                public void onReceive(BytesXMLMessage message) {
                    if (message instanceof TextMessage) {
                        messages.add(((TextMessage) message).getText());
                    }
                    message.ackMessage();
                    latch.countDown();
                }

                @Override
                public void onException(JCSMPException e) {
                    // ignore
                }
            }, flowProps);

            receiver.start();
            latch.await(timeoutSeconds, TimeUnit.SECONDS);
            receiver.stop();
            receiver.close();

            return messages;
        } finally {
            session.closeSession();
        }
    }

    private List<String> consumeAllMessagesAndGetCorrelationIds(int expectedCount, int timeoutSeconds) throws Exception {
        JCSMPProperties properties = new JCSMPProperties();
        properties.setProperty(JCSMPProperties.HOST, getSolaceHost());
        properties.setProperty(JCSMPProperties.VPN_NAME, VPN_NAME);
        properties.setProperty(JCSMPProperties.USERNAME, USERNAME);
        properties.setProperty(JCSMPProperties.PASSWORD, PASSWORD);

        JCSMPSession session = JCSMPFactory.onlyInstance().createSession(properties);
        session.connect();

        try {
            Queue queue = JCSMPFactory.onlyInstance().createQueue(QUEUE_NAME);
            ConsumerFlowProperties flowProps = new ConsumerFlowProperties();
            flowProps.setEndpoint(queue);
            flowProps.setAckMode(JCSMPProperties.SUPPORTED_MESSAGE_ACK_CLIENT);

            final List<String> correlationIds = new ArrayList<String>();
            final CountDownLatch latch = new CountDownLatch(expectedCount);

            FlowReceiver receiver = session.createFlow(new XMLMessageListener() {
                @Override
                public void onReceive(BytesXMLMessage message) {
                    correlationIds.add(message.getCorrelationId());
                    message.ackMessage();
                    latch.countDown();
                }

                @Override
                public void onException(JCSMPException e) {
                    // ignore
                }
            }, flowProps);

            receiver.start();
            latch.await(timeoutSeconds, TimeUnit.SECONDS);
            receiver.stop();
            receiver.close();

            return correlationIds;
        } finally {
            session.closeSession();
        }
    }

    /**
     * Drain all messages from the queue to ensure test isolation.
     */
    private void drainQueue() throws Exception {
        JCSMPProperties properties = new JCSMPProperties();
        properties.setProperty(JCSMPProperties.HOST, getSolaceHost());
        properties.setProperty(JCSMPProperties.VPN_NAME, VPN_NAME);
        properties.setProperty(JCSMPProperties.USERNAME, USERNAME);
        properties.setProperty(JCSMPProperties.PASSWORD, PASSWORD);

        JCSMPSession session = JCSMPFactory.onlyInstance().createSession(properties);
        session.connect();

        try {
            Queue queue = JCSMPFactory.onlyInstance().createQueue(QUEUE_NAME);
            ConsumerFlowProperties flowProps = new ConsumerFlowProperties();
            flowProps.setEndpoint(queue);
            flowProps.setAckMode(JCSMPProperties.SUPPORTED_MESSAGE_ACK_CLIENT);

            final CountDownLatch latch = new CountDownLatch(1);

            FlowReceiver receiver = session.createFlow(new XMLMessageListener() {
                @Override
                public void onReceive(BytesXMLMessage message) {
                    message.ackMessage();
                }

                @Override
                public void onException(JCSMPException e) {
                    latch.countDown();
                }
            }, flowProps);

            receiver.start();
            // Brief wait to drain any messages
            Thread.sleep(500);
            receiver.stop();
            receiver.close();
        } finally {
            session.closeSession();
        }
    }
}
