package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.spi.FrameworkTool;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.tool.execution.ToolExecutionException;

import java.util.ArrayList;
import java.util.List;

/** Small provider-message and resource helpers shared by the reasoning gateway. */
final class ReasoningGatewaySupport {
    private ReasoningGatewaySupport() { }

    static List<Message> providerMessages(String systemPrompt, List<Message> messages) {
        List<Message> combined = new ArrayList<>();
        if (!systemPrompt.isBlank()) combined.add(new SystemMessage(systemPrompt));
        combined.addAll(messages);
        return combined;
    }

    static List<Message> withoutSystemMessages(List<Message> messages) {
        return messages.stream().filter(message -> !(message instanceof SystemMessage)).toList();
    }

    static void addProjectionStatistics(
            ObjectNode target, StepContextProjector.Statistics statistics) {
        target.put("contextProjectionVersion", 1);
        target.put("messagesBefore", statistics.messagesBefore());
        target.put("messagesAfter", statistics.messagesAfter());
        target.put("messageCharactersBefore", statistics.charactersBefore());
        target.put("messageCharactersAfter", statistics.charactersAfter());
        target.put("evictedToolExchanges", statistics.evictedToolExchanges());
        target.put("contextCompacted", statistics.compacted());
    }

    static void closeTools(List<FrameworkTool> runTools) {
        RuntimeException first = null;
        for (int index = runTools.size() - 1; index >= 0; index--) {
            try {
                runTools.get(index).close();
            } catch (Exception failure) {
                if (first == null) first = new IllegalStateException("cannot close run tool", failure);
                else first.addSuppressed(failure);
            }
        }
        if (first != null) throw first;
    }

    static long token(Integer value) {
        return value == null ? 0L : Math.max(0, value.longValue());
    }

    static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException
                || current instanceof ToolExecutionException)) {
            current = current.getCause();
        }
        return current;
    }
}
