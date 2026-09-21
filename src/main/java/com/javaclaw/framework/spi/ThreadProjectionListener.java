package com.javaclaw.framework.spi;

import com.javaclaw.framework.api.*;

/** Acknowledges only durable projection acceptance; retries must be idempotent. */
public interface ThreadProjectionListener {
    boolean accepts(RunScope scope);
    void project(RunRequest request, ThreadEvent event);
}
