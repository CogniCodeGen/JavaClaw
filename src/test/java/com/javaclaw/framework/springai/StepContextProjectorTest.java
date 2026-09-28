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
        StepContextProjector.Projection projection = new StepContextProjector(policy).project(history);
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
        StepContextProjector.Projection projection = new StepContextProjector(policy).project(history);

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
    void 保留必需用户消息的媒体内容() {
        UserMessage current = UserMessage.builder().text("image task").media(List.of()).build();
        StepContextProjector.Projection projection = new StepContextProjector(DEFAULT).project(
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
        StepContextProjector projector = new StepContextProjector(
                new StepContextPolicy(4_000, 48_000, 1, 1_000, 64));

        assertThrows(
                IllegalStateException.class,
                () -> projector.project(List.of(current)));
    }

    @Test
    void 系统提示计入必需上下文预算和诊断字符数() {
        SystemMessage system = new SystemMessage("s".repeat(3_000));
        UserMessage current = new UserMessage("u".repeat(1_001));
        StepContextProjector projector = new StepContextProjector(
                new StepContextPolicy(4_000, 48_000, 1, 1_000, 64));

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> projector.project(List.of(system, current)));

        StepContextProjector.Projection projection = new StepContextProjector(DEFAULT).project(
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
        StepContextProjector projector = new StepContextProjector(
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

        StepContextProjector.Projection projection = new StepContextProjector(
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

        StepContextProjector.Projection projection = new StepContextProjector(null).project(source);

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
        StepContextProjector projector = new StepContextProjector(DEFAULT);

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
        StepContextProjector projector = new StepContextProjector(DEFAULT);

        StepContextProjector.Projection kept = projector.project(List.of(
                new UserMessage("task"), calls, complete));
        assertTrue(kept.messages().contains(calls));
        assertTrue(kept.messages().contains(complete));
        assertThrows(IllegalStateException.class, () -> projector.project(List.of(
                new UserMessage("task"), calls, incomplete)));
    }

    @Test
    void 孤立工具协议消息在Provider前停止() {
        StepContextProjector projector = new StepContextProjector(DEFAULT);
        assertThrows(IllegalStateException.class, () -> projector.project(List.of(
                new UserMessage("task"), toolCall("call", "fetch"))));
        assertThrows(IllegalStateException.class, () -> projector.project(List.of(
                new UserMessage("task"), toolResponse("call", "fetch", "result"))));
        assertThrows(IllegalStateException.class, () -> projector.project(List.of(
                new UserMessage("task"), toolCall("call", "fetch"),
                toolResponse("call", "other", "result"))));
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
