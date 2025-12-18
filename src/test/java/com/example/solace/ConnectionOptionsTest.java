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

    @Test
    public void testUsernameOptional() {
        // Username is now optional to support certificate-based authentication
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

        assertNull("Username should be null when not provided", cmd.connectionOptions.username);
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

    // SSL/TLS Option Tests

    @Test
    public void testSslFlagParsing() {
        @CommandLine.Command(name = "test")
        class TestCommand implements Runnable {
            @CommandLine.Mixin
            ConnectionOptions connectionOptions;

            @Override
            public void run() {}
        }

        TestCommand cmd = new TestCommand();
        new CommandLine(cmd).parseArgs(
            "-H", "tcps://localhost:55443",
            "-v", "default",
            "-q", "test-queue",
            "--ssl"
        );

        assertTrue("SSL flag should be true", cmd.connectionOptions.ssl);
        assertTrue("isSSLEnabled should return true", cmd.connectionOptions.isSSLEnabled());
    }

    @Test
    public void testSslAutoDetectFromTcpsUrl() {
        @CommandLine.Command(name = "test")
        class TestCommand implements Runnable {
            @CommandLine.Mixin
            ConnectionOptions connectionOptions;

            @Override
            public void run() {}
        }

        TestCommand cmd = new TestCommand();
        new CommandLine(cmd).parseArgs(
            "-H", "tcps://localhost:55443",
            "-v", "default",
            "-q", "test-queue"
        );

        assertFalse("SSL flag should be false (not explicitly set)", cmd.connectionOptions.ssl);
        assertTrue("isSSLEnabled should detect tcps:// URL", cmd.connectionOptions.isSSLEnabled());
    }

    @Test
    public void testKeyStoreOptions() {
        @CommandLine.Command(name = "test")
        class TestCommand implements Runnable {
            @CommandLine.Mixin
            ConnectionOptions connectionOptions;

            @Override
            public void run() {}
        }

        TestCommand cmd = new TestCommand();
        new CommandLine(cmd).parseArgs(
            "-H", "tcps://localhost:55443",
            "-v", "default",
            "-q", "test-queue",
            "--key-store", "/path/to/keystore.p12",
            "--key-store-password", "keystorepass"
        );

        assertEquals("/path/to/keystore.p12", cmd.connectionOptions.keyStore);
        assertEquals("keystorepass", cmd.connectionOptions.keyStorePassword);
        assertTrue("hasClientCertificate should return true", cmd.connectionOptions.hasClientCertificate());
    }

    @Test
    public void testTrustStoreOptions() {
        @CommandLine.Command(name = "test")
        class TestCommand implements Runnable {
            @CommandLine.Mixin
            ConnectionOptions connectionOptions;

            @Override
            public void run() {}
        }

        TestCommand cmd = new TestCommand();
        new CommandLine(cmd).parseArgs(
            "-H", "tcps://localhost:55443",
            "-v", "default",
            "-q", "test-queue",
            "--trust-store", "/path/to/truststore.jks",
            "--trust-store-password", "truststorepass"
        );

        assertEquals("/path/to/truststore.jks", cmd.connectionOptions.trustStore);
        assertEquals("truststorepass", cmd.connectionOptions.trustStorePassword);
    }

    @Test
    public void testPemCertificateOptions() {
        @CommandLine.Command(name = "test")
        class TestCommand implements Runnable {
            @CommandLine.Mixin
            ConnectionOptions connectionOptions;

            @Override
            public void run() {}
        }

        TestCommand cmd = new TestCommand();
        new CommandLine(cmd).parseArgs(
            "-H", "tcps://localhost:55443",
            "-v", "default",
            "-q", "test-queue",
            "--client-cert", "/path/to/client.pem",
            "--client-key", "/path/to/client.key",
            "--ca-cert", "/path/to/ca.pem"
        );

        assertEquals("/path/to/client.pem", cmd.connectionOptions.clientCert);
        assertEquals("/path/to/client.key", cmd.connectionOptions.clientKey);
        assertEquals("/path/to/ca.pem", cmd.connectionOptions.caCert);
        assertTrue("hasClientCertificate should return true", cmd.connectionOptions.hasClientCertificate());
    }

    @Test
    public void testSkipCertValidationOption() {
        @CommandLine.Command(name = "test")
        class TestCommand implements Runnable {
            @CommandLine.Mixin
            ConnectionOptions connectionOptions;

            @Override
            public void run() {}
        }

        TestCommand cmd = new TestCommand();
        new CommandLine(cmd).parseArgs(
            "-H", "tcps://localhost:55443",
            "-v", "default",
            "-q", "test-queue",
            "--skip-cert-validation"
        );

        assertTrue("skipCertValidation should be true", cmd.connectionOptions.skipCertValidation);
    }

    @Test
    public void testTlsVersionOption() {
        @CommandLine.Command(name = "test")
        class TestCommand implements Runnable {
            @CommandLine.Mixin
            ConnectionOptions connectionOptions;

            @Override
            public void run() {}
        }

        TestCommand cmd = new TestCommand();
        new CommandLine(cmd).parseArgs(
            "-H", "tcps://localhost:55443",
            "-v", "default",
            "-q", "test-queue",
            "--tls-version", "TLSv1.3"
        );

        assertEquals("TLSv1.3", cmd.connectionOptions.tlsVersion);
    }

    @Test
    public void testDefaultTlsVersion() {
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

        assertEquals("TLSv1.2", cmd.connectionOptions.tlsVersion);
    }

    @Test
    public void testCertificateAuthWithoutUsername() {
        // Test that we can use certificate auth without username
        @CommandLine.Command(name = "test")
        class TestCommand implements Runnable {
            @CommandLine.Mixin
            ConnectionOptions connectionOptions;

            @Override
            public void run() {}
        }

        TestCommand cmd = new TestCommand();
        new CommandLine(cmd).parseArgs(
            "-H", "tcps://localhost:55443",
            "-v", "default",
            "-q", "test-queue",
            "--key-store", "/path/to/client.p12",
            "--key-store-password", "password"
        );

        assertNull("Username should be null for cert-only auth", cmd.connectionOptions.username);
        assertTrue("hasClientCertificate should return true", cmd.connectionOptions.hasClientCertificate());
        assertTrue("isSSLEnabled should return true", cmd.connectionOptions.isSSLEnabled());
    }

    @Test
    public void testCombinedAuthWithUsernameAndCert() {
        // Test that we can use both username and certificate
        @CommandLine.Command(name = "test")
        class TestCommand implements Runnable {
            @CommandLine.Mixin
            ConnectionOptions connectionOptions;

            @Override
            public void run() {}
        }

        TestCommand cmd = new TestCommand();
        new CommandLine(cmd).parseArgs(
            "-H", "tcps://localhost:55443",
            "-v", "default",
            "-u", "myuser",
            "-p", "mypass",
            "-q", "test-queue",
            "--key-store", "/path/to/client.p12"
        );

        assertEquals("myuser", cmd.connectionOptions.username);
        assertEquals("mypass", cmd.connectionOptions.password);
        assertTrue("hasClientCertificate should return true", cmd.connectionOptions.hasClientCertificate());
    }

    @Test
    public void testHasClientCertificateRequiresBothPemFiles() {
        @CommandLine.Command(name = "test")
        class TestCommand implements Runnable {
            @CommandLine.Mixin
            ConnectionOptions connectionOptions;

            @Override
            public void run() {}
        }

        // Only client cert, no key
        TestCommand cmd1 = new TestCommand();
        new CommandLine(cmd1).parseArgs(
            "-H", "tcps://localhost:55443",
            "-v", "default",
            "-q", "test-queue",
            "--client-cert", "/path/to/client.pem"
        );
        assertFalse("hasClientCertificate should be false with only cert", cmd1.connectionOptions.hasClientCertificate());

        // Only client key, no cert
        TestCommand cmd2 = new TestCommand();
        new CommandLine(cmd2).parseArgs(
            "-H", "tcps://localhost:55443",
            "-v", "default",
            "-q", "test-queue",
            "--client-key", "/path/to/client.key"
        );
        assertFalse("hasClientCertificate should be false with only key", cmd2.connectionOptions.hasClientCertificate());
    }

    @Test
    public void testAllSslOptionsTogetherParsing() {
        @CommandLine.Command(name = "test")
        class TestCommand implements Runnable {
            @CommandLine.Mixin
            ConnectionOptions connectionOptions;

            @Override
            public void run() {}
        }

        TestCommand cmd = new TestCommand();
        new CommandLine(cmd).parseArgs(
            "-H", "tcps://broker.example.com:55443",
            "-v", "my-vpn",
            "-u", "admin",
            "-p", "secret",
            "-q", "secure-queue",
            "--ssl",
            "--key-store", "/path/to/keystore.p12",
            "--key-store-password", "kspass",
            "--trust-store", "/path/to/truststore.jks",
            "--trust-store-password", "tspass",
            "--tls-version", "TLSv1.3"
        );

        assertEquals("tcps://broker.example.com:55443", cmd.connectionOptions.host);
        assertEquals("my-vpn", cmd.connectionOptions.vpn);
        assertEquals("admin", cmd.connectionOptions.username);
        assertEquals("secret", cmd.connectionOptions.password);
        assertEquals("secure-queue", cmd.connectionOptions.queue);
        assertTrue(cmd.connectionOptions.ssl);
        assertEquals("/path/to/keystore.p12", cmd.connectionOptions.keyStore);
        assertEquals("kspass", cmd.connectionOptions.keyStorePassword);
        assertEquals("/path/to/truststore.jks", cmd.connectionOptions.trustStore);
        assertEquals("tspass", cmd.connectionOptions.trustStorePassword);
        assertEquals("TLSv1.3", cmd.connectionOptions.tlsVersion);
        assertTrue(cmd.connectionOptions.isSSLEnabled());
        assertTrue(cmd.connectionOptions.hasClientCertificate());
    }

    @Test
    public void testKeyPasswordOption() {
        @CommandLine.Command(name = "test")
        class TestCommand implements Runnable {
            @CommandLine.Mixin
            ConnectionOptions connectionOptions;

            @Override
            public void run() {}
        }

        TestCommand cmd = new TestCommand();
        new CommandLine(cmd).parseArgs(
            "-H", "tcps://localhost:55443",
            "-v", "default",
            "-q", "test-queue",
            "--key-store", "/path/to/keystore.p12",
            "--key-store-password", "keystorepass",
            "--key-password", "privatekeypass"
        );

        assertEquals("/path/to/keystore.p12", cmd.connectionOptions.keyStore);
        assertEquals("keystorepass", cmd.connectionOptions.keyStorePassword);
        assertEquals("privatekeypass", cmd.connectionOptions.keyPassword);
    }

    @Test
    public void testKeyAliasOption() {
        @CommandLine.Command(name = "test")
        class TestCommand implements Runnable {
            @CommandLine.Mixin
            ConnectionOptions connectionOptions;

            @Override
            public void run() {}
        }

        TestCommand cmd = new TestCommand();
        new CommandLine(cmd).parseArgs(
            "-H", "tcps://localhost:55443",
            "-v", "default",
            "-q", "test-queue",
            "--key-store", "/path/to/keystore.p12",
            "--key-store-password", "keystorepass",
            "--key-alias", "myclientkey"
        );

        assertEquals("/path/to/keystore.p12", cmd.connectionOptions.keyStore);
        assertEquals("keystorepass", cmd.connectionOptions.keyStorePassword);
        assertEquals("myclientkey", cmd.connectionOptions.keyAlias);
    }

    @Test
    public void testKeyPasswordAndKeyAliasTogether() {
        @CommandLine.Command(name = "test")
        class TestCommand implements Runnable {
            @CommandLine.Mixin
            ConnectionOptions connectionOptions;

            @Override
            public void run() {}
        }

        TestCommand cmd = new TestCommand();
        new CommandLine(cmd).parseArgs(
            "-H", "tcps://localhost:55443",
            "-v", "default",
            "-q", "test-queue",
            "--key-store", "/path/to/keystore.jks",
            "--key-store-password", "keystorepass",
            "--key-password", "differentkeypass",
            "--key-alias", "myalias"
        );

        assertEquals("/path/to/keystore.jks", cmd.connectionOptions.keyStore);
        assertEquals("keystorepass", cmd.connectionOptions.keyStorePassword);
        assertEquals("differentkeypass", cmd.connectionOptions.keyPassword);
        assertEquals("myalias", cmd.connectionOptions.keyAlias);
        assertTrue(cmd.connectionOptions.hasClientCertificate());
    }

    @Test
    public void testKeyPasswordAndKeyAliasDefaultToNull() {
        @CommandLine.Command(name = "test")
        class TestCommand implements Runnable {
            @CommandLine.Mixin
            ConnectionOptions connectionOptions;

            @Override
            public void run() {}
        }

        TestCommand cmd = new TestCommand();
        new CommandLine(cmd).parseArgs(
            "-H", "tcps://localhost:55443",
            "-v", "default",
            "-q", "test-queue",
            "--key-store", "/path/to/keystore.p12",
            "--key-store-password", "keystorepass"
        );

        assertNull("keyPassword should be null when not provided", cmd.connectionOptions.keyPassword);
        assertNull("keyAlias should be null when not provided", cmd.connectionOptions.keyAlias);
    }
}
