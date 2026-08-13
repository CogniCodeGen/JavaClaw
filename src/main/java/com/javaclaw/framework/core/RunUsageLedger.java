package com.javaclaw.framework.core;

import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.BudgetExceededException;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.spi.RunUsageObserver;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Shared token/cost account used by primary reasoning and all ModelTaskGateway calls. */
public final class RunUsageLedger {
    private static final long TERMINAL_RETENTION_NANOS = java.time.Duration.ofHours(1).toNanos();
    private final ConcurrentHashMap<RunId, Account> accounts = new ConcurrentHashMap<>();
    private final RunUsageObserver observer;

    public RunUsageLedger() {
        this(RunUsageObserver.noop());
    }

    public RunUsageLedger(RunUsageObserver observer) {
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    public void open(RunId runId, RunBudget budget, RunScope scope) {
        purgeExpired();
        if (accounts.putIfAbsent(runId, new Account(budget, scope)) != null) {
            throw new IllegalStateException("usage account already exists: " + runId);
        }
    }

    public UsageSnapshot record(RunId runId, long inputTokens, long outputTokens, BigDecimal cost) {
        return record(runId, inputTokens, outputTokens, cost, true);
    }

    /** Rehydrates an account from durable events without replaying product-side projections. */
    UsageSnapshot restore(RunId runId, long inputTokens, long outputTokens, BigDecimal cost) {
        return record(runId, inputTokens, outputTokens, cost, false);
    }

    private UsageSnapshot record(
            RunId runId, long inputTokens, long outputTokens,
            BigDecimal cost, boolean notifyObserver) {
        purgeExpired();
        Account account = accounts.get(runId);
        if (account == null) throw new IllegalStateException("usage account not found: " + runId);
        long input = account.input.addAndGet(Math.max(0, inputTokens));
        long output = account.output.addAndGet(Math.max(0, outputTokens));
        BigDecimal totalCost = account.cost.updateAndGet(current ->
                current.add(cost == null ? BigDecimal.ZERO : cost.max(BigDecimal.ZERO)));
        if (notifyObserver) {
            observer.recorded(runId, account.scope, Math.max(0, inputTokens),
                    Math.max(0, outputTokens),
                    cost == null ? BigDecimal.ZERO : cost.max(BigDecimal.ZERO));
        }
        if (input > account.budget.maxInputTokens()) {
            throw BudgetExceededException.modelInputTokens(
                    input, account.budget.maxInputTokens());
        }
        if (output > account.budget.maxOutputTokens()) {
            throw BudgetExceededException.modelOutputTokens(
                    output, account.budget.maxOutputTokens());
        }
        if (totalCost.compareTo(account.budget.maxCost()) > 0) {
            throw BudgetExceededException.modelCost(totalCost, account.budget.maxCost());
        }
        return new UsageSnapshot(input, output, totalCost);
    }

    public UsageSnapshot snapshot(RunId runId) {
        Account account = Objects.requireNonNull(accounts.get(runId), "usage account");
        return new UsageSnapshot(account.input.get(), account.output.get(), account.cost.get());
    }

    public void close(RunId runId) {
        Account account = accounts.get(runId);
        if (account != null) account.closedAtNanos.compareAndSet(0L, System.nanoTime());
    }

    private void purgeExpired() {
        long now = System.nanoTime();
        accounts.entrySet().removeIf(entry -> {
            long closedAt = entry.getValue().closedAtNanos.get();
            return closedAt != 0L && now - closedAt >= TERMINAL_RETENTION_NANOS;
        });
    }

    public record UsageSnapshot(long inputTokens, long outputTokens, BigDecimal cost) {}

    private static final class Account {
        private final RunBudget budget;
        private final RunScope scope;
        private final AtomicLong input = new AtomicLong();
        private final AtomicLong output = new AtomicLong();
        private final AtomicReference<BigDecimal> cost = new AtomicReference<>(BigDecimal.ZERO);
        private final AtomicLong closedAtNanos = new AtomicLong();

        private Account(RunBudget budget, RunScope scope) {
            this.budget = Objects.requireNonNull(budget, "budget");
            this.scope = Objects.requireNonNull(scope, "scope");
        }
    }
}
