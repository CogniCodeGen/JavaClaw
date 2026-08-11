package com.javaclaw.platform.spring;

import com.javaclaw.agent.AgentRuntime;
import com.javaclaw.agent.ChatService;
import com.javaclaw.agent.PlanModeService;
import com.javaclaw.agent.ShellCommandService;
import com.javaclaw.agent.expert.CustomAgentConfig;
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
import com.javaclaw.api.conversation.ModeRegistry;
import com.javaclaw.config.DatabaseAccess;
import com.javaclaw.loop.LoopService;
import com.javaclaw.infrastructure.agent.AgentPromptOptimizerAdapter;
import com.javaclaw.infrastructure.agent.CustomAgentDefinitionAdapter;
import com.javaclaw.infrastructure.site.SiteCredentialManagerAdapter;
import com.javaclaw.infrastructure.mcp.McpClientManagerAdapter;
import com.javaclaw.infrastructure.mcp.McpConfigManagerAdapter;
import com.javaclaw.infrastructure.mcp.McpJsonImporterAdapter;
import com.javaclaw.infrastructure.mcp.McpTemplateLibraryAdapter;
import com.javaclaw.infrastructure.settings.AgentConfigModelSettingsAdapter;
import com.javaclaw.infrastructure.settings.AgentConfigBehaviorSettingsAdapter;
import com.javaclaw.infrastructure.settings.EmbeddingGatewayRuntimeProbeAdapter;
import com.javaclaw.infrastructure.settings.HttpModelSettingsProbeAdapter;
import com.javaclaw.infrastructure.settings.JakartaMailConnectionProbeAdapter;
import com.javaclaw.infrastructure.settings.LegacyCommunicationSettingsAdapter;
import com.javaclaw.mode.ChatMode;
import com.javaclaw.mode.LoopMode;
import com.javaclaw.mode.PlanMode;
import com.javaclaw.mode.ShellMode;
import com.javaclaw.mode.TaskMode;
import com.javaclaw.mode.WorkflowCenterMode;
import com.javaclaw.mode.WorkflowMode;
import com.javaclaw.mcp.McpClientManager;
import com.javaclaw.mcp.McpConfigManager;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskScope;
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
import com.javaclaw.platform.fxml.SpringFxmlLoader;
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
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/** 工作区对象的显式子 Context 装配，不做组件扫描。 */
@Configuration(proxyBeanMethods = false)
public class WorkspaceSpringConfiguration {

    @Bean(destroyMethod = "close")
    TaskScope workspaceTaskScope(ManagedTaskExecutor executor, WorkspaceContext workspace) {
        return executor.openScope("workspace-" + workspace.workspaceId(), 256);
    }

    @Bean(destroyMethod = "shutdown")
    AgentRuntime agentRuntime(
            WorkspaceRuntimeOptions options,
            CustomAgentConfig customAgents,
            SiteCredentialManager siteCredentials,
            McpConfigManager mcpConfigurations,
            McpClientManager mcpClients,
            TaskScope workspaceTaskScope) {
        return new AgentRuntime(options.browserManager(), customAgents, siteCredentials,
                mcpConfigurations, mcpClients, workspaceTaskScope);
    }

    @Bean
    CustomAgentConfig customAgentConfig(
            WorkspaceContext workspace,
            JdbcTemplate jdbc) {
        return new CustomAgentConfig(workspace.workspaceId(), jdbc);
    }

    @Bean
    com.javaclaw.config.AgentConfig agentConfig() {
        return com.javaclaw.config.AgentConfig.getInstance();
    }

    @Bean
    com.javaclaw.config.EmailConfig emailConfig() {
        return com.javaclaw.config.EmailConfig.getInstance();
    }

    @Bean
    com.javaclaw.config.NotificationConfig notificationConfig() {
        return com.javaclaw.config.NotificationConfig.getInstance();
    }

    @Bean
    AgentDefinitionPort agentDefinitionPort(
            CustomAgentConfig customAgents,
            com.javaclaw.config.AgentConfig settings) {
        return new CustomAgentDefinitionAdapter(customAgents, settings);
    }

    @Bean
    AgentPromptOptimizationPort agentPromptOptimizationPort(AgentRuntime runtime) {
        return new AgentPromptOptimizerAdapter(runtime);
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
    SpringFxmlLoader workspaceFxmlLoader(AutowireCapableBeanFactory beanFactory) {
        return new SpringFxmlLoader(beanFactory);
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
            WorkspaceContext workspace) {
        return new SiteCredentialManager(databaseAccess, workspace.workspaceId());
    }

    @Bean
    McpConfigManager mcpConfigManager(
            DatabaseAccess databaseAccess,
            WorkspaceContext workspace) {
        return new McpConfigManager(databaseAccess, workspace.workspaceId());
    }

    @Bean
    McpClientManager mcpClientManager(
            McpConfigManager configurations,
            TaskScope workspaceTaskScope) {
        return new McpClientManager(configurations, workspaceTaskScope);
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
    McpImportPort mcpImportPort() {
        return new McpJsonImporterAdapter();
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
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader) {
        return new McpServerEditorFactory(loader);
    }

    @Bean
    McpTemplateCellFactory mcpTemplateCellFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader) {
        return new McpTemplateCellFactory(loader);
    }

    @Bean
    McpTemplateDialogFactory mcpTemplateDialogFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader) {
        return new McpTemplateDialogFactory(loader);
    }

    @Bean
    McpImportDialogFactory mcpImportDialogFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader) {
        return new McpImportDialogFactory(loader);
    }

    @Bean
    McpLogDialogFactory mcpLogDialogFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader) {
        return new McpLogDialogFactory(loader);
    }

    @Bean
    McpCenterViewFactory mcpCenterViewFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader) {
        return new McpCenterViewFactory(loader);
    }

    @Bean
    ModelSettingsPort modelSettingsPort(com.javaclaw.config.AgentConfig config) {
        return new AgentConfigModelSettingsAdapter(config);
    }

    @Bean
    EmbeddingRuntimeProbePort embeddingRuntimeProbePort(AgentRuntime runtime) {
        return new EmbeddingGatewayRuntimeProbeAdapter(runtime.getEmbeddingGateway());
    }

    @Bean
    ModelSettingsProbePort modelSettingsProbePort(
            HttpGateway http,
            JsonCodec json,
            EmbeddingRuntimeProbePort runtimeEmbedding) {
        return new HttpModelSettingsProbeAdapter(http, json, runtimeEmbedding);
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
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader) {
        return new SiteCredentialEditorFactory(loader);
    }

    @Bean
    SiteCredentialPanelFactory siteCredentialPanelFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader) {
        return new SiteCredentialPanelFactory(loader);
    }

    @Bean
    NodeExecutorRegistry nodeExecutorRegistry(AgentRuntime runtime) {
        return PublicNodeCatalog.createRegistry(runtime);
    }

    @Bean
    GraphCheckpointStore graphCheckpointStore(
            WorkspaceContext workspace, DatabaseAccess databaseAccess) {
        return new H2GraphCheckpointStore(workspace.workspaceId(), databaseAccess);
    }

    @Bean
    WorkflowDefinitionStore workflowDefinitionStore(
            WorkspaceContext workspace, DatabaseAccess databaseAccess) {
        return new H2WorkflowDefinitionStore(workspace.workspaceId(), databaseAccess);
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
            AgentRuntime runtime,
            NodeExecutorRegistry nodes,
            WorkflowDefinitionStore definitions,
            GraphCheckpointStore checkpoints,
            SystemGraphRegistry systemGraphs) {
        return new WorkflowService(workspace.workspaceId(), runtime, nodes,
                definitions, checkpoints, systemGraphs);
    }

    @Bean(destroyMethod = "shutdown")
    ChatService chatService(
            AgentRuntime runtime, WorkflowService workflows, TaskScope taskScope) {
        return new ChatService(runtime, workflows, taskScope);
    }

    @Bean(destroyMethod = "shutdown")
    PlanModeService planModeService(AgentRuntime runtime, WorkflowService workflows) {
        return new PlanModeService(runtime, workflows);
    }

    @Bean(destroyMethod = "shutdown")
    LoopService loopService(AgentRuntime runtime, WorkflowService workflows) {
        return new LoopService(runtime, workflows);
    }

    @Bean
    ShellCommandService shellCommandService(
            AgentManagementApplicationService agents,
            TaskScope taskScope) {
        return new ShellCommandService(agents, taskScope);
    }

    @Bean
    ChatMode chatMode(ChatService service) {
        return new ChatMode(service);
    }

    @Bean
    PlanMode planMode(PlanModeService service) {
        return new PlanMode(service);
    }

    @Bean
    LoopMode loopMode(LoopService service) {
        return new LoopMode(service);
    }

    @Bean
    WorkflowMode workflowMode(WorkflowService service) {
        return new WorkflowMode(service);
    }

    @Bean
    ShellMode shellMode(ShellCommandService service) {
        return new ShellMode(service);
    }

    @Bean
    TaskMode taskMode(WorkspaceRuntimeOptions options) {
        return new TaskMode(options.openTaskView());
    }

    @Bean
    WorkflowCenterMode workflowCenterMode(WorkspaceRuntimeOptions options) {
        return new WorkflowCenterMode(options.openWorkflowView(), options.closeWorkflowView());
    }

    @Bean(destroyMethod = "shutdownAll")
    ModeRegistry modeRegistry(
            WorkspaceRuntimeOptions options,
            ChatMode chat,
            PlanMode plan,
            LoopMode loop,
            WorkflowMode workflow,
            ShellMode shell,
            TaskMode task,
            WorkflowCenterMode workflowCenter) {
        ModeRegistry registry = new ModeRegistry(options.disabledModes());
        registry.register(chat);
        registry.register(plan);
        registry.register(loop);
        registry.register(workflow);
        registry.register(shell);
        registry.register(task);
        registry.register(workflowCenter);
        return registry;
    }
}
