package com.javaclaw.protocol;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ProviderAdapterOptions;

/** 共享的严格 JSON 编解码器；Server 与 SDK 不再各自维护 Mapper。 */
public final class CanonicalJson {
    private final ObjectMapper mapper;

    /** 创建带重复键检查、Java 时间和 Path 字符串映射的 codec。 */
    public CanonicalJson() {
        SimpleModule paths = new SimpleModule("javaclaw-paths", Version.unknownVersion());
        paths.addSerializer(Path.class, new CanonicalJsonCodecs.PathSerializer());
        paths.addDeserializer(Path.class, new CanonicalJsonCodecs.PathDeserializer());
        paths.addSerializer(CanonicalPayload.class, new CanonicalJsonCodecs.CanonicalPayloadSerializer());
        paths.addDeserializer(CanonicalPayload.class, new CanonicalJsonCodecs.CanonicalPayloadDeserializer());
        paths.addSerializer(ProviderAdapterOptions.class, new CanonicalJsonCodecs.ProviderAdapterOptionsSerializer());
        paths.addDeserializer(
                ProviderAdapterOptions.class, new CanonicalJsonCodecs.ProviderAdapterOptionsDeserializer());
        paths.addSerializer(new CanonicalJsonCodecs.DeterministicSetSerializer());
        mapper = JsonMapper.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
                .addModule(new Jdk8Module())
                .addModule(new JavaTimeModule())
                .addModule(new IdentifierJsonModule())
                .addModule(paths)
                .build();
        mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    }

    /**
     * 将对象编码为规范化 JSON object。
     *
     * @param value 可序列化值
     * @return 规范 payload
     */
    public CanonicalPayload encode(Object value) {
        JsonNode tree = mapper.valueToTree(value);
        return payload(requireObject(tree));
    }

    /**
     * 将 JSON object 规范化。
     *
     * @param json 原始 JSON
     * @return 规范 payload
     */
    public CanonicalPayload parse(String json) {
        try {
            return payload(requireObject(mapper.readTree(json)));
        } catch (IOException | IllegalArgumentException failure) {
            throw new ProtocolException(ProtocolErrorCode.PARSE_ERROR, "invalid JSON object: " + failure.getMessage());
        }
    }

    /**
     * 把规范 payload 解码为共享契约类型。
     *
     * @param payload payload
     * @param type 目标类型
     * @param <T> 结果类型
     * @return 强类型值
     */
    public <T> T decode(CanonicalPayload payload, Class<T> type) {
        try {
            return mapper.readValue(payload.json(), type);
        } catch (IOException failure) {
            throw new ProtocolException(ProtocolErrorCode.INVALID_PARAMS, "payload does not match contract");
        }
    }

    /**
     * 读取对象中的字符串字段，不暴露 Jackson 类型。
     *
     * @param payload 规范 payload
     * @param fieldName 字段名
     * @return 不存在或为 null 时为空
     */
    public Optional<String> textField(CanonicalPayload payload, String fieldName) {
        JsonNode value = tree(payload).get(Objects.requireNonNull(fieldName, "fieldName"));
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isTextual()) {
            throw new ProtocolException(ProtocolErrorCode.INVALID_PARAMS, fieldName + " must be a string");
        }
        return Optional.of(value.textValue());
    }

    /**
     * 读取对象字段并保持规范 JSON，不向边界外暴露 Jackson 类型。
     *
     * @param payload 父对象
     * @param fieldName 字段名
     * @return 不存在或为 null 时为空
     */
    public Optional<CanonicalPayload> objectField(CanonicalPayload payload, String fieldName) {
        JsonNode value = tree(payload).get(Objects.requireNonNull(fieldName, "fieldName"));
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!(value instanceof ObjectNode object)) {
            throw new ProtocolException(ProtocolErrorCode.INVALID_PARAMS, fieldName + " must be an object");
        }
        return Optional.of(payload(object));
    }

    /**
     * 读取只包含对象的数组字段。
     *
     * @param payload 父对象
     * @param fieldName 字段名
     * @param maximumElements 允许的最大元素数
     * @return 规范子对象列表；缺失字段返回空列表
     */
    public List<CanonicalPayload> objectArrayField(CanonicalPayload payload, String fieldName, int maximumElements) {
        if (maximumElements < 1) {
            throw new IllegalArgumentException("maximumElements must be positive");
        }
        JsonNode value = tree(payload).get(Objects.requireNonNull(fieldName, "fieldName"));
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (!(value instanceof ArrayNode array) || array.size() > maximumElements) {
            throw new ProtocolException(ProtocolErrorCode.INVALID_PARAMS, fieldName + " must be a bounded array");
        }
        List<CanonicalPayload> result = new ArrayList<>(array.size());
        for (JsonNode element : array) {
            if (!(element instanceof ObjectNode object)) {
                throw new ProtocolException(ProtocolErrorCode.INVALID_PARAMS, fieldName + " entries must be objects");
            }
            result.add(payload(object));
        }
        return List.copyOf(result);
    }

    /**
     * 读取只包含非空字符串的有界数组字段。
     *
     * @param payload 父对象
     * @param fieldName 字段名
     * @param maximumElements 允许的最大元素数
     * @return 不存在字段时为空列表
     */
    public List<String> textArrayField(CanonicalPayload payload, String fieldName, int maximumElements) {
        if (maximumElements < 1) {
            throw new IllegalArgumentException("maximumElements must be positive");
        }
        JsonNode value = tree(payload).get(Objects.requireNonNull(fieldName, "fieldName"));
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (!(value instanceof ArrayNode array) || array.size() > maximumElements) {
            throw new ProtocolException(ProtocolErrorCode.INVALID_PARAMS, fieldName + " must be a bounded array");
        }
        List<String> result = new ArrayList<>(array.size());
        for (JsonNode element : array) {
            if (!element.isTextual() || element.textValue().isBlank()) {
                throw new ProtocolException(
                        ProtocolErrorCode.INVALID_PARAMS, fieldName + " entries must be non-blank strings");
            }
            result.add(element.textValue());
        }
        return List.copyOf(result);
    }

    /**
     * 读取可选布尔字段。
     *
     * @param payload 父对象
     * @param fieldName 字段名
     * @return 不存在或为 null 时为空
     */
    public Optional<Boolean> booleanField(CanonicalPayload payload, String fieldName) {
        JsonNode value = tree(payload).get(Objects.requireNonNull(fieldName, "fieldName"));
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isBoolean()) {
            throw new ProtocolException(ProtocolErrorCode.INVALID_PARAMS, fieldName + " must be a boolean");
        }
        return Optional.of(value.booleanValue());
    }

    /**
     * 读取不会发生浮点或字符串强制转换的整数字段。
     *
     * @param payload 父对象
     * @param fieldName 字段名
     * @return 不存在或为 null 时为空
     */
    public Optional<Long> integerField(CanonicalPayload payload, String fieldName) {
        JsonNode value = tree(payload).get(Objects.requireNonNull(fieldName, "fieldName"));
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new ProtocolException(ProtocolErrorCode.INVALID_PARAMS, fieldName + " must be an integer");
        }
        return Optional.of(value.longValue());
    }

    /**
     * 读取不会发生字符串或布尔强制转换的十进数字段。
     *
     * @param payload 父对象
     * @param fieldName 字段名
     * @return 不存在或为 null 时为空
     */
    public Optional<BigDecimal> decimalField(CanonicalPayload payload, String fieldName) {
        JsonNode value = tree(payload).get(Objects.requireNonNull(fieldName, "fieldName"));
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isNumber()) {
            throw new ProtocolException(ProtocolErrorCode.INVALID_PARAMS, fieldName + " must be a number");
        }
        return Optional.of(value.decimalValue());
    }

    /**
     * 返回对象的精确顶层字段名。
     *
     * @param payload 规范 JSON 对象
     * @return 不可变字段名集合
     */
    public Set<String> fieldNames(CanonicalPayload payload) {
        return fieldNames(tree(Objects.requireNonNull(payload, "payload")));
    }

    /**
     * 判断对象树中是否出现给定字段名；比较不区分大小写。
     *
     * <p>该方法用于在进入持久层前拒绝明确禁止的字段，不向调用方暴露 Jackson 类型。
     *
     * @param payload 规范 payload
     * @param fieldNames 要查找的完整字段名
     * @return 任意对象层级命中时为 true
     */
    public boolean containsAnyField(CanonicalPayload payload, Set<String> fieldNames) {
        Set<String> normalized = fieldNames.stream()
                .map(name -> Objects.requireNonNull(name, "fieldName").toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return containsAnyField(tree(payload), normalized);
    }

    /**
     * 要求给定顶层字段存在且都是字符串。
     *
     * <p>该校验用于受控模板的可变槽位。只检查顶层完整字段名，不解释 JSON Pointer 或表达式。
     *
     * @param payload 规范 JSON 对象
     * @param fieldNames 必须存在的顶层字段名
     */
    public void requireTopLevelStringFields(CanonicalPayload payload, Set<String> fieldNames) {
        ObjectNode object = tree(Objects.requireNonNull(payload, "payload"));
        Set<String> fields = Set.copyOf(fieldNames);
        for (String field : fields) {
            JsonNode value = object.get(Objects.requireNonNull(field, "fieldName"));
            if (value == null || !value.isTextual()) {
                throw new ProtocolException(
                        ProtocolErrorCode.INVALID_PARAMS, "variable field must exist as a top-level string: " + field);
            }
        }
    }

    /**
     * 要求 Schema 仅描述一层 primitive object，供不可信端点发起受限用户输入。
     *
     * <p>禁止组合、引用、嵌套对象、数组和 Secret 暗示字段，避免外部 Schema 变成动态执行语言或凭据收集表单。
     *
     * @param schema JSON Schema 对象
     * @param maximumProperties 最大字段数
     */
    public void requireFlatPrimitiveObjectSchema(CanonicalPayload schema, int maximumProperties) {
        if (maximumProperties < 1 || maximumProperties > 100) {
            throw new IllegalArgumentException("maximumProperties must be between 1 and 100");
        }
        ObjectNode root = tree(Objects.requireNonNull(schema, "schema"));
        if (!"object".equals(text(root.get("type")))
                || root.path("additionalProperties").asBoolean(true)) {
            throw new ProtocolException(ProtocolErrorCode.INVALID_PARAMS, "schema must be a closed object");
        }
        JsonNode properties = root.get("properties");
        if (properties == null
                || !properties.isObject()
                || properties.isEmpty()
                || properties.size() > maximumProperties) {
            throw new ProtocolException(
                    ProtocolErrorCode.INVALID_PARAMS, "schema properties are outside the allowed range");
        }
        java.util.Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            requireSafeInputField(field.getKey(), field.getValue());
        }
        if (containsAnyField(schema, Set.of("$ref", "allOf", "anyOf", "oneOf", "not", "if", "then", "else"))) {
            throw new ProtocolException(
                    ProtocolErrorCode.INVALID_PARAMS, "schema composition and references are not allowed");
        }
    }

    /**
     * 校验响应精确符合已经由 {@link #requireFlatPrimitiveObjectSchema(CanonicalPayload, int)} 收窄的 Schema。
     *
     * <p>只实现平台允许的一层 primitive object 子集；不执行默认值、格式转换、引用或表达式。
     *
     * @param schema 受限输入 Schema
     * @param value 用户响应对象
     */
    public void requireFlatPrimitiveObjectValue(CanonicalPayload schema, CanonicalPayload value) {
        requireFlatPrimitiveObjectSchema(schema, 100);
        ObjectNode schemaRoot = tree(schema);
        ObjectNode properties = (ObjectNode) schemaRoot.get("properties");
        ObjectNode actual = tree(Objects.requireNonNull(value, "value"));
        Set<String> allowed = fieldNames(properties);
        if (!allowed.containsAll(fieldNames(actual))) {
            throw new ProtocolException(ProtocolErrorCode.INVALID_PARAMS, "response contains undeclared fields");
        }
        Set<String> required = stringArray(schemaRoot.get("required"), "required", allowed.size());
        if (!fieldNames(actual).containsAll(required)) {
            throw new ProtocolException(ProtocolErrorCode.INVALID_PARAMS, "response omitted required fields");
        }
        java.util.Iterator<Map.Entry<String, JsonNode>> fields = actual.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String type = text(properties.get(field.getKey()).get("type"));
            if (!matchesPrimitive(type, field.getValue())) {
                throw new ProtocolException(
                        ProtocolErrorCode.INVALID_PARAMS,
                        "response field type does not match schema: " + field.getKey());
            }
        }
    }

    private static Set<String> stringArray(JsonNode node, String name, int maximumElements) {
        if (node == null || node.isNull()) {
            return Set.of();
        }
        if (!(node instanceof ArrayNode array) || array.size() > maximumElements) {
            throw new ProtocolException(ProtocolErrorCode.INVALID_PARAMS, name + " must be a bounded string array");
        }
        java.util.HashSet<String> result = new java.util.HashSet<>();
        for (JsonNode value : array) {
            if (!value.isTextual() || !result.add(value.textValue())) {
                throw new ProtocolException(ProtocolErrorCode.INVALID_PARAMS, name + " must contain unique strings");
            }
        }
        return Set.copyOf(result);
    }

    private static boolean matchesPrimitive(String type, JsonNode value) {
        return switch (type) {
            case "string" -> value.isTextual();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            default -> false;
        };
    }

    private static void requireSafeInputField(String name, JsonNode schema) {
        String normalized = name.toLowerCase(Locale.ROOT).replaceAll("[._-]", "");
        if (normalized.contains("secret")
                || normalized.contains("password")
                || normalized.contains("token")
                || normalized.contains("cookie")
                || normalized.contains("credential")
                || normalized.contains("privatekey")) {
            throw new ProtocolException(
                    ProtocolErrorCode.INVALID_PARAMS, "elicitation must not request credential fields");
        }
        Set<String> primitives = Set.of("string", "number", "integer", "boolean");
        if (!schema.isObject() || !primitives.contains(text(schema.get("type")))) {
            throw new ProtocolException(
                    ProtocolErrorCode.INVALID_PARAMS, "elicitation properties must be primitive values");
        }
    }

    private static String text(JsonNode value) {
        return value != null && value.isTextual() ? value.textValue() : "";
    }

    /**
     * 判断候选对象是否只改变获准的顶层字符串字段。
     *
     * <p>数组顺序、数值类型、嵌套对象和所有未列出的字段都必须与模板完全一致。候选也不能增加或删除字段。
     *
     * @param template 已确认的完整模板
     * @param candidate 执行时完整参数
     * @param variableFields 允许变化的顶层字符串字段
     * @return 只有允许字段的字符串值发生变化时为 true
     */
    public boolean matchesExceptTopLevelStrings(
            CanonicalPayload template, CanonicalPayload candidate, Set<String> variableFields) {
        ObjectNode expected = tree(Objects.requireNonNull(template, "template"));
        ObjectNode actual = tree(Objects.requireNonNull(candidate, "candidate"));
        Set<String> fields = Set.copyOf(variableFields);
        if (!fieldNames(expected).equals(fieldNames(actual))) {
            return false;
        }
        for (String field : fields) {
            JsonNode expectedValue = expected.get(Objects.requireNonNull(field, "fieldName"));
            JsonNode actualValue = actual.get(field);
            if (expectedValue == null
                    || actualValue == null
                    || !expectedValue.isTextual()
                    || !actualValue.isTextual()) {
                return false;
            }
        }
        java.util.Iterator<java.util.Map.Entry<String, JsonNode>> entries = expected.fields();
        while (entries.hasNext()) {
            java.util.Map.Entry<String, JsonNode> entry = entries.next();
            if (!fields.contains(entry.getKey()) && !entry.getValue().equals(actual.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    ObjectNode tree(CanonicalPayload payload) {
        try {
            return requireObject(mapper.readTree(payload.json()));
        } catch (IOException failure) {
            throw new ProtocolException(ProtocolErrorCode.PARSE_ERROR, "canonical payload is unreadable");
        }
    }

    ObjectMapper mapper() {
        return mapper;
    }

    CanonicalPayload payload(ObjectNode object) {
        try {
            return new CanonicalPayload(mapper.writeValueAsString(sort(object)));
        } catch (IOException failure) {
            throw new IllegalStateException("cannot serialize canonical JSON", failure);
        }
    }

    private static ObjectNode requireObject(JsonNode node) {
        if (!(node instanceof ObjectNode object)) {
            throw new IllegalArgumentException("JSON value must be an object");
        }
        return object;
    }

    static JsonNode sort(JsonNode node) {
        if (node instanceof ObjectNode object) {
            ObjectNode sorted = object.objectNode();
            List<java.util.Map.Entry<String, JsonNode>> entries = new ArrayList<>();
            object.fields().forEachRemaining(entries::add);
            entries.stream()
                    .sorted(Comparator.comparing(java.util.Map.Entry::getKey))
                    .forEach(entry -> sorted.set(entry.getKey(), sort(entry.getValue())));
            return sorted;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode sorted = array.arrayNode();
            array.forEach(value -> sorted.add(sort(value)));
            return sorted;
        }
        if (node.isBigDecimal()) {
            BigDecimal normalized = node.decimalValue().stripTrailingZeros();
            return com.fasterxml.jackson.databind.node.DecimalNode.valueOf(normalized);
        }
        return node;
    }

    private static boolean containsAnyField(JsonNode node, Set<String> fieldNames) {
        if (node instanceof ObjectNode object) {
            java.util.Iterator<java.util.Map.Entry<String, JsonNode>> entries = object.fields();
            while (entries.hasNext()) {
                java.util.Map.Entry<String, JsonNode> entry = entries.next();
                if (fieldNames.contains(entry.getKey().toLowerCase(Locale.ROOT))
                        || containsAnyField(entry.getValue(), fieldNames)) {
                    return true;
                }
            }
            return false;
        }
        if (node instanceof ArrayNode array) {
            return java.util.stream.StreamSupport.stream(array.spliterator(), false)
                    .anyMatch(value -> containsAnyField(value, fieldNames));
        }
        return false;
    }

    private static Set<String> fieldNames(ObjectNode object) {
        java.util.HashSet<String> names = new java.util.HashSet<>();
        object.fieldNames().forEachRemaining(names::add);
        return Set.copyOf(names);
    }
}
