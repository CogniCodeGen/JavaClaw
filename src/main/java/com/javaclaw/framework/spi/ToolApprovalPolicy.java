package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.RunRequest;

@FunctionalInterface
public interface ToolApprovalPolicy {
    ToolApprovalDecision evaluate(ToolDescriptor tool, JsonNode arguments, RunRequest runRequest);

    /** UI interaction kind persisted with a challenge; functional implementations default safely. */
    default String approvalKind(
            ToolDescriptor tool, JsonNode arguments, RunRequest runRequest) {
        return "CONFIRM";
    }
}
