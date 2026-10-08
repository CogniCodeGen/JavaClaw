package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.RunRequest;

/** Shared model-exposure and execution-time authorization for explicit tool groups. */
public final class ToolGroupAccess {
    public static final String ATTRIBUTE = "framework.allowedToolGroups";
    public static final String DELEGATABLE_ATTRIBUTE = "framework.interaction.delegatableToolGroups";

    private ToolGroupAccess() { }

    public static boolean allows(RunRequest request, String group) {
        return allows(request.attributes().get(ATTRIBUTE), group, ATTRIBUTE);
    }

    /** Only a compiled main role consumes the host-derived delegation grant. */
    public static boolean allowsDelegation(RunRequest request, String group) {
        if (!InteractionExecutionPolicy.isMain(request)) return allows(request, group);
        JsonNode configured = request.attributes().get(DELEGATABLE_ATTRIBUTE);
        return configured == null ? allows(request, group) : allows(configured, group, DELEGATABLE_ATTRIBUTE);
    }

    private static boolean allows(JsonNode configured, String group, String attribute) {
        if (configured == null) return true;
        if (!configured.isArray()) {
            throw new SecurityException(attribute + " must be an array");
        }
        for (JsonNode value : configured) {
            if (value.isTextual() && (value.asText().equals("*") || value.asText().equals(group))) {
                return true;
            }
        }
        return false;
    }
}
