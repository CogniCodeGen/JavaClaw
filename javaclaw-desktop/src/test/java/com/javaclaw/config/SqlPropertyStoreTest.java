package com.javaclaw.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SqlPropertyStoreTest {

    @Test
    void replaceNamespaceRemovesKeysMissingFromSnapshot() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:props-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE app_properties(
                    workspace_id VARCHAR(128), namespace VARCHAR(128), prop_key VARCHAR(256),
                    prop_value CLOB, updated_at TIMESTAMP,
                    PRIMARY KEY(workspace_id, namespace, prop_key))
                """);
        jdbc.update("""
                INSERT INTO app_properties(workspace_id, namespace, prop_key, prop_value)
                VALUES ('ws', 'agent', 'stale.secret', 'old'), ('ws', 'other', 'keep', 'yes')
                """);
        SqlPropertyStore store = new SqlPropertyStore(
                jdbc, new DataSourceTransactionManager(dataSource), () -> "ws");
        Properties replacement = new Properties();
        replacement.setProperty("current", "new");

        assertEquals(true, store.save("agent", replacement));

        Properties loaded = store.load("agent");
        assertEquals("new", loaded.getProperty("current"));
        assertFalse(loaded.containsKey("stale.secret"));
        assertEquals("yes", jdbc.queryForObject("""
                SELECT prop_value FROM app_properties
                WHERE workspace_id = 'ws' AND namespace = 'other' AND prop_key = 'keep'
                """, String.class));
    }
}
