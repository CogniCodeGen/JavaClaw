package com.javaclaw.platform.spring;

import com.javaclaw.agent.AgentRuntime;
import com.javaclaw.agent.ChatService;
import com.javaclaw.agent.PlanModeService;
import com.javaclaw.agent.ShellCommandService;
import com.javaclaw.api.conversation.ModeRegistry;
import com.javaclaw.config.DatabaseAccess;
import com.javaclaw.loop.LoopService;
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
import com.javaclaw.workflow.node.PublicNodeCatalog;
import com.javaclaw.workflow.runtime.NodeExecutorRegistry;
import com.javaclaw.workflow.service.SystemGraphFactory;
import com.javaclaw.workflow.service.SystemGraphRegistry;
import com.javaclaw.workflow.service.WorkflowService;
import com.javaclaw.workflow.store.GraphCheckpointStore;
import com.javaclaw.workflow.store.H2GraphCheckpointStore;
import com.javaclaw.workflow.store.H2WorkflowDefinitionStore;
import com.javaclaw.workflow.store.WorkflowDefinitionStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 工作区对象的显式子 Context 装配，不做组件扫描。 */
@Configuration(proxyBeanMethods = false)
public class WorkspaceSpringConfiguration {

    @Bean(destroyMethod = "close")
    TaskScope workspaceTaskScope(ManagedTaskExecutor executor, WorkspaceContext workspace) {
        return executor.openScope("workspace-" + workspace.workspaceId(), 256);
    }

    @Bean(destroyMethod = "shutdown")
    AgentRuntime agentRuntime(WorkspaceRuntimeOptions options) {
        return new AgentRuntime(options.browserManager());
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
    ChatService chatService(AgentRuntime runtime, WorkflowService workflows) {
        return new ChatService(runtime, workflows);
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
    ShellCommandService shellCommandService(ChatService chatService) {
        return new ShellCommandService(chatService);
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
