package com.javaclaw.agent.kernel;

import java.util.Objects;

import com.javaclaw.agent.runtime.ItemSink;
import com.javaclaw.agent.runtime.TurnExecutionContext;
import com.javaclaw.core.api.ProfileKind;
import com.javaclaw.sandbox.api.SandboxMode;

/** Validates a resolved Profile and routes every mode into the single shared Agent loop. */
public final class RoutingAgentKernel implements AgentKernel {
    private final AgentKernel agentLoop;

    /** 绑定唯一 Agent Loop；路由只验证已解析 Profile，不创建独立执行引擎。 */
    public RoutingAgentKernel(AgentKernel agentLoop) {
        this.agentLoop = Objects.requireNonNull(agentLoop, "agentLoop");
    }

    @Override
    public void execute(TurnExecutionContext context, ItemSink sink) throws Exception {
        String configured = context.turn().config().attributes().getOrDefault("profileKind", ProfileKind.CHAT.name());
        ProfileKind kind;
        try {
            kind = ProfileKind.valueOf(configured);
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("unknown resolved profile kind: " + configured, failure);
        }
        if (kind == ProfileKind.PLAN && context.turn().config().sandboxPolicy().mode() != SandboxMode.READ_ONLY) {
            throw new IllegalStateException("PLAN profile resolved to a writable sandbox");
        }
        agentLoop.execute(context, sink);
    }
}
