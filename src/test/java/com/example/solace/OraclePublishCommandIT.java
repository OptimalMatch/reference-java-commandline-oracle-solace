package com.example.solace;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.Assert.*;

/**
 * Integration tests for OraclePublishCommand using H2 in-memory database.
 * These tests verify the database query and message extraction logic
 * without requiring an actual Oracle database or Solace broker.
 */
public class OraclePublishCommandIT {

    private Connection h2Connection;
    private static final String H2_URL = "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=Oracle";
    private static final String H2_USER = "sa";
    private static final String H2_PASSWORD = "";

    private ByteArrayOutputStream outContent;
    private ByteArrayOutputStream errContent;
    private PrintStream originalOut;
    private PrintStream originalErr;

    @Before
    public void setUp() throws Exception {
        // Capture console output
        originalOut = System.out;
        originalErr = System.err;
        outContent = new ByteArrayOutputStream();
        errContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));

        // Initialize H2 database
        h2Connection = DriverManager.getConnection(H2_URL, H2_USER, H2_PASSWORD);
        Statement stmt = h2Connection.createStatement();

        // Create test table
        stmt.execute("CREATE TABLE IF NOT EXISTS outbound_messages (" +
            "message_id VARCHAR(50), " +
            "message_content CLOB, " +
            "correlation_id VARCHAR(50), " +
            "status VARCHAR(20)" +
            ")");

        // Clear any existing data
        stmt.execute("DELETE FROM outbound_messages");

        stmt.close();
    }

    @After
    public void tearDown() throws Exception {
        // Restore console output
        System.setOut(originalOut);
        System.setErr(originalErr);

        if (h2Connection != null && !h2Connection.isClosed()) {
            Statement stmt = h2Connection.createStatement();
            stmt.execute("DROP TABLE IF EXISTS outbound_messages");
            stmt.close();
            h2Connection.close();
        }
    }

    private void insertTestMessage(String messageId, String content, String correlationId, String status) throws Exception {
        Statement stmt = h2Connection.createStatement();
        stmt.execute(String.format(
            "INSERT INTO outbound_messages VALUES ('%s', '%s', '%s', '%s')",
            messageId, content, correlationId, status
        ));
        stmt.close();
    }

    @Test
    public void testDryRunWithMessages() throws Exception {
        // Insert test data
        insertTestMessage("MSG001", "Test message content 1", "CORR001", "PENDING");
        insertTestMessage("MSG002", "Test message content 2", "CORR002", "PENDING");
        insertTestMessage("MSG003", "Test message content 3", "CORR003", "PROCESSED");

        // Create command with H2 connection parameters
        OraclePublishCommand cmd = new OraclePublishCommand();
        cmd.oracleConnection = new OracleOptions();
        cmd.oracleConnection.dbHost = "localhost";
        cmd.oracleConnection.dbPort = 9092;
        cmd.oracleConnection.dbService = "testdb";
        cmd.oracleConnection.dbUser = H2_USER;
        cmd.oracleConnection.dbPassword = H2_PASSWORD;

        cmd.solaceConnection = new ConnectionOptions();
        cmd.solaceConnection.host = "tcp://localhost:55555";
        cmd.solaceConnection.vpn = "default";
        cmd.solaceConnection.username = "user";
        cmd.solaceConnection.password = "pass";
        cmd.solaceConnection.queue = "test-queue";

        cmd.sqlQuery = "SELECT message_id, message_content, correlation_id FROM outbound_messages WHERE status = 'PENDING'";
        cmd.messageColumn = "message_content";
        cmd.correlationColumn = "correlation_id";
        cmd.dryRun = true;

        // Override getJdbcUrl to use H2
        cmd.oracleConnection = new OracleOptions() {
            @Override
            public String getJdbcUrl() {
                return H2_URL;
            }
        };
        cmd.oracleConnection.dbUser = H2_USER;
        cmd.oracleConnection.dbPassword = H2_PASSWORD;

        Integer result = cmd.call();

        String output = outContent.toString();
        assertEquals(Integer.valueOf(0), result);
        assertTrue(output.contains("DRY RUN MODE"));
        assertTrue(output.contains("Found 2 message(s)"));
    }

    @Test
    public void testDryRunWithNoMessages() throws Exception {
        // Create command - no data inserted
        OraclePublishCommand cmd = new OraclePublishCommand();

        cmd.solaceConnection = new ConnectionOptions();
        cmd.solaceConnection.host = "tcp://localhost:55555";
        cmd.solaceConnection.vpn = "default";
        cmd.solaceConnection.username = "user";
        cmd.solaceConnection.password = "pass";
        cmd.solaceConnection.queue = "test-queue";

        cmd.sqlQuery = "SELECT message_id, message_content FROM outbound_messages WHERE status = 'PENDING'";
        cmd.messageColumn = "";
        cmd.dryRun = true;

        cmd.oracleConnection = new OracleOptions() {
            @Override
            public String getJdbcUrl() {
                return H2_URL;
            }
        };
        cmd.oracleConnection.dbUser = H2_USER;
        cmd.oracleConnection.dbPassword = H2_PASSWORD;

        Integer result = cmd.call();

        String output = outContent.toString();
        assertEquals(Integer.valueOf(0), result);
        assertTrue(output.contains("Found 0 message(s)"));
    }

    @Test
    public void testDryRunShowsMessagePreview() throws Exception {
        insertTestMessage("MSG001", "This is a test message with important content", "CORR001", "PENDING");

        OraclePublishCommand cmd = new OraclePublishCommand();

        cmd.solaceConnection = new ConnectionOptions();
        cmd.solaceConnection.host = "tcp://localhost:55555";
        cmd.solaceConnection.vpn = "default";
        cmd.solaceConnection.username = "user";
        cmd.solaceConnection.password = "pass";
        cmd.solaceConnection.queue = "test-queue";

        cmd.sqlQuery = "SELECT message_content, correlation_id FROM outbound_messages WHERE status = 'PENDING'";
        cmd.messageColumn = "message_content";
        cmd.correlationColumn = "correlation_id";
        cmd.dryRun = true;

        cmd.oracleConnection = new OracleOptions() {
            @Override
            public String getJdbcUrl() {
                return H2_URL;
            }
        };
        cmd.oracleConnection.dbUser = H2_USER;
        cmd.oracleConnection.dbPassword = H2_PASSWORD;

        Integer result = cmd.call();

        String output = outContent.toString();
        assertEquals(Integer.valueOf(0), result);
        assertTrue(output.contains("This is a test message"));
        assertTrue(output.contains("CORR001"));
    }

    @Test
    public void testCommandLineParsing() {
        OraclePublishCommand cmd = new OraclePublishCommand();
        new CommandLine(cmd).parseArgs(
            "--db-host", "oracle.example.com",
            "--db-port", "1522",
            "--db-service", "PROD",
            "--db-user", "dbuser",
            "--db-password", "dbpass",
            "-H", "tcp://solace:55555",
            "-v", "myvpn",
            "-u", "solaceuser",
            "-p", "solacepass",
            "-q", "myqueue",
            "--sql", "SELECT * FROM messages",
            "--message-column", "content",
            "--correlation-column", "corr_id",
            "--delivery-mode", "DIRECT",
            "--ttl", "60000",
            "--dry-run"
        );

        assertEquals("oracle.example.com", cmd.oracleConnection.dbHost);
        assertEquals(1522, cmd.oracleConnection.dbPort);
        assertEquals("PROD", cmd.oracleConnection.dbService);
        assertEquals("dbuser", cmd.oracleConnection.dbUser);
        assertEquals("dbpass", cmd.oracleConnection.dbPassword);

        assertEquals("tcp://solace:55555", cmd.solaceConnection.host);
        assertEquals("myvpn", cmd.solaceConnection.vpn);
        assertEquals("solaceuser", cmd.solaceConnection.username);
        assertEquals("solacepass", cmd.solaceConnection.password);
        assertEquals("myqueue", cmd.solaceConnection.queue);

        assertEquals("SELECT * FROM messages", cmd.sqlQuery);
        assertEquals("content", cmd.messageColumn);
        assertEquals("corr_id", cmd.correlationColumn);
        assertEquals("DIRECT", cmd.deliveryMode);
        assertEquals(60000, cmd.ttl);
        assertTrue(cmd.dryRun);
    }

    @Test
    public void testInvalidSqlQuery() throws Exception {
        OraclePublishCommand cmd = new OraclePublishCommand();

        cmd.solaceConnection = new ConnectionOptions();
        cmd.solaceConnection.host = "tcp://localhost:55555";
        cmd.solaceConnection.vpn = "default";
        cmd.solaceConnection.username = "user";
        cmd.solaceConnection.password = "pass";
        cmd.solaceConnection.queue = "test-queue";

        cmd.sqlQuery = "SELECT * FROM nonexistent_table";
        cmd.messageColumn = "";
        cmd.dryRun = true;

        cmd.oracleConnection = new OracleOptions() {
            @Override
            public String getJdbcUrl() {
                return H2_URL;
            }
        };
        cmd.oracleConnection.dbUser = H2_USER;
        cmd.oracleConnection.dbPassword = H2_PASSWORD;

        Integer result = cmd.call();

        assertEquals(Integer.valueOf(1), result);
    }

    @Test
    public void testInvalidColumnName() throws Exception {
        insertTestMessage("MSG001", "Test content", "CORR001", "PENDING");

        OraclePublishCommand cmd = new OraclePublishCommand();

        cmd.solaceConnection = new ConnectionOptions();
        cmd.solaceConnection.host = "tcp://localhost:55555";
        cmd.solaceConnection.vpn = "default";
        cmd.solaceConnection.username = "user";
        cmd.solaceConnection.password = "pass";
        cmd.solaceConnection.queue = "test-queue";

        cmd.sqlQuery = "SELECT message_content FROM outbound_messages WHERE status = 'PENDING'";
        cmd.messageColumn = "nonexistent_column";
        cmd.dryRun = true;

        cmd.oracleConnection = new OracleOptions() {
            @Override
            public String getJdbcUrl() {
                return H2_URL;
            }
        };
        cmd.oracleConnection.dbUser = H2_USER;
        cmd.oracleConnection.dbPassword = H2_PASSWORD;

        Integer result = cmd.call();

        assertEquals(Integer.valueOf(1), result);
    }

    @Test
    public void testDefaultMessageColumn() throws Exception {
        insertTestMessage("MSG001", "First column content", "CORR001", "PENDING");

        OraclePublishCommand cmd = new OraclePublishCommand();

        cmd.solaceConnection = new ConnectionOptions();
        cmd.solaceConnection.host = "tcp://localhost:55555";
        cmd.solaceConnection.vpn = "default";
        cmd.solaceConnection.username = "user";
        cmd.solaceConnection.password = "pass";
        cmd.solaceConnection.queue = "test-queue";

        // First column should be used when messageColumn is empty
        cmd.sqlQuery = "SELECT message_content FROM outbound_messages WHERE status = 'PENDING'";
        cmd.messageColumn = "";
        cmd.dryRun = true;

        cmd.oracleConnection = new OracleOptions() {
            @Override
            public String getJdbcUrl() {
                return H2_URL;
            }
        };
        cmd.oracleConnection.dbUser = H2_USER;
        cmd.oracleConnection.dbPassword = H2_PASSWORD;

        Integer result = cmd.call();

        String output = outContent.toString();
        assertEquals(Integer.valueOf(0), result);
        assertTrue(output.contains("First column content"));
    }

    @Test
    public void testAliases() {
        CommandLine.Command annotation = OraclePublishCommand.class.getAnnotation(CommandLine.Command.class);
        String[] aliases = annotation.aliases();

        assertEquals(1, aliases.length);
        assertEquals("ora-pub", aliases[0]);
    }
}
