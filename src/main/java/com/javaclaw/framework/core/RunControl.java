package com.javaclaw.framework.core;

import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.BudgetExceededException;
import com.javaclaw.framework.api.ToolApprovalGrant;
import com.javaclaw.framework.spi.CancellationToken;

import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;

/** Per-run mutable control state. It is never stored on an Agent singleton. */
public final class RunControl implements CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicInteger toolCalls = new AtomicInteger();
    private final AtomicReference<String> lastToolFingerprint = new AtomicReference<>();
    private final AtomicInteger consecutiveDuplicateCalls = new AtomicInteger();
    private final ConcurrentHashMap<String, ToolApprovalGrant> oneShotToolApprovals =
            new ConcurrentHashMap<>();
    private final java.util.concurrent.CopyOnWriteArrayList<Runnable> cancellationListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Clock clock;
    private final Instant deadline;
    private final RunBudget budget;

    RunControl(RunBudget budget, Clock clock) {
        this(budget, clock, clock.instant().plus(budget.timeout()));
    }

    RunControl(RunBudget budget, Clock clock, Instant deadline) {
        this.budget = Objects.requireNonNull(budget, "budget");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.deadline = Objects.requireNonNull(deadline, "deadline");
    }

    @Override
    public boolean cancelled() {
        return cancelled.get() || !clock.instant().isBefore(deadline);
    }

    public boolean cancel() {
        if (!cancelled.compareAndSet(false, true)) return false;
        cancellationListeners.forEach(Runnable::run);
        cancellationListeners.clear();
        return true;
    }

    @Override
    public CancellationRegistration onCancel(Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        if (cancelled()) {
            callback.run();
            return () -> { };
        }
        cancellationListeners.add(callback);
        if (cancelled() && cancellationListeners.remove(callback)) callback.run();
        return () -> cancellationListeners.remove(callback);
    }

    public Instant deadline() { return deadline; }

    @Override
    public Duration remaining() {
        Duration value = Duration.between(clock.instant(), deadline);
        return value.isNegative() ? Duration.ZERO : value;
    }

    public int recordToolCall(String fingerprint) {
        int count = restoreToolCall(fingerprint);
        throwIfCancelled();
        return count;
    }

    int restoreToolCall(String fingerprint) {
        int count = toolCalls.incrementAndGet();
        if (count > budget.maxToolCalls()) {
            throw BudgetExceededException.toolCalls(count, budget.maxToolCalls());
        }
        String previous = lastToolFingerprint.getAndSet(Objects.requireNonNull(fingerprint));
        int duplicates;
        if (fingerprint.equals(previous)) {
            duplicates = consecutiveDuplicateCalls.incrementAndGet();
        } else {
            consecutiveDuplicateCalls.set(0);
            duplicates = 0;
        }
        if (duplicates >= 3) {
            throw BudgetExceededException.repeatedToolCalls(duplicates + 1, 3);
        }
        return count;
    }

    public int toolCallCount() { return toolCalls.get(); }

    public void approveToolCall(ToolApprovalGrant grant) {
        Objects.requireNonNull(grant, "grant");
        if (!grant.approved()) throw new IllegalArgumentException("cannot store a denied tool grant");
        oneShotToolApprovals.put(grant.fingerprint(), grant);
    }

    /** Consumes a grant only when it was issued for this exact tool invocation. */
    public Optional<ToolApprovalGrant> consumeToolApprovalGrant(
            String tool, String fingerprint) {
        ToolApprovalGrant grant = oneShotToolApprovals.remove(fingerprint);
        if (grant == null || !grant.tool().equals(Objects.requireNonNull(tool, "tool"))) {
            return Optional.empty();
        }
        return Optional.of(grant);
    }

    /** Removes an unused one-shot grant after its exact continuation finishes or fails. */
    public void discardToolApprovalGrant(String fingerprint) {
        if (fingerprint != null) oneShotToolApprovals.remove(fingerprint);
    }
}
