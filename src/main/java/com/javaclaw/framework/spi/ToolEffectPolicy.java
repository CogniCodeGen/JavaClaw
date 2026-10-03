package com.javaclaw.framework.spi;

/** How the framework treats a tool's side effect during repair and recovery. */
public enum ToolEffectPolicy {
    /** Existing conservative fingerprint/effect-key behavior for external tools. */
    LEGACY,
    /** Repeated calls ensure a resource or capability state without replaying user input. */
    ENSURE_STATE,
    /** Discover existing state before dispatch; only a confirmed delivery may be revisited. */
    DISCOVERY_GATED,
    /** Input is one shot; an uncertain delivery blocks that resource until reconciliation. */
    OBSERVATION_GATED
}
