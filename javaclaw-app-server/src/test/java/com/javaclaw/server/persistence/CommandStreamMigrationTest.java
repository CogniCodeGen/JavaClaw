package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V004 只前向添加流式表，保留已经安装的前三版摘要并支持中断 DDL 重入。 */
class CommandStreamMigrationTest {
    @TempDir
    Path directory;

    @Test
    void 从已安装V3前向升级不重写旧摘要且部分新表可安全补齐() throws Exception {
        var database = new H2Database(directory.resolve("data-v6"));
        database.initialize();
        Map<Integer, String> before = checksums(database);
        try (var connection = database.open();
                var statement = connection.createStatement()) {
            statement.execute("DELETE FROM CORE.SCHEMA_HISTORY WHERE VERSION>=4");
            statement.execute("DROP TABLE CORE.CODING_COMMAND_CHUNK");
        }
        database.initialize();
        assertEquals(before, checksums(database));
        try (var connection = database.open();
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM CORE.CODING_COMMAND_CHUNK")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }
        database.initialize();
        assertEquals(before, checksums(database));
    }

    @Test
    void 未完成V4摘要不匹配时拒绝重写或宣称迁移成功() throws Exception {
        var database = new H2Database(directory.resolve("data-v6"));
        database.initialize();
        try (var connection = database.open();
                var statement = connection.createStatement()) {
            statement.execute("DELETE FROM CORE.SCHEMA_HISTORY WHERE VERSION>=4");
            statement.execute("INSERT INTO CORE.SCHEMA_MIGRATION_PENDING VALUES(4,REPEAT('f',64))");
        }
        assertThrows(PersistenceException.class, database::initialize);
        assertEquals(3, checksums(database).size());
    }

    private static Map<Integer, String> checksums(H2Database database) throws Exception {
        Map<Integer, String> result = new LinkedHashMap<>();
        try (var connection = database.open();
                var statement = connection.createStatement();
                var rows =
                        statement.executeQuery("SELECT VERSION,CHECKSUM FROM CORE.SCHEMA_HISTORY ORDER BY VERSION")) {
            while (rows.next()) {
                result.put(rows.getInt(1), rows.getString(2));
            }
        }
        return Map.copyOf(result);
    }
}
