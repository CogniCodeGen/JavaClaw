package com.javaclaw.desktop.component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.CanonicalJson;

/** 把平台允许的一层 primitive JSON Schema 投影为安全输入字段与规范响应。 */
final class InputResponseSchema {
    private final CanonicalJson json;
    private final CanonicalPayload source;
    private final List<Field> fields;

    private InputResponseSchema(CanonicalJson json, CanonicalPayload source, List<Field> fields) {
        this.json = json;
        this.source = source;
        this.fields = List.copyOf(fields);
    }

    static InputResponseSchema parse(CanonicalJson json, CanonicalPayload source) {
        CanonicalJson checkedJson = Objects.requireNonNull(json, "json");
        CanonicalPayload checkedSource = Objects.requireNonNull(source, "source");
        checkedJson.requireFlatPrimitiveObjectSchema(checkedSource, 100);
        Map<?, ?> root = checkedJson.decode(checkedSource, Map.class);
        Map<?, ?> properties = map(root.get("properties"), "properties");
        Set<String> required = strings(root.get("required"));
        if (!properties.keySet().containsAll(required)) {
            throw new IllegalArgumentException("required 字段必须在 properties 中声明");
        }
        List<Field> fields = new ArrayList<>();
        properties.forEach((name, descriptor) -> fields.add(field(name, descriptor, required)));
        return new InputResponseSchema(checkedJson, checkedSource, fields);
    }

    List<Field> fields() {
        return fields;
    }

    CanonicalPayload response(Map<String, Object> rawValues) {
        Map<String, Object> checked = Objects.requireNonNull(rawValues, "rawValues");
        Set<String> declared =
                fields.stream().map(Field::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!declared.containsAll(checked.keySet())) {
            throw new IllegalArgumentException("响应包含未声明字段");
        }
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (Field field : fields) {
            Object raw = checked.get(field.name());
            Object value = field.value(raw);
            if (value != null) {
                values.put(field.name(), value);
            } else if (field.required()) {
                throw new IllegalArgumentException(field.label() + "不能为空");
            }
        }
        CanonicalPayload response = json.encode(values);
        json.requireFlatPrimitiveObjectValue(source, response);
        return response;
    }

    private static Field field(Object name, Object descriptor, Set<String> required) {
        if (!(name instanceof String fieldName) || fieldName.isBlank()) {
            throw new IllegalArgumentException("输入字段名无效");
        }
        Map<?, ?> shape = map(descriptor, "field schema");
        Object typeValue = shape.get("type");
        if (!(typeValue instanceof String typeName)) {
            throw new IllegalArgumentException("输入字段缺少 primitive type");
        }
        Kind kind = Kind.from(typeName);
        String label = text(shape.get("title"), fieldName);
        String description = text(shape.get("description"), "");
        return new Field(fieldName, label, description, kind, required.contains(fieldName));
    }

    private static Map<?, ?> map(Object value, String name) {
        if (!(value instanceof Map<?, ?> result)) {
            throw new IllegalArgumentException(name + " 必须是对象");
        }
        return result;
    }

    private static Set<String> strings(Object value) {
        if (value == null) {
            return Set.of();
        }
        if (!(value instanceof Collection<?> collection)
                || collection.stream().anyMatch(element -> !(element instanceof String))) {
            throw new IllegalArgumentException("required 必须是字符串数组");
        }
        Set<String> result =
                collection.stream().map(String.class::cast).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (result.size() != collection.size()) {
            throw new IllegalArgumentException("required 不能包含重复字段");
        }
        return result;
    }

    private static String text(Object value, String fallback) {
        return value instanceof String text && !text.isBlank() ? text.strip() : fallback;
    }

    record Field(String name, String label, String description, Kind kind, boolean required) {
        Field {
            name = requireText(name, "name");
            label = requireText(label, "label");
            description = Objects.requireNonNull(description, "description").strip();
            Objects.requireNonNull(kind, "kind");
        }

        Object value(Object raw) {
            if (raw == null) {
                return null;
            }
            return switch (kind) {
                case STRING -> string(raw);
                case NUMBER -> number(raw);
                case INTEGER -> integer(raw);
                case BOOLEAN -> bool(raw);
            };
        }

        private String string(Object raw) {
            String value = Objects.toString(raw, "");
            return !required && value.isBlank() ? null : value;
        }

        private BigDecimal number(Object raw) {
            String value = Objects.toString(raw, "").strip();
            if (value.isEmpty()) {
                return null;
            }
            try {
                return new BigDecimal(value);
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException(label + "必须是数值", failure);
            }
        }

        private BigInteger integer(Object raw) {
            String value = Objects.toString(raw, "").strip();
            if (value.isEmpty()) {
                return null;
            }
            try {
                return new BigInteger(value);
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException(label + "必须是整数", failure);
            }
        }

        private Boolean bool(Object raw) {
            if (!(raw instanceof Boolean value)) {
                throw new IllegalArgumentException(label + "必须选择是或否");
            }
            return value;
        }

        private static String requireText(String value, String name) {
            String text = Objects.requireNonNull(value, name).strip();
            if (text.isEmpty()) {
                throw new IllegalArgumentException(name + " 不能为空");
            }
            return text;
        }
    }

    enum Kind {
        STRING,
        NUMBER,
        INTEGER,
        BOOLEAN;

        private static Kind from(String value) {
            return switch (value) {
                case "string" -> STRING;
                case "number" -> NUMBER;
                case "integer" -> INTEGER;
                case "boolean" -> BOOLEAN;
                default -> throw new IllegalArgumentException("不支持的输入字段类型: " + value);
            };
        }
    }
}
