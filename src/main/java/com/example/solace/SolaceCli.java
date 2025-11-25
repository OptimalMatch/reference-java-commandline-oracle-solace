package com.example.solace;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "solace-cli",
    mixinStandardHelpOptions = true,
    version = "1.0.0",
    description = "Publish and consume messages from Solace queues",
    subcommands = {
        PublishCommand.class,
        ConsumeCommand.class,
        OraclePublishCommand.class,
        OracleExportCommand.class,
        OracleInsertCommand.class,
        FolderPublishCommand.class
    }
)
public class SolaceCli implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new SolaceCli()).execute(args);
        System.exit(exitCode);
    }
}
