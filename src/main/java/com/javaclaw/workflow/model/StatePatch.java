package com.javaclaw.workflow.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 不可变状态增量。 */
public final class StatePatch {

    private static final ObjectMapper MAPPER = new ObjectMapper();
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
                value == null ? MAPPER.nullNode() : value.deepCopy()));
        this.values = Collections.unmodifiableMap(copiedValues);
        this.removals = removals == null ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(removals));
        Map<String, ArrayNode> copiedAppends = new LinkedHashMap<>();
        if (appends != null) appends.forEach((path, value) -> copiedAppends.put(path,
                value == null ? MAPPER.createArrayNode() : value.deepCopy()));
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
            JsonNode json = MAPPER.valueToTree(value);
            values.put(path, json);
            removals.remove(path);
            appends.remove(path);
            operations.add(new Operation(Kind.SET, path, json));
            return this;
        }

        public Builder setJson(String path, JsonNode value) {
            JsonNode json = value == null ? MAPPER.nullNode() : value.deepCopy();
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
            JsonNode json = MAPPER.valueToTree(value);
            appends.computeIfAbsent(path, ignored -> MAPPER.createArrayNode()).add(json);
            operations.add(new Operation(Kind.APPEND, path, json));
            return this;
        }
        public StatePatch build() { return new StatePatch(values, removals, appends, operations); }
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
