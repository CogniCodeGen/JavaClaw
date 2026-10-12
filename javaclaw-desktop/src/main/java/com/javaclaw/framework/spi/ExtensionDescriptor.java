package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable manifest for trusted host-code extensions. */
public record ExtensionDescriptor(
        String id,
        SemanticVersion version,
        String frameworkApiRange,
        String springAiRange,
        List<ExtensionDependency> dependencies,
        Set<String> conflicts,
        ExtensionScope scope,
        HotUpdateCompatibility hotUpdateCompatibility,
        int stateSchemaVersion,
        Map<String, JsonNode> schemas) {
    private static final JsonSchemaValidator SCHEMA_VALIDATOR = new JsonSchemaValidator();

    public ExtensionDescriptor {
        id = Objects.requireNonNull(id, "id").trim();
        version = Objects.requireNonNull(version, "version");
        frameworkApiRange = Objects.requireNonNull(frameworkApiRange, "frameworkApiRange").trim();
        springAiRange = Objects.requireNonNull(springAiRange, "springAiRange").trim();
        dependencies = List.copyOf(dependencies == null ? List.of() : dependencies);
        conflicts = Set.copyOf(conflicts == null ? Set.of() : conflicts);
        scope = Objects.requireNonNull(scope, "scope");
        hotUpdateCompatibility = Objects.requireNonNull(hotUpdateCompatibility, "hotUpdateCompatibility");
        Map<String, JsonNode> schemaCopies = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry
                : (schemas == null ? Map.<String, JsonNode>of() : schemas).entrySet()) {
            String name = entry.getKey();
            JsonNode schema = entry.getValue();
            JsonNode copy = Objects.requireNonNull(schema, "schema " + name).deepCopy();
            SCHEMA_VALIDATOR.requireValidSchema(copy, "extension " + id + ":" + name);
            schemaCopies.put(Objects.requireNonNull(name, "schema name"), copy);
        }
        schemas = Map.copyOf(schemaCopies);
        if (id.isEmpty() || stateSchemaVersion < 0) {
            throw new IllegalArgumentException("invalid extension descriptor");
        }
    }

    public String coordinate() {
        return id + ":" + version;
    }

    @Override
    public Map<String, JsonNode> schemas() {
        Map<String, JsonNode> copied = new LinkedHashMap<>();
        schemas.forEach((name, schema) -> copied.put(name, schema.deepCopy()));
        return Map.copyOf(copied);
    }
}
