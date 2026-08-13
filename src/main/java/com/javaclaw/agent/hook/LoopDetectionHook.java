package com.javaclaw.agent.hook;

import java.util.function.Consumer;

/**
 * Compatibility name for the legacy JavaFX loop-decision callback. ReAct loop detection now lives
 * in framework {@code RunControl} and {@code ToolInvocationGateway}; no hook or mutable Agent state
 * is created here.
 */
public final class LoopDetectionHook {
    private LoopDetectionHook() {}

    @FunctionalInterface
    public interface LoopInteractiveHandler {
        void onLoopDetected(String toolName, int repeats, Consumer<Boolean> decisionCallback);
    }
}
