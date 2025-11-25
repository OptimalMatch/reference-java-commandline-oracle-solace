package com.example.solace;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import picocli.CommandLine;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.Assert.*;

public class ConsumeCommandTest {

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
        // Use CommandLine.usage to test help output
        ConsumeCommand command = new ConsumeCommand();
        CommandLine cmdLine = new CommandLine(command);

        String usage = cmdLine.getUsageMessage();
        assertTrue(usage.contains("--count"));
        assertTrue(usage.contains("--timeout"));
        assertTrue(usage.contains("--browse"));
        assertTrue(usage.contains("--verbose"));
        assertTrue(usage.contains("--output-dir"));
        assertTrue(usage.contains("--extension"));
        assertTrue(usage.contains("--prefix"));
        assertTrue(usage.contains("--use-correlation-id"));
        assertTrue(usage.contains("--use-message-id"));
    }

    @Test
    public void testAliases() {
        // Test that aliases are declared in the annotation
        CommandLine.Command annotation = ConsumeCommand.class.getAnnotation(CommandLine.Command.class);
        String[] aliases = annotation.aliases();

        assertEquals(2, aliases.length);
        assertEquals("sub", aliases[0]);
        assertEquals("receive", aliases[1]);
    }

    @Test
    public void testCommandLineParsing() {
        ConsumeCommand command = new ConsumeCommand();
        CommandLine cmdLine = new CommandLine(command);

        cmdLine.parseArgs(
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "user",
            "-p", "pass",
            "-q", "testqueue",
            "-n", "10",
            "-t", "30",
            "--browse",
            "--verbose",
            "--output-dir", tempFolder.getRoot().getAbsolutePath(),
            "--extension", ".xml",
            "--prefix", "msg_",
            "--use-correlation-id"
        );

        assertEquals("tcp://localhost:55555", command.connection.host);
        assertEquals("default", command.connection.vpn);
        assertEquals("user", command.connection.username);
        assertEquals("pass", command.connection.password);
        assertEquals("testqueue", command.connection.queue);
        assertEquals(10, command.maxMessages);
        assertEquals(30, command.timeout);
        assertTrue(command.browseOnly);
        assertTrue(command.verbose);
        assertEquals(tempFolder.getRoot().getAbsolutePath(), command.outputDir.getAbsolutePath());
        assertEquals(".xml", command.fileExtension);
        assertEquals("msg_", command.filenamePrefix);
        assertTrue(command.useCorrelationId);
    }

    @Test
    public void testDefaultValues() {
        ConsumeCommand command = new ConsumeCommand();
        CommandLine cmdLine = new CommandLine(command);

        cmdLine.parseArgs(
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "user",
            "-p", "pass",
            "-q", "testqueue"
        );

        assertEquals(0, command.maxMessages);
        assertEquals(0, command.timeout);
        assertFalse(command.browseOnly);
        assertFalse(command.noAck);
        assertFalse(command.verbose);
        assertNull(command.outputDir);
        assertEquals(".txt", command.fileExtension);
        assertEquals("message_", command.filenamePrefix);
        assertFalse(command.useCorrelationId);
        assertFalse(command.useMessageId);
    }

    @Test
    public void testExtensionWithoutDot() {
        ConsumeCommand command = new ConsumeCommand();
        CommandLine cmdLine = new CommandLine(command);

        cmdLine.parseArgs(
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "user",
            "-p", "pass",
            "-q", "testqueue",
            "--extension", "xml"  // Without leading dot
        );

        assertEquals("xml", command.fileExtension);
        // The normalization happens in call()
    }

    @Test
    public void testUseMessageIdOption() {
        ConsumeCommand command = new ConsumeCommand();
        CommandLine cmdLine = new CommandLine(command);

        cmdLine.parseArgs(
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "user",
            "-p", "pass",
            "-q", "testqueue",
            "--use-message-id"
        );

        assertTrue(command.useMessageId);
        assertFalse(command.useCorrelationId);
    }

    @Test
    public void testSolaceCliIncludesConsume() {
        int exitCode = cmd.execute("--help");
        assertEquals(0, exitCode);
        String output = sw.toString();
        assertTrue("consume command should be listed", output.contains("consume"));
        assertTrue("sub alias should work", output.contains("sub") || output.contains("receive"));
    }
}
