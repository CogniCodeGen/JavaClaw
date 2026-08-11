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
}
