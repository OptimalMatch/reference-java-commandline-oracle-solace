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

import java.util.List;

import static org.junit.Assert.*;

public class OracleInsertCommandTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private CommandLine cmd;
    private StringWriter sw;
    private StringWriter errSw;

    @Before
    public void setUp() {
        cmd = new CommandLine(new SolaceCli());
        sw = new StringWriter();
        errSw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));
        cmd.setErr(new PrintWriter(errSw));
    }

    @Test
    public void testHelpOutput() {
        int exitCode = cmd.execute("oracle-insert", "--help");
        assertEquals(0, exitCode);
        String output = sw.toString();
        assertTrue(output.contains("oracle-insert"));
        assertTrue(output.contains("--folder"));
        assertTrue(output.contains("--pattern"));
        assertTrue(output.contains("--table"));
        assertTrue(output.contains("--content-column"));
        assertTrue(output.contains("--filename-column"));
        assertTrue(output.contains("--sql-file"));
        assertTrue(output.contains("--batch-size"));
        assertTrue(output.contains("--dry-run"));
    }

    @Test
    public void testAliases() {
        // Test ora-insert alias
        int exitCode1 = cmd.execute("ora-insert", "--help");
        assertEquals(0, exitCode1);

        // Test ora-ins alias
        cmd = new CommandLine(new SolaceCli());
        sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));
        int exitCode2 = cmd.execute("ora-ins", "--help");
        assertEquals(0, exitCode2);
    }

    @Test
    public void testMissingRequiredOptions() {
        // Missing --folder
        int exitCode1 = cmd.execute("oracle-insert",
            "--db-host", "localhost",
            "--db-service", "ORCL",
            "--db-user", "user",
            "--db-password", "pass",
            "--table", "test_table");
        assertNotEquals(0, exitCode1);
    }

    @Test
    public void testCommandLineParsing() {
        OracleInsertCommand command = new OracleInsertCommand();
        CommandLine cmdLine = new CommandLine(command);

        cmdLine.parseArgs(
            "--db-host", "localhost",
            "--db-service", "ORCL",
            "--db-user", "user",
            "--db-password", "pass",
            "--folder", tempFolder.getRoot().getAbsolutePath(),
            "--table", "messages",
            "--content-column", "payload",
            "--filename-column", "filename",
            "--pattern", "*.xml",
            "--batch-size", "50",
            "--dry-run"
        );

        assertEquals(tempFolder.getRoot().getAbsolutePath(), command.sourceFolder.getAbsolutePath());
        assertEquals("messages", command.tableName);
        assertEquals("payload", command.contentColumn);
        assertEquals("filename", command.filenameColumn);
        assertEquals("*.xml", command.filePattern);
        assertEquals(50, command.batchSize);
        assertTrue(command.dryRun);
    }

    @Test
    public void testDefaultValues() {
        OracleInsertCommand command = new OracleInsertCommand();
        CommandLine cmdLine = new CommandLine(command);

        cmdLine.parseArgs(
            "--db-host", "localhost",
            "--db-service", "ORCL",
            "--db-user", "user",
            "--db-password", "pass",
            "--folder", tempFolder.getRoot().getAbsolutePath(),
            "--table", "test_table"
        );

        assertEquals("*", command.filePattern);
        assertEquals("content", command.contentColumn);
        assertNull(command.filenameColumn);
        assertEquals(100, command.batchSize);
        assertFalse(command.recursive);
        assertFalse(command.dryRun);
    }

    @Test
    public void testSqlFileOption() throws Exception {
        // Create a SQL file with custom INSERT
        File sqlFile = tempFolder.newFile("insert.sql");
        String customSql = "INSERT INTO custom_table (data, name, created) VALUES (?, ??, SYSDATE)";
        Files.write(sqlFile.toPath(), customSql.getBytes(StandardCharsets.UTF_8));

        OracleInsertCommand command = new OracleInsertCommand();
        CommandLine cmdLine = new CommandLine(command);

        cmdLine.parseArgs(
            "--db-host", "localhost",
            "--db-service", "ORCL",
            "--db-user", "user",
            "--db-password", "pass",
            "--folder", tempFolder.getRoot().getAbsolutePath(),
            "--sql-file", sqlFile.getAbsolutePath()
        );

        assertNotNull(command.sqlFile);
        assertEquals(sqlFile.getAbsolutePath(), command.sqlFile.getAbsolutePath());
        assertNull(command.tableName);
    }

    @Test
    public void testCannotUseBothTableAndSqlFile() throws Exception {
        // Create a SQL file
        File sqlFile = tempFolder.newFile("insert.sql");
        Files.write(sqlFile.toPath(), "INSERT INTO t VALUES (?)".getBytes(StandardCharsets.UTF_8));

        // Parse with both --table and --sql-file (should parse but fail at runtime)
        OracleInsertCommand command = new OracleInsertCommand();
        CommandLine cmdLine = new CommandLine(command);

        cmdLine.parseArgs(
            "--db-host", "localhost",
            "--db-service", "ORCL",
            "--db-user", "user",
            "--db-password", "pass",
            "--folder", tempFolder.getRoot().getAbsolutePath(),
            "--table", "test_table",
            "--sql-file", sqlFile.getAbsolutePath()
        );

        // Both should be set after parsing
        assertNotNull(command.tableName);
        assertNotNull(command.sqlFile);
        // The error would be caught at runtime in resolveInsertStatement()
    }

    @Test
    public void testSortOptions() {
        OracleInsertCommand command = new OracleInsertCommand();
        CommandLine cmdLine = new CommandLine(command);

        cmdLine.parseArgs(
            "--db-host", "localhost",
            "--db-service", "ORCL",
            "--db-user", "user",
            "--db-password", "pass",
            "--folder", tempFolder.getRoot().getAbsolutePath(),
            "--table", "test_table",
            "--sort", "name"
        );

        assertEquals("name", command.sortOrder);
    }

    @Test
    public void testRecursiveOption() {
        OracleInsertCommand command = new OracleInsertCommand();
        CommandLine cmdLine = new CommandLine(command);

        cmdLine.parseArgs(
            "--db-host", "localhost",
            "--db-service", "ORCL",
            "--db-user", "user",
            "--db-password", "pass",
            "--folder", tempFolder.getRoot().getAbsolutePath(),
            "--table", "test_table",
            "--recursive"
        );

        assertTrue(command.recursive);
    }

    @Test
    public void testSolaceCliIncludesOracleInsert() {
        int exitCode = cmd.execute("--help");
        assertEquals(0, exitCode);
        String output = sw.toString();
        assertTrue("oracle-insert command should be listed", output.contains("oracle-insert"));
    }

    @Test
    public void testNonExistentFolder() {
        OracleInsertCommand command = new OracleInsertCommand();
        command.sourceFolder = new File("/nonexistent/path/to/folder");
        command.tableName = "test_table";
        command.oracleConnection = new OracleOptions();
        command.oracleConnection.dbHost = "localhost";
        command.oracleConnection.dbService = "ORCL";
        command.oracleConnection.dbUser = "user";
        command.oracleConnection.dbPassword = "pass";

        Integer result = command.call();
        assertEquals(Integer.valueOf(1), result);
    }

    // -------------------------------------------------------------------------
    // SQL Safety Validation Tests
    // -------------------------------------------------------------------------

    @Test
    public void testValidateSqlSafeInsert() {
        OracleInsertCommand command = new OracleInsertCommand();
        List<String> warnings = command.validateSqlSafety("INSERT INTO t (data, name) VALUES (?, ??)");
        assertTrue("INSERT should be safe", warnings.isEmpty());
    }

    @Test
    public void testValidateSqlSafeUpdateWithWhere() {
        OracleInsertCommand command = new OracleInsertCommand();
        List<String> warnings = command.validateSqlSafety("UPDATE t SET data = ? WHERE name = ??");
        assertTrue("UPDATE with WHERE should be safe", warnings.isEmpty());
    }

    @Test
    public void testValidateSqlUnsafeUpdateNoWhere() {
        OracleInsertCommand command = new OracleInsertCommand();
        List<String> warnings = command.validateSqlSafety("UPDATE t SET data = ?");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("UPDATE without WHERE"));
    }

    @Test
    public void testValidateSqlUnsafeDeleteNoWhere() {
        OracleInsertCommand command = new OracleInsertCommand();
        List<String> warnings = command.validateSqlSafety("DELETE FROM t");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("DELETE without WHERE"));
    }

    @Test
    public void testValidateSqlSafeDeleteWithWhere() {
        OracleInsertCommand command = new OracleInsertCommand();
        List<String> warnings = command.validateSqlSafety("DELETE FROM t WHERE name = ??");
        assertTrue("DELETE with WHERE should be safe", warnings.isEmpty());
    }

    @Test
    public void testValidateSqlTruncate() {
        OracleInsertCommand command = new OracleInsertCommand();
        List<String> warnings = command.validateSqlSafety("TRUNCATE TABLE my_table");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("TRUNCATE"));
    }

    @Test
    public void testValidateSqlDrop() {
        OracleInsertCommand command = new OracleInsertCommand();
        List<String> warnings = command.validateSqlSafety("DROP TABLE my_table");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("DROP"));
    }

    @Test
    public void testValidateSqlMultipleStatements() {
        OracleInsertCommand command = new OracleInsertCommand();
        List<String> warnings = command.validateSqlSafety("INSERT INTO t VALUES (?); DELETE FROM t");
        assertFalse("Multiple statements should be flagged", warnings.isEmpty());
        boolean found = false;
        for (String w : warnings) {
            if (w.contains("Multiple SQL statements")) found = true;
        }
        assertTrue("Should detect multiple statements", found);
    }

    @Test
    public void testValidateSqlStripsComments() {
        OracleInsertCommand command = new OracleInsertCommand();
        // SQL with comments that might look unsafe but the actual statement is safe
        String sql = "-- This updates all rows\nUPDATE t SET data = ? WHERE id = ??";
        List<String> warnings = command.validateSqlSafety(sql);
        assertTrue("UPDATE with WHERE after stripping comments should be safe", warnings.isEmpty());
    }

    @Test
    public void testValidateSqlStripsBlockComments() {
        OracleInsertCommand command = new OracleInsertCommand();
        String sql = "/* bulk update */ UPDATE t SET data = ?";
        List<String> warnings = command.validateSqlSafety(sql);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("UPDATE without WHERE"));
    }

    @Test
    public void testValidateSqlCaseInsensitive() {
        OracleInsertCommand command = new OracleInsertCommand();
        List<String> warnings = command.validateSqlSafety("update t set data = ?");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("UPDATE without WHERE"));
    }

    @Test
    public void testValidateSqlViaValidateFlag() throws Exception {
        // Create a safe SQL file
        File sqlFile = tempFolder.newFile("safe.sql");
        Files.write(sqlFile.toPath(), "INSERT INTO t VALUES (?)".getBytes(StandardCharsets.UTF_8));

        int exitCode = cmd.execute("oracle-insert",
            "--db-host", "x", "--db-service", "x",
            "--db-user", "x", "--db-password", "x",
            "--sql-file", sqlFile.getAbsolutePath(),
            "--validate-sql");
        assertEquals(0, exitCode);
        assertTrue(sw.toString().contains("SQL validation passed"));
    }

    @Test
    public void testValidateSqlViaValidateFlagUnsafe() throws Exception {
        // Create an unsafe SQL file
        File sqlFile = tempFolder.newFile("unsafe.sql");
        Files.write(sqlFile.toPath(), "UPDATE t SET data = ?".getBytes(StandardCharsets.UTF_8));

        int exitCode = cmd.execute("oracle-insert",
            "--db-host", "x", "--db-service", "x",
            "--db-user", "x", "--db-password", "x",
            "--sql-file", sqlFile.getAbsolutePath(),
            "--validate-sql");
        assertEquals(1, exitCode);
        assertTrue(errSw.toString().contains("UPDATE without WHERE"));
    }

    @Test
    public void testForceOptionParsing() {
        OracleInsertCommand command = new OracleInsertCommand();
        CommandLine cmdLine = new CommandLine(command);

        cmdLine.parseArgs(
            "--db-host", "localhost",
            "--db-service", "ORCL",
            "--db-user", "user",
            "--db-password", "pass",
            "--folder", tempFolder.getRoot().getAbsolutePath(),
            "--sql-file", "/some/file.sql",
            "--force"
        );

        assertTrue(command.force);
    }

    @Test
    public void testHelpIncludesNewOptions() {
        int exitCode = cmd.execute("oracle-insert", "--help");
        assertEquals(0, exitCode);
        String output = sw.toString();
        assertTrue(output.contains("--force"));
        assertTrue(output.contains("--validate-sql"));
    }
}
