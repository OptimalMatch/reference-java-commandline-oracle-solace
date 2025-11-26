package com.example.solace;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for AuditLogger.
 */
public class AuditLoggerTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testBasicAuditLog() throws Exception {
        File logFile = new File(tempFolder.getRoot(), "audit.log");
        AuditLogger audit = new AuditLogger(logFile, "test-command");

        audit.addParameter("host", "localhost")
             .addParameter("port", 8080)
             .addResult("count", 10)
             .logCompletion(0);

        assertTrue(logFile.exists());

        String content = new String(Files.readAllBytes(logFile.toPath()), StandardCharsets.UTF_8);
        assertTrue(content.contains("\"command\":\"test-command\""));
        assertTrue(content.contains("\"host\":\"localhost\""));
        assertTrue(content.contains("\"port\":8080"));
        assertTrue(content.contains("\"count\":10"));
        assertTrue(content.contains("\"exitCode\":0"));
        assertTrue(content.contains("\"success\":true"));
    }

    @Test
    public void testAuditLogWithError() throws Exception {
        File logFile = new File(tempFolder.getRoot(), "audit.log");
        AuditLogger audit = new AuditLogger(logFile, "failing-command");

        audit.setError("Connection timeout")
             .logCompletion(1);

        String content = new String(Files.readAllBytes(logFile.toPath()), StandardCharsets.UTF_8);
        assertTrue(content.contains("\"error\":\"Connection timeout\""));
        assertTrue(content.contains("\"exitCode\":1"));
        assertTrue(content.contains("\"success\":false"));
    }

    @Test
    public void testAuditLogWithListResult() throws Exception {
        File logFile = new File(tempFolder.getRoot(), "audit.log");
        AuditLogger audit = new AuditLogger(logFile, "test-command");

        List<String> failedIds = Arrays.asList("id-1", "id-2", "id-3");
        audit.addResult("failedMessageIds", failedIds)
             .logCompletion(1);

        String content = new String(Files.readAllBytes(logFile.toPath()), StandardCharsets.UTF_8);
        assertTrue(content.contains("\"failedMessageIds\":[\"id-1\",\"id-2\",\"id-3\"]"));
    }

    @Test
    public void testAuditLogWithEmptyList() throws Exception {
        File logFile = new File(tempFolder.getRoot(), "audit.log");
        AuditLogger audit = new AuditLogger(logFile, "test-command");

        List<String> emptyList = Arrays.asList();
        audit.addResult("items", emptyList)
             .logCompletion(0);

        String content = new String(Files.readAllBytes(logFile.toPath()), StandardCharsets.UTF_8);
        assertTrue(content.contains("\"items\":[]"));
    }

    @Test
    public void testAuditLogWithNullValue() throws Exception {
        File logFile = new File(tempFolder.getRoot(), "audit.log");
        AuditLogger audit = new AuditLogger(logFile, "test-command");

        audit.addParameter("nullParam", null)
             .logCompletion(0);

        String content = new String(Files.readAllBytes(logFile.toPath()), StandardCharsets.UTF_8);
        assertTrue(content.contains("\"nullParam\":null"));
    }

    @Test
    public void testAuditLogWithBooleanValue() throws Exception {
        File logFile = new File(tempFolder.getRoot(), "audit.log");
        AuditLogger audit = new AuditLogger(logFile, "test-command");

        audit.addParameter("enabled", true)
             .addParameter("disabled", false)
             .logCompletion(0);

        String content = new String(Files.readAllBytes(logFile.toPath()), StandardCharsets.UTF_8);
        assertTrue(content.contains("\"enabled\":true"));
        assertTrue(content.contains("\"disabled\":false"));
    }

    @Test
    public void testAuditLogAppendsToExistingFile() throws Exception {
        File logFile = new File(tempFolder.getRoot(), "audit.log");

        // First log entry
        AuditLogger audit1 = new AuditLogger(logFile, "command-1");
        audit1.logCompletion(0);

        // Second log entry
        AuditLogger audit2 = new AuditLogger(logFile, "command-2");
        audit2.logCompletion(0);

        String content = new String(Files.readAllBytes(logFile.toPath()), StandardCharsets.UTF_8);
        String[] lines = content.trim().split("\n");
        assertEquals(2, lines.length);
        assertTrue(lines[0].contains("\"command\":\"command-1\""));
        assertTrue(lines[1].contains("\"command\":\"command-2\""));
    }

    @Test
    public void testAuditLogCreatesParentDirectory() throws Exception {
        File logFile = new File(tempFolder.getRoot(), "subdir/nested/audit.log");
        assertFalse(logFile.getParentFile().exists());

        AuditLogger audit = new AuditLogger(logFile, "test-command");
        audit.logCompletion(0);

        assertTrue(logFile.exists());
    }

    @Test
    public void testMaskSensitive() {
        assertEquals("****", AuditLogger.maskSensitive("password123"));
        assertEquals("****", AuditLogger.maskSensitive("x"));
        assertEquals("<empty>", AuditLogger.maskSensitive(""));
        assertEquals("<empty>", AuditLogger.maskSensitive(null));
    }

    @Test
    public void testNoOpAuditLogger() throws Exception {
        AuditOptions options = new AuditOptions();
        options.auditFile = null;

        AuditLogger audit = AuditLogger.create(options, "test-command");

        // Should not throw and should do nothing
        audit.addParameter("key", "value")
             .addResult("count", 10)
             .setError("error")
             .logCompletion(0);

        // No file should be created
        File[] files = tempFolder.getRoot().listFiles();
        assertEquals(0, files == null ? 0 : files.length);
    }

    @Test
    public void testCreateWithNullOptions() throws Exception {
        AuditLogger audit = AuditLogger.create(null, "test-command");

        // Should return no-op logger
        audit.addParameter("key", "value")
             .logCompletion(0);

        // No exception should be thrown
    }

    @Test
    public void testSpecialCharactersEscaped() throws Exception {
        File logFile = new File(tempFolder.getRoot(), "audit.log");
        AuditLogger audit = new AuditLogger(logFile, "test-command");

        audit.addParameter("message", "Line 1\nLine 2\tTabbed\r\n\"Quoted\"")
             .setError("Error with \"quotes\" and\nnewlines")
             .logCompletion(1);

        String content = new String(Files.readAllBytes(logFile.toPath()), StandardCharsets.UTF_8);

        // Verify JSON is valid (no unescaped special chars)
        assertTrue(content.contains("\\n"));
        assertTrue(content.contains("\\t"));
        assertTrue(content.contains("\\\""));
    }

    @Test
    public void testDurationCalculated() throws Exception {
        File logFile = new File(tempFolder.getRoot(), "audit.log");
        AuditLogger audit = new AuditLogger(logFile, "test-command");

        // Small delay to ensure duration > 0
        Thread.sleep(10);

        audit.logCompletion(0);

        String content = new String(Files.readAllBytes(logFile.toPath()), StandardCharsets.UTF_8);
        assertTrue(content.contains("\"durationMs\":"));
    }

    @Test
    public void testTimestampFormat() throws Exception {
        File logFile = new File(tempFolder.getRoot(), "audit.log");
        AuditLogger audit = new AuditLogger(logFile, "test-command");
        audit.logCompletion(0);

        String content = new String(Files.readAllBytes(logFile.toPath()), StandardCharsets.UTF_8);

        // Should contain ISO format timestamps
        assertTrue(content.contains("\"timestamp\":\""));
        assertTrue(content.contains("\"startTime\":\""));
        assertTrue(content.contains("\"endTime\":\""));
    }

    @Test
    public void testListOfNumbers() throws Exception {
        File logFile = new File(tempFolder.getRoot(), "audit.log");
        AuditLogger audit = new AuditLogger(logFile, "test-command");

        List<Integer> numbers = Arrays.asList(1, 2, 3, 4, 5);
        audit.addResult("counts", numbers)
             .logCompletion(0);

        String content = new String(Files.readAllBytes(logFile.toPath()), StandardCharsets.UTF_8);
        assertTrue(content.contains("\"counts\":[1,2,3,4,5]"));
    }
}
