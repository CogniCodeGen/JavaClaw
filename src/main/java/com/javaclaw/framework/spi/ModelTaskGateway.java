package com.javaclaw.framework.spi;

import java.util.concurrent.CompletionStage;

/** Unified provider, retry, usage, budget, audit and cancellation boundary for auxiliary calls. */
public interface ModelTaskGateway {
    CompletionStage<ModelTaskResult> execute(ModelTaskRequest request);

    /** Execute on an existing managed carrier to avoid a nested executor quota deadlock. */
    default ModelTaskResult executeInline(ModelTaskRequest request) {
        return execute(request).toCompletableFuture().join();
    }
}
