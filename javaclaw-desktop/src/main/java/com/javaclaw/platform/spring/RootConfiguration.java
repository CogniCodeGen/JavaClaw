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
import com.javaclaw.desktop.api.DesktopSessionService;
import com.javaclaw.system.JShellRunner;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.ui.javafx.JfxUserInteractionPort;
import com.javaclaw.ui.javafx.image.ImageViewerFactory;
import com.javaclaw.ui.javafx.interaction.InteractionDialogFactory;
import com.javaclaw.infrastructure.tool.LoggingToolAuditSink;
import com.javaclaw.infrastructure.workspace.WorkspaceManagerAdapter;
import com.javaclaw.infrastructure.config.AgentConfigToolReviewSettings;
import com.javaclaw.infrastructure.chat.JdbcChatHistoryStore;
import com.javaclaw.infrastructure.diagnostics.RunJournalDiagnosticsArchive;
import com.javaclaw.diagnostics.TraceExporter;
import com.javaclaw.diagnostics.TraceRecorder;
import com.javaclaw.infrastructure.onboarding.AgentConfigOnboardingSettings;
import com.javaclaw.infrastructure.onboarding.HttpConnectionProbeAdapter;
import com.javaclaw.infrastructure.settings.LegacyTestDataMaintenanceAdapter;
import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.config.AgentConfig;
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
@Import({InferenceRootConfiguration.class, ThreadRuntimeConfiguration.class,
        DesktopRootConfiguration.class})
public class RootConfiguration {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory
            .getLogger(RootConfiguration.class);

    /** Shared Spring AI observation registry for models, ChatClient, advisors and tools. */
    @Bean
    io.micrometer.observation.ObservationRegistry springAiObservationRegistry() {
        return io.micrometer.observation.ObservationRegistry.create();
    }

    @Bean(destroyMethod = "close")
    H2DataSource dataSource(DataRoot dataRoot) {
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

    /** 共享内核执行器适配器，扩展不自行创建线程池。 */
    @Bean("agentKernelExecutor")
    com.javaclaw.framework.spi.CancellableTaskExecutor agentKernelExecutor(
            ManagedTaskExecutor executor) {
        return new com.javaclaw.platform.execution.ManagedCancellableTaskExecutor(executor);
    }


    /** 独立 Runner 的路径与 Desktop classpath 分开维护。 */
    @Bean(destroyMethod = "close")
    com.javaclaw.infrastructure.plugin.v2.DefaultPluginManagementService pluginManagementService(
            DataRoot dataRoot, ManagedTaskExecutor tasks, ObjectMapper json,
            CredentialCipher credentials, HttpClient http, SchemaInitializer schema) throws java.io.IOException {
        var home = com.javaclaw.platform.data.ApplicationHome.resolve();
        var classpath = com.javaclaw.infrastructure.plugin.v2.DefaultPluginManagementService.discoverRunnerClasspath(home.root());
        // 在可信组合根解析系统目录别名；插件存储继续拒绝受管路径中的符号链接。
        var managedData = dataRoot.requireManaged(dataRoot.path()).toRealPath();
        return new com.javaclaw.infrastructure.plugin.v2.DefaultPluginManagementService(
                home.pluginsDirectory(), managedData.resolve("workspaces"),
                managedData.resolve("tmp/plugin-v2"), classpath, tasks, json, credentials, http);
    }

    @Bean
    com.javaclaw.framework.spi.ExternalPluginRuntime externalPluginRuntime(
            com.javaclaw.application.plugin.v2.PluginManagementService plugins, ObjectMapper json,
            com.javaclaw.application.inference.InferenceCatalogPort models) {
        return new com.javaclaw.infrastructure.agent.RemotePluginRuntime(plugins, json, models);
    }

    @Bean(destroyMethod = "close")
    com.javaclaw.infrastructure.agent.PluginHostServices pluginHostServices(
            com.javaclaw.infrastructure.plugin.v2.DefaultPluginManagementService plugins,
            ObjectMapper json, JdbcTemplate jdbc, com.javaclaw.framework.api.AgentClient agents,
            @org.springframework.beans.factory.annotation.Qualifier("agentKernelExecutor") java.util.concurrent.Executor executor) {
        return new com.javaclaw.infrastructure.agent.PluginHostServices(plugins.hostServices(), json, jdbc, agents, executor, plugins);
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
            SchemaInitializer schemaInitializer) {
        schemaInitializer.ensureThreadSchema();
        return new com.javaclaw.framework.store.JdbcRunStore(
                jdbc, transactionManager, json, frameworkClock);
    }

    @Bean
    com.javaclaw.framework.store.JdbcExecutionPlanStore frameworkExecutionPlanStore(
            JdbcTemplate jdbc, ObjectMapper json, Clock frameworkClock) {
        return new com.javaclaw.framework.store.JdbcExecutionPlanStore(jdbc, json, frameworkClock);
    }

    @Bean
    com.javaclaw.framework.store.JdbcExtensionStateStore frameworkExtensionStateStore(
            JdbcTemplate jdbc, ObjectMapper json, Clock frameworkClock) {
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
    com.javaclaw.framework.springai.SpringAiModelRegistry springAiModelRegistry() {
        return new com.javaclaw.framework.springai.SpringAiModelRegistry();
    }

    @Bean
    com.javaclaw.framework.springai.SpringAiAdvisorRegistry springAiAdvisorRegistry() {
        return new com.javaclaw.framework.springai.SpringAiAdvisorRegistry();
    }

    @Bean
    com.javaclaw.framework.springai.SpringAiAnnotatedToolRegistry springAiAnnotatedToolRegistry(
            ObjectMapper json, com.javaclaw.framework.core.InteractionJournal interactions,
            com.javaclaw.framework.store.JdbcRunStore runs) {
        return new com.javaclaw.framework.springai.SpringAiAnnotatedToolRegistry(json, interactions, runs);
    }

    @Bean
    com.javaclaw.framework.core.InteractionJournal interactionJournal(
            com.javaclaw.framework.store.JdbcRunStore runs,
            com.javaclaw.framework.store.JdbcThreadStore threads, ObjectMapper json) {
        return new com.javaclaw.framework.core.InteractionJournal(runs, threads, json);
    }

    @Bean
    com.javaclaw.framework.core.InteractionMetricsQuery interactionMetricsQuery(
            com.javaclaw.framework.store.JdbcRunStore runs, ObjectMapper json, Clock frameworkClock) {
        return new com.javaclaw.framework.core.InteractionMetricsQuery(runs, json, frameworkClock);
    }

    @Bean
    com.javaclaw.framework.core.InteractionDelegateCoordinator interactionDelegateCoordinator(
            ObjectProvider<com.javaclaw.framework.api.AgentClient> agents,
            com.javaclaw.framework.store.JdbcRunStore runs, ObjectMapper json,
            com.javaclaw.framework.core.RunUsageLedger usage,
            com.javaclaw.application.agent.SubAgentApprovalObserver approvals) {
        return new com.javaclaw.framework.core.InteractionDelegateCoordinator(
                agents::getObject, runs, json, usage, approvals);
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
    com.javaclaw.framework.core.RunEventModelTaskAuditSink modelTaskAuditSink(
            com.javaclaw.framework.store.JdbcRunStore runs) {
        return new com.javaclaw.framework.core.RunEventModelTaskAuditSink(runs);
    }

    @Bean
    com.javaclaw.framework.springai.SpringAiModelTaskGateway modelTaskGateway(
            com.javaclaw.framework.springai.SpringAiModelRegistry models,
            com.javaclaw.framework.core.RunUsageLedger usage,
            com.javaclaw.framework.core.RunEventModelTaskAuditSink audit,
            com.javaclaw.framework.store.JdbcRunStore runs,
            ObjectMapper json,
            @org.springframework.beans.factory.annotation.Qualifier("agentKernelExecutor")
            com.javaclaw.framework.spi.CancellableTaskExecutor executor) {
        return new com.javaclaw.framework.springai.SpringAiModelTaskGateway(
                models, usage, audit, json, executor, runs);
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
            ManagedTaskExecutor managedTasks,
            ObjectProvider<com.javaclaw.framework.api.AgentClient> agents,
            com.javaclaw.framework.store.JdbcRunStore runs,
            com.javaclaw.application.agent.SubAgentApprovalObserver childApprovals,
            com.javaclaw.framework.core.InteractionDelegateCoordinator interactions,
            ObjectMapper json) {
        var manager = new com.javaclaw.framework.extension.ExtensionManager(
                new com.javaclaw.framework.spi.ExtensionContext(
                        frameworkClock, executor, modelTasks, extensionState),
                new com.javaclaw.infrastructure.agent.ManagedBackgroundJobScheduler(managedTasks));
        var artifacts = new java.util.ArrayList<com.javaclaw.framework.extension.ExtensionArtifact>(
                com.javaclaw.framework.builtin.BuiltinExtensionCatalog.create(
                        capabilities, capabilities, capabilities, capabilities, hostTools,
                        new com.javaclaw.framework.builtin.SubAgentTools(agents::getObject, runs, childApprovals),
                        capabilities.contextSource("memory"),
                        capabilities.contextSource("knowledge"),
                        capabilities.contextSource("skills"),
                        capabilities.fixedContextSource("memory.persona"),
                        new com.javaclaw.framework.builtin.InteractionTools(interactions, json)));
        manager.publish(artifacts, java.util.Set.of());
        return manager;
    }

    @Bean
    com.javaclaw.framework.api.AgentStudioClient agentStudioClient(
            com.javaclaw.framework.store.JdbcAgentDefinitionStore definitions,
            com.javaclaw.framework.extension.ExtensionManager extensions,
            ObjectMapper json) {
        return new com.javaclaw.framework.core.DefaultAgentStudio(definitions, extensions, json);
    }



    @Bean
    com.javaclaw.framework.core.AgentCompiler agentCompiler(
            com.javaclaw.framework.store.JdbcAgentDefinitionStore definitions,
            com.javaclaw.framework.extension.ExtensionManager extensions,
            ObjectMapper json,
            com.javaclaw.framework.builtin.BuiltinDefinitionRegistry builtins,
            com.javaclaw.framework.spi.ExternalPluginRuntime externalPlugins) {
        return new com.javaclaw.framework.core.AgentCompiler(
                definitions, extensions, json,
                new com.javaclaw.framework.spi.RunConstraints(
                        com.javaclaw.framework.api.PermissionSet.UNRESTRICTED,
                        com.javaclaw.framework.api.RunBudget.UNBOUNDED),
                builtins).withExternalPlugins(externalPlugins);
    }

    @Bean
    com.javaclaw.framework.spi.ToolApprovalPolicy frameworkToolApprovalPolicy(
            AgentConfig settings) {
        return new TrustedToolApprovalPolicy(settings);
    }

    @Bean
    com.javaclaw.framework.core.ToolInvocationGateway frameworkToolInvocationGateway(
            com.javaclaw.framework.spi.ToolApprovalPolicy approvals,
            @org.springframework.beans.factory.annotation.Qualifier("agentKernelExecutor")
            com.javaclaw.framework.spi.CancellableTaskExecutor executor,
            Clock frameworkClock, com.javaclaw.framework.store.JdbcRunStore runs, ObjectMapper json) {
        return new com.javaclaw.framework.core.DefaultToolInvocationGateway(
                approvals, executor, frameworkClock, runs, json);
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
            var operationParameters = java.util.Set.of("cmd_execute", "code_build", "code_test")
                    .contains(challenge.tool())
                    ? java.util.Map.of(
                            "command", challenge.arguments().path("command").asText(""),
                            "workDir", challenge.arguments().path("workDir").asText(
                                    workDir == null ? "" : workDir))
                    : java.util.Map.<String, String>of();
            var outcome = ToolConfirmationManager.requestConfirmationOutcome(
                    origin, challenge.tool(), description, operationParameters);
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
            Clock frameworkClock,
            com.javaclaw.framework.store.JdbcRunStore runs,
            com.javaclaw.framework.api.AgentClient agents) {
        return new com.javaclaw.framework.core.DefaultToolClient(
                gateway, compiler, frameworkClock, approvals, runs, agents);
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
            com.javaclaw.framework.api.InteractionHistoryClient interactionHistory) {
        return new com.javaclaw.framework.springai.SpringAiReasoningGateway(
                models, advisors, tools, extensionState, usage, modelTasks, runs, json, executor,
                observations, interactionHistory);
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
            com.javaclaw.framework.springai.SpringAiModelTaskGateway modelTasks,
            DesktopSessionService desktopSessions,
            com.javaclaw.application.agent.SubAgentApprovalObserver childApprovals,
            ManagedTaskExecutor managedTasks) {
        return new com.javaclaw.framework.core.AgentEngine(
                compiler, runs, plans, reasoning, executor, json, frameworkClock, usage,
                modelTasks, (request, proof) -> {
                    var owner = new com.javaclaw.desktop.api.DesktopSessionOwner(
                            request.scope().workspaceId(), request.scope().sessionId(),
                            request.source().kind(), request.source().id());
                    try {
                        boolean reconciled = desktopSessions.reconcilePendingAction(owner,
                                proof.sessionId(), proof.actionObservationId(),
                                proof.evidenceObservationId()).toCompletableFuture().join();
                        if (!reconciled) log.warn("已核验动作未能解除桌面会话输入门禁: session={} action={} evidence={}",
                                proof.sessionId(), proof.actionObservationId(),
                                proof.evidenceObservationId());
                    } catch (RuntimeException failure) {
                        // The durable Run barrier is already reconciled. A closed or replaced
                        // native session remains conservatively blocked until reopened.
                        log.warn("无法同步已核验桌面动作的会话状态: {}", proof.sessionId(), failure);
                    }
                }, new com.javaclaw.application.agent.DesktopInteractionEventSource(desktopSessions),
                childApprovals, managedTasks);
    }

    @Bean
    com.javaclaw.framework.core.ExecutionKernel executionKernel(
            com.javaclaw.framework.core.AgentEngine engine) {
        return new com.javaclaw.framework.core.ExecutionKernel(engine);
    }

    @Bean
    com.javaclaw.workflow.runtime.WorkflowExtensionPlanProvider workflowExtensionPlanProvider(
            com.javaclaw.framework.extension.ExtensionManager extensions,
            com.javaclaw.framework.api.AgentClient agents, com.javaclaw.framework.api.ToolClient tools,
            com.javaclaw.application.plugin.v2.PluginManagementService plugins,
            WorkspaceManager workspaces, ObjectMapper json) {
        return new com.javaclaw.infrastructure.agent.RemoteWorkflowExtensionProvider(
                new com.javaclaw.framework.extension.FrameworkWorkflowExtensionProvider(extensions, agents, tools),
                plugins, workspaces, json);
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
    DiagnosticsArchivePort diagnosticsArchivePort(TraceExporter exporter,
            com.javaclaw.framework.store.JdbcRunStore runs,
            WorkspaceManager workspaces, ObjectMapper json) {
        return new RunJournalDiagnosticsArchive(runs, runs.threads(), workspaces, json, exporter);
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
