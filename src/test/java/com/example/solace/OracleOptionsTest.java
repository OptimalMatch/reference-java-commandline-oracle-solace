package com.example.solace;

import org.junit.Test;
import picocli.CommandLine;

import static org.junit.Assert.*;

public class OracleOptionsTest {

    @Test
    public void testGetJdbcUrl_defaultPort() {
        OracleOptions options = new OracleOptions();
        options.dbHost = "localhost";
        options.dbPort = 1521;
        options.dbService = "ORCL";

        String jdbcUrl = options.getJdbcUrl();

        assertEquals("jdbc:oracle:thin:@//localhost:1521/ORCL", jdbcUrl);
    }

    @Test
    public void testGetJdbcUrl_customPort() {
        OracleOptions options = new OracleOptions();
        options.dbHost = "db.example.com";
        options.dbPort = 1522;
        options.dbService = "PROD";

        String jdbcUrl = options.getJdbcUrl();

        assertEquals("jdbc:oracle:thin:@//db.example.com:1522/PROD", jdbcUrl);
    }

    @Test
    public void testGetJdbcUrl_withIpAddress() {
        OracleOptions options = new OracleOptions();
        options.dbHost = "192.168.1.100";
        options.dbPort = 1521;
        options.dbService = "XE";

        String jdbcUrl = options.getJdbcUrl();

        assertEquals("jdbc:oracle:thin:@//192.168.1.100:1521/XE", jdbcUrl);
    }

    @Test
    public void testCommandLineParsing() {
        @CommandLine.Command(name = "test")
        class TestCommand implements Runnable {
            @CommandLine.Mixin
            OracleOptions oracleOptions;

            @Override
            public void run() {}
        }

        TestCommand cmd = new TestCommand();
        new CommandLine(cmd).parseArgs(
            "--db-host", "myhost",
            "--db-port", "1525",
            "--db-service", "MYSERVICE",
            "--db-user", "myuser",
            "--db-password", "mypass"
        );

        assertEquals("myhost", cmd.oracleOptions.dbHost);
        assertEquals(1525, cmd.oracleOptions.dbPort);
        assertEquals("MYSERVICE", cmd.oracleOptions.dbService);
        assertEquals("myuser", cmd.oracleOptions.dbUser);
        assertEquals("mypass", cmd.oracleOptions.dbPassword);
    }

    @Test
    public void testDefaultPort() {
        @CommandLine.Command(name = "test")
        class TestCommand implements Runnable {
            @CommandLine.Mixin
            OracleOptions oracleOptions;

            @Override
            public void run() {}
        }

        TestCommand cmd = new TestCommand();
        new CommandLine(cmd).parseArgs(
            "--db-host", "myhost",
            "--db-service", "MYSERVICE",
            "--db-user", "myuser"
        );

        assertEquals(1521, cmd.oracleOptions.dbPort);
    }
}
