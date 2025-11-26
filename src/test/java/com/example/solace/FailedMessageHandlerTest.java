package com.example.solace;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for FailedMessageHandler.
 */
public class FailedMessageHandlerTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testSaveFailedMessage() throws Exception {
        File failedDir = tempFolder.newFolder("failed");
        FailedMessageHandler handler = new FailedMessageHandler(failedDir, null);

        assertTrue(handler.isSaveEnabled());
        assertFalse(handler.isRetryEnabled());

        handler.saveFailedMessage("Test message content", "corr-123", "test-queue", "Connection failed", 1);

        assertEquals(1, handler.getSavedCount());
        assertEquals(1, handler.getFailedMessageIds().size());
        assertEquals("corr-123", handler.getFailedMessageIds().get(0));

        // Verify files were created
        File[] msgFiles = failedDir.listFiles((dir, name) -> name.endsWith(".msg"));
        File[] metaFiles = failedDir.listFiles((dir, name) -> name.endsWith(".meta"));

        assertNotNull(msgFiles);
        assertNotNull(metaFiles);
        assertEquals(1, msgFiles.length);
        assertEquals(1, metaFiles.length);

        // Verify content
        String savedContent = new String(Files.readAllBytes(msgFiles[0].toPath()), StandardCharsets.UTF_8);
        assertEquals("Test message content", savedContent);

        // Verify metadata contains expected fields
        String metaContent = new String(Files.readAllBytes(metaFiles[0].toPath()), StandardCharsets.UTF_8);
        assertTrue(metaContent.contains("\"queue\": \"test-queue\""));
        assertTrue(metaContent.contains("\"correlationId\": \"corr-123\""));
        assertTrue(metaContent.contains("\"error\": \"Connection failed\""));
    }

    @Test
    public void testSaveMultipleFailedMessages() throws Exception {
        File failedDir = tempFolder.newFolder("failed");
        FailedMessageHandler handler = new FailedMessageHandler(failedDir, null);

        handler.saveFailedMessage("Message 1", "corr-1", "queue-1", "Error 1", 1);
        handler.saveFailedMessage("Message 2", "corr-2", "queue-2", "Error 2", 2);
        handler.saveFailedMessage("Message 3", null, "queue-3", "Error 3", 3);

        assertEquals(3, handler.getSavedCount());
        assertEquals(3, handler.getFailedMessageIds().size());

        File[] msgFiles = failedDir.listFiles((dir, name) -> name.endsWith(".msg"));
        assertEquals(3, msgFiles.length);
    }

    @Test
    public void testLoadRetryMessages() throws Exception {
        File retryDir = tempFolder.newFolder("retry");

        // Create test message files manually
        File msg1 = new File(retryDir, "20250101_120000_000_corr-1_1.msg");
        File meta1 = new File(retryDir, "20250101_120000_000_corr-1_1.meta");

        Files.write(msg1.toPath(), "Retry message content".getBytes(StandardCharsets.UTF_8));
        Files.write(meta1.toPath(), ("{\n" +
            "  \"timestamp\": \"2025-01-01T12:00:00Z\",\n" +
            "  \"queue\": \"original-queue\",\n" +
            "  \"correlationId\": \"corr-1\",\n" +
            "  \"index\": 1,\n" +
            "  \"error\": \"Original error\"\n" +
            "}").getBytes(StandardCharsets.UTF_8));

        FailedMessageHandler handler = new FailedMessageHandler(null, retryDir);

        assertFalse(handler.isSaveEnabled());
        assertTrue(handler.isRetryEnabled());

        List<FailedMessageHandler.FailedMessage> messages = handler.loadRetryMessages();

        assertEquals(1, messages.size());
        FailedMessageHandler.FailedMessage msg = messages.get(0);
        assertEquals("Retry message content", msg.getContent());
        assertEquals("corr-1", msg.getCorrelationId());
        assertEquals("original-queue", msg.getOriginalQueue());
        assertEquals(msg1, msg.getSourceFile());
    }

    @Test
    public void testMarkRetrySuccess() throws Exception {
        File retryDir = tempFolder.newFolder("retry");

        // Create test message files
        File msg1 = new File(retryDir, "test_msg.msg");
        File meta1 = new File(retryDir, "test_msg.meta");

        Files.write(msg1.toPath(), "Test content".getBytes(StandardCharsets.UTF_8));
        Files.write(meta1.toPath(), "{\"queue\": \"test\"}".getBytes(StandardCharsets.UTF_8));

        assertTrue(msg1.exists());
        assertTrue(meta1.exists());

        FailedMessageHandler handler = new FailedMessageHandler(null, retryDir);
        List<FailedMessageHandler.FailedMessage> messages = handler.loadRetryMessages();

        assertEquals(1, messages.size());

        // Mark as success - should delete files
        handler.markRetrySuccess(messages.get(0));

        assertFalse(msg1.exists());
        assertFalse(meta1.exists());
    }

    @Test
    public void testInitFailedDirCreatesDirectory() throws Exception {
        File failedDir = new File(tempFolder.getRoot(), "new-failed-dir");
        assertFalse(failedDir.exists());

        FailedMessageHandler handler = new FailedMessageHandler(failedDir, null);
        assertTrue(handler.initFailedDir());

        assertTrue(failedDir.exists());
        assertTrue(failedDir.isDirectory());
    }

    @Test
    public void testInitFailedDirExistingDirectory() throws Exception {
        File failedDir = tempFolder.newFolder("existing");
        assertTrue(failedDir.exists());

        FailedMessageHandler handler = new FailedMessageHandler(failedDir, null);
        assertTrue(handler.initFailedDir());
    }

    @Test
    public void testValidateRetryDirNonExistent() throws Exception {
        File retryDir = new File(tempFolder.getRoot(), "does-not-exist");
        assertFalse(retryDir.exists());

        FailedMessageHandler handler = new FailedMessageHandler(null, retryDir);
        assertFalse(handler.validateRetryDir());
    }

    @Test
    public void testValidateRetryDirExists() throws Exception {
        File retryDir = tempFolder.newFolder("retry");

        FailedMessageHandler handler = new FailedMessageHandler(null, retryDir);
        assertTrue(handler.validateRetryDir());
    }

    @Test
    public void testNullDirectoriesDisabled() throws Exception {
        FailedMessageHandler handler = new FailedMessageHandler(null, null);

        assertFalse(handler.isSaveEnabled());
        assertFalse(handler.isRetryEnabled());
        assertTrue(handler.initFailedDir()); // Should succeed (no-op)
        assertTrue(handler.validateRetryDir()); // Should succeed (no-op)

        // Saving should not throw when disabled
        handler.saveFailedMessage("Content", "corr", "queue", "error", 1);
        assertEquals(0, handler.getSavedCount());

        // Loading should return empty list when disabled
        List<FailedMessageHandler.FailedMessage> messages = handler.loadRetryMessages();
        assertTrue(messages.isEmpty());
    }

    @Test
    public void testSpecialCharactersInCorrelationId() throws Exception {
        File failedDir = tempFolder.newFolder("failed");
        FailedMessageHandler handler = new FailedMessageHandler(failedDir, null);

        // Correlation ID with special characters that need sanitization
        handler.saveFailedMessage("Content", "corr/id:with*special?chars", "queue", "error", 1);

        assertEquals(1, handler.getSavedCount());

        // File should be created with sanitized name
        File[] msgFiles = failedDir.listFiles((dir, name) -> name.endsWith(".msg"));
        assertEquals(1, msgFiles.length);

        // Verify the content is still correct
        String savedContent = new String(Files.readAllBytes(msgFiles[0].toPath()), StandardCharsets.UTF_8);
        assertEquals("Content", savedContent);
    }

    @Test
    public void testLoadRetryMessagesWithoutMetadata() throws Exception {
        File retryDir = tempFolder.newFolder("retry");

        // Create message file without metadata
        File msg1 = new File(retryDir, "orphan_message.msg");
        Files.write(msg1.toPath(), "Orphan content".getBytes(StandardCharsets.UTF_8));

        FailedMessageHandler handler = new FailedMessageHandler(null, retryDir);
        List<FailedMessageHandler.FailedMessage> messages = handler.loadRetryMessages();

        assertEquals(1, messages.size());
        assertEquals("Orphan content", messages.get(0).getContent());
        assertNull(messages.get(0).getCorrelationId());
        assertNull(messages.get(0).getOriginalQueue());
    }

    @Test
    public void testSaveMessageWithNullCorrelationId() throws Exception {
        File failedDir = tempFolder.newFolder("failed");
        FailedMessageHandler handler = new FailedMessageHandler(failedDir, null);

        handler.saveFailedMessage("Content", null, "queue", "error", 42);

        assertEquals(1, handler.getSavedCount());
        // Should use index as message ID when correlation is null
        assertEquals("42", handler.getFailedMessageIds().get(0));
    }

    @Test
    public void testMessageContentWithSpecialCharacters() throws Exception {
        File failedDir = tempFolder.newFolder("failed");
        FailedMessageHandler handler = new FailedMessageHandler(failedDir, null);

        String specialContent = "Line 1\nLine 2\tTabbed\r\nWindows line\n\"Quoted\" and 'single'";
        handler.saveFailedMessage(specialContent, "corr", "queue", "error", 1);

        File[] msgFiles = failedDir.listFiles((dir, name) -> name.endsWith(".msg"));
        String savedContent = new String(Files.readAllBytes(msgFiles[0].toPath()), StandardCharsets.UTF_8);
        assertEquals(specialContent, savedContent);
    }

    @Test
    public void testEmptyRetryDirectory() throws Exception {
        File retryDir = tempFolder.newFolder("empty-retry");

        FailedMessageHandler handler = new FailedMessageHandler(null, retryDir);
        List<FailedMessageHandler.FailedMessage> messages = handler.loadRetryMessages();

        assertTrue(messages.isEmpty());
    }
}
