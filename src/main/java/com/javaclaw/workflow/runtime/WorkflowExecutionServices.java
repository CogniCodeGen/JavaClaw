package com.javaclaw.workflow.runtime;

import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.workflow.service.SystemPipeline;

/** Explicit, closed set of runtime ports available to workflow nodes. */
public record WorkflowExecutionServices(
        ConversationCallbacks callbacks,
        SystemPipeline systemPipeline) {
    public static final WorkflowExecutionServices EMPTY =
            new WorkflowExecutionServices(null, null);

    public static WorkflowExecutionServices conversation(ConversationCallbacks callbacks) {
        return new WorkflowExecutionServices(callbacks, null);
    }

    public static WorkflowExecutionServices system(
            ConversationCallbacks callbacks, SystemPipeline pipeline) {
        return new WorkflowExecutionServices(callbacks, pipeline);
    }
}
