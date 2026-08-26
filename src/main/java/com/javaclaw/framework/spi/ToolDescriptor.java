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
        ToolResultClass resultClass) {
    private static final JsonSchemaValidator SCHEMAS = new JsonSchemaValidator();

    public ToolDescriptor {
        name = Objects.requireNonNull(name, "name").trim();
        description = Objects.requireNonNull(description, "description").trim();
        inputSchema = Objects.requireNonNull(inputSchema, "inputSchema").deepCopy();
        group = Objects.requireNonNull(group, "group").trim();
        requiredPermissions = requiredPermissions == null ? PermissionSet.NONE : requiredPermissions;
        resultClass = resultClass == null ? ToolResultClass.DEFAULT : resultClass;
        if (name.isEmpty()) throw new IllegalArgumentException("tool name must not be blank");
        if (group.isEmpty()) throw new IllegalArgumentException("tool group must not be blank");
        SCHEMAS.requireValidSchema(inputSchema, "tool " + name);
    }

    /** Source-compatible constructor for extension tools compiled against the initial 3.0 API. */
    public ToolDescriptor(String name, String description, JsonNode inputSchema,
                          String group, PermissionSet requiredPermissions, boolean idempotent) {
        this(name, description, inputSchema, group, requiredPermissions, idempotent,
                ToolResultClass.DEFAULT);
    }

    /** Source-compatible constructor for extension tools compiled against the initial 3.0 API. */
    public ToolDescriptor(String name, String description, JsonNode inputSchema,
                          PermissionSet requiredPermissions, boolean idempotent) {
        this(name, description, inputSchema, "extension", requiredPermissions, idempotent,
                ToolResultClass.DEFAULT);
    }

    @Override public JsonNode inputSchema() { return inputSchema.deepCopy(); }
}
