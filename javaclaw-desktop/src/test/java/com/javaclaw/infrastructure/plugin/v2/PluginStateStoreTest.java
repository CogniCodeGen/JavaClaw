package com.javaclaw.infrastructure.plugin.v2;

import com.javaclaw.application.plugin.v2.PluginManagementService.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PluginStateStoreTest {
    @TempDir Path temporary;

    @Test void preservesEncryptedSecretHistoryAndExplicitSecretActions() throws Exception {
        Path root = temporary.toRealPath(); var store = new PluginStateStore(root, PluginFixture.JSON, PluginFixture.CIPHER);
        PluginManifest manifest = PluginManifest.parse(PluginFixture.JSON, PluginFixture.manifest("1.0"));
        var first = store.current("workspace", manifest, "a".repeat(64));
        var set = store.candidate(manifest, first.artifactHash(), first, new ConfigurationUpdate(first.revision(), "{\"label\":\"new\"}", Map.of("apiKey", SecretChange.set("super-secret")), Set.of("storage.read")));
        store.commit("workspace", manifest.id(), set);
        assertTrue(store.runtime(set).contains("super-secret"));
        assertFalse(store.snapshot(manifest, set).configurationJson().contains("super-secret"));
        assertTrue(store.snapshot(manifest, set).configuredSecrets().get("apiKey"));
        for (Path file : Files.walk(root).filter(Files::isRegularFile).toList()) assertFalse(Files.readString(file).contains("super-secret"));
        var keep = store.candidate(manifest, first.artifactHash(), set, new ConfigurationUpdate(set.revision(), "{}", Map.of(), Set.of("storage.read")));
        assertEquals(set.encryptedSecrets(), keep.encryptedSecrets()); store.commit("workspace", manifest.id(), keep);
        var clear = store.candidate(manifest, first.artifactHash(), keep, new ConfigurationUpdate(keep.revision(), "{}", Map.of("apiKey", SecretChange.clear()), Set.of("storage.read")));
        store.commit("workspace", manifest.id(), clear);
        assertFalse(store.snapshot(manifest, clear).configuredSecrets().get("apiKey"));
        assertTrue(store.runtime(store.historical("workspace", manifest.id(), first.artifactHash(), set.revision())).contains("super-secret"));
        assertThrows(PluginException.class, () -> store.historical("workspace", manifest.id(), "b".repeat(64), set.revision()));
        assertThrows(PluginException.class, () -> store.commit("workspace", manifest.id(), new PluginStateStore.StoredConfiguration(set.revision(), set.artifactHash(), "{}", Map.of(), Set.of())));
    }

    @Test void rejectsRevisionConflictUnknownPermissionsAndPlainSecretFields() throws Exception {
        var store = new PluginStateStore(temporary.toRealPath(), PluginFixture.JSON, PluginFixture.CIPHER);
        var manifest = PluginManifest.parse(PluginFixture.JSON, PluginFixture.manifest("1.0"));
        var current = store.current("workspace", manifest, "a".repeat(64));
        assertEquals("REVISION_CONFLICT", assertThrows(PluginException.class, () -> store.candidate(manifest, current.artifactHash(), current, new ConfigurationUpdate(99, "{}", Map.of(), Set.of()))).code());
        assertThrows(PluginException.class, () -> store.candidate(manifest, current.artifactHash(), current, new ConfigurationUpdate(1, "{}", Map.of(), Set.of("host.admin"))));
        assertThrows(PluginException.class, () -> store.candidate(manifest, current.artifactHash(), current, new ConfigurationUpdate(1, "{\"apiKey\":\"secret\"}", Map.of(), Set.of())));
    }

    @Test void rejectsExternalSchemaReferencesBeforeSchemaValidation() {
        String manifest = PluginFixture.manifest("1.0").replace("\"inputSchema\":{\"type\":\"object\"}", "\"inputSchema\":{\"$ref\":\"https://example.invalid/schema\"}");
        assertThrows(PluginException.class, () -> PluginManifest.parse(PluginFixture.JSON, manifest));
    }

    @Test void mapsRestrictedRuntimeRequirementsToExplicitPermissions() {
        String manifest = PluginFixture.manifest("1.0").replace("\"permissions\":[", "\"runtime\":{\"javaModules\":[\"jdk.incubator.vector\",\"jdk.httpserver\"],\"nativeAccess\":true},\"permissions\":[");
        var value = PluginManifest.parse(PluginFixture.JSON, manifest);
        assertTrue(value.permissions().containsAll(Set.of("runtime.module:jdk.incubator.vector", "runtime.module:jdk.httpserver", "runtime.native-access")));
        assertEquals(Set.of("jdk.incubator.vector", "jdk.httpserver"), value.javaModules()); assertTrue(value.nativeAccess());
        assertThrows(PluginException.class, () -> PluginManifest.parse(PluginFixture.JSON, manifest.replace("jdk.httpserver", "jdk.httpserver,--javaagent=evil.jar")));
        assertThrows(PluginException.class, () -> PluginManifest.parse(PluginFixture.JSON, manifest.replace("\"nativeAccess\":true", "\"jvmArgs\":[\"-javaagent:evil.jar\"]")));
    }

    @Test void completesDurableConfigurationCommitWhenCurrentPointerWasNotPublished() throws Exception {
        Path root = temporary.toRealPath(); var manifest = PluginManifest.parse(PluginFixture.JSON, PluginFixture.manifest("1.0"));
        var store = new PluginStateStore(root, PluginFixture.JSON, PluginFixture.CIPHER);
        var first = store.current("workspace", manifest, "a".repeat(64));
        Path pointer = root.resolve("workspace/plugins/fixture/current.json"); byte[] oldPointer = Files.readAllBytes(pointer);
        var next = store.candidate(manifest, first.artifactHash(), first, new ConfigurationUpdate(first.revision(), "{\"label\":\"durable\"}", Map.of(), Set.of()));
        store.commit("workspace", manifest.id(), next); Files.write(pointer, oldPointer);
        var reopened = new PluginStateStore(root, PluginFixture.JSON, PluginFixture.CIPHER);
        assertEquals(next, reopened.current("workspace", manifest, first.artifactHash()));
        assertEquals(first, reopened.historical("workspace", manifest.id(), first.artifactHash(), first.revision()));
    }
}
