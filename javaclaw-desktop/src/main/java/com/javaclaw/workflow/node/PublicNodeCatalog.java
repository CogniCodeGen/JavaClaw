package com.javaclaw.workflow.node;

import com.javaclaw.workflow.runtime.NodeExecutorRegistry;

/** 公共节点执行器的单一注册入口。 */
public final class PublicNodeCatalog {
    private PublicNodeCatalog() {}

    public static NodeExecutorRegistry createRegistry() {
        NodeExecutorRegistry registry = new NodeExecutorRegistry();
        BasicNodeExecutors.register(registry);
        registry.register(new AgentNodeExecutor());
        registry.register(new ToolNodeExecutor());
        registry.register(new SystemPipelineNodeExecutor());
        return registry;
    }

    public static NodeExecutorRegistry createRegistry(
            com.javaclaw.framework.api.AgentClient agents,
            com.javaclaw.framework.api.ToolClient tools,
            com.javaclaw.runtime.WorkspaceContext workspace,
            com.javaclaw.workflow.runtime.WorkflowExtensionPlanProvider extensions) {
        NodeExecutorRegistry registry = new NodeExecutorRegistry();
        BasicNodeExecutors.register(registry);
        registry.register(new AgentNodeExecutor(agents, workspace));
        registry.register(new ToolNodeExecutor(tools, workspace));
        registry.register(new SystemPipelineNodeExecutor());
        registry.registerResolver(extensions::currentExecutor);
        return registry;
    }

    public static NodeExecutorRegistry createRegistry(
            com.javaclaw.framework.api.AgentClient agents,
            com.javaclaw.framework.api.ToolClient tools,
            com.javaclaw.runtime.WorkspaceContext workspace) {
        return createRegistry(agents, tools, workspace,
                com.javaclaw.workflow.runtime.WorkflowExtensionPlanProvider.NONE);
    }
}
