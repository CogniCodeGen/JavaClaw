package com.javaclaw.infrastructure.serviceplugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.ResourceConfiguration;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.StartupPolicy;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServicePluginHostRuntimeTest {
    @TempDir Path temporary;

    @Test
    void commandUsesBuiltInMainClassAndClasspathInsteadOfRunnerJar() {
        ServicePluginHostRuntime runtime = ServicePluginHostRuntime.capture();
        try (ManagedTaskExecutor tasks = new ManagedTaskExecutor()) {
            ServicePluginProcessLauncher launcher = new ServicePluginProcessLauncher(
                    tasks, new ObjectMapper(), new ServicePluginHostServiceRegistry(), 42, runtime);

            List<String> command = launcher.command(definition());

            assertTrue(command.contains("-cp"));
            assertTrue(command.contains(runtime.classpath()));
            assertTrue(command.contains(runtime.mainClass()));
            assertTrue(command.contains("--add-opens=java.base/java.nio=ALL-UNNAMED"));
            assertFalse(command.contains("-jar"));
            assertFalse(command.stream().anyMatch(value -> value.contains("service-plugin-runner.jar")));
            assertTrue(command.getLast().endsWith("plugin.jar"));
        }
    }

    @Test
    void rejectsCodeSourceChangedAfterRuntimeCapture() throws Exception {
        Path host = temporary.resolve("classes");
        Path mainClass = host.resolve("com/javaclaw/service/runner/ServicePluginProcessMain.class");
        Files.createDirectories(mainClass.getParent());
        Files.write(mainClass, new byte[]{1, 2, 3});
        Path jackson = temporary.resolve("jackson.jar");
        Files.write(jackson, new byte[]{4, 5, 6});
        ServicePluginHostRuntime runtime = new ServicePluginHostRuntime(
                host, List.of(host, jackson));

        runtime.verifyUnchanged();
        Files.write(mainClass, new byte[]{7, 8, 9});

        assertThrows(SecurityException.class, runtime::verifyUnchanged);
    }

    private ServicePluginDefinition definition() {
        return new ServicePluginDefinition("fixture", "Fixture", "1.0.0", "1.0",
                "fixture.Plugin", "Test", true, "a".repeat(64),
                temporary.resolve("plugin.jar"), temporary.resolve("data"),
                StartupPolicy.MANUAL, new ResourceConfiguration(128, 0, 1, 2, 32),
                List.of(), true, Set.of(), Map.of(), false);
    }
}
