package com.javaclaw.workflow.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

/** JSON-only 工作流状态，禁止把 Agent、UI 节点等运行期对象塞入检查点。 */
public final class GraphState {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ObjectNode root;

    public GraphState() {
        this(JsonNodeFactory.instance.objectNode());
    }

    public GraphState(ObjectNode root) {
        this.root = root == null ? JsonNodeFactory.instance.objectNode() : root.deepCopy();
    }

    public static GraphState fromJson(String json) {
        if (json == null || json.isBlank()) return new GraphState();
        try {
            JsonNode parsed = MAPPER.readTree(json);
            if (!parsed.isObject()) throw new IllegalArgumentException("GraphState 根必须是 JSON object");
            return new GraphState((ObjectNode) parsed);
        } catch (Exception e) {
            throw new IllegalArgumentException("GraphState JSON 无效", e);
        }
    }

    public JsonNode get(String path) {
        if (path == null || path.isBlank()) return root.deepCopy();
        JsonNode current = root;
        for (String segment : path.split("\\.")) {
            if (!current.isObject() || !current.has(segment)) return MissingNode.getInstance();
            current = current.get(segment);
        }
        return current == null ? MissingNode.getInstance() : current.deepCopy();
    }

    public boolean exists(String path) {
        JsonNode n = get(path);
        return !n.isMissingNode() && !n.isNull();
    }

    public GraphState apply(StatePatch patch) {
        if (patch == null || patch.isEmpty()) return this;
        ObjectNode copy = root.deepCopy();
        for (StatePatch.Operation operation : patch.operations()) {
            switch (operation.kind()) {
                case SET -> setAt(copy, operation.path(), operation.value());
                case REMOVE -> removeAt(copy, operation.path());
                case APPEND -> appendAt(copy, operation.path(), operation.value());
            }
        }
        return new GraphState(copy);
    }

    public GraphState merge(GraphState other) {
        if (other == null) return this;
        ObjectNode copy = root.deepCopy();
        deepMerge(copy, other.root);
        return new GraphState(copy);
    }

    public ObjectNode toObjectNode() { return root.deepCopy(); }
    public String toJson() { return root.toString(); }

    private static void setAt(ObjectNode object, String path, JsonNode value) {
        String[] parts = requirePath(path);
        ObjectNode parent = parent(object, parts);
        parent.set(parts[parts.length - 1], value == null ? JsonNodeFactory.instance.nullNode() : value.deepCopy());
    }

    private static void removeAt(ObjectNode object, String path) {
        String[] parts = requirePath(path);
        JsonNode current = object;
        for (int i = 0; i < parts.length - 1; i++) {
            current = current.path(parts[i]);
            if (!current.isObject()) return;
        }
        ((ObjectNode) current).remove(parts[parts.length - 1]);
    }

    private static void appendAt(ObjectNode object, String path, JsonNode value) {
        String[] parts = requirePath(path);
        ObjectNode parent = parent(object, parts);
        String leaf = parts[parts.length - 1];
        JsonNode existing = parent.get(leaf);
        ArrayNode array;
        if (existing == null || existing.isNull()) {
            array = parent.putArray(leaf);
        } else if (existing.isArray()) {
            array = (ArrayNode) existing;
        } else {
            throw new IllegalArgumentException("append 目标不是数组: " + path);
        }
        array.add(value == null ? JsonNodeFactory.instance.nullNode() : value.deepCopy());
    }

    private static ObjectNode parent(ObjectNode root, String[] parts) {
        ObjectNode current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonNode child = current.get(parts[i]);
            if (child == null || child.isNull()) {
                current = current.putObject(parts[i]);
            } else if (child.isObject()) {
                current = (ObjectNode) child;
            } else {
                throw new IllegalArgumentException("状态路径穿过非对象字段: " + parts[i]);
            }
        }
        return current;
    }

    private static String[] requirePath(String path) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("状态路径不能为空");
        String[] parts = path.split("\\.");
        for (String part : parts) {
            if (!part.matches("[A-Za-z_][A-Za-z0-9_-]*")) {
                throw new IllegalArgumentException("非法状态路径: " + path);
            }
        }
        return parts;
    }

    private static void deepMerge(ObjectNode target, ObjectNode source) {
        Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> e = fields.next();
            JsonNode old = target.get(e.getKey());
            if (old != null && old.isObject() && e.getValue().isObject()) {
                deepMerge((ObjectNode) old, (ObjectNode) e.getValue());
            } else {
                target.set(e.getKey(), e.getValue().deepCopy());
            }
        }
    }
}
