package com.javaclaw.framework.core;

/** A started tool has no durable outcome; execution must never be silently repeated. */
public final class ToolRecoveryRequiredException extends com.javaclaw.framework.api.TurnPausedException {
    private final String stepId;
    private final java.util.List<String> requestedTools;
    private final java.util.List<String> offeredTools;
    public ToolRecoveryRequiredException(String stepId) {
        this(stepId, "Tool outcome is unknown; reconcile the side effect before continuing step " + stepId);
    }
    public ToolRecoveryRequiredException(String stepId, String reason) {
        this(stepId, reason, java.util.List.of(), java.util.List.of());
    }
    public ToolRecoveryRequiredException(String stepId, String reason,
            java.util.List<String> requestedTools, java.util.List<String> offeredTools) {
        super(reason);
        this.stepId = stepId;
        this.requestedTools = java.util.List.copyOf(requestedTools);
        this.offeredTools = java.util.List.copyOf(offeredTools);
    }
    public String stepId() { return stepId; }
    public java.util.List<String> requestedTools() { return requestedTools; }
    public java.util.List<String> offeredTools() { return offeredTools; }
}
