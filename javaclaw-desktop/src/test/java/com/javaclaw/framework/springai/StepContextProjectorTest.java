package com.javaclaw.framework.springai;

import com.javaclaw.framework.core.StepContextPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepContextProjectorTest {
    private static final StepContextPolicy DEFAULT = StepContextPolicy.DEFAULT;

    @Test
    void 保留系统消息和最新用户消息并成对淘汰旧工具交换() {
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage("system"));
        for (int index = 1; index <= 6; index++) {
            history.add(new UserMessage("user-" + index));
            history.add(toolCall("call-" + index, "tool-" + index));
            history.add(toolResponse(
                    "call-" + index, "tool-" + index, ("result-" + index).repeat(200)));
        }

        StepContextPolicy policy = new StepContextPolicy(4_000, 48_000, 2, 16_000, 64);
        StepContextProjector.Projection projection = projector(policy).project(history);
        List<Message> messages = projection.messages();

        assertInstanceOf(SystemMessage.class, messages.getFirst());
        assertTrue(messages.stream().anyMatch(message -> message instanceof UserMessage user
                && user.getText().equals("user-6")));
        assertTrue(messages.stream().anyMatch(message -> message instanceof ToolResponseMessage response
                && response.getResponses().getFirst().id().equals("call-6")));
        assertTrue(messages.stream().anyMatch(message -> message instanceof ToolResponseMessage response
                && response.getResponses().getFirst().id().equals("call-5")));
        assertFalse(messages.stream().anyMatch(message -> message instanceof ToolResponseMessage response
                && response.getResponses().getFirst().id().equals("call-1")));
        assertEquals(4, projection.statistics().evictedToolExchanges());
        assertTrue(projection.statistics().compacted());
    }

    @Test
    void 汇总大型工具结果并保留协议编号() {
        List<Message> history = List.of(
                new SystemMessage("system"),
                new UserMessage("task"),
                toolCall("kept", "fetch"),
                toolResponse("kept", "fetch", "123456789".repeat(200)));

        StepContextPolicy policy = new StepContextPolicy(4_000, 48_000, 1, 1_000, 64);
        StepContextProjector.Projection projection = projector(policy).project(history);

        ToolResponseMessage kept = projection.messages().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .findFirst().orElseThrow();
        String data = kept.getResponses().getFirst().responseData();
        assertTrue(data.contains("工具已执行"));
        assertTrue(data.contains("完整结果保留在 durable tool event"));
        assertTrue(data.length() < policy.maxToolResultCharacters());
    }

    @Test
    void typedToolResultKeepsStatusAndErrorCodeWhenDataIsBounded() throws Exception {
        String typed = new com.fasterxml.jackson.databind.ObjectMapper()
                .createObjectNode().put("status", "FAILED")
                .put("data", "x".repeat(2_000))
                .put("errorCode", "REMOTE_ERROR")
                .put("displayMessage", "verbose details").toString();
        StepContextPolicy policy = new StepContextPolicy(4_000, 48_000, 1, 1_000, 64);
        var projected = projector(policy).project(List.of(
                new UserMessage("task"), toolCall("typed", "fetch"),
                toolResponse("typed", "fetch", typed)));
        var response = (ToolResponseMessage) projected.messages().getLast();
        var summary = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                response.getResponses().getFirst().responseData());

        assertEquals("FAILED", summary.path("status").asText());
        assertEquals("REMOTE_ERROR", summary.path("errorCode").asText());
        assertTrue(summary.path("data").asText().contains("工具已执行"));
        assertEquals("", summary.path("displayMessage").asText());
        assertTrue(response.getResponses().getFirst().responseData().length()
                < policy.maxToolResultCharacters());
    }

    @Test
    void typedWrapperDoesNotConsumeTheBusinessPayloadLimit() throws Exception {
        String payload = "x".repeat(990);
        String typed = new com.fasterxml.jackson.databind.ObjectMapper()
                .createObjectNode().put("status", "SUCCEEDED")
                .put("data", payload).toString();
        var projected = projector(new StepContextPolicy(4_000, 48_000, 1, 1_000, 64))
                .project(List.of(new UserMessage("task"), toolCall("typed", "fetch"),
                        toolResponse("typed", "fetch", typed)));
        var response = (ToolResponseMessage) projected.messages().getLast();
        var visible = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                response.getResponses().getFirst().responseData());

        assertEquals("SUCCEEDED", visible.path("status").asText());
        assertEquals(payload, visible.path("data").asText());
    }

    @Test
    void compactedToolResultKeepsHostEvidenceReferences() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var envelope = json.createObjectNode().put("status", "SUCCEEDED")
                .put("data", "x".repeat(2_000));
        envelope.putArray("evidenceRefs").add("core.tool.completed:run:invocation");
        var projected = projector(new StepContextPolicy(4_000, 48_000, 1, 1_000, 64))
                .project(List.of(new UserMessage("task"), toolCall("typed", "fetch"),
                        toolResponse("typed", "fetch", envelope.toString())));
        var response = (ToolResponseMessage) projected.messages().getLast();
        var summary = json.readTree(response.getResponses().getFirst().responseData());

        assertTrue(summary.path("data").asText().contains("工具已执行"));
        assertEquals(envelope.path("evidenceRefs"), summary.path("evidenceRefs"));
    }

    @Test
    void 保留必需用户消息的媒体内容() {
        UserMessage current = UserMessage.builder().text("image task").media(List.of()).build();
        StepContextProjector.Projection projection = projector(DEFAULT).project(
                List.of(new SystemMessage("system"), current));

        UserMessage result = projection.messages().stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .findFirst().orElseThrow();
        assertEquals("image task", result.getText());
        assertEquals(current.getMedia(), result.getMedia());
    }

    @Test
    void 必需上下文超过预算时在调用Provider前失败() {
        UserMessage current = new UserMessage("x".repeat(4_001));
        StepContextProjector projector = projector(
                new StepContextPolicy(4_000, 48_000, 1, 1_000, 64));

        assertThrows(
                IllegalStateException.class,
                () -> projector.project(List.of(current)));
    }

    @Test
    void 系统提示计入必需上下文预算和诊断字符数() {
        SystemMessage system = new SystemMessage("s".repeat(3_000));
        UserMessage current = new UserMessage("u".repeat(1_001));
        StepContextProjector projector = projector(
                new StepContextPolicy(4_000, 48_000, 1, 1_000, 64));

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> projector.project(List.of(system, current)));

        StepContextProjector.Projection projection = projector(DEFAULT).project(
                List.of(new SystemMessage("system"), new UserMessage("task")));
        assertEquals(10, projection.statistics().charactersBefore());
        assertEquals(10, projection.statistics().charactersAfter());
    }

    @Test
    void 过大的最新工具交换保留协议并汇总已执行结果() {
        List<Message> history = List.of(
                new UserMessage("task"),
                toolCall("large", "fetch"),
                toolResponse("large", "fetch", "x".repeat(5_000)));
        StepContextProjector projector = projector(
                new StepContextPolicy(4_000, 48_000, 1, 16_000, 64));

        StepContextProjector.Projection projection = projector.project(history);

        assertEquals(3, projection.messages().size());
        assertInstanceOf(UserMessage.class, projection.messages().getFirst());
        ToolResponseMessage response = assertInstanceOf(
                ToolResponseMessage.class, projection.messages().getLast());
        assertEquals("large", response.getResponses().getFirst().id());
        assertEquals("fetch", response.getResponses().getFirst().name());
        assertTrue(response.getResponses().getFirst().responseData().contains("工具已执行"));
        assertEquals(0, projection.statistics().evictedToolExchanges());
    }

    @Test
    void requiredExchangeCompactionPreservesTypedToolStatus() throws Exception {
        String typed = new com.fasterxml.jackson.databind.ObjectMapper()
                .createObjectNode().put("status", "SUCCEEDED")
                .put("data", "x".repeat(5_000)).toString();
        var projection = projector(
                new StepContextPolicy(4_000, 48_000, 1, 16_000, 64))
                .project(List.of(new UserMessage("task"), toolCall("large", "fetch"),
                        toolResponse("large", "fetch", typed)));
        var response = (ToolResponseMessage) projection.messages().getLast();
        var summary = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                response.getResponses().getFirst().responseData());

        assertEquals("SUCCEEDED", summary.path("status").asText());
        assertTrue(summary.path("data").asText().contains("工具已执行"));
    }

    @Test
    void 原始任务与最新恢复命令跨多轮压缩保留() {
        UserMessage original = marked("original-task", SpringAiPromptFactory.ORIGINAL_TASK_METADATA);
        UserMessage firstResume = marked("first resume", SpringAiPromptFactory.RESUME_COMMAND_METADATA);
        UserMessage latestResume = marked("latest resume", SpringAiPromptFactory.RESUME_COMMAND_METADATA);
        List<Message> history = List.of(
                new SystemMessage("system"), original,
                firstResume, toolCall("old", "fetch"),
                toolResponse("old", "fetch", "x".repeat(2_500)),
                latestResume, toolCall("latest", "fetch"),
                toolResponse("latest", "fetch", "y".repeat(2_500)));

        StepContextProjector.Projection projection = projector(
                new StepContextPolicy(4_000, 48_000, 1, 16_000, 64)).project(history);

        assertTrue(projection.messages().contains(original));
        assertTrue(projection.messages().contains(latestResume));
        assertTrue(projection.messages().stream().anyMatch(message ->
                message instanceof ToolResponseMessage response
                        && response.getResponses().getFirst().id().equals("latest")));
        assertFalse(projection.messages().stream().anyMatch(message ->
                message instanceof ToolResponseMessage response
                        && response.getResponses().getFirst().id().equals("old")));
    }

    @Test
    void 禁用策略保留完整消息与工具原文() {
        Message orphan = toolCall("orphan", "fetch");
        ToolResponseMessage response = toolResponse("other", "fetch", "x".repeat(20_000));
        List<Message> source = List.of(new UserMessage("task"), orphan, response);

        StepContextProjector.Projection projection = projector(null).project(source);

        assertEquals(source, projection.messages());
        assertFalse(projection.statistics().compacted());
    }

    @Test
    void 相同历史产生确定性投影() {
        List<Message> history = List.of(
                new SystemMessage("system"),
                new UserMessage("task"),
                toolCall("one", "one"),
                toolResponse("one", "one", "result"),
                new UserMessage("follow-up"));
        StepContextProjector projector = projector(DEFAULT);

        assertEquals(projector.project(history).messages(), projector.project(history).messages());
    }

    @Test
    void 多工具调用仅在响应编号全集匹配时成对保留() {
        AssistantMessage calls = AssistantMessage.builder().content("")
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("one", "function", "first", "{}"),
                        new AssistantMessage.ToolCall("two", "function", "second", "{}")))
                .build();
        ToolResponseMessage complete = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("two", "second", "second-result"),
                new ToolResponseMessage.ToolResponse("one", "first", "first-result")))
                .build();
        ToolResponseMessage incomplete = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("one", "first", "first-result")))
                .build();
        StepContextProjector projector = projector(DEFAULT);

        StepContextProjector.Projection kept = projector.project(List.of(
                new UserMessage("task"), calls, complete));
        assertTrue(kept.messages().contains(calls));
        assertTrue(kept.messages().contains(complete));
        assertThrows(IllegalStateException.class, () -> projector.project(List.of(
                new UserMessage("task"), calls, incomplete)));
    }

    @Test
    void 孤立工具协议消息在Provider前停止() {
        StepContextProjector projector = projector(DEFAULT);
        assertThrows(IllegalStateException.class, () -> projector.project(List.of(
                new UserMessage("task"), toolCall("call", "fetch"))));
        assertThrows(IllegalStateException.class, () -> projector.project(List.of(
                new UserMessage("task"), toolResponse("call", "fetch", "result"))));
        assertThrows(IllegalStateException.class, () -> projector.project(List.of(
                new UserMessage("task"), toolCall("call", "fetch"),
                toolResponse("call", "other", "result"))));
    }

    @Test
    void optionalHostSystemAndUserBlocksCanBeEvictedWithoutReplacingTheTask() {
        UserMessage task = marked("original task", SpringAiPromptFactory.ORIGINAL_TASK_METADATA);
        Message identity = host(new UserMessage("selected application identity".repeat(50)),
                "application", HostContextBlock.Kind.APPLICATION_IDENTITY, true);
        Message oldSystem = host(new SystemMessage("old environment".repeat(250)),
                "old-environment", HostContextBlock.Kind.RUNTIME, false);
        Message oldUser = host(new UserMessage("old catalog".repeat(500)),
                "old-catalog", HostContextBlock.Kind.SELECTED_CONTEXT, false);
        List<Message> source = List.of(new SystemMessage("fixed instructions"), task,
                identity, oldSystem, oldUser);
        var projector = projector(new StepContextPolicy(4_000, 48_000, 1, 16_000, 64));

        var projected = projector.project(source);

        assertTrue(projected.messages().contains(task));
        assertTrue(projected.messages().contains(identity));
        assertFalse(projected.messages().contains(oldSystem));
        assertFalse(projected.messages().contains(oldUser));
        projector.validateRequiredEvidence(source, projected.messages());
    }

    @Test
    void explicitlyOptionalLatestToolExchangeIsEvictedAsAnEntirePair() {
        Message call = host(toolCall("catalog", "desktop_session_applications"),
                "catalog-call", HostContextBlock.Kind.TOOL_EXCHANGE, false);
        Message response = host(toolResponse("catalog", "desktop_session_applications",
                "catalog entry".repeat(500)), "catalog-result",
                HostContextBlock.Kind.TOOL_EXCHANGE, false);
        Message identity = host(new UserMessage("identity".repeat(250)),
                "application", HostContextBlock.Kind.APPLICATION_IDENTITY, true);
        List<Message> source = List.of(new UserMessage("task"), identity, call, response);
        var projector = projector(new StepContextPolicy(4_000, 48_000, 1, 16_000, 64));

        var projected = projector.project(source);

        assertEquals(2, projected.messages().size());
        assertEquals(1, projected.statistics().evictedToolExchanges());
        projector.validateRequiredEvidence(source, projected.messages());
    }

    @Test
    void requiredHostEvidenceCannotBeSilentlySummarizedToFitTheBudget() {
        Message response = host(toolResponse("observe", "desktop_session_observe",
                "observation".repeat(500)), "current-observation",
                HostContextBlock.Kind.OBSERVATION, true);
        List<Message> source = List.of(new UserMessage("task"),
                toolCall("observe", "desktop_session_observe"), response);
        var projector = projector(new StepContextPolicy(4_000, 48_000, 1, 16_000, 64));

        var failure = assertThrows(LocalContextBudgetExceededException.class,
                () -> projector.project(source));

        assertEquals("messages", failure.budgetKind());
        assertEquals(4_000, failure.budgetCharacters());
        assertTrue(failure.requiredCharacters() > 4_000);
        assertTrue(failure.components().get("observation") > 5_000);
        assertEquals(List.of("evidence:current-observation"), failure.requiredEvidenceRefs());
        assertTrue(failure.getMessage().startsWith("LOCAL_CONTEXT_BUDGET_EXCEEDED:"));
    }

    @Test
    void requiredToolPayloadLimitHasASeparateDiagnostic() {
        Message response = host(toolResponse("observe", "desktop_session_observe",
                "x".repeat(2_000)), "current-observation",
                HostContextBlock.Kind.OBSERVATION, true);
        var projector = projector(new StepContextPolicy(4_000, 48_000, 1, 1_000, 64));

        var failure = assertThrows(LocalContextBudgetExceededException.class,
                () -> projector.project(List.of(new UserMessage("task"),
                        toolCall("observe", "desktop_session_observe"), response)));

        assertEquals("required_tool_result", failure.budgetKind());
        assertEquals(1_000, failure.budgetCharacters());
        assertEquals(List.of("evidence:current-observation"), failure.requiredEvidenceRefs());
    }

    @Test
    void requiredEvidenceValidationChecksHostIdentityAndRevisionNotJustMessageCount() {
        Message identity = host(new UserMessage("selected application"), "application",
                HostContextBlock.Kind.APPLICATION_IDENTITY, true);
        List<Message> source = List.of(new UserMessage("task"), identity);
        Message stale = HostContextBlock.mark(new UserMessage("selected application"),
                new HostContextBlock.Metadata("application", HostContextBlock.Kind.APPLICATION_IDENTITY,
                        "old-revision", "run", true, List.of("evidence:application")));
        var projector = projector(DEFAULT);

        assertThrows(IllegalStateException.class,
                () -> projector.validateRequiredEvidence(source, List.of(source.getFirst(), stale)));
        projector.validateRequiredEvidence(source, projector.project(source).messages());
    }

    @Test
    void unknownDeliveryEvidenceSurvivesPayloadCompactionAndCannotBeChanged() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var envelope = json.createObjectNode().put("status", "UNKNOWN")
                .put("errorCode", "DELIVERY_UNKNOWN").put("data", "x".repeat(5_000));
        envelope.putArray("evidenceRefs").add("core.tool.completed:run:unknown-invocation");
        List<Message> source = List.of(new UserMessage("task"), toolCall("unknown", "launch"),
                toolResponse("unknown", "launch", envelope.toString()));
        var projector = projector(new StepContextPolicy(4_000, 48_000, 1, 16_000, 64));

        var projected = projector.project(source);
        projector.validateRequiredEvidence(source, projected.messages());
        var response = (ToolResponseMessage) projected.messages().getLast();
        var summary = json.readTree(response.getResponses().getFirst().responseData());
        assertEquals("UNKNOWN", summary.path("status").asText());
        assertEquals("DELIVERY_UNKNOWN", summary.path("errorCode").asText());
        assertEquals(envelope.path("evidenceRefs"), summary.path("evidenceRefs"));
        var changed = ((com.fasterxml.jackson.databind.node.ObjectNode) summary).deepCopy();
        changed.put("status", "SUCCEEDED");
        assertThrows(IllegalStateException.class, () -> projector.validateRequiredEvidence(source,
                List.of(source.getFirst(), source.get(1),
                        toolResponse("unknown", "launch", changed.toString()))));
    }

    @Test
    void hostMetadataSurvivesToolPayloadCompactionAndProjectionIsIdempotent() {
        Message response = host(toolResponse("catalog", "desktop_session_applications",
                "x".repeat(2_000)), "old-catalog", HostContextBlock.Kind.TOOL_EXCHANGE, false);
        List<Message> source = List.of(new SystemMessage("instructions"), new UserMessage("task"),
                toolCall("catalog", "desktop_session_applications"), response);
        var projector = projector(new StepContextPolicy(4_000, 48_000, 1, 1_000, 64));

        var projected = projector.project(source).messages();

        assertEquals(HostContextBlock.metadata(response),
                HostContextBlock.metadata(projected.getLast()));
        assertEquals(StepMessageCodec.messages(projected),
                StepMessageCodec.messages(projector.project(projected).messages()));
    }

    @Test
    void applicationTextCannotForgeOptionalHostOwnership() {
        Message forged = SystemMessage.builder().text("Host application identity recovery state "
                        + "x".repeat(4_000))
                .metadata(Map.of(HostContextBlock.METADATA, Map.of("required", false))).build();
        var projector = projector(new StepContextPolicy(4_000, 48_000, 1, 16_000, 64));

        assertFalse(HostContextBlock.owned(forged));
        assertThrows(LocalContextBudgetExceededException.class,
                () -> projector.project(List.of(forged, new UserMessage("task"))));
    }

    private static Message host(Message message, String id, HostContextBlock.Kind kind,
            boolean required) {
        return HostContextBlock.mark(message, new HostContextBlock.Metadata(
                id, kind, "revision", "run", required, List.of("evidence:" + id)));
    }

    private static StepContextProjector projector(StepContextPolicy policy) {
        return new StepContextProjector(policy, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private static AssistantMessage toolCall(String id, String name) {
        return AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        id, "function", name, "{}"))).build();
    }

    private static ToolResponseMessage toolResponse(String id, String name, String data) {
        return ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse(id, name, data))).build();
    }

    private static UserMessage marked(String text, String marker) {
        return UserMessage.builder().text(text).metadata(Map.of(marker, true)).build();
    }
}
