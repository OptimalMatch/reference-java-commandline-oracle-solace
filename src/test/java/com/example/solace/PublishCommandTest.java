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

    @Test
    public void testTopicOption() {
        PublishCommand command = new PublishCommand();
        CommandLine cmdLine = new CommandLine(command);

        cmdLine.parseArgs(
            "Test message",
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "user",
            "-p", "pass",
            "-q", "test-queue",
            "-T", "my/test/topic"
        );

        assertEquals("my/test/topic", command.topic);
        assertEquals("test-queue", command.connection.queue);
    }

    @Test
    public void testTopicLongOption() {
        PublishCommand command = new PublishCommand();
        CommandLine cmdLine = new CommandLine(command);

        cmdLine.parseArgs(
            "Test message",
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "user",
            "-p", "pass",
            "-q", "test-queue",
            "--topic", "orders/new/customer"
        );

        assertEquals("orders/new/customer", command.topic);
    }

    @Test
    public void testTopicDefaultValue() {
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

        assertNull("topic should be null by default", command.topic);
    }

    @Test
    public void testTopicWithOtherOptions() {
        PublishCommand command = new PublishCommand();
        CommandLine cmdLine = new CommandLine(command);

        cmdLine.parseArgs(
            "Test message",
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "user",
            "-p", "pass",
            "-q", "test-queue",
            "-T", "events/processed",
            "--correlation-id", "corr-456",
            "--delivery-mode", "DIRECT",
            "-Q", "backup-queue"
        );

        assertEquals("events/processed", command.topic);
        assertEquals("corr-456", command.correlationId);
        assertEquals("DIRECT", command.deliveryMode);
        assertEquals("backup-queue", command.secondQueue);
    }

    @Test
    public void testHelpOutputIncludesTopic() {
        PublishCommand command = new PublishCommand();
        CommandLine cmdLine = new CommandLine(command);

        String usage = cmdLine.getUsageMessage();
        assertTrue("--topic option should be in help", usage.contains("--topic"));
        assertTrue("-T option should be in help", usage.contains("-T"));
    }

    @Test
    public void testDescriptionIncludesTopic() {
        CommandLine.Command annotation = PublishCommand.class.getAnnotation(CommandLine.Command.class);
        String description = annotation.description()[0];

        assertTrue("description should mention topic", description.toLowerCase().contains("topic"));
    }
}
