package com.javaclaw.framework.core;

/** A started tool has no durable outcome; execution must never be silently repeated. */
public final class ToolRecoveryRequiredException extends com.javaclaw.framework.api.TurnPausedException {
    private final String stepId;
    public ToolRecoveryRequiredException(String stepId) {
        this(stepId, "Tool outcome is unknown; reconcile the side effect before continuing step " + stepId);
    }
    public ToolRecoveryRequiredException(String stepId, String reason) {
        super(reason);
        this.stepId = stepId;
    }
    public String stepId() { return stepId; }
}
