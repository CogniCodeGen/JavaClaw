package com.javaclaw.framework.api;

/** The sole application-facing entry into Agent execution. */
public interface AgentClient {
    RunHandle start(RunRequest request);

    RunHandle resume(RunId runId, ResumeCommand command);

    boolean cancel(RunId runId, CancelReason reason);

    RunSnapshot get(RunId runId);
}
