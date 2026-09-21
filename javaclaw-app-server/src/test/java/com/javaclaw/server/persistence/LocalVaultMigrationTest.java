package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V011 只安装空表；密钥内容由后端独立保存，Schema 机制不导入任何旧凭据。 */
class LocalVaultMigrationTest {
    @TempDir
    Path temporary;

    @Test
    void 新数据库只创建空的本地主密钥表() throws Exception {
        H2Database database = database();
        try (var connection = database.open()) {
            new LocalVaultSchemaValidation().validate(connection);
            assertEquals(0, count(connection, "CORE.VAULT_LOCAL_MASTER_KEY"));
            assertEquals(0, count(connection, "CORE.SCHEMA_MIGRATION_PENDING"));
            assertEquals(H2Database.CORE_SCHEMA_VERSION, count(connection, "CORE.SCHEMA_HISTORY"));
        }
    }

    @Test
    void 建表与再次启动保持既有摘要和本地主密钥() throws Exception {
        H2Database database = database();
        Map<Integer, String> installed = checksums(database);
        try (var connection = database.open();
                var statement = connection.createStatement()) {
            statement.execute("DELETE FROM CORE.SCHEMA_HISTORY WHERE VERSION>=11");
            statement.execute("DROP TABLE CORE.VAULT_LOCAL_MASTER_KEY");
        }
        database.initialize();
        assertEquals(installed, checksums(database));
        try (var connection = database.open()) {
            assertEquals(0, count(connection, "CORE.VAULT_LOCAL_MASTER_KEY"));
            writeFixture(connection);
        }
        new H2Database(database.dataRoot()).initialize();
        assertEquals(installed, checksums(database));
        try (var connection = database.open()) {
            assertKeyFixture(connection);
        }
    }

    @Test
    void DDL完成但历史未提交时按相同pending摘要恢复且保留表数据() throws Exception {
        H2Database database = database();
        Map<Integer, String> installed = checksums(database);
        try (var connection = database.open();
                var statement = connection.createStatement()) {
            writeFixture(connection);
            statement.execute("DELETE FROM CORE.SCHEMA_HISTORY WHERE VERSION>=11");
            try (var pending = connection.prepareStatement("INSERT INTO CORE.SCHEMA_MIGRATION_PENDING VALUES (11,?)")) {
                pending.setString(1, checksum());
                pending.executeUpdate();
            }
        }
        database.initialize();
        new H2Database(database.dataRoot()).initialize();
        assertEquals(installed, checksums(database));
        try (var connection = database.open()) {
            assertEquals(0, count(connection, "CORE.SCHEMA_MIGRATION_PENDING"));
            assertKeyFixture(connection);
        }
    }

    @Test
    void 缺少任一关键列时保留pending且不能宣称完成迁移() throws Exception {
        assertMissingColumn("KEY_ID", "VARCHAR(160)");
        assertMissingColumn("KEY_BYTES", "VARBINARY(32)");
    }

    private void assertMissingColumn(String retainedColumn, String type) throws Exception {
        H2Database database = database("missing-" + retainedColumn);
        try (var connection = database.open();
                var statement = connection.createStatement()) {
            statement.execute("DELETE FROM CORE.SCHEMA_HISTORY WHERE VERSION>=11");
            statement.execute("DROP TABLE CORE.VAULT_LOCAL_MASTER_KEY");
            statement.execute("CREATE TABLE CORE.VAULT_LOCAL_MASTER_KEY (" + retainedColumn + " " + type + ")");
        }
        assertThrows(PersistenceException.class, database::initialize);
        assertThrows(PersistenceException.class, database::initialize);
        assertEquals(10, checksums(database).size());
        try (var connection = database.open();
                var statement = connection.createStatement();
                var rows =
                        statement.executeQuery("SELECT CHECKSUM FROM CORE.SCHEMA_MIGRATION_PENDING WHERE VERSION=11")) {
            assertTrue(rows.next());
            assertEquals(checksum(), rows.getString(1));
            assertFalse(rows.next());
        }
    }

    @Test
    void 未完成V11摘要不匹配时拒绝补齐DDL或覆盖证据() throws Exception {
        H2Database database = database();
        try (var connection = database.open();
                var statement = connection.createStatement()) {
            statement.execute("DELETE FROM CORE.SCHEMA_HISTORY WHERE VERSION>=11");
            statement.execute("DROP TABLE CORE.VAULT_LOCAL_MASTER_KEY");
            statement.execute("INSERT INTO CORE.SCHEMA_MIGRATION_PENDING VALUES (11,REPEAT('f',64))");
        }
        assertThrows(PersistenceException.class, database::initialize);
        assertEquals(10, checksums(database).size());
        try (var connection = database.open();
                var statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.executeQuery("SELECT * FROM CORE.VAULT_LOCAL_MASTER_KEY"));
            try (var rows =
                    statement.executeQuery("SELECT CHECKSUM FROM CORE.SCHEMA_MIGRATION_PENDING WHERE VERSION=11")) {
                assertTrue(rows.next());
                assertEquals("f".repeat(64), rows.getString(1));
            }
        }
    }

    @Test
    void 已安装V11摘要变化时拒绝启动且保留原历史和密钥() throws Exception {
        H2Database database = database();
        try (var connection = database.open();
                var statement = connection.createStatement()) {
            writeFixture(connection);
            statement.execute("UPDATE CORE.SCHEMA_HISTORY SET CHECKSUM=REPEAT('a',64) WHERE VERSION=11");
        }
        assertThrows(PersistenceException.class, database::initialize);
        assertEquals("a".repeat(64), checksums(database).get(11));
        try (var connection = database.open()) {
            assertKeyFixture(connection);
        }
    }

    @Test
    void 数据库约束拒绝非32字节密钥和重复标识() throws Exception {
        H2Database database = database();
        try (var connection = database.open()) {
            try (var insert =
                    connection.prepareStatement("INSERT INTO CORE.VAULT_LOCAL_MASTER_KEY VALUES ('invalid',?)")) {
                for (byte[] invalid : new byte[][] {null, new byte[31], new byte[33]}) {
                    insert.setBytes(1, invalid);
                    assertThrows(SQLException.class, insert::executeUpdate);
                }
            }
            assertEquals(0, count(connection, "CORE.VAULT_LOCAL_MASTER_KEY"));
            writeFixture(connection);
            assertThrows(SQLException.class, () -> writeFixture(connection));
            assertEquals(1, count(connection, "CORE.VAULT_LOCAL_MASTER_KEY"));
        }
    }

    private H2Database database() {
        return database("default");
    }

    private H2Database database(String scenario) {
        H2Database database = new H2Database(temporary.resolve(scenario).resolve("data-v6"));
        database.initialize();
        return database;
    }

    private void writeFixture(Connection connection) throws SQLException {
        try (var insert = connection.prepareStatement("INSERT INTO CORE.VAULT_LOCAL_MASTER_KEY VALUES ('fixture',?)")) {
            // 全零字节仅用于持久性夹具，不生成或读取任何真实用户密钥。
            insert.setBytes(1, new byte[32]);
            insert.executeUpdate();
        }
    }

    private void assertKeyFixture(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT KEY_ID,KEY_BYTES FROM CORE.VAULT_LOCAL_MASTER_KEY")) {
            assertTrue(rows.next());
            assertEquals("fixture", rows.getString(1));
            assertArrayEquals(new byte[32], rows.getBytes(2));
            assertFalse(rows.next());
        }
    }

    private int count(Connection connection, String table) throws SQLException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private Map<Integer, String> checksums(H2Database database) throws SQLException {
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

    private String checksum() throws Exception {
        try (var input = getClass().getResourceAsStream("/db/core/V011__v6_local_vault.sql")) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
        }
    }
}
