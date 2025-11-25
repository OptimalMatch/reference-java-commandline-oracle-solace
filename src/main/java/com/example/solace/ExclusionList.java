package com.example.solace;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for managing exclusion lists.
 *
 * Supports:
 * - Exact string matching
 * - Wildcard patterns (* and ?)
 * - Regex patterns (lines starting with "regex:")
 * - Comments (lines starting with #)
 * - Empty lines are ignored
 */
public class ExclusionList {

    private final Set<String> exactMatches = new HashSet<String>();
    private final List<Pattern> regexPatterns = new ArrayList<Pattern>();
    private final List<String> wildcardPatterns = new ArrayList<String>();

    /**
     * Load exclusion patterns from a file.
     * Each line can be:
     * - An exact string to match
     * - A wildcard pattern using * (any chars) and ? (single char)
     * - A regex pattern prefixed with "regex:"
     * - A comment line starting with #
     * - Empty lines are ignored
     */
    public static ExclusionList fromFile(File file) throws Exception {
        ExclusionList list = new ExclusionList();

        if (file == null || !file.exists()) {
            return list;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();

                // Skip empty lines and comments
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                // Check for regex pattern
                if (line.toLowerCase().startsWith("regex:")) {
                    String pattern = line.substring(6).trim();
                    list.regexPatterns.add(Pattern.compile(pattern));
                }
                // Check for wildcard pattern
                else if (line.contains("*") || line.contains("?")) {
                    list.wildcardPatterns.add(line);
                }
                // Exact match
                else {
                    list.exactMatches.add(line);
                }
            }
        }

        return list;
    }

    /**
     * Check if a value should be excluded.
     */
    public boolean isExcluded(String value) {
        if (value == null) {
            return false;
        }

        // Check exact match
        if (exactMatches.contains(value)) {
            return true;
        }

        // Check wildcard patterns
        for (String pattern : wildcardPatterns) {
            if (matchesWildcard(value, pattern)) {
                return true;
            }
        }

        // Check regex patterns
        for (Pattern pattern : regexPatterns) {
            if (pattern.matcher(value).matches()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check if content contains any excluded pattern.
     */
    public boolean containsExcluded(String content) {
        if (content == null) {
            return false;
        }

        // Check if content contains any exact match
        for (String exact : exactMatches) {
            if (content.contains(exact)) {
                return true;
            }
        }

        // Check regex patterns with find() for substring match
        for (Pattern pattern : regexPatterns) {
            if (pattern.matcher(content).find()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Simple wildcard matching.
     * * matches any sequence of characters
     * ? matches any single character
     */
    private boolean matchesWildcard(String value, String pattern) {
        // Convert wildcard pattern to regex
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*':
                    regex.append(".*");
                    break;
                case '?':
                    regex.append(".");
                    break;
                case '.':
                case '\\':
                case '[':
                case ']':
                case '(':
                case ')':
                case '{':
                case '}':
                case '^':
                case '$':
                case '|':
                case '+':
                    regex.append("\\").append(c);
                    break;
                default:
                    regex.append(c);
            }
        }
        return value.matches(regex.toString());
    }

    /**
     * Get count of loaded patterns.
     */
    public int size() {
        return exactMatches.size() + wildcardPatterns.size() + regexPatterns.size();
    }

    /**
     * Check if the exclusion list is empty.
     */
    public boolean isEmpty() {
        return exactMatches.isEmpty() && wildcardPatterns.isEmpty() && regexPatterns.isEmpty();
    }

    @Override
    public String toString() {
        return "ExclusionList{exact=" + exactMatches.size() +
               ", wildcard=" + wildcardPatterns.size() +
               ", regex=" + regexPatterns.size() + "}";
    }
}
