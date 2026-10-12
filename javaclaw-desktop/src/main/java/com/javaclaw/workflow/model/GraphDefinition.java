package com.javaclaw.workflow.model;

import java.util.List;
import java.util.Objects;

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
        if (schemaVersion != CURRENT_SCHEMA) {
            throw new IllegalArgumentException("不支持的工作流 Schema 版本: " + schemaVersion);
        }
        id = id == null ? "" : id.trim();
        name = name == null ? "" : name.trim();
        description = description == null ? "" : description.trim();
        if (version < 1) throw new IllegalArgumentException("工作流版本必须大于零");
        kind = Objects.requireNonNull(kind, "kind");
        startNodeId = startNodeId == null ? "" : startNodeId.trim();
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
        if (maxSteps < 1 || maxSteps > 10_000) {
            throw new IllegalArgumentException("maxSteps 必须在 1..10000 之间");
        }
    }
}
