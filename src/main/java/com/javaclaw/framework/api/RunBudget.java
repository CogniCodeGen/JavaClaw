package com.javaclaw.framework.api;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

/** Hard execution budget; layered budgets always resolve to the most restrictive values. */
public record RunBudget(
        Duration timeout,
        long maxInputTokens,
        long maxOutputTokens,
        int maxToolCalls,
        BigDecimal maxCost) {

    public static final RunBudget UNBOUNDED = new RunBudget(
            Duration.ofDays(3650), Long.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE,
            new BigDecimal("1E+100"));

    public RunBudget {
        timeout = Objects.requireNonNull(timeout, "timeout");
        maxCost = Objects.requireNonNull(maxCost, "maxCost");
        if (timeout.isZero() || timeout.isNegative()
                || maxInputTokens < 0 || maxOutputTokens < 0 || maxToolCalls < 0
                || maxCost.signum() < 0) {
            throw new IllegalArgumentException("run budget values must be non-negative and timeout positive");
        }
    }

    public RunBudget restrictWith(RunBudget other) {
        Objects.requireNonNull(other, "other");
        return new RunBudget(
                timeout.compareTo(other.timeout) <= 0 ? timeout : other.timeout,
                Math.min(maxInputTokens, other.maxInputTokens),
                Math.min(maxOutputTokens, other.maxOutputTokens),
                Math.min(maxToolCalls, other.maxToolCalls),
                maxCost.min(other.maxCost));
    }
}
