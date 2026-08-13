package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/** Serializable Workflow Studio template; it contains no runtime service objects. */
public record WorkflowTemplateContribution(
        String id, String displayName, JsonNode definition) {
    public WorkflowTemplateContribution {
        id = Objects.requireNonNull(id, "id").trim();
        displayName = Objects.requireNonNull(displayName, "displayName").trim();
        definition = Objects.requireNonNull(definition, "definition").deepCopy();
        if (id.isEmpty() || displayName.isEmpty()) {
            throw new IllegalArgumentException("workflow template values must not be blank");
        }
        if (!definition.isObject()) {
            throw new IllegalArgumentException("workflow template definition must be a JSON object");
        }
    }
    @Override public JsonNode definition() { return definition.deepCopy(); }
}
