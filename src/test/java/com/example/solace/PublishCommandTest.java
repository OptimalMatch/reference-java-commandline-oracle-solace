package com.example.solace;

import org.junit.Before;
import org.junit.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;

import static org.junit.Assert.*;

public class PublishCommandTest {

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
        PublishCommand command = new PublishCommand();
        CommandLine cmdLine = new CommandLine(command);

        String usage = cmdLine.getUsageMessage();
        assertTrue(usage.contains("publish"));
        assertTrue(usage.contains("--file"));
        assertTrue(usage.contains("--count"));
        assertTrue(usage.contains("--correlation-id"));
        assertTrue(usage.contains("--delivery-mode"));
        assertTrue(usage.contains("--ttl"));
        assertTrue(usage.contains("--second-queue"));
    }

    @Test
    public void testAliases() {
        CommandLine.Command annotation = PublishCommand.class.getAnnotation(CommandLine.Command.class);
        String[] aliases = annotation.aliases();

        assertTrue(Arrays.asList(aliases).contains("pub"));
        assertTrue(Arrays.asList(aliases).contains("send"));
    }

    @Test
    public void testCommandLineParsing() {
        PublishCommand command = new PublishCommand();
        CommandLine cmdLine = new CommandLine(command);

        cmdLine.parseArgs(
            "Hello World",
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "user",
            "-p", "pass",
            "-q", "test-queue",
            "--count", "5",
            "--correlation-id", "corr-123",
            "--delivery-mode", "DIRECT",
            "--ttl", "30000",
            "--second-queue", "backup-queue"
        );

        assertEquals("Hello World", command.message);
        assertEquals("tcp://localhost:55555", command.connection.host);
        assertEquals("default", command.connection.vpn);
        assertEquals("user", command.connection.username);
        assertEquals("pass", command.connection.password);
        assertEquals("test-queue", command.connection.queue);
        assertEquals(5, command.count);
        assertEquals("corr-123", command.correlationId);
        assertEquals("DIRECT", command.deliveryMode);
        assertEquals(30000, command.ttl);
        assertEquals("backup-queue", command.secondQueue);
    }

    @Test
    public void testDefaultValues() {
        PublishCommand command = new PublishCommand();
        CommandLine cmdLine = new CommandLine(command);

        cmdLine.parseArgs(
            "Test message",
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "user",
            "-p", "pass",
            "-q", "test-queue"
        );

        assertEquals(1, command.count);
        assertNull(command.correlationId);
        assertEquals("PERSISTENT", command.deliveryMode);
        assertEquals(0, command.ttl);
        assertNull(command.secondQueue);
    }

    @Test
    public void testSecondQueueShortOption() {
        PublishCommand command = new PublishCommand();
        CommandLine cmdLine = new CommandLine(command);

        cmdLine.parseArgs(
            "Test message",
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "user",
            "-p", "pass",
            "-q", "primary-queue",
            "-Q", "secondary-queue"
        );

        assertEquals("primary-queue", command.connection.queue);
        assertEquals("secondary-queue", command.secondQueue);
    }

    @Test
    public void testSolaceCliIncludesPublish() {
        int exitCode = cmd.execute("--help");
        assertEquals(0, exitCode);
        String output = sw.toString();
        assertTrue("publish command should be listed", output.contains("publish"));
    }
}
