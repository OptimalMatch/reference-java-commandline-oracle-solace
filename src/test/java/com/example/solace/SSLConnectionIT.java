package com.example.solace;

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

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Date;

import static org.junit.Assert.*;

/**
 * Integration tests for SSL/TLS connection functionality.
 *
 * These tests verify:
 * 1. SSL configuration parsing and setup
 * 2. Certificate loading from various formats (JKS, PKCS12, PEM)
 * 3. Trust store and key store creation
 *
 * Note: Full SSL connection testing requires a Solace broker configured with TLS,
 * which is complex to set up in a container. These tests focus on the SSL
 * configuration and certificate handling logic.
 */
public class SSLConnectionIT {

    private static final String SOLACE_IMAGE = "solace/solace-pubsub-standard:latest";
    private static final String PASSWORD = "admin";

    // Check if Docker is available
    private static boolean dockerAvailable = false;
    static {
        try {
            dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            dockerAvailable = false;
        }
    }

    @ClassRule
    public static TestRule skipRule = new TestRule() {
        @Override
        public Statement apply(Statement base, Description description) {
            if (!dockerAvailable) {
                return new Statement() {
                    @Override
                    public void evaluate() {
                        Assume.assumeTrue("Skipping: Docker is not available", false);
                    }
                };
            }
            return base;
        }
    };

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private ConnectionOptions options;

    @Before
    public void setUp() {
        options = new ConnectionOptions();
        options.host = "tcps://localhost:55443";
        options.vpn = "default";
        options.queue = "test-queue";
        options.tlsVersion = "TLSv1.2";
    }

    @Test
    public void testCreateKeyStoreFromPemFiles() throws Exception {
        // Create test certificates
        File certFile = tempFolder.newFile("client.pem");
        File keyFile = tempFolder.newFile("client.key");
        createTestCertAndKey(certFile, keyFile);

        options.clientCert = certFile.getAbsolutePath();
        options.clientKey = keyFile.getAbsolutePath();

        KeyStore keyStore = SSLHelper.createKeyStore(options);

        assertNotNull("KeyStore should be created", keyStore);
        assertTrue("KeyStore should have at least one entry", keyStore.size() > 0);
        assertTrue("KeyStore should contain 'client' alias", keyStore.containsAlias("client"));
    }

    @Test
    public void testCreateTrustStoreFromCaCert() throws Exception {
        // Create test CA certificate
        File caCertFile = tempFolder.newFile("ca.pem");
        createTestCaCert(caCertFile);

        options.caCert = caCertFile.getAbsolutePath();

        KeyStore trustStore = SSLHelper.createTrustStore(options);

        assertNotNull("TrustStore should be created", trustStore);
        assertTrue("TrustStore should have at least one entry", trustStore.size() > 0);
    }

    @Test
    public void testCreateKeyStoreFromPkcs12() throws Exception {
        // Create a PKCS12 keystore with a self-signed cert
        File p12File = tempFolder.newFile("client.p12");
        createPkcs12WithCert(p12File, "testpass");

        options.keyStore = p12File.getAbsolutePath();
        options.keyStorePassword = "testpass";

        // For PKCS12 files, SSLHelper returns null (Solace uses file directly)
        KeyStore keyStore = SSLHelper.createKeyStore(options);
        assertNull("Should return null for PKCS12 (Solace uses file directly)", keyStore);
        assertTrue("hasClientCertificate should be true", options.hasClientCertificate());
    }

    @Test
    public void testCreateTrustStoreFromJks() throws Exception {
        // Create a JKS truststore with a CA cert
        File jksFile = tempFolder.newFile("truststore.jks");
        createJksWithCert(jksFile, "testpass");

        options.trustStore = jksFile.getAbsolutePath();
        options.trustStorePassword = "testpass";

        // For JKS files, SSLHelper returns null (Solace uses file directly)
        KeyStore trustStore = SSLHelper.createTrustStore(options);
        assertNull("Should return null for JKS (Solace uses file directly)", trustStore);
    }

    @Test
    public void testCombinedPemFile() throws Exception {
        // Create a combined PEM file with cert and key
        File combinedFile = tempFolder.newFile("combined.pem");
        createCombinedPem(combinedFile);

        options.keyStore = combinedFile.getAbsolutePath();

        KeyStore keyStore = SSLHelper.createKeyStore(options);

        assertNotNull("KeyStore should be created from combined PEM", keyStore);
        assertTrue("KeyStore should have entries", keyStore.size() > 0);
    }

    @Test
    public void testMultipleCaCertsInFile() throws Exception {
        // Create a PEM file with multiple CA certs
        File caCertFile = tempFolder.newFile("ca-bundle.pem");
        createMultipleCaCerts(caCertFile, 3);

        options.caCert = caCertFile.getAbsolutePath();

        KeyStore trustStore = SSLHelper.createTrustStore(options);

        assertNotNull("TrustStore should be created", trustStore);
        assertEquals("TrustStore should have 3 entries", 3, trustStore.size());
    }

    @Test
    public void testSslOptionsDetection() {
        // Test tcps:// detection
        options.host = "tcps://broker.example.com:55443";
        options.ssl = false;
        assertTrue("Should detect SSL from tcps:// URL", options.isSSLEnabled());

        // Test explicit flag
        options.host = "tcp://broker.example.com:55555";
        options.ssl = true;
        assertTrue("Should enable SSL with explicit flag", options.isSSLEnabled());

        // Test no SSL
        options.host = "tcp://broker.example.com:55555";
        options.ssl = false;
        assertFalse("Should not enable SSL for tcp://", options.isSSLEnabled());
    }

    @Test
    public void testClientCertDetection() {
        // Test with keyStore
        options.keyStore = "/path/to/store.p12";
        assertTrue("Should detect client cert with keyStore", options.hasClientCertificate());

        // Test with PEM files
        options.keyStore = null;
        options.clientCert = "/path/to/cert.pem";
        options.clientKey = "/path/to/key.pem";
        assertTrue("Should detect client cert with PEM files", options.hasClientCertificate());

        // Test with only cert (should be false)
        options.clientKey = null;
        assertFalse("Should not detect client cert with only cert", options.hasClientCertificate());
    }

    @Test
    public void testCertAuthWithoutUsername() throws Exception {
        // Create test certificates
        File certFile = tempFolder.newFile("client.pem");
        File keyFile = tempFolder.newFile("client.key");
        createTestCertAndKey(certFile, keyFile);

        options.username = null;
        options.password = null;
        options.clientCert = certFile.getAbsolutePath();
        options.clientKey = keyFile.getAbsolutePath();
        options.ssl = true;

        // Should be able to create key store without username
        KeyStore keyStore = SSLHelper.createKeyStore(options);
        assertNotNull("KeyStore should be created without username", keyStore);
        assertNull("Username should be null", options.username);
        assertTrue("hasClientCertificate should be true", options.hasClientCertificate());
    }

    @Test
    public void testSkipCertValidationOption() {
        options.skipCertValidation = true;
        assertTrue("skipCertValidation should be true", options.skipCertValidation);
    }

    @Test
    public void testTlsVersionOption() {
        options.tlsVersion = "TLSv1.3";
        assertEquals("TLS version should be TLSv1.3", "TLSv1.3", options.tlsVersion);
    }

    @Test(expected = Exception.class)
    public void testMissingCertFile() throws Exception {
        options.clientCert = "/nonexistent/path/cert.pem";
        options.clientKey = "/nonexistent/path/key.pem";

        SSLHelper.createKeyStore(options);
    }

    @Test(expected = Exception.class)
    public void testMissingCaCertFile() throws Exception {
        options.caCert = "/nonexistent/path/ca.pem";

        SSLHelper.createTrustStore(options);
    }

    // Helper methods

    private void createTestCertAndKey(File certFile, File keyFile) throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();

        X509Certificate cert = generateSelfSignedCert(keyPair, "CN=Test Client");

        try (FileWriter writer = new FileWriter(certFile)) {
            writer.write("-----BEGIN CERTIFICATE-----\n");
            writer.write(java.util.Base64.getEncoder().encodeToString(cert.getEncoded()));
            writer.write("\n-----END CERTIFICATE-----\n");
        }

        try (FileWriter writer = new FileWriter(keyFile)) {
            writer.write("-----BEGIN PRIVATE KEY-----\n");
            writer.write(java.util.Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()));
            writer.write("\n-----END PRIVATE KEY-----\n");
        }
    }

    private void createTestCaCert(File file) throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();

        X509Certificate cert = generateSelfSignedCert(keyPair, "CN=Test CA");

        try (FileWriter writer = new FileWriter(file)) {
            writer.write("-----BEGIN CERTIFICATE-----\n");
            writer.write(java.util.Base64.getEncoder().encodeToString(cert.getEncoded()));
            writer.write("\n-----END CERTIFICATE-----\n");
        }
    }

    private void createMultipleCaCerts(File file, int count) throws Exception {
        try (FileWriter writer = new FileWriter(file)) {
            for (int i = 0; i < count; i++) {
                KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
                keyGen.initialize(2048);
                KeyPair keyPair = keyGen.generateKeyPair();

                X509Certificate cert = generateSelfSignedCert(keyPair, "CN=Test CA " + i);

                writer.write("-----BEGIN CERTIFICATE-----\n");
                writer.write(java.util.Base64.getEncoder().encodeToString(cert.getEncoded()));
                writer.write("\n-----END CERTIFICATE-----\n");
            }
        }
    }

    private void createCombinedPem(File file) throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();

        X509Certificate cert = generateSelfSignedCert(keyPair, "CN=Test Combined");

        try (FileWriter writer = new FileWriter(file)) {
            writer.write("-----BEGIN CERTIFICATE-----\n");
            writer.write(java.util.Base64.getEncoder().encodeToString(cert.getEncoded()));
            writer.write("\n-----END CERTIFICATE-----\n");
            writer.write("-----BEGIN PRIVATE KEY-----\n");
            writer.write(java.util.Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()));
            writer.write("\n-----END PRIVATE KEY-----\n");
        }
    }

    private void createPkcs12WithCert(File file, String password) throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();

        X509Certificate cert = generateSelfSignedCert(keyPair, "CN=Test PKCS12");

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("client", keyPair.getPrivate(), password.toCharArray(),
                new java.security.cert.Certificate[]{cert});

        try (FileOutputStream fos = new FileOutputStream(file)) {
            ks.store(fos, password.toCharArray());
        }
    }

    private void createJksWithCert(File file, String password) throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();

        X509Certificate cert = generateSelfSignedCert(keyPair, "CN=Test JKS CA");

        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(null, null);
        ks.setCertificateEntry("ca", cert);

        try (FileOutputStream fos = new FileOutputStream(file)) {
            ks.store(fos, password.toCharArray());
        }
    }

    private X509Certificate generateSelfSignedCert(KeyPair keyPair, String dn) throws Exception {
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
        info.set(sun.security.x509.X509CertInfo.KEY, new sun.security.x509.CertificateX509Key(keyPair.getPublic()));
        info.set(sun.security.x509.X509CertInfo.VERSION, new sun.security.x509.CertificateVersion(sun.security.x509.CertificateVersion.V3));

        sun.security.x509.AlgorithmId algo = new sun.security.x509.AlgorithmId(sun.security.x509.AlgorithmId.sha256WithRSAEncryption_oid);
        info.set(sun.security.x509.X509CertInfo.ALGORITHM_ID, new sun.security.x509.CertificateAlgorithmId(algo));

        sun.security.x509.X509CertImpl cert = new sun.security.x509.X509CertImpl(info);
        cert.sign(keyPair.getPrivate(), "SHA256withRSA");

        return cert;
    }
}
