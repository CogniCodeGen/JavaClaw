package com.javaclaw.agent.tool;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.agent.automation.AutomationKind;
import com.javaclaw.agent.automation.AutomationPlan;

/** 编排 JSON 的唯一解析边界；Runtime 领域策略不依赖 Jackson，也不运行客户端提供的表达式代码。 */
public final class AutomationPlans {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private AutomationPlans() {}

    /** 将 {"$output":"node-id"} 显式绑定为既有节点的字符串输出；不执行表达式或字符串模板代码。 */
    public static String resolveArguments(String arguments, Map<String, String> outputs) {
        try {
            return resolve(JSON.readTree(arguments), outputs, 0).toString();
        } catch (java.io.IOException invalid) {
            throw new IllegalArgumentException("invalid Workflow tool arguments", invalid);
        }
    }

    private static JsonNode resolve(JsonNode value, Map<String, String> outputs, int depth) {
        if (value == null || depth > 32) {
            throw new IllegalArgumentException("Workflow arguments exceed nesting bound");
        }
        if (value.isObject() && value.size() == 1 && value.has("$output")) {
            if (!value.path("$output").isTextual()
                    || !outputs.containsKey(value.path("$output").asText())) {
                throw new IllegalArgumentException("Workflow output reference is unavailable");
            }
            return JSON.getNodeFactory()
                    .textNode(outputs.get(value.path("$output").asText()));
        }
        if (value.isObject()) {
            var result = JSON.createObjectNode();
            value.properties()
                    .forEach(entry -> result.set(entry.getKey(), resolve(entry.getValue(), outputs, depth + 1)));
            return result;
        }
        if (value.isArray()) {
            var result = JSON.createArrayNode();
            value.forEach(entry -> result.add(resolve(entry, outputs, depth + 1)));
            return result;
        }
        return value.deepCopy();
    }

    /** 验证预算、节点连接、循环边界及验收工具；非法定义在保存之前被拒绝。 */
    public static AutomationPlan parse(AutomationKind kind, String definitionJson) {
        if (definitionJson == null || definitionJson.length() > 262_144) {
            throw new IllegalArgumentException("automation definition exceeds size limit");
        }
        try {
            JsonNode root = JSON.readTree(definitionJson);
            object(root, "definition");
            fields(
                    root,
                    Set.of(
                            "maxIterations",
                            "maxModelCalls",
                            "maxTokens",
                            "maxDurationSeconds",
                            "noProgressLimit",
                            "successCriteria",
                            "nodes",
                            "specification",
                            "openSpecDocuments"));
            var limits = new AutomationPlan.Limits(
                    number(root, "maxIterations", kind == AutomationKind.WORKFLOW ? 100 : 25, 1_000),
                    number(root, "maxModelCalls", 100, 1_000),
                    number(root, "maxTokens", 200_000, 10_000_000),
                    number(root, "maxDurationSeconds", 3_600, 86_400),
                    number(root, "noProgressLimit", 3, 25));
            List<AutomationPlan.Criterion> criteria = criteria(root.path("successCriteria"));
            List<AutomationPlan.Node> nodes = kind == AutomationKind.WORKFLOW ? nodes(root.path("nodes")) : List.of();
            if (kind != AutomationKind.WORKFLOW && root.has("nodes")) {
                throw new IllegalArgumentException("nodes are only valid for Workflow");
            }
            var documents = new java.util.TreeMap<String, String>();
            if (root.has("openSpecDocuments")) {
                JsonNode value = root.path("openSpecDocuments");
                if (kind != AutomationKind.SDD || !value.isObject() || value.size() > 32) {
                    throw new IllegalArgumentException("OpenSpec documents require a bounded SDD definition");
                }
                int total = 0;
                for (var entry : value.properties()) {
                    if (!entry.getKey().matches("(proposal|design|tasks)\\.md|specs/[A-Za-z0-9_-]{1,80}/spec\\.md")
                            || !entry.getValue().isTextual()) {
                        throw new IllegalArgumentException("invalid OpenSpec document path or text");
                    }
                    String body = entry.getValue().asText();
                    total += body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                    if (body.length() > 32_768 || total > 65_536) {
                        throw new IllegalArgumentException("OpenSpec documents exceed the context limit");
                    }
                    documents.put(entry.getKey(), body);
                }
            }
            return new AutomationPlan(kind, limits, criteria, nodes, text(root, "specification", ""), documents);
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("invalid automation JSON", failure);
        }
    }

    private static List<AutomationPlan.Criterion> criteria(JsonNode value) {
        if (value.isMissingNode() || (value.isArray() && value.isEmpty())) {
            return List.of(new AutomationPlan.Criterion(
                    "user-acceptance",
                    "确认目标已达到并接受当前结果",
                    AutomationPlan.CriterionKind.USER_CONFIRMATION,
                    "",
                    "{}",
                    "",
                    "确认"));
        }
        if (!value.isArray() || value.size() > 25) {
            throw new IllegalArgumentException("successCriteria must contain at most 25 criteria");
        }
        var result = new ArrayList<AutomationPlan.Criterion>();
        var ids = new HashSet<String>();
        for (JsonNode criterion : value) {
            object(criterion, "criterion");
            fields(criterion, Set.of("id", "description", "kind", "tool", "arguments", "field", "expected"));
            String id = identifier(criterion, "id");
            if (!ids.add(id)) {
                throw new IllegalArgumentException("duplicate criterion id: " + id);
            }
            var kind = AutomationPlan.CriterionKind.valueOf(required(criterion, "kind"));
            String tool = kind == AutomationPlan.CriterionKind.USER_CONFIRMATION ? "" : required(criterion, "tool");
            String field = kind == AutomationPlan.CriterionKind.RESULT_FIELD ? required(criterion, "field") : "";
            String expected =
                    text(criterion, "expected", kind == AutomationPlan.CriterionKind.COMMAND_EXIT ? "0" : "确认");
            if (kind == AutomationPlan.CriterionKind.COMMAND_EXIT) {
                Integer.parseInt(expected);
            }
            result.add(new AutomationPlan.Criterion(
                    id, required(criterion, "description"), kind, tool, arguments(criterion), field, expected));
        }
        return List.copyOf(result);
    }

    private static List<AutomationPlan.Node> nodes(JsonNode value) {
        if (!value.isArray() || value.isEmpty() || value.size() > 128) {
            throw new IllegalArgumentException("Workflow requires 1..128 nodes");
        }
        var nodes = new LinkedHashMap<String, AutomationPlan.Node>();
        var explicitBounds = new HashSet<String>();
        for (JsonNode node : value) {
            object(node, "node");
            fields(node, Set.of("id", "kind", "next", "otherwise", "maxVisits", "parameters"));
            String id = identifier(node, "id");
            var kind = AutomationPlan.NodeKind.valueOf(required(node, "kind"));
            Map<String, String> parameters = parameters(kind, node.path("parameters"));
            var parsed = new AutomationPlan.Node(
                    id,
                    kind,
                    text(node, "next", ""),
                    text(node, "otherwise", ""),
                    number(node, "maxVisits", 1, 100),
                    parameters);
            if (nodes.putIfAbsent(id, parsed) != null) {
                throw new IllegalArgumentException("duplicate Workflow node: " + id);
            }
            if (node.has("maxVisits") && node.path("maxVisits").asInt() > 0) {
                explicitBounds.add(id);
            }
        }
        List<AutomationPlan.Node> starts = nodes.values().stream()
                .filter(node -> node.kind() == AutomationPlan.NodeKind.START)
                .toList();
        if (starts.size() != 1
                || nodes.values().stream().noneMatch(node -> node.kind() == AutomationPlan.NodeKind.END)) {
            throw new IllegalArgumentException("Workflow requires exactly one START and at least one END");
        }
        for (var node : nodes.values()) {
            if (node.kind() == AutomationPlan.NodeKind.END) {
                if (!node.next().isEmpty() || !node.otherwise().isEmpty()) {
                    throw new IllegalArgumentException("END cannot have outgoing edges");
                }
            } else if (!nodes.containsKey(node.next())
                    || (node.kind() == AutomationPlan.NodeKind.CONDITION && !nodes.containsKey(node.otherwise()))) {
                throw new IllegalArgumentException("Workflow edge refers to missing node: " + node.id());
            }
            if (node.kind() != AutomationPlan.NodeKind.CONDITION
                    && !node.otherwise().isEmpty()) {
                throw new IllegalArgumentException("otherwise is only valid on CONDITION");
            }
        }
        var visited = new HashSet<String>();
        visit(starts.getFirst().id(), nodes, explicitBounds, visited, new ArrayList<>());
        if (visited.size() != nodes.size()) {
            throw new IllegalArgumentException("Workflow contains unreachable nodes");
        }
        for (var node : nodes.values()) {
            if ((reachable(node.next(), node.id(), nodes, new HashSet<>())
                            || reachable(node.otherwise(), node.id(), nodes, new HashSet<>()))
                    && !explicitBounds.contains(node.id())) {
                throw new IllegalArgumentException("every cyclic node must declare a finite maxVisits");
            }
            String input = node.parameters().get("input");
            if (input != null
                    && (!nodes.containsKey(input)
                            || input.equals(node.id())
                            || !reachable(input, node.id(), nodes, new HashSet<>()))) {
                throw new IllegalArgumentException("Workflow input must reference an earlier reachable node");
            }
            if (node.parameters().containsKey("arguments")) {
                var known = new LinkedHashMap<String, String>();
                nodes.values().stream()
                        .filter(candidate -> !candidate.id().equals(node.id())
                                && reachable(candidate.id(), node.id(), nodes, new HashSet<>()))
                        .forEach(candidate -> known.put(candidate.id(), ""));
                resolveArguments(node.parameters().get("arguments"), known);
            }
        }
        return List.copyOf(nodes.values());
    }

    private static boolean reachable(
            String from, String target, Map<String, AutomationPlan.Node> nodes, Set<String> seen) {
        if (from == null || from.isEmpty() || !seen.add(from)) {
            return false;
        }
        if (from.equals(target)) {
            return true;
        }
        var node = nodes.get(from);
        return node != null
                && (reachable(node.next(), target, nodes, seen) || reachable(node.otherwise(), target, nodes, seen));
    }

    private static void visit(
            String id,
            Map<String, AutomationPlan.Node> nodes,
            Set<String> bounds,
            Set<String> visited,
            List<String> stack) {
        int cycle = stack.indexOf(id);
        if (cycle >= 0) {
            if (!bounds.containsAll(stack.subList(cycle, stack.size()))) {
                throw new IllegalArgumentException("every node in a cycle must explicitly declare maxVisits");
            }
            return;
        }
        if (!visited.add(id)) {
            return;
        }
        stack.add(id);
        var node = nodes.get(id);
        if (!node.next().isEmpty()) {
            visit(node.next(), nodes, bounds, visited, stack);
        }
        if (!node.otherwise().isEmpty()) {
            visit(node.otherwise(), nodes, bounds, visited, stack);
        }
        stack.removeLast();
    }

    private static Map<String, String> parameters(AutomationPlan.NodeKind kind, JsonNode value) {
        if (value.isMissingNode()) {
            value = JSON.createObjectNode();
        }
        object(value, "node.parameters");
        Set<String> allowed =
                switch (kind) {
                    case START, END -> Set.of();
                    case AGENT -> Set.of("task", "profileId", "writable", "input");
                    case TOOL -> Set.of("tool", "arguments");
                    case CONDITION -> Set.of("input", "operator", "expected");
                    case TRANSFORM -> Set.of("input", "operation", "value");
                    case HUMAN_INPUT -> Set.of("prompt");
                    case OUTPUT -> Set.of("input", "name");
                };
        fields(value, allowed);
        var result = new LinkedHashMap<String, String>();
        for (var entry : value.properties()) {
            if ("arguments".equals(entry.getKey())) {
                result.put("arguments", arguments(value));
            } else if (!entry.getValue().isValueNode() || entry.getValue().isNull()) {
                throw new IllegalArgumentException("node parameter must be a scalar: " + entry.getKey());
            } else {
                result.put(entry.getKey(), entry.getValue().asText());
            }
        }
        List<String> required =
                switch (kind) {
                    case START, END -> List.of();
                    case AGENT -> List.of("task", "profileId");
                    case TOOL -> List.of("tool");
                    case CONDITION -> List.of("input", "operator", "expected");
                    case TRANSFORM -> List.of("operation");
                    case HUMAN_INPUT -> List.of("prompt");
                    case OUTPUT -> List.of("input", "name");
                };
        required.forEach(key -> {
            if (!result.containsKey(key) || result.get(key).isBlank()) {
                throw new IllegalArgumentException("missing node parameter: " + key);
            }
        });
        if (kind == AutomationPlan.NodeKind.CONDITION
                && !Set.of("equals", "notEquals", "contains", "isEmpty").contains(result.get("operator"))) {
            throw new IllegalArgumentException("unsupported condition operator");
        }
        if (kind == AutomationPlan.NodeKind.TRANSFORM
                && !Set.of("constant", "copy", "trim", "uppercase", "lowercase", "append")
                        .contains(result.get("operation"))) {
            throw new IllegalArgumentException("unsupported transform operation");
        }
        return Map.copyOf(result);
    }

    private static String arguments(JsonNode root) {
        JsonNode value = root.path("arguments");
        if (value.isMissingNode()) {
            return "{}";
        }
        object(value, "arguments");
        return value.toString();
    }

    private static String identifier(JsonNode node, String field) {
        String id = required(node, field);
        if (!id.matches("[A-Za-z0-9_-]{1,80}")) {
            throw new IllegalArgumentException("invalid automation identifier: " + field);
        }
        return id;
    }

    private static String required(JsonNode node, String field) {
        String value = text(node, field, "");
        if (value.isBlank()) {
            throw new IllegalArgumentException("missing automation field: " + field);
        }
        return value;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        if (value.isMissingNode()) {
            return fallback;
        }
        if (!value.isTextual() || value.textValue().length() > 65_536) {
            throw new IllegalArgumentException("invalid automation text: " + field);
        }
        return value.textValue();
    }

    private static int number(JsonNode root, String name, int fallback, int maximum) {
        JsonNode value = root.path(name);
        if (value.isMissingNode()) {
            return fallback;
        }
        if (!value.isIntegralNumber()
                || !value.canConvertToInt()
                || value.intValue() < 0
                || value.intValue() > maximum) {
            throw new IllegalArgumentException("invalid finite automation budget: " + name);
        }
        return value.intValue() == 0 ? fallback : value.intValue();
    }

    private static void fields(JsonNode node, Set<String> allowed) {
        node.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException("unknown automation field: " + name);
            }
        });
    }

    private static void object(JsonNode value, String name) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(name + " must be an object");
        }
    }
}
