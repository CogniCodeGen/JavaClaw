package com.javaclaw.builtin.extensions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 自动化启动 Schema 复用独立执行选择，未知字段始终拒绝。 */
final class ExecutionSelectionSchema {
    private ExecutionSelectionSchema() {}

    static Map<String, Object> create() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("role", optional(reference("id", "revision")));
        fields.put(
                "provider",
                optional(ContractSchemaFactory.object(
                        Map.of(
                                "endpointId",
                                ContractSchemaFactory.string(),
                                "endpointRevision",
                                ContractSchemaFactory.integer(1),
                                "model",
                                ContractSchemaFactory.string()),
                        List.of("endpointId", "endpointRevision", "model"))));
        fields.put("permissionProfile", optional(reference("id", "version")));
        fields.put("approvalPolicy", optional(Map.of("enum", List.of("NONE", "RISKY", "EVERY_CALL"))));
        fields.put(
                "budget",
                optional(ContractSchemaFactory.object(
                        Map.of(
                                "inputTokens",
                                ContractSchemaFactory.integer(1),
                                "outputTokens",
                                ContractSchemaFactory.integer(1),
                                "toolCalls",
                                ContractSchemaFactory.integer(0),
                                "childThreads",
                                ContractSchemaFactory.integer(0),
                                "wallTime",
                                ContractSchemaFactory.string()),
                        List.of("inputTokens", "outputTokens", "toolCalls", "childThreads", "wallTime"))));
        fields.put(
                "visibleCapabilities",
                optional(Map.of("type", "array", "uniqueItems", true, "items", ContractSchemaFactory.string())));
        fields.put(
                "reasoning",
                optional(Map.of("enum", List.of("NONE", "MINIMAL", "LOW", "MEDIUM", "HIGH", "XHIGH", "MAX"))));
        return ContractSchemaFactory.object(fields, List.copyOf(fields.keySet()));
    }

    private static Map<String, Object> reference(String id, String revision) {
        return ContractSchemaFactory.object(
                Map.of(id, ContractSchemaFactory.string(), revision, ContractSchemaFactory.integer(1)),
                List.of(id, revision));
    }

    private static Map<String, Object> optional(Map<String, Object> schema) {
        return Map.of("anyOf", List.of(schema, Map.of("type", "null")));
    }
}
