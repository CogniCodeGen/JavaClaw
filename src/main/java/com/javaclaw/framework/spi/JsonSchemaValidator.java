package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.DefinitionValidationIssue;
import com.networknt.schema.JsonNodePath;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Shared JSON Schema Draft 2020-12 validator for Agent Studio, tools, model tasks and events.
 *
 * <p>Schemas are immutable framework/extension metadata, so compiled validators are cached by
 * their canonical JSON representation. Validation output is sorted to keep Studio and CI results
 * deterministic.</p>
 */
public final class JsonSchemaValidator {
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(
            SpecVersion.VersionFlag.V202012);

    private final ConcurrentMap<String, JsonSchema> compiled = new ConcurrentHashMap<>();

    public List<DefinitionValidationIssue> validate(
            JsonNode schema, JsonNode value, String rootPath) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(value, "value");
        String root = rootPath == null ? "" : rootPath;
        try {
            return compiled.computeIfAbsent(schema.toString(), ignored -> FACTORY.getSchema(schema))
                    .validate(value).stream()
                    .map(message -> issue(root, message))
                    .sorted(Comparator.comparing(DefinitionValidationIssue::path)
                            .thenComparing(DefinitionValidationIssue::code)
                            .thenComparing(DefinitionValidationIssue::message))
                    .toList();
        } catch (RuntimeException invalidSchema) {
            return List.of(new DefinitionValidationIssue(
                    DefinitionValidationIssue.Severity.ERROR,
                    root,
                    "schema.invalid",
                    invalidSchema.getMessage() == null
                            ? invalidSchema.getClass().getSimpleName()
                            : invalidSchema.getMessage()));
        }
    }

    /** Compiles a schema eagerly so extension staging can reject malformed metadata. */
    public void requireValidSchema(JsonNode schema, String description) {
        Objects.requireNonNull(schema, "schema");
        try {
            compiled.computeIfAbsent(schema.toString(), ignored -> FACTORY.getSchema(schema));
        } catch (RuntimeException invalidSchema) {
            throw new IllegalArgumentException("invalid JSON Schema for " + description,
                    invalidSchema);
        }
    }

    private static DefinitionValidationIssue issue(String root, ValidationMessage message) {
        return new DefinitionValidationIssue(
                DefinitionValidationIssue.Severity.ERROR,
                append(root, message.getInstanceLocation()),
                message.getCode() == null ? "schema.validation" : message.getCode(),
                message.getMessage());
    }

    private static String append(String root, JsonNodePath location) {
        StringBuilder path = new StringBuilder(root);
        for (int i = 0; i < location.getNameCount(); i++) {
            path.append('/').append(escape(String.valueOf(location.getElement(i))));
        }
        return path.toString();
    }

    private static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }
}
