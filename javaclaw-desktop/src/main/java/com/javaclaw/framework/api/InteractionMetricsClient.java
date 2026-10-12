package com.javaclaw.framework.api;

/** Read-only journal metrics; this interface never starts a provider call or re-evaluates a task. */
public interface InteractionMetricsClient {
    InteractionMetrics metrics(RunId parentRunId);
}
