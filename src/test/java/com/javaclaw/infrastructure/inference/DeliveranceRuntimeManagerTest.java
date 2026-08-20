package com.javaclaw.infrastructure.inference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.inference.InferenceCatalogPort;
import com.javaclaw.plugin.api.PluginDescriptor;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.data.SchemaInitializer;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveranceRuntimeManagerTest {

    @TempDir Path temporary;
    private JdbcInferenceCatalog catalog;
    private ManagedTaskExecutor tasks;
    private DeliveranceRuntimeManager manager;

    @BeforeEach
    void createManager() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:runtime-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new SchemaInitializer(dataSource).initialize();
        catalog = new JdbcInferenceCatalog(new JdbcTemplate(dataSource),
                new DataSourceTransactionManager(dataSource), json);
        tasks = new ManagedTaskExecutor();
        manager = new DeliveranceRuntimeManager(new DataRoot(temporary.resolve("data")), catalog,
                tasks, json, KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic());
    }

    @AfterEach
    void closeManager() {
        if (tasks != null) tasks.close();
    }

    @Test
    void registersOnlyStaticDescriptorAndKeepsStableRuntimeIdAcrossPluginUpgrade() throws Exception {
        Path jar = canonicalJar("first");
        manager.register(descriptor("0.0.12-2", parameterSchema()), jar, sha256(jar));

        var first = catalog.runtime(DeliveranceRuntimeManager.BUILTIN_RUNTIME_ID).orElseThrow();
        assertTrue(first.active());
        assertEquals(jar.toAbsolutePath().normalize().toString(), first.installPath());
        assertEquals("0.0.12", first.manifest().engineVersion());

        // The file deliberately has no executable main class. Registration succeeding proves
        // static discovery did not execute plugin code.
        canonicalJar("second");
        manager.register(descriptor("0.0.12-3", parameterSchema()), jar, sha256(jar));
        var upgraded = catalog.runtime(DeliveranceRuntimeManager.BUILTIN_RUNTIME_ID).orElseThrow();
        assertEquals(first.manifest().runtimeId(), upgraded.manifest().runtimeId());
        assertFalse(first.manifest().files().getFirst().sha256().equals(
                upgraded.manifest().files().getFirst().sha256()));
    }

    @Test
    void migratesLegacyPluginFilenameAtomically() throws Exception {
        Path directory = temporary.resolve("plugins/builtin-deliverance");
        Files.createDirectories(directory);
        Path legacy = directory.resolve(DeliverancePluginLayout.LEGACY_JAR_NAME);
        Files.writeString(legacy, "legacy");
        Path obsoleteRuntime = directory.resolve("versions/old/deliverance-sidecar.jar");
        Files.createDirectories(obsoleteRuntime.getParent());
        Files.writeString(obsoleteRuntime, "legacy");

        manager.init();

        assertFalse(Files.exists(legacy));
        assertFalse(Files.exists(directory.resolve("versions")));
        assertEquals("legacy", Files.readString(directory.resolve(DeliverancePluginLayout.JAR_NAME)));
    }

    @Test
    void rejectsWrongLocationHashAndInvalidInferenceSchema() throws Exception {
        Path jar = canonicalJar("verified");
        Path outside = temporary.resolve("deliverance.jar");
        Files.copy(jar, outside);
        assertThrows(SecurityException.class,
                () -> manager.register(descriptor("0.0.12-2", parameterSchema()),
                        outside, sha256(outside)));
        assertThrows(SecurityException.class,
                () -> manager.register(descriptor("0.0.12-2", parameterSchema()),
                        jar, "0".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> manager.register(descriptor("0.0.12-2", "{\"type\":\"unknown\"}"),
                        jar, sha256(jar)));
        assertTrue(catalog.runtimes().isEmpty());
    }

    @Test
    void unregisterRemovesTheActiveRuntimeWhenNoProfilesReferenceIt() throws Exception {
        Path jar = canonicalJar("registered");
        manager.register(descriptor("0.0.12-3", parameterSchema()), jar, sha256(jar));

        manager.unregister("builtin-deliverance");

        assertTrue(catalog.runtime(DeliveranceRuntimeManager.BUILTIN_RUNTIME_ID).isEmpty());
    }

    private Path canonicalJar(String marker) throws Exception {
        Path jar = temporary.resolve("plugins/builtin-deliverance/deliverance.jar");
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("plugin.json"));
            output.write(marker.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }

    private static PluginDescriptor descriptor(String version, String parameterSchema) {
        Set<String> capabilities = Set.of("chat", "embeddings", "exact_usage",
                "terminal_usage", "cancellation", "service_plugin",
                "model-type:generation:qwen2", "model-type:embedding:bert");
        var inference = new PluginDescriptor.Inference(
                "deliverance", "0.0.12", version.endsWith("3") ? "3" : "2",
                1, 1, capabilities, parameterSchema);
        var service = new PluginDescriptor.Service(
                "com.javaclaw.plugins.deliverance.DeliveranceServicePlugin", "1.0",
                PluginDescriptor.StartupPolicy.MANUAL,
                new PluginDescriptor.ResourceHints(4096, 4096, 6, 64, 256, true),
                List.of(), Set.of("inference.api-usage"));
        return new PluginDescriptor("builtin-deliverance", "Deliverance 本地推理", version,
                "1.0", service.mainClass(), "本地推理", Set.of(), List.of(),
                PluginDescriptor.PluginType.SERVICE_PLUGIN, service,
                "{\"type\":\"object\",\"properties\":{}}", inference);
    }

    private static String parameterSchema() {
        return "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
                + "\"type\":\"object\",\"properties\":{"
                + "\"load\":{\"type\":\"object\"},"
                + "\"generation\":{\"type\":\"object\"}}}";
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
