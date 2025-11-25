package com.example.solace;

import picocli.CommandLine.Option;

public class ConnectionOptions {

    @Option(names = {"-H", "--host"}, 
            description = "Solace broker host (e.g., tcp://localhost:55555)",
            required = true)
    String host;

    @Option(names = {"-v", "--vpn"}, 
            description = "Message VPN name",
            required = true)
    String vpn;

    @Option(names = {"-u", "--username"}, 
            description = "Username for authentication",
            required = true)
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
}
