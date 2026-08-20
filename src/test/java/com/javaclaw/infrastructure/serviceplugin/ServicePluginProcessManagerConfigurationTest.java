package com.javaclaw.infrastructure.serviceplugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.EndpointConfiguration;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.Protocol;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.ResourceConfiguration;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.State;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.StartupPolicy;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServicePluginProcessManagerConfigurationTest {
    @TempDir Path temporary;

    @Test
    void persistenceFailureDoesNotExposeUnsavedConfiguration() {
        FailingStore store = new FailingStore();
        try (ManagedTaskExecutor tasks = new ManagedTaskExecutor();
             ServicePluginProcessManager manager = manager(tasks, store)) {
            manager.register(definition());
            ResourceConfiguration original = manager.definition("fixture").orElseThrow().resources();
            store.failSave = true;

            assertThrows(IllegalStateException.class, () -> manager.updateResources("fixture",
                    new ResourceConfiguration(768, 128, 2, 8, 96)));
            assertEquals(original, manager.definition("fixture").orElseThrow().resources());
            EndpointConfiguration endpoint = manager.definition("fixture").orElseThrow()
                    .endpoints().getFirst();
            assertThrows(IllegalStateException.class, () -> manager.updateConfiguration("fixture",
                    new ResourceConfiguration(768, 128, 2, 8, 96), List.of(endpoint)));
            assertEquals(original, manager.definition("fixture").orElseThrow().resources());
            assertThrows(IllegalStateException.class,
                    () -> manager.setStartupPolicy("fixture", StartupPolicy.AUTO_START));
            assertEquals(StartupPolicy.MANUAL,
                    manager.definition("fixture").orElseThrow().startupPolicy());
        }
    }

    @Test
    void registeringManualPluginAfterManagerInitDoesNotStartAProcess() throws Exception {
        FailingStore store = new FailingStore();
        try (ManagedTaskExecutor tasks = new ManagedTaskExecutor();
             ServicePluginProcessManager manager = manager(tasks, store)) {
            manager.init();

            manager.register(definition());
            Thread.sleep(100);

            var plugin = manager.list().getFirst();
            assertEquals(State.INSTALLED, plugin.state());
            assertEquals(0, plugin.pid());
        }
    }

    @Test
    void unregisterDeletesPersistedSecretsBeforeRemovingDefinition() {
        FailingStore store = new FailingStore();
        try (ManagedTaskExecutor tasks = new ManagedTaskExecutor();
             ServicePluginProcessManager manager = manager(tasks, store)) {
            manager.register(definition());
            manager.unregister("fixture");

            assertEquals("fixture", store.deleted);
            assertFalse(manager.definition("fixture").isPresent());
        }
    }

    @Test
    void redactedUiUpdatePreservesSecretsAndCannotAddUndeclaredEndpoint() {
        FailingStore store = new FailingStore();
        try (ManagedTaskExecutor tasks = new ManagedTaskExecutor();
             ServicePluginProcessManager manager = manager(tasks, store)) {
            manager.register(definition());
            EndpointConfiguration redacted = manager.list().getFirst().endpoints().getFirst();

            manager.updateEndpoint("fixture", new EndpointConfiguration(redacted.id(),
                    redacted.protocol(), redacted.bindAddress(), 18080, redacted.tlsEnabled(),
                    redacted.allowInsecureLan(), redacted.keyStorePath(), "", redacted.apiKey(),
                    120, redacted.tokensPerMinute(), redacted.maxConcurrent(),
                    redacted.maxConnections(), redacted.maxRequestBytes(), 37));

            EndpointConfiguration saved = manager.definition("fixture").orElseThrow()
                    .endpoints().getFirst();
            assertEquals("abcdefghijklmnopqrstuvwxyz123456", saved.apiKey());
            assertEquals(18080, saved.port());
            assertEquals(120, saved.requestsPerMinute());
            assertEquals(37, saved.requestTimeoutSeconds());
            assertThrows(IllegalArgumentException.class, () -> manager.updateEndpoint("fixture",
                    new EndpointConfiguration("undeclared", Protocol.HTTP, "127.0.0.1", 0,
                            false, false, null, "", "secret", 1, 1, 1, 1, 1)));
        }
    }

    @Test
    void rotatingApiKeyPersistsHighEntropySecretAndReturnsItOnlyOnce() {
        FailingStore store = new FailingStore();
        try (ManagedTaskExecutor tasks = new ManagedTaskExecutor();
             ServicePluginProcessManager manager = manager(tasks, store)) {
            manager.register(definition());

            var rotation = manager.rotateEndpointApiKey("fixture", "api");

            assertTrue(rotation.apiKey().startsWith("jcsp_"));
            assertTrue(rotation.apiKey().length() >= 40);
            assertEquals(rotation.apiKey(), manager.definition("fixture").orElseThrow()
                    .endpoints().getFirst().apiKey());
            assertNotEquals(rotation.apiKey(), manager.list().getFirst().endpoints().getFirst().apiKey());
            assertEquals("********", manager.list().getFirst().endpoints().getFirst().apiKey());
        }
    }

    @Test
    void dedicatedEndpointOwnerOverridesStaleProcessConfigurationButKeepsResources() {
        FailingStore store = new FailingStore();
        try (ManagedTaskExecutor tasks = new ManagedTaskExecutor();
             ServicePluginProcessManager manager = manager(tasks, store)) {
            manager.register(definition(false, 18080));
            ResourceConfiguration resources = new ResourceConfiguration(768, 128, 2, 8, 96);
            manager.updateResources("fixture", resources);

            manager.register(definition(false, 19000));

            ServicePluginDefinition restored = manager.definition("fixture").orElseThrow();
            assertEquals(19000, restored.endpoints().getFirst().port());
            assertEquals(resources, restored.resources());
            assertThrows(IllegalStateException.class,
                    () -> manager.updateEndpoint("fixture", restored.endpoints().getFirst()));
            assertThrows(IllegalStateException.class,
                    () -> manager.rotateEndpointApiKey("fixture", "api"));
        }
    }

    @Test
    void declarativePatchPreservesUntouchedValuesAndRejectsProtectedFields() {
        FailingStore store = new FailingStore();
        try (ManagedTaskExecutor tasks = new ManagedTaskExecutor();
             ServicePluginProcessManager manager = manager(tasks, store)) {
            manager.register(configuredDefinition());

            manager.patchPluginConfigurationAndRestart(
                    "fixture", Map.of("editable", "changed"));

            ServicePluginDefinition saved = manager.definition("fixture").orElseThrow();
            assertEquals("changed", saved.config().get("editable"));
            assertEquals("host-source", saved.config().get("managed"));
            assertEquals("internal-source", saved.config().get("hidden"));
            assertEquals(State.INSTALLED, manager.list().getFirst().state());
            assertThrows(IllegalArgumentException.class,
                    () -> manager.patchPluginConfigurationAndRestart(
                            "fixture", Map.of("managed", "second-source")));
            assertThrows(IllegalArgumentException.class,
                    () -> manager.patchPluginConfigurationAndRestart(
                            "fixture", Map.of("hidden", "second-source")));
            Map<String, String> deletion = new java.util.LinkedHashMap<>();
            deletion.put("managed", null);
            assertThrows(IllegalArgumentException.class,
                    () -> manager.patchPluginConfigurationAndRestart("fixture", deletion));
            assertEquals("host-source", manager.definition("fixture").orElseThrow()
                    .config().get("managed"));
        }
    }

    @Test
    void fullConfigurationSubmissionCannotDropHostManagedValues() {
        FailingStore store = new FailingStore();
        try (ManagedTaskExecutor tasks = new ManagedTaskExecutor();
             ServicePluginProcessManager manager = manager(tasks, store)) {
            manager.register(configuredDefinition());

            manager.updatePluginConfiguration("fixture", Map.of("editable", "replacement"));

            assertEquals(Map.of("editable", "replacement", "managed", "host-source",
                            "hidden", "internal-source"),
                    manager.definition("fixture").orElseThrow().config());
        }
    }

    @Test
    void pluginUpgradeUsesDeclaredEndpointProtocolAndOrderWhileKeepingHostSettings() {
        FailingStore store = new FailingStore();
        try (ManagedTaskExecutor tasks = new ManagedTaskExecutor();
             ServicePluginProcessManager manager = manager(tasks, store)) {
            EndpointConfiguration obsolete = endpoint("obsolete", Protocol.HTTP, 17000);
            EndpointConfiguration api = endpoint("api", Protocol.HTTP, 0);
            manager.register(definition("1.0.0", "a".repeat(64), true,
                    List.of(obsolete, api)));
            manager.updateEndpoint("fixture", new EndpointConfiguration(
                    "api", Protocol.HTTP, "127.0.0.1", 18080, true, false,
                    temporary.resolve("fixture.p12"), "key-store-secret",
                    "abcdefghijklmnopqrstuvwxyz123456", 120, 200_000, 2, 8,
                    2048, 41));

            EndpointConfiguration added = endpoint("added", Protocol.TCP, 19000);
            EndpointConfiguration upgraded = endpoint("api", Protocol.HTTPS, 443);
            manager.register(definition("2.0.0", "b".repeat(64), true,
                    List.of(added, upgraded)));

            List<EndpointConfiguration> restored = manager.definition("fixture").orElseThrow().endpoints();
            assertEquals(List.of("added", "api"), restored.stream()
                    .map(EndpointConfiguration::id).toList());
            assertEquals(added, restored.getFirst());
            EndpointConfiguration restoredApi = restored.get(1);
            assertEquals(Protocol.HTTPS, restoredApi.protocol());
            assertEquals(18080, restoredApi.port());
            assertEquals("abcdefghijklmnopqrstuvwxyz123456", restoredApi.apiKey());
            assertEquals(120, restoredApi.requestsPerMinute());
            assertEquals(41, restoredApi.requestTimeoutSeconds());
        }
    }

    private ServicePluginProcessManager manager(
            ManagedTaskExecutor tasks, ServicePluginConfigurationStore store) {
        ServicePluginResourceBudget budget = new ServicePluginResourceBudget(
                new ServicePluginResourceBudget.Limits(1_024, 2_048, 4, 32, 8, 512),
                () -> false);
        return new ServicePluginProcessManager(new DataRoot(temporary), tasks,
                new ObjectMapper(), budget, store);
    }

    private ServicePluginDefinition definition() {
        return definition(true, 0);
    }

    private ServicePluginDefinition definition(boolean endpointConfigurationManaged, int port) {
        return definition("1.0.0", "a".repeat(64), endpointConfigurationManaged,
                List.of(endpoint("api", Protocol.HTTP, port)));
    }

    private EndpointConfiguration endpoint(String id, Protocol protocol, int port) {
        return new EndpointConfiguration(id, protocol, "127.0.0.1", port,
                protocol == Protocol.HTTPS, false, null, "",
                "abcdefghijklmnopqrstuvwxyz123456", 60, 100_000, 1, 4, 1024);
    }

    private ServicePluginDefinition definition(
            String version, String hash, boolean endpointConfigurationManaged,
            List<EndpointConfiguration> endpoints) {
        return new ServicePluginDefinition("fixture", "Fixture", version, "1.0",
                "example.Fixture", "Test Publisher", true, hash,
                temporary.resolve("plugin.jar"), temporary.resolve("data"), StartupPolicy.MANUAL,
                new ResourceConfiguration(256, 0, 1, 4, 64), endpoints,
                endpointConfigurationManaged, Set.of(), Map.of(), false);
    }

    private ServicePluginDefinition configuredDefinition() {
        ServicePluginDefinition base = definition();
        String schema = """
                {"type":"object","properties":{
                  "editable":{"type":"string"},
                  "managed":{"type":"string","x-javaclaw-host-managed":true},
                  "hidden":{"type":"string","x-javaclaw-hidden":true}
                },"required":["editable","managed","hidden"],"additionalProperties":false}
                """;
        return new ServicePluginDefinition(base.id(), base.name(), base.version(), base.apiVersion(),
                base.mainClass(), base.publisher(), base.signatureVerified(), base.artifactSha256(),
                base.pluginJar(), base.dataDirectory(), base.startupPolicy(), base.resources(),
                base.endpoints(), base.endpointConfigurationManaged(), base.permissions(),
                Map.of("editable", "initial", "managed", "host-source",
                        "hidden", "internal-source"), base.builtIn(),
                "", schema, null, false);
    }

    private static final class FailingStore implements ServicePluginConfigurationStore {
        private SavedConfiguration value;
        private boolean failSave;
        private String deleted = "";

        @Override public Optional<SavedConfiguration> load(String pluginId) {
            return Optional.ofNullable(value).filter(saved -> saved.pluginId().equals(pluginId));
        }

        @Override public void save(SavedConfiguration configuration) {
            if (failSave) throw new IllegalStateException("fixture persistence failure");
            value = configuration;
        }

        @Override public void delete(String pluginId) {
            deleted = pluginId;
            value = null;
        }
    }
}
