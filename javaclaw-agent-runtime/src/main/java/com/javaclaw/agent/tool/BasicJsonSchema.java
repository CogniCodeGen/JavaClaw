package com.javaclaw.agent.tool;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 工具参数的受限、失败关闭 Schema 校验器；治理链与权限预览复用同一语义，不支持的关键字直接拒绝。 */
public final class BasicJsonSchema {
    private static final int MAX_DEPTH = 32;
    private static final int MAX_NODES = 10_000;
    private static final Set<String> UNSUPPORTED =
            Set.of("$ref", "$dynamicRef", "oneOf", "anyOf", "allOf", "not", "if", "then", "else");

    private final JsonNode schema;

    /** 解析且验证受支持的对象 Schema；必须显式禁止额外属性，不能在运行期解析外部引用。 */
    public BasicJsonSchema(ObjectMapper json, String schemaJson) {
        try {
            schema = json.readTree(schemaJson);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("tool schema is not valid JSON", failure);
        }
        if (schema == null || !schema.isObject()) {
            throw new IllegalArgumentException("tool schema must be an object");
        }
        rejectUnsupported(schema);
        if (!"object".equals(schema.path("type").asText())) {
            throw new IllegalArgumentException("tool schema root type must be object");
        }
        if (!schema.path("additionalProperties").isBoolean()
                || schema.path("additionalProperties").booleanValue()) {
            throw new IllegalArgumentException("tool schema must explicitly set additionalProperties to false");
        }
    }

    /** 校验一份参数并限制递归/节点预算；不符合契约时抛出 IllegalArgumentException，不修改输入。 */
    public void validate(JsonNode value) {
        if (value == null) {
            throw new IllegalArgumentException("JSON value is required");
        }
        Counter counter = new Counter();
        validate(schema, value, "$", 0, counter);
    }

    private static void validate(JsonNode schema, JsonNode value, String path, int depth, Counter counter) {
        if (depth > MAX_DEPTH || ++counter.nodes > MAX_NODES) {
            throw new IllegalArgumentException("tool arguments exceed structural limits");
        }
        JsonNode enumValues = schema.get("enum");
        if (enumValues != null && enumValues.isArray()) {
            boolean matched = false;
            for (JsonNode candidate : enumValues) {
                matched |= candidate.equals(value);
            }
            if (!matched) {
                throw new IllegalArgumentException(path + " is not an allowed value");
            }
        }
        String type = schema.path("type").asText();
        if (!type.isEmpty() && !matches(type, value)) {
            throw new IllegalArgumentException(path + " must be " + type);
        }
        switch (type) {
            case "object" -> validateObject(schema, value, path, depth, counter);
            case "array" -> validateArray(schema, value, path, depth, counter);
            case "string" -> validateString(schema, value, path);
            case "integer", "number" -> validateNumber(schema, value, path);
            default -> {}
        }
    }

    private static void validateObject(JsonNode schema, JsonNode value, String path, int depth, Counter counter) {
        Set<String> required = new HashSet<>();
        JsonNode requiredNode = schema.get("required");
        if (requiredNode != null) {
            requiredNode.forEach(item -> required.add(item.asText()));
        }
        for (String name : required) {
            if (!value.has(name)) {
                throw new IllegalArgumentException(path + "." + name + " is required");
            }
        }
        JsonNode properties = schema.path("properties");
        Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode childSchema = properties.get(field.getKey());
            if (childSchema == null) {
                if (!schema.path("additionalProperties").asBoolean(true)) {
                    throw new IllegalArgumentException(path + "." + field.getKey() + " is not allowed");
                }
            } else {
                validate(childSchema, field.getValue(), path + "." + field.getKey(), depth + 1, counter);
            }
        }
    }

    private static void validateArray(JsonNode schema, JsonNode value, String path, int depth, Counter counter) {
        int minimum = schema.path("minItems").asInt(0);
        int maximum = schema.path("maxItems").asInt(Integer.MAX_VALUE);
        if (value.size() < minimum || value.size() > maximum) {
            throw new IllegalArgumentException(path + " has an invalid item count");
        }
        JsonNode items = schema.get("items");
        if (items == null) {
            return;
        }
        for (int index = 0; index < value.size(); index++) {
            validate(items, value.get(index), path + "[" + index + "]", depth + 1, counter);
        }
    }

    private static void validateString(JsonNode schema, JsonNode value, String path) {
        int minimum = schema.path("minLength").asInt(0);
        int maximum = schema.path("maxLength").asInt(1_000_000);
        int length = value.textValue().length();
        if (length < minimum || length > maximum) {
            throw new IllegalArgumentException(path + " has an invalid length");
        }
    }

    private static void validateNumber(JsonNode schema, JsonNode value, String path) {
        if (schema.has("minimum")
                && value.decimalValue().compareTo(schema.get("minimum").decimalValue()) < 0) {
            throw new IllegalArgumentException(path + " is below minimum");
        }
        if (schema.has("maximum")
                && value.decimalValue().compareTo(schema.get("maximum").decimalValue()) > 0) {
            throw new IllegalArgumentException(path + " is above maximum");
        }
    }

    private static boolean matches(String type, JsonNode value) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> throw new IllegalArgumentException("unsupported schema type: " + type);
        };
    }

    private static void rejectUnsupported(JsonNode node) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (UNSUPPORTED.contains(entry.getKey())) {
                    throw new IllegalArgumentException(
                            "unsupported security-sensitive schema keyword: " + entry.getKey());
                }
                rejectUnsupported(entry.getValue());
            });
        } else if (node.isArray()) {
            node.forEach(BasicJsonSchema::rejectUnsupported);
        }
    }

    private static final class Counter {
        private int nodes;
    }
}
