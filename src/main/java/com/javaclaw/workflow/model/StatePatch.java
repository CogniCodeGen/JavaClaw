package com.javaclaw.workflow.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 不可变状态增量。 */
public final class StatePatch {

    public static final StatePatch EMPTY = new StatePatch(Map.of(), Set.of(), Map.of());
    private final Map<String, JsonNode> values;
    private final Set<String> removals;
    private final Map<String, ArrayNode> appends;
    private final List<Operation> operations;

    /**
     * 兼容旧调用方的构造器。三个集合本身不携带跨集合顺序，因此沿用旧的
     * remove → set → append 解释；需要后写覆盖语义时应使用 {@link #builder()}。
     */
    public StatePatch(Map<String, JsonNode> values, Set<String> removals,
                      Map<String, ArrayNode> appends) {
        this(values, removals, appends, legacyOperations(values, removals, appends));
    }

    private StatePatch(Map<String, JsonNode> values, Set<String> removals,
                       Map<String, ArrayNode> appends, List<Operation> operations) {
        Map<String, JsonNode> copiedValues = new LinkedHashMap<>();
        if (values != null) values.forEach((path, value) -> copiedValues.put(path,
                value == null ? JsonNodeFactory.instance.nullNode() : value.deepCopy()));
        this.values = Collections.unmodifiableMap(copiedValues);
        this.removals = removals == null ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(removals));
        Map<String, ArrayNode> copiedAppends = new LinkedHashMap<>();
        if (appends != null) appends.forEach((path, value) -> copiedAppends.put(path,
                value == null ? JsonNodeFactory.instance.arrayNode() : value.deepCopy()));
        this.appends = Collections.unmodifiableMap(copiedAppends);
        this.operations = operations == null ? List.of() : operations.stream()
                .map(Operation::copy).toList();
    }

    public Map<String, JsonNode> values() { return values; }
    public Set<String> removals() { return removals; }
    public Map<String, ArrayNode> appends() { return appends; }
    List<Operation> operations() { return operations; }
    public boolean isEmpty() { return values.isEmpty() && removals.isEmpty() && appends.isEmpty(); }
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final Map<String, JsonNode> values = new LinkedHashMap<>();
        private final Set<String> removals = new LinkedHashSet<>();
        private final Map<String, ArrayNode> appends = new LinkedHashMap<>();
        private final List<Operation> operations = new ArrayList<>();

        public Builder set(String path, Object value) {
            JsonNode json = toJsonNode(value);
            values.put(path, json);
            removals.remove(path);
            appends.remove(path);
            operations.add(new Operation(Kind.SET, path, json));
            return this;
        }

        public Builder setJson(String path, JsonNode value) {
            JsonNode json = value == null ? JsonNodeFactory.instance.nullNode() : value.deepCopy();
            values.put(path, json);
            removals.remove(path);
            appends.remove(path);
            operations.add(new Operation(Kind.SET, path, json));
            return this;
        }

        public Builder remove(String path) {
            removals.add(path);
            values.remove(path);
            appends.remove(path);
            operations.add(new Operation(Kind.REMOVE, path, null));
            return this;
        }
        public Builder append(String path, Object value) {
            JsonNode json = toJsonNode(value);
            appends.computeIfAbsent(path, ignored -> JsonNodeFactory.instance.arrayNode()).add(json);
            operations.add(new Operation(Kind.APPEND, path, json));
            return this;
        }
        public StatePatch build() { return new StatePatch(values, removals, appends, operations); }
    }

    private static JsonNode toJsonNode(Object value) {
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        if (value == null) return nodes.nullNode();
        if (value instanceof JsonNode node) return node.deepCopy();
        if (value instanceof String text) return nodes.textNode(text);
        if (value instanceof Character character) return nodes.textNode(character.toString());
        if (value instanceof Enum<?> enumeration) return nodes.textNode(enumeration.name());
        if (value instanceof Boolean bool) return nodes.booleanNode(bool);
        if (value instanceof Byte number) return nodes.numberNode(number);
        if (value instanceof Short number) return nodes.numberNode(number);
        if (value instanceof Integer number) return nodes.numberNode(number);
        if (value instanceof Long number) return nodes.numberNode(number);
        if (value instanceof Float number) return nodes.numberNode(number);
        if (value instanceof Double number) return nodes.numberNode(number);
        if (value instanceof BigInteger number) return nodes.numberNode(number);
        if (value instanceof BigDecimal number) return nodes.numberNode(number);
        if (value instanceof Map<?, ?> map) {
            ObjectNode object = nodes.objectNode();
            map.forEach((key, item) -> object.set(String.valueOf(key), toJsonNode(item)));
            return object;
        }
        if (value instanceof Iterable<?> iterable) {
            ArrayNode array = nodes.arrayNode();
            iterable.forEach(item -> array.add(toJsonNode(item)));
            return array;
        }
        if (value.getClass().isArray()) {
            ArrayNode array = nodes.arrayNode();
            for (int index = 0; index < Array.getLength(value); index++) {
                array.add(toJsonNode(Array.get(value, index)));
            }
            return array;
        }
        throw new IllegalArgumentException(
                "工作流状态只接受 JSON 值，实际类型: " + value.getClass().getName());
    }

    enum Kind { SET, REMOVE, APPEND }

    record Operation(Kind kind, String path, JsonNode value) {
        Operation copy() {
            return new Operation(kind, path, value == null ? null : value.deepCopy());
        }
    }

    private static List<Operation> legacyOperations(Map<String, JsonNode> values,
                                                    Set<String> removals,
                                                    Map<String, ArrayNode> appends) {
        List<Operation> out = new ArrayList<>();
        if (removals != null) removals.forEach(path -> out.add(new Operation(Kind.REMOVE, path, null)));
        if (values != null) values.forEach((path, value) ->
                out.add(new Operation(Kind.SET, path, value)));
        if (appends != null) appends.forEach((path, array) -> {
            if (array != null) array.forEach(value -> out.add(new Operation(Kind.APPEND, path, value)));
        });
        return out;
    }
}
