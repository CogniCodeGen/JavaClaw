package com.javaclaw.framework.core;


import com.javaclaw.framework.api.*;
import reactor.core.publisher.Flux;

import java.util.concurrent.CompletionStage;

/** 连接同一次运行的持久化与实时事件，逐次检查线程可读性。 */
final class EngineRunHandle implements RunHandle {
    private final ActiveEngineRun active;

    private final AgentEngine engine;

    EngineRunHandle(AgentEngine engine, ActiveEngineRun active) {
        this.engine = engine;
        this.active = active;
    }

    @Override public RunId id() { return active.id; }

    @Override
    public Flux<RunEventEnvelope> events(long afterSequence) {
        Flux<RunEventEnvelope> durable = Flux.defer(() -> {
            engine.requireReadableRun(active.id);
            return Flux.fromIterable(engine.runs.eventsAfter(active.id, afterSequence));
        });
        Flux<RunEventEnvelope> live = active.sink.asFlux()
                .filter(event -> event.sequence() > afterSequence);
        return Flux.concat(durable, live).filter(event -> engine.runs.readable(active.request.scope()))
                .distinct(RunEventEnvelope::sequence);
    }

    @Override public CompletionStage<RunOutcome> completion() { return active.completion; }
}

