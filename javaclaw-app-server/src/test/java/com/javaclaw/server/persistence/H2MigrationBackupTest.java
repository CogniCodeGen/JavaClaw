package com.javaclaw.server.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;

import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.Restore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H2MigrationBackupTest {
    @TempDir
    Path temporary;

    @Test
    void backsUpBeforeDdlAndRestoresTheExactPreviousSchemaAndUserContent() throws Exception {
        JdbcDataSource source = previousSchema();
        try (var upgraded = new H2Database(source)) {
            assertEquals(
                    9, upgraded.query(connection -> countMigrations(connection)).intValue());
            assertTrue(upgraded.query(connection -> hasConversationWindows(connection))
                    .booleanValue());
            assertFalse(upgraded.query(connection -> hasLegacyInstructions(connection))
                    .booleanValue());
        }
        Path directory = temporary.resolve("migration-backups");
        Path archive;
        try (var files = Files.list(directory)) {
            var archives =
                    files.filter(path -> path.toString().endsWith(".zip")).toList();
            assertEquals(1, archives.size());
            archive = archives.getFirst();
        }
        assertTrue(archive.getFileName().toString().startsWith("v8-to-v9-"));
        String digest =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive)));
        assertEquals(
                digest + "  " + archive.getFileName() + "\n",
                Files.readString(archive.resolveSibling(archive.getFileName() + ".sha256")));
        var posix = Files.getFileAttributeView(archive, PosixFileAttributeView.class);
        if (posix != null) {
            assertEquals(
                    PosixFilePermissions.fromString("rw-------"),
                    posix.readAttributes().permissions());
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory));
        }

        // 只在测试创建的独立目录执行恢复，不让 H2Database 自动升级这个恢复副本。
        Path restored = temporary.resolve("restore-check");
        Restore.execute(archive.toString(), restored.toString(), "restored");
        try (Connection connection = dataSource(restored.resolve("restored")).getConnection()) {
            assertEquals(8, countMigrations(connection));
            assertTrue(hasMaintenanceTable(connection));
            assertTrue(hasLegacyInstructions(connection));
            assertFalse(hasConversationWindows(connection));
            try (var query = connection.createStatement();
                    var row =
                            query.executeQuery("SELECT value_json FROM server_configuration WHERE config_key='test'")) {
                assertTrue(row.next());
                assertEquals("\"用户数据保留\"", row.getString(1));
            }
            try (var query = connection.createStatement();
                    var row = query.executeQuery(
                            "SELECT content FROM project_instruction_revisions WHERE instruction_id='legacy'")) {
                assertTrue(row.next());
                assertEquals("迁移前旧规则", row.getString(1));
            }
        }
        // 相同版本重启不不断制造新备份；旧备份也不会被自动回收。
        try (var reopened = new H2Database(source)) {
            assertEquals(
                    9, reopened.query(connection -> countMigrations(connection)).intValue());
        }
        try (var files = Files.list(directory)) {
            assertEquals(
                    1L, files.filter(path -> path.toString().endsWith(".zip")).count());
        }
    }

    @Test
    void refusesMigrationBeforeAnyDdlWhenTheBackupDirectoryIsUnusable() throws Exception {
        JdbcDataSource source = previousSchema();
        Path blocked = temporary.resolve("migration-backups");
        Files.writeString(blocked, "user-owned-file");
        assertThrows(IllegalStateException.class, () -> new H2Database(source));
        assertEquals("user-owned-file", Files.readString(blocked));
        assertOldSchemaUntouched(source);
    }

    @Test
    void preflightsAllHistoryBeforeBackingUpOrApplyingNewMigrations() throws Exception {
        JdbcDataSource source = previousSchema();
        try (Connection connection = source.getConnection();
                var update = connection.createStatement()) {
            update.executeUpdate("UPDATE schema_migrations SET checksum=REPEAT('0',64) WHERE version=8");
        }
        assertThrows(IllegalStateException.class, () -> new H2Database(source));
        assertFalse(Files.exists(temporary.resolve("migration-backups")));
        assertOldSchemaUntouched(source);
    }

    @Test
    void rejectsGapsInHistoryInsteadOfApplyingDdlOutOfOrder() throws Exception {
        JdbcDataSource source = previousSchema();
        try (Connection connection = source.getConnection();
                var update = connection.createStatement()) {
            update.executeUpdate("DELETE FROM schema_migrations WHERE version=6");
        }
        assertThrows(IllegalStateException.class, () -> new H2Database(source));
        assertFalse(Files.exists(temporary.resolve("migration-backups")));
        try (Connection connection = source.getConnection()) {
            assertEquals(7, countMigrations(connection));
            assertTrue(hasMaintenanceTable(connection));
            assertTrue(hasLegacyInstructions(connection));
            assertFalse(hasConversationWindows(connection));
        }
    }

    @Test
    void doesNotCreatePointlessBackupsForANewDatabase() throws Exception {
        try (var database = new H2Database(dataSource(temporary.resolve("new")))) {
            assertEquals(
                    9, database.query(connection -> countMigrations(connection)).intValue());
        }
        assertFalse(Files.exists(temporary.resolve("migration-backups")));
    }

    private JdbcDataSource previousSchema() throws Exception {
        JdbcDataSource source = dataSource(temporary.resolve("test-v4"));
        try (var database = new H2Database(source)) {
            database.transaction(connection -> {
                try (var update = connection.createStatement()) {
                    update.executeUpdate("INSERT INTO server_configuration VALUES ('test', '\"用户数据保留\"', 1)");
                    // 夹具回到 V008，恢复 V007 旧表后验证 V009 的删表操作只能发生在自动备份之后。
                    update.execute("DROP TABLE conversation_windows");
                    update.execute("""
                            CREATE TABLE project_instructions(
                                instruction_id VARCHAR(80) PRIMARY KEY,
                                workspace_id VARCHAR(80) NOT NULL,
                                relative_directory VARCHAR(2000) NOT NULL,
                                current_revision BIGINT NOT NULL,
                                enabled BOOLEAN NOT NULL,
                                updated_at BIGINT NOT NULL,
                                CONSTRAINT fk_instruction_workspace FOREIGN KEY(workspace_id)
                                    REFERENCES workspaces(workspace_id) ON DELETE CASCADE)
                            """);
                    update.execute("""
                            CREATE TABLE project_instruction_revisions(
                                instruction_id VARCHAR(80) NOT NULL,
                                revision BIGINT NOT NULL,
                                content CLOB NOT NULL,
                                sha256 CHAR(64) NOT NULL,
                                source_attachment_sha256 CHAR(64),
                                confirmed_at BIGINT NOT NULL,
                                PRIMARY KEY(instruction_id, revision),
                                CONSTRAINT fk_instruction_revision FOREIGN KEY(instruction_id)
                                    REFERENCES project_instructions(instruction_id) ON DELETE CASCADE)
                            """);
                    update.executeUpdate("INSERT INTO workspaces VALUES "
                            + "('legacy-workspace','Legacy','"
                            + temporary.toAbsolutePath().toString().replace("'", "''")
                            + "',1,FALSE,'','legacy-workspace',1,1)");
                    update.executeUpdate(
                            "INSERT INTO project_instructions VALUES " + "('legacy','legacy-workspace','',1,TRUE,1)");
                    update.executeUpdate("INSERT INTO project_instruction_revisions VALUES "
                            + "('legacy',1,'迁移前旧规则',REPEAT('0',64),NULL,1)");
                    update.executeUpdate("DELETE FROM schema_migrations WHERE version=9");
                }
                return null;
            });
        }
        return source;
    }

    private static void assertOldSchemaUntouched(JdbcDataSource source) throws SQLException {
        try (Connection connection = source.getConnection()) {
            assertEquals(8, countMigrations(connection));
            assertTrue(hasMaintenanceTable(connection));
            assertTrue(hasLegacyInstructions(connection));
            assertFalse(hasConversationWindows(connection));
        }
    }

    private static JdbcDataSource dataSource(Path base) {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:file:" + base.toAbsolutePath() + ";DB_CLOSE_ON_EXIT=FALSE");
        source.setUser("sa");
        source.setPassword("");
        return source;
    }

    private static int countMigrations(Connection connection) throws SQLException {
        try (var query = connection.createStatement();
                var row = query.executeQuery("SELECT COUNT(*) FROM schema_migrations")) {
            row.next();
            return row.getInt(1);
        }
    }

    private static boolean hasMaintenanceTable(Connection connection) throws SQLException {
        try (var tables = connection.getMetaData().getTables(null, "PUBLIC", "KNOWLEDGE_MAINTENANCE_JOBS", null)) {
            return tables.next();
        }
    }

    private static boolean hasLegacyInstructions(Connection connection) throws SQLException {
        try (var tables = connection.getMetaData().getTables(null, "PUBLIC", "PROJECT_INSTRUCTIONS", null)) {
            return tables.next();
        }
    }

    private static boolean hasConversationWindows(Connection connection) throws SQLException {
        try (var tables = connection.getMetaData().getTables(null, "PUBLIC", "CONVERSATION_WINDOWS", null)) {
            return tables.next();
        }
    }
}
