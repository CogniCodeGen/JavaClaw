package com.javaclaw.api.conversation;

import java.util.Objects;
import java.util.UUID;

/** One prior user/assistant message supplied to a new conversation run. */
public record ConversationMessage(Role role, String content, String messageId) {
    public enum Role { USER, ASSISTANT }

    public ConversationMessage {
        role = Objects.requireNonNull(role, "role");
        content = content == null ? "" : content;
        messageId = messageId == null || messageId.isBlank()
                ? UUID.randomUUID().toString() : messageId.strip();
    }

    /** Compatibility constructor for callers that do not manage durable chat messages. */
    public ConversationMessage(Role role, String content) {
        this(role, content, null);
    }
}
