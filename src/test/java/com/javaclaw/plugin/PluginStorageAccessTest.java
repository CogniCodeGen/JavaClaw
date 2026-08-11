package com.javaclaw.plugin;

import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.spring.ApplicationContexts;
import com.javaclaw.plugin.api.Capability;
import com.javaclaw.plugin.capability.StorageAccessImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PluginStorageAccessTest {

    @TempDir
    Path tempDirectory;

    @Test
    void storageIsDurableAndBoundToPluginAndWorkspace() {
        try (var context = ApplicationContexts.createRoot(
                new DataRoot(tempDirectory.resolve("plugin-storage")))) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            PlatformTransactionManager transactions = context.getBean(
                    PlatformTransactionManager.class);
            var identity = new PluginScope.PluginIdentity(
                    "demo", Set.of(Capability.STORAGE));

            ScopedValue.where(PluginScope.CURRENT, identity).run(() -> {
                var first = new StorageAccessImpl("demo", "workspace-a", jdbc, transactions);
                first.put("alpha", "one");
                first.put("beta", "two");
                first.put("alpha", "updated");

                var reloaded = new StorageAccessImpl("demo", "workspace-a", jdbc, transactions);
                assertEquals("updated", reloaded.get("alpha"));
                assertEquals(Set.of("alpha", "beta"), reloaded.keys());

                var otherWorkspace = new StorageAccessImpl(
                        "demo", "workspace-b", jdbc, transactions);
                assertEquals("", otherWorkspace.get("alpha"));

                reloaded.remove("beta");
                assertEquals(Set.of("alpha"), reloaded.keys());
            });
        }
    }
}
