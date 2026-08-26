package com.javaclaw.framework.builtin.context;

import java.util.List;
import java.util.Optional;

/** Loads a complete durable conversation prefix ending at a stable message id. */
public interface ConversationHistorySource {
    Optional<List<ConversationHistoryMessage>> loadThrough(
            String workspaceId, String sessionId, String lastMessageId);
}
