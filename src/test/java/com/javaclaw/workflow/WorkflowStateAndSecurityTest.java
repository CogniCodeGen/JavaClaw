package com.javaclaw.workflow;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.workflow.model.GraphState;
import com.javaclaw.workflow.model.GraphDefinition;
import com.javaclaw.workflow.model.GraphKind;
import com.javaclaw.workflow.model.EdgeDefinition;
import com.javaclaw.workflow.model.EdgeKind;
import com.javaclaw.workflow.model.ConditionOperator;
import com.javaclaw.workflow.model.ConditionRule;
import com.javaclaw.workflow.model.NodeDefinition;
import com.javaclaw.workflow.model.NodeType;
import com.javaclaw.workflow.model.ResumeSafety;
import com.javaclaw.workflow.model.RetryPolicy;
import com.javaclaw.workflow.model.StatePatch;
import com.javaclaw.workflow.node.AgentNodeExecutor;
import com.javaclaw.workflow.node.PublicNodeCatalog;
import com.javaclaw.workflow.node.ToolNodeExecutor;
import com.javaclaw.workflow.editor.WorkflowEditorModel;
import com.javaclaw.workflow.runtime.GraphValidator;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowStateAndSecurityTest {

    @Test
    void 空白会话工作流自带输出节点并可直接发布() {
        GraphDefinition blank = WorkflowEditorModel.blank("空白工作流");

        var issues = GraphValidator.validate(blank, PublicNodeCatalog.createRegistry());

        assertTrue(issues.isEmpty(), () -> "空白模板不应产生校验错误: " + issues);
        NodeDefinition output = blank.nodes().stream()
                .filter(node -> node.type() == NodeType.OUTPUT)
                .findFirst().orElseThrow();
        assertEquals("{{input}}", output.config().path("template").asText());
        assertEquals("output", output.config().path("outputKey").asText());
        assertTrue(blank.edges().stream().anyMatch(edge ->
                edge.source().equals("start") && edge.target().equals(output.id())));
        assertTrue(blank.edges().stream().anyMatch(edge ->
                edge.source().equals(output.id()) && edge.target().equals("end")));
    }

    @Test
    void 任一终态分支缺少输出都会阻止发布() {
        var empty = JsonNodeFactory.instance.objectNode();
        NodeDefinition start = new NodeDefinition("start", NodeType.START, "start", "开始",
                empty, 0, 0, RetryPolicy.NONE, ResumeSafety.SAFE);
        NodeDefinition route = new NodeDefinition("route", NodeType.CONDITION, "condition", "分支",
                empty, 0, 0, RetryPolicy.NONE, ResumeSafety.SAFE);
        NodeDefinition output = new NodeDefinition("output", NodeType.OUTPUT, "output", "输出",
                empty.deepCopy().put("template", "完成"), 0, 0, RetryPolicy.NONE, ResumeSafety.SAFE);
        NodeDefinition withOutput = new NodeDefinition("with-output", NodeType.END, "end", "正常结束",
                empty, 0, 0, RetryPolicy.NONE, ResumeSafety.SAFE);
        NodeDefinition silent = new NodeDefinition("silent", NodeType.END, "end", "静默结束",
                empty, 0, 0, RetryPolicy.NONE, ResumeSafety.SAFE);
        GraphDefinition graph = new GraphDefinition(1, "wf-output-branches", "分支输出", "",
                1, GraphKind.CUSTOM, "start",
                List.of(start, route, output, withOutput, silent),
                List.of(
                        new EdgeDefinition("start-route", "start", "route",
                                EdgeKind.NORMAL, null, 0, false),
                        new EdgeDefinition("route-output", "route", "output",
                                EdgeKind.CONDITIONAL,
                                new com.javaclaw.workflow.model.ConditionRule(
                                        "ok", com.javaclaw.workflow.model.ConditionOperator.EQUAL,
                                        JsonNodeFactory.instance.booleanNode(true)),
                                0, false),
                        new EdgeDefinition("route-silent", "route", "silent",
                                EdgeKind.CONDITIONAL, null, 1, true),
                        new EdgeDefinition("output-end", "output", "with-output",
                                EdgeKind.NORMAL, null, 0, false)),
                20);

        var issues = GraphValidator.validate(graph, PublicNodeCatalog.createRegistry());

        assertTrue(issues.stream().anyMatch(issue -> issue.message().contains("OUTPUT")));
    }

    @Test
    void 条件边在发布前拒绝非法路径和错误的比较值类型() {
        var empty = JsonNodeFactory.instance.objectNode();
        NodeDefinition start = new NodeDefinition("start", NodeType.START, "start", "开始",
                empty, 0, 0, RetryPolicy.NONE, ResumeSafety.SAFE);
        NodeDefinition route = new NodeDefinition("route", NodeType.CONDITION, "condition", "分支",
                empty, 0, 0, RetryPolicy.NONE, ResumeSafety.SAFE);
        NodeDefinition output = new NodeDefinition("output", NodeType.OUTPUT, "output", "输出",
                empty.deepCopy().put("template", "完成"), 0, 0, RetryPolicy.NONE, ResumeSafety.SAFE);
        NodeDefinition end = new NodeDefinition("end", NodeType.END, "end", "结束",
                empty, 0, 0, RetryPolicy.NONE, ResumeSafety.SAFE);
        GraphDefinition graph = new GraphDefinition(1, "wf-condition-validation", "条件校验", "",
                1, GraphKind.CUSTOM, "start", List.of(start, route, output, end), List.of(
                new EdgeDefinition("start-route", "start", "route",
                        EdgeKind.NORMAL, null, 0, false),
                new EdgeDefinition("invalid-path", "route", "output",
                        EdgeKind.CONDITIONAL,
                        new ConditionRule("result..ok", ConditionOperator.EQUAL,
                                JsonNodeFactory.instance.booleanNode(true)),
                        0, false),
                new EdgeDefinition("invalid-number", "route", "output",
                        EdgeKind.CONDITIONAL,
                        new ConditionRule("result.score", ConditionOperator.GTE,
                                JsonNodeFactory.instance.textNode("high")),
                        1, false),
                new EdgeDefinition("fallback", "route", "output",
                        EdgeKind.CONDITIONAL, null, 2, true),
                new EdgeDefinition("output-end", "output", "end",
                        EdgeKind.NORMAL, null, 0, false)), 20);

        var issues = GraphValidator.validate(graph, PublicNodeCatalog.createRegistry());

        assertTrue(issues.stream().anyMatch(issue -> issue.elementId().equals("invalid-path")
                && issue.message().contains("状态路径")));
        assertTrue(issues.stream().anyMatch(issue -> issue.elementId().equals("invalid-number")
                && issue.message().contains("必须是数字")));
    }

    @Test
    void 后续set和remove会覆盖同路径的append() {
        GraphState setWins = new GraphState().apply(StatePatch.builder()
                .append("items", "old")
                .set("items", "final")
                .build());
        assertEquals("final", setWins.get("items").asText());

        GraphState removeWins = new GraphState().apply(StatePatch.builder()
                .append("items", "old")
                .remove("items")
                .build());
        assertFalse(removeWins.exists("items"));
    }

    @Test
    void set数组后append仍按顺序追加() {
        var initial = JsonNodeFactory.instance.arrayNode().add("first");
        GraphState state = new GraphState().apply(StatePatch.builder()
                .setJson("items", initial)
                .append("items", "second")
                .build());

        assertEquals(2, state.get("items").size());
        assertEquals("second", state.get("items").get(1).asText());
    }

    @Test
    void 父子路径操作严格遵循声明顺序() {
        GraphState removeParentWins = new GraphState().apply(StatePatch.builder()
                .set("profile.name", "JavaClaw")
                .remove("profile")
                .build());
        assertFalse(removeParentWins.exists("profile"));

        GraphState removeChildWins = new GraphState().apply(StatePatch.builder()
                .set("profile", java.util.Map.of("name", "JavaClaw", "role", "agent"))
                .remove("profile.name")
                .build());
        assertFalse(removeChildWins.exists("profile.name"));
        assertEquals("agent", removeChildWins.get("profile.role").asText());

        GraphState laterChildSetWins = new GraphState().apply(StatePatch.builder()
                .remove("profile")
                .set("profile.name", "JavaClaw")
                .build());
        assertEquals("JavaClaw", laterChildSetWins.get("profile.name").asText());
    }

    @Test
    void 不能通过省略source调用Mcp桥() {
        var config = JsonNodeFactory.instance.objectNode()
                .put("toolName", "mcp_call_tool");
        NodeDefinition node = new NodeDefinition("remote", NodeType.TOOL, "tool", "远程工具",
                config, 0, 0, RetryPolicy.NONE, ResumeSafety.CONFIRM_RETRY);

        assertTrue(new ToolNodeExecutor().validate(node).stream()
                .anyMatch(message -> message.contains("MCP")));
    }

    @Test
    void Agent节点拒绝Mcp工具组() {
        var config = JsonNodeFactory.instance.objectNode().put("prompt", "test");
        config.putArray("toolGroups").add("mcp");
        NodeDefinition node = new NodeDefinition("agent", NodeType.AGENT, "agent", "Agent",
                config, 0, 0, RetryPolicy.NONE, ResumeSafety.CONFIRM_RETRY);

        assertTrue(new AgentNodeExecutor().validate(node).stream()
                .anyMatch(message -> message.contains("MCP")));
    }

    @Test
    void Agent节点拒绝未知工具组() {
        var config = JsonNodeFactory.instance.objectNode().put("prompt", "test");
        config.putArray("toolGroups").add("not-a-real-group");
        NodeDefinition node = new NodeDefinition("agent", NodeType.AGENT, "agent", "Agent",
                config, 0, 0, RetryPolicy.NONE, ResumeSafety.CONFIRM_RETRY);

        assertTrue(new AgentNodeExecutor().validate(node).stream()
                .anyMatch(message -> message.contains("未知工具组")));
    }

    @Test
    void Tool节点拒绝Mcp和未知工具组() {
        var config = JsonNodeFactory.instance.objectNode().put("toolName", "some_tool");
        config.putArray("toolGroups").add("mcp").add("not-a-real-group");
        NodeDefinition node = new NodeDefinition("tool", NodeType.TOOL, "tool", "Tool",
                config, 0, 0, RetryPolicy.NONE, ResumeSafety.CONFIRM_RETRY);

        List<String> errors = new ToolNodeExecutor().validate(node);

        assertTrue(errors.stream().anyMatch(message -> message.contains("MCP")));
        assertTrue(errors.stream().anyMatch(message -> message.contains("未知工具组")));
    }

    @Test
    void Transform在发布前拒绝非法操作配置() {
        var config = JsonNodeFactory.instance.objectNode();
        var operations = config.putArray("operations");
        operations.addObject().put("op", "unknown").put("path", "result");
        operations.addObject().put("op", "copy").put("path", "result").put("from", "");
        operations.addObject().put("op", "set").put("path", "");
        NodeDefinition node = new NodeDefinition("transform", NodeType.TRANSFORM,
                "transform", "转换", config, 0, 0, RetryPolicy.NONE, ResumeSafety.SAFE);

        var errors = PublicNodeCatalog.createRegistry().require("transform").validate(node);

        assertTrue(errors.stream().anyMatch(message -> message.contains("未知操作")));
        assertTrue(errors.stream().anyMatch(message -> message.contains(".from")));
        assertTrue(errors.stream().anyMatch(message -> message.contains(".path")));
        assertTrue(errors.stream().anyMatch(message -> message.contains("value")));
    }

    @Test
    void 自定义图拒绝伪装节点执行器和多个错误出口() {
        var empty = JsonNodeFactory.instance.objectNode();
        var toolConfig = JsonNodeFactory.instance.objectNode().put("toolName", "some_tool");
        NodeDefinition start = new NodeDefinition("start", NodeType.START, "start", "开始",
                empty, 0, 0, RetryPolicy.NONE, ResumeSafety.SAFE);
        NodeDefinition disguised = new NodeDefinition("work", NodeType.TRANSFORM, "tool", "伪装工具",
                toolConfig, 0, 0, RetryPolicy.NONE, ResumeSafety.SAFE);
        NodeDefinition end1 = new NodeDefinition("end1", NodeType.END, "end", "结束1",
                empty, 0, 0, RetryPolicy.NONE, ResumeSafety.SAFE);
        NodeDefinition end2 = new NodeDefinition("end2", NodeType.END, "end", "结束2",
                empty, 0, 0, RetryPolicy.NONE, ResumeSafety.SAFE);
        GraphDefinition graph = new GraphDefinition(1, "wf", "测试", "", 1, GraphKind.CUSTOM,
                "start", List.of(start, disguised, end1, end2), List.of(
                new EdgeDefinition("a", "start", "work", EdgeKind.NORMAL, null, 0, false),
                new EdgeDefinition("b", "work", "end1", EdgeKind.NORMAL, null, 0, false),
                new EdgeDefinition("e1", "work", "end1", EdgeKind.ERROR, null, 0, false),
                new EdgeDefinition("e2", "work", "end2", EdgeKind.ERROR, null, 0, false)), 20);

        var issues = GraphValidator.validate(graph, PublicNodeCatalog.createRegistry());
        assertTrue(issues.stream().anyMatch(issue -> issue.message().contains("类型与执行器不匹配")));
        assertTrue(issues.stream().anyMatch(issue -> issue.message().contains("一条错误出口")));
        assertTrue(issues.stream().anyMatch(issue -> issue.message().contains("CONFIRM_RETRY")));
    }

    @Test
    void 非公共扩展执行器只能挂载到System节点() {
        var empty = JsonNodeFactory.instance.objectNode();
        NodeDefinition start = new NodeDefinition("start", NodeType.START, "start", "开始",
                empty, 0, 0, RetryPolicy.NONE, ResumeSafety.SAFE);
        NodeDefinition disguisedEnd = new NodeDefinition("end", NodeType.END, "trusted.extension", "结束",
                empty, 0, 0, RetryPolicy.NONE, ResumeSafety.SAFE);
        GraphDefinition graph = new GraphDefinition(1, "wf-extension", "扩展校验", "", 1,
                GraphKind.CUSTOM, "start", List.of(start, disguisedEnd),
                List.of(new EdgeDefinition("finish", "start", "end",
                        EdgeKind.NORMAL, null, 0, false)), 10);
        var registry = PublicNodeCatalog.createRegistry();
        registry.register(new com.javaclaw.workflow.runtime.NodeExecutor() {
            @Override public String type() { return "trusted.extension"; }
            @Override public com.javaclaw.workflow.runtime.NodeResult execute(
                    com.javaclaw.workflow.runtime.NodeExecutionContext context) {
                return com.javaclaw.workflow.runtime.NodeResult.next();
            }
        });

        var issues = GraphValidator.validate(graph, registry);

        assertTrue(issues.stream().anyMatch(issue -> issue.elementId().equals("end")
                && issue.message().contains("类型与执行器不匹配")));
    }

    @Test
    void 公共输出路径在发布前校验() {
        var config = JsonNodeFactory.instance.objectNode().put("responseKey", "invalid..path");
        NodeDefinition human = new NodeDefinition("human", NodeType.HUMAN_INPUT,
                "human_input", "人工输入", config, 0, 0, RetryPolicy.NONE, ResumeSafety.SAFE);
        assertTrue(PublicNodeCatalog.createRegistry().require("human_input").validate(human).stream()
                .anyMatch(message -> message.contains("responseKey")));
    }
}
