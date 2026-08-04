package com.javaclaw.workflow;

import com.javaclaw.api.conversation.ConversationRequest;
import com.javaclaw.workflow.service.SystemGraphFactory;
import com.javaclaw.workflow.service.SystemInvocationState;
import com.javaclaw.workflow.node.SystemPipelineNodeExecutor;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemGraphDefinitionTest {
    @Test
    void 系统图暴露多个真实检查点阶段且每个系统节点都有阶段契约() {
        for (var graph : List.of(SystemGraphFactory.chat(), SystemGraphFactory.plan(),
                SystemGraphFactory.loop(), SystemGraphFactory.sdd())) {
            assertTrue(graph.nodes().stream()
                            .filter(node -> node.type() == com.javaclaw.workflow.model.NodeType.SYSTEM).count() >= 2,
                    graph.id() + " 应至少包含准备与执行两个真实阶段");
            assertTrue(graph.nodes().stream()
                    .filter(node -> node.type() == com.javaclaw.workflow.model.NodeType.SYSTEM)
                    .allMatch(node -> node.id().equals(node.config().path("stageId").asText())));
            assertTrue(com.javaclaw.workflow.runtime.GraphValidator.validate(
                    graph, com.javaclaw.workflow.node.PublicNodeCatalog.createRegistry()).isEmpty());
        }
        assertEquals(List.of("vision", "orchestrate"), stageIds(SystemGraphFactory.chat()));
        assertEquals(List.of("knowledge", "discussion"), stageIds(SystemGraphFactory.plan()));
        assertEquals(List.of("preflight", "run"), stageIds(SystemGraphFactory.loop()));
        assertEquals(List.of("prepare", "implement"), stageIds(SystemGraphFactory.sdd()));
    }

    @Test
    void 系统请求可从检查点状态重建() {
        ConversationRequest original = new ConversationRequest("原始问题",
                List.of(new File("target/original.png")), "session-1");
        ConversationRequest restored = SystemInvocationState.request(SystemInvocationState.from(original));
        assertEquals(original.userInput(), restored.userInput());
        assertEquals(original.sessionId(), restored.sessionId());
        assertEquals(original.attachments().getFirst().getAbsolutePath(),
                restored.attachments().getFirst().getAbsolutePath());
    }

    @Test
    void 系统节点拒绝缺失或伪造阶段标识() {
        var executor = new SystemPipelineNodeExecutor();
        var empty = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        var missing = new com.javaclaw.workflow.model.NodeDefinition(
                "pipeline", com.javaclaw.workflow.model.NodeType.SYSTEM, "system.pipeline", "管线",
                empty, 0, 0, com.javaclaw.workflow.model.RetryPolicy.NONE,
                com.javaclaw.workflow.model.ResumeSafety.CONFIRM_RETRY);
        var mismatch = new com.javaclaw.workflow.model.NodeDefinition(
                "pipeline", com.javaclaw.workflow.model.NodeType.SYSTEM, "system.pipeline", "管线",
                empty.deepCopy().put("stageId", "other"), 0, 0,
                com.javaclaw.workflow.model.RetryPolicy.NONE,
                com.javaclaw.workflow.model.ResumeSafety.CONFIRM_RETRY);

        assertTrue(executor.validate(missing).stream().anyMatch(message -> message.contains("stageId")));
        assertTrue(executor.validate(mismatch).stream().anyMatch(message -> message.contains("节点 ID")));
    }

    @Test
    void 系统节点总是调用对应阶段实现() throws Exception {
        var node = SystemGraphFactory.chat().nodes().stream()
                .filter(candidate -> candidate.type() == com.javaclaw.workflow.model.NodeType.SYSTEM)
                .findFirst().orElseThrow();
        AtomicReference<String> invokedStage = new AtomicReference<>();
        com.javaclaw.workflow.service.SystemPipeline pipeline = (stageId, context) -> {
            invokedStage.set(stageId);
            return com.javaclaw.workflow.runtime.NodeResult.next();
        };
        var context = new com.javaclaw.workflow.runtime.NodeExecutionContext(
                "run", "thread", node, new com.javaclaw.workflow.model.GraphState(),
                new com.javaclaw.workflow.runtime.CancellationToken(),
                com.javaclaw.workflow.runtime.GraphListener.NOOP,
                Map.of(com.javaclaw.workflow.service.SystemPipeline.class, pipeline));

        new SystemPipelineNodeExecutor().execute(context);

        assertEquals(node.id(), invokedStage.get());
    }

    private static List<String> stageIds(com.javaclaw.workflow.model.GraphDefinition graph) {
        return graph.nodes().stream()
                .filter(node -> node.type() == com.javaclaw.workflow.model.NodeType.SYSTEM)
                .map(com.javaclaw.workflow.model.NodeDefinition::id).toList();
    }
}
