package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.RunRequest;

/** Shared model-exposure and execution-time authorization for explicit tool groups. */
public final class ToolGroupAccess {
    public static final String ATTRIBUTE = "framework.allowedToolGroups";

    private ToolGroupAccess() { }

    public static boolean allows(RunRequest request, String group) {
        JsonNode configured = request.attributes().get(ATTRIBUTE);
        if (configured == null) return true;
        if (!configured.isArray()) {
            throw new SecurityException(ATTRIBUTE + " must be an array");
        }
        for (JsonNode value : configured) {
            if (value.isTextual() && (value.asText().equals("*") || value.asText().equals(group))) {
                return true;
            }
        }
        return false;
    }
}
