package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.RunRequest;

/** Restricts which tools are visible/executable; it cannot grant a permission. */
@FunctionalInterface
public interface ToolPolicy {
    ToolPolicyDecision evaluate(
            ToolDescriptor tool, JsonNode configuration, RunRequest request);
}
