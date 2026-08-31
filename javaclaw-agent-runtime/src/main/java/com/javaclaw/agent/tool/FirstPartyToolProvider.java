package com.javaclaw.agent.tool;

import java.util.List;

import com.javaclaw.agent.runtime.TurnExecutionContext;

/** Immutable provider for built-in JavaClaw tools. */
public final class FirstPartyToolProvider implements ToolProvider {
    private final String id;
    private final List<RegisteredTool> tools;

    /** 固定第一方 Provider 标识和工具列表；不在构造时执行任何工具。 */
    public FirstPartyToolProvider(String id, List<RegisteredTool> tools) {
        this.id = ToolProviderSupport.requireId(id);
        this.tools = List.copyOf(tools);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public List<RegisteredTool> tools(TurnExecutionContext context) {
        return tools;
    }

    @Override
    public List<com.javaclaw.core.api.ToolDescriptor> catalog() {
        return tools.stream().map(RegisteredTool::descriptor).toList();
    }
}
