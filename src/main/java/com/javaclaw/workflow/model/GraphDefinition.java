package com.javaclaw.workflow.model;

import java.util.List;

/** 不可变、可持久化的图定义。 */
public record GraphDefinition(
        int schemaVersion,
        String id,
        String name,
        String description,
        int version,
        GraphKind kind,
        String startNodeId,
        List<NodeDefinition> nodes,
        List<EdgeDefinition> edges,
        int maxSteps) {

    public static final int CURRENT_SCHEMA = 1;

    public GraphDefinition {
        schemaVersion = schemaVersion <= 0 ? CURRENT_SCHEMA : schemaVersion;
        id = id == null ? "" : id.trim();
        name = name == null ? "" : name.trim();
        description = description == null ? "" : description.trim();
        version = Math.max(1, version);
        kind = kind == null ? GraphKind.CUSTOM : kind;
        startNodeId = startNodeId == null ? "" : startNodeId.trim();
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        maxSteps = maxSteps <= 0 ? 200 : Math.min(maxSteps, 10_000);
    }
}
