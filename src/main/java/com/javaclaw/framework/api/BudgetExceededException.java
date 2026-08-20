package com.javaclaw.framework.api;

import java.math.BigDecimal;
import java.util.Objects;

/** Identifies the exact hard limit that stopped a run. */
public final class BudgetExceededException extends RuntimeException {
    public enum Kind {
        MODEL_INPUT_TOKENS,
        MODEL_OUTPUT_TOKENS,
        MODEL_COST,
        TOOL_CALLS,
        REPEATED_TOOL_CALLS,
        UNKNOWN
    }

    private final Kind kind;
    private final String actual;
    private final String limit;

    /** Backward-compatible constructor for callers without structured budget metadata. */
    public BudgetExceededException(String message) {
        this(Kind.UNKNOWN, message, "", "");
    }

    public BudgetExceededException(Kind kind, String message, String actual, String limit) {
        super(Objects.requireNonNullElse(message, "execution budget exceeded"));
        this.kind = Objects.requireNonNullElse(kind, Kind.UNKNOWN);
        this.actual = Objects.requireNonNullElse(actual, "");
        this.limit = Objects.requireNonNullElse(limit, "");
    }

    public static BudgetExceededException modelInputTokens(long actual, long limit) {
        return numeric(Kind.MODEL_INPUT_TOKENS, "model input token budget exceeded", actual, limit);
    }

    public static BudgetExceededException modelOutputTokens(long actual, long limit) {
        return numeric(Kind.MODEL_OUTPUT_TOKENS, "model output token budget exceeded", actual, limit);
    }

    public static BudgetExceededException modelCost(BigDecimal actual, BigDecimal limit) {
        String used = actual.toPlainString();
        String maximum = limit.toPlainString();
        return new BudgetExceededException(Kind.MODEL_COST,
                "model cost budget exceeded: used=" + used + ", limit=" + maximum,
                used, maximum);
    }

    public static BudgetExceededException toolCalls(int actual, int limit) {
        return numeric(Kind.TOOL_CALLS, "tool call budget exceeded", actual, limit);
    }

    public static BudgetExceededException repeatedToolCalls(int actual, int limit) {
        return numeric(Kind.REPEATED_TOOL_CALLS, "repeated tool-call loop detected", actual, limit);
    }

    private static BudgetExceededException numeric(
            Kind kind, String description, long actual, long limit) {
        return new BudgetExceededException(kind,
                description + ": used=" + actual + ", limit=" + limit,
                Long.toString(actual), Long.toString(limit));
    }

    public Kind kind() {
        return kind;
    }

    public String actual() {
        return actual;
    }

    public String limit() {
        return limit;
    }
}
