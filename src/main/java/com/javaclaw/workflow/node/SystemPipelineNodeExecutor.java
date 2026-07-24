package com.javaclaw.workflow.node;

import com.javaclaw.workflow.runtime.NodeExecutionContext;
import com.javaclaw.workflow.runtime.NodeExecutor;
import com.javaclaw.workflow.runtime.NodeResult;
import com.javaclaw.workflow.service.SystemPipeline;

/** 系统图专用节点；每个节点都必须委派给运行时注入的真实阶段实现。 */
public final class SystemPipelineNodeExecutor implements NodeExecutor {
    @Override public String type() { return "system.pipeline"; }

    @Override
    public java.util.List<String> validate(com.javaclaw.workflow.model.NodeDefinition node) {
        String stageId = node.config().path("stageId").asText("");
        if (stageId.isBlank()) return java.util.List.of("系统阶段必须配置 stageId");
        if (!stageId.equals(node.id())) return java.util.List.of("系统阶段 stageId 必须与节点 ID 一致");
        return java.util.List.of();
    }

    @Override public NodeResult execute(NodeExecutionContext context) throws Exception {
        String stageId = context.node().config().path("stageId").asText();
        return context.require(SystemPipeline.class).executeStage(stageId, context);
    }
}
