package com.javaclaw.framework.api;

/** Correlation data for workflows and parent/sub-agent runs. */
public record RunLinkage(RunId parentRunId, String workflowRunId, String correlationId) {
    public static RunLinkage root(String correlationId) {
        return new RunLinkage(null, null, correlationId);
    }
}
