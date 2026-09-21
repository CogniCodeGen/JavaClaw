package com.javaclaw.application.agent;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class ChildTurnContinuationTest {
    @Test void resumesAuthorizedPausedChildBeforeCreatingAnyQueuedDuplicate() {
        var agents = new PausedClient();
        RunHandle resumed = ChildTurnContinuation.start(agents, request());
        assertEquals(agents.id, resumed.id());
        assertEquals(0, agents.starts);
        assertEquals(1, agents.resumes);
        assertEquals("delegation.continue", agents.command.type());
        assertTrue(agents.command.payload().isEmpty(), "recovery must not inject a new task into the original turn");
    }

    @Test void uncertainToolRequiresReconciliationWithoutResumeOrNewTurn() {
        var agents = new PausedClient();
        agents.uncertain = true;
        assertThrows(TurnPausedException.class, () -> ChildTurnContinuation.start(agents, request()));
        assertEquals(0, agents.starts);
        assertEquals(0, agents.resumes);
        assertEquals(0, agents.cancels);
    }

    @Test void aChildThatPausesDuringWorkReturnsControlWithoutCancellingItsDurableTurn() {
        var agents = new PausedClient();
        assertThrows(TurnPausedException.class, () -> ChildTurnContinuation.await(
                agents, agents.handle, Duration.ofSeconds(2), () -> { }));
        assertEquals(0, agents.cancels);
        assertFalse(agents.handle.completion().toCompletableFuture().isDone());
    }

    @Test void runningNewAndCompatibilityChildrenRemainOnTheNormalStartPath() throws Exception {
        var agents = new PausedClient();
        agents.state = RunState.RUNNING;
        assertSame(agents.handle, ChildTurnContinuation.start(agents, request()));
        assertEquals(1, agents.starts);
        assertEquals(0, agents.resumes);
        agents.managed = false;
        assertSame(agents.handle, ChildTurnContinuation.start(agents, request()));
        assertEquals(2, agents.starts);
        var outcome = new RunOutcome(agents.id, RunState.COMPLETED, null, null);
        agents.handle.completion().toCompletableFuture().complete(outcome);
        assertEquals(outcome, ChildTurnContinuation.await(agents, agents.handle, Duration.ofSeconds(1), () -> { }));
        agents.managed = true;
        agents.hasActive = false;
        assertSame(agents.handle, ChildTurnContinuation.start(agents, request()));
        assertEquals(3, agents.starts);
    }

    @Test void aPausedHandleFoundThroughItsIdempotencyKeyAlsoResumesWithoutNewInput() {
        var agents = new PausedClient();
        agents.hasActive = false;
        ChildTurnContinuation.start(agents, request());
        assertEquals(1, agents.starts);
        assertEquals(1, agents.resumes);
    }

    @Test void deadlineAndCancellationStopOnlyTheCallerAwait() {
        var agents = new PausedClient();
        assertThrows(java.util.concurrent.TimeoutException.class, () -> ChildTurnContinuation.await(
                agents, agents.handle, Duration.ZERO, () -> { }));
        assertThrows(java.util.concurrent.CancellationException.class, () -> ChildTurnContinuation.await(
                agents, agents.handle, Duration.ofSeconds(1), () -> { throw new java.util.concurrent.CancellationException(); }));
        agents.managed = false;
        assertThrows(java.util.concurrent.TimeoutException.class, () -> ChildTurnContinuation.await(
                agents, agents.handle, Duration.ofMillis(2), () -> { }));
        agents.managed = true;
        agents.state = RunState.RUNNING;
        assertThrows(java.util.concurrent.TimeoutException.class, () -> ChildTurnContinuation.await(
                agents, agents.handle, Duration.ofMillis(2), () -> { }));
        assertEquals(0, agents.cancels);
    }

    private static RunRequest request() {
        return RunRequest.builder().scope(new RunScope("workspace", "user", "child"))
                .agent(AgentDefinitionRef.latest("system.default")).profile(RunProfileRef.latest("chat"))
                .source(InvocationSource.chat())
                .input(InputBlock.text("delegated task")).build();
    }

    private static final class PausedClient implements AgentClient {
        private final RunId id = RunId.random();
        private final RunHandle handle = new RunHandle() {
            private final CompletableFuture<RunOutcome> completion = new CompletableFuture<>();
            @Override public RunId id() { return id; }
            @Override public Flux<RunEventEnvelope> events(long sequence) { return Flux.empty(); }
            @Override public java.util.concurrent.CompletionStage<RunOutcome> completion() { return completion; }
        };
        private int starts, resumes, cancels;
        private boolean uncertain;
        private boolean managed = true, hasActive = true;
        private RunState state = RunState.PAUSED;
        private ResumeCommand command;
        @Override public boolean supportsManagedTurns() { return managed; }
        @Override public Optional<RunSnapshot> activeTurn(RunScope scope) { return hasActive ? Optional.of(get(id)) : Optional.empty(); }
        @Override public RunHandle start(RunRequest request) { starts++; return handle; }
        @Override public RunHandle resume(RunId runId, ResumeCommand value) {
            resumes++; command = value; return handle;
        }
        @Override public boolean cancel(RunId runId, CancelReason reason) { cancels++; return true; }
        @Override public RunSnapshot get(RunId runId) {
            return new RunSnapshot(id, state, "plan", 2, Instant.now(), Instant.now(),
                    uncertain ? JsonNodeFactory.instance.objectNode().put("kind", "tool.recovery_required") : null,
                    null, 1);
        }
    }
}
