package com.javaclaw.framework.api;

import reactor.core.publisher.Flux;

import java.util.concurrent.CompletionStage;

/** Live and replayable handle returned by AgentClient. */
public interface RunHandle {
    RunId id();

    Flux<RunEventEnvelope> events(long afterSequence);

    CompletionStage<RunOutcome> completion();
}
