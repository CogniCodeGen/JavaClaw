package com.javaclaw.framework.core;

import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.RunStore;
import com.javaclaw.framework.spi.ThreadStore;
import com.javaclaw.framework.store.ThreadRolloutProjector;
import java.util.List;

/** Coordinates task lifecycle across transactional state and restartable resource cleanup. */
public final class DefaultThreadClient implements ThreadClient {
    private final ThreadStore threads;
    private final RunStore runs;
    private final AgentClient agents;
    private final ThreadLifecycleRegistry lifecycle;
    private final ThreadRolloutProjector rollouts;
    public DefaultThreadClient(ThreadStore threads, RunStore runs, AgentClient agents,
                               ThreadLifecycleRegistry lifecycle, ThreadRolloutProjector rollouts) {
        this.threads = threads; this.runs = runs; this.agents = agents; this.lifecycle = lifecycle; this.rollouts = rollouts;
    }
    @Override public ThreadSnapshot start(ThreadStartRequest request) { return threads.create(request); }
    @Override public ThreadSnapshot get(RunScope scope) {
        ThreadSnapshot value = threads.find(scope).orElseThrow(() -> new java.util.NoSuchElementException("thread not found"));
        if (value.status() == ThreadStatus.DELETED || value.status() == ThreadStatus.DELETING)
            throw new java.util.NoSuchElementException("thread deleted");
        return value;
    }
    @Override public List<ThreadSnapshot> list(String workspace, String user, boolean archived) { return threads.list(workspace, user, archived); }
    @Override public ThreadSnapshot resume(RunScope scope) {
        ThreadSnapshot current = get(scope);
        if (current.status() == ThreadStatus.FORKING)
            lifecycle.forked(current, threads.events(scope, 0));
        return threads.setStatus(scope, ThreadStatus.ACTIVE);
    }
    @Override public ThreadSnapshot archive(RunScope scope) { return threads.setStatus(scope, ThreadStatus.ARCHIVED); }
    @Override public ThreadSnapshot configure(RunScope scope, ThreadConfiguration configuration) { return threads.configure(scope, configuration); }
    @Override public List<RunSnapshot> turns(RunScope scope) { return threads.turns(scope); }
    @Override public List<ThreadEvent> events(RunScope scope, long after) { return threads.events(scope, after); }
    @Override public ThreadSnapshot fork(RunScope source, TurnId through, String title) {
        ThreadSnapshot target = threads.fork(source, through, title);
        try {
            return resume(target.scope());
        } catch (RuntimeException failure) {
            delete(target.scope()); throw failure;
        }
    }
    @Override public void delete(RunScope scope) {
        if (threads.find(scope).isEmpty()) threads.create(ThreadStartRequest.root(scope, ""));
        List<RunScope> deleted = threads.markDeleting(scope);
        // A crash may leave external resources after the transactional tombstone was committed.
        if (deleted.isEmpty()) deleted = List.of(scope);
        for (var run : runs.nonTerminalRuns()) {
            if (deleted.contains(run.request().scope()))
                agents.cancel(run.snapshot().id(), new CancelReason("THREAD_DELETED", "会话已删除"));
        }
        for (RunScope target : deleted.reversed()) {
            lifecycle.deleting(target);
            rollouts.delete(target);
            threads.purge(target);
        }
    }
}
