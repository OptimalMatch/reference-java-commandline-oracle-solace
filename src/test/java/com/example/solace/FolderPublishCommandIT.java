package com.example.solace;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;

import static org.junit.Assert.*;

/**
 * Integration tests for FolderPublishCommand.
 * These tests verify the file reading, filtering, and sorting logic
 * using actual file system operations in dry-run mode.
 */
public class FolderPublishCommandIT {

    private File tempDir;
    private ByteArrayOutputStream outContent;
    private ByteArrayOutputStream errContent;
    private PrintStream originalOut;
    private PrintStream originalErr;

    @Before
    public void setUp() throws IOException {
        tempDir = createTempDirectory();

        // Capture console output
        originalOut = System.out;
        originalErr = System.err;
        outContent = new ByteArrayOutputStream();
        errContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));
    }

    @After
    public void tearDown() {
        // Restore console output
        System.setOut(originalOut);
        System.setErr(originalErr);

        deleteDirectory(tempDir);
    }

    private File createTempDirectory() throws IOException {
        File temp = File.createTempFile("integration-test", "dir");
        temp.delete();
        temp.mkdir();
        return temp;
    }

    private void deleteDirectory(File dir) {
        if (dir != null && dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            dir.delete();
        }
    }

    private File createFile(File dir, String name, String content) throws IOException {
        File file = new File(dir, name);
        FileWriter writer = new FileWriter(file);
        writer.write(content);
        writer.close();
        return file;
    }

    @Test
    public void testFullCommandLineExecution() throws Exception {
        createFile(tempDir, "message1.xml", "<order><id>1</id></order>");
        createFile(tempDir, "message2.xml", "<order><id>2</id></order>");

        String[] args = {
            "folder-publish",
            tempDir.getAbsolutePath(),
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "user",
            "-p", "pass",
            "-q", "test-queue",
            "--pattern", "*.xml",
            "--dry-run"
        };

        int exitCode = new CommandLine(new SolaceCli()).execute(args);

        String output = outContent.toString();
        assertEquals(0, exitCode);
        assertTrue(output.contains("Found 2 file(s)"));
        assertTrue(output.contains("DRY RUN MODE"));
    }

    @Test
    public void testRecursiveDirectoryProcessing() throws Exception {
        // Create nested directory structure
        File subDir1 = new File(tempDir, "subdir1");
        File subDir2 = new File(tempDir, "subdir2");
        File deepDir = new File(subDir1, "deep");
        subDir1.mkdir();
        subDir2.mkdir();
        deepDir.mkdir();

        createFile(tempDir, "root.json", "{\"level\": \"root\"}");
        createFile(subDir1, "level1.json", "{\"level\": \"1\"}");
        createFile(subDir2, "level1b.json", "{\"level\": \"1b\"}");
        createFile(deepDir, "level2.json", "{\"level\": \"2\"}");

        String[] args = {
            "folder-publish",
            tempDir.getAbsolutePath(),
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "user",
            "-q", "test-queue",
            "--pattern", "*.json",
            "--recursive",
            "--dry-run"
        };

        int exitCode = new CommandLine(new SolaceCli()).execute(args);

        String output = outContent.toString();
        assertEquals(0, exitCode);
        assertTrue(output.contains("Found 4 file(s)"));
    }

    @Test
    public void testFilePatternMatching() throws Exception {
        createFile(tempDir, "order1.xml", "<order>1</order>");
        createFile(tempDir, "order2.xml", "<order>2</order>");
        createFile(tempDir, "invoice1.json", "{\"invoice\": 1}");
        createFile(tempDir, "readme.txt", "readme content");
        createFile(tempDir, "notes.md", "# Notes");

        // Test XML pattern
        FolderPublishCommand cmd = createCommand("*.xml");
        cmd.call();
        String output = outContent.toString();
        assertTrue(output.contains("Found 2 file(s)"));

        // Reset output
        outContent.reset();

        // Test JSON pattern
        cmd = createCommand("*.json");
        cmd.call();
        output = outContent.toString();
        assertTrue(output.contains("Found 1 file(s)"));

        // Reset output
        outContent.reset();

        // Test wildcard pattern
        cmd = createCommand("order*");
        cmd.call();
        output = outContent.toString();
        assertTrue(output.contains("Found 2 file(s)"));
    }

    @Test
    public void testSortByName() throws Exception {
        createFile(tempDir, "charlie.txt", "c");
        createFile(tempDir, "alpha.txt", "a");
        createFile(tempDir, "bravo.txt", "b");

        FolderPublishCommand cmd = createCommand("*.txt");
        cmd.sortBy = "NAME";
        cmd.call();

        String output = outContent.toString();
        int alphaPos = output.indexOf("alpha.txt");
        int bravoPos = output.indexOf("bravo.txt");
        int charliePos = output.indexOf("charlie.txt");

        assertTrue(alphaPos < bravoPos);
        assertTrue(bravoPos < charliePos);
    }

    @Test
    public void testSortBySize() throws Exception {
        createFile(tempDir, "small.txt", "a");
        createFile(tempDir, "medium.txt", "abcdefghij");
        createFile(tempDir, "large.txt", "abcdefghijklmnopqrstuvwxyz");

        FolderPublishCommand cmd = createCommand("*.txt");
        cmd.sortBy = "SIZE";
        cmd.call();

        String output = outContent.toString();
        int smallPos = output.indexOf("small.txt");
        int mediumPos = output.indexOf("medium.txt");
        int largePos = output.indexOf("large.txt");

        assertTrue(smallPos < mediumPos);
        assertTrue(mediumPos < largePos);
    }

    @Test
    public void testSortByDate() throws Exception {
        File first = createFile(tempDir, "first.txt", "first");
        Thread.sleep(100);
        File second = createFile(tempDir, "second.txt", "second");
        Thread.sleep(100);
        File third = createFile(tempDir, "third.txt", "third");

        // Ensure modification times are different
        first.setLastModified(System.currentTimeMillis() - 2000);
        second.setLastModified(System.currentTimeMillis() - 1000);
        third.setLastModified(System.currentTimeMillis());

        FolderPublishCommand cmd = createCommand("*.txt");
        cmd.sortBy = "DATE";
        cmd.call();

        String output = outContent.toString();
        int firstPos = output.indexOf("first.txt");
        int secondPos = output.indexOf("second.txt");
        int thirdPos = output.indexOf("third.txt");

        assertTrue(firstPos < secondPos);
        assertTrue(secondPos < thirdPos);
    }

    @Test
    public void testLargeFileContent() throws Exception {
        // Create a file with large content
        StringBuilder largeContent = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            largeContent.append("Line ").append(i).append(": This is test content\n");
        }
        createFile(tempDir, "large.txt", largeContent.toString());

        FolderPublishCommand cmd = createCommand("*.txt");
        cmd.call();

        String output = outContent.toString();
        assertTrue(output.contains("Found 1 file(s)"));
        assertTrue(output.contains("bytes"));
    }

    @Test
    public void testMixedFileTypes() throws Exception {
        createFile(tempDir, "data.xml", "<data/>");
        createFile(tempDir, "data.json", "{}");
        createFile(tempDir, "data.txt", "text");
        createFile(tempDir, "data.csv", "a,b,c");

        // All files
        FolderPublishCommand cmd = createCommand("*");
        cmd.call();
        String output = outContent.toString();
        assertTrue(output.contains("Found 4 file(s)"));

        outContent.reset();

        // Only data.* pattern
        cmd = createCommand("data.*");
        cmd.call();
        output = outContent.toString();
        assertTrue(output.contains("Found 4 file(s)"));
    }

    @Test
    public void testEmptyDirectory() throws Exception {
        FolderPublishCommand cmd = createCommand("*");
        cmd.call();

        String output = outContent.toString();
        assertTrue(output.contains("No files found"));
    }

    @Test
    public void testSpecialCharactersInFilename() throws Exception {
        createFile(tempDir, "file-with-dash.txt", "content1");
        createFile(tempDir, "file_with_underscore.txt", "content2");
        createFile(tempDir, "file.with.dots.txt", "content3");

        FolderPublishCommand cmd = createCommand("*.txt");
        cmd.call();

        String output = outContent.toString();
        assertTrue(output.contains("Found 3 file(s)"));
    }

    @Test
    public void testFilenameAsCorrelationId() throws Exception {
        createFile(tempDir, "order-12345.xml", "<order/>");
        createFile(tempDir, "order-67890.xml", "<order/>");

        FolderPublishCommand cmd = createCommand("*.xml");
        cmd.useFilenameAsCorrelation = true;
        cmd.call();

        // In dry-run mode, we just verify the command completes successfully
        String output = outContent.toString();
        assertTrue(output.contains("Found 2 file(s)"));
    }

    @Test
    public void testHelpOutput() {
        String[] args = {"folder-publish", "--help"};

        int exitCode = new CommandLine(new SolaceCli()).execute(args);

        String output = outContent.toString();
        assertEquals(0, exitCode);
        assertTrue(output.contains("folder-publish"));
        assertTrue(output.contains("--pattern"));
        assertTrue(output.contains("--recursive"));
        assertTrue(output.contains("--dry-run"));
    }

    @Test
    public void testUsingAlias() throws Exception {
        createFile(tempDir, "test.txt", "test content");

        String[] args = {
            "folder-pub",  // Using alias
            tempDir.getAbsolutePath(),
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "user",
            "-q", "test-queue",
            "--dry-run"
        };

        int exitCode = new CommandLine(new SolaceCli()).execute(args);
        assertEquals(0, exitCode);
    }

    @Test
    public void testNoMatchingPattern() throws Exception {
        createFile(tempDir, "data.xml", "<data/>");
        createFile(tempDir, "data.json", "{}");

        FolderPublishCommand cmd = createCommand("*.csv");
        cmd.call();

        String output = outContent.toString();
        assertTrue(output.contains("No files found"));
    }

    private FolderPublishCommand createCommand(String pattern) {
        FolderPublishCommand cmd = new FolderPublishCommand();
        cmd.folderPath = tempDir.getAbsolutePath();
        cmd.filePattern = pattern;
        cmd.dryRun = true;
        cmd.sortBy = "NAME";
        cmd.recursive = false;
        cmd.connection = new ConnectionOptions();
        cmd.connection.host = "tcp://localhost:55555";
        cmd.connection.vpn = "default";
        cmd.connection.username = "user";
        cmd.connection.password = "pass";
        cmd.connection.queue = "test-queue";
        return cmd;
    }
}
