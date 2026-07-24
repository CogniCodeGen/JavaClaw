package com.javaclaw.workflow.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

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
        type = type == null ? NodeType.SYSTEM : type;
        executorType = executorType == null || executorType.isBlank()
                ? type.name().toLowerCase() : executorType.trim();
        label = label == null || label.isBlank() ? id : label.trim();
        config = config == null ? JsonNodeFactory.instance.objectNode() : config.deepCopy();
        retryPolicy = retryPolicy == null ? RetryPolicy.NONE : retryPolicy;
        resumeSafety = resumeSafety == null ? ResumeSafety.SAFE : resumeSafety;
    }

    @Override public JsonNode config() { return config.deepCopy(); }
}
