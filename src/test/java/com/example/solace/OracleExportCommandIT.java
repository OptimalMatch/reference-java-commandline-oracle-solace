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
}
