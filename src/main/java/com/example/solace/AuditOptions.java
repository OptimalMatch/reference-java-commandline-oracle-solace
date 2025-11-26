package com.example.solace;

import picocli.CommandLine.Option;

import java.io.File;

/**
 * Mixin for audit logging options.
 * Add this mixin to commands that should support audit logging.
 */
public class AuditOptions {

    @Option(names = {"--audit-log"},
            description = "File to write audit log entries (JSON format, one entry per line)")
    File auditFile;
}
