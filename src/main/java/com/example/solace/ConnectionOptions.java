package com.example.solace;

import picocli.CommandLine.Option;

public class ConnectionOptions {

    @Option(names = {"-H", "--host"},
            description = "Solace broker host (e.g., tcp://localhost:55555 or tcps://localhost:55443)",
            required = true)
    String host;

    @Option(names = {"-v", "--vpn"},
            description = "Message VPN name",
            required = true)
    String vpn;

    @Option(names = {"-u", "--username"},
            description = "Username for authentication (optional when using client certificates)")
    String username;

    @Option(names = {"-p", "--password"},
            description = "Password for authentication",
            defaultValue = "",
            interactive = true,
            arity = "0..1")
    String password;

    @Option(names = {"-q", "--queue"},
            description = "Queue name",
            required = true)
    String queue;

    // SSL/TLS Options
    @Option(names = {"--ssl"},
            description = "Enable SSL/TLS (auto-detected if host uses tcps://)")
    boolean ssl;

    @Option(names = {"--trust-store"},
            description = "Path to trust store file (JKS, PKCS12, or PEM) for server certificate validation")
    String trustStore;

    @Option(names = {"--trust-store-password"},
            description = "Password for trust store (if required)",
            interactive = true,
            arity = "0..1")
    String trustStorePassword;

    @Option(names = {"--key-store"},
            description = "Path to key store file (JKS, PKCS12, or PEM) for client certificate authentication")
    String keyStore;

    @Option(names = {"--key-store-password"},
            description = "Password for key store",
            interactive = true,
            arity = "0..1")
    String keyStorePassword;

    @Option(names = {"--key-password"},
            description = "Password for private key (if different from key store password)",
            interactive = true,
            arity = "0..1")
    String keyPassword;

    @Option(names = {"--key-alias"},
            description = "Alias of the private key entry in the key store")
    String keyAlias;

    @Option(names = {"--client-cert"},
            description = "Path to client certificate file (PEM format, use with --client-key)")
    String clientCert;

    @Option(names = {"--client-key"},
            description = "Path to client private key file (PEM format, use with --client-cert)")
    String clientKey;

    @Option(names = {"--ca-cert"},
            description = "Path to CA certificate file (PEM format) for server validation")
    String caCert;

    @Option(names = {"--skip-cert-validation"},
            description = "Skip server certificate validation (NOT recommended for production)")
    boolean skipCertValidation;

    @Option(names = {"--tls-version"},
            description = "TLS protocol version (e.g., TLSv1.2, TLSv1.3)",
            defaultValue = "TLSv1.2")
    String tlsVersion;

    public boolean isSSLEnabled() {
        return ssl || (host != null && host.toLowerCase().startsWith("tcps://"));
    }

    public boolean hasClientCertificate() {
        return keyStore != null || (clientCert != null && clientKey != null);
    }
}
