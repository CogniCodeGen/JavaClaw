package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/** Schema, Studio metadata and handler for a trusted WorkflowEngine node type. */
public record WorkflowNodeContribution(
        String type,
        JsonNode configurationSchema,
        JsonNode uiSchema,
        Handler handler) {

    private static final JsonSchemaValidator SCHEMAS = new JsonSchemaValidator();

    public WorkflowNodeContribution {
        type = Objects.requireNonNull(type, "type").trim();
        configurationSchema = Objects.requireNonNull(
                configurationSchema, "configurationSchema").deepCopy();
        uiSchema = Objects.requireNonNull(uiSchema, "uiSchema").deepCopy();
        handler = Objects.requireNonNull(handler, "handler");
        if (!type.matches("[a-z][a-z0-9_.-]*")) {
            throw new IllegalArgumentException("invalid workflow node type: " + type);
        }
        SCHEMAS.requireValidSchema(configurationSchema, "workflow node " + type);
    }

    @FunctionalInterface
    public interface Handler {
        WorkflowNodeResult execute(WorkflowNodeInvocation invocation) throws Exception;
    }

    @Override public JsonNode configurationSchema() { return configurationSchema.deepCopy(); }
    @Override public JsonNode uiSchema() { return uiSchema.deepCopy(); }
}
