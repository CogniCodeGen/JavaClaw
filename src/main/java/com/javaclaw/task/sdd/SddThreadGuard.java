package com.javaclaw.task.sdd;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Serializes SDD artifact access with the owning Thread's deletion tombstone. */
public final class SddThreadGuard {
    private final JdbcTemplate jdbc;
    private final String workspaceId;
    private final String threadId;
    private final TransactionTemplate transactions;

    public SddThreadGuard(JdbcTemplate jdbc, String workspaceId, String threadId) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        this.threadId = threadId;
        transactions = new TransactionTemplate(new DataSourceTransactionManager(
                Objects.requireNonNull(jdbc.getDataSource(), "dataSource")));
    }

    public String threadId() { return threadId; }

    public <T> T ifAlive(Supplier<T> operation, T deletedResult) {
        if (threadId == null) return operation.get();
        return transactions.execute(status -> alive(jdbc, workspaceId, List.of(threadId), true)
                ? operation.get() : deletedResult);
    }

    public static String coordinator(String workspaceId, String taskId, boolean graph) {
        return graph ? workspaceId + ":" + taskId + ":system-sdd" : "sdd:" + taskId;
    }

    public static List<String> coordinators(String workspaceId, String taskId) {
        return List.of(coordinator(workspaceId, taskId, true), coordinator(workspaceId, taskId, false));
    }

    /** The absent-row case is for legacy tasks that have never acquired an Agent Thread. */
    public static boolean alive(JdbcTemplate jdbc, String workspaceId, List<String> threadIds, boolean lock) {
        for (String id : threadIds) {
            List<String> states = jdbc.queryForList("SELECT status FROM agent_threads "
                            + "WHERE workspace_id=? AND user_id='local-user' AND thread_id=?"
                            + (lock ? " FOR UPDATE" : ""), String.class, workspaceId, id);
            if (states.stream().anyMatch(state -> state.equals("DELETING") || state.equals("DELETED")))
                return false;
        }
        return true;
    }
}
