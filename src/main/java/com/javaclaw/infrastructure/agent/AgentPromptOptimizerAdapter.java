package com.javaclaw.infrastructure.agent;

import com.javaclaw.agent.AgentRuntime;
import com.javaclaw.agent.expert.AgentPromptOptimizer;
import com.javaclaw.application.agent.AgentPromptOptimizationPort;

import java.util.Objects;

/** 使用当前工作区模型与 TokenTracker 执行提示词优化。 */
public final class AgentPromptOptimizerAdapter implements AgentPromptOptimizationPort {

    private final AgentRuntime runtime;

    public AgentPromptOptimizerAdapter(AgentRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public String optimize(String name, String description, String draft) {
        return new AgentPromptOptimizer(
                runtime.getModelFactory().createChatModel(), runtime.getTokenTracker())
                .optimize(name, description, draft);
    }
}
