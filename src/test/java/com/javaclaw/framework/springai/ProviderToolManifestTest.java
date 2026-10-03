package com.javaclaw.framework.springai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProviderToolManifestTest {
    @Test
    void currentDirectoryReplacesCheckpointedManifestWithoutChangingHistoricalFeedback() {
        UserMessage task = new UserMessage("打开编辑器查看文件");
        ToolResponseMessage feedback = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("call", "probe",
                        "{\"error\":\"tool_not_offered\",\"offeredTools\":[\"launch\"]}"))).build();
        List<Message> saved = ProviderToolManifest.replace(List.of(task, feedback),
                List.of(callback("launch")));
        List<Message> restored = StepMessageCodec.messages(StepMessageCodec.messages(saved));
        assertTrue(ProviderToolManifest.isManifest(restored.getLast()));

        List<Message> next = ProviderToolManifest.replace(restored,
                List.of(callback("probe"), callback("targets")));

        assertEquals(3, next.size());
        assertEquals(StepMessageCodec.message(feedback), StepMessageCodec.message(next.get(1)));
        assertTrue(next.getLast().getText().contains("本次调用可用工具：probe、targets。"));
        assertFalse(next.getLast().getText().contains("launch"));
        assertFalse(next.getLast().getText().contains("控制通道 harness_submit_decision"),
                "a control tool must only be advertised when its trusted callback is present");
        assertEquals(1, next.stream().filter(ProviderToolManifest::isManifest).count());
        assertEquals(StepMessageCodec.messages(next),
                StepMessageCodec.messages(StepMessageCodec.messages(StepMessageCodec.messages(next))));
    }

    @Test
    void aSameNamedOrdinaryCallbackIsNotClassifiedAsTheControlChannel() {
        UserMessage manifest = ProviderToolManifest.message(
                List.of(callback(HarnessDecisionToolCallback.NAME)));
        assertTrue(manifest.getText().contains(
                "本次调用可用工具：harness_submit_decision。"));
        assertFalse(manifest.getText().contains("控制通道 harness_submit_decision"));
    }

    private static ToolCallback callback(String name) {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description("fixture")
                        .inputSchema("{\"type\":\"object\"}").build();
            }
            @Override public String call(String arguments) {
                throw new AssertionError("manifest must not invoke tools");
            }
        };
    }
}
