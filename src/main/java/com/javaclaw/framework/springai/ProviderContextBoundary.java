package com.javaclaw.framework.springai;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** The advisor's latest projected conversation, checked again at the model boundary. */
final class ProviderContextBoundary {
    private final AtomicReference<List<Message>> expected = new AtomicReference<>();
    private final AtomicReference<List<ToolCallback>> expectedTools = new AtomicReference<>();
    private final AtomicReference<String> toolCandidateStepId = new AtomicReference<>();

    void expect(List<Message> messages) {
        expected.set(List.copyOf(messages));
    }

    void expect(List<Message> messages, List<ToolCallback> tools) {
        expect(messages, tools, null);
    }

    void expect(List<Message> messages, List<ToolCallback> tools,
            String candidateStepId) {
        expect(messages);
        expectedTools.set(List.copyOf(tools));
        toolCandidateStepId.set(candidateStepId);
    }

    List<Message> expected() {
        return expected.get();
    }

    List<ToolCallback> expectedTools() { return expectedTools.get(); }

    String toolCandidateStepId() { return toolCandidateStepId.get(); }
}
