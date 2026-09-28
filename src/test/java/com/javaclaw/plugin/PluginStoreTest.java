package com.javaclaw.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.spring.ApplicationContexts;
import com.javaclaw.plugin.api.Capability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PluginStoreTest {

    @TempDir
    Path tempDirectory;

    @Test
    void persistsStateAndKeepsWorkspaceSnapshotsIsolated() {
        try (var context = ApplicationContexts.createRoot(
                new DataRoot(tempDirectory.resolve("plugin-store")))) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            PlatformTransactionManager transactions = context.getBean(
                    PlatformTransactionManager.class);
            ObjectMapper json = context.getBean(ObjectMapper.class);

            PluginStore writer = new PluginStore(jdbc, transactions, json);
            writer.bind("workspace-a");
            writer.update("demo", true, Set.of(Capability.CHAT, Capability.STORAGE));
            writer.setConfig("demo", Map.of("endpoint", "https://example.test"));

            PluginStore reader = new PluginStore(jdbc, transactions, json);
            reader.bind("workspace-a");
            assertTrue(reader.isEnabled("demo"));
            assertEquals(Set.of(Capability.CHAT, Capability.STORAGE), reader.granted("demo"));
            assertEquals("https://example.test", reader.config("demo").get("endpoint"));

            reader.bind("workspace-b");
            assertFalse(reader.isEnabled("demo"));
            assertTrue(reader.granted("demo").isEmpty());
            reader.update("other", true, Set.of(Capability.MEMORY));

            reader.bind("workspace-a");
            assertTrue(reader.isEnabled("demo"));
            assertFalse(reader.isEnabled("other"));
        }
    }

    @Test
    void unknownPersistedCapabilityIsRejected() {
        try (var context = ApplicationContexts.createRoot(
                new DataRoot(tempDirectory.resolve("unknown-capability")))) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            jdbc.update("""
                    INSERT INTO plugin_state(workspace_id, plugin_id, enabled, granted_json, config_json)
                    VALUES ('workspace-a', 'demo', TRUE, '["RETIRED_CAPABILITY"]', '{}')
                    """);
            PluginStore store = new PluginStore(jdbc,
                    context.getBean(PlatformTransactionManager.class),
                    context.getBean(ObjectMapper.class));
            assertThrows(IllegalStateException.class, () -> store.bind("workspace-a"));
        }
    }

    @Test
    void malformedPersistedConfigurationCannotReplaceBoundWorkspace() {
        try (var context = ApplicationContexts.createRoot(
                new DataRoot(tempDirectory.resolve("malformed-configuration")))) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            PluginStore store = new PluginStore(jdbc,
                    context.getBean(PlatformTransactionManager.class),
                    context.getBean(ObjectMapper.class));
            store.bind("workspace-a");
            store.update("demo", true, Set.of(Capability.CHAT));
            jdbc.update("""
                    INSERT INTO plugin_state(workspace_id, plugin_id, enabled, granted_json, config_json)
                    VALUES ('workspace-b', 'broken', TRUE, '[]', '{"endpoint":123}')
                    """);

            assertThrows(IllegalStateException.class, () -> store.bind("workspace-b"));
            assertTrue(store.isEnabled("demo"));
            assertEquals(Set.of(Capability.CHAT), store.granted("demo"));
        }
    }
}
