package com.example.solace;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;

import static com.example.solace.AuditLogger.maskSensitive;

@Command(
    name = "oracle-insert",
    aliases = {"ora-insert", "ora-ins"},
    description = "Read files from a folder and insert their contents into Oracle database",
    mixinStandardHelpOptions = true
)
public class OracleInsertCommand implements Callable<Integer> {

    @Mixin
    OracleOptions oracleConnection;

    @Mixin
    AuditOptions auditOptions;

    @Option(names = {"--folder", "-d"},
            description = "Source folder containing files to insert",
            required = true)
    File sourceFolder;

    @Option(names = {"--pattern", "-p"},
            description = "Glob pattern to filter files (default: *)",
            defaultValue = "*")
    String filePattern;

    @Option(names = {"--recursive", "-r"},
            description = "Recursively scan subdirectories")
    boolean recursive;

    @Option(names = {"--table", "-t"},
            description = "Target table name (required if not using --sql-file)")
    String tableName;

    @Option(names = {"--content-column", "-c"},
            description = "Column name for file content (required if using --table)",
            defaultValue = "content")
    String contentColumn;

    @Option(names = {"--filename-column"},
            description = "Column name to store the filename (optional)")
    String filenameColumn;

    @Option(names = {"--sql-file", "-f"},
            description = "File containing custom INSERT statement (use ? for content, ?? for filename)")
    File sqlFile;

    @Option(names = {"--sort"},
            description = "Sort order: name, date, or size")
    String sortOrder;

    @Option(names = {"--batch-size"},
            description = "Commit after this many inserts (default: 100)",
            defaultValue = "100")
    int batchSize;

    @Option(names = {"--dry-run"},
            description = "Preview files without inserting")
    boolean dryRun;

    @Option(names = {"--exclude-file"},
            description = "File containing patterns to exclude (checks filename and optionally content)")
    File excludeFile;

    @Option(names = {"--exclude-content"},
            description = "Also check file content against exclusion patterns")
    boolean excludeByContent;

    // Internal flag to track if custom SQL file used ?? for filename
    private boolean sqlFileHasFilenamePlaceholder = false;
    private ExclusionList exclusionList;

    @Override
    public Integer call() {
        Connection dbConnection = null;
        AuditLogger audit = AuditLogger.create(auditOptions, "oracle-insert");

        // Log parameters (mask sensitive values)
        audit.addParameter("oracleJdbcUrl", oracleConnection.getJdbcUrl())
             .addParameter("oracleUser", oracleConnection.dbUser)
             .addParameter("oraclePassword", maskSensitive(oracleConnection.dbPassword))
             .addParameter("sourceFolder", sourceFolder != null ? sourceFolder.getAbsolutePath() : null)
             .addParameter("filePattern", filePattern)
             .addParameter("recursive", recursive)
             .addParameter("tableName", tableName)
             .addParameter("contentColumn", contentColumn)
             .addParameter("filenameColumn", filenameColumn)
             .addParameter("batchSize", batchSize)
             .addParameter("dryRun", dryRun);

        try {
            // Validate source folder
            if (!sourceFolder.exists()) {
                System.err.println("Error: Source folder does not exist: " + sourceFolder.getAbsolutePath());
                audit.setError("Source folder does not exist").logCompletion(1);
                return 1;
            }
            if (!sourceFolder.isDirectory()) {
                System.err.println("Error: Source path is not a directory: " + sourceFolder.getAbsolutePath());
                audit.setError("Source path is not a directory").logCompletion(1);
                return 1;
            }

            // Load exclusion list if specified
            if (excludeFile != null) {
                if (!excludeFile.exists()) {
                    System.err.println("Error: Exclude file not found: " + excludeFile.getAbsolutePath());
                    audit.setError("Exclude file not found").logCompletion(1);
                    return 1;
                }
                exclusionList = ExclusionList.fromFile(excludeFile);
                System.out.println("Loaded " + exclusionList.size() + " exclusion pattern(s) from " + excludeFile.getName());
            }

            // Validate table or SQL file
            String insertSql = resolveInsertStatement();
            if (insertSql == null) {
                audit.setError("Failed to resolve insert statement").logCompletion(1);
                return 1;
            }

            // Get list of files
            List<File> files = getFiles();
            if (files.isEmpty()) {
                System.out.println("No files found matching pattern '" + filePattern + "' in " + sourceFolder.getAbsolutePath());
                return 0;
            }

            // Sort files if requested
            sortFiles(files);

            System.out.println("Found " + files.size() + " file(s) to process");

            if (dryRun) {
                System.out.println("\n=== DRY RUN MODE - No data will be inserted ===\n");
                for (int i = 0; i < files.size(); i++) {
                    File file = files.get(i);
                    System.out.println((i + 1) + ". " + file.getName());
                    System.out.println("   Path: " + file.getAbsolutePath());
                    System.out.println("   Size: " + file.length() + " bytes");
                }
                System.out.println("\nDry run complete. Would insert " + files.size() + " record(s).");
                System.out.println("SQL: " + formatQueryForDisplay(insertSql));
                return 0;
            }

            // Connect to Oracle
            System.out.println("Connecting to Oracle at " + oracleConnection.getJdbcUrl() + "...");
            dbConnection = DriverManager.getConnection(
                oracleConnection.getJdbcUrl(),
                oracleConnection.dbUser,
                oracleConnection.dbPassword
            );
            dbConnection.setAutoCommit(false);
            System.out.println("Connected to Oracle successfully");

            // Prepare statement
            PreparedStatement pstmt = dbConnection.prepareStatement(insertSql);

            int insertCount = 0;
            int errorCount = 0;
            int excludedCount = 0;

            // Use progress reporter for batches of more than 10 files
            ProgressReporter progress = null;
            boolean showProgress = files.size() > 10;
            if (showProgress) {
                progress = new ProgressReporter("Inserting", files.size(), 2);
                progress.start();
            }

            for (File file : files) {
                try {
                    String filename = getFilenameWithoutExtension(file.getName());

                    // Check filename exclusion
                    if (shouldExcludeFile(file.getName())) {
                        excludedCount++;
                        if (!showProgress) {
                            System.out.println("Excluded: " + file.getName() + " (filename match)");
                        }
                        continue;
                    }

                    String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

                    // Check content exclusion
                    if (excludeByContent && exclusionList != null && exclusionList.containsExcluded(content)) {
                        excludedCount++;
                        if (!showProgress) {
                            System.out.println("Excluded: " + file.getName() + " (content match)");
                        }
                        continue;
                    }

                    // Set parameters
                    int paramIndex = 1;
                    pstmt.setString(paramIndex++, content);
                    if (filenameColumn != null || sqlFileHasFilenamePlaceholder) {
                        // If using custom SQL with ??, we need to set filename
                        pstmt.setString(paramIndex++, filename);
                    }

                    pstmt.executeUpdate();
                    insertCount++;

                    if (showProgress) {
                        progress.increment();
                    } else {
                        System.out.println("Inserted: " + file.getName() + " (" + content.length() + " chars)");
                    }

                    // Commit batch
                    if (insertCount % batchSize == 0) {
                        dbConnection.commit();
                        if (!showProgress) {
                            System.out.println("  Committed batch of " + batchSize + " records");
                        }
                    }

                } catch (Exception e) {
                    errorCount++;
                    if (showProgress) {
                        progress.incrementError();
                    } else {
                        System.err.println("Error inserting " + file.getName() + ": " + e.getMessage());
                    }
                }
            }

            // Final commit
            dbConnection.commit();

            if (showProgress) {
                progress.stop();
            }

            pstmt.close();

            System.out.println();
            if (showProgress) {
                progress.printSummary();
                if (excludedCount > 0) {
                    System.out.println("  Excluded: " + excludedCount);
                }
            } else {
                System.out.println("Insert complete:");
                System.out.println("  Records inserted: " + insertCount);
                if (excludedCount > 0) {
                    System.out.println("  Records excluded: " + excludedCount);
                }
                if (errorCount > 0) {
                    System.out.println("  Errors: " + errorCount);
                }
            }
            System.out.println("  Target table: " + (tableName != null ? tableName : "(custom SQL)"));

            // Log results
            audit.addResult("recordsInserted", insertCount)
                 .addResult("recordsExcluded", excludedCount)
                 .addResult("errors", errorCount)
                 .addResult("dryRun", dryRun);
            audit.logCompletion(errorCount > 0 ? 1 : 0);
            return errorCount > 0 ? 1 : 0;

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            if (dbConnection != null) {
                try {
                    dbConnection.rollback();
                } catch (Exception re) {
                    // Ignore rollback errors
                }
            }
            audit.setError(e.getMessage()).logCompletion(1);
            return 1;
        } finally {
            if (dbConnection != null) {
                try {
                    dbConnection.close();
                } catch (Exception e) {
                    // Ignore close errors
                }
            }
        }
    }

    /**
     * Resolve INSERT statement from --table or --sql-file options.
     */
    private String resolveInsertStatement() {
        if (tableName != null && sqlFile != null) {
            System.err.println("Error: Cannot specify both --table and --sql-file. Use one or the other.");
            return null;
        }

        if (sqlFile != null) {
            if (!sqlFile.exists()) {
                System.err.println("Error: SQL file not found: " + sqlFile.getAbsolutePath());
                return null;
            }
            try {
                String content = new String(Files.readAllBytes(sqlFile.toPath()), StandardCharsets.UTF_8);
                if (content.trim().isEmpty()) {
                    System.err.println("Error: SQL file is empty: " + sqlFile.getAbsolutePath());
                    return null;
                }
                // Track if ?? placeholder exists for filename, then replace with ?
                sqlFileHasFilenamePlaceholder = content.contains("??");
                String sql = content.trim().replace("??", "?");
                System.out.println("Loaded SQL from file: " + sqlFile.getAbsolutePath());
                return sql;
            } catch (Exception e) {
                System.err.println("Error reading SQL file: " + e.getMessage());
                return null;
            }
        }

        if (tableName == null) {
            System.err.println("Error: Must specify either --table or --sql-file option.");
            return null;
        }

        // Build INSERT statement from table name
        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO ").append(tableName).append(" (").append(contentColumn);
        if (filenameColumn != null) {
            sql.append(", ").append(filenameColumn);
        }
        sql.append(") VALUES (?");
        if (filenameColumn != null) {
            sql.append(", ?");
        }
        sql.append(")");

        return sql.toString();
    }

    /**
     * Get list of files matching the pattern.
     */
    private List<File> getFiles() {
        List<File> result = new ArrayList<File>();
        collectFiles(sourceFolder.toPath(), result);
        return result;
    }

    private void collectFiles(Path dir, List<File> result) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, filePattern)) {
            for (Path path : stream) {
                if (Files.isRegularFile(path)) {
                    result.add(path.toFile());
                }
            }
        } catch (Exception e) {
            System.err.println("Error reading directory " + dir + ": " + e.getMessage());
        }

        if (recursive) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path path : stream) {
                    if (Files.isDirectory(path)) {
                        collectFiles(path, result);
                    }
                }
            } catch (Exception e) {
                // Ignore errors in subdirectories
            }
        }
    }

    /**
     * Sort files based on the sort order option.
     */
    private void sortFiles(List<File> files) {
        if (sortOrder == null || sortOrder.isEmpty()) {
            return;
        }

        switch (sortOrder.toLowerCase()) {
            case "name":
                Collections.sort(files, new Comparator<File>() {
                    @Override
                    public int compare(File f1, File f2) {
                        return f1.getName().compareToIgnoreCase(f2.getName());
                    }
                });
                break;
            case "date":
                Collections.sort(files, new Comparator<File>() {
                    @Override
                    public int compare(File f1, File f2) {
                        return Long.compare(f1.lastModified(), f2.lastModified());
                    }
                });
                break;
            case "size":
                Collections.sort(files, new Comparator<File>() {
                    @Override
                    public int compare(File f1, File f2) {
                        return Long.compare(f1.length(), f2.length());
                    }
                });
                break;
            default:
                System.err.println("Warning: Unknown sort order '" + sortOrder + "', ignoring");
        }
    }

    /**
     * Get filename without extension.
     */
    private String getFilenameWithoutExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0) {
            return filename.substring(0, lastDot);
        }
        return filename;
    }

    /**
     * Format query for display - truncate long queries.
     */
    private String formatQueryForDisplay(String sql) {
        if (sql == null) return "null";
        String oneLine = sql.replaceAll("\\s+", " ").trim();
        if (oneLine.length() > 100) {
            return oneLine.substring(0, 100) + "...";
        }
        return oneLine;
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
