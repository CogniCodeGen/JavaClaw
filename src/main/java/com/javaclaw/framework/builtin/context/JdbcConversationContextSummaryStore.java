package com.javaclaw.framework.builtin.context;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** H2-backed summary store. Raw chat messages remain in chat_messages unchanged. */
public final class JdbcConversationContextSummaryStore implements ConversationContextSummaryStore {
    private final JdbcTemplate jdbc;

    public JdbcConversationContextSummaryStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<ConversationContextSummary> find(String workspaceId, String sessionId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT workspace_id, session_id, summarized_messages, cursor_message_id,
                           source_hash,
                           summary_json, rendered_summary, updated_at
                    FROM conversation_context_summary
                    WHERE workspace_id = ? AND session_id = ?
                    """, (rs, row) -> new ConversationContextSummary(
                    rs.getString("workspace_id"), rs.getString("session_id"),
                    rs.getInt("summarized_messages"), rs.getString("cursor_message_id"),
                    rs.getString("source_hash"),
                    rs.getString("summary_json"), rs.getString("rendered_summary"),
                    rs.getTimestamp("updated_at").toInstant()), workspaceId, sessionId));
        } catch (EmptyResultDataAccessException missing) {
            return Optional.empty();
        }
    }

    @Override
    public void save(ConversationContextSummary summary) {
        jdbc.update("""
                MERGE INTO conversation_context_summary
                (workspace_id, session_id, summarized_messages, cursor_message_id, source_hash,
                 summary_json, rendered_summary, updated_at)
                KEY(workspace_id, session_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, summary.workspaceId(), summary.sessionId(), summary.summarizedMessages(),
                summary.cursorMessageId(), summary.sourceHash(), summary.summaryJson(),
                summary.renderedSummary(),
                Timestamp.from(summary.updatedAt() == null ? Instant.now() : summary.updatedAt()));
    }

    @Override
    public void delete(String workspaceId, String sessionId) {
        jdbc.update("""
                DELETE FROM conversation_context_summary
                WHERE workspace_id = ? AND session_id = ?
                """, workspaceId, sessionId);
    }
}
