package com.example.solace;

import org.junit.Test;
import picocli.CommandLine;

import static org.junit.Assert.*;

public class ConnectionOptionsTest {

    @Test
    public void testCommandLineParsing() {
        @CommandLine.Command(name = "test")
        class TestCommand implements Runnable {
            @CommandLine.Mixin
            ConnectionOptions connectionOptions;

            @Override
            public void run() {}
        }

        TestCommand cmd = new TestCommand();
        new CommandLine(cmd).parseArgs(
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "testuser",
            "-p", "testpass",
            "-q", "test-queue"
        );

        assertEquals("tcp://localhost:55555", cmd.connectionOptions.host);
        assertEquals("default", cmd.connectionOptions.vpn);
        assertEquals("testuser", cmd.connectionOptions.username);
        assertEquals("testpass", cmd.connectionOptions.password);
        assertEquals("test-queue", cmd.connectionOptions.queue);
    }

    @Test
    public void testLongOptionNames() {
        @CommandLine.Command(name = "test")
        class TestCommand implements Runnable {
            @CommandLine.Mixin
            ConnectionOptions connectionOptions;

            @Override
            public void run() {}
        }

        TestCommand cmd = new TestCommand();
        new CommandLine(cmd).parseArgs(
            "--host", "tcp://solace.example.com:55555",
            "--vpn", "my-vpn",
            "--username", "admin",
            "--password", "secret",
            "--queue", "my-queue"
        );

        assertEquals("tcp://solace.example.com:55555", cmd.connectionOptions.host);
        assertEquals("my-vpn", cmd.connectionOptions.vpn);
        assertEquals("admin", cmd.connectionOptions.username);
        assertEquals("secret", cmd.connectionOptions.password);
        assertEquals("my-queue", cmd.connectionOptions.queue);
    }

    @Test
    public void testEmptyPasswordAllowed() {
        @CommandLine.Command(name = "test")
        class TestCommand implements Runnable {
            @CommandLine.Mixin
            ConnectionOptions connectionOptions;

            @Override
            public void run() {}
        }

        TestCommand cmd = new TestCommand();
        new CommandLine(cmd).parseArgs(
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "testuser",
            "-q", "test-queue"
        );

        assertEquals("", cmd.connectionOptions.password);
    }

    @Test(expected = CommandLine.MissingParameterException.class)
    public void testHostRequired() {
        @CommandLine.Command(name = "test")
        class TestCommand implements Runnable {
            @CommandLine.Mixin
            ConnectionOptions connectionOptions;

            @Override
            public void run() {}
        }

        TestCommand cmd = new TestCommand();
        new CommandLine(cmd).parseArgs(
            "-v", "default",
            "-u", "testuser",
            "-q", "test-queue"
        );
    }

    @Test(expected = CommandLine.MissingParameterException.class)
    public void testVpnRequired() {
        @CommandLine.Command(name = "test")
        class TestCommand implements Runnable {
            @CommandLine.Mixin
            ConnectionOptions connectionOptions;

            @Override
            public void run() {}
        }

        TestCommand cmd = new TestCommand();
        new CommandLine(cmd).parseArgs(
            "-H", "tcp://localhost:55555",
            "-u", "testuser",
            "-q", "test-queue"
        );
    }

    @Test(expected = CommandLine.MissingParameterException.class)
    public void testUsernameRequired() {
        @CommandLine.Command(name = "test")
        class TestCommand implements Runnable {
            @CommandLine.Mixin
            ConnectionOptions connectionOptions;

            @Override
            public void run() {}
        }

        TestCommand cmd = new TestCommand();
        new CommandLine(cmd).parseArgs(
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-q", "test-queue"
        );
    }

    @Test(expected = CommandLine.MissingParameterException.class)
    public void testQueueRequired() {
        @CommandLine.Command(name = "test")
        class TestCommand implements Runnable {
            @CommandLine.Mixin
            ConnectionOptions connectionOptions;

            @Override
            public void run() {}
        }

        TestCommand cmd = new TestCommand();
        new CommandLine(cmd).parseArgs(
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "testuser"
        );
    }

    @Test
    public void testSpecialCharactersInValues() {
        @CommandLine.Command(name = "test")
        class TestCommand implements Runnable {
            @CommandLine.Mixin
            ConnectionOptions connectionOptions;

            @Override
            public void run() {}
        }

        TestCommand cmd = new TestCommand();
        new CommandLine(cmd).parseArgs(
            "-H", "tcp://host-with-dash.example.com:55555",
            "-v", "vpn_with_underscore",
            "-u", "user@domain.com",
            "-p", "p@ss!word#123",
            "-q", "queue.with.dots"
        );

        assertEquals("tcp://host-with-dash.example.com:55555", cmd.connectionOptions.host);
        assertEquals("vpn_with_underscore", cmd.connectionOptions.vpn);
        assertEquals("user@domain.com", cmd.connectionOptions.username);
        assertEquals("p@ss!word#123", cmd.connectionOptions.password);
        assertEquals("queue.with.dots", cmd.connectionOptions.queue);
    }
}
