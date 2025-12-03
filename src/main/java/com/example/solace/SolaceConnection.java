package com.example.solace;

import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.JCSMPException;

import java.security.KeyStore;

public class SolaceConnection {

    public static JCSMPSession createSession(ConnectionOptions options) throws JCSMPException {
        JCSMPProperties properties = new JCSMPProperties();
        properties.setProperty(JCSMPProperties.HOST, options.host);
        properties.setProperty(JCSMPProperties.VPN_NAME, options.vpn);

        // Username/password are optional when using client certificates
        if (options.username != null) {
            properties.setProperty(JCSMPProperties.USERNAME, options.username);
        }
        if (options.password != null && !options.password.isEmpty()) {
            properties.setProperty(JCSMPProperties.PASSWORD, options.password);
        }

        // Configure SSL/TLS if enabled
        if (options.isSSLEnabled()) {
            configureSSL(properties, options);
        }

        // Additional recommended settings
        properties.setProperty(JCSMPProperties.REAPPLY_SUBSCRIPTIONS, true);
        properties.setProperty(JCSMPProperties.CLIENT_NAME,
            "solace-cli-" + System.currentTimeMillis());

        JCSMPSession session = JCSMPFactory.onlyInstance().createSession(properties);
        session.connect();

        return session;
    }

    private static void configureSSL(JCSMPProperties properties, ConnectionOptions options) throws JCSMPException {
        try {
            // Configure certificate validation
            properties.setProperty(JCSMPProperties.SSL_VALIDATE_CERTIFICATE, !options.skipCertValidation);
            if (options.skipCertValidation) {
                properties.setProperty(JCSMPProperties.SSL_VALIDATE_CERTIFICATE_DATE, false);
            }

            // Configure trust store for server certificate validation
            KeyStore trustStore = SSLHelper.createTrustStore(options);
            if (trustStore != null) {
                properties.setProperty(JCSMPProperties.SSL_IN_MEMORY_TRUST_STORE, trustStore);
            } else if (options.trustStore != null) {
                // Use file-based trust store
                properties.setProperty(JCSMPProperties.SSL_TRUST_STORE, options.trustStore);
                if (options.trustStorePassword != null) {
                    properties.setProperty(JCSMPProperties.SSL_TRUST_STORE_PASSWORD, options.trustStorePassword);
                }
            }

            // Configure key store for client certificate authentication
            if (options.hasClientCertificate()) {
                KeyStore keyStore = SSLHelper.createKeyStore(options);
                if (keyStore != null) {
                    properties.setProperty(JCSMPProperties.SSL_IN_MEMORY_KEY_STORE, keyStore);
                    if (options.keyStorePassword != null) {
                        properties.setProperty(JCSMPProperties.SSL_KEY_STORE_PASSWORD, options.keyStorePassword);
                    }
                } else if (options.keyStore != null) {
                    // Use file-based key store
                    properties.setProperty(JCSMPProperties.SSL_KEY_STORE, options.keyStore);
                    if (options.keyStorePassword != null) {
                        properties.setProperty(JCSMPProperties.SSL_KEY_STORE_PASSWORD, options.keyStorePassword);
                    }
                }

                // Set authentication scheme for client certificate
                properties.setProperty(JCSMPProperties.AUTHENTICATION_SCHEME,
                    JCSMPProperties.AUTHENTICATION_SCHEME_CLIENT_CERTIFICATE);
            }

        } catch (Exception e) {
            throw new JCSMPException("Failed to configure SSL: " + e.getMessage(), e);
        }
    }
}
