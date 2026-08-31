package com.javaclaw.sdk;

import java.util.ArrayList;
import java.util.LinkedHashMap;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.sdk.model.AutomationDefinitionInfo;
import com.javaclaw.sdk.model.JsonDocument;

/** 自动化表单与协议 JSON 的显式映射；领域算法只在服务端运行。 */
final class AutomationDocuments {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private AutomationDocuments() {}

    static AutomationDefinitionInfo read(JsonDocument document) {
        try {
            var value = JSON.readTree(document.canonicalJson());
            if (!value.isObject()) {
                throw new IllegalArgumentException("自动化定义必须是对象");
            }
            var criteria = new ArrayList<AutomationDefinitionInfo.Criterion>();
            for (var criterion : value.path("successCriteria")) {
                criteria.add(new AutomationDefinitionInfo.Criterion(
                        criterion.path("id").asText(),
                        criterion.path("description").asText(),
                        criterion.path("kind").asText(),
                        criterion.path("tool").asText(),
                        new JsonDocument(
                                criterion.path("arguments").isObject()
                                        ? criterion.path("arguments").toString()
                                        : "{}"),
                        criterion.path("field").asText(),
                        criterion.path("expected").asText("确认")));
            }
            var nodes = new ArrayList<AutomationDefinitionInfo.Node>();
            for (var node : value.path("nodes")) {
                var parameters = new LinkedHashMap<String, String>();
                node.path("parameters")
                        .properties()
                        .forEach(entry -> parameters.put(
                                entry.getKey(),
                                entry.getKey().equals("arguments")
                                        ? entry.getValue().toString()
                                        : entry.getValue().asText()));
                nodes.add(new AutomationDefinitionInfo.Node(
                        node.path("id").asText(),
                        node.path("kind").asText(),
                        node.path("next").asText(),
                        node.path("otherwise").asText(),
                        node.path("maxVisits").asInt(1),
                        parameters));
            }
            return new AutomationDefinitionInfo(
                    value.path("maxIterations").asInt(),
                    value.path("maxModelCalls").asInt(),
                    value.path("maxTokens").asInt(),
                    value.path("maxDurationSeconds").asInt(),
                    value.path("noProgressLimit").asInt(3),
                    value.path("specification").asText(),
                    criteria,
                    nodes,
                    strings(value.path("openSpecDocuments")));
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("自动化定义不是有效 JSON", failure);
        }
    }

    static JsonDocument write(AutomationDefinitionInfo definition) {
        try {
            var value = JSON.createObjectNode()
                    .put("maxIterations", definition.maxIterations())
                    .put("maxModelCalls", definition.maxModelCalls())
                    .put("maxTokens", definition.maxTokens())
                    .put("maxDurationSeconds", definition.maxDurationSeconds())
                    .put("noProgressLimit", definition.noProgressLimit())
                    .put("specification", definition.specification());
            if (!definition.openSpecDocuments().isEmpty()) {
                var documents = value.putObject("openSpecDocuments");
                new java.util.TreeMap<>(definition.openSpecDocuments()).forEach(documents::put);
            }
            var criteria = value.putArray("successCriteria");
            for (var item : definition.criteria()) {
                var node = criteria.addObject()
                        .put("id", item.id())
                        .put("description", item.description())
                        .put("kind", item.kind())
                        .put("tool", item.tool())
                        .put("field", item.field())
                        .put("expected", item.expected());
                var arguments = JSON.readTree(item.arguments().canonicalJson());
                if (!arguments.isObject()) {
                    throw new IllegalArgumentException("验收工具参数必须是对象");
                }
                node.set("arguments", arguments);
            }
            if (!definition.nodes().isEmpty()) {
                var nodes = value.putArray("nodes");
                for (var item : definition.nodes()) {
                    var node = nodes.addObject()
                            .put("id", item.id())
                            .put("kind", item.kind())
                            .put("next", item.next())
                            .put("otherwise", item.otherwise())
                            .put("maxVisits", item.maxVisits());
                    var parameters = node.putObject("parameters");
                    for (var entry : item.parameters().entrySet()) {
                        if (entry.getKey().equals("arguments")) {
                            parameters.set(entry.getKey(), JSON.readTree(entry.getValue()));
                        } else {
                            parameters.put(entry.getKey(), entry.getValue());
                        }
                    }
                }
            }
            return new JsonDocument(value.toString());
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("工具参数不是有效 JSON", failure);
        }
    }

    private static java.util.Map<String, String> strings(com.fasterxml.jackson.databind.JsonNode value) {
        if (value.isMissingNode()) {
            return java.util.Map.of();
        }
        if (!value.isObject()) {
            throw new IllegalArgumentException("OpenSpec 文档必须是对象");
        }
        var result = new java.util.TreeMap<String, String>();
        value.properties().forEach(entry -> {
            if (!entry.getValue().isTextual()) {
                throw new IllegalArgumentException("OpenSpec 文档必须是文本");
            }
            result.put(entry.getKey(), entry.getValue().asText());
        });
        return java.util.Map.copyOf(result);
    }
}
