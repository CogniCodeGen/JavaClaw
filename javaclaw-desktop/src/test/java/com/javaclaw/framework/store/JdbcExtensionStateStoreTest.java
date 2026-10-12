package com.javaclaw.framework.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.platform.data.SchemaInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcExtensionStateStoreTest {

    @Test
    void stateIsRunScopedExtensionNamespacedAndSchemaVersioned() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:extension-state-" + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1", "sa", "");
        new SchemaInitializer(dataSource).initialize();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        JdbcExtensionStateStore store = new JdbcExtensionStateStore(
                jdbc, new ObjectMapper().findAndRegisterModules(), Clock.systemUTC());
        RunId run = new RunId("run-1");

        store.put(run, "memory.recall", "cursor", 2,
                JsonNodeFactory.instance.objectNode().put("sequence", 7));

        assertEquals(7, store.view(run).get("memory.recall", "cursor")
                .orElseThrow().path("sequence").asInt());
        assertTrue(store.view(run).get("gepa.evaluate", "cursor").isEmpty());
        assertEquals(2, jdbc.queryForObject("""
                SELECT schema_version FROM agent_extension_state
                WHERE run_id = ? AND extension_id = ? AND state_key = ?
                """, Integer.class, run.value(), "memory.recall", "cursor"));

        store.remove(run, "memory.recall", "cursor");
        assertTrue(store.view(run).get("memory.recall", "cursor").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> store.put(
                run, "memory.recall", "cursor", 0,
                JsonNodeFactory.instance.objectNode()));
    }
}
