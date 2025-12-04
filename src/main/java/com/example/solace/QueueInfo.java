package com.example.solace;

import java.util.Map;

/**
 * Represents queue information from SEMP v2 Monitor API.
 */
public class QueueInfo {

    private String queueName;
    private String msgVpnName;
    private boolean enabled;
    private String accessType;
    private String permission;
    private int partitionCount;
    private long partitionRebalanceDelay;
    private boolean ingressEnabled;
    private boolean egressEnabled;
    private int bindCount;
    private int maxBindCount;
    private long spooledMsgCount;
    private long msgSpoolUsage;           // Current spool usage in bytes
    private long maxMsgSpoolUsage;        // Max allowed spool in MB
    private long highestMsgSpoolUsage;    // High water mark in bytes
    private long maxMsgSize;
    private boolean respectMsgPriorityEnabled;
    private boolean consumerAckPropagationEnabled;
    private String selector;

    // For subscription count, we'd need a separate API call
    private int subscriptionCount;

    public static QueueInfo fromSempResponse(Map<String, Object> data) {
        QueueInfo info = new QueueInfo();

        info.queueName = getString(data, "queueName");
        info.msgVpnName = getString(data, "msgVpnName");
        info.enabled = getBoolean(data, "enabled");
        info.accessType = getString(data, "accessType");
        info.permission = getString(data, "permission");
        info.partitionCount = getInt(data, "partitionCount");
        info.partitionRebalanceDelay = getLong(data, "partitionRebalanceDelay");
        info.ingressEnabled = getBoolean(data, "ingressEnabled");
        info.egressEnabled = getBoolean(data, "egressEnabled");
        info.bindCount = getInt(data, "bindCount");
        info.maxBindCount = getInt(data, "maxBindCount");
        info.spooledMsgCount = getLong(data, "spooledMsgCount");
        info.msgSpoolUsage = getLong(data, "msgSpoolUsage");
        info.maxMsgSpoolUsage = getLong(data, "maxMsgSpoolUsage");
        info.highestMsgSpoolUsage = getLong(data, "highestMsgSpoolUsage");
        info.maxMsgSize = getLong(data, "maxMsgSize");
        info.respectMsgPriorityEnabled = getBoolean(data, "respectMsgPriorityEnabled");
        info.consumerAckPropagationEnabled = getBoolean(data, "consumerAckPropagationEnabled");

        return info;
    }

    private static String getString(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value != null ? value.toString() : null;
    }

    private static boolean getBoolean(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static int getInt(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long getLong(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    // Getters

    public String getQueueName() {
        return queueName;
    }

    public String getMsgVpnName() {
        return msgVpnName;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getAccessType() {
        return accessType;
    }

    public String getPermission() {
        return permission;
    }

    public int getPartitionCount() {
        return partitionCount;
    }

    public long getPartitionRebalanceDelay() {
        return partitionRebalanceDelay;
    }

    public boolean isIngressEnabled() {
        return ingressEnabled;
    }

    public boolean isEgressEnabled() {
        return egressEnabled;
    }

    public int getBindCount() {
        return bindCount;
    }

    public int getMaxBindCount() {
        return maxBindCount;
    }

    public long getSpooledMsgCount() {
        return spooledMsgCount;
    }

    public long getMsgSpoolUsage() {
        return msgSpoolUsage;
    }

    public long getMaxMsgSpoolUsage() {
        return maxMsgSpoolUsage;
    }

    public long getHighestMsgSpoolUsage() {
        return highestMsgSpoolUsage;
    }

    public long getMaxMsgSize() {
        return maxMsgSize;
    }

    public boolean isRespectMsgPriorityEnabled() {
        return respectMsgPriorityEnabled;
    }

    public boolean isConsumerAckPropagationEnabled() {
        return consumerAckPropagationEnabled;
    }

    public int getSubscriptionCount() {
        return subscriptionCount;
    }

    public void setSubscriptionCount(int subscriptionCount) {
        this.subscriptionCount = subscriptionCount;
    }

    /**
     * Get status string based on enabled and ingress/egress settings.
     */
    public String getStatus() {
        if (!enabled) {
            return "Disabled";
        }
        if (ingressEnabled && egressEnabled) {
            return "Up";
        }
        if (ingressEnabled) {
            return "Ingress Only";
        }
        if (egressEnabled) {
            return "Egress Only";
        }
        return "Shutdown";
    }

    /**
     * Get current spool usage in megabytes.
     */
    public double getSpoolUsageMB() {
        return msgSpoolUsage / (1024.0 * 1024.0);
    }

    /**
     * Get high water mark in megabytes.
     */
    public double getHighWaterMarkMB() {
        return highestMsgSpoolUsage / (1024.0 * 1024.0);
    }

    /**
     * Format spool usage as a human-readable string.
     */
    public String formatSpoolUsage() {
        if (msgSpoolUsage < 1024) {
            return msgSpoolUsage + " B";
        } else if (msgSpoolUsage < 1024 * 1024) {
            return String.format("%.1f KB", msgSpoolUsage / 1024.0);
        } else if (msgSpoolUsage < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", msgSpoolUsage / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", msgSpoolUsage / (1024.0 * 1024.0 * 1024.0));
        }
    }

    /**
     * Format high water mark as a human-readable string.
     */
    public String formatHighWaterMark() {
        if (highestMsgSpoolUsage < 1024) {
            return highestMsgSpoolUsage + " B";
        } else if (highestMsgSpoolUsage < 1024 * 1024) {
            return String.format("%.1f KB", highestMsgSpoolUsage / 1024.0);
        } else if (highestMsgSpoolUsage < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", highestMsgSpoolUsage / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", highestMsgSpoolUsage / (1024.0 * 1024.0 * 1024.0));
        }
    }

    @Override
    public String toString() {
        return "QueueInfo{" +
                "queueName='" + queueName + '\'' +
                ", status=" + getStatus() +
                ", accessType='" + accessType + '\'' +
                ", spooledMsgCount=" + spooledMsgCount +
                ", bindCount=" + bindCount +
                '}';
    }
}
