package com.javaclaw.framework.builtin.context;

import java.util.Objects;

/** One complete, model-eligible durable chat message in persistence order. */
public record ConversationHistoryMessage(String messageId, Role role, String content) {
    public enum Role { USER, ASSISTANT }

    public ConversationHistoryMessage {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("conversation history message id must not be blank");
        }
        messageId = messageId.strip();
        role = Objects.requireNonNull(role, "role");
        content = content == null ? "" : content;
    }
}
