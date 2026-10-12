package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/** JSON schema registered for one namespaced event version. */
public record EventTypeDescriptor(String type, int schemaVersion, JsonNode jsonSchema) {
    private static final JsonSchemaValidator SCHEMAS = new JsonSchemaValidator();

    public EventTypeDescriptor {
        type = Objects.requireNonNull(type, "type").trim();
        jsonSchema = Objects.requireNonNull(jsonSchema, "jsonSchema").deepCopy();
        if (type.isEmpty() || schemaVersion < 1) {
            throw new IllegalArgumentException("invalid event type descriptor");
        }
        SCHEMAS.requireValidSchema(jsonSchema, "event " + type + "@" + schemaVersion);
    }

    @Override public JsonNode jsonSchema() { return jsonSchema.deepCopy(); }
}
