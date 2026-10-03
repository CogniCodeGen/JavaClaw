package com.javaclaw.task.sdd.agent;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.spi.JsonSchemaValidator;
import org.springframework.ai.converter.BeanOutputConverter;

import java.io.IOException;
import java.util.Objects;
import java.util.stream.Collectors;

/** One strict schema for both the SDD model instruction and the accepted result. */
final class SddStructuredOutput<T> {
    private static final JsonSchemaValidator VALIDATOR = new JsonSchemaValidator();

    private final Class<T> type;
    private final ObjectMapper json;
    private final JsonNode schema;

    SddStructuredOutput(Class<T> type, ObjectMapper json) {
        this.type = Objects.requireNonNull(type, "type");
        this.json = Objects.requireNonNull(json, "json");
        try {
            schema = json.readTree(new BeanOutputConverter<>(type).getJsonSchema());
        } catch (IOException invalid) {
            throw new IllegalStateException("无法解析 SDD 输出 Schema: " + type.getName(), invalid);
        }
        forbidUnknownProperties(schema);
        VALIDATOR.requireValidSchema(schema, type.getName());
    }

    String formatInstructions() {
        return "只返回一个完整 JSON 对象，严格符合以下 Schema。不要添加 Markdown 代码围栏、前言或后记；"
                + "不得增加 Schema 未声明的字段。\n" + schema.toPrettyString();
    }

    T parse(String output) {
        if (output == null || output.isBlank()) {
            throw new IllegalArgumentException("missing structured JSON");
        }
        try (JsonParser parser = json.getFactory().createParser(output)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode value = json.readTree(parser);
            if (value == null || !value.isObject() || parser.nextToken() != null) {
                throw new IllegalArgumentException("expected exactly one JSON object");
            }
            var issues = VALIDATOR.validate(schema, value, "$");
            if (!issues.isEmpty()) {
                throw new IllegalArgumentException("JSON Schema violation: " + issues.stream()
                        .limit(3)
                        .map(issue -> issue.path() + " " + issue.code() + " " + issue.message())
                        .collect(Collectors.joining("; ")));
            }
            return json.treeToValue(value, type);
        } catch (IOException invalid) {
            throw new IllegalArgumentException("invalid structured JSON", invalid);
        }
    }

    private static void forbidUnknownProperties(JsonNode node) {
        if (node.isArray()) {
            node.forEach(SddStructuredOutput::forbidUnknownProperties);
            return;
        }
        if (!(node instanceof ObjectNode object)) return;
        if (object.has("properties") || "object".equals(object.path("type").asText())) {
            object.put("additionalProperties", false);
        }
        object.fields().forEachRemaining(field -> forbidUnknownProperties(field.getValue()));
    }
}
