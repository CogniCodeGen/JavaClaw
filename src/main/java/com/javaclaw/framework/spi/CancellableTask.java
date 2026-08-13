package com.javaclaw.framework.spi;

import java.util.concurrent.CompletionStage;

/** A task distinguishes logical completion from actual carrier-thread termination. */
public interface CancellableTask<T> {
    CompletionStage<T> completion();
    CompletionStage<Void> termination();
    boolean cancel();
}
