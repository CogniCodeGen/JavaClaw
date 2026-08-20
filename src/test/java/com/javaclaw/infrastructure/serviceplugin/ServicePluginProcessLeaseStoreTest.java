package com.javaclaw.infrastructure.serviceplugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServicePluginProcessLeaseStoreTest {
    @TempDir Path temporary;

    @Test
    void recognizesNewAndLegacyPidRecordCommands() throws Exception {
        ObjectMapper json = new ObjectMapper();
        String oldJson = """
                {"pid":1,"startedAtEpochMilli":2,"pluginId":"fixture",
                 "pluginVersion":"1.0","artifactSha256":"aaa","desktopGeneration":3,
                 "runnerPath":"/old/runner.jar","pluginPath":"/plugins/fixture.jar"}
                """;
        ServicePluginProcessLeaseStore.PidRecord legacy = json.readValue(
                oldJson, ServicePluginProcessLeaseStore.PidRecord.class);
        ServicePluginProcessLeaseStore.PidRecord builtIn =
                new ServicePluginProcessLeaseStore.PidRecord(1, 2, "fixture", "1.0",
                        "aaa", 3, null,
                        "com.javaclaw.service.runner.ServicePluginProcessMain",
                        "/plugins/fixture.jar");

        assertTrue(ServicePluginProcessLeaseStore.matches(legacy,
                List.of("-jar", "/old/runner.jar", "/plugins/fixture.jar")));
        assertTrue(ServicePluginProcessLeaseStore.matches(builtIn,
                List.of("-cp", "/host.jar", builtIn.runnerMainClass(), builtIn.pluginPath())));
        assertFalse(ServicePluginProcessLeaseStore.matches(builtIn,
                List.of("-cp", "/host.jar", builtIn.runnerMainClass(), "/plugins/other.jar")));
    }

    @Test
    void removesOnlyFixedLegacyRunnerCacheFile() throws Exception {
        Path legacyDirectory = temporary.resolve("plugin-runner");
        Path legacyRunner = legacyDirectory.resolve("javaclaw-service-plugin-runner.jar");
        Path unrelated = legacyDirectory.resolve("keep.txt");
        Files.createDirectories(legacyDirectory);
        Files.write(legacyRunner, new byte[]{1});
        Files.write(unrelated, new byte[]{2});

        new ServicePluginProcessLeaseStore(temporary, new ObjectMapper(), "runner.Main").init();

        assertFalse(Files.exists(legacyRunner));
        assertTrue(Files.exists(unrelated));
    }
}
