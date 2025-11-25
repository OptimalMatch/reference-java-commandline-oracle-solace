package com.example.solace;

import org.junit.Before;
import org.junit.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;

import static org.junit.Assert.*;

public class CopyQueueCommandTest {

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
        CopyQueueCommand command = new CopyQueueCommand();
        CommandLine cmdLine = new CommandLine(command);

        String usage = cmdLine.getUsageMessage();
        assertTrue(usage.contains("copy-queue"));
        assertTrue(usage.contains("--dest"));
        assertTrue(usage.contains("--count"));
        assertTrue(usage.contains("--timeout"));
        assertTrue(usage.contains("--move"));
        assertTrue(usage.contains("--preserve-properties"));
        assertTrue(usage.contains("--delivery-mode"));
        assertTrue(usage.contains("--dry-run"));
    }

    @Test
    public void testAliases() {
        CommandLine.Command annotation = CopyQueueCommand.class.getAnnotation(CommandLine.Command.class);
        String[] aliases = annotation.aliases();

        assertEquals(2, aliases.length);
        assertTrue(Arrays.asList(aliases).contains("copy"));
        assertTrue(Arrays.asList(aliases).contains("cp"));
    }

    @Test
    public void testCommandLineParsing() {
        CopyQueueCommand command = new CopyQueueCommand();
        CommandLine cmdLine = new CommandLine(command);

        cmdLine.parseArgs(
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "user",
            "-p", "pass",
            "-q", "source-queue",
            "--dest", "dest-queue",
            "--count", "100",
            "--timeout", "10",
            "--move",
            "--preserve-properties",
            "--delivery-mode", "DIRECT",
            "--dry-run"
        );

        assertEquals("tcp://localhost:55555", command.connection.host);
        assertEquals("default", command.connection.vpn);
        assertEquals("user", command.connection.username);
        assertEquals("pass", command.connection.password);
        assertEquals("source-queue", command.connection.queue);
        assertEquals("dest-queue", command.destinationQueue);
        assertEquals(100, command.maxCount);
        assertEquals(10, command.timeout);
        assertTrue(command.moveMessages);
        assertTrue(command.preserveProperties);
        assertEquals("DIRECT", command.deliveryMode);
        assertTrue(command.dryRun);
    }

    @Test
    public void testDefaultValues() {
        CopyQueueCommand command = new CopyQueueCommand();
        CommandLine cmdLine = new CommandLine(command);

        cmdLine.parseArgs(
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "user",
            "-p", "pass",
            "-q", "source-queue",
            "--dest", "dest-queue"
        );

        assertEquals(0, command.maxCount);
        assertEquals(5, command.timeout);
        assertFalse(command.moveMessages);
        assertFalse(command.preserveProperties);
        assertEquals("", command.deliveryMode);
        assertFalse(command.dryRun);
    }

    @Test
    public void testShortOptions() {
        CopyQueueCommand command = new CopyQueueCommand();
        CommandLine cmdLine = new CommandLine(command);

        cmdLine.parseArgs(
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "user",
            "-p", "pass",
            "-q", "source-queue",
            "-d", "dest-queue",
            "-c", "50",
            "-t", "15"
        );

        assertEquals("dest-queue", command.destinationQueue);
        assertEquals(50, command.maxCount);
        assertEquals(15, command.timeout);
    }

    @Test
    public void testSolaceCliIncludesCopyQueue() {
        int exitCode = cmd.execute("--help");
        assertEquals(0, exitCode);
        String output = sw.toString();
        assertTrue("copy-queue command should be listed", output.contains("copy-queue"));
    }

    @Test
    public void testDestinationQueueRequired() {
        CopyQueueCommand command = new CopyQueueCommand();
        CommandLine cmdLine = new CommandLine(command);

        // Try parsing without --dest - should fail
        try {
            cmdLine.parseArgs(
                "-H", "tcp://localhost:55555",
                "-v", "default",
                "-u", "user",
                "-p", "pass",
                "-q", "source-queue"
            );
            fail("Should have thrown exception for missing required --dest option");
        } catch (CommandLine.MissingParameterException e) {
            assertTrue(e.getMessage().contains("dest"));
        }
    }
}
