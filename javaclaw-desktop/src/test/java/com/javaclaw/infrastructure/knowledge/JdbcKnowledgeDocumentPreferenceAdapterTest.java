package com.javaclaw.infrastructure.knowledge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Set;
import java.util.UUID;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcKnowledgeDocumentPreferenceAdapterTest {

    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;

    @BeforeEach
    void createSchema() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:knowledge-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        jdbc.execute("""
                CREATE TABLE knowledge_doc_prefs (
                    workspace_id VARCHAR(128) NOT NULL,
                    scope VARCHAR(32) NOT NULL,
                    doc_name VARCHAR(1024) NOT NULL,
                    excluded BOOLEAN NOT NULL,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (workspace_id, scope, doc_name)
                )
                """);
    }

    @Test
    void replacesPreferencesAtomicallyAndKeepsWorkspacesIsolated() {
        var first = adapter("workspace-a");
        var second = adapter("workspace-b");

        first.replaceExcluded(Set.of("guide.md", "notes.txt"));
        second.replaceExcluded(Set.of("private.md"));
        first.replaceExcluded(Set.of("guide.md"));

        assertEquals(Set.of("guide.md"), first.loadExcluded());
        assertEquals(Set.of("private.md"), second.loadExcluded());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_doc_prefs", Integer.class));
    }

    @Test
    void normalizesBlankEntriesBeforePersistence() {
        var preferences = adapter("workspace-a");

        preferences.replaceExcluded(Set.of("  guide.md  ", " "));

        assertEquals(Set.of("guide.md"), preferences.loadExcluded());
    }

    @Test
    void rollsBackDeleteWhenReplacementInsertFails() {
        var preferences = adapter("workspace-a");
        preferences.replaceExcluded(Set.of("existing.md"));
        LinkedHashSet<String> invalid = new LinkedHashSet<>();
        invalid.add("replacement.md");
        invalid.add("x".repeat(1_025));

        assertThrows(RuntimeException.class, () -> preferences.replaceExcluded(invalid));

        assertEquals(Set.of("existing.md"), preferences.loadExcluded());
    }

    private JdbcKnowledgeDocumentPreferenceAdapter adapter(String workspaceId) {
        return new JdbcKnowledgeDocumentPreferenceAdapter(workspaceId, jdbc, transactions);
    }
}
