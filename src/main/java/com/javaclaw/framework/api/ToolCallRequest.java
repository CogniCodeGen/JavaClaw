package com.javaclaw.framework.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.spi.CancellationToken;

import java.util.Objects;
import java.util.Set;

/** Direct workflow/tool-node invocation command with an explicit authorization ceiling. */
public record ToolCallRequest(
        RunScope scope,
        InvocationSource source,
        String toolName,
        JsonNode arguments,
        PermissionSet permissionCeiling,
        RunBudget budget,
        String correlationId,
        CancellationToken cancellation,
        Set<String> allowedToolGroups) {
    public ToolCallRequest {
        scope = Objects.requireNonNull(scope, "scope");
        source = Objects.requireNonNull(source, "source");
        toolName = Objects.requireNonNull(toolName, "toolName").trim();
        arguments = Objects.requireNonNull(arguments, "arguments").deepCopy();
        permissionCeiling = permissionCeiling == null ? PermissionSet.NONE : permissionCeiling;
        budget = budget == null ? RunBudget.UNBOUNDED : budget;
        cancellation = cancellation == null ? () -> false : cancellation;
        allowedToolGroups = Set.copyOf(Objects.requireNonNull(
                allowedToolGroups, "allowedToolGroups"));
        if (toolName.isEmpty()) throw new IllegalArgumentException("toolName must not be blank");
    }

    public ToolCallRequest(
            RunScope scope, InvocationSource source, String toolName, JsonNode arguments,
            PermissionSet permissionCeiling, RunBudget budget, String correlationId,
            CancellationToken cancellation) {
        this(scope, source, toolName, arguments, permissionCeiling, budget, correlationId,
                cancellation, Set.of());
    }
    @Override public JsonNode arguments() { return arguments.deepCopy(); }
}
