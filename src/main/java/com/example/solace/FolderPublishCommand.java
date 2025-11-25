package com.example.solace;

import com.solacesystems.jcsmp.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileReader;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.Callable;

@Command(
    name = "folder-publish",
    aliases = {"folder-pub", "dir-pub"},
    description = "Publish messages from files in a folder to Solace",
    mixinStandardHelpOptions = true
)
public class FolderPublishCommand implements Callable<Integer> {

    @Mixin
    ConnectionOptions connection;

    @Parameters(index = "0",
            description = "Folder path containing message files")
    String folderPath;

    @Option(names = {"--pattern"},
            description = "File name pattern filter (e.g., '*.xml', '*.json')",
            defaultValue = "*")
    String filePattern;

    @Option(names = {"--recursive", "-r"},
            description = "Process subfolders recursively")
    boolean recursive;

    @Option(names = {"--use-filename-as-correlation"},
            description = "Use filename (without extension) as correlation ID")
    boolean useFilenameAsCorrelation;

    @Option(names = {"--correlation-id"},
            description = "Correlation ID for all messages")
    String correlationId;

    @Option(names = {"--delivery-mode"},
            description = "Delivery mode: PERSISTENT or DIRECT",
            defaultValue = "PERSISTENT")
    String deliveryMode;

    @Option(names = {"--ttl"},
            description = "Time to live in milliseconds (0 = no expiry)",
            defaultValue = "0")
    long ttl;

    @Option(names = {"--dry-run"},
            description = "List files but don't publish messages")
    boolean dryRun;

    @Option(names = {"--sort"},
            description = "Sort files by: NAME, DATE, SIZE",
            defaultValue = "NAME")
    String sortBy;

    @Option(names = {"--second-queue", "-Q"},
            description = "Also publish to this second queue (fan-out)")
    String secondQueue;

    @Option(names = {"--exclude-file"},
            description = "File containing filename patterns to exclude (one per line)")
    File excludeFile;

    @Option(names = {"--exclude-content"},
            description = "Also exclude files whose content matches patterns in exclude file")
    boolean excludeByContent;

    private ExclusionList exclusionList;

    @Override
    public Integer call() {
        JCSMPSession session = null;
        XMLMessageProducer producer = null;

        try {
            File folder = new File(folderPath);
            if (!folder.exists()) {
                System.err.println("Error: Folder does not exist: " + folderPath);
                return 1;
            }
            if (!folder.isDirectory()) {
                System.err.println("Error: Path is not a folder: " + folderPath);
                return 1;
            }

            // Load exclusion list if specified
            if (excludeFile != null) {
                if (!excludeFile.exists()) {
                    System.err.println("Error: Exclude file not found: " + excludeFile.getAbsolutePath());
                    return 1;
                }
                exclusionList = ExclusionList.fromFile(excludeFile);
                System.out.println("Loaded " + exclusionList.size() + " exclusion pattern(s) from " + excludeFile.getName());
            }

            // Get files matching pattern
            File[] files = getFiles(folder);
            if (files == null || files.length == 0) {
                System.out.println("No files found matching pattern '" + filePattern + "' in " + folderPath);
                return 0;
            }

            // Sort files
            sortFiles(files);

            System.out.println("Found " + files.length + " file(s) to process");

            if (dryRun) {
                System.out.println("\n=== DRY RUN MODE - Messages will not be published ===\n");
                for (File file : files) {
                    System.out.println("  " + file.getPath() + " (" + file.length() + " bytes)");
                }
                System.out.println("\nDry run complete. Found " + files.length + " file(s) to publish.");
                return 0;
            }

            // Connect to Solace
            System.out.println("Connecting to " + connection.host + "...");
            session = SolaceConnection.createSession(connection);
            System.out.println("Connected successfully");

            Queue queue = JCSMPFactory.onlyInstance().createQueue(connection.queue);
            Queue queue2 = (secondQueue != null) ? JCSMPFactory.onlyInstance().createQueue(secondQueue) : null;

            producer = session.getMessageProducer(new JCSMPStreamingPublishCorrelatingEventHandler() {
                @Override
                public void responseReceivedEx(Object key) {
                    // Message acknowledged
                }

                @Override
                public void handleErrorEx(Object key, JCSMPException cause, long timestamp) {
                    System.err.println("Error publishing message: " + cause.getMessage());
                }
            });

            DeliveryMode mode = "DIRECT".equalsIgnoreCase(deliveryMode)
                ? DeliveryMode.DIRECT
                : DeliveryMode.PERSISTENT;

            int successCount = 0;
            int errorCount = 0;
            int excludedCount = 0;

            // Use progress reporter for batches of more than 10 files
            ProgressReporter progress = null;
            boolean showProgress = files.length > 10;
            if (showProgress) {
                int totalOps = (queue2 != null) ? files.length * 2 : files.length;
                progress = new ProgressReporter("Publishing", totalOps, 2);
                progress.start();
            }

            for (File file : files) {
                try {
                    // Check filename exclusion
                    if (shouldExcludeFile(file.getName())) {
                        excludedCount++;
                        if (!showProgress) {
                            System.out.println("Excluded: " + file.getName() + " (filename match)");
                        }
                        continue;
                    }

                    String content = readFile(file);

                    // Check content exclusion
                    if (excludeByContent && exclusionList != null && exclusionList.containsExcluded(content)) {
                        excludedCount++;
                        if (!showProgress) {
                            System.out.println("Excluded: " + file.getName() + " (content match)");
                        }
                        continue;
                    }

                    TextMessage msg = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
                    msg.setText(content);
                    msg.setDeliveryMode(mode);

                    // Set correlation ID
                    String msgCorrelationId = correlationId;
                    if (useFilenameAsCorrelation) {
                        msgCorrelationId = getFilenameWithoutExtension(file);
                    }
                    if (msgCorrelationId != null) {
                        msg.setCorrelationId(msgCorrelationId);
                    }

                    if (ttl > 0) {
                        msg.setTimeToLive(ttl);
                    }

                    producer.send(msg, queue);
                    successCount++;
                    if (showProgress) {
                        progress.increment();
                    } else {
                        System.out.println("Published: " + file.getName() + " to '" + connection.queue + "' (" + content.length() + " chars)");
                    }

                    if (queue2 != null) {
                        producer.send(msg, queue2);
                        successCount++;
                        if (showProgress) {
                            progress.increment();
                        } else {
                            System.out.println("Published: " + file.getName() + " to '" + secondQueue + "' (" + content.length() + " chars)");
                        }
                    }

                } catch (Exception e) {
                    errorCount++;
                    if (showProgress) {
                        progress.incrementError();
                    } else {
                        System.err.println("Failed to publish " + file.getName() + ": " + e.getMessage());
                    }
                }
            }

            if (showProgress) {
                progress.stop();
                progress.printSummary();
                if (excludedCount > 0) {
                    System.out.println("  Excluded: " + excludedCount);
                }
            } else {
                System.out.println("\nCompleted: " + successCount + " published, " + errorCount + " failed" +
                    (excludedCount > 0 ? ", " + excludedCount + " excluded" : ""));
            }
            return errorCount > 0 ? 1 : 0;

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            return 1;
        } finally {
            if (producer != null) {
                producer.close();
            }
            if (session != null) {
                session.closeSession();
            }
        }
    }

    private File[] getFiles(File folder) {
        final String pattern = filePattern.replace(".", "\\.").replace("*", ".*").replace("?", ".");

        FileFilter filter = new FileFilter() {
            @Override
            public boolean accept(File file) {
                if (file.isDirectory()) {
                    return false;
                }
                if ("*".equals(filePattern)) {
                    return true;
                }
                return file.getName().matches(pattern);
            }
        };

        if (recursive) {
            return getFilesRecursive(folder, filter).toArray(new File[0]);
        }
        return folder.listFiles(filter);
    }

    private java.util.List<File> getFilesRecursive(File folder, FileFilter filter) {
        java.util.List<File> result = new java.util.ArrayList<File>();
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    result.addAll(getFilesRecursive(file, filter));
                } else if (filter.accept(file)) {
                    result.add(file);
                }
            }
        }
        return result;
    }

    private void sortFiles(File[] files) {
        Comparator<File> comparator;
        if ("DATE".equalsIgnoreCase(sortBy)) {
            comparator = new Comparator<File>() {
                @Override
                public int compare(File f1, File f2) {
                    return Long.compare(f1.lastModified(), f2.lastModified());
                }
            };
        } else if ("SIZE".equalsIgnoreCase(sortBy)) {
            comparator = new Comparator<File>() {
                @Override
                public int compare(File f1, File f2) {
                    return Long.compare(f1.length(), f2.length());
                }
            };
        } else {
            comparator = new Comparator<File>() {
                @Override
                public int compare(File f1, File f2) {
                    return f1.getName().compareTo(f2.getName());
                }
            };
        }
        Arrays.sort(files, comparator);
    }

    private String readFile(File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(line);
            }
        } finally {
            if (reader != null) {
                reader.close();
            }
        }
        return sb.toString();
    }

    private String getFilenameWithoutExtension(File file) {
        String name = file.getName();
        int lastDot = name.lastIndexOf('.');
        if (lastDot > 0) {
            return name.substring(0, lastDot);
        }
        return name;
    }

    /**
     * Check if a file should be excluded based on its filename.
     */
    private boolean shouldExcludeFile(String filename) {
        if (exclusionList == null || exclusionList.isEmpty()) {
            return false;
        }
        return exclusionList.isExcluded(filename);
    }
}
