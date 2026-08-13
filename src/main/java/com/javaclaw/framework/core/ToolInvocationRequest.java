package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.spi.FrameworkTool;
import com.javaclaw.framework.spi.ToolExecutionContext;
import com.javaclaw.framework.spi.ToolPolicy;
import com.javaclaw.framework.spi.ToolResultPostProcessor;

import java.util.List;

public record ToolInvocationRequest(
        FrameworkTool tool,
        JsonNode arguments,
        ToolExecutionContext context,
        RunRequest runRequest,
        PermissionSet effectivePermissions,
        JsonNode toolPolicyConfiguration,
        List<ToolPolicy> toolPolicies,
        List<ToolResultPostProcessor> resultPostProcessors,
        RunControl control,
        ReasoningEventSink events) {
    public ToolInvocationRequest {
        arguments = arguments.deepCopy();
        toolPolicyConfiguration = toolPolicyConfiguration.deepCopy();
        toolPolicies = List.copyOf(toolPolicies);
        resultPostProcessors = List.copyOf(resultPostProcessors);
    }
}
