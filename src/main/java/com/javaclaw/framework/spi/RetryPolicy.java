package com.javaclaw.framework.spi;

import java.util.Optional;

/** Ordered retry policy; the first policy returning a directive wins. */
public interface RetryPolicy {
    String id();

    int order();

    Optional<RetryDirective> evaluate(RetryContext context);
}
