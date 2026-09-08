package com.javaclaw.server.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.h2.tools.RunScript;

/** V001 之后的有序迁移；H2 DDL 可以隐式提交，因此使用持久 pending checksum 和可重入 DDL 恢复。 */
final class CoreMigrationRunner {
    private static final List<Migration> MIGRATIONS = List.of(
            new Migration(2, "context runtime", "/db/core/V002__v6_context_runtime.sql"),
            new Migration(3, "coding execution", "/db/core/V003__v6_coding_execution.sql"),
            new Migration(4, "command stream", "/db/core/V004__v6_command_stream.sql"),
            new Migration(5, "turn stream", "/db/core/V005__v6_turn_stream.sql"),
            new Migration(6, "conversation evidence", "/db/core/V006__v6_completion_evidence.sql"),
            new Migration(7, "job cancellation", "/db/core/V007__v6_job_cancellation.sql"));

    void migrate(Connection connection) throws SQLException {
        Map<Integer, String> installed = installed(connection);
        validateVersions(installed);
        preparePending(connection);
        for (Migration migration : MIGRATIONS) {
            byte[] script = read(migration.resource());
            String checksum = digest(script);
            if (installed.containsKey(migration.version())) {
                requireChecksum(installed.get(migration.version()), checksum);
            } else {
                apply(connection, migration, script, checksum);
            }
        }
    }

    private Map<Integer, String> installed(Connection connection) throws SQLException {
        Map<Integer, String> values = new LinkedHashMap<>();
        try (var statement = connection.createStatement();
                var rows =
                        statement.executeQuery("SELECT VERSION, CHECKSUM FROM CORE.SCHEMA_HISTORY ORDER BY VERSION")) {
            while (rows.next()) {
                values.put(rows.getInt(1), rows.getString(2));
            }
        }
        return values;
    }

    private void validateVersions(Map<Integer, String> installed) {
        int expected = 1;
        for (int version : installed.keySet()) {
            if (version != expected++ || version > H2Database.CORE_SCHEMA_VERSION) {
                throw new PersistenceException("data-v6 schema 版本不连续或高于当前程序支持范围");
            }
        }
    }

    private void preparePending(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS CORE.SCHEMA_MIGRATION_PENDING (
                    VERSION INT PRIMARY KEY, CHECKSUM CHAR(64) NOT NULL
                )
                """);
        }
        connection.commit();
    }

    private void apply(Connection connection, Migration migration, byte[] script, String checksum) throws SQLException {
        recordPending(connection, migration.version(), checksum);
        RunScript.execute(connection, new StringReader(new String(script, StandardCharsets.UTF_8)));
        validateContextTables(connection, migration.version());
        if (migration.version() == 3) {
            new CodingSchemaValidation().validate(connection);
        }
        if (migration.version() == 4) {
            validateCommandStreams(connection);
        }
        new ConversationSchemaValidation().validate(connection, migration.version());
        try (var statement = connection.prepareStatement("""
            INSERT INTO CORE.SCHEMA_HISTORY (VERSION, DESCRIPTION, CHECKSUM, INSTALLED_AT)
            VALUES (?, ?, ?, CURRENT_TIMESTAMP)
            """)) {
            statement.setInt(1, migration.version());
            statement.setString(2, migration.description());
            statement.setString(3, checksum);
            statement.executeUpdate();
        }
        try (var statement =
                connection.prepareStatement("DELETE FROM CORE.SCHEMA_MIGRATION_PENDING WHERE VERSION = ?")) {
            statement.setInt(1, migration.version());
            statement.executeUpdate();
        }
        connection.commit();
    }

    private void recordPending(Connection connection, int version, String checksum) throws SQLException {
        try (var statement =
                connection.prepareStatement("SELECT CHECKSUM FROM CORE.SCHEMA_MIGRATION_PENDING WHERE VERSION = ?")) {
            statement.setInt(1, version);
            try (var rows = statement.executeQuery()) {
                if (rows.next()) {
                    requireChecksum(rows.getString(1), checksum);
                    return;
                }
            }
        }
        try (var statement = connection.prepareStatement("INSERT INTO CORE.SCHEMA_MIGRATION_PENDING VALUES (?, ?)")) {
            statement.setInt(1, version);
            statement.setString(2, checksum);
            statement.executeUpdate();
        }
        connection.commit();
    }

    private void validateCommandStreams(Connection connection) throws SQLException {
        for (String query : List.of(
                "SELECT OPERATION_ID,TURN_ID,WORKSPACE_ID,MAXIMUM_BYTES,OUTPUT_BYTES,STATE,EXIT_CODE,UPDATED_AT FROM"
                        + " CORE.CODING_COMMAND_STREAM WHERE 1=0",
                "SELECT OPERATION_ID,OFFSET_BYTES,CHANNEL,CONTENT_DIGEST,CONTENT_LENGTH FROM CORE.CODING_COMMAND_CHUNK"
                        + " WHERE 1=0")) {
            try (var statement = connection.createStatement();
                    var ignored = statement.executeQuery(query)) {
                // 先验证新表与关键列，再提交不可变 migration checksum。
            }
        }
    }

    private void validateContextTables(Connection connection, int version) throws SQLException {
        if (version != 2) {
            return;
        }
        for (String query : List.of(
                "SELECT PROVIDER_ID, PROVIDER_REVISION, MODEL, CONTEXT_WINDOW_TOKENS, MAXIMUM_OUTPUT_TOKENS FROM"
                        + " CORE.PROVIDER_MODEL_CONTEXT WHERE 1 = 0",
                "SELECT TURN_ID, POLICY_JSON, POLICY_DIGEST, WINDOW_JSON, WINDOW_DIGEST, THROUGH_SEQUENCE, REVISION"
                        + " FROM CORE.TURN_CONTEXT_STATE WHERE 1 = 0",
                "SELECT TURN_ID, ORDINAL, STATE, INTENT_DIGEST, NATIVE_CALL, BEFORE_TOKENS, SOURCE_SEQUENCE,"
                        + " AFTER_TOKENS, USAGE_JSON, RESULT_DIGEST FROM CORE.TURN_COMPACTION_CALL WHERE 1 = 0")) {
            try (var statement = connection.createStatement();
                    var ignored = statement.executeQuery(query)) {
                // 缺列或不完整的历史 DDL 必须阻止 history 提交，不能仅凭表名认为迁移完成。
            }
        }
    }

    private byte[] read(String resource) {
        try (InputStream stream = CoreMigrationRunner.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new PersistenceException("缺少 migration resource: " + resource);
            }
            return stream.readAllBytes();
        } catch (IOException failure) {
            throw new PersistenceException("读取 migration 失败", failure);
        }
    }

    private static void requireChecksum(String stored, String expected) {
        if (!stored.equals(expected)) {
            throw new PersistenceException("data-v6 migration checksum 不一致");
        }
    }

    private static String digest(byte[] script) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(script));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private record Migration(int version, String description, String resource) {}
}
