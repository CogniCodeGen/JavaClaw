package com.javaclaw.infrastructure.plugin.v2;

import com.javaclaw.platform.execution.ManagedTaskExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BundledPluginInstallerTest {
    @TempDir Path temporary;

    @Test void recordsDefaultImportsUnderPluginsAndIgnoresVersionedLedger() throws Exception {
        Path home = temporary.toRealPath();
        Path packages = Files.createDirectories(home.resolve("runtime/plugin-packages"));
        Path archive = PluginFixture.archive(packages, "1.0", new byte[]{1, 2, 3});
        String hash = PluginPaths.sha256(archive);
        Files.writeString(home.resolve("runtime/plugin-distribution.json"),
                PluginFixture.JSON.writeValueAsString(Map.of("packages", List.of(Map.of(
                        "path", home.relativize(archive).toString().replace('\\', '/'), "sha256", hash)))));
        Path oldPlugins = Files.createDirectories(home.resolve("plugins-v2"));
        Path oldLedger = Files.writeString(oldPlugins.resolve(".bundled-imports.json"), "old ledger");

        try (var tasks = new ManagedTaskExecutor();
             var service = new DefaultPluginManagementService(home.resolve("plugins"),
                     home.resolve("data/workspaces"), home.resolve("data/tmp/plugin-v2"),
                     List.of(), tasks, PluginFixture.JSON, PluginFixture.CIPHER, HttpClient.newHttpClient())) {
            var installer = new BundledPluginInstaller(service, PluginFixture.JSON);
            assertEquals("INSTALLED_DISABLED", installer.importPackages(home, "first").getFirst().status());
            assertEquals(PluginFixture.JSON.valueToTree(List.of(hash)),
                    PluginFixture.JSON.readTree(home.resolve("plugins/.bundled-imports.json").toFile()));
            assertEquals("old ledger", Files.readString(oldLedger));
            assertFalse(service.details("first", "fixture").summary().enabled());
            assertEquals(0, service.diagnostics("first", "fixture").pid());
            assertEquals("SKIPPED_IMPORTED", installer.importPackages(home, "second").getFirst().status());
        }
    }
}
