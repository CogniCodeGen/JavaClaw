package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.CapabilityId;

import java.util.Objects;

/** Agent Studio metadata and schemas for an installable capability. */
public record CapabilityDescriptor(
        CapabilityId id,
        String displayName,
        String description,
        JsonNode configurationSchema,
        JsonNode uiSchema) {
    private static final JsonSchemaValidator SCHEMAS = new JsonSchemaValidator();

    public CapabilityDescriptor {
        id = Objects.requireNonNull(id, "id");
        displayName = Objects.requireNonNull(displayName, "displayName").trim();
        description = description == null ? "" : description.trim();
        configurationSchema = Objects.requireNonNull(configurationSchema, "configurationSchema").deepCopy();
        uiSchema = Objects.requireNonNull(uiSchema, "uiSchema").deepCopy();
        if (displayName.isEmpty()) {
            throw new IllegalArgumentException("capability display name must not be blank");
        }
        SCHEMAS.requireValidSchema(configurationSchema, "capability " + id);
    }

    @Override public JsonNode configurationSchema() { return configurationSchema.deepCopy(); }
    @Override public JsonNode uiSchema() { return uiSchema.deepCopy(); }
}
