package com.javaclaw.platform.spring;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.diagnostics.DiagnosticsApplicationService;
import com.javaclaw.application.diagnostics.DiagnosticsArchivePort;
import com.javaclaw.application.diagnostics.DiagnosticsUseCase;
import com.javaclaw.application.event.DomainEventPublisher;
import com.javaclaw.application.chat.ToolReviewSettingsPort;
import com.javaclaw.application.chat.ChatHistoryApplicationService;
import com.javaclaw.application.chat.ChatHistoryPort;
import com.javaclaw.application.chat.ChatHistoryUseCase;
import com.javaclaw.application.onboarding.ConnectionProbePort;
import com.javaclaw.application.onboarding.OnboardingApplicationService;
import com.javaclaw.application.onboarding.OnboardingSettingsPort;
import com.javaclaw.application.onboarding.OnboardingUseCase;
import com.javaclaw.application.plugin.PluginManagementApplicationService;
import com.javaclaw.application.plugin.AgentExtensionManagementApplicationService;
import com.javaclaw.application.plugin.PluginManagementPort;
import com.javaclaw.application.plugin.PluginManagementUseCase;
import com.javaclaw.application.tool.ToolAuthorization;
import com.javaclaw.application.tool.ToolAuthorizer;
import com.javaclaw.application.tool.ToolAuditSink;
import com.javaclaw.application.tool.ToolInvocationPipeline;
import com.javaclaw.application.workspace.WorkspaceApplicationService;
import com.javaclaw.application.workspace.WorkspaceManagementPort;
import com.javaclaw.application.workspace.WorkspaceUseCase;
import com.javaclaw.application.settings.TestDataMaintenanceApplicationService;
import com.javaclaw.application.settings.TestDataMaintenancePort;
import com.javaclaw.application.settings.TestDataMaintenanceUseCase;
import com.javaclaw.config.DatabaseAccess;
import com.javaclaw.config.DataManager;
import com.javaclaw.config.WorkspaceManager;
import com.javaclaw.config.SqlPropertyStore;
import com.javaclaw.config.EmailConfig;
import com.javaclaw.config.NotificationConfig;
import com.javaclaw.config.CredentialCipher;
import com.javaclaw.config.CredentialEncryptor;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.data.DataSourceDatabaseAccess;
import com.javaclaw.platform.data.H2DataSource;
import com.javaclaw.platform.build.ApplicationBuildIdentity;
import com.javaclaw.platform.data.SchemaInitializer;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.desktop.ExternalDirectoryOpener;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.http.HttpGateway;
import com.javaclaw.platform.json.JsonCodec;
import com.javaclaw.platform.process.ProcessRunner;
import com.javaclaw.platform.storage.AtomicContentStore;
import com.javaclaw.desktop.DesktopAutomation;
import com.javaclaw.desktop.DesktopAutomationPort;
import com.javaclaw.desktop.DesktopToolFactory;
import com.javaclaw.desktop.RobotInput;
import com.javaclaw.system.JShellRunner;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.ui.javafx.JfxUserInteractionPort;
import com.javaclaw.ui.javafx.image.ImageViewerFactory;
import com.javaclaw.ui.javafx.interaction.InteractionDialogFactory;
import com.javaclaw.infrastructure.tool.LoggingToolAuditSink;
import com.javaclaw.infrastructure.workspace.WorkspaceManagerAdapter;
import com.javaclaw.infrastructure.config.AgentConfigToolReviewSettings;
import com.javaclaw.infrastructure.chat.JdbcChatHistoryStore;
import com.javaclaw.infrastructure.diagnostics.TraceExporterDiagnosticsArchive;
import com.javaclaw.diagnostics.TraceExporter;
import com.javaclaw.diagnostics.TraceRecorder;
import com.javaclaw.infrastructure.plugin.PluginManagerManagementAdapter;
import com.javaclaw.infrastructure.serviceplugin.ServicePluginProcessManager;
import com.javaclaw.infrastructure.plugin.FrameworkAgentExtensionManagementAdapter;
import com.javaclaw.infrastructure.onboarding.AgentConfigOnboardingSettings;
import com.javaclaw.infrastructure.onboarding.HttpConnectionProbeAdapter;
import com.javaclaw.infrastructure.settings.LegacyTestDataMaintenanceAdapter;
import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.plugin.PluginManager;
import com.javaclaw.plugin.PluginStorageFactory;
import com.javaclaw.plugin.PluginStore;
import com.javaclaw.plugin.capability.StorageAccessImpl;
import com.javaclaw.ui.javafx.onboarding.OnboardingViewFactory;
import com.javaclaw.ui.javafx.onboarding.ProviderCardFactory;
import com.javaclaw.system.CommandSessionManager;
import com.javaclaw.system.CommandWhitelistManager;
import com.javaclaw.system.CommandToolFactory;
import com.javaclaw.app.UIHelper;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

/** 进程级基础设施的显式 Spring 装配。 */
@Configuration(proxyBeanMethods = false)
@Import(InferenceRootConfiguration.class)
public class RootConfiguration {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory
            .getLogger(RootConfiguration.class);

    /** Shared Spring AI observation registry for models, ChatClient, advisors and tools. */
    @Bean
    io.micrometer.observation.ObservationRegistry springAiObservationRegistry() {
        return io.micrometer.observation.ObservationRegistry.create();
    }

    @Bean
    DataSource dataSource(DataRoot dataRoot) {
        return new H2DataSource(dataRoot);
    }

    @Bean
    SchemaInitializer schemaInitializer(DataSource dataSource) {
        SchemaInitializer initializer = new SchemaInitializer(dataSource);
        initializer.initialize();
        return initializer;
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource, SchemaInitializer schemaInitializer) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    PlatformTransactionManager transactionManager(
            DataSource dataSource, SchemaInitializer schemaInitializer) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    com.javaclaw.platform.data.UsageHistoryReset usageHistoryReset(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        var reset = new com.javaclaw.platform.data.UsageHistoryReset(jdbc, transactionManager);
        reset.resetOnce();
        return reset;
    }

    @Bean(initMethod = "warmUp")
    CredentialCipher credentialCipher(JdbcTemplate jdbc) {
        return new CredentialEncryptor(jdbc);
    }

    @Bean(initMethod = "init")
    WorkspaceManager workspaceManager(
            DataRoot dataRoot,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager) {
        return new WorkspaceManager(dataRoot, jdbc, transactionManager);
    }

    @Bean
    WorkspaceManagementPort workspaceManagementPort(WorkspaceManager manager) {
        return new WorkspaceManagerAdapter(manager);
    }

    @Bean
    WorkspaceApplicationService workspaceApplicationService(WorkspaceManagementPort workspaces) {
        return new WorkspaceUseCase(workspaces);
    }

    @Bean
    DataManager dataManager(WorkspaceManager workspaces) {
        return new DataManager(workspaces);
    }

    @Bean
    DatabaseAccess databaseAccess(DataSource dataSource, SchemaInitializer schemaInitializer) {
        String description = dataSource instanceof H2DataSource h2
                ? h2.databaseFile().toString() : dataSource.toString();
        return new DataSourceDatabaseAccess(dataSource, description);
    }

    @Bean
    com.javaclaw.application.settings.ModelProviderCatalog modelProviderCatalog() {
        return new com.javaclaw.application.settings.DefaultModelProviderCatalog();
    }

    @Bean
    SqlPropertyStore sqlPropertyStore(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            WorkspaceManager workspaces) {
        return new SqlPropertyStore(jdbc, transactionManager, workspaces::getCurrentWorkspaceId);
    }

    @Bean
    AgentConfig agentConfig(
            SqlPropertyStore properties, DatabaseAccess database, CredentialCipher credentials) {
        return new AgentConfig(properties, database, credentials);
    }

    @Bean
    EmailConfig emailConfig(
            SqlPropertyStore properties, DatabaseAccess database, CredentialCipher credentials) {
        return new EmailConfig(properties, database, credentials);
    }

    @Bean
    NotificationConfig notificationConfig(
            SqlPropertyStore properties, DatabaseAccess database, CredentialCipher credentials) {
        return new NotificationConfig(properties, database, credentials);
    }

    @Bean
    ChatHistoryPort chatHistoryPort(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper json) {
        return new JdbcChatHistoryStore(jdbc, transactionManager, json);
    }

    @Bean
    ChatHistoryApplicationService chatHistoryApplicationService(ChatHistoryPort history) {
        return new ChatHistoryUseCase(history);
    }

    @Bean
    CommandWhitelistManager commandWhitelistManager(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            WorkspaceManager workspaces) {
        return new CommandWhitelistManager(
                jdbc, transactionManager, workspaces::getCurrentWorkspaceId);
    }

    @Bean(destroyMethod = "close")
    ManagedTaskExecutor managedTaskExecutor() {
        return new ManagedTaskExecutor();
    }

    /** 唯一的服务插件进程宿主；其依赖关系保证在执行器、H2 和密钥服务之前关闭。 */
    @Bean
    com.javaclaw.infrastructure.serviceplugin.ServicePluginHostServiceRegistry
    servicePluginHostServiceRegistry() {
        return new com.javaclaw.infrastructure.serviceplugin.ServicePluginHostServiceRegistry();
    }

    @Bean(initMethod = "init", destroyMethod = "close")
    ServicePluginProcessManager servicePluginProcessManager(
            DataRoot dataRoot,
            ManagedTaskExecutor tasks,
            ObjectMapper json,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            CredentialCipher credentials,
            com.javaclaw.infrastructure.serviceplugin.ServicePluginHostServiceRegistry hostServices) {
        return new ServicePluginProcessManager(
                dataRoot, tasks, json, jdbc, transactionManager, credentials, hostServices);
    }

    /** Shared kernel executor adapter; extensions never create their own thread pools. */
    @Bean("agentKernelExecutor")
    com.javaclaw.framework.spi.CancellableTaskExecutor agentKernelExecutor(
            ManagedTaskExecutor executor) {
        return new com.javaclaw.platform.execution.ManagedCancellableTaskExecutor(executor);
    }

    @Bean
    Clock frameworkClock() {
        return Clock.systemUTC();
    }

    @Bean
    com.javaclaw.framework.store.JdbcRunStore frameworkRunStore(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper json,
            Clock frameworkClock,
            com.javaclaw.platform.data.UsageHistoryReset usageHistoryReset) {
        return new com.javaclaw.framework.store.JdbcRunStore(
                jdbc, transactionManager, json, frameworkClock);
    }

    @Bean
    com.javaclaw.framework.store.JdbcExecutionPlanStore frameworkExecutionPlanStore(
            JdbcTemplate jdbc, ObjectMapper json, Clock frameworkClock,
            com.javaclaw.platform.data.UsageHistoryReset usageHistoryReset) {
        return new com.javaclaw.framework.store.JdbcExecutionPlanStore(jdbc, json, frameworkClock);
    }

    @Bean
    com.javaclaw.framework.store.JdbcExtensionStateStore frameworkExtensionStateStore(
            JdbcTemplate jdbc, ObjectMapper json, Clock frameworkClock,
            com.javaclaw.platform.data.UsageHistoryReset usageHistoryReset) {
        return new com.javaclaw.framework.store.JdbcExtensionStateStore(jdbc, json, frameworkClock);
    }

    @Bean
    com.javaclaw.framework.store.JdbcExtensionArtifactRepository extensionArtifactRepository(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            Clock frameworkClock) {
        return new com.javaclaw.framework.store.JdbcExtensionArtifactRepository(
                jdbc, transactionManager, frameworkClock);
    }

    @Bean
    com.javaclaw.framework.extension.TrustedExtensionInstaller trustedExtensionInstaller(
            DataRoot dataRoot,
            com.javaclaw.framework.store.JdbcExtensionArtifactRepository artifacts,
            Clock frameworkClock) {
        return new com.javaclaw.framework.extension.TrustedExtensionInstaller(
                dataRoot.path().resolve("extension-cache"), artifacts, frameworkClock);
    }

    @Bean
    com.javaclaw.framework.store.JdbcAgentDefinitionStore frameworkDefinitionStore(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper json,
            Clock frameworkClock) {
        return new com.javaclaw.framework.store.JdbcAgentDefinitionStore(
                jdbc, transactionManager, json, frameworkClock);
    }

    @Bean
    com.javaclaw.infrastructure.agent.WorkspaceUsageRegistry workspaceUsageRegistry() {
        return new com.javaclaw.infrastructure.agent.WorkspaceUsageRegistry();
    }

    @Bean
    com.javaclaw.framework.core.RunUsageLedger frameworkUsageLedger(
            com.javaclaw.infrastructure.agent.WorkspaceUsageRegistry usageObservers) {
        return new com.javaclaw.framework.core.RunUsageLedger(usageObservers);
    }

    @Bean
    com.javaclaw.framework.core.RunUsageAccountRestorer runUsageAccountRestorer(
            com.javaclaw.framework.store.JdbcRunStore runs,
            com.javaclaw.framework.store.JdbcExecutionPlanStore plans,
            com.javaclaw.framework.core.RunUsageLedger usage,
            ObjectMapper json) {
        return new com.javaclaw.framework.core.RunUsageAccountRestorer(runs, plans, usage, json);
    }

    @Bean
    com.javaclaw.framework.springai.SpringAiModelRegistry springAiModelRegistry() {
        return new com.javaclaw.framework.springai.SpringAiModelRegistry();
    }

    @Bean
    com.javaclaw.framework.springai.SpringAiAdvisorRegistry springAiAdvisorRegistry() {
        return new com.javaclaw.framework.springai.SpringAiAdvisorRegistry();
    }

    @Bean
    com.javaclaw.framework.springai.SpringAiAnnotatedToolRegistry springAiAnnotatedToolRegistry(
            ObjectMapper json) {
        return new com.javaclaw.framework.springai.SpringAiAnnotatedToolRegistry(json);
    }

    @Bean
    com.javaclaw.framework.builtin.WorkspaceCapabilityRegistry workspaceCapabilityRegistry() {
        return new com.javaclaw.framework.builtin.WorkspaceCapabilityRegistry();
    }

    @Bean
    com.javaclaw.framework.builtin.BuiltinDefinitionRegistry builtinDefinitionRegistry() {
        return new com.javaclaw.framework.builtin.BuiltinDefinitionRegistry();
    }

    @Bean
    com.javaclaw.framework.core.RunEventRelay runEventRelay() {
        return new com.javaclaw.framework.core.RunEventRelay();
    }

    @Bean
    com.javaclaw.framework.core.RunEventModelTaskAuditSink modelTaskAuditSink(
            com.javaclaw.framework.store.JdbcRunStore runs,
            com.javaclaw.framework.core.RunEventRelay events) {
        return new com.javaclaw.framework.core.RunEventModelTaskAuditSink(runs, events);
    }

    @Bean
    com.javaclaw.framework.springai.SpringAiModelTaskGateway modelTaskGateway(
            com.javaclaw.framework.springai.SpringAiModelRegistry models,
            com.javaclaw.framework.core.RunUsageLedger usage,
            com.javaclaw.framework.core.RunEventModelTaskAuditSink audit,
            com.javaclaw.framework.store.JdbcRunStore runs,
            com.javaclaw.framework.core.RunUsageAccountRestorer usageAccounts,
            ObjectMapper json,
            Clock frameworkClock,
            @org.springframework.beans.factory.annotation.Qualifier("agentKernelExecutor")
            com.javaclaw.framework.spi.CancellableTaskExecutor executor) {
        return new com.javaclaw.framework.springai.SpringAiModelTaskGateway(
                models, usage, audit, json, executor, runs, usageAccounts, frameworkClock);
    }

    @Bean(destroyMethod = "close")
    com.javaclaw.framework.extension.ExtensionManager extensionManager(
            Clock frameworkClock,
            @org.springframework.beans.factory.annotation.Qualifier("agentKernelExecutor")
            java.util.concurrent.Executor executor,
            com.javaclaw.framework.springai.SpringAiModelTaskGateway modelTasks,
            com.javaclaw.framework.springai.SpringAiAnnotatedToolRegistry hostTools,
            com.javaclaw.framework.builtin.WorkspaceCapabilityRegistry capabilities,
            com.javaclaw.framework.store.JdbcExtensionStateStore extensionState,
            com.javaclaw.framework.store.JdbcRunStore runs,
            JdbcTemplate jdbc,
            ManagedTaskExecutor managedTasks,
            com.javaclaw.framework.extension.TrustedExtensionInstaller installer) {
        var manager = new com.javaclaw.framework.extension.ExtensionManager(
                new com.javaclaw.framework.spi.ExtensionContext(
                        frameworkClock, executor, modelTasks, extensionState),
                new com.javaclaw.infrastructure.agent.ManagedBackgroundJobScheduler(managedTasks));
        var artifacts = new java.util.ArrayList<com.javaclaw.framework.extension.ExtensionArtifact>(
                com.javaclaw.framework.builtin.BuiltinExtensionCatalog.create(
                        capabilities, capabilities, capabilities, capabilities, hostTools,
                        new com.javaclaw.framework.builtin.context.ContextCompactionAdvisorFactory(
                                new com.javaclaw.framework.builtin.context.JdbcConversationContextSummaryStore(jdbc),
                                new com.javaclaw.infrastructure.chat.JdbcConversationHistorySource(jdbc),
                                frameworkClock),
                        runs));
        var restored = installer.loadAuthorized();
        artifacts.addAll(restored.artifacts());
        restored.failures().forEach(failure ->
                log.error("系统扩展缓存恢复失败，引用该版本的 Run 将进入恢复阻塞: {}", failure));
        try {
            manager.publish(artifacts, restored.disabledExtensionIds());
            return manager;
        } catch (RuntimeException failure) {
            try {
                manager.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            try {
                com.javaclaw.framework.extension.ExtensionArtifact.closeClassLoaders(
                        restored.artifacts());
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    @Bean
    com.javaclaw.framework.api.AgentStudioClient agentStudioClient(
            com.javaclaw.framework.store.JdbcAgentDefinitionStore definitions,
            com.javaclaw.framework.extension.ExtensionManager extensions,
            ObjectMapper json) {
        return new com.javaclaw.framework.core.DefaultAgentStudio(definitions, extensions, json);
    }

    @Bean
    com.javaclaw.framework.extension.TrustedExtensionService trustedExtensionService(
            com.javaclaw.framework.extension.TrustedExtensionInstaller installer,
            com.javaclaw.framework.store.JdbcExtensionArtifactRepository artifacts,
            com.javaclaw.framework.extension.ExtensionManager extensions) {
        return new com.javaclaw.framework.extension.TrustedExtensionService(
                installer, artifacts, extensions);
    }

    @Bean
    AgentExtensionManagementApplicationService agentExtensionManagementApplicationService(
            com.javaclaw.framework.extension.TrustedExtensionService extensions) {
        return new FrameworkAgentExtensionManagementAdapter(extensions);
    }

    @Bean
    com.javaclaw.framework.core.AgentCompiler agentCompiler(
            com.javaclaw.framework.store.JdbcAgentDefinitionStore definitions,
            com.javaclaw.framework.extension.ExtensionManager extensions,
            ObjectMapper json,
            com.javaclaw.framework.builtin.BuiltinDefinitionRegistry builtins) {
        return new com.javaclaw.framework.core.AgentCompiler(
                definitions, extensions, json,
                new com.javaclaw.framework.spi.RunConstraints(
                        com.javaclaw.framework.api.PermissionSet.UNRESTRICTED,
                        com.javaclaw.framework.api.RunBudget.UNBOUNDED),
                builtins);
    }

    @Bean
    com.javaclaw.framework.spi.ToolApprovalPolicy frameworkToolApprovalPolicy(
            AgentConfig settings) {
        return new com.javaclaw.framework.spi.ToolApprovalPolicy() {
            private com.javaclaw.agent.ToolApprovalRiskPolicy.Assessment assess(
                    com.javaclaw.framework.spi.ToolDescriptor tool) {
                return com.javaclaw.agent.ToolApprovalRiskPolicy.assess(
                        tool.name(), ToolConfirmationManager.isEnabled(),
                        settings.getToolReviewMode());
            }

            @Override
            public com.javaclaw.framework.spi.ToolApprovalDecision evaluate(
                    com.javaclaw.framework.spi.ToolDescriptor tool,
                    com.fasterxml.jackson.databind.JsonNode arguments,
                    com.javaclaw.framework.api.RunRequest request) {
                return assess(tool).decision();
            }

            @Override
            public String approvalKind(
                    com.javaclaw.framework.spi.ToolDescriptor tool,
                    com.fasterxml.jackson.databind.JsonNode arguments,
                    com.javaclaw.framework.api.RunRequest request) {
                return assess(tool).kind();
            }
        };
    }

    @Bean
    com.javaclaw.framework.core.ToolInvocationGateway frameworkToolInvocationGateway(
            com.javaclaw.framework.spi.ToolApprovalPolicy approvals,
            @org.springframework.beans.factory.annotation.Qualifier("agentKernelExecutor")
            com.javaclaw.framework.spi.CancellableTaskExecutor executor,
            Clock frameworkClock) {
        return new com.javaclaw.framework.core.DefaultToolInvocationGateway(
                approvals, executor, frameworkClock);
    }

    @Bean
    com.javaclaw.framework.spi.ToolApprovalResolver frameworkToolApprovalResolver(
            @org.springframework.beans.factory.annotation.Qualifier("agentKernelExecutor")
            java.util.concurrent.Executor executor) {
        return (challenge, request) -> java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            String kind = request.source().kind();
            String id = request.source().id();
            String workDir = request.attributes().containsKey("workDir")
                    ? request.attributes().get("workDir").asText(null) : null;
            com.javaclaw.agent.ToolCallOrigin origin = switch (kind) {
                case "chat", "plan" -> com.javaclaw.agent.ToolCallOrigin.INTERACTIVE;
                case "schedule" -> com.javaclaw.agent.ToolCallOrigin.scheduled(id);
                case "loop", "sdd", "workflow" ->
                        com.javaclaw.agent.ToolCallOrigin.managedTask(id, workDir);
                default -> com.javaclaw.agent.ToolCallOrigin.UNKNOWN;
            };
            String description;
            if (challenge.tool().equals("cmd_execute")) {
                String command = challenge.arguments().path("command").asText("");
                String directory = challenge.arguments().path("workDir").asText(workDir);
                description = ToolConfirmationManager.buildCommandDescription(
                        command, directory == null ? "" : directory);
            } else {
                description = challenge.description()
                        + (challenge.arguments().isEmpty()
                        ? "" : "\n参数: " + challenge.arguments());
            }
            var outcome = ToolConfirmationManager.requestConfirmationOutcome(
                    origin, challenge.tool(), description);
            return outcome.isAllow()
                    ? com.javaclaw.framework.api.ToolApprovalGrant.approve(
                            challenge, outcome == ToolConfirmationManager.ConfirmOutcome.ALLOWED_HUMAN)
                    : com.javaclaw.framework.api.ToolApprovalGrant.deny(challenge);
        }, executor);
    }

    @Bean
    com.javaclaw.framework.api.ToolClient frameworkToolClient(
            com.javaclaw.framework.core.ToolInvocationGateway gateway,
            com.javaclaw.framework.core.AgentCompiler compiler,
            com.javaclaw.framework.spi.ToolApprovalResolver approvals,
            Clock frameworkClock) {
        return new com.javaclaw.framework.core.DefaultToolClient(
                gateway, compiler, frameworkClock, approvals);
    }

    @Bean
    com.javaclaw.framework.core.ReasoningGateway springAiReasoningGateway(
            com.javaclaw.framework.springai.SpringAiModelRegistry models,
            com.javaclaw.framework.springai.SpringAiAdvisorRegistry advisors,
            com.javaclaw.framework.core.ToolInvocationGateway tools,
            com.javaclaw.framework.store.JdbcExtensionStateStore extensionState,
            com.javaclaw.framework.core.RunUsageLedger usage,
            com.javaclaw.framework.springai.SpringAiModelTaskGateway modelTasks,
            com.javaclaw.framework.store.JdbcRunStore runs,
            ObjectMapper json,
            @org.springframework.beans.factory.annotation.Qualifier("agentKernelExecutor")
            com.javaclaw.framework.spi.CancellableTaskExecutor executor,
            io.micrometer.observation.ObservationRegistry observations,
            Clock frameworkClock) {
        return new com.javaclaw.framework.springai.SpringAiReasoningGateway(
                models, advisors, tools, extensionState, usage, modelTasks, runs, json, executor,
                observations, frameworkClock);
    }

    @Bean(destroyMethod = "close")
    com.javaclaw.framework.core.DefaultRunResourceRegistry runResourceRegistry() {
        return new com.javaclaw.framework.core.DefaultRunResourceRegistry();
    }

    @Bean(destroyMethod = "close")
    com.javaclaw.framework.core.AgentEngine agentEngine(
            com.javaclaw.framework.core.AgentCompiler compiler,
            com.javaclaw.framework.store.JdbcRunStore runs,
            com.javaclaw.framework.store.JdbcExecutionPlanStore plans,
            com.javaclaw.framework.core.ReasoningGateway reasoning,
            @org.springframework.beans.factory.annotation.Qualifier("agentKernelExecutor")
            java.util.concurrent.Executor executor,
            ObjectMapper json,
            Clock frameworkClock,
            com.javaclaw.framework.core.RunUsageLedger usage,
            com.javaclaw.framework.core.RunEventRelay events,
            com.javaclaw.framework.spi.RunResourceRegistry runResources) {
        return new com.javaclaw.framework.core.AgentEngine(compiler, runs, plans, reasoning,
                executor, json, frameworkClock, usage, events, runResources);
    }

    @Bean
    com.javaclaw.framework.core.ExecutionKernel executionKernel(
            com.javaclaw.framework.core.AgentEngine engine) {
        return new com.javaclaw.framework.core.ExecutionKernel(engine);
    }

    @Bean
    com.javaclaw.workflow.runtime.WorkflowExtensionPlanProvider workflowExtensionPlanProvider(
            com.javaclaw.framework.extension.ExtensionManager extensions,
            com.javaclaw.framework.api.AgentClient agents,
            com.javaclaw.framework.api.ToolClient tools) {
        return new com.javaclaw.framework.extension.FrameworkWorkflowExtensionProvider(
                extensions, agents, tools);
    }

    @Bean
    FrameworkStartupDiagnostics frameworkStartupDiagnostics(ApplicationContext context) {
        return new FrameworkStartupDiagnostics(context);
    }

    @Bean(destroyMethod = "close")
    ToolReviewSettingsPort toolReviewSettings(
            ManagedTaskExecutor executor, AgentConfig config) {
        ToolConfirmationManager.configure(config);
        return new AgentConfigToolReviewSettings(executor, config);
    }

    @Bean
    ProcessRunner processRunner(ManagedTaskExecutor executor) {
        return new ProcessRunner(executor);
    }

    @Bean
    DesktopAutomation desktopAutomation(ProcessRunner processes) {
        return new DesktopAutomation(processes);
    }

    @Bean
    DesktopAutomationPort desktopAutomationPort(DesktopAutomation automation) {
        return automation.create();
    }

    @Bean
    @Lazy
    RobotInput robotInput() {
        return new RobotInput();
    }

    @Bean
    DesktopToolFactory desktopToolFactory(
            DesktopAutomationPort port, ObjectProvider<RobotInput> input) {
        return new DesktopToolFactory(port, input::getObject);
    }

    @Bean
    JShellRunner jshellRunner(ManagedTaskExecutor executor) {
        return new JShellRunner(executor);
    }

    @Bean
    ExternalDirectoryOpener externalDirectoryOpener(ManagedTaskExecutor executor) {
        return new ExternalDirectoryOpener(executor);
    }

    @Bean(destroyMethod = "close")
    TraceRecorder traceRecorder(WorkspaceManager workspaces, ObjectMapper mapper) {
        return new TraceRecorder(workspaces, mapper);
    }

    @Bean
    TraceExporter traceExporter(
            WorkspaceManager workspaces, TraceRecorder recorder, AgentConfig config) {
        return new TraceExporter(workspaces, recorder, config);
    }

    @Bean
    DiagnosticsArchivePort diagnosticsArchivePort(TraceExporter exporter) {
        return new TraceExporterDiagnosticsArchive(exporter);
    }

    @Bean
    DiagnosticsApplicationService diagnosticsApplicationService(
            DiagnosticsArchivePort archive) {
        return new DiagnosticsUseCase(archive, Clock.systemUTC());
    }

    @Bean
    TestDataMaintenancePort testDataMaintenancePort() {
        return new LegacyTestDataMaintenanceAdapter();
    }

    @Bean
    TestDataMaintenanceApplicationService testDataMaintenanceApplicationService(
            TestDataMaintenancePort maintenance) {
        return new TestDataMaintenanceUseCase(maintenance);
    }

    @Bean
    PluginStore pluginStore(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper json) {
        return new PluginStore(jdbc, transactionManager, json);
    }

    @Bean
    PluginStorageFactory pluginStorageFactory(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager) {
        return (pluginId, workspaceId) -> new StorageAccessImpl(
                pluginId, workspaceId, jdbc, transactionManager);
    }

    @Bean(destroyMethod = "shutdown")
    PluginManager pluginManager(
            PluginStore store,
            ManagedTaskExecutor executor,
            com.javaclaw.framework.api.AgentClient agents,
            @org.springframework.beans.factory.annotation.Qualifier("agentKernelExecutor")
            java.util.concurrent.Executor agentCallbacksExecutor,
            ToolInvocationPipeline tools,
            PluginStorageFactory storage,
            UserInteractionPort interaction,
            CredentialCipher credentials,
            ObjectMapper json,
            ServicePluginProcessManager servicePlugins,
            com.javaclaw.infrastructure.inference.DeliveranceRuntimeManager inferencePlugins) {
        return new PluginManager(
                store, executor, agents, agentCallbacksExecutor, tools, storage,
                interaction, credentials, json, servicePlugins, inferencePlugins);
    }

    @Bean(destroyMethod = "close")
    CommandSessionManager commandSessionManager(ManagedTaskExecutor executor) {
        return new CommandSessionManager(executor);
    }

    @Bean
    CommandToolFactory commandToolFactory(
            AgentConfig settings,
            CommandWhitelistManager whitelist,
            CommandSessionManager sessions,
            ProcessRunner processes) {
        return new CommandToolFactory(settings, whitelist, sessions, processes);
    }

    @Bean
    PluginManagementPort pluginManagementPort(PluginManager manager) {
        return new PluginManagerManagementAdapter(manager);
    }

    @Bean
    PluginManagementApplicationService pluginManagementApplicationService(
            PluginManagementPort plugins) {
        return new PluginManagementUseCase(plugins);
    }

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Bean
    JsonCodec jsonCodec(ObjectMapper mapper) {
        return new JsonCodec(mapper);
    }

    @Bean
    HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Bean
    HttpGateway httpGateway(ManagedTaskExecutor executor, HttpClient client) {
        return new HttpGateway(executor, client);
    }

    @Bean
    OnboardingSettingsPort onboardingSettingsPort(
            AgentConfig config,
            com.javaclaw.application.inference.InferenceCatalogPort inference,
            WorkspaceManager workspaces,
            PlatformTransactionManager transactions) {
        return new AgentConfigOnboardingSettings(() -> config, inference,
                workspaces::getCurrentWorkspaceId, transactions);
    }

    @Bean
    ConnectionProbePort connectionProbePort(HttpGateway gateway) {
        return new HttpConnectionProbeAdapter(gateway);
    }

    @Bean
    OnboardingApplicationService onboardingApplicationService(
            OnboardingSettingsPort settings,
            ConnectionProbePort connection,
            com.javaclaw.application.settings.ModelProviderCatalog providers) {
        return new OnboardingUseCase(settings, connection, providers);
    }

    @Bean
    AtomicContentStore atomicContentStore() {
        return new AtomicContentStore();
    }

    @Bean
    DomainEventPublisher domainEventPublisher(ApplicationEventPublisher publisher) {
        return publisher::publishEvent;
    }

    @Bean
    ToolAuthorizer toolAuthorizer() {
        return invocation -> {
            if (!ToolConfirmationManager.requiresConfirmation(invocation.toolName())) {
                return ToolAuthorization.allow();
            }
            return ToolConfirmationManager.requestConfirmation(
                    invocation.origin(), invocation.toolName(), invocation.description())
                    ? ToolAuthorization.allow()
                    : ToolAuthorization.reject("用户或安全策略未授权该操作");
        };
    }

    @Bean
    ToolAuditSink toolAuditSink() {
        return new LoggingToolAuditSink();
    }

    @Bean
    ToolInvocationPipeline toolInvocationPipeline(
            ToolAuthorizer authorizer, ToolAuditSink auditSink) {
        return new ToolInvocationPipeline(authorizer, auditSink);
    }

    @Bean
    FxDispatcher fxDispatcher() {
        return new FxDispatcher();
    }

    @Bean
    UIHelper uiHelper(FxDispatcher fx) {
        return new UIHelper(fx);
    }

    @Bean
    JfxUserInteractionPort jfxUserInteractionPort(
            FxDispatcher fxDispatcher,
            ImageViewerFactory imageViewer,
            InteractionDialogFactory dialogs) {
        return new JfxUserInteractionPort(fxDispatcher, imageViewer, dialogs);
    }

    @Bean
    DialogService dialogService(UserInteractionPort interactionPort) {
        return new DialogService(interactionPort);
    }

    @Bean
    ApplicationBuildIdentity applicationBuildIdentity() {
        return ApplicationBuildIdentity.launchedOrCapture(RootConfiguration.class);
    }

    @Bean
    SpringFxmlLoader springFxmlLoader(
            AutowireCapableBeanFactory beanFactory,
            ApplicationBuildIdentity buildIdentity) {
        return new SpringFxmlLoader(beanFactory, buildIdentity);
    }

    @Bean
    InteractionDialogFactory interactionDialogFactory(
            SpringFxmlLoader loader, UIHelper ui) {
        return new InteractionDialogFactory(loader, ui);
    }

    @Bean
    ProviderCardFactory providerCardFactory(SpringFxmlLoader loader) {
        return new ProviderCardFactory(loader);
    }

    @Bean
    OnboardingViewFactory onboardingViewFactory(
            OnboardingApplicationService onboarding,
            SpringFxmlLoader loader) {
        return new OnboardingViewFactory(onboarding, loader);
    }

    @Bean
    WorkspaceSpringContextFactory workspaceSpringContextFactory(ApplicationContext rootContext) {
        return new WorkspaceSpringContextFactory(rootContext);
    }
}
