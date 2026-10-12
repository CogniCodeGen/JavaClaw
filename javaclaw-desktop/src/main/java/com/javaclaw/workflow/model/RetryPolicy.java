package com.javaclaw.workflow.model;

/** 节点失败重试策略。maxAttempts 包含首次执行。 */
public record RetryPolicy(int maxAttempts, long initialBackoffMillis, double multiplier) {
    public static final RetryPolicy NONE = new RetryPolicy(1, 0L, 1.0);

    public RetryPolicy {
        if (maxAttempts < 1 || maxAttempts > 10) {
            throw new IllegalArgumentException("maxAttempts 必须在 1..10 之间");
        }
        if (initialBackoffMillis < 0 || initialBackoffMillis > 300_000L) {
            throw new IllegalArgumentException("initialBackoffMillis 超出范围");
        }
        if (multiplier < 1.0 || multiplier > 10.0) {
            throw new IllegalArgumentException("multiplier 必须在 1..10 之间");
        }
    }

    public long backoffBeforeAttempt(int attempt) {
        if (attempt <= 1 || initialBackoffMillis == 0) return 0L;
        double value = initialBackoffMillis * Math.pow(multiplier, attempt - 2);
        return Math.min(300_000L, (long) value);
    }
}
