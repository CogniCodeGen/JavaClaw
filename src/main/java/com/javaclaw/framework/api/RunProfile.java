package com.javaclaw.framework.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.Map;
import java.util.Objects;

/** Published execution policy layered over an AgentDefinition. */
public record RunProfile(
        String id,
        long version,
        String name,
        PermissionSet permissionCeiling,
        RunBudget budget,
        Map<CapabilityId, JsonNode> capabilityOverrides,
        JsonNode failurePolicy,
        String checksum) {

    public RunProfile {
        id = required(id, "id");
        name = required(name, "name");
        checksum = required(checksum, "checksum");
        if (version < 1) {
            throw new IllegalArgumentException("profile version must be positive");
        }
        permissionCeiling = permissionCeiling == null
                ? PermissionSet.UNRESTRICTED : permissionCeiling;
        budget = budget == null ? RunBudget.UNBOUNDED : budget;
        java.util.LinkedHashMap<CapabilityId, JsonNode> overrides = new java.util.LinkedHashMap<>();
        (capabilityOverrides == null ? Map.<CapabilityId, JsonNode>of() : capabilityOverrides)
                .forEach((capabilityId, value) -> overrides.put(capabilityId, value.deepCopy()));
        capabilityOverrides = Map.copyOf(overrides);
        failurePolicy = failurePolicy == null
                ? JsonNodeFactory.instance.objectNode() : failurePolicy.deepCopy();
    }
    @Override public Map<CapabilityId, JsonNode> capabilityOverrides() {
        java.util.LinkedHashMap<CapabilityId, JsonNode> copied = new java.util.LinkedHashMap<>();
        capabilityOverrides.forEach((capabilityId, value) -> copied.put(capabilityId, value.deepCopy()));
        return Map.copyOf(copied);
    }
    @Override public JsonNode failurePolicy() { return failurePolicy.deepCopy(); }

    private static String required(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
