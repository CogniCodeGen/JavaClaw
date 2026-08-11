package com.javaclaw.platform.spring;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.diagnostics.DiagnosticsApplicationService;
import com.javaclaw.application.diagnostics.DiagnosticsArchivePort;
import com.javaclaw.application.diagnostics.DiagnosticsUseCase;
import com.javaclaw.application.event.DomainEventPublisher;
import com.javaclaw.application.chat.ToolReviewSettingsPort;
import com.javaclaw.application.onboarding.ConnectionProbePort;
import com.javaclaw.application.onboarding.OnboardingApplicationService;
import com.javaclaw.application.onboarding.OnboardingSettingsPort;
import com.javaclaw.application.onboarding.OnboardingUseCase;
import com.javaclaw.application.plugin.PluginManagementApplicationService;
import com.javaclaw.application.plugin.PluginManagementPort;
import com.javaclaw.application.plugin.PluginManagementUseCase;
import com.javaclaw.application.tool.ToolAuthorization;
import com.javaclaw.application.tool.ToolAuthorizer;
import com.javaclaw.application.tool.ToolAuditSink;
import com.javaclaw.application.tool.ToolInvocationPipeline;
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
import com.javaclaw.system.JShellRunner;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.ui.javafx.JfxUserInteractionPort;
import com.javaclaw.ui.javafx.image.ImageViewerFactory;
import com.javaclaw.ui.javafx.interaction.InteractionDialogFactory;
import com.javaclaw.infrastructure.tool.LoggingToolAuditSink;
import com.javaclaw.infrastructure.config.AgentConfigToolReviewSettings;
import com.javaclaw.infrastructure.diagnostics.TraceExporterDiagnosticsArchive;
import com.javaclaw.diagnostics.TraceExporter;
import com.javaclaw.diagnostics.TraceRecorder;
import com.javaclaw.infrastructure.plugin.PluginManagerManagementAdapter;
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
import com.javaclaw.chat.ChatHistoryManager;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

/** 进程级基础设施的显式 Spring 装配。 */
@Configuration(proxyBeanMethods = false)
public class RootConfiguration {

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
    ChatHistoryManager chatHistoryManager(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper json,
            WorkspaceManager workspaces) {
        return new ChatHistoryManager(
                jdbc, transactionManager, json, workspaces::getCurrentWorkspaceId);
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
            ToolInvocationPipeline tools,
            PluginStorageFactory storage,
            UserInteractionPort interaction,
            CredentialCipher credentials) {
        return new PluginManager(store, executor, tools, storage, interaction, credentials);
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
    OnboardingSettingsPort onboardingSettingsPort(AgentConfig config) {
        return new AgentConfigOnboardingSettings(() -> config);
    }

    @Bean
    ConnectionProbePort connectionProbePort(HttpGateway gateway) {
        return new HttpConnectionProbeAdapter(gateway);
    }

    @Bean
    OnboardingApplicationService onboardingApplicationService(
            OnboardingSettingsPort settings,
            ConnectionProbePort connection) {
        return new OnboardingUseCase(settings, connection);
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
    SpringFxmlLoader springFxmlLoader(AutowireCapableBeanFactory beanFactory) {
        return new SpringFxmlLoader(beanFactory);
    }

    @Bean
    InteractionDialogFactory interactionDialogFactory(SpringFxmlLoader loader) {
        return new InteractionDialogFactory(loader);
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
