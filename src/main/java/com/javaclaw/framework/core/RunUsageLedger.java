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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

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
                parent == null ? new ReentrantLock(true) : parent.calls,
                parent == null ? new AtomicInteger() : parent.abandonedCalls));
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

    /** 调用在实际派发前计入整棵预算树；同一持久化 invocation 的恢复不会再次扣除。 */
    synchronized void recordToolCall(RunId runId, String invocationId) {
        if (invocationId == null || invocationId.isBlank())
            throw new IllegalArgumentException("tool invocation id is required for budget admission");
        Account account = require(runId);
        if (account.toolInvocations.contains(invocationId)) return;
        for (RunId ancestorId : lineage(runId)) {
            Account ancestor = require(ancestorId);
            long used = aggregateToolCalls(ancestorId);
            if (used >= ancestor.budget.maxToolCalls())
                throw BudgetExceededException.toolCalls((int) Math.min(Integer.MAX_VALUE, used + 1),
                        ancestor.budget.maxToolCalls());
        }
        account.toolInvocations.add(invocationId);
    }

    synchronized void restoreToolCalls(RunId runId, java.util.Collection<String> invocations) {
        Account account = require(runId);
        account.toolInvocations.clear();
        account.toolInvocations.addAll(invocations);
    }

    private long aggregateToolCalls(RunId runId) {
        long count = 0;
        for (var entry : accounts.entrySet()) if (belongsTo(entry.getKey(), runId))
            count = Math.addExact(count, entry.getValue().toolInvocations.size());
        return count;
    }

    /** Serializes physical model requests sharing a budget so siblings observe settled usage. */
    public ModelCall beginModelCall(RunId runId) {
        ReentrantLock lock;
        AtomicInteger abandoned;
        synchronized (this) {
            Account account = require(runId);
            lock = account.calls;
            abandoned = account.abandonedCalls;
        }
        try {
            do { requireNoAbandonedCall(abandoned); }
            while (!lock.tryLock(50, TimeUnit.MILLISECONDS));
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new com.javaclaw.framework.spi.RunCancelledException();
        }
        try {
            requireNoAbandonedCall(abandoned);
            synchronized (this) { checkLineage(runId, true); }
            return new ModelCall(this, runId, lock, abandoned);
        } catch (RuntimeException failure) { lock.unlock(); throw failure; }
    }

    private static void requireNoAbandonedCall(AtomicInteger abandoned) {
        if (abandoned.get() > 0) throw new com.javaclaw.framework.api.TurnPausedException(
                "上一次模型调用已超时或取消，但提供方仍在处理中；已暂停后续模型请求，旧调用结束后可继续。");
    }

    public synchronized RunBudget remainingBudget(RunId runId) {
        Account account = require(runId);
        RunBudget remaining = RunBudget.UNBOUNDED;
        for (RunId current : lineage(runId)) {
            Account ancestor = require(current);
            UsageSnapshot used = aggregateSnapshot(current);
            remaining = remaining.restrictWith(new RunBudget(ancestor.budget.timeout(),
                    Math.max(0, ancestor.budget.maxInputTokens() - used.inputTokens()),
                    Math.max(0, ancestor.budget.maxOutputTokens() - used.outputTokens()),
                    (int) Math.max(0, ancestor.budget.maxToolCalls() - aggregateToolCalls(current)),
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

    private synchronized void requireInputCapacity(RunId runId, long promptTokenFloor) {
        if (promptTokenFloor < 0) throw new IllegalArgumentException("negative prompt floor");
        for (RunId current : lineage(runId)) {
            Account account = require(current);
            long used = aggregateSnapshot(current).inputTokens();
            long remaining = Math.max(0, account.budget.maxInputTokens() - used);
            if (promptTokenFloor > remaining) {
                throw BudgetExceededException.modelInputPreflight(
                        used, remaining, promptTokenFloor, account.budget.maxInputTokens());
            }
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
            if (entry.getValue().calls.isLocked()) continue;
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
        private final RunUsageLedger ledger;
        private final RunId runId;
        private final ReentrantLock lock;
        private final AtomicInteger abandonedCalls;
        private boolean closed;
        private boolean abandoned;
        private ModelCall(RunUsageLedger ledger, RunId runId, ReentrantLock lock,
                          AtomicInteger abandonedCalls) {
            this.ledger = ledger; this.runId = runId; this.lock = lock;
            this.abandonedCalls = abandonedCalls;
        }
        /** Check the prompt while this physical call still holds its shared budget lock. */
        public void requireInputCapacity(long promptTokenFloor) {
            if (closed || !lock.isHeldByCurrentThread()) {
                throw new IllegalStateException("model call admission lease is not active");
            }
            ledger.requireInputCapacity(runId, promptTokenFloor);
        }
        /** 逻辑收尾不释放物理预算锁，但阻止后续请求无限等待同一个旧提供方。 */
        public synchronized void markAbandoned() {
            if (!closed && !abandoned) {
                abandoned = true;
                abandonedCalls.incrementAndGet();
            }
        }
        @Override public synchronized void close() {
            if (!closed) {
                closed = true;
                if (abandoned) abandonedCalls.decrementAndGet();
                lock.unlock();
            }
        }
    }
    private static final class Account {
        private final RunBudget budget;
        private final RunScope scope;
        private final RunId parent;
        private final ReentrantLock calls;
        private final AtomicInteger abandonedCalls;
        private UsageSnapshot direct = UsageSnapshot.ZERO;
        private final java.util.Set<String> toolInvocations = new HashSet<>();
        private long closedAtNanos;
        private Account(RunBudget budget, RunScope scope, RunId parent, ReentrantLock calls,
                        AtomicInteger abandonedCalls) {
            this.budget = Objects.requireNonNull(budget); this.scope = Objects.requireNonNull(scope);
            this.parent = parent; this.calls = calls;
            this.abandonedCalls = abandonedCalls;
        }
    }
}
