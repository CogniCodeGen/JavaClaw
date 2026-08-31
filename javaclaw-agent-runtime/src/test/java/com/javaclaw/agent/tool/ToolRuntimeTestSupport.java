package com.javaclaw.agent.tool;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.javaclaw.agent.runtime.ItemSink;
import com.javaclaw.agent.runtime.ToolExecutionContext;
import com.javaclaw.agent.runtime.TurnExecutionContext;
import com.javaclaw.core.api.ToolExecutionResult;
import com.javaclaw.core.api.TurnSteering;

/** Test adapter that keeps production callers on the per-Turn session API. */
public final class ToolRuntimeTestSupport {
    private ToolRuntimeTestSupport() {}

    public static List<ToolProvider> providers(List<RegisteredTool> tools) {
        return List.of(new FirstPartyToolProvider("test", tools));
    }

    public static ToolExecutionResult execute(
            GovernedToolRuntime runtime, ToolExecutionContext context, ItemSink sink) {
        TurnExecutionContext turn = new TurnExecutionContext(
                context.thread(), context.turn(), List.of(), new AtomicBoolean(), TurnSteering.NONE);
        try (TurnToolSession session = runtime.open(turn, sink)) {
            try {
                return session.execute(context.call());
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException("tool execution failed", failure);
            }
        }
    }
}
