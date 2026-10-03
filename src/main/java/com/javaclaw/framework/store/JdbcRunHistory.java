package com.javaclaw.framework.store;

import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.spi.StoredRun;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Objects;

/** Read-only Run history used by process, effect and delegated-budget recovery. */
final class JdbcRunHistory {
    private final JdbcTemplate jdbc;
    private final RowMapper<StoredRun> rowMapper;

    JdbcRunHistory(JdbcTemplate jdbc, RowMapper<StoredRun> rowMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.rowMapper = Objects.requireNonNull(rowMapper, "rowMapper");
    }

    /** Includes terminal children; their effects and physical charges survive termination. */
    List<StoredRun> childRuns(RunId parentId) {
        return jdbc.query("SELECT * FROM agent_runs WHERE run_id=?", rowMapper, parentId.value())
                .stream().findFirst().map(parent -> jdbc.query(
                        "SELECT * FROM agent_runs WHERE workspace_id=? AND user_id=? ORDER BY created_at, run_id",
                        rowMapper, parent.request().scope().workspaceId(), parent.request().scope().userId())
                        .stream().filter(run -> parentId.equals(run.request().linkage().parentRunId()))
                        .toList()).orElse(List.of());
    }

    /** Complete effect history for exactly one conversation, including terminal Runs. */
    List<StoredRun> scopeRuns(RunScope scope) {
        Objects.requireNonNull(scope, "scope");
        return jdbc.query("SELECT * FROM agent_runs WHERE workspace_id=? AND user_id=? "
                        + "AND session_id=? ORDER BY created_at, run_id",
                rowMapper, scope.workspaceId(), scope.userId(), scope.sessionId());
    }

    /** Process recovery discovers unsettled Runs, then enforces their scope access checks. */
    List<StoredRun> nonTerminalRuns() {
        return jdbc.query("""
                SELECT * FROM agent_runs
                WHERE state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
                ORDER BY created_at, run_id
                """, rowMapper);
    }
}
