package com.javaclaw.framework.builtin.context;

import java.time.Instant;

/** Durable structured summary cursor for one workspace conversation. */
public record ConversationContextSummary(
        String workspaceId,
        String sessionId,
        int summarizedMessages,
        String cursorMessageId,
        String sourceHash,
        String summaryJson,
        String renderedSummary,
        Instant updatedAt) {

    /** Compatibility constructor for pre-cursor callers and legacy rows. */
    public ConversationContextSummary(
            String workspaceId,
            String sessionId,
            int summarizedMessages,
            String sourceHash,
            String summaryJson,
            String renderedSummary,
            Instant updatedAt) {
        this(workspaceId, sessionId, summarizedMessages, null, sourceHash,
                summaryJson, renderedSummary, updatedAt);
    }
}
