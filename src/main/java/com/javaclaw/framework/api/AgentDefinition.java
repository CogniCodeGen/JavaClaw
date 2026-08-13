package com.javaclaw.framework.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Published, schema-driven Agent identity and capability configuration. */
public record AgentDefinition(
        String id,
        long version,
        String name,
        String modelPolicyRef,
        Map<String, String> promptSections,
        Map<CapabilityId, JsonNode> capabilityBindings,
        JsonNode toolPolicy,
        JsonNode permissionPolicy,
        RunBudget budgetPolicy,
        JsonNode outputContract,
        Map<String, String> compatibleExtensionRanges,
        String checksum) {

    public AgentDefinition {
        id = required(id, "id");
        name = required(name, "name");
        modelPolicyRef = required(modelPolicyRef, "modelPolicyRef");
        checksum = required(checksum, "checksum");
        if (version < 1) {
            throw new IllegalArgumentException("definition version must be positive");
        }
        promptSections = Map.copyOf(promptSections == null ? Map.of() : promptSections);
        LinkedHashMap<CapabilityId, JsonNode> bindings = new LinkedHashMap<>();
        if (capabilityBindings != null) {
            capabilityBindings.forEach((key, value) -> bindings.put(key, value.deepCopy()));
        }
        capabilityBindings = Map.copyOf(bindings);
        toolPolicy = copyOrEmpty(toolPolicy);
        permissionPolicy = copyOrEmpty(permissionPolicy);
        budgetPolicy = budgetPolicy == null ? RunBudget.UNBOUNDED : budgetPolicy;
        outputContract = copyOrEmpty(outputContract);
        compatibleExtensionRanges = Map.copyOf(
                compatibleExtensionRanges == null ? Map.of() : compatibleExtensionRanges);
    }

    public List<CapabilityId> capabilities() {
        return capabilityBindings.keySet().stream().sorted().toList();
    }

    @Override public Map<CapabilityId, JsonNode> capabilityBindings() {
        LinkedHashMap<CapabilityId, JsonNode> copied = new LinkedHashMap<>();
        capabilityBindings.forEach((id, value) -> copied.put(id, value.deepCopy()));
        return Map.copyOf(copied);
    }
    @Override public JsonNode toolPolicy() { return toolPolicy.deepCopy(); }
    @Override public JsonNode permissionPolicy() { return permissionPolicy.deepCopy(); }
    @Override public JsonNode outputContract() { return outputContract.deepCopy(); }

    private static JsonNode copyOrEmpty(JsonNode value) {
        return value == null ? JsonNodeFactory.instance.objectNode() : value.deepCopy();
    }

    private static String required(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
