package com.javaclaw.framework.core;

import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.BudgetExceededException;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.spi.RunUsageObserver;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/** Direct response charges are recorded once; ancestors enforce the sum of their subtree. */
public final class RunUsageLedger {
    private static final long TERMINAL_RETENTION_NANOS = java.time.Duration.ofHours(1).toNanos();
    private final Map<RunId, Account> accounts = new HashMap<>();
    private final RunUsageObserver observer;

    public RunUsageLedger() { this(RunUsageObserver.noop()); }
    public RunUsageLedger(RunUsageObserver observer) { this.observer = Objects.requireNonNull(observer); }

    public void open(RunId runId, RunBudget budget, RunScope scope) { open(runId, budget, scope, null); }

    public synchronized void open(RunId runId, RunBudget budget, RunScope scope, RunId parentRunId) {
        purgeExpired();
        if (accounts.containsKey(runId)) throw new IllegalStateException("usage account already exists: " + runId);
        Account parent = parentRunId == null ? null : require(parentRunId);
        if (parent != null && (!parent.scope.workspaceId().equals(scope.workspaceId())
                || !parent.scope.userId().equals(scope.userId())))
            throw new IllegalArgumentException("parent usage account belongs to another workspace or user");
        accounts.put(runId, new Account(budget, scope, parentRunId,
                parent == null ? new ReentrantLock(true) : parent.calls));
    }

    public synchronized boolean contains(RunId runId) { return accounts.containsKey(runId); }

    public UsageSnapshot record(RunId runId, long inputTokens, long outputTokens, BigDecimal cost) {
        long input = Math.max(0, inputTokens), output = Math.max(0, outputTokens);
        BigDecimal charge = cost == null ? BigDecimal.ZERO : cost.max(BigDecimal.ZERO);
        RunScope scope;
        UsageSnapshot direct;
        RuntimeException exceeded = null;
        synchronized (this) {
            Account account = require(runId);
            account.direct = plus(account.direct, new UsageSnapshot(input, output, charge));
            direct = account.direct;
            scope = account.scope;
            try { checkLineage(runId, false); } catch (RuntimeException failure) { exceeded = failure; }
        }
        // Product usage projections receive only the physical provider charge, never ancestor sums.
        try { observer.recorded(runId, scope, input, output, charge); }
        catch (RuntimeException failure) {
            if (exceeded == null) throw failure;
            exceeded.addSuppressed(failure);
        }
        if (exceeded != null) throw exceeded;
        return direct;
    }

    /** Replace from durable evidence; repeat startup replay cannot double-charge an account. */
    synchronized UsageSnapshot restore(RunId runId, long inputTokens, long outputTokens, BigDecimal cost) {
        UsageSnapshot snapshot = new UsageSnapshot(Math.max(0, inputTokens), Math.max(0, outputTokens),
                cost == null ? BigDecimal.ZERO : cost.max(BigDecimal.ZERO));
        require(runId).direct = snapshot;
        return snapshot;
    }

    public synchronized UsageSnapshot snapshot(RunId runId) { return require(runId).direct; }

    public synchronized UsageSnapshot aggregateSnapshot(RunId runId) {
        require(runId);
        UsageSnapshot result = UsageSnapshot.ZERO;
        for (var entry : accounts.entrySet()) if (belongsTo(entry.getKey(), runId)) result = plus(result, entry.getValue().direct);
        return result;
    }

    /** Serializes physical model requests sharing a budget so siblings observe settled usage. */
    public ModelCall beginModelCall(RunId runId) {
        ReentrantLock lock;
        synchronized (this) { lock = require(runId).calls; }
        try { lock.lockInterruptibly(); }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new com.javaclaw.framework.spi.RunCancelledException();
        }
        try {
            synchronized (this) { checkLineage(runId, true); }
            return new ModelCall(lock);
        } catch (RuntimeException failure) { lock.unlock(); throw failure; }
    }

    public synchronized RunBudget remainingBudget(RunId runId) {
        Account account = require(runId);
        RunBudget remaining = RunBudget.UNBOUNDED;
        for (RunId current : lineage(runId)) {
            Account ancestor = require(current);
            UsageSnapshot used = aggregateSnapshot(current);
            remaining = remaining.restrictWith(new RunBudget(ancestor.budget.timeout(),
                    Math.max(0, ancestor.budget.maxInputTokens() - used.inputTokens()),
                    Math.max(0, ancestor.budget.maxOutputTokens() - used.outputTokens()), ancestor.budget.maxToolCalls(),
                    ancestor.budget.maxCost().subtract(used.cost()).max(BigDecimal.ZERO)));
        }
        return account.budget.restrictWith(remaining);
    }

    public synchronized void close(RunId runId) {
        Account account = accounts.get(runId);
        if (account != null && account.closedAtNanos == 0) account.closedAtNanos = System.nanoTime();
    }

    private void checkLineage(RunId runId, boolean admission) {
        for (RunId current : lineage(runId)) {
            Account account = require(current);
            UsageSnapshot used = aggregateSnapshot(current);
            if (used.inputTokens() > account.budget.maxInputTokens()
                    || admission && used.inputTokens() == account.budget.maxInputTokens())
                throw BudgetExceededException.modelInputTokens(used.inputTokens(), account.budget.maxInputTokens());
            if (used.outputTokens() > account.budget.maxOutputTokens()
                    || admission && used.outputTokens() == account.budget.maxOutputTokens())
                throw BudgetExceededException.modelOutputTokens(used.outputTokens(), account.budget.maxOutputTokens());
            int cost = used.cost().compareTo(account.budget.maxCost());
            if (cost > 0 || admission && cost == 0)
                throw BudgetExceededException.modelCost(used.cost(), account.budget.maxCost());
        }
    }

    private List<RunId> lineage(RunId id) {
        List<RunId> values = new ArrayList<>();
        var seen = new HashSet<RunId>();
        while (id != null) {
            if (!seen.add(id)) throw new IllegalStateException("cyclic parent usage linkage");
            values.add(id);
            id = require(id).parent;
        }
        return values;
    }
    private boolean belongsTo(RunId child, RunId parent) { return lineage(child).contains(parent); }
    private Account require(RunId id) {
        Account account = accounts.get(id);
        if (account == null) throw new IllegalStateException("usage account not found: " + id);
        return account;
    }
    private static UsageSnapshot plus(UsageSnapshot left, UsageSnapshot right) {
        return new UsageSnapshot(Math.addExact(left.inputTokens(), right.inputTokens()),
                Math.addExact(left.outputTokens(), right.outputTokens()), left.cost().add(right.cost()));
    }
    private void purgeExpired() {
        long now = System.nanoTime();
        var expired = new HashSet<RunId>();
        for (var entry : accounts.entrySet()) {
            if (entry.getValue().parent != null) continue;
            var family = accounts.keySet().stream().filter(id -> belongsTo(id, entry.getKey())).toList();
            if (family.stream().allMatch(id -> require(id).closedAtNanos != 0
                    && now - require(id).closedAtNanos >= TERMINAL_RETENTION_NANOS)) expired.addAll(family);
        }
        expired.forEach(accounts::remove);
    }

    public record UsageSnapshot(long inputTokens, long outputTokens, BigDecimal cost) {
        static final UsageSnapshot ZERO = new UsageSnapshot(0, 0, BigDecimal.ZERO);
    }
    public static final class ModelCall implements AutoCloseable {
        private final ReentrantLock lock;
        private boolean closed;
        private ModelCall(ReentrantLock lock) { this.lock = lock; }
        @Override public void close() { if (!closed) { closed = true; lock.unlock(); } }
    }
    private static final class Account {
        private final RunBudget budget;
        private final RunScope scope;
        private final RunId parent;
        private final ReentrantLock calls;
        private UsageSnapshot direct = UsageSnapshot.ZERO;
        private long closedAtNanos;
        private Account(RunBudget budget, RunScope scope, RunId parent, ReentrantLock calls) {
            this.budget = Objects.requireNonNull(budget); this.scope = Objects.requireNonNull(scope);
            this.parent = parent; this.calls = calls;
        }
    }
}
