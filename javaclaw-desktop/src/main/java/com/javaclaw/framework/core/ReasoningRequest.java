package com.javaclaw.framework.core;

import com.javaclaw.framework.api.ResumeCommand;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunRequest;

/** One initial or resumed reasoning turn inside the same durable run. */
public record ReasoningRequest(
        RunId runId,
        ExecutionPlan plan,
        RunRequest runRequest,
        ResumeCommand resumeCommand,
        RunControl control,
        ReasoningEventSink events,
        ApprovedToolInvocation approvedToolInvocation) {

    public ReasoningRequest(
            RunId runId,
            ExecutionPlan plan,
            RunRequest runRequest,
            ResumeCommand resumeCommand,
            RunControl control,
            ReasoningEventSink events) {
        this(runId, plan, runRequest, resumeCommand, control, events, null);
    }
}
