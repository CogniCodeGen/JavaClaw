package com.javaclaw.infrastructure.serviceplugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.ResourceConfiguration;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.EndpointConfiguration;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.Protocol;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.StartupPolicy;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.State;
import com.javaclaw.fixture.serviceplugin.IsolatedFixtureServicePlugin;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServicePluginProcessManagerIntegrationTest {
    @TempDir Path temporary;

    @Test
    void startsAuthenticatesAndStopsPluginWithBuiltInChildRuntime() throws Exception {
        Path pluginJar = fixtureJar();
        try (ManagedTaskExecutor tasks = new ManagedTaskExecutor();
             ServicePluginProcessManager manager = new ServicePluginProcessManager(
                     new DataRoot(temporary.resolve("host-data")), tasks,
                     new ObjectMapper().findAndRegisterModules())) {
            manager.init();
            manager.register(definition(pluginJar, sha256(pluginJar)));

            assertEquals(State.INSTALLED, manager.list().getFirst().state());
            manager.start("fixture");

            var running = manager.list().getFirst();
            assertEquals(State.HEALTHY, running.state());
            assertTrue(running.pid() > 0);
            assertTrue(running.services().contains("fixture/echo"));
            var pidRecord = new ObjectMapper().readTree(temporary.resolve(
                    "host-data/run/service-plugins/fixture.json").toFile());
            assertEquals("com.javaclaw.service.runner.ServicePluginProcessMain",
                    pidRecord.path("runnerMainClass").asText());
            assertTrue(pidRecord.path("runnerPath").isNull());
            assertEquals(pluginJar.toString(), pidRecord.path("pluginPath").asText());

            manager.stop("fixture");
            assertEquals(State.STOPPED, manager.list().getFirst().state());
            assertEquals(0, manager.list().getFirst().pid());
        }
    }

    @Test
    void refusesPluginJarChangedAfterRegistration() throws Exception {
        Path pluginJar = fixtureJar();
        String approvedHash = sha256(pluginJar);
        try (ManagedTaskExecutor tasks = new ManagedTaskExecutor();
             ServicePluginProcessManager manager = new ServicePluginProcessManager(
                     new DataRoot(temporary.resolve("tamper-data")), tasks,
                     new ObjectMapper().findAndRegisterModules())) {
            manager.init();
            manager.register(definition(pluginJar, approvedHash));
            Files.write(pluginJar, new byte[]{1, 2, 3});

            assertThrows(SecurityException.class, () -> manager.start("fixture"));
            assertEquals(State.FAILED, manager.list().getFirst().state());
        }
    }

    @Test
    void configurationMutationRestartsOnlyRunningPluginAndRollsBackFailedRestart()
            throws Exception {
        Path pluginJar = fixtureJar();
        try (ManagedTaskExecutor tasks = new ManagedTaskExecutor();
             ServicePluginProcessManager manager = new ServicePluginProcessManager(
                     new DataRoot(temporary.resolve("restart-data")), tasks,
                     new ObjectMapper().findAndRegisterModules())) {
            manager.init();
            manager.register(definition(pluginJar, sha256(pluginJar)));
            manager.start("fixture");
            long firstPid = manager.list().getFirst().pid();
            ResourceConfiguration accepted = new ResourceConfiguration(384, 0, 1, 4, 64);

            manager.updateResourcesAndRestart("fixture", accepted);

            var restarted = manager.list().getFirst();
            assertEquals(State.HEALTHY, restarted.state());
            assertEquals(accepted, restarted.resources());
            assertNotEquals(firstPid, restarted.pid());

            ResourceConfiguration rejected = new ResourceConfiguration(
                    30_000, 0, 1, 4, 64);
            assertThrows(ServicePluginResourceBudget.ResourceExhaustedException.class,
                    () -> manager.updateResourcesAndRestart("fixture", rejected));

            var restored = manager.list().getFirst();
            assertEquals(State.HEALTHY, restored.state());
            assertEquals(accepted, restored.resources());
            assertTrue(restored.pid() > 0);
        }
    }

    @Test
    void hotConfigurationKeepsTheRunningProcessIdentity() throws Exception {
        Path pluginJar = fixtureJar();
        String hash = sha256(pluginJar);
        try (ManagedTaskExecutor tasks = new ManagedTaskExecutor();
             ServicePluginProcessManager manager = new ServicePluginProcessManager(
                     new DataRoot(temporary.resolve("hot-config-data")), tasks,
                     new ObjectMapper().findAndRegisterModules())) {
            manager.init();
            manager.register(definition(pluginJar, hash));
            manager.start("fixture");
            long pid = manager.list().getFirst().pid();
            EndpointConfiguration endpoint = new EndpointConfiguration(
                    "hot", Protocol.HTTP, "127.0.0.1", 0, false, false,
                    null, "", "x".repeat(32), 60, 100_000, 1, 4, 1024);
            ServicePluginDefinition candidate = definition(
                    pluginJar, hash, List.of(endpoint));

            manager.hotConfigure("fixture", candidate, Duration.ofSeconds(5));
            manager.register(candidate);

            assertEquals(pid, manager.list().getFirst().pid());
            assertEquals("hot", manager.definition("fixture").orElseThrow()
                    .endpoints().getFirst().id());
        }
    }

    private ServicePluginDefinition definition(Path pluginJar, String hash) {
        return definition(pluginJar, hash, List.of());
    }

    private ServicePluginDefinition definition(
            Path pluginJar, String hash, List<EndpointConfiguration> endpoints) {
        return new ServicePluginDefinition("fixture", "Fixture", "1.0.0", "1.0",
                IsolatedFixtureServicePlugin.class.getName(), "Test Publisher", true, hash,
                pluginJar, temporary.resolve("plugin-data"), StartupPolicy.MANUAL,
                new ResourceConfiguration(128, 0, 1, 4, 64), endpoints, true,
                Set.of(), Map.of(), false);
    }

    private Path fixtureJar() throws Exception {
        Path jar = temporary.resolve("fixture-plugin.jar");
        String resource = "/" + IsolatedFixtureServicePlugin.class.getName()
                .replace('.', '/') + ".class";
        try (InputStream input = IsolatedFixtureServicePlugin.class.getResourceAsStream(resource);
             JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            if (input == null) throw new IllegalStateException("fixture class missing");
            output.putNextEntry(new JarEntry(resource.substring(1)));
            input.transferTo(output);
            output.closeEntry();
        }
        return jar;
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
