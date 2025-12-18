package com.example.solace;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Date;

import static org.junit.Assert.*;

/**
 * Unit tests for SolaceConnection SSL configuration.
 * These tests verify the configuration logic without actually connecting to a broker.
 */
public class SolaceConnectionTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private ConnectionOptions options;

    @Before
    public void setUp() {
        options = new ConnectionOptions();
        options.host = "tcp://localhost:55555";
        options.vpn = "default";
        options.username = "admin";
        options.password = "admin";
        options.queue = "test-queue";
        options.tlsVersion = "TLSv1.2";
    }

    @Test
    public void testConnectionOptionsDefaults() {
        assertFalse("SSL should be disabled by default", options.isSSLEnabled());
        assertFalse("Should not have client cert by default", options.hasClientCertificate());
        assertFalse("skipCertValidation should be false by default", options.skipCertValidation);
        assertEquals("Default TLS version should be TLSv1.2", "TLSv1.2", options.tlsVersion);
    }

    @Test
    public void testSslEnabledWithTcpsUrl() {
        options.host = "tcps://localhost:55443";
        assertTrue("SSL should be enabled with tcps:// URL", options.isSSLEnabled());
    }

    @Test
    public void testSslEnabledWithFlag() {
        options.ssl = true;
        assertTrue("SSL should be enabled with --ssl flag", options.isSSLEnabled());
    }

    @Test
    public void testClientCertWithKeyStore() {
        options.keyStore = "/path/to/keystore.p12";
        assertTrue("Should detect client cert with keyStore", options.hasClientCertificate());
    }

    @Test
    public void testClientCertWithPemFiles() {
        options.clientCert = "/path/to/cert.pem";
        options.clientKey = "/path/to/key.pem";
        assertTrue("Should detect client cert with PEM files", options.hasClientCertificate());
    }

    @Test
    public void testNoClientCertWithOnlyCertFile() {
        options.clientCert = "/path/to/cert.pem";
        // No clientKey set
        assertFalse("Should not detect client cert with only cert file", options.hasClientCertificate());
    }

    @Test
    public void testUsernameOptionalWithCert() {
        options.username = null;
        options.keyStore = "/path/to/keystore.p12";
        options.host = "tcps://localhost:55443";

        assertNull("Username should be null", options.username);
        assertTrue("Should have client cert", options.hasClientCertificate());
        assertTrue("SSL should be enabled", options.isSSLEnabled());
    }

    @Test
    public void testCombinedAuthConfig() {
        options.username = "user";
        options.password = "pass";
        options.keyStore = "/path/to/keystore.p12";
        options.host = "tcps://localhost:55443";

        assertEquals("Username should be set", "user", options.username);
        assertEquals("Password should be set", "pass", options.password);
        assertTrue("Should have client cert", options.hasClientCertificate());
        assertTrue("SSL should be enabled", options.isSSLEnabled());
    }

    @Test
    public void testTrustStoreConfig() {
        options.trustStore = "/path/to/truststore.jks";
        options.trustStorePassword = "password";

        assertEquals("Trust store path should be set", "/path/to/truststore.jks", options.trustStore);
        assertEquals("Trust store password should be set", "password", options.trustStorePassword);
    }

    @Test
    public void testCaCertConfig() {
        options.caCert = "/path/to/ca.pem";

        assertEquals("CA cert path should be set", "/path/to/ca.pem", options.caCert);
    }

    @Test
    public void testSkipCertValidation() {
        options.skipCertValidation = true;

        assertTrue("Skip cert validation should be true", options.skipCertValidation);
    }

    @Test
    public void testTlsVersionConfig() {
        options.tlsVersion = "TLSv1.3";

        assertEquals("TLS version should be TLSv1.3", "TLSv1.3", options.tlsVersion);
    }

    @Test
    public void testKeyPasswordConfig() {
        options.keyStore = "/path/to/keystore.p12";
        options.keyStorePassword = "keystorepass";
        options.keyPassword = "privatekeypass";

        assertEquals("Key store path should be set", "/path/to/keystore.p12", options.keyStore);
        assertEquals("Key store password should be set", "keystorepass", options.keyStorePassword);
        assertEquals("Key password should be set", "privatekeypass", options.keyPassword);
    }

    @Test
    public void testKeyAliasConfig() {
        options.keyStore = "/path/to/keystore.jks";
        options.keyStorePassword = "keystorepass";
        options.keyAlias = "myclientkey";

        assertEquals("Key store path should be set", "/path/to/keystore.jks", options.keyStore);
        assertEquals("Key alias should be set", "myclientkey", options.keyAlias);
    }

    @Test
    public void testKeyPasswordAndKeyAliasConfig() {
        options.keyStore = "/path/to/keystore.jks";
        options.keyStorePassword = "keystorepass";
        options.keyPassword = "privatekeypass";
        options.keyAlias = "myclientkey";
        options.host = "tcps://localhost:55443";

        assertEquals("Key store path should be set", "/path/to/keystore.jks", options.keyStore);
        assertEquals("Key store password should be set", "keystorepass", options.keyStorePassword);
        assertEquals("Key password should be set", "privatekeypass", options.keyPassword);
        assertEquals("Key alias should be set", "myclientkey", options.keyAlias);
        assertTrue("Should have client certificate", options.hasClientCertificate());
        assertTrue("SSL should be enabled", options.isSSLEnabled());
    }

    @Test
    public void testKeyPasswordAndKeyAliasDefaultToNull() {
        // By default, keyPassword and keyAlias should be null
        assertNull("Key password should be null by default", options.keyPassword);
        assertNull("Key alias should be null by default", options.keyAlias);
    }

    @Test
    public void testSslHelperCreatesKeyStoreFromPem() throws Exception {
        // Create temporary PEM files with real certificates
        File certFile = tempFolder.newFile("client.pem");
        File keyFile = tempFolder.newFile("client.key");

        createTestCertAndKey(certFile, keyFile);

        options.clientCert = certFile.getAbsolutePath();
        options.clientKey = keyFile.getAbsolutePath();
        options.ssl = true;

        KeyStore keyStore = SSLHelper.createKeyStore(options);
        assertNotNull("KeyStore should be created from PEM files", keyStore);
        assertTrue("KeyStore should contain entries", keyStore.size() > 0);
    }

    @Test
    public void testSslHelperCreatesTrustStoreFromPem() throws Exception {
        // Create temporary CA cert file
        File caCertFile = tempFolder.newFile("ca.pem");
        createTestCaCert(caCertFile);

        options.caCert = caCertFile.getAbsolutePath();
        options.ssl = true;

        KeyStore trustStore = SSLHelper.createTrustStore(options);
        assertNotNull("TrustStore should be created from PEM CA cert", trustStore);
        assertTrue("TrustStore should contain entries", trustStore.size() > 0);
    }

    @Test
    public void testSslHelperReturnsNullForJksFiles() throws Exception {
        // Create an empty JKS file
        File jksFile = tempFolder.newFile("store.jks");
        createEmptyKeyStore(jksFile, "JKS", "changeit");

        options.trustStore = jksFile.getAbsolutePath();
        options.trustStorePassword = "changeit";

        // For JKS/PKCS12 files, SSLHelper returns null to let Solace use file directly
        KeyStore trustStore = SSLHelper.createTrustStore(options);
        assertNull("Should return null for JKS file (Solace uses file directly)", trustStore);
    }

    @Test
    public void testSslHelperReturnsNullForP12Files() throws Exception {
        // Create an empty PKCS12 file
        File p12File = tempFolder.newFile("store.p12");
        createEmptyKeyStore(p12File, "PKCS12", "changeit");

        options.keyStore = p12File.getAbsolutePath();
        options.keyStorePassword = "changeit";

        // For JKS/PKCS12 files, SSLHelper returns null to let Solace use file directly
        KeyStore keyStore = SSLHelper.createKeyStore(options);
        assertNull("Should return null for PKCS12 file (Solace uses file directly)", keyStore);
    }

    @Test
    public void testSslHelperWithCombinedPemFile() throws Exception {
        // Create a combined PEM file (cert + key in one file)
        File combinedFile = tempFolder.newFile("combined.pem");
        createCombinedPem(combinedFile);

        options.keyStore = combinedFile.getAbsolutePath();
        options.ssl = true;

        KeyStore keyStore = SSLHelper.createKeyStore(options);
        assertNotNull("KeyStore should be created from combined PEM file", keyStore);
    }

    // Helper methods

    private void createTestCertAndKey(File certFile, File keyFile) throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();

        X509Certificate cert = generateSelfSignedCert(keyPair, "CN=Test Client");

        // Write certificate
        try (FileWriter writer = new FileWriter(certFile)) {
            writer.write("-----BEGIN CERTIFICATE-----\n");
            writer.write(java.util.Base64.getEncoder().encodeToString(cert.getEncoded()));
            writer.write("\n-----END CERTIFICATE-----\n");
        }

        // Write private key (PKCS8 format)
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

    private void createEmptyKeyStore(File file, String type, String password) throws Exception {
        KeyStore ks = KeyStore.getInstance(type);
        ks.load(null, password.toCharArray());
        try (FileOutputStream fos = new FileOutputStream(file)) {
            ks.store(fos, password.toCharArray());
        }
    }
}
