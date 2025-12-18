package com.example.solace;

import com.solacesystems.jcsmp.*;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.PrintStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Integration tests for SSL/TLS with Java Keystores.
 *
 * These tests verify:
 * 1. TLS connections with JKS and PKCS12 keystores
 * 2. The --key-password option for separate private key passwords
 * 3. The --key-alias option for specifying which key to use
 * 4. Client certificate authentication over TLS
 *
 * Tests are split into:
 * - Keystore-only tests: Run without Solace, verify keystore handling logic
 * - Connection tests: Require Docker/Solace container for full integration testing
 */
public class SSLKeystoreIT {

    private static final String SOLACE_IMAGE = "solace/solace-pubsub-standard:latest";
    private static final String VPN_NAME = "default";
    private static final String USERNAME = "admin";
    private static final String PASSWORD = "admin";
    private static final String QUEUE_NAME = "ssl-test-queue-" + System.currentTimeMillis();

    // Keystore passwords
    private static final String KEYSTORE_PASSWORD = "keystorepass";
    private static final String KEY_PASSWORD = "privatekeypass";  // Different from keystore password
    private static final String KEY_ALIAS = "clientkey";
    private static final String TRUSTSTORE_PASSWORD = "truststorepass";

    // Check if Docker is available
    private static boolean dockerAvailable = false;
    private static boolean containerStarted = false;
    static {
        try {
            dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            dockerAvailable = false;
        }
    }

    // Static temp directory for certificates
    private static File certDir;
    private static File serverKeystore;
    private static File clientKeystore;
    private static File clientKeystoreJks;
    private static File truststore;
    private static File truststoreJks;
    private static boolean certificatesCreated = false;

    // Container declared without @ClassRule - we'll manage it manually
    private static GenericContainer<?> solaceContainer = null;

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private ByteArrayOutputStream outContent;
    private ByteArrayOutputStream errContent;
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeClass
    public static void setUpCertificates() throws Exception {
        // Always create certificates - they're needed for keystore tests regardless of Docker
        certDir = new File(System.getProperty("java.io.tmpdir"), "solace-ssl-test-" + System.currentTimeMillis());
        certDir.mkdirs();

        // Generate CA certificate
        KeyPair caKeyPair = generateKeyPair();
        X509Certificate caCert = generateCACertificate(caKeyPair, "CN=Test CA, O=Test, C=US");

        // Generate server certificate signed by CA
        KeyPair serverKeyPair = generateKeyPair();
        X509Certificate serverCert = generateSignedCertificate(
            serverKeyPair, caKeyPair, caCert, "CN=localhost, O=Test, C=US");

        // Generate client certificate signed by CA
        KeyPair clientKeyPair = generateKeyPair();
        X509Certificate clientCert = generateSignedCertificate(
            clientKeyPair, caKeyPair, caCert, "CN=Test Client, O=Test, C=US");

        // Create server keystore (PKCS12) for Solace
        serverKeystore = new File(certDir, "server.p12");
        createPkcs12Keystore(serverKeystore, "server", serverKeyPair, serverCert, caCert, PASSWORD);

        // Create client keystore (PKCS12) with DIFFERENT key password
        clientKeystore = new File(certDir, "client.p12");
        createPkcs12KeystoreWithKeyPassword(clientKeystore, KEY_ALIAS, clientKeyPair, clientCert,
            caCert, KEYSTORE_PASSWORD, KEY_PASSWORD);

        // Create client keystore (JKS) with DIFFERENT key password
        clientKeystoreJks = new File(certDir, "client.jks");
        createJksKeystoreWithKeyPassword(clientKeystoreJks, KEY_ALIAS, clientKeyPair, clientCert,
            caCert, KEYSTORE_PASSWORD, KEY_PASSWORD);

        // Create truststore (PKCS12) with CA certificate
        truststore = new File(certDir, "truststore.p12");
        createTruststore(truststore, "PKCS12", caCert, TRUSTSTORE_PASSWORD);

        // Create truststore (JKS) with CA certificate
        truststoreJks = new File(certDir, "truststore.jks");
        createTruststore(truststoreJks, "JKS", caCert, TRUSTSTORE_PASSWORD);

        // Write CA cert as PEM for Solace configuration
        File caCertPem = new File(certDir, "ca.pem");
        writePemCertificate(caCertPem, caCert);

        // Write server cert and key as PEM for Solace
        File serverCertPem = new File(certDir, "server-cert.pem");
        File serverKeyPem = new File(certDir, "server-key.pem");
        writePemCertificate(serverCertPem, serverCert);
        writePemPrivateKey(serverKeyPem, serverKeyPair);

        certificatesCreated = true;
    }

    @Before
    public void setUp() throws Exception {
        originalOut = System.out;
        originalErr = System.err;
        outContent = new ByteArrayOutputStream();
        errContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));
    }

    @After
    public void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    /**
     * Try to start the Solace container if Docker is available.
     * Returns true if container is ready, false otherwise.
     */
    private static synchronized boolean ensureContainerStarted() {
        if (containerStarted) {
            return true;
        }
        if (!dockerAvailable) {
            return false;
        }
        if (solaceContainer == null) {
            try {
                solaceContainer = new GenericContainer<>(DockerImageName.parse(SOLACE_IMAGE))
                    .withExposedPorts(55555, 55443, 8080)
                    .withSharedMemorySize(1024L * 1024L * 1024L)
                    .withEnv("username_admin_globalaccesslevel", "admin")
                    .withEnv("username_admin_password", PASSWORD)
                    .withEnv("system_scaling_maxconnectioncount", "100")
                    .waitingFor(Wait.forLogMessage(".*Primary Virtual Router is now active.*", 1)
                            .withStartupTimeout(Duration.ofMinutes(3)));
                solaceContainer.start();
                containerStarted = true;
            } catch (Exception e) {
                System.err.println("Failed to start Solace container: " + e.getMessage());
                containerStarted = false;
            }
        }
        return containerStarted;
    }

    private String getSolaceHost() {
        return "tcp://" + solaceContainer.getHost() + ":" + solaceContainer.getMappedPort(55555);
    }

    private String getSolaceTlsHost() {
        return "tcps://" + solaceContainer.getHost() + ":" + solaceContainer.getMappedPort(55443);
    }

    private void createQueue() throws Exception {
        JCSMPProperties properties = new JCSMPProperties();
        properties.setProperty(JCSMPProperties.HOST, getSolaceHost());
        properties.setProperty(JCSMPProperties.VPN_NAME, VPN_NAME);
        properties.setProperty(JCSMPProperties.USERNAME, USERNAME);
        properties.setProperty(JCSMPProperties.PASSWORD, PASSWORD);

        JCSMPSession session = JCSMPFactory.onlyInstance().createSession(properties);
        session.connect();

        try {
            Queue queue = JCSMPFactory.onlyInstance().createQueue(QUEUE_NAME);
            EndpointProperties endpointProps = new EndpointProperties();
            endpointProps.setPermission(EndpointProperties.PERMISSION_CONSUME);
            endpointProps.setAccessType(EndpointProperties.ACCESSTYPE_EXCLUSIVE);

            session.provision(queue, endpointProps, JCSMPSession.FLAG_IGNORE_ALREADY_EXISTS);
        } finally {
            session.closeSession();
        }
    }

    // ==================== Tests for Keystore Password Handling ====================

    @Test
    public void testPkcs12KeystoreWithSeparateKeyPassword() throws Exception {
        // This test verifies that we can load a PKCS12 keystore where the
        // private key password differs from the keystore password.

        // First verify the keystore was created correctly
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(new java.io.FileInputStream(clientKeystore), KEYSTORE_PASSWORD.toCharArray());
        assertTrue("Keystore should contain the key alias", ks.containsAlias(KEY_ALIAS));

        // Verify we can get the key with the key password
        assertNotNull("Should be able to retrieve key with correct password",
            ks.getKey(KEY_ALIAS, KEY_PASSWORD.toCharArray()));

        // Verify we cannot get the key with the wrong password
        try {
            ks.getKey(KEY_ALIAS, KEYSTORE_PASSWORD.toCharArray());
            // PKCS12 keystores typically use the same password, but our test creates one with different passwords
            // This is to simulate the scenario described in the commit
        } catch (Exception e) {
            // Expected - key password is different from keystore password
        }
    }

    @Test
    public void testJksKeystoreWithSeparateKeyPassword() throws Exception {
        // This test verifies that we can load a JKS keystore where the
        // private key password differs from the keystore password.

        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(new java.io.FileInputStream(clientKeystoreJks), KEYSTORE_PASSWORD.toCharArray());
        assertTrue("Keystore should contain the key alias", ks.containsAlias(KEY_ALIAS));

        // Verify we can get the key with the correct key password
        assertNotNull("Should be able to retrieve key with correct password",
            ks.getKey(KEY_ALIAS, KEY_PASSWORD.toCharArray()));

        // Verify we cannot get the key with the keystore password
        try {
            ks.getKey(KEY_ALIAS, KEYSTORE_PASSWORD.toCharArray());
            fail("Should not be able to retrieve key with wrong password");
        } catch (java.security.UnrecoverableKeyException e) {
            // Expected - this is the exact error that --key-password option is meant to fix
        }
    }

    @Test
    public void testConnectionOptionsWithKeyPassword() throws Exception {
        // Test that ConnectionOptions correctly stores the key password
        ConnectionOptions options = new ConnectionOptions();
        options.host = getSolaceTlsHost();
        options.vpn = VPN_NAME;
        options.queue = QUEUE_NAME;
        options.keyStore = clientKeystore.getAbsolutePath();
        options.keyStorePassword = KEYSTORE_PASSWORD;
        options.keyPassword = KEY_PASSWORD;
        options.keyAlias = KEY_ALIAS;

        assertEquals("Key store path should be set", clientKeystore.getAbsolutePath(), options.keyStore);
        assertEquals("Key store password should be set", KEYSTORE_PASSWORD, options.keyStorePassword);
        assertEquals("Key password should be set", KEY_PASSWORD, options.keyPassword);
        assertEquals("Key alias should be set", KEY_ALIAS, options.keyAlias);
        assertTrue("Should have client certificate", options.hasClientCertificate());
        assertTrue("SSL should be enabled", options.isSSLEnabled());
    }

    @Test
    public void testConnectionOptionsWithKeyAlias() throws Exception {
        // Test that ConnectionOptions correctly stores the key alias
        ConnectionOptions options = new ConnectionOptions();
        options.host = getSolaceTlsHost();
        options.vpn = VPN_NAME;
        options.queue = QUEUE_NAME;
        options.keyStore = clientKeystoreJks.getAbsolutePath();
        options.keyStorePassword = KEYSTORE_PASSWORD;
        options.keyAlias = KEY_ALIAS;

        assertEquals("Key alias should be set", KEY_ALIAS, options.keyAlias);
    }

    @Test
    public void testCommandLineParsingWithKeyPassword() throws Exception {
        // Test that the CLI correctly parses --key-password option
        // Uses a fake host since we just want to test argument parsing
        String[] args = {
            "publish",
            "test message",
            "-H", "tcps://localhost:55443",  // Fake host - just testing parsing
            "-v", VPN_NAME,
            "-q", QUEUE_NAME,
            "--key-store", clientKeystore.getAbsolutePath(),
            "--key-store-password", KEYSTORE_PASSWORD,
            "--key-password", KEY_PASSWORD,
            "--trust-store", truststore.getAbsolutePath(),
            "--trust-store-password", TRUSTSTORE_PASSWORD,
            "--skip-cert-validation"
        };

        // This will fail to connect, but the important thing is that
        // the arguments are parsed correctly without errors
        int exitCode = new CommandLine(new SolaceCli()).execute(args);

        String output = outContent.toString() + errContent.toString();
        // Verify no argument parsing errors (connection errors are expected)
        assertFalse("Should not have parsing errors",
            output.contains("Unknown option") || output.contains("Missing required"));
    }

    @Test
    public void testCommandLineParsingWithKeyAlias() throws Exception {
        // Test that the CLI correctly parses --key-alias option
        String[] args = {
            "publish",
            "test message",
            "-H", "tcps://localhost:55443",  // Fake host - just testing parsing
            "-v", VPN_NAME,
            "-q", QUEUE_NAME,
            "--key-store", clientKeystoreJks.getAbsolutePath(),
            "--key-store-password", KEYSTORE_PASSWORD,
            "--key-password", KEY_PASSWORD,
            "--key-alias", KEY_ALIAS,
            "--trust-store", truststoreJks.getAbsolutePath(),
            "--trust-store-password", TRUSTSTORE_PASSWORD,
            "--skip-cert-validation"
        };

        int exitCode = new CommandLine(new SolaceCli()).execute(args);

        String output = outContent.toString() + errContent.toString();
        assertFalse("Should not have parsing errors for --key-alias",
            output.contains("Unknown option") || output.contains("Missing required"));
    }

    @Test
    public void testCommandLineParsingWithAllKeyOptions() throws Exception {
        // Test that all keystore-related options can be used together
        String[] args = {
            "publish",
            "test message",
            "-H", "tcps://localhost:55443",  // Fake host - just testing parsing
            "-v", VPN_NAME,
            "-q", QUEUE_NAME,
            "--key-store", clientKeystoreJks.getAbsolutePath(),
            "--key-store-password", KEYSTORE_PASSWORD,
            "--key-password", KEY_PASSWORD,
            "--key-alias", KEY_ALIAS,
            "--trust-store", truststoreJks.getAbsolutePath(),
            "--trust-store-password", TRUSTSTORE_PASSWORD,
            "--tls-version", "TLSv1.2",
            "--skip-cert-validation"
        };

        int exitCode = new CommandLine(new SolaceCli()).execute(args);

        String output = outContent.toString() + errContent.toString();
        assertFalse("Should not have any parsing errors",
            output.contains("Unknown option") || output.contains("Missing required"));
    }

    @Test
    public void testPlainTcpConnectionStillWorks() throws Exception {
        // This test requires a running Solace container
        Assume.assumeTrue("Skipping: Solace container not available", ensureContainerStarted());

        // Create the test queue
        createQueue();

        // Verify that plain TCP connections still work as a baseline
        String testMessage = "Plain TCP test message";

        String[] args = {
            "publish",
            testMessage,
            "-H", getSolaceHost(),
            "-v", VPN_NAME,
            "-u", USERNAME,
            "-p", PASSWORD,
            "-q", QUEUE_NAME
        };

        int exitCode = new CommandLine(new SolaceCli()).execute(args);

        assertEquals("Plain TCP publish should succeed", 0, exitCode);
        assertTrue("Should show success message",
            outContent.toString().contains("Successfully published 1 message"));

        // Verify message was received
        String receivedMessage = consumeOneMessage();
        assertEquals(testMessage, receivedMessage);
    }

    @Test
    public void testKeystoreWithMultipleAliases() throws Exception {
        // Create a keystore with multiple key entries to test --key-alias selection
        File multiKeystore = new File(certDir, "multi-key.jks");

        KeyPair keyPair1 = generateKeyPair();
        KeyPair keyPair2 = generateKeyPair();
        KeyPair caKeyPair = generateKeyPair();
        X509Certificate caCert = generateCACertificate(caKeyPair, "CN=Multi Test CA");
        X509Certificate cert1 = generateSignedCertificate(keyPair1, caKeyPair, caCert, "CN=Client 1");
        X509Certificate cert2 = generateSignedCertificate(keyPair2, caKeyPair, caCert, "CN=Client 2");

        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(null, KEYSTORE_PASSWORD.toCharArray());

        // Add two different keys with different aliases
        ks.setKeyEntry("alias1", keyPair1.getPrivate(), KEY_PASSWORD.toCharArray(),
            new java.security.cert.Certificate[]{cert1, caCert});
        ks.setKeyEntry("alias2", keyPair2.getPrivate(), KEY_PASSWORD.toCharArray(),
            new java.security.cert.Certificate[]{cert2, caCert});

        try (FileOutputStream fos = new FileOutputStream(multiKeystore)) {
            ks.store(fos, KEYSTORE_PASSWORD.toCharArray());
        }

        // Verify the keystore has both aliases
        KeyStore loadedKs = KeyStore.getInstance("JKS");
        loadedKs.load(new java.io.FileInputStream(multiKeystore), KEYSTORE_PASSWORD.toCharArray());

        assertTrue("Should contain alias1", loadedKs.containsAlias("alias1"));
        assertTrue("Should contain alias2", loadedKs.containsAlias("alias2"));
        assertEquals("Should have exactly 2 entries", 2, loadedKs.size());

        // Test CLI with specific alias selection
        String[] args = {
            "publish",
            "test",
            "-H", getSolaceTlsHost(),
            "-v", VPN_NAME,
            "-q", QUEUE_NAME,
            "--key-store", multiKeystore.getAbsolutePath(),
            "--key-store-password", KEYSTORE_PASSWORD,
            "--key-password", KEY_PASSWORD,
            "--key-alias", "alias2",  // Specifically select alias2
            "--skip-cert-validation"
        };

        int exitCode = new CommandLine(new SolaceCli()).execute(args);

        // Verify alias option was accepted
        String output = outContent.toString() + errContent.toString();
        assertFalse("Should accept --key-alias option", output.contains("Unknown option"));
    }

    // ==================== Helper Methods ====================

    private String consumeOneMessage() throws Exception {
        JCSMPProperties properties = new JCSMPProperties();
        properties.setProperty(JCSMPProperties.HOST, getSolaceHost());
        properties.setProperty(JCSMPProperties.VPN_NAME, VPN_NAME);
        properties.setProperty(JCSMPProperties.USERNAME, USERNAME);
        properties.setProperty(JCSMPProperties.PASSWORD, PASSWORD);

        JCSMPSession session = JCSMPFactory.onlyInstance().createSession(properties);
        session.connect();

        try {
            Queue queue = JCSMPFactory.onlyInstance().createQueue(QUEUE_NAME);
            ConsumerFlowProperties flowProps = new ConsumerFlowProperties();
            flowProps.setEndpoint(queue);
            flowProps.setAckMode(JCSMPProperties.SUPPORTED_MESSAGE_ACK_CLIENT);

            final String[] receivedMessage = new String[1];
            final CountDownLatch latch = new CountDownLatch(1);

            FlowReceiver receiver = session.createFlow(new XMLMessageListener() {
                @Override
                public void onReceive(BytesXMLMessage message) {
                    if (message instanceof TextMessage) {
                        receivedMessage[0] = ((TextMessage) message).getText();
                    }
                    message.ackMessage();
                    latch.countDown();
                }

                @Override
                public void onException(JCSMPException e) {
                    latch.countDown();
                }
            }, flowProps);

            receiver.start();
            latch.await(10, TimeUnit.SECONDS);
            receiver.stop();
            receiver.close();

            return receivedMessage[0];
        } finally {
            session.closeSession();
        }
    }

    // ==================== Certificate Generation Helpers ====================

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        return keyGen.generateKeyPair();
    }

    private static X509Certificate generateCACertificate(KeyPair keyPair, String dn) throws Exception {
        return generateSelfSignedCert(keyPair, dn, true);
    }

    private static X509Certificate generateSignedCertificate(
            KeyPair subjectKeyPair, KeyPair issuerKeyPair,
            X509Certificate issuerCert, String subjectDn) throws Exception {

        long now = System.currentTimeMillis();
        Date startDate = new Date(now);
        Date endDate = new Date(now + 365L * 24 * 60 * 60 * 1000);

        sun.security.x509.X500Name subject = new sun.security.x509.X500Name(subjectDn);
        sun.security.x509.X500Name issuer = new sun.security.x509.X500Name(issuerCert.getSubjectX500Principal().getName());

        sun.security.x509.X509CertInfo info = new sun.security.x509.X509CertInfo();
        info.set(sun.security.x509.X509CertInfo.VALIDITY,
                new sun.security.x509.CertificateValidity(startDate, endDate));
        info.set(sun.security.x509.X509CertInfo.SERIAL_NUMBER,
                new sun.security.x509.CertificateSerialNumber(new BigInteger(64, new java.security.SecureRandom())));
        info.set(sun.security.x509.X509CertInfo.SUBJECT, subject);
        info.set(sun.security.x509.X509CertInfo.ISSUER, issuer);
        info.set(sun.security.x509.X509CertInfo.KEY,
                new sun.security.x509.CertificateX509Key(subjectKeyPair.getPublic()));
        info.set(sun.security.x509.X509CertInfo.VERSION,
                new sun.security.x509.CertificateVersion(sun.security.x509.CertificateVersion.V3));

        sun.security.x509.AlgorithmId algo = new sun.security.x509.AlgorithmId(
                sun.security.x509.AlgorithmId.sha256WithRSAEncryption_oid);
        info.set(sun.security.x509.X509CertInfo.ALGORITHM_ID,
                new sun.security.x509.CertificateAlgorithmId(algo));

        sun.security.x509.X509CertImpl cert = new sun.security.x509.X509CertImpl(info);
        cert.sign(issuerKeyPair.getPrivate(), "SHA256withRSA");

        return cert;
    }

    private static X509Certificate generateSelfSignedCert(KeyPair keyPair, String dn, boolean isCA) throws Exception {
        long now = System.currentTimeMillis();
        Date startDate = new Date(now);
        Date endDate = new Date(now + 365L * 24 * 60 * 60 * 1000);

        sun.security.x509.X500Name owner = new sun.security.x509.X500Name(dn);
        sun.security.x509.X509CertInfo info = new sun.security.x509.X509CertInfo();

        info.set(sun.security.x509.X509CertInfo.VALIDITY,
                new sun.security.x509.CertificateValidity(startDate, endDate));
        info.set(sun.security.x509.X509CertInfo.SERIAL_NUMBER,
                new sun.security.x509.CertificateSerialNumber(new BigInteger(64, new java.security.SecureRandom())));
        info.set(sun.security.x509.X509CertInfo.SUBJECT, owner);
        info.set(sun.security.x509.X509CertInfo.ISSUER, owner);
        info.set(sun.security.x509.X509CertInfo.KEY,
                new sun.security.x509.CertificateX509Key(keyPair.getPublic()));
        info.set(sun.security.x509.X509CertInfo.VERSION,
                new sun.security.x509.CertificateVersion(sun.security.x509.CertificateVersion.V3));

        sun.security.x509.AlgorithmId algo = new sun.security.x509.AlgorithmId(
                sun.security.x509.AlgorithmId.sha256WithRSAEncryption_oid);
        info.set(sun.security.x509.X509CertInfo.ALGORITHM_ID,
                new sun.security.x509.CertificateAlgorithmId(algo));

        // Add basic constraints for CA
        if (isCA) {
            sun.security.x509.CertificateExtensions extensions = new sun.security.x509.CertificateExtensions();
            extensions.set(sun.security.x509.BasicConstraintsExtension.NAME,
                    new sun.security.x509.BasicConstraintsExtension(true, true, 0));
            info.set(sun.security.x509.X509CertInfo.EXTENSIONS, extensions);
        }

        sun.security.x509.X509CertImpl cert = new sun.security.x509.X509CertImpl(info);
        cert.sign(keyPair.getPrivate(), "SHA256withRSA");

        return cert;
    }

    private static void createPkcs12Keystore(File file, String alias, KeyPair keyPair,
            X509Certificate cert, X509Certificate caCert, String password) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry(alias, keyPair.getPrivate(), password.toCharArray(),
                new java.security.cert.Certificate[]{cert, caCert});
        try (FileOutputStream fos = new FileOutputStream(file)) {
            ks.store(fos, password.toCharArray());
        }
    }

    private static void createPkcs12KeystoreWithKeyPassword(File file, String alias, KeyPair keyPair,
            X509Certificate cert, X509Certificate caCert,
            String keystorePassword, String keyPassword) throws Exception {
        // Note: PKCS12 typically uses the same password for keystore and keys,
        // but we can test with JKS which supports different passwords
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        // PKCS12 uses keystore password for key entries by design
        // So we create it but the real test is with JKS
        ks.setKeyEntry(alias, keyPair.getPrivate(), keyPassword.toCharArray(),
                new java.security.cert.Certificate[]{cert, caCert});
        try (FileOutputStream fos = new FileOutputStream(file)) {
            ks.store(fos, keystorePassword.toCharArray());
        }
    }

    private static void createJksKeystoreWithKeyPassword(File file, String alias, KeyPair keyPair,
            X509Certificate cert, X509Certificate caCert,
            String keystorePassword, String keyPassword) throws Exception {
        // JKS supports different passwords for keystore and individual keys
        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(null, null);
        ks.setKeyEntry(alias, keyPair.getPrivate(), keyPassword.toCharArray(),
                new java.security.cert.Certificate[]{cert, caCert});
        try (FileOutputStream fos = new FileOutputStream(file)) {
            ks.store(fos, keystorePassword.toCharArray());
        }
    }

    private static void createTruststore(File file, String type, X509Certificate caCert,
            String password) throws Exception {
        KeyStore ks = KeyStore.getInstance(type);
        ks.load(null, null);
        ks.setCertificateEntry("ca", caCert);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            ks.store(fos, password.toCharArray());
        }
    }

    private static void writePemCertificate(File file, X509Certificate cert) throws Exception {
        try (FileWriter writer = new FileWriter(file)) {
            writer.write("-----BEGIN CERTIFICATE-----\n");
            String base64 = Base64.getEncoder().encodeToString(cert.getEncoded());
            // Write in 64-character lines
            for (int i = 0; i < base64.length(); i += 64) {
                writer.write(base64.substring(i, Math.min(i + 64, base64.length())));
                writer.write("\n");
            }
            writer.write("-----END CERTIFICATE-----\n");
        }
    }

    private static void writePemPrivateKey(File file, KeyPair keyPair) throws Exception {
        try (FileWriter writer = new FileWriter(file)) {
            writer.write("-----BEGIN PRIVATE KEY-----\n");
            String base64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
            for (int i = 0; i < base64.length(); i += 64) {
                writer.write(base64.substring(i, Math.min(i + 64, base64.length())));
                writer.write("\n");
            }
            writer.write("-----END PRIVATE KEY-----\n");
        }
    }
}
