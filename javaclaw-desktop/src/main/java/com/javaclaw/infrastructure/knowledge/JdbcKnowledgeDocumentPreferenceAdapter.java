package com.javaclaw.infrastructure.knowledge;

import com.javaclaw.application.knowledge.KnowledgeDocumentPreferencePort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** H2-backed document exclusion preferences isolated by workspace. */
public final class JdbcKnowledgeDocumentPreferenceAdapter
        implements KnowledgeDocumentPreferencePort {

    private static final String SCOPE = "all";

    private final String workspaceId;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcKnowledgeDocumentPreferenceAdapter(
            String workspaceId,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId must not be blank");
        }
        this.workspaceId = workspaceId.strip();
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    @Override
    public Set<String> loadExcluded() {
        return new LinkedHashSet<>(jdbc.queryForList("""
                SELECT doc_name
                FROM knowledge_doc_prefs
                WHERE workspace_id = ? AND scope = ? AND excluded = TRUE
                ORDER BY doc_name
                """, String.class, workspaceId, SCOPE));
    }

    @Override
    public void replaceExcluded(Set<String> documentNames) {
        Set<String> normalized = normalize(documentNames);
        transactions.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM knowledge_doc_prefs WHERE workspace_id = ? AND scope = ?",
                    workspaceId, SCOPE);
            for (String name : normalized) {
                jdbc.update("""
                        INSERT INTO knowledge_doc_prefs(
                            workspace_id, scope, doc_name, excluded, updated_at)
                        VALUES (?, ?, ?, TRUE, CURRENT_TIMESTAMP)
                        """, workspaceId, SCOPE, name);
            }
        });
    }

    private static Set<String> normalize(Set<String> names) {
        if (names == null || names.isEmpty()) return Set.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String name : names) {
            if (name != null && !name.isBlank()) result.add(name.strip());
        }
        return java.util.Collections.unmodifiableSet(result);
    }
}
