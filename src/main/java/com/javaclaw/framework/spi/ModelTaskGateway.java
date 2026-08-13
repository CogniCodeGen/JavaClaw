package com.javaclaw.framework.spi;

import java.util.concurrent.CompletionStage;

/** Unified provider, retry, usage, budget, audit and cancellation boundary for auxiliary calls. */
public interface ModelTaskGateway {
    CompletionStage<ModelTaskResult> execute(ModelTaskRequest request);
}
