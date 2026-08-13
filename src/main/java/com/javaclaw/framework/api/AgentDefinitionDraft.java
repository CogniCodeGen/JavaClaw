package com.javaclaw.framework.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Editable Agent Studio document. It has no executable version or checksum. */
public record AgentDefinitionDraft(
        String id,
        String name,
        String modelPolicyRef,
        Map<String, String> promptSections,
        Map<CapabilityId, JsonNode> capabilityBindings,
        JsonNode toolPolicy,
        JsonNode permissionPolicy,
        RunBudget budgetPolicy,
        JsonNode outputContract,
        Map<String, String> compatibleExtensionRanges) {

    public AgentDefinitionDraft {
        id = required(id, "id");
        name = required(name, "name");
        modelPolicyRef = required(modelPolicyRef, "modelPolicyRef");
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
        if (value.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
