package com.example.solace;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import picocli.CommandLine;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.Assert.*;

public class FolderPublishCommandTest {

    private File tempDir;

    @Before
    public void setUp() throws IOException {
        tempDir = createTempDirectory();
    }

    @After
    public void tearDown() {
        deleteDirectory(tempDir);
    }

    private File createTempDirectory() throws IOException {
        File temp = File.createTempFile("test", "dir");
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

    private void createFile(File dir, String name, String content) throws IOException {
        File file = new File(dir, name);
        FileWriter writer = new FileWriter(file);
        writer.write(content);
        writer.close();
    }

    @Test
    public void testCommandLineParsing() {
        FolderPublishCommand cmd = new FolderPublishCommand();
        new CommandLine(cmd).parseArgs(
            tempDir.getAbsolutePath(),
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "user",
            "-p", "pass",
            "-q", "test-queue",
            "--pattern", "*.xml",
            "--recursive",
            "--use-filename-as-correlation",
            "--delivery-mode", "DIRECT",
            "--ttl", "5000",
            "--dry-run"
        );

        assertEquals(tempDir.getAbsolutePath(), cmd.folderPath);
        assertEquals("*.xml", cmd.filePattern);
        assertTrue(cmd.recursive);
        assertTrue(cmd.useFilenameAsCorrelation);
        assertEquals("DIRECT", cmd.deliveryMode);
        assertEquals(5000, cmd.ttl);
        assertTrue(cmd.dryRun);
    }

    @Test
    public void testDefaultValues() {
        FolderPublishCommand cmd = new FolderPublishCommand();
        new CommandLine(cmd).parseArgs(
            tempDir.getAbsolutePath(),
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "user",
            "-q", "test-queue"
        );

        assertEquals("*", cmd.filePattern);
        assertFalse(cmd.recursive);
        assertFalse(cmd.useFilenameAsCorrelation);
        assertEquals("PERSISTENT", cmd.deliveryMode);
        assertEquals(0, cmd.ttl);
        assertFalse(cmd.dryRun);
        assertEquals("NAME", cmd.sortBy);
    }

    @Test
    public void testDryRunWithEmptyFolder() {
        FolderPublishCommand cmd = new FolderPublishCommand();
        cmd.folderPath = tempDir.getAbsolutePath();
        cmd.filePattern = "*";
        cmd.dryRun = true;
        cmd.sortBy = "NAME";
        cmd.connection = new ConnectionOptions();
        cmd.connection.queue = "test-queue";

        Integer result = cmd.call();

        assertEquals(Integer.valueOf(0), result);
    }

    @Test
    public void testDryRunWithFiles() throws IOException {
        createFile(tempDir, "message1.txt", "Hello World 1");
        createFile(tempDir, "message2.txt", "Hello World 2");
        createFile(tempDir, "message3.txt", "Hello World 3");

        FolderPublishCommand cmd = new FolderPublishCommand();
        cmd.folderPath = tempDir.getAbsolutePath();
        cmd.filePattern = "*.txt";
        cmd.dryRun = true;
        cmd.sortBy = "NAME";
        cmd.connection = new ConnectionOptions();
        cmd.connection.queue = "test-queue";

        Integer result = cmd.call();

        assertEquals(Integer.valueOf(0), result);
    }

    @Test
    public void testFilePatternFiltering() throws IOException {
        createFile(tempDir, "message1.xml", "<msg>1</msg>");
        createFile(tempDir, "message2.xml", "<msg>2</msg>");
        createFile(tempDir, "message3.json", "{\"msg\":3}");
        createFile(tempDir, "readme.txt", "readme");

        FolderPublishCommand cmd = new FolderPublishCommand();
        cmd.folderPath = tempDir.getAbsolutePath();
        cmd.filePattern = "*.xml";
        cmd.dryRun = true;
        cmd.sortBy = "NAME";
        cmd.connection = new ConnectionOptions();
        cmd.connection.queue = "test-queue";

        Integer result = cmd.call();

        assertEquals(Integer.valueOf(0), result);
    }

    @Test
    public void testNonExistentFolder() {
        FolderPublishCommand cmd = new FolderPublishCommand();
        cmd.folderPath = "/nonexistent/folder/path";
        cmd.filePattern = "*";
        cmd.sortBy = "NAME";
        cmd.connection = new ConnectionOptions();
        cmd.connection.queue = "test-queue";

        Integer result = cmd.call();

        assertEquals(Integer.valueOf(1), result);
    }

    @Test
    public void testFileInsteadOfFolder() throws IOException {
        File file = new File(tempDir, "notafolder.txt");
        file.createNewFile();

        FolderPublishCommand cmd = new FolderPublishCommand();
        cmd.folderPath = file.getAbsolutePath();
        cmd.filePattern = "*";
        cmd.sortBy = "NAME";
        cmd.connection = new ConnectionOptions();
        cmd.connection.queue = "test-queue";

        Integer result = cmd.call();

        assertEquals(Integer.valueOf(1), result);
    }

    @Test
    public void testRecursiveFileListing() throws IOException {
        // Create subdirectory
        File subDir = new File(tempDir, "subdir");
        subDir.mkdir();

        createFile(tempDir, "root.txt", "root message");
        createFile(subDir, "sub.txt", "sub message");

        FolderPublishCommand cmd = new FolderPublishCommand();
        cmd.folderPath = tempDir.getAbsolutePath();
        cmd.filePattern = "*.txt";
        cmd.recursive = true;
        cmd.dryRun = true;
        cmd.sortBy = "NAME";
        cmd.connection = new ConnectionOptions();
        cmd.connection.queue = "test-queue";

        Integer result = cmd.call();

        assertEquals(Integer.valueOf(0), result);
    }

    @Test
    public void testGetFilenameWithoutExtension() throws Exception {
        FolderPublishCommand cmd = new FolderPublishCommand();

        Method method = FolderPublishCommand.class.getDeclaredMethod("getFilenameWithoutExtension", File.class);
        method.setAccessible(true);

        assertEquals("message", method.invoke(cmd, new File("message.txt")));
        assertEquals("my.file", method.invoke(cmd, new File("my.file.xml")));
        assertEquals("noextension", method.invoke(cmd, new File("noextension")));
        assertEquals(".hidden", method.invoke(cmd, new File(".hidden")));
    }

    @Test
    public void testSortOptions() {
        FolderPublishCommand cmd = new FolderPublishCommand();

        new CommandLine(cmd).parseArgs(
            tempDir.getAbsolutePath(),
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "user",
            "-q", "test-queue",
            "--sort", "DATE"
        );
        assertEquals("DATE", cmd.sortBy);

        cmd = new FolderPublishCommand();
        new CommandLine(cmd).parseArgs(
            tempDir.getAbsolutePath(),
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "user",
            "-q", "test-queue",
            "--sort", "SIZE"
        );
        assertEquals("SIZE", cmd.sortBy);
    }

    @Test
    public void testAliases() {
        CommandLine.Command annotation = FolderPublishCommand.class.getAnnotation(CommandLine.Command.class);
        String[] aliases = annotation.aliases();

        assertTrue(Arrays.asList(aliases).contains("folder-pub"));
        assertTrue(Arrays.asList(aliases).contains("dir-pub"));
    }

    @Test
    public void testSecondQueueOption() {
        FolderPublishCommand cmd = new FolderPublishCommand();
        new CommandLine(cmd).parseArgs(
            tempDir.getAbsolutePath(),
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "user",
            "-q", "primary-queue",
            "--second-queue", "secondary-queue"
        );

        assertEquals("primary-queue", cmd.connection.queue);
        assertEquals("secondary-queue", cmd.secondQueue);
    }

    @Test
    public void testSecondQueueShortOption() {
        FolderPublishCommand cmd = new FolderPublishCommand();
        new CommandLine(cmd).parseArgs(
            tempDir.getAbsolutePath(),
            "-H", "tcp://localhost:55555",
            "-v", "default",
            "-u", "user",
            "-q", "primary-queue",
            "-Q", "backup-queue"
        );

        assertEquals("primary-queue", cmd.connection.queue);
        assertEquals("backup-queue", cmd.secondQueue);
    }
}
