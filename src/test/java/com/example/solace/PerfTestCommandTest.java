package com.example.solace;

import org.junit.Before;
import org.junit.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.Assert.*;

public class PerfTestCommandTest {

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
        PerfTestCommand command = new PerfTestCommand();
        CommandLine cmdLine = new CommandLine(command);

        String usage = cmdLine.getUsageMessage();
        assertTrue(usage.contains("perf-test"));
        assertTrue(usage.contains("--mode"));
        assertTrue(usage.contains("--count"));
        assertTrue(usage.contains("--size"));
        assertTrue(usage.contains("--rate"));
        assertTrue(usage.contains("--warmup"));
        assertTrue(usage.contains("--threads"));
        assertTrue(usage.contains("--delivery-mode"));
        assertTrue(usage.contains("--report-interval"));
        assertTrue(usage.contains("--latency"));
    }

    @Test
    public void testAliases() {
        CommandLine.Command annotation = PerfTestCommand.class.getAnnotation(CommandLine.Command.class);
        String[] aliases = annotation.aliases();

        assertEquals(2, aliases.length);
        assertEquals("perf", aliases[0]);
        assertEquals("benchmark", aliases[1]);
    }

    @Test
    public void testCommandLineParsing() {
        PerfTestCommand command = new PerfTestCommand();
        CommandLine cmdLine = new CommandLine(command);

        cmdLine.parseArgs(
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "user",
            "-p", "pass",
            "-q", "testqueue",
            "--mode", "both",
            "--count", "5000",
            "--size", "256",
            "--rate", "1000",
            "--warmup", "200",
            "--threads", "2",
            "--delivery-mode", "DIRECT",
            "--report-interval", "10",
            "--latency"
        );

        assertEquals("tcp://localhost:55555", command.connection.host);
        assertEquals("default", command.connection.vpn);
        assertEquals("user", command.connection.username);
        assertEquals("pass", command.connection.password);
        assertEquals("testqueue", command.connection.queue);
        assertEquals("both", command.mode);
        assertEquals(5000, command.messageCount);
        assertEquals(256, command.messageSize);
        assertEquals(1000, command.targetRate);
        assertEquals(200, command.warmupCount);
        assertEquals(2, command.threadCount);
        assertEquals("DIRECT", command.deliveryMode);
        assertEquals(10, command.reportInterval);
        assertTrue(command.measureLatency);
    }

    @Test
    public void testDefaultValues() {
        PerfTestCommand command = new PerfTestCommand();
        CommandLine cmdLine = new CommandLine(command);

        cmdLine.parseArgs(
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "user",
            "-p", "pass",
            "-q", "testqueue"
        );

        assertEquals("publish", command.mode);
        assertEquals(1000, command.messageCount);
        assertEquals(100, command.messageSize);
        assertEquals(0, command.targetRate);
        assertEquals(100, command.warmupCount);
        assertEquals(1, command.threadCount);
        assertEquals("PERSISTENT", command.deliveryMode);
        assertEquals(5, command.reportInterval);
        assertFalse(command.measureLatency);
    }

    @Test
    public void testModeOptions() {
        PerfTestCommand command = new PerfTestCommand();
        CommandLine cmdLine = new CommandLine(command);

        // Test publish mode
        cmdLine.parseArgs(
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "user",
            "-p", "pass",
            "-q", "testqueue",
            "--mode", "publish"
        );
        assertEquals("publish", command.mode);

        // Test consume mode
        command = new PerfTestCommand();
        cmdLine = new CommandLine(command);
        cmdLine.parseArgs(
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "user",
            "-p", "pass",
            "-q", "testqueue",
            "-m", "consume"
        );
        assertEquals("consume", command.mode);

        // Test both mode
        command = new PerfTestCommand();
        cmdLine = new CommandLine(command);
        cmdLine.parseArgs(
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "user",
            "-p", "pass",
            "-q", "testqueue",
            "-m", "both"
        );
        assertEquals("both", command.mode);
    }

    @Test
    public void testSolaceCliIncludesPerfTest() {
        int exitCode = cmd.execute("--help");
        assertEquals(0, exitCode);
        String output = sw.toString();
        assertTrue("perf-test command should be listed", output.contains("perf-test"));
    }

    @Test
    public void testShortOptions() {
        PerfTestCommand command = new PerfTestCommand();
        CommandLine cmdLine = new CommandLine(command);

        cmdLine.parseArgs(
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "user",
            "-p", "pass",
            "-q", "testqueue",
            "-m", "publish",
            "-c", "2000",
            "-s", "512",
            "-r", "500",
            "-t", "4"
        );

        assertEquals("publish", command.mode);
        assertEquals(2000, command.messageCount);
        assertEquals(512, command.messageSize);
        assertEquals(500, command.targetRate);
        assertEquals(4, command.threadCount);
    }

    @Test
    public void testDeliveryModeOptions() {
        PerfTestCommand command = new PerfTestCommand();
        CommandLine cmdLine = new CommandLine(command);

        // Test PERSISTENT mode
        cmdLine.parseArgs(
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "user",
            "-p", "pass",
            "-q", "testqueue",
            "--delivery-mode", "PERSISTENT"
        );
        assertEquals("PERSISTENT", command.deliveryMode);

        // Test DIRECT mode
        command = new PerfTestCommand();
        cmdLine = new CommandLine(command);
        cmdLine.parseArgs(
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "user",
            "-p", "pass",
            "-q", "testqueue",
            "--delivery-mode", "DIRECT"
        );
        assertEquals("DIRECT", command.deliveryMode);
    }
}
