package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingSystemMigrationTest {
    @TempDir
    Path temporary;

    @Test
    void 部分系统DDL可按原checksum恢复并保持旧历史() throws Exception {
        H2Database database = new H2Database(temporary.resolve("data-v6"));
        database.initialize();
        try (var connection = database.open();
                var statement = connection.createStatement()) {
            statement.execute("DELETE FROM CORE.SCHEMA_HISTORY WHERE VERSION>=10");
            statement.execute("DROP TABLE CORE.TURN_SYSTEM_ENVIRONMENT");
            try (var pending = connection.prepareStatement("INSERT INTO CORE.SCHEMA_MIGRATION_PENDING VALUES (10,?)")) {
                pending.setString(1, checksum());
                pending.executeUpdate();
            }
        }
        database.initialize();
        try (var connection = database.open();
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*),MAX(VERSION) FROM CORE.SCHEMA_HISTORY")) {
            assertTrue(rows.next());
            assertEquals(H2Database.CORE_SCHEMA_VERSION, rows.getInt(1));
            assertEquals(H2Database.CORE_SCHEMA_VERSION, rows.getInt(2));
            new CodingSystemSchemaValidation().validate(connection);
        }
    }

    @Test
    void 系统快照缺少摘要列时拒绝完成迁移() throws Exception {
        H2Database database = new H2Database(temporary.resolve("partial/data-v6"));
        database.initialize();
        try (var connection = database.open();
                var statement = connection.createStatement()) {
            statement.execute("DELETE FROM CORE.SCHEMA_HISTORY WHERE VERSION>=10");
            statement.execute("ALTER TABLE CORE.TURN_SYSTEM_ENVIRONMENT DROP COLUMN CATALOG_DIGEST");
        }
        assertThrows(PersistenceException.class, database::initialize);
        try (var connection = database.open();
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT MAX(VERSION) FROM CORE.SCHEMA_HISTORY")) {
            assertTrue(rows.next());
            assertEquals(9, rows.getInt(1));
        }
    }

    private String checksum() throws Exception {
        try (var input = getClass().getResourceAsStream("/db/core/V010__v6_system_commands.sql")) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
        }
    }
}
