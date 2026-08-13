package com.javaclaw.api.conversation;

import java.util.Objects;

/** One prior user/assistant message supplied to a new conversation run. */
public record ConversationMessage(Role role, String content) {
    public enum Role { USER, ASSISTANT }

    public ConversationMessage {
        role = Objects.requireNonNull(role, "role");
        content = content == null ? "" : content;
    }
}
