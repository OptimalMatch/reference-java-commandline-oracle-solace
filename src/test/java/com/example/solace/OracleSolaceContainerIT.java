package com.example.solace;

import com.solacesystems.jcsmp.*;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Full end-to-end integration test using both Oracle and Solace containers.
 * Tests the oracle-publish command which reads from Oracle and publishes to Solace.
 *
 * These tests REQUIRE Docker to be available and will be SKIPPED if:
 * - Docker is not running
 * - SOLACE_HOST environment variable is set (external broker mode - use SolaceContainerIT instead)
 *
 * To run these tests, Docker must be available and NO SOLACE_HOST should be set.
 */
public class OracleSolaceContainerIT {

    private static final String SOLACE_IMAGE = "solace/solace-pubsub-standard:latest";
    private static final String VPN_NAME = "default";
    private static final String SOLACE_USERNAME = "admin";
    private static final String SOLACE_PASSWORD = "admin";
    private static final String QUEUE_NAME = "oracle-messages-queue-" + System.currentTimeMillis();

    // Skip if external Solace broker is configured (these tests need both containers)
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

    // Skip if using external broker or Docker not available
    private static boolean shouldSkip = useExternalBroker || !dockerAvailable;

    @ClassRule
    public static TestRule skipRule = new TestRule() {
        @Override
        public org.junit.runners.model.Statement apply(org.junit.runners.model.Statement base, Description description) {
            if (shouldSkip) {
                return new org.junit.runners.model.Statement() {
                    @Override
                    public void evaluate() {
                        if (useExternalBroker) {
                            Assume.assumeTrue("Skipping: External SOLACE_HOST configured, use SolaceContainerIT instead", false);
                        } else {
                            Assume.assumeTrue("Skipping: Docker is not available", false);
                        }
                    }
                };
            }
            return base;
        }
    };

    @ClassRule
    @SuppressWarnings("resource")
    public static OracleContainer oracleContainer = shouldSkip ? null :
            new OracleContainer(DockerImageName.parse("gvenzl/oracle-xe:21-slim"))
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

    @ClassRule
    @SuppressWarnings("resource")
    public static GenericContainer<?> solaceContainer = shouldSkip ? null :
            new GenericContainer<>(DockerImageName.parse(SOLACE_IMAGE))
            .withExposedPorts(55555, 8080)
            .withSharedMemorySize(1024L * 1024L * 1024L)
            .withEnv("username_admin_globalaccesslevel", "admin")
            .withEnv("username_admin_password", SOLACE_PASSWORD)
            .waitingFor(Wait.forLogMessage(".*Primary Virtual Router is now active.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    private ByteArrayOutputStream outContent;
    private ByteArrayOutputStream errContent;
    private PrintStream originalOut;
    private PrintStream originalErr;

    @Before
    public void setUp() throws Exception {
        originalOut = System.out;
        originalErr = System.err;
        outContent = new ByteArrayOutputStream();
        errContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));

        // Set up Oracle table
        setupOracleTable();

        // Set up Solace queue
        createSolaceQueue();
    }

    @After
    public void tearDown() throws Exception {
        System.setOut(originalOut);
        System.setErr(originalErr);

        // Clean up Oracle table
        cleanupOracleTable();
    }

    private String getSolaceHost() {
        return "tcp://" + solaceContainer.getHost() + ":" + solaceContainer.getMappedPort(55555);
    }

    private void setupOracleTable() throws Exception {
        Connection conn = DriverManager.getConnection(
                oracleContainer.getJdbcUrl(),
                oracleContainer.getUsername(),
                oracleContainer.getPassword());

        java.sql.Statement stmt = conn.createStatement();

        // Create table
        stmt.execute("CREATE TABLE outbound_messages (" +
                "message_id VARCHAR2(50), " +
                "message_content CLOB, " +
                "correlation_id VARCHAR2(50), " +
                "status VARCHAR2(20))");

        stmt.close();
        conn.close();
    }

    private void cleanupOracleTable() throws Exception {
        Connection conn = DriverManager.getConnection(
                oracleContainer.getJdbcUrl(),
                oracleContainer.getUsername(),
                oracleContainer.getPassword());

        java.sql.Statement stmt = conn.createStatement();
        try {
            stmt.execute("DROP TABLE outbound_messages");
        } catch (Exception e) {
            // Ignore if table doesn't exist
        }
        stmt.close();
        conn.close();
    }

    private void insertOracleMessage(String messageId, String content, String correlationId, String status) throws Exception {
        Connection conn = DriverManager.getConnection(
                oracleContainer.getJdbcUrl(),
                oracleContainer.getUsername(),
                oracleContainer.getPassword());

        java.sql.Statement stmt = conn.createStatement();
        stmt.execute(String.format(
                "INSERT INTO outbound_messages VALUES ('%s', '%s', '%s', '%s')",
                messageId, content, correlationId, status));

        stmt.close();
        conn.close();
    }

    private void createSolaceQueue() throws Exception {
        JCSMPProperties properties = new JCSMPProperties();
        properties.setProperty(JCSMPProperties.HOST, getSolaceHost());
        properties.setProperty(JCSMPProperties.VPN_NAME, VPN_NAME);
        properties.setProperty(JCSMPProperties.USERNAME, SOLACE_USERNAME);
        properties.setProperty(JCSMPProperties.PASSWORD, SOLACE_PASSWORD);

        JCSMPSession session = JCSMPFactory.onlyInstance().createSession(properties);
        session.connect();

        Queue queue = JCSMPFactory.onlyInstance().createQueue(QUEUE_NAME);
        EndpointProperties endpointProps = new EndpointProperties();
        endpointProps.setPermission(EndpointProperties.PERMISSION_CONSUME);
        endpointProps.setAccessType(EndpointProperties.ACCESSTYPE_EXCLUSIVE);

        try {
            session.provision(queue, endpointProps, JCSMPSession.FLAG_IGNORE_ALREADY_EXISTS);
        } finally {
            session.closeSession();
        }
    }

    @Test
    public void testOracleToSolacePublish() throws Exception {
        // Insert test messages into Oracle
        insertOracleMessage("MSG001", "Order created for customer A", "ORDER-001", "PENDING");
        insertOracleMessage("MSG002", "Order created for customer B", "ORDER-002", "PENDING");
        insertOracleMessage("MSG003", "Order processed", "ORDER-003", "PROCESSED");

        // Parse Oracle JDBC URL to extract host, port, service
        String jdbcUrl = oracleContainer.getJdbcUrl();
        // Format: jdbc:oracle:thin:@//host:port/service

        // Run oracle-publish command
        OraclePublishCommand cmd = new OraclePublishCommand();

        // Configure Oracle connection using container's JDBC URL directly
        cmd.oracleConnection = new OracleOptions() {
            @Override
            public String getJdbcUrl() {
                return oracleContainer.getJdbcUrl();
            }
        };
        cmd.oracleConnection.dbUser = oracleContainer.getUsername();
        cmd.oracleConnection.dbPassword = oracleContainer.getPassword();

        // Configure Solace connection
        cmd.solaceConnection = new ConnectionOptions();
        cmd.solaceConnection.host = getSolaceHost();
        cmd.solaceConnection.vpn = VPN_NAME;
        cmd.solaceConnection.username = SOLACE_USERNAME;
        cmd.solaceConnection.password = SOLACE_PASSWORD;
        cmd.solaceConnection.queue = QUEUE_NAME;

        // Configure query
        cmd.sqlQuery = "SELECT message_content, correlation_id FROM outbound_messages WHERE status = 'PENDING'";
        cmd.messageColumn = "message_content";
        cmd.correlationColumn = "correlation_id";
        cmd.deliveryMode = "PERSISTENT";
        cmd.dryRun = false;

        Integer result = cmd.call();

        assertEquals(Integer.valueOf(0), result);
        String output = outContent.toString();
        assertTrue(output.contains("Successfully published 2 message"));

        // Verify messages were received in Solace
        List<String[]> messages = consumeAllMessagesWithCorrelation(2, 10);
        assertEquals(2, messages.size());

        // Verify message content and correlation IDs
        boolean foundOrder001 = false;
        boolean foundOrder002 = false;
        for (String[] msg : messages) {
            if ("ORDER-001".equals(msg[1])) {
                assertEquals("Order created for customer A", msg[0]);
                foundOrder001 = true;
            } else if ("ORDER-002".equals(msg[1])) {
                assertEquals("Order created for customer B", msg[0]);
                foundOrder002 = true;
            }
        }
        assertTrue("Expected ORDER-001", foundOrder001);
        assertTrue("Expected ORDER-002", foundOrder002);
    }

    @Test
    public void testOracleToSolaceDryRun() throws Exception {
        insertOracleMessage("MSG001", "Test message 1", "CORR-001", "PENDING");
        insertOracleMessage("MSG002", "Test message 2", "CORR-002", "PENDING");

        OraclePublishCommand cmd = new OraclePublishCommand();

        cmd.oracleConnection = new OracleOptions() {
            @Override
            public String getJdbcUrl() {
                return oracleContainer.getJdbcUrl();
            }
        };
        cmd.oracleConnection.dbUser = oracleContainer.getUsername();
        cmd.oracleConnection.dbPassword = oracleContainer.getPassword();

        cmd.solaceConnection = new ConnectionOptions();
        cmd.solaceConnection.host = getSolaceHost();
        cmd.solaceConnection.vpn = VPN_NAME;
        cmd.solaceConnection.username = SOLACE_USERNAME;
        cmd.solaceConnection.password = SOLACE_PASSWORD;
        cmd.solaceConnection.queue = QUEUE_NAME;

        cmd.sqlQuery = "SELECT message_content FROM outbound_messages WHERE status = 'PENDING'";
        cmd.messageColumn = "";
        cmd.dryRun = true;

        Integer result = cmd.call();

        assertEquals(Integer.valueOf(0), result);
        String output = outContent.toString();
        assertTrue(output.contains("DRY RUN MODE"));
        assertTrue(output.contains("Found 2 message(s)"));
    }

    @Test
    public void testOracleEmptyResultSet() throws Exception {
        // No messages inserted

        OraclePublishCommand cmd = new OraclePublishCommand();

        cmd.oracleConnection = new OracleOptions() {
            @Override
            public String getJdbcUrl() {
                return oracleContainer.getJdbcUrl();
            }
        };
        cmd.oracleConnection.dbUser = oracleContainer.getUsername();
        cmd.oracleConnection.dbPassword = oracleContainer.getPassword();

        cmd.solaceConnection = new ConnectionOptions();
        cmd.solaceConnection.host = getSolaceHost();
        cmd.solaceConnection.vpn = VPN_NAME;
        cmd.solaceConnection.username = SOLACE_USERNAME;
        cmd.solaceConnection.password = SOLACE_PASSWORD;
        cmd.solaceConnection.queue = QUEUE_NAME;

        cmd.sqlQuery = "SELECT message_content FROM outbound_messages WHERE status = 'NONEXISTENT'";
        cmd.messageColumn = "";
        cmd.dryRun = false;

        Integer result = cmd.call();

        assertEquals(Integer.valueOf(0), result);
        String output = outContent.toString();
        assertTrue(output.contains("Successfully published 0 message"));
    }

    private List<String[]> consumeAllMessagesWithCorrelation(int expectedCount, int timeoutSeconds) throws Exception {
        JCSMPProperties properties = new JCSMPProperties();
        properties.setProperty(JCSMPProperties.HOST, getSolaceHost());
        properties.setProperty(JCSMPProperties.VPN_NAME, VPN_NAME);
        properties.setProperty(JCSMPProperties.USERNAME, SOLACE_USERNAME);
        properties.setProperty(JCSMPProperties.PASSWORD, SOLACE_PASSWORD);

        JCSMPSession session = JCSMPFactory.onlyInstance().createSession(properties);
        session.connect();

        try {
            Queue queue = JCSMPFactory.onlyInstance().createQueue(QUEUE_NAME);
            ConsumerFlowProperties flowProps = new ConsumerFlowProperties();
            flowProps.setEndpoint(queue);
            flowProps.setAckMode(JCSMPProperties.SUPPORTED_MESSAGE_ACK_CLIENT);

            final List<String[]> messages = new ArrayList<String[]>();
            final CountDownLatch latch = new CountDownLatch(expectedCount);

            FlowReceiver receiver = session.createFlow(new XMLMessageListener() {
                @Override
                public void onReceive(BytesXMLMessage message) {
                    String content = "";
                    if (message instanceof TextMessage) {
                        content = ((TextMessage) message).getText();
                    }
                    messages.add(new String[]{content, message.getCorrelationId()});
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
}
