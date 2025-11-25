package com.example.solace;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

/**
 * Unit tests for ExclusionList.
 */
public class ExclusionListTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testExactMatch() throws Exception {
        File excludeFile = tempFolder.newFile("exclude.txt");
        String content = "exact-match-1\nexact-match-2\n";
        Files.write(excludeFile.toPath(), content.getBytes(StandardCharsets.UTF_8));

        ExclusionList list = ExclusionList.fromFile(excludeFile);

        assertTrue(list.isExcluded("exact-match-1"));
        assertTrue(list.isExcluded("exact-match-2"));
        assertFalse(list.isExcluded("exact-match-3"));
        assertFalse(list.isExcluded("exact-match")); // Partial won't match
    }

    @Test
    public void testWildcardPattern() throws Exception {
        File excludeFile = tempFolder.newFile("exclude.txt");
        String content = "test-*.xml\norder_???.json\n";
        Files.write(excludeFile.toPath(), content.getBytes(StandardCharsets.UTF_8));

        ExclusionList list = ExclusionList.fromFile(excludeFile);

        assertTrue(list.isExcluded("test-001.xml"));
        assertTrue(list.isExcluded("test-abc.xml"));
        assertTrue(list.isExcluded("test-.xml")); // * matches zero chars too
        assertFalse(list.isExcluded("test-001.json"));

        assertTrue(list.isExcluded("order_001.json"));
        assertTrue(list.isExcluded("order_abc.json"));
        assertFalse(list.isExcluded("order_1234.json")); // Too many chars for ???
        assertFalse(list.isExcluded("order_12.json")); // Too few chars for ???
    }

    @Test
    public void testRegexPattern() throws Exception {
        File excludeFile = tempFolder.newFile("exclude.txt");
        String content = "regex:^test-\\d{3}\\.xml$\nregex:order_[A-Z]+\\.json\n";
        Files.write(excludeFile.toPath(), content.getBytes(StandardCharsets.UTF_8));

        ExclusionList list = ExclusionList.fromFile(excludeFile);

        assertTrue(list.isExcluded("test-001.xml"));
        assertTrue(list.isExcluded("test-999.xml"));
        assertFalse(list.isExcluded("test-abc.xml")); // Not digits
        assertFalse(list.isExcluded("test-1234.xml")); // Too many digits

        assertTrue(list.isExcluded("order_ABC.json"));
        assertTrue(list.isExcluded("order_X.json"));
        assertFalse(list.isExcluded("order_abc.json")); // Lowercase
        assertFalse(list.isExcluded("order_123.json")); // Digits
    }

    @Test
    public void testCommentsAndEmptyLines() throws Exception {
        File excludeFile = tempFolder.newFile("exclude.txt");
        String content = "# This is a comment\n\nexact-value\n# Another comment\npattern-*\n\n";
        Files.write(excludeFile.toPath(), content.getBytes(StandardCharsets.UTF_8));

        ExclusionList list = ExclusionList.fromFile(excludeFile);

        assertEquals(2, list.size());
        assertTrue(list.isExcluded("exact-value"));
        assertTrue(list.isExcluded("pattern-test"));
        assertFalse(list.isExcluded("# This is a comment"));
    }

    @Test
    public void testContainsExcluded() throws Exception {
        File excludeFile = tempFolder.newFile("exclude.txt");
        String content = "secret-key\nregex:password=\\w+\n";
        Files.write(excludeFile.toPath(), content.getBytes(StandardCharsets.UTF_8));

        ExclusionList list = ExclusionList.fromFile(excludeFile);

        assertTrue(list.containsExcluded("This contains secret-key in the middle"));
        assertTrue(list.containsExcluded("config: password=abc123"));
        assertFalse(list.containsExcluded("This has no exclusions"));
    }

    @Test
    public void testEmptyList() throws Exception {
        File excludeFile = tempFolder.newFile("exclude.txt");
        String content = "# Only comments\n\n# Nothing else\n";
        Files.write(excludeFile.toPath(), content.getBytes(StandardCharsets.UTF_8));

        ExclusionList list = ExclusionList.fromFile(excludeFile);

        assertTrue(list.isEmpty());
        assertEquals(0, list.size());
        assertFalse(list.isExcluded("anything"));
        assertFalse(list.containsExcluded("anything"));
    }

    @Test
    public void testNullFile() throws Exception {
        ExclusionList list = ExclusionList.fromFile(null);

        assertTrue(list.isEmpty());
        assertFalse(list.isExcluded("anything"));
    }

    @Test
    public void testNonExistentFile() throws Exception {
        File nonExistent = new File(tempFolder.getRoot(), "does-not-exist.txt");
        ExclusionList list = ExclusionList.fromFile(nonExistent);

        assertTrue(list.isEmpty());
    }

    @Test
    public void testNullValueChecks() throws Exception {
        File excludeFile = tempFolder.newFile("exclude.txt");
        String content = "test-value\n";
        Files.write(excludeFile.toPath(), content.getBytes(StandardCharsets.UTF_8));

        ExclusionList list = ExclusionList.fromFile(excludeFile);

        assertFalse(list.isExcluded(null));
        assertFalse(list.containsExcluded(null));
    }

    @Test
    public void testMixedPatterns() throws Exception {
        File excludeFile = tempFolder.newFile("exclude.txt");
        String content = "exact-value\nwildcard-*\nregex:^pattern-\\d+$\n";
        Files.write(excludeFile.toPath(), content.getBytes(StandardCharsets.UTF_8));

        ExclusionList list = ExclusionList.fromFile(excludeFile);

        assertEquals(3, list.size());

        // Exact
        assertTrue(list.isExcluded("exact-value"));
        assertFalse(list.isExcluded("exact-values"));

        // Wildcard
        assertTrue(list.isExcluded("wildcard-test"));
        assertTrue(list.isExcluded("wildcard-"));
        assertFalse(list.isExcluded("wildcard"));

        // Regex
        assertTrue(list.isExcluded("pattern-123"));
        assertFalse(list.isExcluded("pattern-abc"));
    }

    @Test
    public void testSpecialRegexCharsInWildcard() throws Exception {
        File excludeFile = tempFolder.newFile("exclude.txt");
        // These contain regex special chars that should be escaped in wildcard mode
        String content = "file.txt\npath[1].json\n(test).xml\n";
        Files.write(excludeFile.toPath(), content.getBytes(StandardCharsets.UTF_8));

        ExclusionList list = ExclusionList.fromFile(excludeFile);

        assertTrue(list.isExcluded("file.txt"));
        assertTrue(list.isExcluded("path[1].json"));
        assertTrue(list.isExcluded("(test).xml"));
        assertFalse(list.isExcluded("fileXtxt")); // . should not match any char
    }

    @Test
    public void testToString() throws Exception {
        File excludeFile = tempFolder.newFile("exclude.txt");
        String content = "exact1\nexact2\nwild-*\nregex:test\n";
        Files.write(excludeFile.toPath(), content.getBytes(StandardCharsets.UTF_8));

        ExclusionList list = ExclusionList.fromFile(excludeFile);

        String str = list.toString();
        assertTrue(str.contains("exact=2"));
        assertTrue(str.contains("wildcard=1"));
        assertTrue(str.contains("regex=1"));
    }
}
