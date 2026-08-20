package com.javaclaw.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.StartupPolicy;
import com.javaclaw.application.tool.ToolAuditSink;
import com.javaclaw.application.tool.ToolAuthorization;
import com.javaclaw.application.tool.ToolInvocation;
import com.javaclaw.application.tool.ToolInvocationPipeline;
import com.javaclaw.application.tool.ToolResult;
import com.javaclaw.config.CredentialCipher;
import com.javaclaw.framework.api.AgentClient;
import com.javaclaw.framework.api.CancelReason;
import com.javaclaw.framework.api.ResumeCommand;
import com.javaclaw.framework.api.RunHandle;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunSnapshot;
import com.javaclaw.infrastructure.serviceplugin.ServicePluginProcessManager;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.data.SchemaInitializer;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginManagerServiceApprovalTest {
    @TempDir Path temporary;
    private ManagedTaskExecutor tasks;
    private ServicePluginProcessManager processes;
    private PluginManager manager;

    @AfterEach
    void close() {
        if (manager != null) manager.shutdown();
        if (processes != null) processes.close();
        if (tasks != null) tasks.close();
        System.clearProperty(ServicePluginRegistrar.ALLOW_UNSIGNED_DEVELOPMENT_PROPERTY);
    }

    @Test
    void approvalReloadsArtifactChangedAfterPassiveDiscovery() throws Exception {
        Path plugins = temporary.resolve("plugins");
        Path directory = plugins.resolve("builtin-deliverance");
        Path pluginJar = directory.resolve("deliverance.jar");
        createPluginJar(pluginJar, "0.0.12-1");
        System.setProperty(ServicePluginRegistrar.ALLOW_UNSIGNED_DEVELOPMENT_PROPERTY, "true");

        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:plugin-approval-" + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1", "sa", "");
        new SchemaInitializer(dataSource).initialize();
        PluginStore store = new PluginStore(new JdbcTemplate(dataSource),
                new DataSourceTransactionManager(dataSource), json);
        tasks = new ManagedTaskExecutor();
        DataRoot dataRoot = new DataRoot(temporary.resolve("data"));
        processes = new ServicePluginProcessManager(dataRoot, tasks, json);
        AtomicInteger confirmations = new AtomicInteger();
        AtomicBoolean allow = new AtomicBoolean();
        UserInteractionPort interaction = new UserInteractionPort() {
            @Override public boolean confirm(ConfirmRequest request) {
                confirmations.incrementAndGet();
                return allow.get();
            }
            @Override public void notify(ToastRequest request) { }
        };
        manager = new PluginManager(plugins, store, tasks, unusedAgentClient(), Runnable::run,
                new ToolInvocationPipeline(ignored -> ToolAuthorization.allow(), noOpAudit()),
                (pluginId, workspaceId) -> null, interaction, identityCipher(), json,
                processes, ServicePluginContributionRegistry.NOOP);

        manager.refresh();
        assertEquals(PluginState.PENDING_APPROVAL, manager.list().getFirst().state());

        createPluginJar(pluginJar, "0.0.12-2");
        assertFalse(manager.approveServicePlugin("builtin-deliverance"));
        assertEquals(PluginState.PENDING_APPROVAL, manager.list().getFirst().state());
        assertFalse(Files.exists(directory.resolve(".service-plugin-approved")));

        allow.set(true);
        assertTrue(manager.approveServicePlugin("builtin-deliverance"));

        assertEquals(2, confirmations.get());
        assertTrue(manager.list().isEmpty());
        assertEquals("0.0.12-2", processes.definition("builtin-deliverance")
                .orElseThrow().version());
        assertEquals(StartupPolicy.MANUAL, processes.definition("builtin-deliverance")
                .orElseThrow().startupPolicy());
        assertEquals("0.0.12-2", json.readTree(
                directory.resolve(".service-plugin-approved").toFile())
                .path("pluginVersion").asText());
    }

    private static AgentClient unusedAgentClient() {
        return new AgentClient() {
            @Override public RunHandle start(RunRequest request) {
                throw new UnsupportedOperationException();
            }
            @Override public RunHandle resume(RunId runId, ResumeCommand command) {
                throw new UnsupportedOperationException();
            }
            @Override public boolean cancel(RunId runId, CancelReason reason) { return false; }
            @Override public RunSnapshot get(RunId runId) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static ToolAuditSink noOpAudit() {
        return new ToolAuditSink() {
            @Override public void started(ToolInvocation invocation) { }
            @Override public void completed(ToolInvocation invocation, ToolResult result) { }
        };
    }

    private static CredentialCipher identityCipher() {
        return new CredentialCipher() {
            @Override public String encrypt(String value) { return value; }
            @Override public String decrypt(String value) { return value; }
            @Override public boolean isEncrypted(String value) { return false; }
            @Override public void warmUp() { }
        };
    }

    private static void createPluginJar(Path jar, String version) throws Exception {
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("plugin.json"));
            output.write(("""
                    {"id":"builtin-deliverance","name":"Deliverance 本地推理",
                     "version":"%s","pluginType":"SERVICE_PLUGIN",
                     "configurationSchema":{"type":"object","properties":{}},
                     "service":{"mainClass":"com.javaclaw.plugins.deliverance.DeliveranceServicePlugin",
                     "apiVersion":"1.0","startupPolicy":"AUTO_START","resourceHints":{},
                     "externalEndpoints":[]}}
                    """.formatted(version)).getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }
}
