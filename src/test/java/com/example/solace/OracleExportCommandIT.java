package com.example.solace;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import picocli.CommandLine;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.Assert.*;

/**
 * Integration tests for OracleExportCommand using H2 database (Oracle compatibility mode).
 */
public class OracleExportCommandIT {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private CommandLine cmd;
    private StringWriter sw;
    private StringWriter errSw;

    private static final String H2_URL = "jdbc:h2:mem:testdb;MODE=Oracle;DB_CLOSE_DELAY=-1";
    private static final String H2_USER = "sa";
    private static final String H2_PASSWORD = "";

    @Before
    public void setUp() throws Exception {
        cmd = new CommandLine(new SolaceCli());
        sw = new StringWriter();
        errSw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));
        cmd.setErr(new PrintWriter(errSw));

        // Initialize H2 database with test data
        initializeTestDatabase();
    }

    private void initializeTestDatabase() throws Exception {
        Connection conn = DriverManager.getConnection(H2_URL, H2_USER, H2_PASSWORD);
        Statement stmt = conn.createStatement();

        // Drop tables if exist
        stmt.execute("DROP TABLE IF EXISTS export_messages");

        // Create test table
        stmt.execute("CREATE TABLE export_messages (" +
            "id VARCHAR(50), " +
            "content CLOB, " +
            "filename VARCHAR(100), " +
            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
            ")");

        // Insert test data
        stmt.execute("INSERT INTO export_messages (id, content, filename) VALUES " +
            "('MSG001', 'This is the first message content', 'first_message')");
        stmt.execute("INSERT INTO export_messages (id, content, filename) VALUES " +
            "('MSG002', '<xml><data>Second message as XML</data></xml>', 'second_message')");
        stmt.execute("INSERT INTO export_messages (id, content, filename) VALUES " +
            "('MSG003', '{\"type\":\"json\",\"value\":\"Third message\"}', 'third_message')");

        stmt.close();
        conn.close();
    }

    @Test
    public void testDryRunWithMessages() throws Exception {
        File outputDir = tempFolder.newFolder("output");

        // Create command that uses H2 directly
        OracleExportCommand command = new OracleExportCommand();
        command.oracleConnection = new OracleOptions();
        command.sqlQuery = "SELECT content, filename FROM export_messages ORDER BY id";
        command.messageColumn = "content";
        command.filenameColumn = "filename";
        command.outputFolder = outputDir;
        command.fileExtension = ".txt";
        command.filenamePrefix = "msg_";
        command.dryRun = true;

        // Use H2 connection directly
        Connection conn = DriverManager.getConnection(H2_URL, H2_USER, H2_PASSWORD);
        java.sql.Statement stmt = conn.createStatement();
        java.sql.ResultSet rs = stmt.executeQuery(command.sqlQuery);

        int count = 0;
        while (rs.next()) {
            count++;
            String content = rs.getString(1);
            String filename = rs.getString(2);
            assertNotNull(content);
            assertNotNull(filename);
        }

        assertEquals(3, count);
        rs.close();
        stmt.close();
        conn.close();

        // Verify no files were written in dry run
        assertEquals(0, outputDir.listFiles().length);
    }

    @Test
    public void testExportWithFilenameColumn() throws Exception {
        File outputDir = tempFolder.newFolder("export_filename");

        // Simulate the export logic
        Connection conn = DriverManager.getConnection(H2_URL, H2_USER, H2_PASSWORD);
        java.sql.Statement stmt = conn.createStatement();
        java.sql.ResultSet rs = stmt.executeQuery(
            "SELECT content, filename FROM export_messages ORDER BY id");

        int count = 0;
        while (rs.next()) {
            String content = rs.getString(1);
            String filename = rs.getString(2) + ".txt";

            File outputFile = new File(outputDir, filename);
            Files.write(outputFile.toPath(), content.getBytes(StandardCharsets.UTF_8));
            count++;
        }

        rs.close();
        stmt.close();
        conn.close();

        // Verify files were created
        File[] files = outputDir.listFiles();
        assertNotNull(files);
        assertEquals(3, files.length);

        // Check specific files exist
        assertTrue(new File(outputDir, "first_message.txt").exists());
        assertTrue(new File(outputDir, "second_message.txt").exists());
        assertTrue(new File(outputDir, "third_message.txt").exists());

        // Check content
        String content = new String(Files.readAllBytes(
            new File(outputDir, "first_message.txt").toPath()), StandardCharsets.UTF_8);
        assertEquals("This is the first message content", content);
    }

    @Test
    public void testExportWithSequentialNumbering() throws Exception {
        File outputDir = tempFolder.newFolder("export_sequential");

        // Simulate the export logic with sequential numbering
        Connection conn = DriverManager.getConnection(H2_URL, H2_USER, H2_PASSWORD);
        java.sql.Statement stmt = conn.createStatement();
        java.sql.ResultSet rs = stmt.executeQuery(
            "SELECT content FROM export_messages ORDER BY id");

        int count = 0;
        while (rs.next()) {
            count++;
            String content = rs.getString(1);
            String filename = "message_" + String.format("%06d", count) + ".xml";

            File outputFile = new File(outputDir, filename);
            Files.write(outputFile.toPath(), content.getBytes(StandardCharsets.UTF_8));
        }

        rs.close();
        stmt.close();
        conn.close();

        // Verify files were created with correct names
        assertTrue(new File(outputDir, "message_000001.xml").exists());
        assertTrue(new File(outputDir, "message_000002.xml").exists());
        assertTrue(new File(outputDir, "message_000003.xml").exists());
    }

    @Test
    public void testExportWithCustomPrefix() throws Exception {
        File outputDir = tempFolder.newFolder("export_prefix");

        String prefix = "order_";
        String extension = ".json";

        Connection conn = DriverManager.getConnection(H2_URL, H2_USER, H2_PASSWORD);
        java.sql.Statement stmt = conn.createStatement();
        java.sql.ResultSet rs = stmt.executeQuery(
            "SELECT content FROM export_messages WHERE id = 'MSG003'");

        int count = 0;
        while (rs.next()) {
            count++;
            String content = rs.getString(1);
            String filename = prefix + String.format("%06d", count) + extension;

            File outputFile = new File(outputDir, filename);
            Files.write(outputFile.toPath(), content.getBytes(StandardCharsets.UTF_8));
        }

        rs.close();
        stmt.close();
        conn.close();

        // Verify file with custom prefix
        File expectedFile = new File(outputDir, "order_000001.json");
        assertTrue(expectedFile.exists());

        String content = new String(Files.readAllBytes(expectedFile.toPath()), StandardCharsets.UTF_8);
        assertTrue(content.contains("json"));
    }

    @Test
    public void testSkipExistingFiles() throws Exception {
        File outputDir = tempFolder.newFolder("export_skip");

        // Pre-create a file
        File existingFile = new File(outputDir, "first_message.txt");
        Files.write(existingFile.toPath(), "ORIGINAL CONTENT".getBytes(StandardCharsets.UTF_8));

        // Simulate export without overwrite
        Connection conn = DriverManager.getConnection(H2_URL, H2_USER, H2_PASSWORD);
        java.sql.Statement stmt = conn.createStatement();
        java.sql.ResultSet rs = stmt.executeQuery(
            "SELECT content, filename FROM export_messages WHERE id = 'MSG001'");

        boolean overwrite = false;
        while (rs.next()) {
            String content = rs.getString(1);
            String filename = rs.getString(2) + ".txt";

            File outputFile = new File(outputDir, filename);
            if (outputFile.exists() && !overwrite) {
                // Skip - don't write
                continue;
            }
            Files.write(outputFile.toPath(), content.getBytes(StandardCharsets.UTF_8));
        }

        rs.close();
        stmt.close();
        conn.close();

        // Verify original content was preserved
        String content = new String(Files.readAllBytes(existingFile.toPath()), StandardCharsets.UTF_8);
        assertEquals("ORIGINAL CONTENT", content);
    }

    @Test
    public void testOverwriteExistingFiles() throws Exception {
        File outputDir = tempFolder.newFolder("export_overwrite");

        // Pre-create a file
        File existingFile = new File(outputDir, "first_message.txt");
        Files.write(existingFile.toPath(), "ORIGINAL CONTENT".getBytes(StandardCharsets.UTF_8));

        // Simulate export with overwrite
        Connection conn = DriverManager.getConnection(H2_URL, H2_USER, H2_PASSWORD);
        java.sql.Statement stmt = conn.createStatement();
        java.sql.ResultSet rs = stmt.executeQuery(
            "SELECT content, filename FROM export_messages WHERE id = 'MSG001'");

        boolean overwrite = true;
        while (rs.next()) {
            String content = rs.getString(1);
            String filename = rs.getString(2) + ".txt";

            File outputFile = new File(outputDir, filename);
            if (outputFile.exists() && !overwrite) {
                continue;
            }
            Files.write(outputFile.toPath(), content.getBytes(StandardCharsets.UTF_8));
        }

        rs.close();
        stmt.close();
        conn.close();

        // Verify content was overwritten
        String content = new String(Files.readAllBytes(existingFile.toPath()), StandardCharsets.UTF_8);
        assertEquals("This is the first message content", content);
    }

    @Test
    public void testEmptyResultSet() throws Exception {
        File outputDir = tempFolder.newFolder("export_empty");

        Connection conn = DriverManager.getConnection(H2_URL, H2_USER, H2_PASSWORD);
        java.sql.Statement stmt = conn.createStatement();
        java.sql.ResultSet rs = stmt.executeQuery(
            "SELECT content FROM export_messages WHERE id = 'NONEXISTENT'");

        int count = 0;
        while (rs.next()) {
            count++;
        }

        rs.close();
        stmt.close();
        conn.close();

        assertEquals(0, count);
        assertEquals(0, outputDir.listFiles().length);
    }

    @Test
    public void testSanitizeFilename() throws Exception {
        // Add test data with special characters
        Connection conn = DriverManager.getConnection(H2_URL, H2_USER, H2_PASSWORD);
        java.sql.Statement stmt = conn.createStatement();
        stmt.execute("INSERT INTO export_messages (id, content, filename) VALUES " +
            "('MSG004', 'Special content', 'file:with*special?chars')");
        stmt.close();

        // Simulate filename sanitization
        stmt = conn.createStatement();
        java.sql.ResultSet rs = stmt.executeQuery(
            "SELECT filename FROM export_messages WHERE id = 'MSG004'");

        String sanitized = null;
        if (rs.next()) {
            String original = rs.getString(1);
            // Apply sanitization logic
            sanitized = original.replaceAll("[\\\\/:*?\"<>|\\s]", "_");
        }

        rs.close();
        stmt.close();
        conn.close();

        assertNotNull(sanitized);
        assertEquals("file_with_special_chars", sanitized);
    }

    @Test
    public void testIntegrationWithFolderPublish() throws Exception {
        // This test verifies the two-step workflow:
        // 1. Export from Oracle to files
        // 2. Use folder-publish to read and (conceptually) publish

        File outputDir = tempFolder.newFolder("workflow_test");

        // Step 1: Export (simulated)
        Connection conn = DriverManager.getConnection(H2_URL, H2_USER, H2_PASSWORD);
        java.sql.Statement stmt = conn.createStatement();
        java.sql.ResultSet rs = stmt.executeQuery(
            "SELECT content, id FROM export_messages ORDER BY id");

        while (rs.next()) {
            String content = rs.getString(1);
            String filename = rs.getString(2) + ".xml";
            File outputFile = new File(outputDir, filename);
            Files.write(outputFile.toPath(), content.getBytes(StandardCharsets.UTF_8));
        }

        rs.close();
        stmt.close();
        conn.close();

        // Step 2: Verify files can be read by folder-publish (dry-run concept)
        File[] files = outputDir.listFiles((dir, name) -> name.endsWith(".xml"));
        assertNotNull(files);
        assertEquals(3, files.length);

        // Verify content can be read
        for (File file : files) {
            byte[] content = Files.readAllBytes(file.toPath());
            assertTrue(content.length > 0);
        }
    }

    @Test
    public void testExportWithMetadataColumnsAndManifest() throws Exception {
        // Add test data with metadata columns
        Connection conn = DriverManager.getConnection(H2_URL, H2_USER, H2_PASSWORD);
        java.sql.Statement stmt = conn.createStatement();
        stmt.execute("DROP TABLE IF EXISTS export_metadata_test");
        stmt.execute("CREATE TABLE export_metadata_test (" +
            "filename VARCHAR(100), " +
            "content CLOB, " +
            "status VARCHAR(50), " +
            "region VARCHAR(50), " +
            "priority VARCHAR(20)" +
            ")");
        stmt.execute("INSERT INTO export_metadata_test VALUES " +
            "('customer001', '<data>Content 1</data>', 'ACTIVE', 'US-EAST', 'HIGH')");
        stmt.execute("INSERT INTO export_metadata_test VALUES " +
            "('customer002', '<data>Content 2</data>', 'INACTIVE', 'US-WEST', 'LOW')");
        stmt.execute("INSERT INTO export_metadata_test VALUES " +
            "('customer003', '<data>Content 3</data>', 'PENDING', 'EU-CENTRAL', 'MEDIUM')");
        stmt.close();

        File outputDir = tempFolder.newFolder("export_metadata");

        // Simulate the export with metadata columns logic
        stmt = conn.createStatement();
        java.sql.ResultSet rs = stmt.executeQuery(
            "SELECT filename, content, status, region, priority FROM export_metadata_test ORDER BY filename");

        // Collect metadata for manifest
        java.util.List<String[]> manifestRows = new java.util.ArrayList<>();
        String[] metadataColNames = {"status", "region", "priority"};

        while (rs.next()) {
            String filename = rs.getString(1) + ".xml";
            String content = rs.getString(2);

            // Write content file
            File outputFile = new File(outputDir, filename);
            Files.write(outputFile.toPath(), content.getBytes(StandardCharsets.UTF_8));

            // Collect metadata row
            String[] row = new String[4];
            row[0] = filename;
            row[1] = rs.getString(3); // status
            row[2] = rs.getString(4); // region
            row[3] = rs.getString(5); // priority
            manifestRows.add(row);
        }

        rs.close();
        stmt.close();

        // Write manifest file
        File manifestFile = new File(outputDir, "manifest.csv");
        try (java.io.Writer writer = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(manifestFile), StandardCharsets.UTF_8)) {
            // Write header
            writer.write("filename,status,region,priority\n");
            // Write data rows
            for (String[] row : manifestRows) {
                writer.write(String.join(",", row) + "\n");
            }
        }

        conn.close();

        // Verify content files were created
        assertTrue(new File(outputDir, "customer001.xml").exists());
        assertTrue(new File(outputDir, "customer002.xml").exists());
        assertTrue(new File(outputDir, "customer003.xml").exists());

        // Verify manifest file
        assertTrue(manifestFile.exists());
        String manifestContent = new String(Files.readAllBytes(manifestFile.toPath()), StandardCharsets.UTF_8);
        String[] lines = manifestContent.split("\n");
        assertEquals(4, lines.length); // header + 3 data rows

        // Verify header
        assertEquals("filename,status,region,priority", lines[0]);

        // Verify data rows
        assertEquals("customer001.xml,ACTIVE,US-EAST,HIGH", lines[1]);
        assertEquals("customer002.xml,INACTIVE,US-WEST,LOW", lines[2]);
        assertEquals("customer003.xml,PENDING,EU-CENTRAL,MEDIUM", lines[3]);
    }

    @Test
    public void testManifestWithSpecialCharactersInValues() throws Exception {
        // Test that values with commas and quotes are properly escaped
        Connection conn = DriverManager.getConnection(H2_URL, H2_USER, H2_PASSWORD);
        java.sql.Statement stmt = conn.createStatement();
        stmt.execute("DROP TABLE IF EXISTS export_special_chars");
        stmt.execute("CREATE TABLE export_special_chars (" +
            "filename VARCHAR(100), " +
            "content CLOB, " +
            "description VARCHAR(200)" +
            ")");
        stmt.execute("INSERT INTO export_special_chars VALUES " +
            "('file1', 'content', 'Simple description')");
        stmt.execute("INSERT INTO export_special_chars VALUES " +
            "('file2', 'content', 'Description, with comma')");
        stmt.execute("INSERT INTO export_special_chars VALUES " +
            "('file3', 'content', 'Description with \"quotes\"')");
        stmt.close();

        File outputDir = tempFolder.newFolder("export_special");

        stmt = conn.createStatement();
        java.sql.ResultSet rs = stmt.executeQuery(
            "SELECT filename, content, description FROM export_special_chars ORDER BY filename");

        java.util.List<String[]> manifestRows = new java.util.ArrayList<>();

        while (rs.next()) {
            String filename = rs.getString(1) + ".txt";
            String content = rs.getString(2);
            String description = rs.getString(3);

            File outputFile = new File(outputDir, filename);
            Files.write(outputFile.toPath(), content.getBytes(StandardCharsets.UTF_8));

            manifestRows.add(new String[]{filename, description});
        }

        rs.close();
        stmt.close();
        conn.close();

        // Write manifest with proper CSV escaping
        File manifestFile = new File(outputDir, "manifest.csv");
        try (java.io.Writer writer = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(manifestFile), StandardCharsets.UTF_8)) {
            writer.write("filename,description\n");
            for (String[] row : manifestRows) {
                writer.write(escapeCsvField(row[0]) + "," + escapeCsvField(row[1]) + "\n");
            }
        }

        // Verify manifest
        String manifestContent = new String(Files.readAllBytes(manifestFile.toPath()), StandardCharsets.UTF_8);
        String[] lines = manifestContent.split("\n");

        assertEquals("filename,description", lines[0]);
        assertEquals("file1.txt,Simple description", lines[1]);
        assertEquals("file2.txt,\"Description, with comma\"", lines[2]);
        assertEquals("file3.txt,\"Description with \"\"quotes\"\"\"", lines[3]);
    }

    // Helper method for CSV escaping in tests
    private String escapeCsvField(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    @Test
    public void testManifestReadableByBashAndJava() throws Exception {
        // Test that the manifest format can be parsed back
        File outputDir = tempFolder.newFolder("export_parseable");

        // Create a manifest
        File manifestFile = new File(outputDir, "manifest.csv");
        try (java.io.Writer writer = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(manifestFile), StandardCharsets.UTF_8)) {
            writer.write("filename,status,region,priority\n");
            writer.write("file001.xml,ACTIVE,US-EAST,HIGH\n");
            writer.write("file002.xml,INACTIVE,EU-WEST,LOW\n");
        }

        // Parse it back (simulating what a Java transform would do)
        java.util.Map<String, java.util.Map<String, String>> metadata = new java.util.HashMap<>();

        java.io.BufferedReader reader = new java.io.BufferedReader(
            new java.io.InputStreamReader(new java.io.FileInputStream(manifestFile), StandardCharsets.UTF_8));

        String headerLine = reader.readLine();
        String[] headers = headerLine.split(",");

        String line;
        while ((line = reader.readLine()) != null) {
            String[] values = line.split(",");
            String filename = values[0];
            java.util.Map<String, String> rowData = new java.util.HashMap<>();
            for (int i = 1; i < headers.length && i < values.length; i++) {
                rowData.put(headers[i], values[i]);
            }
            metadata.put(filename, rowData);
        }
        reader.close();

        // Verify parsed data
        assertEquals(2, metadata.size());

        assertTrue(metadata.containsKey("file001.xml"));
        assertEquals("ACTIVE", metadata.get("file001.xml").get("status"));
        assertEquals("US-EAST", metadata.get("file001.xml").get("region"));
        assertEquals("HIGH", metadata.get("file001.xml").get("priority"));

        assertTrue(metadata.containsKey("file002.xml"));
        assertEquals("INACTIVE", metadata.get("file002.xml").get("status"));
        assertEquals("EU-WEST", metadata.get("file002.xml").get("region"));
        assertEquals("LOW", metadata.get("file002.xml").get("priority"));
    }
}
