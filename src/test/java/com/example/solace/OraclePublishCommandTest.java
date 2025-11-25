package com.example.solace;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import picocli.CommandLine;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class OraclePublishCommandTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private CommandLine cmd;
    private StringWriter sw;
    private StringWriter errSw;

    @Before
    public void setUp() {
        cmd = new CommandLine(new SolaceCli());
        sw = new StringWriter();
        errSw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));
        cmd.setErr(new PrintWriter(errSw));
    }

    @Test
    public void testHelpOutput() {
        int exitCode = cmd.execute("oracle-publish", "--help");
        assertEquals(0, exitCode);
        String output = sw.toString();
        assertTrue(output.contains("oracle-publish"));
        assertTrue(output.contains("--sql"));
        assertTrue(output.contains("--sql-file"));
        assertTrue(output.contains("--message-column"));
        assertTrue(output.contains("--correlation-column"));
        assertTrue(output.contains("--delivery-mode"));
        assertTrue(output.contains("--ttl"));
        assertTrue(output.contains("--dry-run"));
    }

    @Test
    public void testAlias() {
        int exitCode = cmd.execute("ora-pub", "--help");
        assertEquals(0, exitCode);
        String output = sw.toString();
        assertTrue(output.contains("oracle-publish"));
    }

    @Test
    public void testSqlFileOption() throws Exception {
        // Create a SQL file with multiline query
        File sqlFile = tempFolder.newFile("query.sql");
        String multilineSql = "SELECT \n" +
            "    message_id,\n" +
            "    message_content,\n" +
            "    correlation_id\n" +
            "FROM outbound_messages\n" +
            "WHERE status = 'PENDING'\n" +
            "ORDER BY created_at";
        Files.write(sqlFile.toPath(), multilineSql.getBytes(StandardCharsets.UTF_8));

        // Test parsing with --sql-file
        OraclePublishCommand command = new OraclePublishCommand();
        CommandLine cmdLine = new CommandLine(command);

        cmdLine.parseArgs(
            "--db-host", "localhost",
            "--db-service", "ORCL",
            "--db-user", "user",
            "--db-password", "pass",
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "solaceuser",
            "-p", "solacepass",
            "-q", "testqueue",
            "--sql-file", sqlFile.getAbsolutePath()
        );

        assertNotNull(command.sqlFile);
        assertEquals(sqlFile.getAbsolutePath(), command.sqlFile.getAbsolutePath());
        assertNull(command.sqlQuery);
    }

    @Test
    public void testSqlFileWithShortOption() throws Exception {
        // Create a SQL file
        File sqlFile = tempFolder.newFile("test.sql");
        Files.write(sqlFile.toPath(), "SELECT 1 FROM DUAL".getBytes(StandardCharsets.UTF_8));

        // Test parsing with -f (short option)
        OraclePublishCommand command = new OraclePublishCommand();
        CommandLine cmdLine = new CommandLine(command);

        cmdLine.parseArgs(
            "--db-host", "localhost",
            "--db-service", "ORCL",
            "--db-user", "user",
            "--db-password", "pass",
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "solaceuser",
            "-p", "solacepass",
            "-q", "testqueue",
            "-f", sqlFile.getAbsolutePath()
        );

        assertNotNull(command.sqlFile);
    }

    @Test
    public void testCannotUseBothSqlAndSqlFile() throws Exception {
        // Create a SQL file
        File sqlFile = tempFolder.newFile("query.sql");
        Files.write(sqlFile.toPath(), "SELECT 1 FROM DUAL".getBytes(StandardCharsets.UTF_8));

        // Parse with both --sql and --sql-file (should parse but fail at runtime)
        OraclePublishCommand command = new OraclePublishCommand();
        CommandLine cmdLine = new CommandLine(command);

        cmdLine.parseArgs(
            "--db-host", "localhost",
            "--db-service", "ORCL",
            "--db-user", "user",
            "--db-password", "pass",
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "solaceuser",
            "-p", "solacepass",
            "-q", "testqueue",
            "--sql", "SELECT 1",
            "--sql-file", sqlFile.getAbsolutePath()
        );

        // Both should be set after parsing
        assertNotNull(command.sqlQuery);
        assertNotNull(command.sqlFile);
        // The error would be caught at runtime in resolveSqlQuery()
    }

    @Test
    public void testDefaultValues() {
        OraclePublishCommand command = new OraclePublishCommand();
        CommandLine cmdLine = new CommandLine(command);

        cmdLine.parseArgs(
            "--db-host", "localhost",
            "--db-service", "ORCL",
            "--db-user", "user",
            "--db-password", "pass",
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "solaceuser",
            "-p", "solacepass",
            "-q", "testqueue",
            "--sql", "SELECT 1"
        );

        assertEquals("", command.messageColumn);
        assertNull(command.correlationColumn);
        assertEquals("PERSISTENT", command.deliveryMode);
        assertEquals(0, command.ttl);
        assertFalse(command.dryRun);
    }

    @Test
    public void testSolaceCliIncludesOraclePublish() {
        int exitCode = cmd.execute("--help");
        assertEquals(0, exitCode);
        String output = sw.toString();
        assertTrue("oracle-publish command should be listed", output.contains("oracle-publish"));
        assertTrue("ora-pub alias should be mentioned", output.contains("ora-pub"));
    }

    @Test
    public void testSecondQueueOption() {
        OraclePublishCommand command = new OraclePublishCommand();
        CommandLine cmdLine = new CommandLine(command);

        cmdLine.parseArgs(
            "--db-host", "localhost",
            "--db-service", "ORCL",
            "--db-user", "user",
            "--db-password", "pass",
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "solaceuser",
            "-p", "solacepass",
            "-q", "primary-queue",
            "--sql", "SELECT 1",
            "--second-queue", "secondary-queue"
        );

        assertEquals("primary-queue", command.solaceConnection.queue);
        assertEquals("secondary-queue", command.secondQueue);
    }

    @Test
    public void testSecondQueueShortOption() {
        OraclePublishCommand command = new OraclePublishCommand();
        CommandLine cmdLine = new CommandLine(command);

        cmdLine.parseArgs(
            "--db-host", "localhost",
            "--db-service", "ORCL",
            "--db-user", "user",
            "--db-password", "pass",
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "solaceuser",
            "-p", "solacepass",
            "-q", "primary-queue",
            "--sql", "SELECT 1",
            "-Q", "backup-queue"
        );

        assertEquals("primary-queue", command.solaceConnection.queue);
        assertEquals("backup-queue", command.secondQueue);
    }
}
