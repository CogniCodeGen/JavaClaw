package com.javaclaw.framework.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class BuiltinSchemas {
    private BuiltinSchemas() {}

    public static ObjectNode objectSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("type", "object");
        schema.putObject("properties");
        schema.put("additionalProperties", false);
        return schema;
    }

    public static ObjectNode booleanProperty(ObjectNode schema, String name, boolean defaultValue) {
        ObjectNode property = (ObjectNode) schema.withObject("/properties").putObject(name);
        property.put("type", "boolean");
        property.put("default", defaultValue);
        return schema;
    }

    public static ObjectNode integerProperty(
            ObjectNode schema, String name, int defaultValue, int minimum, int maximum) {
        ObjectNode property = (ObjectNode) schema.withObject("/properties").putObject(name);
        property.put("type", "integer");
        property.put("default", defaultValue);
        property.put("minimum", minimum);
        property.put("maximum", maximum);
        return schema;
    }

    public static ObjectNode authoringMaximum(ObjectNode schema, String name, int maximum) {
        JsonNode property = schema.path("properties").path(name);
        if (!(property instanceof ObjectNode object)) {
            throw new IllegalArgumentException("unknown integer property: " + name);
        }
        object.put(com.javaclaw.framework.spi.AgentStudioUiSchema.AUTHORING_MAXIMUM, maximum);
        return schema;
    }

    public static JsonNode ui(String group, int order) {
        ObjectNode ui = JsonNodeFactory.instance.objectNode();
        ui.put("group", group);
        ui.put("order", order);
        return ui;
    }
}
