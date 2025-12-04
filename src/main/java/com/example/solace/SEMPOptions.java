package com.example.solace;

import picocli.CommandLine.Option;

/**
 * SEMP (Solace Element Management Protocol) connection options.
 * Used for management API operations like listing queues, monitoring, etc.
 */
public class SEMPOptions {

    @Option(names = {"--semp-url"},
            description = "SEMP API base URL (e.g., http://localhost:8080 or https://localhost:1943)",
            required = true)
    String sempUrl;

    @Option(names = {"--semp-user"},
            description = "SEMP admin username",
            required = true)
    String sempUser;

    @Option(names = {"--semp-password"},
            description = "SEMP admin password",
            interactive = true,
            arity = "0..1")
    String sempPassword;

    @Option(names = {"--semp-vpn"},
            description = "Message VPN to query (defaults to 'default')",
            defaultValue = "default")
    String msgVpn;

    @Option(names = {"--semp-skip-cert-validation"},
            description = "Skip SSL certificate validation for SEMP (NOT recommended for production)")
    boolean skipCertValidation;

    /**
     * Get the base SEMP v2 monitor API URL for the configured VPN.
     */
    public String getMonitorApiUrl() {
        String base = sempUrl.endsWith("/") ? sempUrl.substring(0, sempUrl.length() - 1) : sempUrl;
        return base + "/SEMP/v2/monitor/msgVpns/" + urlEncode(msgVpn);
    }

    /**
     * URL-encode a string for use in URL paths.
     */
    private String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return value;
        }
    }
}
