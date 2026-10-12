package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.PermissionSet;

import java.util.Objects;

public record ToolDescriptor(
        String name,
        String description,
        JsonNode inputSchema,
        String group,
        PermissionSet requiredPermissions,
        boolean idempotent,
        ToolEffectPolicy effectPolicy) {
    private static final JsonSchemaValidator SCHEMAS = new JsonSchemaValidator();

    public ToolDescriptor {
        name = Objects.requireNonNull(name, "name").trim();
        description = Objects.requireNonNull(description, "description").trim();
        inputSchema = Objects.requireNonNull(inputSchema, "inputSchema").deepCopy();
        group = Objects.requireNonNull(group, "group").trim();
        requiredPermissions = requiredPermissions == null ? PermissionSet.NONE : requiredPermissions;
        effectPolicy = effectPolicy == null ? ToolEffectPolicy.LEGACY : effectPolicy;
        if (name.isEmpty()) throw new IllegalArgumentException("tool name must not be blank");
        if (group.isEmpty()) throw new IllegalArgumentException("tool group must not be blank");
        SCHEMAS.requireValidSchema(inputSchema, "tool " + name);
    }

    public ToolDescriptor(String name, String description, JsonNode inputSchema, String group,
                          PermissionSet requiredPermissions, boolean idempotent) {
        this(name, description, inputSchema, group, requiredPermissions,
                idempotent, ToolEffectPolicy.LEGACY);
    }

    @Override public JsonNode inputSchema() { return inputSchema.deepCopy(); }
}
