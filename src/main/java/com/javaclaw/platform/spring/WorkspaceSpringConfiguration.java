package com.javaclaw.platform.spring;

import com.javaclaw.application.agent.AgentDefinitionPort;
import com.javaclaw.application.agent.AgentManagementApplicationService;
import com.javaclaw.application.agent.AgentManagementUseCase;
import com.javaclaw.application.agent.AgentPromptOptimizationPort;
import com.javaclaw.application.site.SiteCredentialApplicationService;
import com.javaclaw.application.site.SiteCredentialPort;
import com.javaclaw.application.site.SiteCredentialUseCase;
import com.javaclaw.application.mcp.McpConfigurationPort;
import com.javaclaw.application.mcp.McpImportPort;
import com.javaclaw.application.mcp.McpManagementApplicationService;
import com.javaclaw.application.mcp.McpManagementUseCase;
import com.javaclaw.application.mcp.McpRuntimePort;
import com.javaclaw.application.mcp.McpTemplatePort;
import com.javaclaw.application.knowledge.KnowledgeDocumentPreferencePort;
import com.javaclaw.application.workflow.WorkflowApplicationService;
import com.javaclaw.application.workflow.WorkflowPort;
import com.javaclaw.application.workflow.WorkflowUseCase;
import com.javaclaw.application.settings.ModelSettingsApplicationService;
import com.javaclaw.application.settings.BehaviorSettingsApplicationService;
import com.javaclaw.application.settings.BehaviorSettingsPort;
import com.javaclaw.application.settings.BehaviorSettingsUseCase;
import com.javaclaw.application.settings.EmbeddingRuntimeProbePort;
import com.javaclaw.application.settings.CommunicationSettingsApplicationService;
import com.javaclaw.application.settings.CommunicationSettingsPort;
import com.javaclaw.application.settings.CommunicationSettingsUseCase;
import com.javaclaw.application.settings.EmailConnectionProbePort;
import com.javaclaw.application.settings.ModelSettingsPort;
import com.javaclaw.application.settings.ModelSettingsProbePort;
import com.javaclaw.application.settings.ModelSettingsUseCase;
import com.javaclaw.application.schedule.ScheduleApplicationService;
import com.javaclaw.application.schedule.SchedulePort;
import com.javaclaw.application.schedule.ScheduleUseCase;
import com.javaclaw.application.task.SddTaskApplicationService;
import com.javaclaw.application.task.SddTaskUseCase;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.config.DatabaseAccess;
import com.javaclaw.infrastructure.agent.AgentPromptOptimizerAdapter;
import com.javaclaw.infrastructure.agent.StudioAgentDefinitionAdapter;
import com.javaclaw.infrastructure.site.SiteCredentialManagerAdapter;
import com.javaclaw.infrastructure.mcp.McpClientManagerAdapter;
import com.javaclaw.infrastructure.mcp.McpConfigManagerAdapter;
import com.javaclaw.infrastructure.mcp.McpJsonImporterAdapter;
import com.javaclaw.infrastructure.mcp.McpTemplateLibraryAdapter;
import com.javaclaw.infrastructure.knowledge.JdbcKnowledgeDocumentPreferenceAdapter;
import com.javaclaw.infrastructure.workflow.WorkflowServiceAdapter;
import com.javaclaw.infrastructure.settings.AgentConfigModelSettingsAdapter;
import com.javaclaw.infrastructure.settings.AgentConfigBehaviorSettingsAdapter;
import com.javaclaw.infrastructure.settings.EmbeddingGatewayRuntimeProbeAdapter;
import com.javaclaw.infrastructure.settings.HttpModelSettingsProbeAdapter;
import com.javaclaw.infrastructure.settings.JakartaMailConnectionProbeAdapter;
import com.javaclaw.infrastructure.settings.LegacyCommunicationSettingsAdapter;
import com.javaclaw.infrastructure.schedule.ScheduleManagerAdapter;
import com.javaclaw.mcp.McpClientManager;
import com.javaclaw.mcp.McpConfigManager;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.schedule.ScheduleManager;
import com.javaclaw.schedule.ScheduledTaskStore;
import com.javaclaw.skill.SkillRuntimeServices;
import com.javaclaw.skill.curation.SkillCurator;
import com.javaclaw.task.sdd.run.SddTaskManager;
import com.javaclaw.task.sdd.run.SddTaskStore;
import com.javaclaw.runtime.WorkspaceContext;
import com.javaclaw.site.SiteCredentialManager;
import com.javaclaw.ui.javafx.agent.AgentRowFactory;
import com.javaclaw.ui.javafx.agent.AgentSettingsPanelFactory;
import com.javaclaw.ui.javafx.site.SiteCredentialCardFactory;
import com.javaclaw.ui.javafx.site.SiteCredentialEditorFactory;
import com.javaclaw.ui.javafx.site.SiteCredentialPanelFactory;
import com.javaclaw.ui.javafx.mcp.McpCenterViewFactory;
import com.javaclaw.ui.javafx.mcp.McpImportDialogFactory;
import com.javaclaw.ui.javafx.mcp.McpKeyValueRowFactory;
import com.javaclaw.ui.javafx.mcp.McpLogDialogFactory;
import com.javaclaw.ui.javafx.mcp.McpServerCardFactory;
import com.javaclaw.ui.javafx.mcp.McpServerEditorFactory;
import com.javaclaw.ui.javafx.mcp.McpTemplateCellFactory;
import com.javaclaw.ui.javafx.mcp.McpTemplateDialogFactory;
import com.javaclaw.ui.javafx.mcp.McpToolRowFactory;
import com.javaclaw.ui.javafx.settings.ModelSettingsSectionFactory;
import com.javaclaw.ui.javafx.settings.BehaviorSettingsSectionFactory;
import com.javaclaw.ui.javafx.settings.CommunicationSettingsSectionFactory;
import com.javaclaw.ui.javafx.settings.MaintenanceSettingsSectionFactory;
import com.javaclaw.ui.javafx.settings.TestDataCandidateCellFactory;
import com.javaclaw.ui.javafx.settings.AppearanceSettingsSectionFactory;
import com.javaclaw.ui.javafx.settings.SettingsPanelCatalogFactory;
import com.javaclaw.ui.javafx.settings.SettingsViewFactory;
import com.javaclaw.ui.javafx.schedule.ScheduleHistoryCellFactory;
import com.javaclaw.ui.javafx.schedule.ScheduleTaskCellFactory;
import com.javaclaw.ui.javafx.schedule.ScheduleViewFactory;
import com.javaclaw.ui.javafx.workflow.WorkflowConditionDialogFactory;
import com.javaclaw.ui.javafx.workflow.WorkflowDefinitionCellFactory;
import com.javaclaw.ui.javafx.workflow.WorkflowInputDialogFactory;
import com.javaclaw.ui.javafx.workflow.WorkflowNodeCardFactory;
import com.javaclaw.ui.javafx.workflow.WorkflowRunCellFactory;
import com.javaclaw.ui.javafx.workflow.WorkflowViewFactory;
import com.javaclaw.ui.javafx.task.SddBudgetDialogFactory;
import com.javaclaw.ui.javafx.task.SddDetailCellFactory;
import com.javaclaw.ui.javafx.task.SddTaskCellFactory;
import com.javaclaw.ui.javafx.task.SddTaskViewFactory;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.build.ApplicationBuildIdentity;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.http.HttpGateway;
import com.javaclaw.platform.json.JsonCodec;
import com.javaclaw.workflow.node.PublicNodeCatalog;
import com.javaclaw.workflow.runtime.NodeExecutorRegistry;
import com.javaclaw.workflow.service.SystemGraphFactory;
import com.javaclaw.workflow.service.SystemGraphRegistry;
import com.javaclaw.workflow.service.WorkflowService;
import com.javaclaw.workflow.store.GraphCheckpointStore;
import com.javaclaw.workflow.store.H2GraphCheckpointStore;
import com.javaclaw.workflow.store.H2WorkflowDefinitionStore;
import com.javaclaw.workflow.store.WorkflowDefinitionStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** 工作区对象的显式子 Context 装配，不做组件扫描。 */
@Configuration(proxyBeanMethods = false)
@Import(WorkspaceIntelligenceConfiguration.class)
public class WorkspaceSpringConfiguration {

    @Bean(destroyMethod = "close")
    com.javaclaw.framework.springai.SpringAiModelFactory springAiModelFactory(
            com.javaclaw.config.AgentConfig settings,
            io.micrometer.observation.ObservationRegistry observations,
            com.javaclaw.application.inference.InferenceCatalogPort inferenceCatalog,
            com.javaclaw.inference.api.LocalInferenceGateway inferenceGateway,
            com.fasterxml.jackson.databind.ObjectMapper json,
            WorkspaceContext workspace) {
        return new com.javaclaw.framework.springai.SpringAiModelFactory(
                settings, observations, inferenceCatalog, inferenceGateway,
                json, workspace.workspaceId());
    }

    @Bean
    com.javaclaw.framework.api.ModelPolicyRefs springAiModelPolicies(
            WorkspaceContext workspace,
            com.javaclaw.framework.springai.SpringAiModelFactory factory,
            com.javaclaw.framework.springai.SpringAiModelRegistry registry) {
        return factory.install(workspace.workspaceId(), registry);
    }

    @Bean(destroyMethod = "close")
    com.javaclaw.framework.builtin.BuiltinDefinitionRegistry.Registration builtinAgentDefinitions(
            WorkspaceContext workspace,
            com.javaclaw.framework.store.JdbcAgentDefinitionStore definitions,
            com.javaclaw.framework.api.ModelPolicyRefs models,
            com.javaclaw.framework.extension.ExtensionManager extensions,
            com.javaclaw.framework.builtin.BuiltinDefinitionRegistry builtins) {
        return builtins.register(workspace.workspaceId(), definitions, models, extensions);
    }

    @Bean
    com.javaclaw.infrastructure.agent.JdbcTokenUsageProjector tokenUsageProjector(
            WorkspaceContext workspace,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            com.fasterxml.jackson.databind.ObjectMapper json,
            java.time.Clock frameworkClock) {
        return new com.javaclaw.infrastructure.agent.JdbcTokenUsageProjector(
                workspace.workspaceId(), jdbc, transactionManager, json,
                frameworkClock, java.time.ZoneId.systemDefault());
    }

    @Bean
    com.javaclaw.agent.TokenTracker tokenTracker(
            WorkspaceContext workspace,
            JdbcTemplate jdbc,
            com.javaclaw.config.AgentConfig settings,
            com.javaclaw.infrastructure.agent.JdbcTokenUsageProjector projector) {
        return new com.javaclaw.agent.TokenTracker(
                workspace.workspaceId(), jdbc, settings, projector);
    }

    @Bean(destroyMethod = "close")
    com.javaclaw.infrastructure.agent.TokenUsageProjectionCoordinator
            tokenUsageProjectionCoordinator(
                    com.javaclaw.infrastructure.agent.JdbcTokenUsageProjector projector,
                    com.javaclaw.agent.TokenTracker tokens,
                    ManagedTaskExecutor tasks,
                    @Qualifier("workspaceTaskScope") TaskScope scope) {
        return new com.javaclaw.infrastructure.agent.TokenUsageProjectionCoordinator(
                projector, tokens, tasks, scope);
    }

    @Bean(destroyMethod = "close")
    com.javaclaw.infrastructure.agent.WorkspaceUsageRegistry.Registration tokenUsageProjection(
            WorkspaceContext workspace,
            com.javaclaw.infrastructure.agent.TokenUsageProjectionCoordinator coordinator,
            com.javaclaw.infrastructure.agent.WorkspaceUsageRegistry usageObservers) {
        return usageObservers.register(workspace.workspaceId(), coordinator);
    }

    @Bean
    com.javaclaw.framework.spi.EmbeddingModelProvider embeddingModelProvider(
            com.javaclaw.framework.springai.SpringAiModelFactory models) {
        return models.createEmbeddingProvider();
    }

    @Bean
    com.javaclaw.memory.embed.EmbeddingGateway embeddingGateway(
            com.javaclaw.framework.spi.EmbeddingModelProvider models,
            @Qualifier("workspaceTaskScope") TaskScope tasks,
            com.javaclaw.config.AgentConfig settings) {
        return new com.javaclaw.memory.embed.EmbeddingGateway(models, tasks, settings);
    }

    @Bean(destroyMethod = "close")
    TaskScope workspaceTaskScope(ManagedTaskExecutor executor, WorkspaceContext workspace) {
        return executor.openScope("workspace-" + workspace.workspaceId(), 256);
    }

    @Bean(destroyMethod = "close")
    TaskScope scheduleTaskScope(ManagedTaskExecutor executor, WorkspaceContext workspace) {
        return executor.openScope("schedule-" + workspace.workspaceId(), 1);
    }

    @Bean(destroyMethod = "close")
    com.javaclaw.framework.springai.SpringAiAnnotatedToolRegistry.Registration workspaceTools(
            WorkspaceRuntimeOptions options,
            WorkspaceContext workspace,
            SiteCredentialManager siteCredentials,
            com.javaclaw.config.AgentConfig settings,
            com.javaclaw.config.EmailConfig emailSettings,
            com.javaclaw.config.NotificationConfig notificationSettings,
            com.javaclaw.system.CommandToolFactory commandTools,
            com.javaclaw.desktop.DesktopToolFactory desktopTools,
            com.javaclaw.platform.process.ProcessRunner processes,
            com.javaclaw.agent.expert.KnowledgeExpert knowledge,
            McpConfigManager mcpConfigurations,
            McpClientManager mcpClients,
            com.javaclaw.application.plugin.PluginToolGateway pluginTools,
            SkillRuntimeServices skills,
            com.javaclaw.system.JShellRunner jshell,
            ObjectProvider<SddTaskApplicationService> sddTasks,
            ScheduleApplicationService schedules,
            JsonCodec json,
            com.javaclaw.framework.spi.ModelTaskGateway modelTasks,
            com.javaclaw.framework.springai.SpringAiAnnotatedToolRegistry registry,
            com.javaclaw.framework.spi.RunResourceRegistry runResources) {
        java.util.List<Class<?>> toolTypes = hostToolContractTypes();
        com.javaclaw.framework.springai.SpringAiAnnotatedToolRegistry.validateContracts(toolTypes);
        com.javaclaw.application.agent.ToolBundleCatalog.validateAgainst(
                com.javaclaw.framework.springai.SpringAiAnnotatedToolRegistry
                        .declaredToolNames(toolTypes));
        var objects = new com.javaclaw.application.agent.WorkspaceToolObjects(
                options.browserManager(), siteCredentials, workspace, settings,
                emailSettings, notificationSettings, commandTools, desktopTools, processes,
                knowledge, mcpConfigurations, mcpClients, pluginTools, skills, jshell,
                sddTasks::getObject, schedules, json, modelTasks,
                com.javaclaw.framework.builtin.ClarifyTools::new, runResources);
        return registry.register(workspace.workspaceId(), objects::create);
    }

    /** Complete production host-tool inventory, including framework-owned built-ins. */
    public static java.util.List<Class<?>> hostToolContractTypes() {
        java.util.ArrayList<Class<?>> result = new java.util.ArrayList<>(
                com.javaclaw.application.agent.WorkspaceToolObjects.toolContractTypes());
        result.add(com.javaclaw.framework.builtin.ClarifyTools.class);
        return java.util.List.copyOf(result);
    }

    @Bean
    KnowledgeDocumentPreferencePort knowledgeDocumentPreferencePort(
            WorkspaceContext workspace,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager) {
        return new JdbcKnowledgeDocumentPreferenceAdapter(
                workspace.workspaceId(), jdbc, transactionManager);
    }

    @Bean
    AgentDefinitionPort agentDefinitionPort(
            WorkspaceContext workspace,
            com.javaclaw.framework.api.AgentStudioClient studio,
            com.fasterxml.jackson.databind.ObjectMapper json,
            com.javaclaw.infrastructure.agent.LegacyCustomAgentDraftMigrator migration) {
        return new StudioAgentDefinitionAdapter(studio, workspace.workspaceId(), json);
    }

    @Bean
    com.javaclaw.infrastructure.agent.LegacyCustomAgentDraftMigrator legacyCustomAgentDraftMigrator(
            WorkspaceContext workspace,
            JdbcTemplate jdbc,
            com.javaclaw.framework.api.AgentStudioClient studio,
            com.fasterxml.jackson.databind.ObjectMapper json,
            com.javaclaw.framework.builtin.BuiltinDefinitionRegistry.Registration bootstrap) {
        return new com.javaclaw.infrastructure.agent.LegacyCustomAgentDraftMigrator(
                workspace.workspaceId(), jdbc, studio, json);
    }

    @Bean
    AgentPromptOptimizationPort agentPromptOptimizationPort(
            com.javaclaw.framework.api.AgentClient agents,
            WorkspaceContext workspace) {
        return new AgentPromptOptimizerAdapter(agents, workspace);
    }

    @Bean
    AgentManagementApplicationService agentManagementApplicationService(
            AgentDefinitionPort definitions,
            AgentPromptOptimizationPort optimizer) {
        return new AgentManagementUseCase(definitions, optimizer);
    }

    /** 子 Context 自有加载器，确保工作区 Controller 使用工作区 Bean 并随 Context 失效。 */
    @Bean
    @Primary
    SpringFxmlLoader workspaceFxmlLoader(
            AutowireCapableBeanFactory beanFactory,
            ApplicationBuildIdentity buildIdentity) {
        return new SpringFxmlLoader(beanFactory, buildIdentity);
    }

    @Bean
    AgentRowFactory agentRowFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader workspaceFxmlLoader) {
        return new AgentRowFactory(workspaceFxmlLoader);
    }

    @Bean
    AgentSettingsPanelFactory agentSettingsPanelFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader workspaceFxmlLoader) {
        return new AgentSettingsPanelFactory(workspaceFxmlLoader);
    }

    @Bean
    SiteCredentialManager siteCredentialManager(
            DatabaseAccess databaseAccess,
            com.javaclaw.config.CredentialCipher credentials,
            WorkspaceContext workspace) {
        return new SiteCredentialManager(
                databaseAccess, workspace.workspaceId(), credentials);
    }

    @Bean
    McpConfigManager mcpConfigManager(
            DatabaseAccess databaseAccess,
            com.javaclaw.config.CredentialCipher credentials,
            WorkspaceContext workspace,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        return new McpConfigManager(
                databaseAccess, workspace.workspaceId(), credentials, objectMapper);
    }

    @Bean
    McpClientManager mcpClientManager(
            McpConfigManager configurations,
            @Qualifier("workspaceTaskScope") TaskScope workspaceTaskScope,
            JsonCodec json) {
        return new McpClientManager(configurations, workspaceTaskScope, json);
    }

    @Bean
    McpConfigurationPort mcpConfigurationPort(McpConfigManager manager) {
        return new McpConfigManagerAdapter(manager);
    }

    @Bean
    McpRuntimePort mcpRuntimePort(McpClientManager manager) {
        return new McpClientManagerAdapter(manager);
    }

    @Bean
    McpImportPort mcpImportPort(JsonCodec json) {
        return new McpJsonImporterAdapter(new com.javaclaw.mcp.McpJsonImporter(json));
    }

    @Bean
    McpTemplatePort mcpTemplatePort() {
        return new McpTemplateLibraryAdapter();
    }

    @Bean
    McpManagementApplicationService mcpManagementApplicationService(
            McpConfigurationPort configurations,
            McpRuntimePort runtime,
            McpImportPort importer,
            McpTemplatePort templates) {
        return new McpManagementUseCase(configurations, runtime, importer, templates);
    }

    @Bean
    McpToolRowFactory mcpToolRowFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader) {
        return new McpToolRowFactory(loader);
    }

    @Bean
    McpServerCardFactory mcpServerCardFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader) {
        return new McpServerCardFactory(loader);
    }

    @Bean
    McpKeyValueRowFactory mcpKeyValueRowFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader) {
        return new McpKeyValueRowFactory(loader);
    }

    @Bean
    McpServerEditorFactory mcpServerEditorFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader,
            com.javaclaw.app.UIHelper ui) {
        return new McpServerEditorFactory(loader, ui);
    }

    @Bean
    McpTemplateCellFactory mcpTemplateCellFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader) {
        return new McpTemplateCellFactory(loader);
    }

    @Bean
    McpTemplateDialogFactory mcpTemplateDialogFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader,
            com.javaclaw.app.UIHelper ui) {
        return new McpTemplateDialogFactory(loader, ui);
    }

    @Bean
    McpImportDialogFactory mcpImportDialogFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader,
            com.javaclaw.app.UIHelper ui) {
        return new McpImportDialogFactory(loader, ui);
    }

    @Bean
    McpLogDialogFactory mcpLogDialogFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader,
            com.javaclaw.app.UIHelper ui) {
        return new McpLogDialogFactory(loader, ui);
    }

    @Bean
    McpCenterViewFactory mcpCenterViewFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader) {
        return new McpCenterViewFactory(loader);
    }

    @Bean
    ModelSettingsPort modelSettingsPort(
            com.javaclaw.config.AgentConfig config,
            com.javaclaw.application.inference.InferenceCatalogPort inference,
            WorkspaceContext workspace,
            PlatformTransactionManager transactionManager) {
        return new AgentConfigModelSettingsAdapter(
                config, inference, workspace.workspaceId(), transactionManager);
    }

    @Bean
    EmbeddingRuntimeProbePort embeddingRuntimeProbePort(
            com.javaclaw.memory.embed.EmbeddingGateway embeddings) {
        return new EmbeddingGatewayRuntimeProbeAdapter(embeddings);
    }

    @Bean
    ModelSettingsProbePort modelSettingsProbePort(
            HttpGateway http,
            JsonCodec json,
            EmbeddingRuntimeProbePort runtimeEmbedding,
            com.javaclaw.inference.api.LocalInferenceGateway inference) {
        return new HttpModelSettingsProbeAdapter(http, json, runtimeEmbedding, inference);
    }

    @Bean
    ModelSettingsApplicationService modelSettingsApplicationService(
            ModelSettingsPort settings,
            ModelSettingsProbePort probes) {
        return new ModelSettingsUseCase(settings, probes);
    }

    @Bean
    ModelSettingsSectionFactory modelSettingsSectionFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader) {
        return new ModelSettingsSectionFactory(loader);
    }

    @Bean
    CommunicationSettingsPort communicationSettingsPort(
            com.javaclaw.config.EmailConfig email,
            com.javaclaw.config.NotificationConfig notifications) {
        return new LegacyCommunicationSettingsAdapter(email, notifications);
    }

    @Bean
    EmailConnectionProbePort emailConnectionProbePort() {
        return new JakartaMailConnectionProbeAdapter();
    }

    @Bean
    CommunicationSettingsApplicationService communicationSettingsApplicationService(
            CommunicationSettingsPort settings,
            EmailConnectionProbePort probes) {
        return new CommunicationSettingsUseCase(settings, probes);
    }

    @Bean
    CommunicationSettingsSectionFactory communicationSettingsSectionFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader) {
        return new CommunicationSettingsSectionFactory(loader);
    }

    @Bean
    BehaviorSettingsPort behaviorSettingsPort(com.javaclaw.config.AgentConfig config) {
        return new AgentConfigBehaviorSettingsAdapter(config);
    }

    @Bean
    BehaviorSettingsApplicationService behaviorSettingsApplicationService(
            BehaviorSettingsPort settings) {
        return new BehaviorSettingsUseCase(settings);
    }

    @Bean
    BehaviorSettingsSectionFactory behaviorSettingsSectionFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader) {
        return new BehaviorSettingsSectionFactory(loader);
    }

    @Bean
    TestDataCandidateCellFactory testDataCandidateCellFactory() {
        return new TestDataCandidateCellFactory();
    }

    @Bean
    MaintenanceSettingsSectionFactory maintenanceSettingsSectionFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader) {
        return new MaintenanceSettingsSectionFactory(loader);
    }

    @Bean
    AppearanceSettingsSectionFactory appearanceSettingsSectionFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader) {
        return new AppearanceSettingsSectionFactory(loader);
    }

    @Bean
    SettingsPanelCatalogFactory settingsPanelCatalogFactory(
            AgentSettingsPanelFactory agents,
            SiteCredentialPanelFactory sites,
            McpCenterViewFactory mcp,
            ModelSettingsSectionFactory models,
            CommunicationSettingsSectionFactory communication,
            BehaviorSettingsSectionFactory behavior,
            MaintenanceSettingsSectionFactory maintenance,
            AppearanceSettingsSectionFactory appearance) {
        return new SettingsPanelCatalogFactory(agents, sites, mcp, models, communication,
                behavior, maintenance, appearance);
    }

    @Bean
    SettingsViewFactory settingsViewFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader,
            FxDispatcher fx) {
        return new SettingsViewFactory(loader, fx);
    }

    @Bean
    ScheduledTaskStore scheduledTaskStore(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            JsonCodec json) {
        return new ScheduledTaskStore(jdbc, transactionManager, json);
    }

    @Bean(destroyMethod = "shutdown")
    ScheduleManager scheduleManager(
            ScheduledTaskStore store,
            WorkspaceContext workspace,
            @Qualifier("scheduleTaskScope") TaskScope scheduleTasks,
            com.javaclaw.config.NotificationConfig notificationSettings,
            com.javaclaw.config.EmailConfig emailSettings) {
        return new ScheduleManager(store, workspace.workspaceId(), scheduleTasks,
                notificationSettings, emailSettings);
    }

    @Bean
    SchedulePort schedulePort(ScheduleManager manager) {
        return new ScheduleManagerAdapter(manager);
    }

    @Bean
    ScheduleApplicationService scheduleApplicationService(SchedulePort schedules) {
        return new ScheduleUseCase(schedules);
    }

    @Bean
    ScheduleTaskCellFactory scheduleTaskCellFactory() {
        return new ScheduleTaskCellFactory();
    }

    @Bean
    ScheduleHistoryCellFactory scheduleHistoryCellFactory() {
        return new ScheduleHistoryCellFactory();
    }

    @Bean
    ScheduleViewFactory scheduleViewFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader) {
        return new ScheduleViewFactory(loader);
    }

    @Bean
    SiteCredentialPort siteCredentialPort(SiteCredentialManager manager) {
        return new SiteCredentialManagerAdapter(manager);
    }

    @Bean
    SiteCredentialApplicationService siteCredentialApplicationService(SiteCredentialPort port) {
        return new SiteCredentialUseCase(port);
    }

    @Bean
    SiteCredentialCardFactory siteCredentialCardFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader) {
        return new SiteCredentialCardFactory(loader);
    }

    @Bean
    SiteCredentialEditorFactory siteCredentialEditorFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader,
            com.javaclaw.app.UIHelper ui) {
        return new SiteCredentialEditorFactory(loader, ui);
    }

    @Bean
    SiteCredentialPanelFactory siteCredentialPanelFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader) {
        return new SiteCredentialPanelFactory(loader);
    }

    @Bean
    NodeExecutorRegistry nodeExecutorRegistry(
            com.javaclaw.framework.api.AgentClient agents,
            com.javaclaw.framework.api.ToolClient tools,
            WorkspaceContext workspace,
            com.javaclaw.workflow.runtime.WorkflowExtensionPlanProvider extensions) {
        return PublicNodeCatalog.createRegistry(agents, tools, workspace, extensions);
    }

    @Bean
    GraphCheckpointStore graphCheckpointStore(
            WorkspaceContext workspace,
            DatabaseAccess databaseAccess,
            com.fasterxml.jackson.databind.ObjectMapper json) {
        return new H2GraphCheckpointStore(workspace.workspaceId(), databaseAccess, json);
    }

    @Bean
    WorkflowDefinitionStore workflowDefinitionStore(
            WorkspaceContext workspace,
            DatabaseAccess databaseAccess,
            com.fasterxml.jackson.databind.ObjectMapper json) {
        return new H2WorkflowDefinitionStore(workspace.workspaceId(), databaseAccess, json);
    }

    @Bean
    SystemGraphRegistry systemGraphRegistry() {
        SystemGraphRegistry registry = new SystemGraphRegistry();
        registry.register(SystemGraphFactory.sdd());
        return registry;
    }

    @Bean(destroyMethod = "close")
    WorkflowService workflowService(
            WorkspaceContext workspace,
            WorkspaceRuntimeOptions options,
            SiteCredentialManager siteCredentials,
            NodeExecutorRegistry nodes,
            WorkflowDefinitionStore definitions,
            GraphCheckpointStore checkpoints,
            SystemGraphRegistry systemGraphs,
            UserInteractionPort interaction,
            @Qualifier("workspaceTaskScope") TaskScope tasks,
            com.javaclaw.workflow.runtime.WorkflowExtensionPlanProvider extensionPlans) {
        return new WorkflowService(workspace.workspaceId(), options.browserManager(), siteCredentials, nodes,
                definitions, checkpoints, systemGraphs, interaction, tasks, extensionPlans);
    }

    @Bean
    WorkflowPort workflowPort(WorkflowService service) {
        return new WorkflowServiceAdapter(service);
    }

    @Bean
    WorkflowApplicationService workflowApplicationService(WorkflowPort workflows) {
        return new WorkflowUseCase(workflows);
    }

    @Bean
    SddTaskStore sddTaskStore(
            WorkspaceContext workspace,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            JsonCodec json) {
        return new SddTaskStore(workspace.workspaceId(), jdbc, transactionManager, json);
    }

    @Bean(destroyMethod = "close")
    SddTaskManager sddTaskManager(
            com.javaclaw.framework.api.AgentClient agents,
            com.javaclaw.framework.spi.ModelTaskGateway modelTasks,
            SkillRuntimeServices skills,
            SkillCurator skillCurator,
            com.javaclaw.config.AgentConfig settings,
            @Qualifier("workspaceTaskScope") TaskScope tasks,
            UserInteractionPort interaction,
            WorkflowService workflows,
            JdbcTemplate jdbc,
            JsonCodec json,
            com.javaclaw.platform.process.ProcessRunner processes,
            WorkspaceContext workspace,
            SddTaskStore store) {
        return new SddTaskManager(agents, modelTasks, workspace, skills, skillCurator, settings,
                tasks, interaction, workflows, jdbc, json, processes, workspace.workspaceId(), store);
    }

    @Bean
    SddTaskApplicationService sddTaskApplicationService(SddTaskManager tasks) {
        return new SddTaskUseCase(tasks);
    }

    @Bean
    SddTaskCellFactory sddTaskCellFactory() { return new SddTaskCellFactory(); }

    @Bean
    SddDetailCellFactory sddDetailCellFactory() { return new SddDetailCellFactory(); }

    @Bean
    SddBudgetDialogFactory sddBudgetDialogFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader) {
        return new SddBudgetDialogFactory(loader);
    }

    @Bean
    SddTaskViewFactory sddTaskViewFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader) {
        return new SddTaskViewFactory(loader);
    }

    @Bean
    WorkflowDefinitionCellFactory workflowDefinitionCellFactory() {
        return new WorkflowDefinitionCellFactory();
    }

    @Bean
    WorkflowRunCellFactory workflowRunCellFactory() {
        return new WorkflowRunCellFactory();
    }

    @Bean
    WorkflowNodeCardFactory workflowNodeCardFactory() {
        return new WorkflowNodeCardFactory();
    }

    @Bean
    WorkflowConditionDialogFactory workflowConditionDialogFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader,
            com.javaclaw.app.UIHelper ui) {
        return new WorkflowConditionDialogFactory(loader, ui);
    }

    @Bean
    WorkflowInputDialogFactory workflowInputDialogFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader,
            com.javaclaw.platform.fx.FxDispatcher fx,
            com.javaclaw.app.UIHelper ui) {
        return new WorkflowInputDialogFactory(loader, fx, ui);
    }

    @Bean
    WorkflowViewFactory workflowViewFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader,
            com.javaclaw.platform.fx.FxDispatcher fx) {
        return new WorkflowViewFactory(loader, fx);
    }

}
