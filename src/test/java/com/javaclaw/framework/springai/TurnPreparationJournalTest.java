package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunProfileRef;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.api.RunSnapshot;
import com.javaclaw.framework.api.RunState;
import com.javaclaw.framework.core.ReasoningEventSink;
import com.javaclaw.framework.core.ToolRecoveryRequiredException;
import com.javaclaw.framework.spi.CreateRunResult;
import com.javaclaw.framework.spi.ExtensionStateView;
import com.javaclaw.framework.spi.RunEventDraft;
import com.javaclaw.framework.spi.RunStore;
import com.javaclaw.framework.spi.StoredRun;
import com.javaclaw.framework.spi.TurnPreparation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TurnPreparationJournalTest {
    @Test
    void completedPreparationIsReusedAfterResume() {
        Fixture fixture = new Fixture();
        AtomicInteger calls = new AtomicInteger();
        TurnPreparation preparation = fixture.preparation(() -> {
            calls.incrementAndGet();
            return "纠错背景";
        });

        assertEquals("纠错背景", fixture.prepare(preparation));
        assertEquals("纠错背景", fixture.prepare(preparation));
        assertEquals(1, calls.get());
        assertEquals(1, fixture.store.events.stream()
                .filter(event -> event.type().equals("core.step.started")).count());
        assertEquals(1, fixture.store.events.stream()
                .filter(event -> event.type().equals("core.step.completed")).count());
    }

    @Test
    void unknownOutcomePausesWithoutRepeatingSideEffect() {
        Fixture fixture = new Fixture();
        AtomicInteger calls = new AtomicInteger();
        TurnPreparation preparation = fixture.preparation(() -> {
            calls.incrementAndGet();
            throw new AssertionError("simulated process crash");
        });

        assertThrows(AssertionError.class, () -> fixture.prepare(preparation));
        assertThrows(ToolRecoveryRequiredException.class, () -> fixture.prepare(preparation));
        assertEquals(1, calls.get());
    }

    @Test
    void redactedResultPausesInsteadOfReplayingIncompletePrompt() {
        Fixture fixture = new Fixture();
        fixture.store.redactCompletion = true;
        AtomicInteger calls = new AtomicInteger();
        TurnPreparation preparation = fixture.preparation(() -> {
            calls.incrementAndGet();
            return "sensitive instruction";
        });

        assertThrows(ToolRecoveryRequiredException.class, () -> fixture.prepare(preparation));
        assertThrows(ToolRecoveryRequiredException.class, () -> fixture.prepare(preparation));
        assertEquals(1, calls.get());
    }

    private static final class Fixture {
        private final RunId runId = RunId.random();
        private final RunRequest request = RunRequest.builder()
                .agent(AgentDefinitionRef.latest("test"))
                .profile(RunProfileRef.latest("chat"))
                .source(InvocationSource.chat())
                .scope(new RunScope("workspace", "user", "thread"))
                .input(InputBlock.text("hello"))
                .build();
        private final FakeRunStore store = new FakeRunStore(runId, request);
        private final ExtensionStateView state = new ExtensionStateView() {
            @Override public RunId runId() { return runId; }
            @Override public Optional<JsonNode> get(String extensionId, String key) {
                return Optional.empty();
            }
        };

        private String prepare(TurnPreparation value) {
            return TurnPreparationJournal.prepare(runId, request, store.eventsSink(),
                    List.of(value), store, state);
        }

        private TurnPreparation preparation(java.util.function.Supplier<String> action) {
            return new TurnPreparation() {
                @Override public String id() { return "memory.correction"; }
                @Override public String prepare(RunRequest request, ExtensionStateView state) {
                    return action.get();
                }
            };
        }
    }

    private static final class FakeRunStore implements RunStore {
        private final RunId runId;
        private final RunRequest request;
        private final List<RunEventEnvelope> events = new ArrayList<>();
        private boolean redactCompletion;

        private FakeRunStore(RunId runId, RunRequest request) {
            this.runId = runId;
            this.request = request;
        }

        private ReasoningEventSink eventsSink() {
            return (type, schemaVersion, producer, payload) -> {
                ObjectNode body = (ObjectNode) payload.deepCopy();
                if (redactCompletion && type.equals("core.step.completed")) {
                    body.put("credentialRedacted", true);
                }
                events.add(new RunEventEnvelope(runId.value(), events.size() + 1,
                        Instant.now(), type, schemaVersion, producer, null, null, body));
            };
        }

        @Override public Optional<StoredRun> find(RunId id) {
            if (!runId.equals(id)) return Optional.empty();
            RunSnapshot snapshot = new RunSnapshot(id, RunState.RUNNING, "plan", events.size(),
                    Instant.now(), Instant.now(), null, null, 0);
            return Optional.of(new StoredRun(snapshot, request));
        }

        @Override public List<RunEventEnvelope> eventsAfter(RunId id, long afterSequence) {
            return events.stream().filter(event -> event.sequence() > afterSequence).toList();
        }

        @Override public CreateRunResult create(RunId id, RunRequest request,
                String executionPlanId, RunEventDraft createdEvent) {
            throw new UnsupportedOperationException();
        }

        @Override public Optional<StoredRun> findByIdempotencyKey(String workspaceId, String key) {
            return Optional.empty();
        }

        @Override public List<StoredRun> nonTerminalRuns() { return List.of(); }

        @Override public Optional<RunEventEnvelope> append(RunId id, Set<RunState> expectedStates,
                RunState nextState, RunEventDraft event, JsonNode output, String error) {
            throw new UnsupportedOperationException();
        }
    }
}
