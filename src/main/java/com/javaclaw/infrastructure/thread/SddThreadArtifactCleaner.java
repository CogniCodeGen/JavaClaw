package com.javaclaw.infrastructure.thread;

import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.spi.ThreadLifecycleListener;
import com.javaclaw.platform.json.JsonCodec;
import com.javaclaw.task.sdd.run.SddTaskStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Root-lifetime cleanup also serves Threads whose workspace runtime is currently closed. */
public final class SddThreadArtifactCleaner implements ThreadLifecycleListener {
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final JsonCodec json;

    public SddThreadArtifactCleaner(JdbcTemplate jdbc, PlatformTransactionManager transactions, JsonCodec json) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json;
    }

    @Override public boolean accepts(RunScope scope) { return "local-user".equals(scope.userId()); }

    @Override public void deleting(RunScope scope) {
        if (accepts(scope)) new SddTaskStore(scope.workspaceId(), jdbc, transactions, json)
                .deleteThreadArtifacts(scope.sessionId());
    }
}
