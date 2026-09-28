package com.javaclaw.framework.core;

import java.util.concurrent.CompletionStage;

/** The sole execution boundary for Agent and Workflow tools. */
public interface ToolInvocationGateway {
    CompletionStage<ToolInvocationResult> invoke(ToolInvocationRequest request);

    /** Execute from an existing managed carrier without acquiring its executor quota twice. */
    default ToolInvocationResult invokeInline(ToolInvocationRequest request) {
        throw new UnsupportedOperationException(
                "on-demand context requires a gateway with inline execution support");
    }
}
