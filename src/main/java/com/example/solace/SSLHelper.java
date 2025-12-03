package com.example.solace;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.security.spec.*;
import java.util.*;
import java.util.regex.*;

public class SSLHelper {

    /**
     * Creates a KeyStore for client certificate authentication.
     * Returns null if file-based keystore should be used directly by Solace.
     */
    public static KeyStore createKeyStore(ConnectionOptions options) throws Exception {
        // If using PEM files, we need to convert to in-memory KeyStore
        if (options.clientCert != null && options.clientKey != null) {
            return loadPEMKeyStore(options.clientCert, options.clientKey, options.keyStorePassword);
        }

        // For JKS/PKCS12 files, check if it's a PEM file that needs conversion
        if (options.keyStore != null && isPEMFile(options.keyStore)) {
            // This is a single PEM file containing both cert and key
            return loadCombinedPEMKeyStore(options.keyStore, options.keyStorePassword);
        }

        // Return null to signal that Solace should use file-based keystore directly
        return null;
    }

    /**
     * Creates a TrustStore for server certificate validation.
     * Returns null if file-based truststore should be used directly by Solace.
     */
    public static KeyStore createTrustStore(ConnectionOptions options) throws Exception {
        // If using PEM CA certificate, convert to in-memory KeyStore
        if (options.caCert != null) {
            return loadPEMTrustStore(options.caCert);
        }

        // For JKS/PKCS12 files, check if it's a PEM file that needs conversion
        if (options.trustStore != null && isPEMFile(options.trustStore)) {
            return loadPEMTrustStore(options.trustStore);
        }

        // Return null to signal that Solace should use file-based truststore directly
        return null;
    }

    private static boolean isPEMFile(String path) {
        String lowerPath = path.toLowerCase();
        return lowerPath.endsWith(".pem") || lowerPath.endsWith(".crt") || lowerPath.endsWith(".cer");
    }

    private static KeyStore loadKeyStore(String path, String password) throws Exception {
        String type = detectKeyStoreType(path);
        KeyStore keyStore = KeyStore.getInstance(type);
        char[] passwordChars = password != null ? password.toCharArray() : null;

        try (InputStream is = Files.newInputStream(Paths.get(path))) {
            keyStore.load(is, passwordChars);
        }
        return keyStore;
    }

    private static String detectKeyStoreType(String path) {
        String lowerPath = path.toLowerCase();
        if (lowerPath.endsWith(".p12") || lowerPath.endsWith(".pfx")) {
            return "PKCS12";
        } else if (lowerPath.endsWith(".jks")) {
            return "JKS";
        } else if (lowerPath.endsWith(".pem") || lowerPath.endsWith(".crt") || lowerPath.endsWith(".cer")) {
            return "PEM";
        }
        // Default to PKCS12 as it's more portable
        return "PKCS12";
    }

    private static KeyStore loadPEMKeyStore(String certPath, String keyPath, String password) throws Exception {
        // Read certificate
        X509Certificate certificate = loadPEMCertificate(certPath);

        // Read private key
        String keyContent = new String(Files.readAllBytes(Paths.get(keyPath)));
        PrivateKey privateKey = loadPEMPrivateKeyFromContent(keyContent, password);

        // Create in-memory PKCS12 keystore
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);

        char[] passwordChars = password != null ? password.toCharArray() : new char[0];
        keyStore.setKeyEntry("client", privateKey, passwordChars, new java.security.cert.Certificate[]{certificate});

        return keyStore;
    }

    private static KeyStore loadCombinedPEMKeyStore(String pemPath, String password) throws Exception {
        // Read the combined PEM file (contains both cert and key)
        String content = new String(Files.readAllBytes(Paths.get(pemPath)));

        // Extract certificate
        List<X509Certificate> certs = loadPEMCertificatesFromContent(content);
        if (certs.isEmpty()) {
            throw new CertificateException("No certificate found in file: " + pemPath);
        }

        // Extract private key
        PrivateKey privateKey = loadPEMPrivateKeyFromContent(content, password);

        // Create in-memory PKCS12 keystore
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);

        char[] passwordChars = password != null ? password.toCharArray() : new char[0];
        keyStore.setKeyEntry("client", privateKey, passwordChars, certs.toArray(new java.security.cert.Certificate[0]));

        return keyStore;
    }

    private static KeyStore loadPEMTrustStore(String caCertPath) throws Exception {
        List<X509Certificate> certificates = loadPEMCertificates(caCertPath);

        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);

        int i = 0;
        for (X509Certificate cert : certificates) {
            trustStore.setCertificateEntry("ca-" + i++, cert);
        }

        return trustStore;
    }

    private static X509Certificate loadPEMCertificate(String path) throws Exception {
        List<X509Certificate> certs = loadPEMCertificates(path);
        if (certs.isEmpty()) {
            throw new CertificateException("No certificate found in file: " + path);
        }
        return certs.get(0);
    }

    private static List<X509Certificate> loadPEMCertificates(String path) throws Exception {
        String content = new String(Files.readAllBytes(Paths.get(path)));
        return loadPEMCertificatesFromContent(content);
    }

    private static List<X509Certificate> loadPEMCertificatesFromContent(String content) throws Exception {
        List<X509Certificate> certificates = new ArrayList<>();

        CertificateFactory cf = CertificateFactory.getInstance("X.509");

        Pattern pattern = Pattern.compile(
            "-----BEGIN CERTIFICATE-----\\s*([A-Za-z0-9+/=\\s]+)\\s*-----END CERTIFICATE-----",
            Pattern.MULTILINE
        );

        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String base64 = matcher.group(1).replaceAll("\\s", "");
            byte[] decoded = Base64.getDecoder().decode(base64);
            X509Certificate cert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(decoded));
            certificates.add(cert);
        }

        return certificates;
    }

    private static PrivateKey loadPEMPrivateKeyFromContent(String content, String password) throws Exception {
        // Check if encrypted
        if (content.contains("ENCRYPTED") && password == null) {
            throw new IllegalArgumentException("Private key is encrypted but no password provided");
        }

        // Extract key data
        Pattern pattern = Pattern.compile(
            "-----BEGIN (?:RSA |EC |)PRIVATE KEY-----\\s*([A-Za-z0-9+/=\\s]+)\\s*-----END (?:RSA |EC |)PRIVATE KEY-----",
            Pattern.MULTILINE
        );

        Matcher matcher = pattern.matcher(content);
        if (!matcher.find()) {
            // Try PKCS8 format
            pattern = Pattern.compile(
                "-----BEGIN PRIVATE KEY-----\\s*([A-Za-z0-9+/=\\s]+)\\s*-----END PRIVATE KEY-----",
                Pattern.MULTILINE
            );
            matcher = pattern.matcher(content);
            if (!matcher.find()) {
                throw new IllegalArgumentException("No private key found in content");
            }
        }

        String base64 = matcher.group(1).replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(base64);

        // Determine key type and create appropriate key spec
        if (content.contains("BEGIN RSA PRIVATE KEY")) {
            // PKCS#1 RSA key - need to wrap in PKCS#8
            return loadPKCS1RSAKey(decoded);
        } else if (content.contains("BEGIN EC PRIVATE KEY")) {
            // SEC1 EC key
            return loadEC1Key(decoded);
        } else {
            // PKCS#8 format (generic "PRIVATE KEY")
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decoded);
            // Try RSA first, then EC
            try {
                return KeyFactory.getInstance("RSA").generatePrivate(spec);
            } catch (InvalidKeySpecException e) {
                return KeyFactory.getInstance("EC").generatePrivate(spec);
            }
        }
    }

    private static PrivateKey loadPKCS1RSAKey(byte[] pkcs1Bytes) throws Exception {
        // Wrap PKCS#1 in PKCS#8 structure
        // PKCS#8 header for RSA
        byte[] pkcs8Header = new byte[] {
            0x30, (byte) 0x82, 0x00, 0x00, // SEQUENCE (length placeholder)
            0x02, 0x01, 0x00,              // INTEGER 0 (version)
            0x30, 0x0d,                    // SEQUENCE
            0x06, 0x09,                    // OID
            0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, // rsaEncryption
            0x05, 0x00,                    // NULL
            0x04, (byte) 0x82, 0x00, 0x00  // OCTET STRING (length placeholder)
        };

        int totalLength = pkcs8Header.length + pkcs1Bytes.length;
        byte[] pkcs8Bytes = new byte[totalLength];

        System.arraycopy(pkcs8Header, 0, pkcs8Bytes, 0, pkcs8Header.length);
        System.arraycopy(pkcs1Bytes, 0, pkcs8Bytes, pkcs8Header.length, pkcs1Bytes.length);

        // Fix lengths
        int seqLength = totalLength - 4;
        pkcs8Bytes[2] = (byte) ((seqLength >> 8) & 0xff);
        pkcs8Bytes[3] = (byte) (seqLength & 0xff);

        int octetLength = pkcs1Bytes.length;
        pkcs8Bytes[pkcs8Header.length - 2] = (byte) ((octetLength >> 8) & 0xff);
        pkcs8Bytes[pkcs8Header.length - 1] = (byte) (octetLength & 0xff);

        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(pkcs8Bytes);
        return KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

    private static PrivateKey loadEC1Key(byte[] sec1Bytes) throws Exception {
        // For EC keys in SEC1 format, we need to wrap them in PKCS#8
        // This is a simplified approach - for production, consider using BouncyCastle
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(sec1Bytes);
        return KeyFactory.getInstance("EC").generatePrivate(spec);
    }
}
