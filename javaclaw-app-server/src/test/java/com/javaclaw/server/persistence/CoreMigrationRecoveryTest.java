package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreMigrationRecoveryTest {
    @TempDir
    Path directory;

    @Test
    void 部分DDL已提交时通过相同摘要恢复且历史版本连续() throws Exception {
        H2Database database = new H2Database(directory.resolve("data-v6"));
        database.initialize();
        try (var connection = database.open();
                var statement = connection.createStatement()) {
            statement.execute("DELETE FROM CORE.SCHEMA_HISTORY WHERE VERSION > 1");
            statement.execute("DROP TABLE CORE.TURN_COMPACTION_CALL");
            try (var pending = connection.prepareStatement("INSERT INTO CORE.SCHEMA_MIGRATION_PENDING VALUES (2, ?)")) {
                pending.setString(1, contextChecksum());
                pending.executeUpdate();
            }
        }
        database.initialize();
        try (var connection = database.open();
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*), MAX(VERSION) FROM CORE.SCHEMA_HISTORY")) {
            assertTrue(rows.next());
            assertEquals(H2Database.CORE_SCHEMA_VERSION, rows.getInt(1));
            assertEquals(H2Database.CORE_SCHEMA_VERSION, rows.getInt(2));
        }
    }

    @Test
    void 部分迁移摘要不匹配与未来数据库都拒绝启动() throws Exception {
        H2Database partial = new H2Database(directory.resolve("partial/data-v6"));
        partial.initialize();
        try (var connection = partial.open();
                var statement = connection.createStatement()) {
            statement.execute("DELETE FROM CORE.SCHEMA_HISTORY WHERE VERSION > 1");
            statement.execute("INSERT INTO CORE.SCHEMA_MIGRATION_PENDING VALUES (2, REPEAT('f', 64))");
        }
        assertThrows(PersistenceException.class, partial::initialize);
        H2Database future = new H2Database(directory.resolve("future/data-v6"));
        future.initialize();
        try (var connection = future.open();
                var statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO CORE.SCHEMA_HISTORY VALUES (5, 'future', REPEAT('a', 64), CURRENT_TIMESTAMP)");
        }
        assertThrows(PersistenceException.class, future::initialize);
    }

    private static String contextChecksum() throws Exception {
        try (var input = CoreMigrationRecoveryTest.class.getResourceAsStream("/db/core/V002__v6_context_runtime.sql")) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
        }
    }
}
