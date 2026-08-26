package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.DefinitionValidationIssue;
import com.javaclaw.framework.spi.AgentStudioUiSchema;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** JavaClaw-specific constraints that apply to newly authored definitions, not legacy loading. */
final class AgentStudioAuthoringConstraints {
    private AgentStudioAuthoringConstraints() { }

    static List<DefinitionValidationIssue> validate(
            JsonNode schema, JsonNode value, String path) {
        List<DefinitionValidationIssue> issues = new ArrayList<>();
        validate(schema, value, path == null ? "" : path, issues);
        return List.copyOf(issues);
    }

    private static void validate(
            JsonNode schema, JsonNode value, String path,
            List<DefinitionValidationIssue> issues) {
        if (schema == null || schema.isMissingNode() || value == null || value.isMissingNode()) {
            return;
        }
        JsonNode maximum = schema.get(AgentStudioUiSchema.AUTHORING_MAXIMUM);
        if (maximum != null && maximum.isNumber() && value.isNumber()) {
            BigDecimal actual = value.decimalValue();
            BigDecimal limit = maximum.decimalValue();
            if (actual.compareTo(limit) > 0) {
                issues.add(new DefinitionValidationIssue(
                        DefinitionValidationIssue.Severity.ERROR,
                        path,
                        "configuration.authoring_maximum",
                        "value must be less than or equal to " + limit.toPlainString()));
            }
        }
        if (value.isObject()) {
            JsonNode properties = schema.path("properties");
            value.fields().forEachRemaining(entry -> validate(
                    properties.path(entry.getKey()), entry.getValue(),
                    append(path, entry.getKey()), issues));
        } else if (value.isArray()) {
            JsonNode items = schema.path("items");
            for (int index = 0; index < value.size(); index++) {
                validate(items, value.get(index), append(path, Integer.toString(index)), issues);
            }
        }
    }

    private static String append(String path, String segment) {
        String escaped = segment.replace("~", "~0").replace("/", "~1");
        return path == null || path.isEmpty() ? "/" + escaped : path + "/" + escaped;
    }
}
