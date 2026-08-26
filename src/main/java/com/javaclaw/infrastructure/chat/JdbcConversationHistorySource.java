package com.javaclaw.infrastructure.chat;

import com.javaclaw.framework.builtin.context.ConversationHistoryMessage;
import com.javaclaw.framework.builtin.context.ConversationHistorySource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Read-only H2 source used to validate and rebuild persistent context summaries. */
public final class JdbcConversationHistorySource implements ConversationHistorySource {
    private final JdbcTemplate jdbc;

    public JdbcConversationHistorySource(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<List<ConversationHistoryMessage>> loadThrough(
            String workspaceId, String sessionId, String lastMessageId) {
        if (workspaceId == null || workspaceId.isBlank()
                || sessionId == null || sessionId.isBlank()
                || lastMessageId == null || lastMessageId.isBlank()) {
            return Optional.empty();
        }
        List<Row> rows = jdbc.query("""
                SELECT position, message_id, role, content, timestamp, delivery_state
                FROM chat_messages
                WHERE workspace_id = ? AND session_id = ?
                ORDER BY position
                """, (rs, index) -> new Row(
                rs.getInt("position"),
                resolvedId(workspaceId, sessionId, rs.getInt("position"),
                        rs.getString("message_id"), rs.getString("role"),
                        rs.getString("content"), rs.getString("timestamp")),
                rs.getString("role"), rs.getString("content"),
                rs.getString("delivery_state")), workspaceId, sessionId);

        List<ConversationHistoryMessage> result = new ArrayList<>();
        boolean found = false;
        for (Row row : rows) {
            ConversationHistoryMessage eligible = eligible(row);
            if (eligible != null) result.add(eligible);
            if (row.messageId().equals(lastMessageId)) {
                found = eligible != null;
                break;
            }
        }
        return found ? Optional.of(List.copyOf(result)) : Optional.empty();
    }

    private static ConversationHistoryMessage eligible(Row row) {
        if (row.content() == null || row.content().isBlank()) return null;
        if ("USER".equals(row.role())) {
            return new ConversationHistoryMessage(
                    row.messageId(), ConversationHistoryMessage.Role.USER, row.content());
        }
        if ("ASSISTANT".equals(row.role())
                && (row.deliveryState() == null || row.deliveryState().isBlank()
                || "COMPLETE".equals(row.deliveryState()))) {
            return new ConversationHistoryMessage(
                    row.messageId(), ConversationHistoryMessage.Role.ASSISTANT, row.content());
        }
        return null;
    }

    private static String resolvedId(
            String workspaceId, String sessionId, int position, String persistedId,
            String role, String content, String timestamp) {
        return persistedId == null || persistedId.isBlank()
                ? LegacyChatMessageIds.derive(
                        workspaceId, sessionId, position, role, content, timestamp)
                : persistedId;
    }

    private record Row(
            int position, String messageId, String role, String content, String deliveryState) { }
}
