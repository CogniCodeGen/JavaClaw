package com.javaclaw.infrastructure.plugin.v2;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.application.plugin.v2.PluginManagementService.PluginException;
import org.junit.jupiter.api.Test;
import com.javaclaw.plugin.protocol.v2.PluginIdentity;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PluginManifestTest {
    @Test void validatesCurrentPlatformAndRejectsUnsupportedRequirementsStatically() throws Exception {
        ObjectNode root = (ObjectNode) PluginFixture.JSON.readTree(PluginFixture.manifest("1.0"));
        root.putObject("platform").putArray("os").add(PluginManifest.normalizedOs(System.getProperty("os.name")));
        ((ObjectNode) root.path("platform")).putArray("architectures").add(PluginManifest.normalizedArchitecture(System.getProperty("os.arch")));
        root.putObject("runtime").put("minimumJavaVersion", Runtime.version().feature());
        assertDoesNotThrow(() -> PluginManifest.parse(PluginFixture.JSON, root.toString()));
        ((ObjectNode) root.path("runtime")).put("minimumJavaVersion", Runtime.version().feature() + 1);
        assertThrows(PluginException.class, () -> PluginManifest.parse(PluginFixture.JSON, root.toString()));
        ((ObjectNode) root.path("runtime")).put("minimumJavaVersion", 25);
        String differentOs = PluginManifest.normalizedOs(System.getProperty("os.name")).equals("windows") ? "linux" : "windows";
        ((ObjectNode) root.path("platform")).putArray("os").add(differentOs);
        assertThrows(PluginException.class, () -> PluginManifest.parse(PluginFixture.JSON, root.toString()));
        ((ObjectNode) root.path("platform")).putArray("os").add(PluginManifest.normalizedOs(System.getProperty("os.name")));
        String differentArchitecture = PluginManifest.normalizedArchitecture(System.getProperty("os.arch")).equals("aarch64") ? "x86_64" : "aarch64";
        ((ObjectNode) root.path("platform")).putArray("architectures").add(differentArchitecture);
        assertThrows(PluginException.class, () -> PluginManifest.parse(PluginFixture.JSON, root.toString()));
        ((ObjectNode) root.path("platform")).putArray("os");
        assertThrows(PluginException.class, () -> PluginManifest.parse(PluginFixture.JSON, root.toString()));
        ((ObjectNode) root.path("platform")).putArray("os").add("unsupported");
        assertThrows(PluginException.class, () -> PluginManifest.parse(PluginFixture.JSON, root.toString()));
    }

    @Test void usesProtocolSchemaToRejectMalformedAndUnknownRuntimeOptions() throws Exception {
        ObjectNode root = (ObjectNode) PluginFixture.JSON.readTree(PluginFixture.manifest("1.0"));
        root.putObject("runtime").put("nativeAccess", "true");
        assertThrows(PluginException.class, () -> PluginManifest.parse(PluginFixture.JSON, root.toString()));
        root.putObject("runtime").put("minimumJavaVersion", 24);
        assertThrows(PluginException.class, () -> PluginManifest.parse(PluginFixture.JSON, root.toString()));
        root.putObject("runtime").putArray("javaOptions").add("-javaagent:evil.jar");
        assertThrows(PluginException.class, () -> PluginManifest.parse(PluginFixture.JSON, root.toString()));
        root.putObject("resources").put("heapMiB", 64);
        root.remove("runtime");
        assertThrows(PluginException.class, () -> PluginManifest.parse(PluginFixture.JSON, root.toString()));
    }

    @Test void normalizesPlatformNamesAndBoundsDefaultPhysicalMemoryBudget() {
        assertEquals("macos", PluginManifest.normalizedOs("Mac OS X"));
        assertEquals("windows", PluginManifest.normalizedOs("Windows 11"));
        assertEquals("linux", PluginManifest.normalizedOs("Linux"));
        assertEquals("x86_64", PluginManifest.normalizedArchitecture("amd64"));
        assertEquals("aarch64", PluginManifest.normalizedArchitecture("arm64"));
        assertEquals(2048, DefaultPluginManagementService.defaultHeapBudgetMiB(0));
        assertEquals(4096, DefaultPluginManagementService.defaultHeapBudgetMiB(8192L * 1024 * 1024));
        assertEquals(16_384, DefaultPluginManagementService.defaultHeapBudgetMiB(128L * 1024 * 1024 * 1024));
    }

    @Test void acceptsGeneralSystemModuleDeclarationsAndRejectsInjectedOrOversizedNames() throws Exception {
        ObjectNode root = (ObjectNode) PluginFixture.JSON.readTree(PluginFixture.manifest("1.0"));
        root.putObject("runtime").putArray("javaModules").add("java.sql").add("jdk.attach");
        PluginManifest parsed = PluginManifest.parse(PluginFixture.JSON, root.toString());
        assertEquals(Set.of("java.sql", "jdk.attach"), parsed.javaModules());
        assertTrue(parsed.permissions().containsAll(Set.of("runtime.module:java.sql", "runtime.module:jdk.attach")));
        for (String invalid : java.util.List.of("java.sql,jdk.attach", "ALL-SYSTEM", "ALL-DEFAULT", "--add-opens", "java.sql --javaagent=evil.jar",
                "java/sql", "java..sql", "org.example.custom", "jdk." + "a".repeat(110))) {
            ((ObjectNode) root.path("runtime")).putArray("javaModules").add(invalid);
            assertThrows(PluginException.class, () -> PluginManifest.parse(PluginFixture.JSON, root.toString()), invalid);
        }
    }

    @Test void redactsDeclaredAndNestedEndpointSecretsAndGenericBearerValues() {
        PluginManifest manifest = PluginManifest.parse(PluginFixture.JSON, PluginFixture.manifest("1.0"));
        var secrets = PluginProcessSupervisor.secretValues(PluginFixture.JSON, manifest,
                "{\"apiKey\":\"declared-private-value\",\"endpointTls\":{\"privateKeyPem\":\"nested-private-key-value\"}}");
        var session = new PluginProcessSupervisor.Session(PluginIdentity.getDefaultInstance(), manifest, Set.of(), secrets);
        session.log("declared-private-value nested-private-key-value " + session.controlToken + " " + session.hostToken);
        String log = session.logs().getFirst();
        for (String secret : secrets) assertFalse(log.contains(secret));
        assertFalse(log.contains(session.controlToken)); assertFalse(log.contains(session.hostToken));
        assertFalse(session.redact("Bearer standalone-sensitive-value").contains("standalone-sensitive-value"));
    }
}
