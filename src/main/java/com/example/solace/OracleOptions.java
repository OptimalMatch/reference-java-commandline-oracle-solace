package com.example.solace;

import picocli.CommandLine.Option;

public class OracleOptions {

    @Option(names = {"--db-host"},
            description = "Oracle database host",
            required = true)
    String dbHost;

    @Option(names = {"--db-port"},
            description = "Oracle database port",
            defaultValue = "1521")
    int dbPort;

    @Option(names = {"--db-service"},
            description = "Oracle service name or SID",
            required = true)
    String dbService;

    @Option(names = {"--db-user"},
            description = "Oracle database username",
            required = true)
    String dbUser;

    @Option(names = {"--db-password"},
            description = "Oracle database password",
            interactive = true,
            arity = "0..1")
    String dbPassword;

    public String getJdbcUrl() {
        return String.format("jdbc:oracle:thin:@//%s:%d/%s", dbHost, dbPort, dbService);
    }
}
