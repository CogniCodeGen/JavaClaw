package com.javaclaw.server.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 生产入口只接受全新库或当前本地 Vault 库；拒绝旧库时不能先打开可写连接。 */
class LocalVaultDatabaseInitializationTest {
    @TempDir
    Path temporary;

    @Test
    void 全新本地数据库可创建且重启保留密钥和历史() throws Exception {
        H2Database database = new H2Database(temporary.resolve("data-v6"));
        database.initializeLocalVault();
        try (var connection = database.open()) {
            insertKey(connection);
        }
        H2Database restarted = new H2Database(database.dataRoot());
        restarted.initializeLocalVault();
        try (var connection = readOnly(restarted)) {
            assertEquals(
                    H2Database.CORE_SCHEMA_VERSION, scalar(connection, "SELECT COUNT(*) FROM CORE.SCHEMA_HISTORY"));
            assertEquals(
                    0,
                    scalar(
                            connection,
                            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES"
                                    + " WHERE TABLE_SCHEMA='CORE' AND TABLE_NAME='VAULT_LOCAL_STORAGE'"));
            assertKey(connection);
        }
    }

    @Test
    void 拒绝旧版数据库且文件字节历史和业务行保持不变() throws Exception {
        H2Database database = new H2Database(temporary.resolve("data-v6"));
        database.initialize();
        try (var connection = database.open();
                var statement = connection.createStatement()) {
            statement.execute("DELETE FROM CORE.SCHEMA_HISTORY WHERE VERSION>=11");
            statement.execute("DROP TABLE CORE.VAULT_LOCAL_MASTER_KEY");
            statement.execute("INSERT INTO CORE.COMMAND_RESULT"
                    + " (IDEMPOTENCY_KEY,METHOD_NAME,REQUEST_DIGEST,RESPONSE_PAYLOAD,CREATED_AT)"
                    + " VALUES ('old-command','test',REPEAT('a',64),'retained',CURRENT_TIMESTAMP)");
        }
        assertRejectedWithoutWrites(database);
        try (var connection = readOnly(database);
                var statement = connection.createStatement();
                var rows = statement.executeQuery(
                        "SELECT RESPONSE_PAYLOAD FROM CORE.COMMAND_RESULT" + " WHERE IDEMPOTENCY_KEY='old-command'")) {
            assertTrue(rows.next());
            assertEquals("retained", rows.getString(1));
            assertEquals(10, scalar(connection, "SELECT MAX(VERSION) FROM CORE.SCHEMA_HISTORY"));
            assertEquals(
                    0,
                    scalar(
                            connection,
                            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES"
                                    + " WHERE TABLE_SCHEMA='CORE' AND TABLE_NAME='VAULT_LOCAL_MASTER_KEY'"));
        }
    }

    @Test
    void 缺失历史版本不连续或未来版本都不能进入可写初始化() throws Exception {
        assertInvalidHistory("missing-table", "DROP TABLE CORE.SCHEMA_HISTORY");
        assertInvalidHistory("missing-version", "DELETE FROM CORE.SCHEMA_HISTORY");
        assertInvalidHistory("gap", "DELETE FROM CORE.SCHEMA_HISTORY WHERE VERSION=5");
        assertInvalidHistory("future", "UPDATE CORE.SCHEMA_HISTORY SET VERSION=12 WHERE VERSION=11");
    }

    @Test
    void 版本完整但本地主密钥表缺列时拒绝修改数据库() throws Exception {
        H2Database database = new H2Database(temporary.resolve("data-v6"));
        database.initialize();
        try (var connection = database.open();
                var statement = connection.createStatement()) {
            statement.execute("DROP TABLE CORE.VAULT_LOCAL_MASTER_KEY");
            statement.execute("CREATE TABLE CORE.VAULT_LOCAL_MASTER_KEY (KEY_ID VARCHAR(160) PRIMARY KEY)");
        }
        assertRejectedWithoutWrites(database);
    }

    @Test
    void 仅存辅助材料时不能隐式创建主数据库() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("data-v6"));
        Path evidence = root.resolve("javaclaw.trace.db");
        Files.writeString(evidence, "existing database evidence");
        byte[] before = Files.readAllBytes(evidence);
        var failure = assertThrows(PersistenceException.class, () -> new H2Database(root).initializeLocalVault());
        assertTrue(failure.getMessage().contains("新的空 data-v6 目录"));
        assertArrayEquals(before, Files.readAllBytes(evidence));
        assertFalse(Files.exists(root.resolve("javaclaw.mv.db")));
        assertEquals(List.of("javaclaw.trace.db"), filenames(root));
    }

    @Test
    void 当前版本仍由既有初始化器拒绝被更改的历史摘要() throws Exception {
        H2Database database = new H2Database(temporary.resolve("data-v6"));
        database.initializeLocalVault();
        try (var connection = database.open();
                var statement = connection.createStatement()) {
            statement.execute("UPDATE CORE.SCHEMA_HISTORY SET CHECKSUM=REPEAT('f',64) WHERE VERSION=11");
        }
        var failure = assertThrows(PersistenceException.class, database::initializeLocalVault);
        assertTrue(failure.getMessage().contains("checksum"));
        try (var connection = readOnly(database);
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT CHECKSUM FROM CORE.SCHEMA_HISTORY WHERE VERSION=11")) {
            assertTrue(rows.next());
            assertEquals("f".repeat(64), rows.getString(1));
        }
    }

    private void assertInvalidHistory(String scenario, String sql) throws Exception {
        H2Database database = new H2Database(temporary.resolve(scenario).resolve("data-v6"));
        database.initialize();
        try (var connection = database.open();
                var statement = connection.createStatement()) {
            insertKey(connection);
            statement.execute(sql);
        }
        assertRejectedWithoutWrites(database);
        try (var connection = readOnly(database)) {
            assertKey(connection);
        }
    }

    private void assertRejectedWithoutWrites(H2Database database) throws Exception {
        Path file = database.dataRoot().resolve("javaclaw.mv.db");
        byte[] before = Files.readAllBytes(file);
        List<String> filesBefore = filenames(database.dataRoot());
        var failure = assertThrows(PersistenceException.class, database::initializeLocalVault);
        assertTrue(failure.getMessage().contains("新的空 data-v6 目录"));
        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals(filesBefore, filenames(database.dataRoot()));
    }

    private Connection readOnly(H2Database database) throws SQLException {
        return DriverManager.getConnection(
                "jdbc:h2:file:" + database.dataRoot().resolve("javaclaw")
                        + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r;DB_CLOSE_ON_EXIT=FALSE;TRACE_LEVEL_FILE=0",
                "sa",
                "");
    }

    private List<String> filenames(Path root) throws Exception {
        try (var files = Files.list(root)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private int scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private void insertKey(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO CORE.VAULT_LOCAL_MASTER_KEY VALUES ('test',?)")) {
            statement.setBytes(1, new byte[32]);
            statement.executeUpdate();
        }
    }

    private void assertKey(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(
                        "SELECT KEY_BYTES FROM CORE.VAULT_LOCAL_MASTER_KEY WHERE KEY_ID='test'")) {
            assertTrue(rows.next());
            assertArrayEquals(new byte[32], rows.getBytes(1));
            assertFalse(rows.next());
        }
    }
}
