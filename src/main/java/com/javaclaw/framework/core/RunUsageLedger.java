package com.javaclaw.framework.core;

import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.BudgetExceededException;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.api.ModelTokenUsage;
import com.javaclaw.framework.api.ModelUsageFact;
import com.javaclaw.framework.spi.RunUsageObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** Shared token/cost account used by primary reasoning and all ModelTaskGateway calls. */
public final class RunUsageLedger {
    private static final Logger log = LoggerFactory.getLogger(RunUsageLedger.class);
    private static final long TERMINAL_RETENTION_NANOS = java.time.Duration.ofHours(1).toNanos();
    private final ConcurrentHashMap<RunId, Account> accounts = new ConcurrentHashMap<>();
    private final RunUsageObserver observer;
    private final LongSupplier nanoTime;

    public RunUsageLedger() {
        this(RunUsageObserver.noop(), System::nanoTime);
    }

    public RunUsageLedger(RunUsageObserver observer) {
        this(observer, System::nanoTime);
    }

    RunUsageLedger(RunUsageObserver observer, LongSupplier nanoTime) {
        this.observer = Objects.requireNonNull(observer, "observer");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public void open(RunId runId, RunBudget budget, RunScope scope) {
        purgeExpired();
        if (accounts.putIfAbsent(runId, new Account(budget, scope)) != null) {
            throw new IllegalStateException("usage account already exists: " + runId);
        }
    }

    public UsageSnapshot record(RunId runId, long inputTokens, long outputTokens, BigDecimal cost) {
        return record(runId, new ModelTokenUsage(inputTokens, outputTokens), cost, true, null);
    }

    public UsageSnapshot record(RunId runId, ModelTokenUsage usage, BigDecimal cost) {
        return record(runId, usage, cost, true, null);
    }

    /** Idempotent entry point for one durably identified provider response. */
    public UsageSnapshot recordOnce(
            RunId runId, String modelCallId, ModelTokenUsage usage, BigDecimal cost) {
        ModelTokenUsage value = usage == null ? ModelTokenUsage.ZERO : usage;
        return recordOnce(runId, modelCallId, Instant.now(), value,
                value.inputTokens(), cost);
    }

    /** Idempotent rich entry point for one durably committed provider response. */
    public UsageSnapshot recordOnce(
            RunId runId,
            String modelCallId,
            Instant occurredAt,
            ModelTokenUsage usage,
            long pricingInputTokens,
            BigDecimal cost) {
        String callId = Objects.requireNonNull(modelCallId, "modelCallId").strip();
        if (callId.isEmpty()) throw new IllegalArgumentException("modelCallId is blank");
        purgeExpired();
        Account account = accounts.get(runId);
        if (account == null) throw new IllegalStateException("usage account not found: " + runId);
        synchronized (account.modelCallIds) {
            if (!account.modelCallIds.add(callId)) return snapshot(account);
            ModelUsageFact fact = new ModelUsageFact(
                    runId, account.scope, callId, occurredAt,
                    usage, pricingInputTokens, cost);
            UsageSnapshot recorded = record(
                    runId, account, fact.usage(), fact.estimatedCostCny(), true, fact);
            if (account.closedAtNanos.get() != 0L) {
                account.closedAtNanos.set(nanoTime.getAsLong());
            }
            return recorded;
        }
    }

    /** Rehydrates an account from durable events without replaying product-side projections. */
    UsageSnapshot restore(RunId runId, long inputTokens, long outputTokens, BigDecimal cost) {
        return record(runId, new ModelTokenUsage(inputTokens, outputTokens), cost, false, null);
    }

    UsageSnapshot restore(RunId runId, ModelTokenUsage usage, BigDecimal cost) {
        return record(runId, usage, cost, false, null);
    }

    UsageSnapshot restore(
            RunId runId, ModelTokenUsage usage, BigDecimal cost, java.util.Set<String> modelCallIds) {
        Account account = accounts.get(runId);
        if (account == null) throw new IllegalStateException("usage account not found: " + runId);
        synchronized (account.modelCallIds) {
            account.modelCallIds.addAll(modelCallIds == null ? java.util.Set.of() : modelCallIds);
            return record(runId, usage, cost, false, null);
        }
    }

    boolean hasAccount(RunId runId) {
        purgeExpired();
        return accounts.containsKey(runId);
    }

    /** Keeps a terminal account alive for a provider call that is about to start. */
    UsageSnapshot retainAccount(RunId runId) {
        purgeExpired();
        Account account = accounts.get(runId);
        if (account == null) return null;
        if (account.closedAtNanos.get() != 0L) {
            account.closedAtNanos.set(nanoTime.getAsLong());
        }
        return snapshot(account);
    }

    UsageSnapshot restoreAccount(
            RunId runId, RunBudget budget, RunScope scope,
            DurableUsageHistory.Snapshot history, boolean terminal) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(history, "history");
        purgeExpired();
        Account restored = new Account(budget, scope);
        ModelTokenUsage usage = history.usage();
        restored.input.set(usage.inputTokens());
        restored.cacheRead.set(usage.cacheReadInputTokens());
        restored.cacheWrite.set(usage.cacheWriteInputTokens());
        restored.output.set(usage.outputTokens());
        restored.reasoning.set(usage.reasoningTokens());
        restored.calls.set(usage.modelCalls());
        restored.cost.set(history.cost());
        restored.modelCallIds.addAll(history.modelCallIds());
        if (terminal) restored.closedAtNanos.set(nanoTime.getAsLong());
        Account effective = accounts.putIfAbsent(runId, restored);
        return snapshot(effective == null ? restored : effective);
    }

    private UsageSnapshot record(
            RunId runId, ModelTokenUsage delta,
            BigDecimal cost, boolean notifyObserver, ModelUsageFact fact) {
        purgeExpired();
        Account account = accounts.get(runId);
        if (account == null) throw new IllegalStateException("usage account not found: " + runId);
        return record(runId, account, delta, cost, notifyObserver, fact);
    }

    private UsageSnapshot record(
            RunId runId, Account account, ModelTokenUsage delta,
            BigDecimal cost, boolean notifyObserver, ModelUsageFact fact) {
        ModelTokenUsage usage = delta == null ? ModelTokenUsage.ZERO : delta;
        long input = account.input.addAndGet(usage.inputTokens());
        long cacheRead = account.cacheRead.addAndGet(usage.cacheReadInputTokens());
        long cacheWrite = account.cacheWrite.addAndGet(usage.cacheWriteInputTokens());
        long output = account.output.addAndGet(usage.outputTokens());
        long reasoning = account.reasoning.addAndGet(usage.reasoningTokens());
        long calls = account.calls.addAndGet(usage.modelCalls());
        BigDecimal totalCost = account.cost.updateAndGet(current ->
                current.add(cost == null ? BigDecimal.ZERO : cost.max(BigDecimal.ZERO)));
        BudgetExceededException exceeded = null;
        if (input > account.budget.maxInputTokens()) {
            exceeded = BudgetExceededException.modelInputTokens(
                    input, account.budget.maxInputTokens());
        } else if (output > account.budget.maxOutputTokens()) {
            exceeded = BudgetExceededException.modelOutputTokens(
                    output, account.budget.maxOutputTokens());
        } else if (totalCost.compareTo(account.budget.maxCost()) > 0) {
            exceeded = BudgetExceededException.modelCost(totalCost, account.budget.maxCost());
        }
        if (notifyObserver) {
            try {
                if (fact == null) {
                    observer.recorded(runId, account.scope, usage,
                            cost == null ? BigDecimal.ZERO : cost.max(BigDecimal.ZERO));
                } else {
                    observer.recorded(fact);
                }
            } catch (RuntimeException projectionFailure) {
                // The committed usage event is the fact source. A product projection outage must
                // never cause a paid provider response to be issued again.
                log.warn("Could not project model usage for {}: {}",
                        runId, projectionFailure.toString());
                log.debug("Model usage projection failure", projectionFailure);
            }
        }
        UsageSnapshot snapshot = new UsageSnapshot(
                input, cacheRead, cacheWrite, output, reasoning, calls, totalCost);
        if (exceeded != null) throw exceeded;
        return snapshot;
    }

    public UsageSnapshot snapshot(RunId runId) {
        Account account = Objects.requireNonNull(accounts.get(runId), "usage account");
        return snapshot(account);
    }

    private static UsageSnapshot snapshot(Account account) {
        return new UsageSnapshot(account.input.get(), account.cacheRead.get(),
                account.cacheWrite.get(), account.output.get(), account.reasoning.get(),
                account.calls.get(), account.cost.get());
    }

    public void close(RunId runId) {
        Account account = accounts.get(runId);
        if (account != null) account.closedAtNanos.compareAndSet(0L, nanoTime.getAsLong());
    }

    private void purgeExpired() {
        long now = nanoTime.getAsLong();
        accounts.entrySet().removeIf(entry -> {
            long closedAt = entry.getValue().closedAtNanos.get();
            return closedAt != 0L && now - closedAt >= TERMINAL_RETENTION_NANOS;
        });
    }

    public record UsageSnapshot(
            long inputTokens, long cacheReadInputTokens, long cacheWriteInputTokens,
            long outputTokens, long reasoningTokens, long modelCalls, BigDecimal cost) {
        public UsageSnapshot(long inputTokens, long outputTokens, BigDecimal cost) {
            this(inputTokens, 0, 0, outputTokens, 0, 0, cost);
        }
    }

    private static final class Account {
        private final RunBudget budget;
        private final RunScope scope;
        private final AtomicLong input = new AtomicLong();
        private final AtomicLong cacheRead = new AtomicLong();
        private final AtomicLong cacheWrite = new AtomicLong();
        private final AtomicLong output = new AtomicLong();
        private final AtomicLong reasoning = new AtomicLong();
        private final AtomicLong calls = new AtomicLong();
        private final AtomicReference<BigDecimal> cost = new AtomicReference<>(BigDecimal.ZERO);
        private final AtomicLong closedAtNanos = new AtomicLong();
        private final java.util.Set<String> modelCallIds = new java.util.HashSet<>();

        private Account(RunBudget budget, RunScope scope) {
            this.budget = Objects.requireNonNull(budget, "budget");
            this.scope = Objects.requireNonNull(scope, "scope");
        }
    }
}
