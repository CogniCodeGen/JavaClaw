package com.javaclaw.framework.api;

import java.util.concurrent.CompletionStage;

/** Product/workflow port for a direct tool node; execution still uses ToolInvocationGateway. */
public interface ToolClient {
    CompletionStage<ToolCallOutcome> invoke(ToolCallRequest request);
}
