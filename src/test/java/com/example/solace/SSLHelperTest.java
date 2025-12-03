package com.example.solace;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.io.FileOutputStream;
import java.security.KeyStore;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.security.cert.Certificate;
import java.math.BigInteger;
import java.util.Date;
import java.security.PrivateKey;

import static org.junit.Assert.*;

/**
 * Unit tests for SSLHelper certificate loading functionality.
 */
public class SSLHelperTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private ConnectionOptions options;

    // Sample PEM certificate (self-signed, for testing only)
    private static final String SAMPLE_CERT_PEM =
        "-----BEGIN CERTIFICATE-----\n" +
        "MIIBkTCB+wIJAKHBfpegPjMCMA0GCSqGSIb3DQEBCwUAMBExDzANBgNVBAMMBnRl\n" +
        "c3RjYTAeFw0yNDAxMDEwMDAwMDBaFw0yNTAxMDEwMDAwMDBaMBExDzANBgNVBAMM\n" +
        "BnRlc3RjYTBcMA0GCSqGSIb3DQEBAQUAA0sAMEgCQQC5YhO/pv2V8fxP4QzL5yY+\n" +
        "9Y5Y5Y5Y5Y5Y5Y5Y5Y5Y5Y5Y5Y5Y5Y5Y5Y5Y5Y5Y5Y5Y5Y5Y5Y5Y5Y5Y5Y5YAgMB\n" +
        "AAEwDQYJKoZIhvcNAQELBQADQQBtest\n" +
        "-----END CERTIFICATE-----\n";

    // Sample PKCS8 private key (for testing only - not a real key)
    private static final String SAMPLE_KEY_PEM =
        "-----BEGIN PRIVATE KEY-----\n" +
        "MIIBVQIBADANBgkqhkiG9w0BAQEFAASCAT8wggE7AgEAAkEAuWITv6b9lfH8T+EM\n" +
        "y+cmPvWOWOWOWOWOWOWOWOWOWOWOWOWOWOWOWOWOWOWOWOWOWOWOWOWOWOWOWOWO\n" +
        "WOWOWAIDAQABAkBtest\n" +
        "-----END PRIVATE KEY-----\n";

    @Before
    public void setUp() {
        options = new ConnectionOptions();
        options.host = "tcps://localhost:55443";
        options.vpn = "default";
        options.tlsVersion = "TLSv1.2";
    }

    @Test
    public void testIsSSLEnabled_withTcpsHost() {
        options.host = "tcps://localhost:55443";
        options.ssl = false;
        assertTrue("Should detect SSL from tcps:// URL", options.isSSLEnabled());
    }

    @Test
    public void testIsSSLEnabled_withTcpHost() {
        options.host = "tcp://localhost:55555";
        options.ssl = false;
        assertFalse("Should not enable SSL for tcp:// URL", options.isSSLEnabled());
    }

    @Test
    public void testIsSSLEnabled_withExplicitFlag() {
        options.host = "tcp://localhost:55555";
        options.ssl = true;
        assertTrue("Should enable SSL when flag is set", options.isSSLEnabled());
    }

    @Test
    public void testIsSSLEnabled_caseInsensitive() {
        options.host = "TCPS://localhost:55443";
        options.ssl = false;
        assertTrue("Should detect SSL from TCPS:// URL (case insensitive)", options.isSSLEnabled());
    }

    @Test
    public void testHasClientCertificate_withKeyStore() {
        options.keyStore = "/path/to/keystore.p12";
        assertTrue("Should detect client cert with keyStore", options.hasClientCertificate());
    }

    @Test
    public void testHasClientCertificate_withPemFiles() {
        options.clientCert = "/path/to/client.pem";
        options.clientKey = "/path/to/client.key";
        assertTrue("Should detect client cert with PEM files", options.hasClientCertificate());
    }

    @Test
    public void testHasClientCertificate_withOnlyCert() {
        options.clientCert = "/path/to/client.pem";
        options.clientKey = null;
        assertFalse("Should not detect client cert with only cert (no key)", options.hasClientCertificate());
    }

    @Test
    public void testHasClientCertificate_noClientCert() {
        options.keyStore = null;
        options.clientCert = null;
        options.clientKey = null;
        assertFalse("Should not detect client cert when none configured", options.hasClientCertificate());
    }

    @Test
    public void testCreateTrustStore_withCaCert() throws Exception {
        // Create a temporary CA cert file
        File caCertFile = tempFolder.newFile("ca.pem");
        writePemCertificate(caCertFile);

        options.caCert = caCertFile.getAbsolutePath();

        KeyStore trustStore = SSLHelper.createTrustStore(options);
        assertNotNull("Trust store should be created from CA cert", trustStore);
        assertTrue("Trust store should contain at least one certificate", trustStore.size() > 0);
    }

    @Test
    public void testCreateTrustStore_withPemTrustStore() throws Exception {
        // Create a temporary PEM trust store file
        File trustStoreFile = tempFolder.newFile("truststore.pem");
        writePemCertificate(trustStoreFile);

        options.trustStore = trustStoreFile.getAbsolutePath();

        KeyStore trustStore = SSLHelper.createTrustStore(options);
        assertNotNull("Trust store should be created from PEM file", trustStore);
    }

    @Test
    public void testCreateTrustStore_withJksTrustStore() throws Exception {
        // For JKS/PKCS12 files, SSLHelper returns null to let Solace use file directly
        File trustStoreFile = tempFolder.newFile("truststore.jks");
        createEmptyKeyStore(trustStoreFile, "JKS", "changeit");

        options.trustStore = trustStoreFile.getAbsolutePath();
        options.trustStorePassword = "changeit";

        KeyStore trustStore = SSLHelper.createTrustStore(options);
        assertNull("Should return null for JKS file (Solace uses file directly)", trustStore);
    }

    @Test
    public void testCreateTrustStore_nullWhenNoConfig() throws Exception {
        options.trustStore = null;
        options.caCert = null;

        KeyStore trustStore = SSLHelper.createTrustStore(options);
        assertNull("Trust store should be null when no config", trustStore);
    }

    @Test
    public void testCreateKeyStore_withPemFiles() throws Exception {
        // Create temporary PEM files
        File certFile = tempFolder.newFile("client.pem");
        File keyFile = tempFolder.newFile("client.key");

        writeTestCertAndKey(certFile, keyFile);

        options.clientCert = certFile.getAbsolutePath();
        options.clientKey = keyFile.getAbsolutePath();

        KeyStore keyStore = SSLHelper.createKeyStore(options);
        assertNotNull("Key store should be created from PEM files", keyStore);
    }

    @Test
    public void testCreateKeyStore_withPkcs12File() throws Exception {
        // For PKCS12 files, SSLHelper returns null to let Solace use file directly
        File keyStoreFile = tempFolder.newFile("client.p12");
        createEmptyKeyStore(keyStoreFile, "PKCS12", "changeit");

        options.keyStore = keyStoreFile.getAbsolutePath();
        options.keyStorePassword = "changeit";

        KeyStore keyStore = SSLHelper.createKeyStore(options);
        assertNull("Should return null for PKCS12 file (Solace uses file directly)", keyStore);
    }

    @Test
    public void testCreateKeyStore_nullWhenNoConfig() throws Exception {
        options.keyStore = null;
        options.clientCert = null;
        options.clientKey = null;

        KeyStore keyStore = SSLHelper.createKeyStore(options);
        assertNull("Key store should be null when no config", keyStore);
    }

    @Test(expected = Exception.class)
    public void testCreateKeyStore_throwsOnMissingFile() throws Exception {
        options.clientCert = "/nonexistent/path/client.pem";
        options.clientKey = "/nonexistent/path/client.key";

        SSLHelper.createKeyStore(options);
    }

    @Test(expected = Exception.class)
    public void testCreateTrustStore_throwsOnMissingFile() throws Exception {
        options.caCert = "/nonexistent/path/ca.pem";

        SSLHelper.createTrustStore(options);
    }

    @Test
    public void testCreateKeyStore_withCombinedPemFile() throws Exception {
        // Create a combined PEM file (cert + key in one file)
        File combinedFile = tempFolder.newFile("combined.pem");
        writeCombinedPem(combinedFile);

        options.keyStore = combinedFile.getAbsolutePath();

        KeyStore keyStore = SSLHelper.createKeyStore(options);
        assertNotNull("Key store should be created from combined PEM file", keyStore);
    }

    // Helper methods

    private void writePemCertificate(File file) throws Exception {
        // Generate a real self-signed certificate for testing
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

    private void writeTestCertAndKey(File certFile, File keyFile) throws Exception {
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

    private void writeCombinedPem(File file) throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();

        X509Certificate cert = generateSelfSignedCert(keyPair, "CN=Test Combined");

        try (FileWriter writer = new FileWriter(file)) {
            // Write certificate
            writer.write("-----BEGIN CERTIFICATE-----\n");
            writer.write(java.util.Base64.getEncoder().encodeToString(cert.getEncoded()));
            writer.write("\n-----END CERTIFICATE-----\n");

            // Write private key
            writer.write("-----BEGIN PRIVATE KEY-----\n");
            writer.write(java.util.Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()));
            writer.write("\n-----END PRIVATE KEY-----\n");
        }
    }

    private X509Certificate generateSelfSignedCert(KeyPair keyPair, String dn) throws Exception {
        // Use reflection to access sun.security classes or use BouncyCastle
        // For simplicity, we'll use the JDK's internal API
        long now = System.currentTimeMillis();
        Date startDate = new Date(now);
        Date endDate = new Date(now + 365L * 24 * 60 * 60 * 1000); // 1 year

        // Using sun.security.x509 classes (available in most JDKs)
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
