package com.javaclaw.framework.core;

import com.javaclaw.framework.api.AgentClient;

import java.util.Objects;

/** Process-level kernel exposing the one AgentEngine to application and workflow adapters. */
public final class ExecutionKernel {
    private final AgentEngine agentEngine;

    public ExecutionKernel(AgentEngine agentEngine) {
        this.agentEngine = Objects.requireNonNull(agentEngine, "agentEngine");
    }

    public AgentClient agents() {
        return agentEngine;
    }

    public KernelDiagnostics diagnostics() {
        return new KernelDiagnostics(1, agentEngine.activeRunCount());
    }

    public record KernelDiagnostics(int agentEngineCount, int activeRunCount) {}
}
