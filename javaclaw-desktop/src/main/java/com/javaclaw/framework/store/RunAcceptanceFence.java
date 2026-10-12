package com.javaclaw.framework.store;

import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.api.RunState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.function.Supplier;

/** Serializes child creation with a parent's evidence acceptance and terminal transition. */
final class RunAcceptanceFence {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JdbcThreadStore threads;

    RunAcceptanceFence(JdbcTemplate jdbc, TransactionTemplate transactions,
            JdbcThreadStore threads) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.threads = threads;
    }

    <T> T withLock(RunId id, Supplier<T> work) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(work, "work");
        return transactions.execute(status -> {
            Parent parent = parent(id, false);
            threads.lock(parent.scope());
            parent(id, true);
            return work.get();
        });
    }

    /** Called in the child create transaction after locking its destination thread. */
    void requireParent(RunRequest request) {
        RunId parentId = request.linkage().parentRunId();
        if (parentId == null) return;
        Parent parent = parent(parentId, true);
        if (!parent.scope().workspaceId().equals(request.scope().workspaceId())
                || !parent.scope().userId().equals(request.scope().userId())
                || parent.scope().equals(request.scope())) {
            throw new SecurityException("child Run cannot cross its parent scope");
        }
        if (parent.state().terminal()) {
            throw new IllegalStateException("cannot create a child Run after its parent ended");
        }
        Long latestOutcome = jdbc.queryForObject("SELECT COALESCE(MAX(event_sequence),0) "
                        + "FROM agent_run_events WHERE run_id=? AND type='core.task.outcome' "
                        + "AND producer='framework.core' AND schema_version=3",
                Long.class, parentId.value());
        Long latestResume = jdbc.queryForObject("SELECT COALESCE(MAX(event_sequence),0) "
                        + "FROM agent_run_events WHERE run_id=? AND type='core.run.resumed' "
                        + "AND producer='framework.core' AND schema_version=1",
                Long.class, parentId.value());
        if (latestOutcome != null && latestOutcome > (latestResume == null ? 0 : latestResume)) {
            throw new IllegalStateException(
                    "cannot create a child Run after its parent submitted this turn's task outcome");
        }
    }

    private Parent parent(RunId id, boolean lock) {
        String sql = "SELECT workspace_id,user_id,session_id,state FROM agent_runs WHERE run_id=?"
                + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, (row, index) -> new Parent(
                new RunScope(row.getString("workspace_id"), row.getString("user_id"),
                        row.getString("session_id")),
                RunState.valueOf(row.getString("state"))), id.value()).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown parent Run"));
    }

    private record Parent(RunScope scope, RunState state) { }
}
