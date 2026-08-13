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
                    int topK = com.javaclaw.framework.api.CapabilityRuntime.configuration(
                            request, "memory.recall").path("topK").asInt(8);
                    return Objects.requireNonNull(recall, "recall").recall(request, query, topK);
                }));
    }

    private static ObjectNode schema() {
        ObjectNode schema = com.javaclaw.framework.builtin.BuiltinSchemas.objectSchema();
        com.javaclaw.framework.builtin.BuiltinSchemas.booleanProperty(schema, "enabled", true);
        com.javaclaw.framework.builtin.BuiltinSchemas.integerProperty(schema, "topK", 8, 1, 50);
        return schema;
    }
}
