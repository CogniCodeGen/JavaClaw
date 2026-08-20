package com.javaclaw.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.StartupPolicy;
import com.javaclaw.infrastructure.serviceplugin.ServicePluginProcessManager;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.plugin.api.PluginDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServicePluginRegistrarTest {
    @TempDir Path temporary;
    private ManagedTaskExecutor tasks;
    private ServicePluginProcessManager processes;
    private String originalUserDirectory;

    @AfterEach
    void close() {
        System.clearProperty(ServicePluginRegistrar.ALLOW_UNSIGNED_DEVELOPMENT_PROPERTY);
        if (originalUserDirectory != null) {
            System.setProperty("user.dir", originalUserDirectory);
        }
        if (processes != null) processes.close();
        if (tasks != null) tasks.close();
    }

    @Test
    void registersApprovedUnsignedSampleAsManualWithoutExecutingPluginCode() throws Exception {
        Path plugins = temporary.resolve("plugins");
        Path pluginDirectory = plugins.resolve("builtin-deliverance");
        Path canonicalJar = pluginDirectory.resolve("deliverance.jar");
        createJar(canonicalJar, false);
        System.setProperty(ServicePluginRegistrar.ALLOW_UNSIGNED_DEVELOPMENT_PROPERTY, "true");

        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        tasks = new ManagedTaskExecutor();
        processes = new ServicePluginProcessManager(
                new DataRoot(temporary.resolve("data")), tasks, json);
        AtomicInteger confirmations = new AtomicInteger();
        AtomicInteger contributions = new AtomicInteger();
        UserInteractionPort interaction = new UserInteractionPort() {
            @Override public boolean confirm(ConfirmRequest request) {
                confirmations.incrementAndGet();
                return true;
            }
            @Override public void notify(ToastRequest request) { }
        };
        ServicePluginContributionRegistry registry = new ServicePluginContributionRegistry() {
            @Override public void register(
                    PluginDescriptor descriptor, Path pluginJar, String artifactSha256) {
                contributions.incrementAndGet();
            }
            @Override public void unregister(String pluginId) { }
        };
        PluginDescriptorLoader descriptors = new PluginDescriptorLoader(json);
        ServicePluginRegistrar registrar = new ServicePluginRegistrar(plugins, processes, interaction,
                descriptors, json, registry);

        assertTrue(registrar.discover(
                descriptors.load(canonicalJar), canonicalJar, pluginDirectory));

        var definition = processes.definition("builtin-deliverance").orElseThrow();
        assertFalse(definition.signatureVerified());
        assertTrue(definition.developmentUnsigned());
        assertEquals(StartupPolicy.MANUAL, definition.startupPolicy());
        assertEquals(1, confirmations.get());
        assertEquals(1, contributions.get());
        assertThrows(SecurityException.class,
                () -> processes.setStartupPolicy("builtin-deliverance", StartupPolicy.AUTO_START));
    }

    @Test
    void passiveDiscoveryWaitsForExplicitApprovalAndKeepsFirstRegistrationManual() throws Exception {
        Path plugins = temporary.resolve("plugins");
        Path pluginDirectory = plugins.resolve("builtin-deliverance");
        Path jar = pluginDirectory.resolve("deliverance.jar");
        createJar(jar, false, "0.0.12-2", "AUTO_START");
        System.setProperty(ServicePluginRegistrar.ALLOW_UNSIGNED_DEVELOPMENT_PROPERTY, "true");

        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        tasks = new ManagedTaskExecutor();
        processes = new ServicePluginProcessManager(
                new DataRoot(temporary.resolve("data")), tasks, json);
        AtomicInteger confirmations = new AtomicInteger();
        UserInteractionPort interaction = new UserInteractionPort() {
            @Override public boolean confirm(ConfirmRequest request) {
                confirmations.incrementAndGet();
                assertTrue(request.toolName().startsWith("批准服务插件："));
                return true;
            }
            @Override public void notify(ToastRequest request) { }
        };
        PluginDescriptorLoader loader = new PluginDescriptorLoader(json);
        ServicePluginRegistrar registrar = new ServicePluginRegistrar(plugins, processes, interaction,
                loader, json, ServicePluginContributionRegistry.NOOP);
        PluginDescriptor descriptor = loader.load(jar);

        assertEquals(ServicePluginRegistrar.DiscoveryState.PENDING_APPROVAL,
                registrar.discoverPassive(descriptor, jar, pluginDirectory));
        assertEquals(0, confirmations.get());
        assertFalse(Files.exists(pluginDirectory.resolve(".service-plugin-approved")));
        assertTrue(processes.definition(descriptor.id()).isEmpty());

        assertTrue(registrar.approvePending(descriptor, jar, pluginDirectory));
        assertEquals(1, confirmations.get());
        assertTrue(Files.isRegularFile(pluginDirectory.resolve(".service-plugin-approved")));
        assertEquals(StartupPolicy.MANUAL,
                processes.definition(descriptor.id()).orElseThrow().startupPolicy());
        assertEquals(com.javaclaw.application.serviceplugin
                        .ServicePluginManagementApplicationService.State.INSTALLED,
                processes.list().getFirst().state());
    }

    @Test
    void passiveDiscoveryReusesMatchingApprovalWithoutRunnerArtifactOrPrompt() throws Exception {
        Path plugins = temporary.resolve("plugins");
        Path pluginDirectory = plugins.resolve("builtin-deliverance");
        Path jar = pluginDirectory.resolve("deliverance.jar");
        createJar(jar, false);
        System.setProperty(ServicePluginRegistrar.ALLOW_UNSIGNED_DEVELOPMENT_PROPERTY, "true");
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        AtomicInteger confirmations = new AtomicInteger();
        UserInteractionPort interaction = new UserInteractionPort() {
            @Override public boolean confirm(ConfirmRequest request) {
                confirmations.incrementAndGet();
                return true;
            }
            @Override public void notify(ToastRequest request) { }
        };
        PluginDescriptorLoader loader = new PluginDescriptorLoader(json);
        tasks = new ManagedTaskExecutor();
        processes = new ServicePluginProcessManager(
                new DataRoot(temporary.resolve("first-data")), tasks, json);
        ServicePluginRegistrar first = new ServicePluginRegistrar(plugins, processes, interaction,
                loader, json, ServicePluginContributionRegistry.NOOP);
        assertTrue(first.discover(loader.load(jar), jar, pluginDirectory));
        assertEquals(1, confirmations.get());
        processes.close();
        tasks.close();

        tasks = new ManagedTaskExecutor();
        processes = new ServicePluginProcessManager(
                new DataRoot(temporary.resolve("upgraded-data")), tasks, json);
        ServicePluginRegistrar upgraded = new ServicePluginRegistrar(
                plugins, processes, interaction, loader, json,
                ServicePluginContributionRegistry.NOOP);

        assertEquals(ServicePluginRegistrar.DiscoveryState.REGISTERED,
                upgraded.discoverPassive(loader.load(jar), jar, pluginDirectory));
        assertEquals(1, confirmations.get());
        assertEquals(StartupPolicy.MANUAL,
                processes.definition("builtin-deliverance").orElseThrow().startupPolicy());
        assertFalse(Files.exists(temporary.resolve(
                "upgraded-data/plugin-runner/javaclaw-service-plugin-runner.jar")));
    }

    @Test
    void declinedExplicitApprovalLeavesArtifactPendingWithoutApprovalRecord() throws Exception {
        Path plugins = temporary.resolve("plugins");
        Path pluginDirectory = plugins.resolve("builtin-deliverance");
        Path jar = pluginDirectory.resolve("deliverance.jar");
        createJar(jar, false);
        System.setProperty(ServicePluginRegistrar.ALLOW_UNSIGNED_DEVELOPMENT_PROPERTY, "true");

        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        tasks = new ManagedTaskExecutor();
        processes = new ServicePluginProcessManager(
                new DataRoot(temporary.resolve("data")), tasks, json);
        AtomicInteger confirmations = new AtomicInteger();
        UserInteractionPort interaction = new UserInteractionPort() {
            @Override public boolean confirm(ConfirmRequest request) {
                confirmations.incrementAndGet();
                return false;
            }
            @Override public void notify(ToastRequest request) { }
        };
        PluginDescriptorLoader loader = new PluginDescriptorLoader(json);
        ServicePluginRegistrar registrar = new ServicePluginRegistrar(plugins, processes, interaction,
                loader, json, ServicePluginContributionRegistry.NOOP);
        PluginDescriptor descriptor = loader.load(jar);

        assertFalse(registrar.approvePending(descriptor, jar, pluginDirectory));
        assertEquals(1, confirmations.get());
        assertFalse(Files.exists(pluginDirectory.resolve(".service-plugin-approved")));
        assertTrue(processes.definition(descriptor.id()).isEmpty());
    }

    @Test
    void neverDowngradesBrokenSignatureMetadataToUnsignedDevelopmentMode() throws Exception {
        Path jar = temporary.resolve("plugins/builtin-deliverance/deliverance.jar");
        createJar(jar, true);
        System.setProperty(ServicePluginRegistrar.ALLOW_UNSIGNED_DEVELOPMENT_PROPERTY, "true");
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        tasks = new ManagedTaskExecutor();
        processes = new ServicePluginProcessManager(
                new DataRoot(temporary.resolve("data")), tasks, json);
        ServicePluginRegistrar registrar = new ServicePluginRegistrar(temporary.resolve("plugins"),
                processes, null,
                new PluginDescriptorLoader(json), json, ServicePluginContributionRegistry.NOOP);

        assertThrows(Exception.class, () -> registrar.discover(
                new PluginDescriptorLoader(json).load(jar), jar, jar.getParent()));
    }

    @Test
    void upgradesOneJarAtomicallyPreservesDataAndRollsBackFailedRegistration() throws Exception {
        originalUserDirectory = System.getProperty("user.dir");
        System.setProperty("user.dir", temporary.toString());
        System.setProperty(ServicePluginRegistrar.ALLOW_UNSIGNED_DEVELOPMENT_PROPERTY, "true");

        Path plugins = temporary.resolve("plugins");
        Path destination = plugins.resolve("builtin-deliverance");
        Path installed = destination.resolve("deliverance.jar");
        createJar(installed, false, "0.0.12-2");
        Path dataSentinel = destination.resolve("data/models/sentinel");
        Files.createDirectories(dataSentinel.getParent());
        Files.writeString(dataSentinel, "keep");

        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        tasks = new ManagedTaskExecutor();
        processes = new ServicePluginProcessManager(
                new DataRoot(temporary.resolve("data")), tasks, json);
        AtomicInteger confirmations = new AtomicInteger();
        UserInteractionPort interaction = new UserInteractionPort() {
            @Override public boolean confirm(ConfirmRequest request) {
                confirmations.incrementAndGet();
                return true;
            }
            @Override public void notify(ToastRequest request) { }
        };
        ServicePluginContributionRegistry registry = new ServicePluginContributionRegistry() {
            @Override public void register(
                    PluginDescriptor descriptor, Path pluginJar, String artifactSha256) {
                if (descriptor.version().endsWith("-3")) {
                    throw new IllegalStateException("simulated contribution failure");
                }
            }
            @Override public void unregister(String pluginId) { }
        };
        PluginDescriptorLoader loader = new PluginDescriptorLoader(json);
        ServicePluginRegistrar registrar = new ServicePluginRegistrar(plugins, processes, interaction,
                loader, json, registry);
        assertTrue(registrar.discover(loader.load(installed), installed, destination));

        Path failedUpgrade = temporary.resolve(
                "sample-plugins/deliverance/target/deliverance-failed.jar");
        createJar(failedUpgrade, false, "0.0.12-3");
        assertThrows(IllegalStateException.class, () -> registrar.install(
                failedUpgrade, loader.load(failedUpgrade), destination));

        assertEquals("0.0.12-2", loader.load(installed).version());
        assertEquals("0.0.12-2", processes.definition("builtin-deliverance")
                .orElseThrow().version());
        assertEquals("keep", Files.readString(dataSentinel));

        Path successfulUpgrade = temporary.resolve(
                "sample-plugins/deliverance/target/deliverance-success.jar");
        createJar(successfulUpgrade, false, "0.0.12-4");
        assertEquals(installed, registrar.install(
                successfulUpgrade, loader.load(successfulUpgrade), destination));
        assertEquals("0.0.12-4", loader.load(installed).version());
        assertEquals("0.0.12-4", processes.definition("builtin-deliverance")
                .orElseThrow().version());
        assertEquals("keep", Files.readString(dataSentinel));
        assertEquals(3, confirmations.get());
    }

    private static void createJar(Path jar, boolean brokenSignatureMetadata) throws Exception {
        createJar(jar, brokenSignatureMetadata, "0.0.12-2");
    }

    private static void createJar(
            Path jar, boolean brokenSignatureMetadata, String version) throws Exception {
        createJar(jar, brokenSignatureMetadata, version, "MANUAL");
    }

    private static void createJar(
            Path jar, boolean brokenSignatureMetadata, String version,
            String startupPolicy) throws Exception {
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("plugin.json"));
            output.write(("""
                    {"id":"builtin-deliverance","name":"Deliverance 本地推理",
                     "version":"%s","pluginType":"SERVICE_PLUGIN",
                     "configurationSchema":{"type":"object","properties":{}},
                     "service":{"mainClass":"com.javaclaw.plugins.deliverance.DeliveranceServicePlugin",
                     "apiVersion":"1.0","startupPolicy":"%s","resourceHints":{},
                     "externalEndpoints":[]}}
                    """.formatted(version, startupPolicy)).getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            if (brokenSignatureMetadata) {
                output.putNextEntry(new JarEntry("META-INF/BROKEN.SF"));
                output.write("not-a-signature".getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
    }
}
