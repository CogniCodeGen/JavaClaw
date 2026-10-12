package com.javaclaw.framework.springai;

import com.javaclaw.framework.core.InteractionEventWaitRequiredException;
import com.javaclaw.framework.core.InteractionWaitRequiredException;

import java.util.concurrent.atomic.AtomicReference;

/** Carries a host wait across normal stream completion without inventing a tool response. */
final class InteractionStreamWait {
    private final AtomicReference<RuntimeException> pending = new AtomicReference<>();

    boolean capture(Throwable failure) {
        Throwable cause = ReasoningGatewaySupport.unwrap(failure);
        if (!(cause instanceof InteractionWaitRequiredException)
                && !(cause instanceof InteractionEventWaitRequiredException)) return false;
        pending.compareAndSet(null, (RuntimeException) cause);
        return true;
    }

    /** Called after every outer Spring AI aggregator has completed normally. */
    void throwIfWaiting() {
        RuntimeException wait = pending.getAndSet(null);
        if (wait != null) throw wait;
    }
}
