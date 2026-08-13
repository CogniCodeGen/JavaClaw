package com.javaclaw.framework.core;

import java.util.concurrent.CompletionStage;

/** The sole execution boundary for Agent and Workflow tools. */
public interface ToolInvocationGateway {
    CompletionStage<ToolInvocationResult> invoke(ToolInvocationRequest request);
}
