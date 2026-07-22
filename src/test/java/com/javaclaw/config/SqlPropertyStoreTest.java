package com.javaclaw.config;

import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SqlPropertyStoreTest {

    @Test
    void replaceNamespaceRemovesKeysMissingFromSnapshot() throws Exception {
        try (var c = DriverManager.getConnection("jdbc:h2:mem:props-" + System.nanoTime())) {
            c.createStatement().execute("""
                    CREATE TABLE app_properties(
                        workspace_id VARCHAR(128), namespace VARCHAR(128), prop_key VARCHAR(256),
                        prop_value CLOB, updated_at TIMESTAMP,
                        PRIMARY KEY(workspace_id, namespace, prop_key))
                    """);
            c.createStatement().execute("""
                    INSERT INTO app_properties(workspace_id, namespace, prop_key, prop_value)
                    VALUES ('ws', 'agent', 'stale.secret', 'old'), ('ws', 'other', 'keep', 'yes')
                    """);
            Properties replacement = new Properties();
            replacement.setProperty("current", "new");

            c.setAutoCommit(false);
            SqlPropertyStore.replaceNamespace(c, "ws", "agent", replacement);
            c.commit();

            try (var rs = c.createStatement().executeQuery(
                    "SELECT namespace, prop_key, prop_value FROM app_properties ORDER BY namespace")) {
                assertTrueRow(rs, "agent", "current", "new");
                assertTrueRow(rs, "other", "keep", "yes");
                assertFalse(rs.next());
            }
        }
    }

    private static void assertTrueRow(java.sql.ResultSet rs, String namespace, String key, String value)
            throws Exception {
        assertEquals(true, rs.next());
        assertEquals(namespace, rs.getString(1));
        assertEquals(key, rs.getString(2));
        assertEquals(value, rs.getString(3));
    }
}
