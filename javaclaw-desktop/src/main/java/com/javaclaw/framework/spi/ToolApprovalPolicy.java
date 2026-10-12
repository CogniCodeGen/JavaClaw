package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.RunRequest;

@FunctionalInterface
public interface ToolApprovalPolicy {
    ToolApprovalDecision evaluate(ToolDescriptor tool, JsonNode arguments, RunRequest runRequest);

    /** Implementations may inspect host-owned tool identity; descriptor-only policies stay compatible. */
    default ToolApprovalDecision evaluate(
            FrameworkTool tool, JsonNode arguments, RunRequest runRequest) {
        return evaluate(tool.descriptor(), arguments, runRequest);
    }

    /** UI interaction kind persisted with a challenge; functional implementations default safely. */
    default String approvalKind(
            ToolDescriptor tool, JsonNode arguments, RunRequest runRequest) {
        return "CONFIRM";
    }

    default String approvalKind(
            FrameworkTool tool, JsonNode arguments, RunRequest runRequest) {
        return approvalKind(tool.descriptor(), arguments, runRequest);
    }
}
