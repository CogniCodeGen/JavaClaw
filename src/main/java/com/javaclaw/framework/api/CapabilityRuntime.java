package com.javaclaw.framework.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

/** Read-only access to compiler-normalized capability configuration on a RunRequest. */
public final class CapabilityRuntime {
    private CapabilityRuntime() {}

    public static JsonNode configuration(RunRequest request, String capabilityId) {
        JsonNode all = request.attributes().get("framework.compiledCapabilities");
        if (all == null || !all.isObject()) return MissingNode.getInstance();
        JsonNode value = all.get(capabilityId);
        return value == null ? MissingNode.getInstance() : value.deepCopy();
    }

    public static boolean enabled(RunRequest request, String capabilityId) {
        return !configuration(request, capabilityId).isMissingNode();
    }
}
