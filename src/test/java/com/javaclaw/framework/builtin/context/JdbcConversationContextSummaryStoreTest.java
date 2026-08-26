package com.javaclaw.framework.builtin.context;

import com.javaclaw.platform.data.SchemaInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcConversationContextSummaryStoreTest {

    @Test
    void summaryCursorSurvivesStoreRecreationAndUpsertsAtomically() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:context-summary-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        new SchemaInitializer(dataSource).initialize();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        JdbcConversationContextSummaryStore first =
                new JdbcConversationContextSummaryStore(jdbc);

        assertTrue(first.find("workspace", "session").isEmpty());
        Instant initialTime = Instant.parse("2026-08-20T01:02:03Z");
        first.save(new ConversationContextSummary(
                "workspace", "session", 12, "message-12", "hash-a", "{\"goals\":[]}",
                "Goals:\n- first", initialTime));

        JdbcConversationContextSummaryStore reopened =
                new JdbcConversationContextSummaryStore(new JdbcTemplate(dataSource));
        ConversationContextSummary restored = reopened.find("workspace", "session").orElseThrow();
        assertEquals(12, restored.summarizedMessages());
        assertEquals("message-12", restored.cursorMessageId());
        assertEquals("hash-a", restored.sourceHash());
        assertEquals("Goals:\n- first", restored.renderedSummary());
        assertEquals(initialTime, restored.updatedAt());

        Instant updatedTime = initialTime.plusSeconds(10);
        reopened.save(new ConversationContextSummary(
                "workspace", "session", 18, "message-18", "hash-b", "{\"facts\":[\"f\"]}",
                "Facts:\n- f", updatedTime));
        ConversationContextSummary updated = first.find("workspace", "session").orElseThrow();
        assertEquals(18, updated.summarizedMessages());
        assertEquals("message-18", updated.cursorMessageId());
        assertEquals("hash-b", updated.sourceHash());
        assertEquals(updatedTime, updated.updatedAt());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversation_context_summary", Integer.class));
        reopened.delete("workspace", "session");
        assertTrue(first.find("workspace", "session").isEmpty());
    }
}
