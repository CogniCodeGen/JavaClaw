package com.javaclaw.workflow.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/** 可序列化节点定义。 */
public record NodeDefinition(
        String id,
        NodeType type,
        String executorType,
        String label,
        JsonNode config,
        double x,
        double y,
        RetryPolicy retryPolicy,
        ResumeSafety resumeSafety) {

    public NodeDefinition {
        id = id == null ? "" : id.trim();
        type = Objects.requireNonNull(type, "type");
        if (executorType == null || executorType.isBlank()) {
            throw new IllegalArgumentException("节点执行器类型不能为空");
        }
        executorType = executorType.trim();
        label = label == null || label.isBlank() ? id : label.trim();
        config = Objects.requireNonNull(config, "config").deepCopy();
        retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        resumeSafety = Objects.requireNonNull(resumeSafety, "resumeSafety");
    }

    @Override public JsonNode config() { return config.deepCopy(); }
}
