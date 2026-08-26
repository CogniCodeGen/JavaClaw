package com.javaclaw.framework.builtin.memory;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.builtin.BuiltinCapabilityExtension;
import com.javaclaw.framework.spi.ExtensionDependency;

import java.util.List;
import java.util.Objects;

public final class MemoryRecallExtension extends BuiltinCapabilityExtension {
    public MemoryRecallExtension(MemoryRecallGateway recall) {
        super("memory.recall", "Memory Recall", "EclipseStore graph and vector recall",
                schema(), com.javaclaw.framework.builtin.BuiltinSchemas.ui("Memory", 20),
                List.of(new ExtensionDependency("memory.graph", ">=2.0.0 <3.0.0", false)),
                registrar -> registrar.promptContributor((request, state) -> {
                    String query = request.inputs().stream()
                            .filter(block -> block.type().equals("core.text"))
                            .map(block -> block.data().path("text").asText())
                            .collect(java.util.stream.Collectors.joining("\n"));
                    int topK = Math.max(1, Math.min(3,
                            com.javaclaw.framework.api.CapabilityRuntime.configuration(
                                    request, "memory.recall").path("topK").asInt(3)));
                    String result = Objects.requireNonNull(recall, "recall").recall(request, query, topK);
                    return com.javaclaw.util.TokenEstimator.truncateToTokens(result, 1_200);
                }));
    }

    private static ObjectNode schema() {
        ObjectNode schema = com.javaclaw.framework.builtin.BuiltinSchemas.objectSchema();
        com.javaclaw.framework.builtin.BuiltinSchemas.booleanProperty(schema, "enabled", true);
        com.javaclaw.framework.builtin.BuiltinSchemas.integerProperty(schema, "topK", 3, 1, 50);
        com.javaclaw.framework.builtin.BuiltinSchemas.authoringMaximum(schema, "topK", 3);
        return schema;
    }
}
