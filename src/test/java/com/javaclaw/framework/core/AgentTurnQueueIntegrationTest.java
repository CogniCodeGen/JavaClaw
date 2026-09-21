package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.extension.ExtensionManager;
import com.javaclaw.framework.spi.ExtensionContext;
import com.javaclaw.framework.spi.RunEventDraft;
import com.javaclaw.framework.store.*;
import com.javaclaw.platform.data.SchemaInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import static org.junit.jupiter.api.Assertions.*;

class AgentTurnQueueIntegrationTest {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @Test void cancellingQueuedTurnDoesNotStealTheActiveOwnerOrSkipEnqueueOrder() {
        try (Fixture fixture = new Fixture()) {
            Map<RunId, CompletableFuture<ReasoningResult>> work = new LinkedHashMap<>();
            try (AgentEngine engine = fixture.engine(request -> {
                var result = new CompletableFuture<ReasoningResult>(); work.put(request.runId(), result); return result;
            })) {
                RunHandle first = engine.start(fixture.request("first"));
                RunHandle skipped = engine.start(fixture.request("skipped"));
                RunHandle next = engine.start(fixture.request("next"));
                assertEquals(RunState.CREATED, engine.get(next.id()).state());
                assertTrue(engine.cancel(skipped.id(), new CancelReason("TEST", "queued")));
                assertEquals(List.of(first.id()), List.copyOf(work.keySet()));
                assertEquals(first.id().value(), fixture.owner());
                work.get(first.id()).complete(completed());
                assertEquals(List.of(first.id(), next.id()), List.copyOf(work.keySet()));
                work.get(next.id()).complete(completed());
                assertNull(fixture.owner());
            }
        }
    }

    @Test void cancellationKeepsThreadOccupiedUntilTheOldExecutionActuallyExits() {
        try (Fixture fixture = new Fixture()) {
            var pending = new ArrayList<CompletableFuture<ReasoningResult>>();
            try (AgentEngine engine = fixture.engine(request -> {
                var result = new CompletableFuture<ReasoningResult>(); pending.add(result); return result;
            })) {
                var first = engine.start(fixture.request("first"));
                var next = engine.start(fixture.request("next"));
                engine.cancel(first.id(), new CancelReason("TEST", "cancel running"));
                assertEquals(RunState.CREATED, engine.get(next.id()).state());
                assertEquals(1, pending.size());
                pending.getFirst().complete(completed());
                assertEquals(RunState.RUNNING, engine.get(next.id()).state());
                assertEquals(2, pending.size());
                pending.getLast().complete(completed());
            }
        }
    }

    @Test void managedCancellationRequiresDriverExitAndCompletionTransfersEvidence() {
        try (Fixture fixture = new Fixture(); AgentEngine engine = fixture.engine(request -> {
            throw new AssertionError("managed turns cannot execute the reasoning driver");
        })) {
            ManagedTurn first = engine.beginTurn(fixture.request("first"));
            ManagedTurn next = engine.beginTurn(fixture.request("next"));
            assertTrue(first.ready().toCompletableFuture().isDone());
            assertFalse(next.ready().toCompletableFuture().isDone());
            engine.cancel(first.id(), new CancelReason("TEST", "managed"));
            assertFalse(next.ready().toCompletableFuture().isDone());
            first.close();
            assertTrue(next.ready().toCompletableFuture().isDone());
            next.emit("core.step.started", JSON.objectNode().put("stepId", "evidence").put("kind", "ORCHESTRATION")
                    .set("input", JSON.objectNode().put("task", "check")));
            next.emit("core.step.completed", JSON.objectNode().put("stepId", "evidence")
                    .set("output", JSON.objectNode().put("verified", true)));
            var output = JSON.objectNode().put("text", "done");
            output.putArray("facts").add("explicit fact");
            next.complete(output);
            var terminal = fixture.runs.eventsAfter(next.id(), 0).stream()
                    .filter(event -> event.type().equals("core.run.completed")).findFirst().orElseThrow().payload();
            assertEquals(output, terminal.path("output"));
            assertEquals("explicit fact", terminal.path("turnResult").path("facts").get(0).asText());
            assertEquals("evidence", terminal.path("turnResult").path("sourceSteps").get(0).asText());
            assertEquals(0, terminal.path("turnResult").path("decisions").size());
            assertNull(fixture.owner());
        }
    }

    @Test void shutdownPreservesQueueAndManagedHeadResumesWithANewReadySignal() {
        try (Fixture fixture = new Fixture()) {
            List<Runnable> submitted = new ArrayList<>();
            AgentEngine before = fixture.engine(request -> { throw new AssertionError(); }, submitted::add);
            ManagedTurn first = before.beginTurn(fixture.request("first"));
            ManagedTurn next = before.beginTurn(fixture.request("next"));
            assertEquals(RunState.CREATED, before.get(first.id()).state());
            before.close();
            assertTrue(first.ready().toCompletableFuture().isCompletedExceptionally());
            assertTrue(next.ready().toCompletableFuture().isCompletedExceptionally());
            try (AgentEngine after = fixture.engine(request -> { throw new AssertionError(); })) {
                assertEquals(RunState.PAUSED, after.get(first.id()).state());
                assertEquals(RunState.CREATED, after.get(next.id()).state());
                var resumed = after.beginTurn(fixture.request("first"));
                var queued = after.beginTurn(fixture.request("next"));
                assertTrue(resumed.ready().toCompletableFuture().isDone());
                assertFalse(queued.ready().toCompletableFuture().isDone());
                submitted.forEach(Runnable::run); // Detached old executor must not mutate the new process state.
                assertEquals(RunState.RUNNING, after.get(first.id()).state());
                resumed.complete(JSON.objectNode().put("text", "first done"));
                assertTrue(queued.ready().toCompletableFuture().isDone());
                queued.complete(JSON.objectNode().put("text", "next done"));
                assertNull(fixture.owner());
            }
            first.close(); next.close();
        }
    }

    @Test void startupRepairsTerminalOwnerLeftBetweenCommitAndCleanup() {
        try (Fixture fixture = new Fixture()) {
            var unfinished = new CompletableFuture<ReasoningResult>();
            AgentEngine before = fixture.engine(request -> unfinished);
            var first = before.start(fixture.request("first"));
            var next = before.start(fixture.request("next"));
            fixture.runs.append(first.id(), Set.of(RunState.RUNNING), RunState.COMPLETED,
                    new RunEventDraft("core.run.completed", 1, "test", null, null, JSON.objectNode()), JSON.objectNode(), null);
            assertEquals(first.id().value(), fixture.owner());
            before.close();
            try (AgentEngine after = fixture.engine(request -> CompletableFuture.completedFuture(completed()))) {
                assertEquals(next.id().value(), fixture.owner());
                assertEquals(RunState.PAUSED, after.get(next.id()).state());
                after.resume(next.id(), new ResumeCommand("continue", JSON.objectNode()));
                assertEquals(RunState.COMPLETED, after.get(next.id()).state());
                assertNull(fixture.owner());
            }
            unfinished.complete(completed());
        }
    }

    @Test void idempotencyIsIsolatedByWorkspaceUserAndThread() {
        try (Fixture fixture = new Fixture(); AgentEngine engine = fixture.engine(request -> CompletableFuture.completedFuture(completed()))) {
            var first = engine.start(fixture.request("same-key"));
            var otherThread = engine.start(fixture.request("same-key", new RunScope("workspace", "user", "another")));
            var otherUser = engine.start(fixture.request("same-key", new RunScope("workspace", "another-user", "session")));
            assertNotEquals(first.id(), otherThread.id());
            assertNotEquals(first.id(), otherUser.id());
            assertEquals(first.id(), engine.start(fixture.request("same-key")).id());
            assertEquals(otherUser.id(), engine.start(fixture.request("same-key", new RunScope("workspace", "another-user", "session"))).id());
        }
    }

    @Test void parentCompletionCancelsAttachedChildrenButKeepsDetachedAndLaterMaintenance() {
        try (Fixture fixture = new Fixture()) {
            Map<RunId, CompletableFuture<ReasoningResult>> pending = new LinkedHashMap<>();
            try (AgentEngine engine = fixture.engine(request -> {
                var future = new CompletableFuture<ReasoningResult>(); pending.put(request.runId(), future); return future;
            })) {
                var parent = engine.start(fixture.request("parent"));
                var attached = engine.start(fixture.child(parent.id(), "attached", false));
                var detached = engine.start(fixture.child(parent.id(), "detached", true));
                var maintenance = new java.util.concurrent.atomic.AtomicReference<RunHandle>();
                parent.events(0).filter(event -> event.type().equals("core.run.completed")).subscribe(event ->
                        maintenance.set(engine.start(fixture.child(parent.id(), "maintenance", false))));
                pending.get(parent.id()).complete(completed());
                assertEquals(RunState.CANCELLED, engine.get(attached.id()).state());
                assertEquals(RunState.RUNNING, engine.get(detached.id()).state());
                assertNotNull(maintenance.get());
                assertEquals(RunState.RUNNING, engine.get(maintenance.get().id()).state());
                List.copyOf(pending.values()).forEach(value -> value.complete(completed()));
            }
        }
    }

    @Test void restartRetainsCompletedChildChargesOnceAndMaintenanceHasAnIndependentBudget() {
        try (Fixture fixture = new Fixture()) {
            RunUsageLedger original = new RunUsageLedger();
            RunId parentId, childId, maintenanceId;
            var parentRequest = fixture.request("budget-parent");
            try (AgentEngine engine = fixture.engine(request -> { throw new AssertionError(); }, Runnable::run, original)) {
                ManagedTurn parent = engine.beginTurn(parentRequest);
                parentId = parent.id();
                ManagedTurn child = engine.beginTurn(fixture.child(parentId, "budget-child", false));
                childId = child.id();
                child.emit("core.step.started", JSON.objectNode().put("stepId", "physical-call").put("kind", "MODEL"));
                child.emit("core.step.completed", JSON.objectNode().put("stepId", "physical-call")
                        .set("usage", JSON.objectNode().put("inputTokens", 6).put("outputTokens", 2).put("estimatedCostCny", 1)));
                // Compatibility usage and step evidence describe the same provider charge.
                child.emit("core.model.usage", JSON.objectNode().put("inputTokens", 6).put("outputTokens", 2).put("estimatedCostCny", 1));
                original.record(childId, 6, 2, java.math.BigDecimal.ONE);
                child.complete(JSON.objectNode());
                ManagedTurn maintenance = engine.beginTurn(fixture.child(parentId, "maintenance", true));
                maintenanceId = maintenance.id();
                maintenance.emit("core.model_task.usage", JSON.objectNode().put("inputTokens", 9).put("outputTokens", 1));
                original.record(maintenanceId, 9, 1, java.math.BigDecimal.ZERO);
                maintenance.complete(JSON.objectNode());
                assertEquals(6, original.aggregateSnapshot(parentId).inputTokens());
                parent.close();
            }
            RunUsageLedger recovered = new RunUsageLedger();
            try (AgentEngine engine = fixture.engine(request -> { throw new AssertionError(); }, Runnable::run, recovered)) {
                assertEquals(0, recovered.snapshot(parentId).inputTokens());
                assertEquals(6, recovered.snapshot(childId).inputTokens());
                assertEquals(9, recovered.snapshot(maintenanceId).inputTokens());
                assertEquals(6, recovered.aggregateSnapshot(parentId).inputTokens());
                new RunUsageRecovery(fixture.runs, fixture.plans, fixture.json, recovered).restore(parentId);
                assertEquals(6, recovered.aggregateSnapshot(parentId).inputTokens());
                ManagedTurn parent = engine.beginTurn(parentRequest);
                parent.complete(JSON.objectNode());
                var handoff = fixture.runs.eventsAfter(parentId, 0).getLast().payload().path("turnResult");
                assertEquals(0, handoff.path("directUsage").path("inputTokens").asLong());
                assertEquals(6, handoff.path("aggregateUsage").path("inputTokens").asLong());
            }
        }
    }

    @Test void tombstonedThreadRejectsPublicTurnAndStepReadsButStillAllowsCancellation() {
        try (Fixture fixture = new Fixture(); AgentEngine engine = fixture.engine(request -> new CompletableFuture<>())) {
            ManagedTurn turn = engine.beginTurn(fixture.request("delete"));
            turn.emit("core.step.started", JSON.objectNode().put("stepId", "private-input").put("kind", "MODEL"));
            var stepClient = new RunStepQuery(fixture.runs);
            assertEquals(1, stepClient.steps(turn.id()).size());
            fixture.runs.threads().markDeleting(new RunScope("workspace", "user", "session"));
            assertThrows(NoSuchElementException.class, () -> engine.get(turn.id()));
            assertThrows(NoSuchElementException.class, () -> stepClient.steps(turn.id()));
            assertThrows(NoSuchElementException.class, () -> turn.events(0).collectList().block());
            assertTrue(engine.activeTurn(new RunScope("workspace", "user", "session")).isEmpty());
            assertTrue(engine.cancel(turn.id(), new CancelReason("THREAD_DELETED", "test")));
            turn.close();
            assertEquals(RunState.CANCELLED, fixture.runs.find(turn.id()).orElseThrow().snapshot().state());
        }
    }

    @Test void terminalStepSettlementPreservesStateAndRejectsNewDuplicateOrDeletedWork() {
        try (Fixture fixture = new Fixture(); AgentEngine engine = fixture.engine(request -> new CompletableFuture<>())) {
            ManagedTurn turn = engine.beginTurn(fixture.request("late-step"));
            turn.emit("core.step.started", JSON.objectNode().put("stepId", "finished-late").put("kind", "MODEL"));
            turn.emit("core.step.started", JSON.objectNode().put("stepId", "deleted-late").put("kind", "MODEL_TASK"));
            assertTrue(engine.cancel(turn.id(), new CancelReason("TEST", "already cancelled")));
            var cancelled = fixture.runs.find(turn.id()).orElseThrow().snapshot();
            var completion = new RunEventDraft("core.step.completed", 1, "test", null, null,
                    JSON.objectNode().put("stepId", "finished-late").set("usage", JSON.objectNode().put("inputTokens", 4)));
            assertTrue(fixture.runs.settleStep(turn.id(), completion).isPresent());
            assertTrue(fixture.runs.settleStep(turn.id(), completion).isEmpty());
            assertTrue(fixture.runs.settleStep(turn.id(), new RunEventDraft("core.step.failed", 1, "test", null, null,
                    JSON.objectNode().put("stepId", "finished-late"))).isEmpty());
            assertTrue(fixture.runs.settleStep(turn.id(), new RunEventDraft("core.step.completed", 1, "test", null, null,
                    JSON.objectNode().put("stepId", "never-started"))).isEmpty());
            assertTrue(fixture.runs.settleStep(turn.id(), new RunEventDraft("core.step.started", 1, "test", null, null,
                    JSON.objectNode().put("stepId", "new-work"))).isEmpty());
            var settled = fixture.runs.find(turn.id()).orElseThrow().snapshot();
            assertEquals(cancelled.state(), settled.state());
            assertEquals(cancelled.output(), settled.output());
            assertEquals(cancelled.error(), settled.error());
            assertEquals(cancelled.lastSequence() + 1, settled.lastSequence());
            assertEquals(AgentStep.State.COMPLETED, new RunStepQuery(fixture.runs).steps(turn.id()).getFirst().state());
            fixture.runs.threads().markDeleting(new RunScope("workspace", "user", "session"));
            assertTrue(fixture.runs.settleStep(turn.id(), new RunEventDraft("core.step.completed", 1, "test", null, null,
                    JSON.objectNode().put("stepId", "deleted-late"))).isEmpty());
            turn.close();
        }
    }

    private static ReasoningResult completed() { return ReasoningResult.completed(JSON.objectNode().put("text", "done")); }

    private static final class Fixture implements AutoCloseable {
        private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        // Equal creation milliseconds exercise durable FIFO ordering, rather than UUID sorting.
        private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        private final JdbcTemplate jdbc;
        private final JdbcRunStore runs;
        private final JdbcExecutionPlanStore plans;
        private final JdbcAgentDefinitionStore definitions;
        private final ExtensionManager extensions;
        Fixture() {
            var source = new DriverManagerDataSource("jdbc:h2:mem:turn-queue-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
            new SchemaInitializer(source).initialize();
            jdbc = new JdbcTemplate(source);
            var transactions = new DataSourceTransactionManager(source);
            runs = new JdbcRunStore(jdbc, transactions, json, clock);
            plans = new JdbcExecutionPlanStore(jdbc, json, clock);
            definitions = new JdbcAgentDefinitionStore(jdbc, transactions, json, clock);
            var agent = new AgentDefinitionDraft("test.agent", "Agent", "test:model", Map.of(), Map.of(),
                    JSON.objectNode(), JSON.objectNode(), RunBudget.UNBOUNDED, JSON.objectNode(), Map.of());
            definitions.saveAgentDraft("workspace", agent, false); definitions.publishAgent("workspace", agent.id());
            var profile = new RunProfileDraft("test.profile", "Profile", PermissionSet.UNRESTRICTED,
                    RunBudget.UNBOUNDED, Map.of(), JSON.objectNode());
            definitions.saveProfileDraft("workspace", profile, false); definitions.publishProfile("workspace", profile.id());
            extensions = new ExtensionManager(new ExtensionContext(clock, Runnable::run,
                    request -> CompletableFuture.failedFuture(new AssertionError("no auxiliary model"))));
            extensions.publish(List.of());
        }
        RunRequest request(String key) { return request(key, new RunScope("workspace", "user", "session")); }
        RunRequest request(String key, RunScope scope) {
            return RunRequest.builder().agent(AgentDefinitionRef.latest("test.agent"))
                    .profile(RunProfileRef.latest("test.profile")).source(InvocationSource.chat()).scope(scope)
                    .input(InputBlock.text(key)).permissionCeiling(PermissionSet.UNRESTRICTED).idempotencyKey(key).build();
        }
        RunRequest child(RunId parent, String name, boolean detached) {
            RunRequest request = request(name, new RunScope("workspace", "user", name));
            return new RunRequest(request.agent(), request.profile(), new InvocationSource(name.equals("maintenance")
                    ? "maintenance" : "subagent", name), request.scope(), request.inputs(), new RunLinkage(parent, null, name),
                    request.permissionCeiling(), request.budget(), request.idempotencyKey(),
                    Map.of("framework.detached", JSON.booleanNode(detached)));
        }
        AgentEngine engine(ReasoningGateway reasoning) { return engine(reasoning, Runnable::run); }
        AgentEngine engine(ReasoningGateway reasoning, Executor executor) {
            return engine(reasoning, executor, new RunUsageLedger());
        }
        AgentEngine engine(ReasoningGateway reasoning, Executor executor, RunUsageLedger ledger) {
            return new AgentEngine(new AgentCompiler(definitions, extensions, json), runs, plans, reasoning,
                    executor, json, clock, ledger);
        }
        String owner() { return jdbc.queryForObject("SELECT active_turn_id FROM agent_threads WHERE workspace_id='workspace' "
                + "AND user_id='user' AND thread_id='session'", String.class); }
        @Override public void close() { extensions.close(); }
    }
}
