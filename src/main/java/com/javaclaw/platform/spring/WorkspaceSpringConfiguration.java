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
import com.javaclaw.api.conversation.ModeRegistry;
import com.javaclaw.config.DatabaseAccess;
import com.javaclaw.loop.LoopService;
import com.javaclaw.infrastructure.agent.AgentPromptOptimizerAdapter;
import com.javaclaw.infrastructure.agent.CustomAgentDefinitionAdapter;
import com.javaclaw.infrastructure.site.SiteCredentialManagerAdapter;
import com.javaclaw.mode.ChatMode;
import com.javaclaw.mode.LoopMode;
import com.javaclaw.mode.PlanMode;
import com.javaclaw.mode.ShellMode;
import com.javaclaw.mode.TaskMode;
import com.javaclaw.mode.WorkflowCenterMode;
import com.javaclaw.mode.WorkflowMode;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.runtime.WorkspaceContext;
import com.javaclaw.site.SiteCredentialManager;
import com.javaclaw.ui.javafx.agent.AgentRowFactory;
import com.javaclaw.ui.javafx.agent.AgentSettingsPanelFactory;
import com.javaclaw.ui.javafx.site.SiteCredentialCardFactory;
import com.javaclaw.ui.javafx.site.SiteCredentialEditorFactory;
import com.javaclaw.ui.javafx.site.SiteCredentialPanelFactory;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
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
            SiteCredentialManager siteCredentials) {
        return new AgentRuntime(options.browserManager(), customAgents, siteCredentials);
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
