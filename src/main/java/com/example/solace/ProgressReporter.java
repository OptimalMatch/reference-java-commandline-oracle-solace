package com.example.solace;

import java.text.DecimalFormat;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Utility class for displaying progress during long-running CLI operations.
 * Supports both known-total (percentage) and unknown-total (counter) modes.
 */
public class ProgressReporter {

    private final String operation;
    private final int total;
    private final AtomicInteger processed = new AtomicInteger(0);
    private final AtomicInteger errors = new AtomicInteger(0);
    private final AtomicLong startTime = new AtomicLong(0);
    private final int reportInterval;
    private final DecimalFormat df = new DecimalFormat("#,###");

    private volatile boolean running = true;
    private Thread reporterThread;
    private int lastReportedCount = 0;

    /**
     * Create a progress reporter with known total (shows percentage).
     *
     * @param operation      Description of the operation (e.g., "Publishing", "Consuming")
     * @param total          Total number of items to process
     * @param reportInterval Interval in seconds between progress reports
     */
    public ProgressReporter(String operation, int total, int reportInterval) {
        this.operation = operation;
        this.total = total;
        this.reportInterval = reportInterval > 0 ? reportInterval : 5;
    }

    /**
     * Create a progress reporter with unknown total (shows counter only).
     *
     * @param operation      Description of the operation
     * @param reportInterval Interval in seconds between progress reports
     */
    public ProgressReporter(String operation, int reportInterval) {
        this(operation, -1, reportInterval);
    }

    /**
     * Start the progress reporter in a background thread.
     */
    public void start() {
        startTime.set(System.currentTimeMillis());
        reporterThread = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(reportInterval * 1000L);
                    if (!running) break;
                    printProgress();
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        reporterThread.setDaemon(true);
        reporterThread.start();
    }

    /**
     * Increment the processed count by 1.
     */
    public void increment() {
        processed.incrementAndGet();
    }

    /**
     * Increment the processed count by the specified amount.
     */
    public void incrementBy(int amount) {
        processed.addAndGet(amount);
    }

    /**
     * Increment the error count by 1.
     */
    public void incrementError() {
        errors.incrementAndGet();
    }

    /**
     * Get the current processed count.
     */
    public int getProcessed() {
        return processed.get();
    }

    /**
     * Get the current error count.
     */
    public int getErrors() {
        return errors.get();
    }

    /**
     * Stop the progress reporter and print final summary.
     */
    public void stop() {
        running = false;
        if (reporterThread != null) {
            reporterThread.interrupt();
            try {
                reporterThread.join(1000);
            } catch (InterruptedException e) {
                // Ignore
            }
        }
    }

    /**
     * Print current progress to stdout.
     */
    private void printProgress() {
        int current = processed.get();
        int errorCount = errors.get();
        long elapsed = System.currentTimeMillis() - startTime.get();
        double rate = elapsed > 0 ? (current * 1000.0 / elapsed) : 0;
        int delta = current - lastReportedCount;
        double intervalRate = delta / (double) reportInterval;

        StringBuilder sb = new StringBuilder();
        sb.append(operation).append(": ").append(df.format(current));

        if (total > 0) {
            double percent = (current * 100.0) / total;
            sb.append("/").append(df.format(total));
            sb.append(" (").append(String.format("%.1f%%", percent)).append(")");
        }

        sb.append(" | ").append(String.format("%.0f", intervalRate)).append("/s");

        if (errorCount > 0) {
            sb.append(" | Errors: ").append(df.format(errorCount));
        }

        // Estimate remaining time if we know the total
        if (total > 0 && rate > 0 && current < total) {
            long remainingItems = total - current;
            long remainingMs = (long) (remainingItems / rate * 1000);
            sb.append(" | ETA: ").append(formatDuration(remainingMs));
        }

        System.out.println(sb.toString());
        lastReportedCount = current;
    }

    /**
     * Print final summary after operation completes.
     */
    public void printSummary() {
        int current = processed.get();
        int errorCount = errors.get();
        long elapsed = System.currentTimeMillis() - startTime.get();
        double rate = elapsed > 0 ? (current * 1000.0 / elapsed) : 0;

        System.out.println();
        System.out.println(operation + " complete:");
        System.out.println("  Processed: " + df.format(current) + (total > 0 ? "/" + df.format(total) : ""));
        if (errorCount > 0) {
            System.out.println("  Errors:    " + df.format(errorCount));
        }
        System.out.println("  Duration:  " + formatDuration(elapsed));
        System.out.println("  Rate:      " + String.format("%.1f", rate) + "/s");
    }

    /**
     * Format duration in human-readable format.
     */
    private String formatDuration(long ms) {
        if (ms < 1000) {
            return ms + "ms";
        } else if (ms < 60000) {
            return String.format("%.1fs", ms / 1000.0);
        } else if (ms < 3600000) {
            long minutes = ms / 60000;
            long seconds = (ms % 60000) / 1000;
            return String.format("%dm %ds", minutes, seconds);
        } else {
            long hours = ms / 3600000;
            long minutes = (ms % 3600000) / 60000;
            return String.format("%dh %dm", hours, minutes);
        }
    }

    /**
     * Create a simple spinner for operations where count isn't meaningful.
     */
    public static class Spinner {
        private static final String[] FRAMES = {"|", "/", "-", "\\"};
        private int frame = 0;
        private final String message;
        private volatile boolean running = true;
        private Thread spinnerThread;

        public Spinner(String message) {
            this.message = message;
        }

        public void start() {
            spinnerThread = new Thread(() -> {
                while (running) {
                    System.out.print("\r" + FRAMES[frame] + " " + message);
                    frame = (frame + 1) % FRAMES.length;
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            });
            spinnerThread.setDaemon(true);
            spinnerThread.start();
        }

        public void stop(String finalMessage) {
            running = false;
            if (spinnerThread != null) {
                spinnerThread.interrupt();
                try {
                    spinnerThread.join(500);
                } catch (InterruptedException e) {
                    // Ignore
                }
            }
            System.out.print("\r" + finalMessage + "                    \n");
        }
    }
}
