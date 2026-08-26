package com.javaclaw.framework.builtin.context;

import java.util.Optional;

public interface ConversationContextSummaryStore {
    Optional<ConversationContextSummary> find(String workspaceId, String sessionId);

    void save(ConversationContextSummary summary);

    default void delete(String workspaceId, String sessionId) { }
}
