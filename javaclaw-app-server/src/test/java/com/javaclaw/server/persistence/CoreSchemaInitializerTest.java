package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreSchemaInitializerTest {
    // 拆分前完整 v6 baseline 的摘要；分段不能改变既有数据库所校验的字节序列。
    private static final String BASELINE_CHECKSUM = "6f925f420019b0002d7f0a7a0c1c29304a308346ecb5f68d57e0fb598561e703";

    @TempDir
    Path temporaryDirectory;

    @Test
    void domainResourcesInitializeOneCompleteBaselineAndRestartPreservesItsChecksum() throws Exception {
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        assertSingleBaseline(database);
        try (Connection connection = database.open();
                var statement = connection.createStatement()) {
            assertEquals(77, count(connection, "INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'CORE'"));
            for (String table : List.of(
                    "AGENT_TURN",
                    "AGENT_ROLE",
                    "EXECUTION_CONFIGURATION",
                    "THIRD_PARTY_BUNDLE",
                    "CREDENTIAL_SECRET",
                    "PRIVATE_NETWORK_GRANT",
                    "MCP_ENDPOINT",
                    "CHILD_TURN_RESERVATION",
                    "CONVERSATION_COMPLETION_HEAD",
                    "CONVERSATION_COMPLETION",
                    "CONVERSATION_EVIDENCE_EXCLUSION",
                    "EXTENSION_JOB_CANCELLATION")) {
                assertEquals(0, count(connection, "CORE." + table));
            }
            assertEquals(1, count(connection, "CORE.ATTACHMENT_UPLOAD_QUOTA"));
            assertEquals(1, count(connection, "CORE.CONVERSATION_COMPLETION_INIT"));
            statement.executeUpdate("""
                    INSERT INTO CORE.COMMAND_RESULT
                        (IDEMPOTENCY_KEY, METHOD_NAME, REQUEST_DIGEST, RESPONSE_PAYLOAD, CREATED_AT)
                    VALUES ('restart-marker', 'test', REPEAT('a', 64), '{}', CURRENT_TIMESTAMP)
                    """);
        }

        H2Database reopened = new H2Database(database.dataRoot());
        reopened.initialize();
        assertSingleBaseline(reopened);
        try (Connection connection = reopened.open()) {
            assertEquals(H2Database.CORE_SCHEMA_VERSION, count(connection, "CORE.SCHEMA_HISTORY"));
        }
        try (Connection connection = reopened.open()) {
            assertEquals(1, count(connection, "CORE.COMMAND_RESULT"));
            assertEquals(1, count(connection, "CORE.ATTACHMENT_UPLOAD_QUOTA"));
        }
    }

    @Test
    void installedChecksumMismatchCannotBeSilentlyReplacedByDomainResources() throws Exception {
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        try (Connection connection = database.open();
                var statement =
                        connection.prepareStatement("UPDATE CORE.SCHEMA_HISTORY SET CHECKSUM = ? WHERE VERSION = 1")) {
            statement.setString(1, "f".repeat(64));
            assertEquals(1, statement.executeUpdate());
        }
        PersistenceException failure = assertThrows(PersistenceException.class, database::initialize);
        assertTrue(failure.getMessage().contains("baseline checksum"));
        assertTrue(failure.getMessage().contains(database.dataRoot().toString()));
        assertTrue(failure.getMessage().contains("数据库摘要=" + "f".repeat(64)));
        assertTrue(failure.getMessage().contains("程序摘要=" + BASELINE_CHECKSUM));
        assertTrue(failure.getMessage().contains("备份完整数据目录"));
        try (Connection connection = database.open();
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT CHECKSUM FROM CORE.SCHEMA_HISTORY WHERE VERSION = 1")) {
            assertTrue(result.next());
            assertEquals("f".repeat(64), result.getString(1));
            assertFalse(result.next());
            assertEquals(77, count(connection, "INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'CORE'"));
        }
    }

    private static void assertSingleBaseline(H2Database database) throws SQLException {
        try (Connection connection = database.open();
                var statement = connection.createStatement();
                var result = statement.executeQuery(
                        "SELECT VERSION, DESCRIPTION, CHECKSUM FROM CORE.SCHEMA_HISTORY WHERE VERSION = 1")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt("VERSION"));
            assertEquals("v6 baseline", result.getString("DESCRIPTION"));
            assertEquals(BASELINE_CHECKSUM, result.getString("CHECKSUM"));
            assertFalse(result.next());
        }
    }

    private static int count(Connection connection, String table) throws SQLException {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }
}
