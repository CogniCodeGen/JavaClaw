package com.javaclaw.framework.api;

import com.fasterxml.jackson.databind.JsonNode;

/** Dynamic Agent Studio form contributed by an installed extension. */
public record CapabilityForm(
        CapabilityId id,
        String extensionId,
        String displayName,
        String description,
        JsonNode configurationSchema,
        JsonNode uiSchema) {
    public CapabilityForm {
        configurationSchema = configurationSchema.deepCopy();
        uiSchema = uiSchema.deepCopy();
    }
    @Override public JsonNode configurationSchema() { return configurationSchema.deepCopy(); }
    @Override public JsonNode uiSchema() { return uiSchema.deepCopy(); }
}
