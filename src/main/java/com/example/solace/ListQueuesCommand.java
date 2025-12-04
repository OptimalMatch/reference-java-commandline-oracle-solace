package com.example.solace;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;

import static com.example.solace.AuditLogger.maskSensitive;

@Command(
    name = "list-queues",
    aliases = {"lsq", "queues"},
    description = "List durable queues matching a name pattern with detailed information",
    mixinStandardHelpOptions = true
)
public class ListQueuesCommand implements Callable<Integer> {

    @Mixin
    SEMPOptions semp;

    @Mixin
    AuditOptions auditOptions;

    @Parameters(index = "0", arity = "0..1",
            description = "Queue name pattern with wildcards (e.g., 'GAO.Q_T2*', '*ORDERS*')")
    String namePattern;

    @Option(names = {"--format", "-f"},
            description = "Output format: table, csv, json (default: table)",
            defaultValue = "table")
    String format;

    @Option(names = {"--sort", "-s"},
            description = "Sort by: name, messages, spool, binds (default: name)",
            defaultValue = "name")
    String sortBy;

    @Option(names = {"--desc"},
            description = "Sort in descending order")
    boolean descending;

    @Option(names = {"--show-empty"},
            description = "Include queues with no messages")
    boolean showEmpty = true;

    @Option(names = {"--verbose", "-V"},
            description = "Show additional queue details")
    boolean verbose;

    @Option(names = {"--max-results", "-n"},
            description = "Maximum number of queues to display (0 = unlimited)",
            defaultValue = "0")
    int maxResults;

    @Override
    public Integer call() {
        AuditLogger audit = AuditLogger.create(auditOptions, "list-queues");

        // Log parameters
        audit.addParameter("sempUrl", semp.sempUrl)
             .addParameter("sempUser", semp.sempUser)
             .addParameter("sempPassword", maskSensitive(semp.sempPassword))
             .addParameter("msgVpn", semp.msgVpn)
             .addParameter("namePattern", namePattern)
             .addParameter("format", format)
             .addParameter("sortBy", sortBy);

        try {
            System.out.println("Connecting to SEMP API at " + semp.sempUrl + "...");
            SEMPClient client = new SEMPClient(semp);

            System.out.println("Querying queues in VPN '" + semp.msgVpn + "'" +
                    (namePattern != null ? " matching '" + namePattern + "'" : "") + "...\n");

            List<QueueInfo> queues = client.listQueues(namePattern);

            // Filter empty queues if requested
            if (!showEmpty) {
                queues.removeIf(q -> q.getSpooledMsgCount() == 0);
            }

            // Sort queues
            sortQueues(queues);

            // Apply max results limit
            if (maxResults > 0 && queues.size() > maxResults) {
                queues = queues.subList(0, maxResults);
            }

            // Output in requested format
            switch (format.toLowerCase()) {
                case "csv":
                    outputCsv(queues);
                    break;
                case "json":
                    outputJson(queues);
                    break;
                case "table":
                default:
                    outputTable(queues);
                    break;
            }

            System.out.println("\nTotal: " + queues.size() + " queue(s)");

            audit.addResult("queuesFound", queues.size())
                 .logCompletion(0);
            return 0;

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            if (verbose) {
                e.printStackTrace();
            }
            audit.setError(e.getMessage()).logCompletion(1);
            return 1;
        }
    }

    private void sortQueues(List<QueueInfo> queues) {
        Comparator<QueueInfo> comparator;

        switch (sortBy.toLowerCase()) {
            case "messages":
            case "msgs":
                comparator = Comparator.comparingLong(QueueInfo::getSpooledMsgCount);
                break;
            case "spool":
            case "size":
                comparator = Comparator.comparingLong(QueueInfo::getMsgSpoolUsage);
                break;
            case "binds":
            case "bind":
                comparator = Comparator.comparingInt(QueueInfo::getBindCount);
                break;
            case "name":
            default:
                comparator = Comparator.comparing(QueueInfo::getQueueName, String.CASE_INSENSITIVE_ORDER);
                break;
        }

        if (descending) {
            comparator = comparator.reversed();
        }

        queues.sort(comparator);
    }

    private void outputTable(List<QueueInfo> queues) {
        if (queues.isEmpty()) {
            System.out.println("No queues found.");
            return;
        }

        // Calculate column widths
        int nameWidth = Math.max(10, queues.stream()
                .mapToInt(q -> q.getQueueName().length())
                .max().orElse(10));
        nameWidth = Math.min(nameWidth, 50); // Cap at 50 chars

        if (verbose) {
            printVerboseTable(queues, nameWidth);
        } else {
            printCompactTable(queues, nameWidth);
        }
    }

    private void printCompactTable(List<QueueInfo> queues, int nameWidth) {
        // Header
        String headerFormat = "%-" + nameWidth + "s  %-8s  %-12s  %12s  %12s  %10s  %6s%n";
        String rowFormat = "%-" + nameWidth + "s  %-8s  %-12s  %,12d  %12s  %10s  %6d%n";

        System.out.printf(headerFormat,
                "QUEUE NAME", "STATUS", "ACCESS TYPE", "MSGS SPOOLED", "SPOOL USAGE", "HWM", "BINDS");
        System.out.println(repeat("-", nameWidth + 77));

        for (QueueInfo q : queues) {
            String name = q.getQueueName();
            if (name.length() > nameWidth) {
                name = name.substring(0, nameWidth - 3) + "...";
            }

            System.out.printf(rowFormat,
                    name,
                    q.getStatus(),
                    q.getAccessType() != null ? q.getAccessType() : "n/a",
                    q.getSpooledMsgCount(),
                    q.formatSpoolUsage(),
                    q.formatHighWaterMark(),
                    q.getBindCount());
        }
    }

    private void printVerboseTable(List<QueueInfo> queues, int nameWidth) {
        // More detailed output with additional columns
        String headerFormat = "%-" + nameWidth + "s  %-10s  %-8s  %-12s  %5s  %12s  %12s  %10s  %6s  %8s%n";
        String rowFormat = "%-" + nameWidth + "s  %-10s  %-8s  %-12s  %5d  %,12d  %12s  %10s  %6d  %8d%n";

        System.out.printf(headerFormat,
                "QUEUE NAME", "MSG VPN", "STATUS", "ACCESS TYPE", "PART",
                "MSGS SPOOLED", "SPOOL USAGE", "HWM", "BINDS", "MAX BIND");
        System.out.println(repeat("-", nameWidth + 107));

        for (QueueInfo q : queues) {
            String name = q.getQueueName();
            if (name.length() > nameWidth) {
                name = name.substring(0, nameWidth - 3) + "...";
            }

            String vpn = q.getMsgVpnName();
            if (vpn != null && vpn.length() > 10) {
                vpn = vpn.substring(0, 7) + "...";
            }

            System.out.printf(rowFormat,
                    name,
                    vpn != null ? vpn : "n/a",
                    q.getStatus(),
                    q.getAccessType() != null ? q.getAccessType() : "n/a",
                    q.getPartitionCount(),
                    q.getSpooledMsgCount(),
                    q.formatSpoolUsage(),
                    q.formatHighWaterMark(),
                    q.getBindCount(),
                    q.getMaxBindCount());
        }
    }

    private void outputCsv(List<QueueInfo> queues) {
        // Header
        System.out.println("queue_name,msg_vpn,status,access_type,partition_count," +
                "msgs_spooled,spool_usage_bytes,hwm_bytes,bind_count,max_bind_count," +
                "ingress_enabled,egress_enabled,max_spool_mb");

        for (QueueInfo q : queues) {
            System.out.printf("%s,%s,%s,%s,%d,%d,%d,%d,%d,%d,%s,%s,%d%n",
                    escapeCsv(q.getQueueName()),
                    escapeCsv(q.getMsgVpnName()),
                    q.getStatus(),
                    q.getAccessType(),
                    q.getPartitionCount(),
                    q.getSpooledMsgCount(),
                    q.getMsgSpoolUsage(),
                    q.getHighestMsgSpoolUsage(),
                    q.getBindCount(),
                    q.getMaxBindCount(),
                    q.isIngressEnabled(),
                    q.isEgressEnabled(),
                    q.getMaxMsgSpoolUsage());
        }
    }

    private void outputJson(List<QueueInfo> queues) {
        System.out.println("[");
        for (int i = 0; i < queues.size(); i++) {
            QueueInfo q = queues.get(i);
            System.out.println("  {");
            System.out.println("    \"queueName\": \"" + escapeJson(q.getQueueName()) + "\",");
            System.out.println("    \"msgVpnName\": \"" + escapeJson(q.getMsgVpnName()) + "\",");
            System.out.println("    \"status\": \"" + q.getStatus() + "\",");
            System.out.println("    \"accessType\": \"" + q.getAccessType() + "\",");
            System.out.println("    \"partitionCount\": " + q.getPartitionCount() + ",");
            System.out.println("    \"spooledMsgCount\": " + q.getSpooledMsgCount() + ",");
            System.out.println("    \"msgSpoolUsage\": " + q.getMsgSpoolUsage() + ",");
            System.out.println("    \"msgSpoolUsageMB\": " + String.format("%.4f", q.getSpoolUsageMB()) + ",");
            System.out.println("    \"highestMsgSpoolUsage\": " + q.getHighestMsgSpoolUsage() + ",");
            System.out.println("    \"highWaterMarkMB\": " + String.format("%.4f", q.getHighWaterMarkMB()) + ",");
            System.out.println("    \"bindCount\": " + q.getBindCount() + ",");
            System.out.println("    \"maxBindCount\": " + q.getMaxBindCount() + ",");
            System.out.println("    \"ingressEnabled\": " + q.isIngressEnabled() + ",");
            System.out.println("    \"egressEnabled\": " + q.isEgressEnabled() + ",");
            System.out.println("    \"maxMsgSpoolUsage\": " + q.getMaxMsgSpoolUsage());
            System.out.print("  }");
            if (i < queues.size() - 1) {
                System.out.println(",");
            } else {
                System.out.println();
            }
        }
        System.out.println("]");
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }

    private String repeat(String str, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(str);
        }
        return sb.toString();
    }
}
