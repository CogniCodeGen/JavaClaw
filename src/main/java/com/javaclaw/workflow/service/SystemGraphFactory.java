package com.javaclaw.workflow.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.workflow.model.EdgeDefinition;
import com.javaclaw.workflow.model.EdgeKind;
import com.javaclaw.workflow.model.ConditionOperator;
import com.javaclaw.workflow.model.ConditionRule;
import com.javaclaw.workflow.model.GraphDefinition;
import com.javaclaw.workflow.model.GraphKind;
import com.javaclaw.workflow.model.NodeDefinition;
import com.javaclaw.workflow.model.NodeType;
import com.javaclaw.workflow.model.ResumeSafety;
import com.javaclaw.workflow.model.RetryPolicy;

import java.util.List;
import java.util.ArrayList;

/** 为现有稳定领域管线创建只读、可检查点且与真实执行边界一致的系统图。 */
public final class SystemGraphFactory {
    private SystemGraphFactory() {}

    /** 创建一个仍需原子执行的兼容系统图；内置主流程应使用下方的阶段图。 */
    public static GraphDefinition pipeline(String id, String name, String description, String stageLabel) {
        var empty = JsonNodeFactory.instance.objectNode();
        var stageConfig = JsonNodeFactory.instance.objectNode().put("stageId", "pipeline");
        NodeDefinition start = new NodeDefinition("start", NodeType.START, "start", "开始",
                empty, 60, 120, RetryPolicy.NONE, ResumeSafety.SAFE);
        NodeDefinition pipeline = new NodeDefinition("pipeline", NodeType.SYSTEM, "system.pipeline", stageLabel,
                stageConfig, 260, 120, RetryPolicy.NONE, ResumeSafety.CONFIRM_RETRY);
        NodeDefinition end = new NodeDefinition("end", NodeType.END, "end", "结束",
                empty, 500, 120, RetryPolicy.NONE, ResumeSafety.SAFE);
        List<NodeDefinition> nodes = List.of(start, pipeline, end);
        List<EdgeDefinition> edges = List.of(
                new EdgeDefinition("start-pipeline", "start", "pipeline",
                        EdgeKind.NORMAL, null, 0, false),
                new EdgeDefinition("pipeline-end", "pipeline", "end",
                        EdgeKind.NORMAL, null, 0, false));
        return new GraphDefinition(GraphDefinition.CURRENT_SCHEMA, id, name, description, 1,
                GraphKind.SYSTEM, "start", nodes, edges, 16);
    }

    public static GraphDefinition chat() {
        return sequential("system-chat", "对话编排",
                "视觉准备、路由、目标、知识、记忆、Agent 与 GEPA 管线",
                List.of(new Stage("vision", "视觉准备", ResumeSafety.SAFE),
                        new Stage("orchestrate", "路由与编排执行", ResumeSafety.CONFIRM_RETRY)));
    }

    public static GraphDefinition plan() {
        return sequential("system-plan", "研讨编排", "知识增强、专家选择、多轮讨论与协调汇总",
                List.of(new Stage("knowledge", "知识增强", ResumeSafety.SAFE),
                        new Stage("discussion", "专家讨论与汇总", ResumeSafety.CONFIRM_RETRY)));
    }

    public static GraphDefinition loop() {
        return sequential("system-loop", "循环编排", "目标预检、执行、核验、裁决与停止护栏",
                List.of(new Stage("preflight", "目标分解与安全预检", ResumeSafety.CONFIRM_RETRY),
                        new Stage("run", "循环执行与核验", ResumeSafety.CONFIRM_RETRY)));
    }

    public static GraphDefinition sdd() {
        var graph = sequential("system-sdd", "SDD 托管编排",
                "OpenSpec 提案、规格、设计、任务、实现、验收与归档",
                List.of(new Stage("prepare", "OpenSpec 提案至任务拆解", ResumeSafety.CONFIRM_RETRY),
                        new Stage("implement", "实现、验收与归档", ResumeSafety.CONFIRM_RETRY)));
        List<EdgeDefinition> edges = new ArrayList<>(graph.edges());
        edges.removeIf(edge -> edge.source().equals("prepare"));
        edges.add(new EdgeDefinition("prepare-implement", "prepare", "implement",
                EdgeKind.CONDITIONAL,
                new ConditionRule("sdd.proceed", ConditionOperator.EQUAL,
                        JsonNodeFactory.instance.booleanNode(true)),
                0, false));
        edges.add(new EdgeDefinition("prepare-end", "prepare", "end",
                EdgeKind.CONDITIONAL, null, 1, true));
        return new GraphDefinition(graph.schemaVersion(), graph.id(), graph.name(), graph.description(),
                graph.version(), graph.kind(), graph.startNodeId(), graph.nodes(), edges, graph.maxSteps());
    }

    private static GraphDefinition sequential(
            String id, String name, String description, List<Stage> stages) {
        var empty = JsonNodeFactory.instance.objectNode();
        List<NodeDefinition> nodes = new ArrayList<>();
        List<EdgeDefinition> edges = new ArrayList<>();
        NodeDefinition start = new NodeDefinition("start", NodeType.START, "start", "开始",
                empty, 60, 120, RetryPolicy.NONE, ResumeSafety.SAFE);
        nodes.add(start);
        String previous = "start";
        double x = 260;
        for (Stage stage : stages) {
            var config = JsonNodeFactory.instance.objectNode().put("stageId", stage.id());
            nodes.add(new NodeDefinition(stage.id(), NodeType.SYSTEM, "system.pipeline", stage.label(),
                    config, x, 120, RetryPolicy.NONE, stage.safety()));
            edges.add(new EdgeDefinition(previous + "-" + stage.id(), previous, stage.id(),
                    EdgeKind.NORMAL, null, 0, false));
            previous = stage.id();
            x += 260;
        }
        nodes.add(new NodeDefinition("end", NodeType.END, "end", "结束",
                empty, x, 120, RetryPolicy.NONE, ResumeSafety.SAFE));
        edges.add(new EdgeDefinition(previous + "-end", previous, "end",
                EdgeKind.NORMAL, null, 0, false));
        return new GraphDefinition(GraphDefinition.CURRENT_SCHEMA, id, name, description, 1,
                GraphKind.SYSTEM, "start", nodes, edges, Math.max(16, stages.size() * 4 + 4));
    }

    private record Stage(String id, String label, ResumeSafety safety) {}
}
