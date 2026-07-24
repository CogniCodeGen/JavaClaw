package com.javaclaw.workflow.node;

import com.javaclaw.agent.AgentRuntime;
import com.javaclaw.workflow.runtime.NodeExecutorRegistry;

/** 公共节点执行器的单一注册入口。 */
public final class PublicNodeCatalog {
    private PublicNodeCatalog() {}

    public static NodeExecutorRegistry createRegistry() {
        return createRegistry(null);
    }

    public static NodeExecutorRegistry createRegistry(AgentRuntime runtime) {
        NodeExecutorRegistry registry = new NodeExecutorRegistry();
        BasicNodeExecutors.register(registry);
        registry.register(new AgentNodeExecutor(runtime));
        registry.register(new ToolNodeExecutor(runtime));
        registry.register(new SystemPipelineNodeExecutor());
        return registry;
    }
}
