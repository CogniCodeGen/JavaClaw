package com.javaclaw.platform.http;

import java.time.Duration;
import java.util.Set;

/** 幂等 HTTP 请求的有界指数退避策略。 */
public record HttpRetryPolicy(
        int maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff,
        Set<Integer> retryableStatuses) {

    public HttpRetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("HTTP 最大尝试次数必须大于零");
        }
        initialBackoff = initialBackoff == null ? Duration.ZERO : initialBackoff;
        maxBackoff = maxBackoff == null ? initialBackoff : maxBackoff;
        if (initialBackoff.isNegative() || maxBackoff.isNegative()
                || maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("HTTP 退避时间非法");
        }
        retryableStatuses = retryableStatuses == null ? Set.of() : Set.copyOf(retryableStatuses);
    }

    public static HttpRetryPolicy none() {
        return new HttpRetryPolicy(1, Duration.ZERO, Duration.ZERO, Set.of());
    }

    public static HttpRetryPolicy idempotentDefaults() {
        return new HttpRetryPolicy(3, Duration.ofMillis(200), Duration.ofSeconds(2),
                Set.of(408, 429, 502, 503, 504));
    }

    Duration backoffAfterAttempt(int completedAttempt) {
        if (initialBackoff.isZero()) {
            return Duration.ZERO;
        }
        long multiplier = 1L << Math.min(Math.max(completedAttempt - 1, 0), 30);
        long nanos;
        try {
            nanos = Math.multiplyExact(initialBackoff.toNanos(), multiplier);
        } catch (ArithmeticException overflow) {
            nanos = Long.MAX_VALUE;
        }
        return Duration.ofNanos(Math.min(nanos, maxBackoff.toNanos()));
    }
}
