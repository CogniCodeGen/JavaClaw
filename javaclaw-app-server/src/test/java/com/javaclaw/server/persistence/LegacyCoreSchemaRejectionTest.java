package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyCoreSchemaRejectionTest {
    private static final String LEGACY_BASELINE_CHECKSUM =
            "f33e9cc1b83fd85188b361ea579cd6847850449a9ed93548fa452d42057bb2b2";
    private static final String LEGACY_MCP_CHECKSUM =
            "de6a22e8a6624e0f0605d0648e2dc7523e2820402763467770c84f7ccef16407";

    @TempDir
    Path temporaryDirectory;

    @Test
    void legacyBaselineAndConflictingSecondVersionAreRejectedWithoutChangingHistoryOrBusinessData() throws Exception {
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        installLegacyHistoryAndSentinel(database);

        PersistenceException failure = assertThrows(PersistenceException.class, database::initialize);

        assertTrue(failure.getMessage().contains("baseline checksum"));
        assertLegacyHistory(database);
        assertBusinessSentinel(database);
    }

    private static void installLegacyHistoryAndSentinel(H2Database database) throws SQLException {
        // 仅复现已观察到的旧 history 身份；业务表由当前版本创建，不声称此夹具还原了旧版 Schema。
        // V002 与当前迁移含义不同，拒绝 V001 时必须保留两条记录，不能先改写摘要再继续迁移。
        try (Connection connection = database.open();
                var statement = connection.createStatement();
                var update = connection.prepareStatement(
                        "UPDATE CORE.SCHEMA_HISTORY SET DESCRIPTION = ?, CHECKSUM = ? WHERE VERSION = ?")) {
            connection.setAutoCommit(false);
            statement.executeUpdate("DELETE FROM CORE.SCHEMA_HISTORY WHERE VERSION > 2");
            update.setString(1, "v6 baseline");
            update.setString(2, LEGACY_BASELINE_CHECKSUM);
            update.setInt(3, 1);
            assertEquals(1, update.executeUpdate());
            update.setString(1, "mcp discovery snapshot");
            update.setString(2, LEGACY_MCP_CHECKSUM);
            update.setInt(3, 2);
            assertEquals(1, update.executeUpdate());
            statement.executeUpdate("""
                    INSERT INTO CORE.COMMAND_RESULT
                        (IDEMPOTENCY_KEY, METHOD_NAME, REQUEST_DIGEST, RESPONSE_PAYLOAD, CREATED_AT)
                    VALUES ('legacy-history-marker', 'test', REPEAT('a', 64), '{"retained":true}',
                        TIMESTAMP WITH TIME ZONE '2026-09-08 00:00:00+00:00')
                    """);
            connection.commit();
        }
    }

    private static void assertLegacyHistory(H2Database database) throws SQLException {
        try (Connection connection = database.open();
                var statement = connection.createStatement();
                var rows = statement.executeQuery(
                        "SELECT VERSION, DESCRIPTION, CHECKSUM FROM CORE.SCHEMA_HISTORY ORDER BY VERSION")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt("VERSION"));
            assertEquals("v6 baseline", rows.getString("DESCRIPTION"));
            assertEquals(LEGACY_BASELINE_CHECKSUM, rows.getString("CHECKSUM"));
            assertTrue(rows.next());
            assertEquals(2, rows.getInt("VERSION"));
            assertEquals("mcp discovery snapshot", rows.getString("DESCRIPTION"));
            assertEquals(LEGACY_MCP_CHECKSUM, rows.getString("CHECKSUM"));
            assertFalse(rows.next());
        }
    }

    private static void assertBusinessSentinel(H2Database database) throws SQLException {
        try (Connection connection = database.open();
                var statement = connection.createStatement();
                var rows = statement.executeQuery("""
                        SELECT IDEMPOTENCY_KEY, METHOD_NAME, REQUEST_DIGEST, RESPONSE_PAYLOAD, CREATED_AT
                        FROM CORE.COMMAND_RESULT
                        """)) {
            assertTrue(rows.next());
            assertEquals("legacy-history-marker", rows.getString("IDEMPOTENCY_KEY"));
            assertEquals("test", rows.getString("METHOD_NAME"));
            assertEquals("a".repeat(64), rows.getString("REQUEST_DIGEST"));
            assertEquals("{\"retained\":true}", rows.getString("RESPONSE_PAYLOAD"));
            assertEquals(
                    OffsetDateTime.parse("2026-09-08T00:00:00Z"), rows.getObject("CREATED_AT", OffsetDateTime.class));
            assertFalse(rows.next());
        }
    }
}
