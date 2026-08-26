package com.javaclaw.infrastructure.chat;

import com.javaclaw.framework.builtin.context.ConversationHistoryMessage;
import com.javaclaw.platform.data.SchemaInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcConversationHistorySourceTest {

    @Test
    void loadsCompleteEligiblePrefixAndDerivesStableLegacyIdentity() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:history-source-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        new SchemaInitializer(dataSource).initialize();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        insert(jdbc, 0, null, "USER", "legacy user", null);
        insert(jdbc, 1, "assistant-1", "ASSISTANT", "answer", "COMPLETE");
        insert(jdbc, 2, "system-1", "SYSTEM", "system", "COMPLETE");
        insert(jdbc, 3, "user-2", "USER", "follow-up", null);
        insert(jdbc, 4, "assistant-failed", "ASSISTANT", "failed", "FAILED");
        insert(jdbc, 5, "assistant-2", "ASSISTANT", "final", "COMPLETE");

        JdbcConversationHistorySource source = new JdbcConversationHistorySource(jdbc);
        List<ConversationHistoryMessage> first = source.loadThrough(
                "workspace", "session", "assistant-2").orElseThrow();
        List<ConversationHistoryMessage> second = source.loadThrough(
                "workspace", "session", "assistant-2").orElseThrow();

        assertEquals(List.of("legacy user", "answer", "follow-up", "final"),
                first.stream().map(ConversationHistoryMessage::content).toList());
        assertTrue(first.getFirst().messageId().startsWith("legacy-"));
        assertEquals(first.getFirst().messageId(), second.getFirst().messageId());
        assertTrue(source.loadThrough("workspace", "session", "missing").isEmpty());
    }

    private static void insert(
            JdbcTemplate jdbc,
            int position,
            String messageId,
            String role,
            String content,
            String deliveryState) {
        jdbc.update("""
                INSERT INTO chat_messages(
                    workspace_id, session_id, position, message_id, role, content,
                    timestamp, image_paths_json, adopted, delivery_state)
                VALUES ('workspace', 'session', ?, ?, ?, ?,
                        '2026-08-20 12:00:00', '[]', FALSE, ?)
                """, position, messageId, role, content, deliveryState);
    }
}
