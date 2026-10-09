package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.AgentDefinitionDraft;
import com.javaclaw.framework.api.BudgetExceededException;
import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.CancelReason;
import com.javaclaw.framework.api.CapabilityMetadata;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.ResumeCommand;
import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.RunHandle;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunProfileDraft;
import com.javaclaw.framework.api.RunProfileRef;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.api.RunState;
import com.javaclaw.framework.api.RunLinkage;
import com.javaclaw.framework.api.ModelDecisionV1;
import com.javaclaw.framework.api.TaskContractV3;
import com.javaclaw.framework.api.TaskCriterionV3;
import com.javaclaw.framework.api.TaskOutcome;
import com.javaclaw.framework.extension.ExtensionArtifact;
import com.javaclaw.framework.extension.ExtensionManager;
import com.javaclaw.framework.spi.AgentFrameworkExtension;
import com.javaclaw.framework.spi.ExtensionContext;
import com.javaclaw.framework.spi.ExtensionDescriptor;
import com.javaclaw.framework.spi.EventCodec;
import com.javaclaw.framework.spi.EventTypeDescriptor;
import com.javaclaw.framework.spi.ExtensionRegistrar;
import com.javaclaw.framework.spi.ExtensionScope;
import com.javaclaw.framework.spi.HotUpdateCompatibility;
import com.javaclaw.framework.spi.SemanticVersion;
import com.javaclaw.framework.spi.ModelTaskGateway;
import com.javaclaw.framework.spi.RunEventDraft;
import com.javaclaw.framework.spi.EffectReconciliationV1;
import com.javaclaw.framework.spi.EffectReceiptV1;
import com.javaclaw.framework.spi.ToolEffectPolicy;
import com.javaclaw.framework.spi.InteractionEventSource;
import com.javaclaw.util.ProjectAccessPolicy;
import com.javaclaw.framework.store.JdbcAgentDefinitionStore;
import com.javaclaw.framework.store.JdbcExecutionPlanStore;
import com.javaclaw.framework.store.JdbcRunStore;
import com.javaclaw.platform.data.SchemaInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEngineIntegrationTest {

    @Test
    void boundedEventTimeoutSettlesItsReservedToolAndResumesExactlyOnce() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        AtomicInteger reasoningCalls = new AtomicInteger();
        ControlledInteractionEvents source = new ControlledInteractionEvents();
        ObjectNode wait = eventWait("BOUNDED", 100);
        try (ExtensionManager extensions = fixture.extensionManager();
             AgentEngine engine = fixture.engine(extensions, eventWaitReasoning(reasoningCalls, wait), source)) {
            RunHandle handle = engine.start(fixture.request("bounded-event-timeout"));
            PendingInteractionEvent pending = source.next();
            assertEquals(RunState.WAITING_EVENT, engine.get(handle.id()).state());

            // An event source may return an early timeout; it must not shorten the requested wait.
            pending.result().complete(eventTimeout(pending.lease()));
            assertEquals(RunState.WAITING_EVENT, engine.get(handle.id()).state());
            assertEquals(1, reasoningCalls.get());

            assertEquals(RunState.COMPLETED,
                    handle.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - pending.startedAtNanos()) >= 95,
                    "an early source timeout must wait for the requested subscription boundary");
            assertEquals(2, reasoningCalls.get());
            assertEquals(1, source.subscriptions.get(), "a bounded timeout never silently renews");
            assertEventWaitSettledOnce(fixture, handle.id(), "WAIT_TIMEOUT");
            var step = new RunStepQuery(fixture.runs).steps(handle.id()).stream()
                    .filter(value -> value.id().equals(com.javaclaw.framework.api.StepId.tool(
                            handle.id(), wait.path("invocationId").asText())))
                    .findFirst().orElseThrow();
            assertEquals(com.javaclaw.framework.api.AgentStep.State.COMPLETED, step.state());
        }
    }

    @Test
    void boundedEventWaitExpiresEvenWhenTheHostSubscriptionNeverCompletes() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        AtomicInteger calls = new AtomicInteger();
        ControlledInteractionEvents source = new ControlledInteractionEvents();
        try (ExtensionManager extensions = fixture.extensionManager();
             AgentEngine engine = fixture.engine(extensions,
                     eventWaitReasoning(calls, eventWait("BOUNDED", 150)), source)) {
            RunHandle handle = engine.start(fixture.request("bounded-unresponsive-event-source"));
            PendingInteractionEvent pending = source.next();
            assertEquals(RunState.WAITING_EVENT, engine.get(handle.id()).state());

            assertEquals(RunState.COMPLETED, handle.completion().toCompletableFuture()
                    .get(2, TimeUnit.SECONDS).state());
            assertTrue(pending.result().isCancelled(), "the host-owned deadline closes the stale subscription");
            assertFalse(pending.result().complete(JsonNodeFactory.instance.objectNode().put("event", "FRAME_AVAILABLE")));
            assertEquals(2, calls.get(), "the expired wait starts only one continuation");
            assertEquals(1, source.subscriptions.get());
            assertEventWaitSettledOnce(fixture, handle.id(), "WAIT_TIMEOUT");
        }
    }

    @Test
    void persistedBoundedEventDeadlineDoesNotRestartTheOriginalTimeout() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        AtomicInteger calls = new AtomicInteger();
        ControlledInteractionEvents source = new ControlledInteractionEvents();
        ObjectNode wait = eventWait("BOUNDED", 30_000);
        try (ExtensionManager extensions = fixture.extensionManager();
             AgentEngine engine = fixture.engine(extensions, eventWaitReasoning(calls, wait), source)) {
            wait.put("waitDeadline", Instant.now().plusMillis(200).toString());
            RunHandle handle = engine.start(fixture.request("persisted-bounded-event-deadline"));
            PendingInteractionEvent pending = source.next();
            assertTrue(pending.lease().path("timeoutMillis").asLong() <= 200,
                    "reattaching a persisted wait must use its remaining absolute deadline");
            assertEquals(wait.path("waitDeadline"), pending.lease().path("waitDeadline"));

            assertEquals(RunState.COMPLETED, handle.completion().toCompletableFuture()
                    .get(2, TimeUnit.SECONDS).state());
            assertTrue(pending.result().isCancelled());
            assertEquals(2, calls.get());
            assertEquals(1, source.subscriptions.get());
            assertEventWaitSettledOnce(fixture, handle.id(), "WAIT_TIMEOUT");
        }
    }

    @Test
    void expiredPersistedEventDeadlineSettlesWithoutOpeningAnotherSubscription() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        AtomicInteger calls = new AtomicInteger();
        ControlledInteractionEvents source = new ControlledInteractionEvents();
        ObjectNode wait = eventWait("BOUNDED", 30_000)
                .put("waitDeadline", Instant.now().minusSeconds(1).toString());
        try (ExtensionManager extensions = fixture.extensionManager();
             AgentEngine engine = fixture.engine(extensions, eventWaitReasoning(calls, wait), source)) {
            RunHandle handle = engine.start(fixture.request("expired-persisted-event-deadline"));
            assertEquals(RunState.COMPLETED, handle.completion().toCompletableFuture()
                    .get(2, TimeUnit.SECONDS).state());
            assertEquals(0, source.subscriptions.get(), "an expired wait must not subscribe beyond its deadline");
            assertEquals(2, calls.get());
            assertEventWaitSettledOnce(fixture, handle.id(), "WAIT_TIMEOUT");
        }
    }

    @Test
    void boundedTimerKeepsAnArrivedFrameWhenItsSourceCallbackIsStillQueued() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        AtomicInteger calls = new AtomicInteger();
        ControlledInteractionEvents source = new ControlledInteractionEvents();
        AtomicBoolean holdCallbacks = new AtomicBoolean();
        java.util.concurrent.BlockingQueue<Runnable> queued = new java.util.concurrent.LinkedBlockingQueue<>();
        Executor executor = command -> {
            if (holdCallbacks.get()) queued.add(command);
            else command.run();
        };
        try (ExtensionManager extensions = fixture.extensionManager();
             AgentEngine engine = fixture.engine(extensions,
                     eventWaitReasoning(calls, eventWait("BOUNDED", 200)), executor, source)) {
            RunHandle handle = engine.start(fixture.request("frame-before-queued-callback"));
            PendingInteractionEvent pending = source.next();
            CompletableFuture<JsonNode> settled = new CompletableFuture<>();
            var subscription = handle.events(0).filter(event -> event.type().equals("core.tool.completed"))
                    .subscribe(event -> settled.complete(event.payload().path("output")));
            try {
                holdCallbacks.set(true);
                pending.result().complete(JsonNodeFactory.instance.objectNode().put("event", "FRAME_AVAILABLE")
                        .put("sessionId", pending.lease().path("sessionId").asText())
                        .put("capturedAtMillis", pending.lease().path("afterCapturedAtMillis").asLong() + 1));
                assertFalse(queued.isEmpty(), "the event-source completion must remain queued for this race");

                assertEquals("FRAME_AVAILABLE", settled.get(2, TimeUnit.SECONDS).path("event").asText(),
                        "the host timer must preserve the already-arrived frame instead of synthesizing a timeout");
                assertEquals(1, calls.get(), "the continuation stays queued until the executor is drained");
                var completion = handle.completion().toCompletableFuture();
                while (!completion.isDone()) {
                    Runnable callback = queued.poll(2, TimeUnit.SECONDS);
                    assertNotNull(callback, "the queued continuation must remain runnable");
                    callback.run();
                }
                assertEquals(RunState.COMPLETED, completion.get(2, TimeUnit.SECONDS).state());
                assertEquals(2, calls.get());
                assertEquals(1, source.subscriptions.get());
                assertEventWaitSettledOnce(fixture, handle.id(), "FRAME_AVAILABLE");
            } finally {
                holdCallbacks.set(false);
                Runnable callback;
                while ((callback = queued.poll()) != null) callback.run();
                subscription.dispose();
            }
        }
    }

    @Test
    void watchAndLegacyEventTimeoutsRenewTheSameWaitUntilARealFrameArrives() throws Exception {
        List<Fixture> fixtures = new java.util.ArrayList<>();
        List<ExtensionManager> extensions = new java.util.ArrayList<>();
        List<AgentEngine> engines = new java.util.ArrayList<>();
        List<ControlledInteractionEvents> sources = new java.util.ArrayList<>();
        List<AtomicInteger> reasoningCalls = new java.util.ArrayList<>();
        List<RunHandle> handles = new java.util.ArrayList<>();
        List<PendingInteractionEvent> initial = new java.util.ArrayList<>();
        try {
            // Run both modes concurrently so the renewal guard costs only one subscription interval.
            for (String mode : List.of("UNTIL_CHANGE", "LEGACY")) {
                Fixture fixture = new Fixture();
                fixture.publishDefinition(Map.of());
                fixtures.add(fixture);
                ExtensionManager extension = fixture.extensionManager();
                extensions.add(extension);
                ControlledInteractionEvents source = new ControlledInteractionEvents();
                sources.add(source);
                AtomicInteger calls = new AtomicInteger();
                reasoningCalls.add(calls);
                engines.add(fixture.engine(extension,
                        eventWaitReasoning(calls, eventWait(mode.equals("LEGACY") ? null : mode, 20)), source));
            }
            for (int index = 0; index < engines.size(); index++) {
                handles.add(engines.get(index).start(fixtures.get(index).request("watch-event-timeout")));
                PendingInteractionEvent pending = sources.get(index).next();
                initial.add(pending);
                pending.result().complete(eventTimeout(pending.lease()));
                assertEquals(RunState.WAITING_EVENT, engines.get(index).get(handles.get(index).id()).state());
                assertEquals(1, reasoningCalls.get(index).get());
                assertEquals(0, eventCount(fixtures.get(index), handles.get(index).id(), "core.run.resumed"));
            }
            for (int index = 0; index < engines.size(); index++) {
                PendingInteractionEvent renewed = sources.get(index).next();
                assertEquals(initial.get(index).lease(), renewed.lease(),
                        "renewal preserves invocation, observation baseline and wait mode");
                assertEquals(1, reasoningCalls.get(index).get(), "subscription renewal is not a model turn");
                renewed.result().complete(JsonNodeFactory.instance.objectNode().put("event", "FRAME_AVAILABLE")
                        .put("sessionId", renewed.lease().path("sessionId").asText())
                        .put("capturedAtMillis", renewed.lease().path("afterCapturedAtMillis").asLong() + 1));
                assertEquals(RunState.COMPLETED, handles.get(index).completion().toCompletableFuture()
                        .get(2, TimeUnit.SECONDS).state());
                assertEquals(2, reasoningCalls.get(index).get());
                assertEquals(2, sources.get(index).subscriptions.get());
                assertEventWaitSettledOnce(fixtures.get(index), handles.get(index).id(), "FRAME_AVAILABLE");
            }
        } finally {
            for (AgentEngine engine : engines) engine.close();
            for (ExtensionManager extension : extensions) extension.close();
        }
    }

    @Test
    void mismatchedEventTimeoutFailsClosedWithoutResumingReasoning() throws Exception {
        for (String mismatch : List.of("session", "duration", "duration-type")) {
            Fixture fixture = new Fixture();
            fixture.publishDefinition(Map.of());
            AtomicInteger calls = new AtomicInteger();
            ControlledInteractionEvents source = new ControlledInteractionEvents();
            try (ExtensionManager extensions = fixture.extensionManager();
                 AgentEngine engine = fixture.engine(extensions,
                         eventWaitReasoning(calls, eventWait("BOUNDED", 1_000)), source)) {
                RunHandle handle = engine.start(fixture.request("invalid-event-timeout-" + mismatch));
                PendingInteractionEvent pending = source.next();
                ObjectNode output = eventTimeout(pending.lease());
                if (mismatch.equals("session")) output.put("sessionId", "another-session");
                else if (mismatch.equals("duration"))
                    output.put("timeoutMillis", pending.lease().path("timeoutMillis").asLong() + 1);
                else output.put("timeoutMillis", "1000");
                pending.result().complete(output);
                var outcome = handle.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
                assertEquals(RunState.FAILED, outcome.state(), mismatch);
                assertTrue(outcome.error().contains("invalid subscription timeout"), outcome.error());
                assertEquals(1, calls.get());
                assertEquals(1, source.subscriptions.get());
                assertEquals(0, eventCount(fixture, handle.id(), "core.run.resumed"));
                assertEquals(0, eventCount(fixture, handle.id(), "core.tool.completed"));
            }
        }
    }

    @Test
    void cancellingEventWaitDisposesItsPendingSourceAndIgnoresLateFrames() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        AtomicInteger calls = new AtomicInteger();
        ControlledInteractionEvents source = new ControlledInteractionEvents();
        try (ExtensionManager extensions = fixture.extensionManager();
             AgentEngine engine = fixture.engine(extensions,
                     eventWaitReasoning(calls, eventWait("BOUNDED", 100)), source)) {
            RunHandle handle = engine.start(fixture.request("cancel-pending-event-wait"));
            PendingInteractionEvent pending = source.next();
            assertTrue(engine.cancel(handle.id(), new CancelReason("USER_CANCELLED", "stop waiting")));
            assertTrue(pending.result().isCancelled(), "cancellation closes the host subscription");
            assertFalse(pending.result().complete(JsonNodeFactory.instance.objectNode().put("event", "FRAME_AVAILABLE")));
            assertEquals(RunState.CANCELLED, handle.completion().toCompletableFuture()
                    .get(2, TimeUnit.SECONDS).state());
            assertEquals(1, calls.get());
            assertEquals(1, source.subscriptions.get());
            assertEquals(0, eventCount(fixture, handle.id(), "core.run.resumed"));
            assertEquals(0, eventCount(fixture, handle.id(), "core.tool.completed"));
        }
    }

    @Test
    void cancellingAnEarlyBoundedTimeoutPreventsItsScheduledDelivery() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        AtomicInteger calls = new AtomicInteger();
        ControlledInteractionEvents source = new ControlledInteractionEvents();
        try (ExtensionManager extensions = fixture.extensionManager();
             AgentEngine engine = fixture.engine(extensions,
                     eventWaitReasoning(calls, eventWait("BOUNDED", 100)), source)) {
            RunHandle handle = engine.start(fixture.request("cancel-scheduled-event-timeout"));
            PendingInteractionEvent pending = source.next();
            pending.result().complete(eventTimeout(pending.lease()));
            assertEquals(RunState.WAITING_EVENT, engine.get(handle.id()).state());
            assertTrue(engine.cancel(handle.id(), new CancelReason("USER_CANCELLED", "stop waiting")));
            CompletableFuture.runAsync(() -> { }, CompletableFuture.delayedExecutor(150, TimeUnit.MILLISECONDS))
                    .get(2, TimeUnit.SECONDS);
            assertEquals(RunState.CANCELLED, engine.get(handle.id()).state());
            assertEquals(1, calls.get());
            assertEquals(1, source.subscriptions.get());
            assertEquals(0, eventCount(fixture, handle.id(), "core.run.resumed"));
            assertEquals(0, eventCount(fixture, handle.id(), "core.tool.completed"));
        }
    }

    private static ObjectNode eventWait(String mode, long timeoutMillis) {
        ObjectNode wait = JsonNodeFactory.instance.objectNode().put("kind", "interaction.waiting_event")
                .put("invocationId", "fixture-event-wait").put("sessionId", "native-session")
                .put("afterCapturedAtMillis", 1234).put("timeoutMillis", timeoutMillis)
                .put("afterWindowGeneration", 3).put("afterContentRevision", 7);
        if (mode != null) wait.put("waitMode", mode);
        return wait;
    }

    private static ObjectNode eventTimeout(JsonNode wait) {
        return JsonNodeFactory.instance.objectNode().put("event", "WAIT_TIMEOUT")
                .put("sessionId", wait.path("sessionId").asText())
                .put("timeoutMillis", wait.path("timeoutMillis").asLong());
    }

    private static ReasoningGateway eventWaitReasoning(AtomicInteger calls, ObjectNode wait) {
        return request -> {
            if (calls.incrementAndGet() > 1) {
                assertNotNull(request.resumeCommand());
                assertEquals("interaction.event", request.resumeCommand().type());
                return CompletableFuture.completedFuture(ReasoningResult.completed(
                        JsonNodeFactory.instance.objectNode().put("text", "event received")));
            }
            String invocation = wait.path("invocationId").asText();
            var input = JsonNodeFactory.instance.objectNode().put("tool", "interaction_wait_event")
                    .put("invocationId", invocation);
            input.set("arguments", wait);
            var started = input.deepCopy().put("fingerprint", invocation).put("effectKey", invocation)
                    .put("effectPolicy", "LEGACY").put("resourceKey", "").put("idempotent", true);
            request.events().toolStarted(StepEvents.startedPayload(
                    com.javaclaw.framework.api.StepId.tool(request.runId(), invocation),
                    com.javaclaw.framework.api.AgentStep.Kind.TOOL, input, null), started);
            var suspended = JsonNodeFactory.instance.objectNode().put("tool", "interaction_wait_event")
                    .put("invocationId", invocation);
            suspended.set("wait", wait);
            request.events().emit("core.tool.suspended", 1, "framework.core", suspended);
            return CompletableFuture.completedFuture(new ReasoningResult(
                    RunState.WAITING_EVENT, wait, "INTERACTION_EVENT_PENDING"));
        };
    }

    private static long eventCount(Fixture fixture, RunId run, String type) {
        return fixture.runs.eventsAfter(run, 0).stream().filter(event -> event.type().equals(type)).count();
    }

    private static void assertEventWaitSettledOnce(Fixture fixture, RunId run, String eventName) {
        assertEquals(1, eventCount(fixture, run, "core.tool.started"));
        assertEquals(1, eventCount(fixture, run, "core.tool.completed"));
        assertEquals(1, eventCount(fixture, run, "core.interaction.event_received"));
        assertEquals(1, eventCount(fixture, run, "core.run.resumed"));
        var result = fixture.runs.eventsAfter(run, 0).stream()
                .filter(event -> event.type().equals("core.tool.completed")).findFirst().orElseThrow();
        assertEquals("fixture-event-wait", result.payload().path("invocationId").asText());
        assertEquals(eventName, result.payload().path("output").path("event").asText());
    }

    private static final class ControlledInteractionEvents implements InteractionEventSource {
        private final AtomicInteger subscriptions = new AtomicInteger();
        private final java.util.concurrent.BlockingQueue<PendingInteractionEvent> pending =
                new java.util.concurrent.LinkedBlockingQueue<>();

        @Override public java.util.concurrent.CompletionStage<JsonNode> await(RunRequest request,
                JsonNode wait, com.javaclaw.framework.spi.CancellationToken cancellation) {
            CompletableFuture<JsonNode> result = new CompletableFuture<>();
            subscriptions.incrementAndGet();
            pending.add(new PendingInteractionEvent(wait.deepCopy(), result, System.nanoTime()));
            return result;
        }

        private PendingInteractionEvent next() throws InterruptedException {
            PendingInteractionEvent value = pending.poll(2, TimeUnit.SECONDS);
            assertNotNull(value, "expected the host to create an event subscription");
            return value;
        }
    }

    private record PendingInteractionEvent(JsonNode lease, CompletableFuture<JsonNode> result,
                                           long startedAtNanos) { }

    @Test
    void unreliableContractPausesBeforeAnyReasoningOrBusinessAction() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        AtomicInteger calls = new AtomicInteger();
        TaskContractV3 contract = new TaskContractV3(3, "打开QQ查看联系人", List.of(
                new TaskCriterionV3("contacts", "查看联系人", "desktop.observe",
                        CapabilityMetadata.TargetKind.DESKTOP_APPLICATION, "QQ",
                        EffectReceiptV1.Status.OBSERVED, "联系人")), true, false, "definition",
                List.of("AMBIGUOUS_VIEW"), List.of("联系人分组还是具体联系人"));
        try (ExtensionManager extensions = fixture.extensionManager();
             AgentEngine engine = fixture.harnessEngine(extensions, request -> {
                 calls.incrementAndGet();
                 return CompletableFuture.completedFuture(ReasoningResult.completed(
                         JsonNodeFactory.instance.objectNode().put("text", "done")));
             })) {
            RunHandle handle = engine.start(fixture.request("unreliable-before-action")
                    .withAttribute(TaskContractCompiler.ATTRIBUTE, fixture.json.valueToTree(contract)));
            var snapshot = engine.get(handle.id());
            assertEquals(RunState.PAUSED, snapshot.state(), snapshot.error());
            assertEquals(0, calls.get());
            assertEquals("task.contract.unreliable", snapshot.output().path("kind").asText());
            assertTrue(snapshot.output().path("reasonCodes").toString().contains("AMBIGUOUS_VIEW"));
            assertEquals("TASK_CONTRACT_UNRELIABLE", engine.taskResult(handle.id()).orElseThrow().stopReason());
            assertTrue(fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .noneMatch(event -> event.type().equals("core.tool.started")));
        }
    }

    @Test
    void toolRequirementsReplaceCallerContextWithTheFrozenHostContract() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        TaskContractV3 contract = new TaskContractV3(3, "查看联系人", List.of(
                new TaskCriterionV3("contacts", "查看联系人", "desktop.observe",
                        CapabilityMetadata.TargetKind.DESKTOP_APPLICATION, "QQ",
                        EffectReceiptV1.Status.OBSERVED, "联系人")), true, true, "definition");
        var forged = JsonNodeFactory.instance.objectNode().put("version", 3)
                .put("reliable", true).put("applicable", true);
        forged.putArray("criteria").addObject().put("id", "forged")
                .put("capabilityId", "desktop.observe").put("targetType", "DESKTOP_APPLICATION")
                .put("requiredSubject", "伪造页面");
        AtomicInteger calls = new AtomicInteger();
        try (ExtensionManager extensions = fixture.extensionManager();
             AgentEngine engine = fixture.harnessEngine(extensions, request -> {
                 calls.incrementAndGet();
                 var context = new TaskAcceptanceContext(request.runRequest(), List::of).currentContext();
                 assertEquals(1, context.size());
                 assertEquals("host", context.getFirst().path("source").asText());
                 assertEquals("contacts", context.getFirst().path("conditions").get(0)
                         .path("criterionId").asText());
                 assertEquals("联系人", context.getFirst().path("conditions").get(0)
                         .path("subject").asText());
                 assertFalse(context.toString().contains("伪造页面"));
                 return CompletableFuture.completedFuture(ReasoningResult.completed(
                         JsonNodeFactory.instance.objectNode().put("text", "observations still needed")));
             })) {
            RunHandle handle = engine.start(fixture.request("frozen-requirements")
                    .withAttribute(TaskContractCompiler.ATTRIBUTE, fixture.json.valueToTree(contract))
                    .withAttribute(TaskContractCompiler.ORIGINAL_REQUEST_ATTRIBUTE,
                            JsonNodeFactory.instance.textNode("查看联系人"))
                    .withAttribute(TaskAcceptanceContext.ATTRIBUTE, forged));
            assertEquals(RunState.COMPLETED, engine.get(handle.id()).state());
            assertEquals(1, calls.get());
            assertEquals(TaskOutcome.UNVERIFIED, engine.taskResult(handle.id()).orElseThrow().outcome(),
                    "providing requirements is not completion evidence");
        }
    }

    @Test
    void humanClarificationCanReplaceAnUnreliableExplicitGoalBeforeAnyAction() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        AtomicInteger planningCalls = new AtomicInteger();
        AtomicInteger reasoningCalls = new AtomicInteger();
        String clarification = "停止打开QQ，只解释什么是联系人";
        ModelTaskGateway planner = task -> {
            int call = planningCalls.incrementAndGet();
            var answer = JsonNodeFactory.instance.objectNode();
            if (call <= 2) {
                answer.put("applicable", true).put("reliable", false);
                answer.putArray("reasonCodes").add("AMBIGUOUS_VIEW");
            } else {
                assertEquals(clarification, task.input().path("currentUserInput").asText());
                assertTrue(task.input().path("humanHistory").toString().contains("打开QQ查看联系人"));
                assertFalse(task.input().path("originalRequestExplicit").asBoolean());
                answer.put("originalRequest", clarification).put("applicable", false).put("reliable", true);
            }
            answer.putArray("criteria");
            return CompletableFuture.completedFuture(new com.javaclaw.framework.spi.ModelTaskResult(
                    answer, "fixture", 0, 0, false, Map.of()));
        };
        ReasoningGateway reasoning = request -> {
            reasoningCalls.incrementAndGet();
            assertEquals(clarification, TaskContractCompiler.originalRequest(request.runRequest()));
            return CompletableFuture.completedFuture(ReasoningResult.completed(
                    JsonNodeFactory.instance.objectNode().put("text", "联系人是通讯录中的个人或组织条目")));
        };
        try (ExtensionManager extensions = fixture.extensionManager();
             AgentEngine engine = new AgentEngine(new AgentCompiler(fixture.definitions, extensions, fixture.json),
                     fixture.runs, fixture.plans, withClaimDone(reasoning), Runnable::run,
                     fixture.json, fixture.clock, new RunUsageLedger(), planner)) {
            RunHandle handle = engine.start(fixture.request("clarify-unreliable-goal")
                    .withAttribute(TaskContractCompiler.ORIGINAL_REQUEST_ATTRIBUTE,
                            JsonNodeFactory.instance.textNode("打开QQ查看联系人")));
            assertEquals(RunState.PAUSED, engine.get(handle.id()).state());
            assertEquals(0, reasoningCalls.get());
            engine.resume(handle.id(), new ResumeCommand("user.input",
                    JsonNodeFactory.instance.objectNode().put("text", clarification)));
            assertEquals(RunState.COMPLETED, engine.get(handle.id()).state());
            assertEquals(3, planningCalls.get());
            assertEquals(1, reasoningCalls.get());
            assertEquals(TaskOutcome.DELIVERED, engine.taskResult(handle.id()).orElseThrow().outcome());
            assertEquals(1, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.task.contract_revised")).count());
        }
    }

    @Test
    void pausedUnverifiedResultIsReplacedAfterAResumedDelivery() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        TaskContractV3 question = new TaskContractV3(3, "answer later", List.of(),
                false, true, "definition");
        ReasoningGateway reasoning = request -> CompletableFuture.completedFuture(
                request.resumeCommand() == null
                        ? new ReasoningResult(RunState.PAUSED,
                                JsonNodeFactory.instance.objectNode().put("text", "more work needed"),
                                "TASK_UNVERIFIED")
                        : ReasoningResult.completed(
                                JsonNodeFactory.instance.objectNode().put("text", "answer")));
        try (ExtensionManager extensions = fixture.extensionManager();
             AgentEngine engine = fixture.harnessEngine(extensions, reasoning)) {
            RunRequest input = fixture.request("answer later").withAttribute(
                    TaskContractCompiler.ATTRIBUTE, fixture.json.valueToTree(question));
            RunHandle handle = engine.start(input);
            assertEquals(RunState.PAUSED, engine.get(handle.id()).state());
            assertEquals(TaskOutcome.UNVERIFIED,
                    engine.taskResult(handle.id()).orElseThrow().outcome());

            RunHandle resumed = engine.resume(handle.id(), new ResumeCommand("user.input",
                    JsonNodeFactory.instance.objectNode().put("answer", "continue")));
            assertEquals(RunState.COMPLETED,
                    resumed.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
            assertEquals(TaskOutcome.DELIVERED,
                    engine.taskResult(handle.id()).orElseThrow().outcome());
            assertEquals(2, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.task.outcome")).count());
        }
    }

    @Test
    void internalContextAndCatalogReadsKeepQuestionContract() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        TaskContractV3 question = new TaskContractV3(3, "what is this?", List.of(),
                false, true, "definition");
        try (ExtensionManager extensions = fixture.extensionManager();
             AgentEngine engine = fixture.harnessEngine(extensions, request -> {
                 for (String tool : List.of("framework_context_search_abc",
                         "framework_context_fetch_abc", "framework_context_fixed_abc",
                         "framework_tool_catalog")) {
                     request.events().emit("core.tool.started", 1, "framework.core",
                             fixture.json.createObjectNode().put("tool", tool)
                                     .put("fingerprint", tool)
                                     .put("trustedContextRead",
                                             !tool.equals("framework_tool_catalog"))
                                     .put("trustedToolCatalog",
                                             tool.equals("framework_tool_catalog")));
                 }
                 return CompletableFuture.completedFuture(ReasoningResult.completed(
                         JsonNodeFactory.instance.objectNode().put("text", "answer")));
             })) {
            var handle = engine.start(fixture.request("question-with-context").withAttribute(
                    TaskContractCompiler.ATTRIBUTE, fixture.json.valueToTree(question)));
            handle.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            var events = fixture.runs.eventsAfter(handle.id(), 0);
            assertTrue(events.stream().noneMatch(event ->
                    event.type().equals("core.task.contract_revised")));
            assertEquals(TaskOutcome.DELIVERED,
                    TaskResultEvaluator.latestOutcome(events, fixture.json).orElseThrow().outcome());
            assertEquals("answer", engine.get(handle.id()).output().path("text").asText());
        }
    }

    @Test
    void businessToolStillRevisesQuestionContract() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        TaskContractV3 question = new TaskContractV3(3, "what is this?", List.of(),
                false, true, "definition");
        try (ExtensionManager extensions = fixture.extensionManager();
             AgentEngine engine = fixture.harnessEngine(extensions, request -> {
                 request.events().emit("core.tool.started", 1, "framework.core",
                         fixture.json.createObjectNode().put("tool", "web_content")
                                 .put("fingerprint", "web-content"));
                 return CompletableFuture.completedFuture(ReasoningResult.completed(
                         JsonNodeFactory.instance.objectNode().put("text", "answer")));
             })) {
            var handle = engine.start(fixture.request("question-with-business-tool").withAttribute(
                    TaskContractCompiler.ATTRIBUTE, fixture.json.valueToTree(question)));
            handle.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            var events = fixture.runs.eventsAfter(handle.id(), 0);
            assertEquals(1, events.stream().filter(event ->
                    event.type().equals("core.task.contract_revised")).count());
            assertEquals(TaskOutcome.UNVERIFIED,
                    TaskResultEvaluator.latestOutcome(events, fixture.json).orElseThrow().outcome());
        }
    }

    @Test
    void taskHarnessPersistsContractAndOutcomeBeforeTechnicalCompletion() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        TaskContractV3 contract = new TaskContractV3(3, "write result", List.of(
                new TaskCriterionV3("write", "result exists", "file.write",
                        CapabilityMetadata.TargetKind.FILE, "result.txt",
                        EffectReceiptV1.Status.VERIFIED, "")),
                true, true, "definition");
        try (ExtensionManager extensions = fixture.extensionManager();
             AgentEngine engine = fixture.harnessEngine(extensions, request -> {
                 request.events().emit("core.tool.receipt", 1, "framework.core",
                         fixture.json.createObjectNode().put("invocationId", "write-1")
                                 .put("tool", "sys_file_write").put("operation", "write")
                                 .put("target", ProjectAccessPolicy.projectRoot().resolve("result.txt").toString())
                                 .put("status", "VERIFIED")
                                 .put("observedAt", "2026-01-01T00:00:00Z")
                                 .put("evidenceRef", "file:result.txt"));
                 return CompletableFuture.completedFuture(ReasoningResult.completed(
                         JsonNodeFactory.instance.objectNode().put("text", "done")));
             })) {
            RunRequest input = fixture.request("task-result").withAttribute(
                    TaskContractCompiler.ATTRIBUTE, fixture.json.valueToTree(contract));
            var handle = engine.start(input);
            assertEquals(RunState.COMPLETED,
                    handle.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
            var events = fixture.runs.eventsAfter(handle.id(), 0);
            assertTrue(events.getFirst().payload().path("taskHarnessV3").asBoolean());
            assertEquals(3, events.stream().filter(event -> event.type().equals("core.task.contract"))
                    .findFirst().orElseThrow().schemaVersion());
            assertEquals(3, events.stream().filter(event -> event.type().equals("core.task.outcome"))
                    .findFirst().orElseThrow().schemaVersion());
            assertTrue(TaskResultEvaluator.latestContract(events, fixture.json).isEmpty());
            assertTrue(TaskResultEvaluator.latestContractV3(events, fixture.json).isPresent());
            List<String> types = events.stream().map(event -> event.type()).toList();
            assertTrue(types.indexOf("core.task.contract") < types.indexOf("core.tool.receipt"));
            assertTrue(types.indexOf("core.tool.receipt") < types.indexOf("core.task.outcome"));
            assertTrue(types.indexOf("core.task.outcome") < types.indexOf("core.run.completed"));
            assertEquals(TaskOutcome.VERIFIED_COMPLETE, TaskResultEvaluator.latestOutcome(
                    events, fixture.json).orElseThrow().outcome());
            assertEquals("VERIFIED_COMPLETE", events.getLast().payload()
                    .path("taskResult").path("outcome").asText());
        }
    }

    @Test
    void v3UnknownClickIsReconciledAfterVerifiedOutcomeBeforeRunCompletion() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        String actionFrame = UUID.randomUUID().toString();
        String evidenceFrame = UUID.randomUUID().toString();
        TaskContractV3 contract = new TaskContractV3(3, "打开系统设置查看网络", List.of(
                new TaskCriterionV3("open", "打开系统设置", "desktop.open",
                        CapabilityMetadata.TargetKind.DESKTOP_APPLICATION, "系统设置",
                        EffectReceiptV1.Status.ACCEPTED, ""),
                new TaskCriterionV3("click", "点击网络", "desktop.click",
                        CapabilityMetadata.TargetKind.DESKTOP_APPLICATION, "系统设置",
                        EffectReceiptV1.Status.ACCEPTED, ""),
                new TaskCriterionV3("view", "网络视图可见", "desktop.observe",
                        CapabilityMetadata.TargetKind.DESKTOP_APPLICATION, "系统设置",
                        EffectReceiptV1.Status.OBSERVED, "network")),
                true, true, "definition");
        AtomicReference<RunId> activeId = new AtomicReference<>();
        AtomicReference<TaskResultEvaluator.VerifiedActionEvidence> delivered = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();
        ReasoningGateway reasoning = request -> {
            activeId.set(request.runId());
            ObjectNode open = desktopReceipt("open-1", "open", "ACCEPTED", "open:settings",
                    "2026-01-01T00:00:00Z");
            open.putObject("metadata").put("targetId", "window-1")
                    .put("sessionId", "session-1");
            request.events().emit("core.tool.receipt", 1, "framework.core", open);

            ObjectNode beforeClick = desktopReceipt("observe-before-click", "observe",
                    "OBSERVED", "frame:overview", "2026-01-01T00:00:00.500Z");
            beforeClick.put("subject", "overview");
            beforeClick.putObject("metadata").put("targetId", "window-1")
                    .put("sessionId", "session-1").put("observationId", actionFrame)
                    .put("windowGeneration", "4").put("contentRevision", "1")
                    .put("capturedAtMillis", "1767225600500")
                    .put("viewEvidence", "heading:1,2,10,10|content:2,20,20,20");
            request.events().emit("core.tool.receipt", 1, "framework.core", beforeClick);

            ObjectNode started = JsonNodeFactory.instance.objectNode()
                    .put("tool", "desktop_session_click").put("invocationId", "click-1")
                    .put("fingerprint", "click-one").put("effectKey", "click-one")
                    .put("effectPolicy", "OBSERVATION_GATED")
                    .put("resourceKey", "desktop:window-1").put("idempotent", false);
            request.control().restoreEffectStart("click-1", "click-one", "click-one",
                    false, ToolEffectPolicy.OBSERVATION_GATED, "desktop:window-1");
            request.events().toolStarted(StepEvents.startedPayload(
                    com.javaclaw.framework.api.StepId.tool(request.runId(), "click-1"),
                    com.javaclaw.framework.api.AgentStep.Kind.TOOL, started, null), started);
            ObjectNode click = desktopReceipt("click-1", "click", "UNKNOWN", "click:settings",
                    "2026-01-01T00:00:01Z");
            click.putObject("metadata").put("targetId", "window-1")
                    .put("sessionId", "session-1")
                    .put("observationId", actionFrame).put("windowGeneration", "4")
                    .put("dispatchAttempted", "true").put("delivery", "MAYBE_SENT");
            request.events().emit("core.tool.receipt", 1, "framework.core", click);
            request.control().restoreEffectReceipt("click-1", EffectReceiptV1.Status.UNKNOWN,
                    "MAYBE_SENT");
            assertThrows(ToolPermissionDeniedException.class, () ->
                    request.control().assertRepairRetryAllowed("click-two", "click-two", false,
                            ToolEffectPolicy.OBSERVATION_GATED, "desktop:window-1"));

            ObjectNode observed = desktopReceipt("observe-1", "observe", "OBSERVED",
                    "frame:network", "2026-01-01T00:00:03Z");
            observed.put("subject", "network");
            observed.putObject("metadata").put("targetId", "window-1")
                    .put("sessionId", "session-1").put("observationId", evidenceFrame)
                    .put("windowGeneration", "4").put("contentRevision", "2")
                    .put("capturedAtMillis", "1767225603000")
                    .put("viewEvidence", "heading:1,2,10,10|content:2,20,20,20");
            request.events().emit("core.tool.receipt", 1, "framework.core", observed);
            var durable = TaskEvidenceCollector.collect(fixture.runs, request.runId());
            var frozen = TaskResultEvaluator.latestContractV3(durable, fixture.json).orElseThrow();
            var catalog = TrustedCapabilityRegistry.builtins();
            var desktop = TaskResultEvaluator.desktopContract(frozen, catalog);
            assertEquals(1, TaskResultEvaluator.verifiedCheckpointEvidence(desktop, durable).size(),
                    "the linked before/action/after frames must create one checkpoint candidate");
            int clickAt = java.util.stream.IntStream.range(0, durable.size())
                    .filter(index -> durable.get(index).type().equals("core.tool.receipt")
                            && durable.get(index).payload().path("invocationId").asText("")
                                    .equals("click-1"))
                    .findFirst().orElseThrow();
            TaskContractV3 prefix = new TaskContractV3(3, frozen.originalRequest(),
                    frozen.criteria().subList(0, 1), true, true, frozen.source());
            var prefixResult = TaskResultEvaluator.evaluateV3(prefix,
                    durable.subList(0, clickAt), "", catalog);
            assertEquals(TaskOutcome.VERIFIED_COMPLETE, prefixResult.outcome(),
                    "the preceding V3 open criterion must be proven: " + prefixResult);
            assertTrue(durable.stream().anyMatch(event ->
                    event.type().equals("core.task.checkpoint_verified")),
                    "the V3 criterion prefix and frozen contract must authorize the checkpoint");
            request.control().assertRepairRetryAllowed("click-two", "click-two", false,
                    ToolEffectPolicy.OBSERVATION_GATED, "desktop:window-1");
            assertThrows(ToolPermissionDeniedException.class, () ->
                    request.control().assertRepairRetryAllowed("click-one", "click-one", false,
                            ToolEffectPolicy.OBSERVATION_GATED, "desktop:window-1"));
            return CompletableFuture.completedFuture(ReasoningResult.completed(
                    JsonNodeFactory.instance.objectNode().put("text", "网络视图已显示")));
        };
        ModelTaskGateway planner = task -> CompletableFuture.failedFuture(
                new AssertionError("structured contract must not call a model planner"));
        try (ExtensionManager extensions = fixture.extensionManager();
             AgentEngine engine = new AgentEngine(
                     new AgentCompiler(fixture.definitions, extensions, fixture.json),
                     fixture.runs, fixture.plans, withClaimDone(reasoning), Runnable::run,
                     fixture.json, fixture.clock, new RunUsageLedger(), planner,
                     (request, proof) -> {
                         callbacks.incrementAndGet();
                         delivered.set(proof);
                         RunId runId = activeId.get();
                         assertNotNull(runId);
                         var committed = fixture.runs.eventsAfter(runId, 0);
                         assertTrue(committed.stream().anyMatch(event ->
                                 event.type().equals("core.effect.reconciled")
                                         && event.payload().path("actionInvocationId")
                                                 .asText("").equals(proof.invocationId())
                                         && event.payload().path("evidenceObservationId")
                                                 .asText("").equals(proof.evidenceObservationId())),
                                 "callback may only receive a durably reconciled action");
                         assertTrue(committed.stream().noneMatch(event ->
                                 event.type().equals("core.run.completed")),
                                 "callback runs before technical completion");
                     })) {
            RunRequest input = fixture.request("v3-unknown-click").withAttribute(
                    TaskContractCompiler.ATTRIBUTE, fixture.json.valueToTree(contract));
            var handle = engine.start(input);
            var completion = handle.completion().toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);
            assertEquals(RunState.COMPLETED, completion.state(), completion.error());
            var events = fixture.runs.eventsAfter(handle.id(), 0);
            List<String> types = events.stream().map(event -> event.type()).toList();
            int outcomeAt = types.indexOf("core.task.outcome");
            int checkpointAt = types.indexOf("core.task.checkpoint_verified");
            int reconciledAt = types.indexOf("core.effect.reconciled");
            int completedAt = types.indexOf("core.run.completed");
            assertTrue(checkpointAt >= 0 && checkpointAt < reconciledAt
                    && reconciledAt < outcomeAt && outcomeAt < completedAt,
                    "a verified intermediate view releases the gate before final task completion");
            assertEquals("VERIFIED_COMPLETE", events.get(outcomeAt).payload()
                    .path("outcome").asText());
            assertEquals("SATISFIED", events.get(reconciledAt).payload()
                    .path("outcome").asText());
            assertEquals(1, callbacks.get());
            assertEquals(new TaskResultEvaluator.VerifiedActionEvidence("click-1",
                    "session-1", "window-1", actionFrame, evidenceFrame), delivered.get());
        }
    }

    private static ObjectNode desktopReceipt(String invocationId, String operation,
            String status, String evidenceRef, String observedAt) {
        return JsonNodeFactory.instance.objectNode().put("invocationId", invocationId)
                .put("tool", "desktop_session_" + operation).put("operation", operation)
                .put("target", "系统设置").put("status", status)
                .put("observedAt", observedAt).put("evidenceRef", evidenceRef);
    }

    @Test
    void taskContractReplaysAfterRestartAndLegacyRunIsNotBackfilled() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        TaskContractV3 contract = new TaskContractV3(3, "what is this?", List.of(),
                false, true, "definition");
        try (ExtensionManager extensions = fixture.extensionManager()) {
            ReasoningGateway resumable = request -> CompletableFuture.completedFuture(
                    request.resumeCommand() == null
                            ? ReasoningResult.waitingForInput(JsonNodeFactory.instance.objectNode(), "wait")
                            : ReasoningResult.completed(JsonNodeFactory.instance.objectNode().put("text", "answer")));
            RunRequest input = fixture.request("replay-contract").withAttribute(
                    TaskContractCompiler.ATTRIBUTE, fixture.json.valueToTree(contract));
            RunId runId;
            try (AgentEngine first = fixture.harnessEngine(extensions, resumable)) {
                runId = first.start(input).id();
                assertEquals(RunState.WAITING_INPUT, fixture.runs.find(runId).orElseThrow().snapshot().state());
            }
            try (AgentEngine restored = fixture.harnessEngine(extensions, resumable)) {
                var resumed = restored.resume(runId,
                        new ResumeCommand("user.input", JsonNodeFactory.instance.objectNode()));
                assertEquals(RunState.COMPLETED,
                        resumed.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
                var events = fixture.runs.eventsAfter(runId, 0);
                assertEquals(1, events.stream().filter(event -> event.type().equals("core.task.contract")).count());
                assertEquals(TaskOutcome.DELIVERED,
                        TaskResultEvaluator.latestOutcome(events, fixture.json).orElseThrow().outcome());
            }
            try (AgentEngine legacy = fixture.engine(extensions,
                    request -> CompletableFuture.completedFuture(ReasoningResult.completed(
                            JsonNodeFactory.instance.objectNode().put("text", "legacy"))))) {
                var handle = legacy.start(fixture.request("legacy-no-harness"));
                handle.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
                assertTrue(fixture.runs.eventsAfter(handle.id(), 0).stream()
                        .noneMatch(event -> event.type().startsWith("core.task.")));
            }
        }
    }

    @Test
    void parentTaskAcceptsTrustedReceiptFromCompletedDescendantRun() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        TaskContractV3 contract = new TaskContractV3(3, "write child result and inspect it", List.of(
                new TaskCriterionV3("write", "child result exists", "file.write",
                        CapabilityMetadata.TargetKind.FILE, "child.txt",
                        EffectReceiptV1.Status.VERIFIED, ""),
                new TaskCriterionV3("inspect", "parent inspects child result", "file.read",
                        CapabilityMetadata.TargetKind.FILE, "child.txt",
                        EffectReceiptV1.Status.OBSERVED, "")),
                true, true, "definition");
        TaskContractV3 childContract = new TaskContractV3(3, "write child.txt", List.of(
                new TaskCriterionV3("write", "child result exists", "file.write",
                        CapabilityMetadata.TargetKind.FILE, "child.txt",
                        EffectReceiptV1.Status.VERIFIED, "")), true, true, "definition");
        AtomicReference<AgentEngine> engineRef = new AtomicReference<>();
        ReasoningGateway reasoning = request -> {
            if (request.runRequest().scope().sessionId().equals("grandchild-session")) {
                request.events().emit("core.tool.receipt", 1, "framework.core",
                        fixture.json.createObjectNode().put("invocationId", "grandchild-write")
                                .put("tool", "sys_file_write").put("operation", "write")
                                .put("target", ProjectAccessPolicy.projectRoot().resolve("child.txt").toString())
                                .put("status", "VERIFIED")
                                .put("observedAt", "2026-01-01T00:00:00Z")
                                .put("evidenceRef", "file:child.txt"));
            } else if (request.runRequest().source().kind().equals("subagent")) {
                RunRequest grandchild = RunRequest.builder()
                        .agent(AgentDefinitionRef.latest("test.agent"))
                        .profile(RunProfileRef.latest("test.profile"))
                        .source(InvocationSource.subAgent(request.runId().value()))
                        .scope(new RunScope("workspace", "user", "grandchild-session"))
                        .input(InputBlock.text("write child.txt"))
                        .attributes(Map.of(TaskContractCompiler.ATTRIBUTE, fixture.json.valueToTree(childContract)))
                        .linkage(new RunLinkage(request.runId(), null, null))
                        .permissionCeiling(PermissionSet.UNRESTRICTED).build();
                try { engineRef.get().start(grandchild).completion().toCompletableFuture()
                        .get(2, TimeUnit.SECONDS); }
                catch (Exception failure) { return CompletableFuture.failedFuture(failure); }
            } else {
                RunRequest child = RunRequest.builder()
                        .agent(AgentDefinitionRef.latest("test.agent"))
                        .profile(RunProfileRef.latest("test.profile"))
                        .source(InvocationSource.subAgent(request.runId().value()))
                        .scope(new RunScope("workspace", "user", "child-session"))
                        .input(InputBlock.text("write child.txt"))
                        .attributes(Map.of(TaskContractCompiler.ATTRIBUTE, fixture.json.valueToTree(childContract)))
                        .linkage(new RunLinkage(request.runId(), null, null))
                        .permissionCeiling(PermissionSet.UNRESTRICTED).build();
                try { engineRef.get().start(child).completion().toCompletableFuture().get(2, TimeUnit.SECONDS); }
                catch (Exception failure) { return CompletableFuture.failedFuture(failure); }
                request.events().emit("core.tool.receipt", 1, "framework.core",
                        fixture.json.createObjectNode().put("invocationId", "parent-inspect")
                                .put("tool", "sys_file_read").put("operation", "read")
                                .put("target", ProjectAccessPolicy.projectRoot()
                                        .resolve("child.txt").toString()).put("status", "OBSERVED")
                                .put("observedAt", "2026-01-01T00:00:01Z")
                                .put("evidenceRef", "file:parent-inspect"));
            }
            return CompletableFuture.completedFuture(ReasoningResult.completed(
                    JsonNodeFactory.instance.objectNode().put("text", "done")));
        };
        try (ExtensionManager extensions = fixture.extensionManager();
             AgentEngine engine = fixture.harnessEngine(extensions, reasoning)) {
            engineRef.set(engine);
            RunRequest parent = fixture.request("parent-child").withAttribute(
                    TaskContractCompiler.ATTRIBUTE, fixture.json.valueToTree(contract));
            var handle = engine.start(parent);
            handle.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            var result = TaskResultEvaluator.latestOutcome(
                    fixture.runs.eventsAfter(handle.id(), 0), fixture.json).orElseThrow();
            assertEquals(TaskOutcome.VERIFIED_COMPLETE, result.outcome());
            assertEquals(List.of("file:child.txt", "file:parent-inspect"), result.evidenceRefs());
        }
    }

    @Test
    void parentClaimDoneCannotDeliverWhileAChildRunIsNotTerminal() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        TaskContractV3 question = new TaskContractV3(3, "answer after child", List.of(),
                false, true, "definition");
        AtomicReference<RunId> childId = new AtomicReference<>();
        ReasoningGateway reasoning = request -> {
            RunRequest child = RunRequest.builder()
                    .agent(AgentDefinitionRef.latest("test.agent"))
                    .profile(RunProfileRef.latest("test.profile"))
                    .source(InvocationSource.subAgent(request.runId().value()))
                    .scope(new RunScope("workspace", "user", "child-session"))
                    .input(InputBlock.text("continue the task"))
                    .linkage(new RunLinkage(request.runId(), null, null))
                    .permissionCeiling(PermissionSet.UNRESTRICTED).build();
            RunId id = RunId.random();
            childId.set(id);
            fixture.runs.create(id, child, "test-plan", new RunEventDraft(
                    "core.run.created", 1, "framework.core", null, null,
                    JsonNodeFactory.instance.objectNode()));
            fixture.runs.append(id, Set.of(RunState.CREATED), RunState.RUNNING,
                    new RunEventDraft("core.run.started", 1, "framework.core", null, null,
                            JsonNodeFactory.instance.objectNode()), null, null).orElseThrow();
            return CompletableFuture.completedFuture(ReasoningResult.completed(
                    JsonNodeFactory.instance.objectNode().put("text", "answer")));
        };
        try (ExtensionManager extensions = fixture.extensionManager();
             AgentEngine engine = fixture.harnessEngine(extensions, reasoning)) {
            RunRequest parent = fixture.request("parent-with-active-child").withAttribute(
                    TaskContractCompiler.ATTRIBUTE, fixture.json.valueToTree(question));
            RunHandle handle = engine.start(parent);
            handle.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertEquals(TaskOutcome.UNVERIFIED,
                    engine.taskResult(handle.id()).orElseThrow().outcome());
            assertEquals("DESCENDANT_RUN_IN_PROGRESS",
                    engine.taskResult(handle.id()).orElseThrow().stopReason());
            assertEquals(RunState.CANCELLED, fixture.runs.find(childId.get())
                    .orElseThrow().snapshot().state());
        }
    }

    @Test
    void restartRestoresNonIdempotentEffectKeyAndRepairBoundary() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        TaskContractV3 emailContract = new TaskContractV3(3, "send email", List.of(
                new TaskCriterionV3("send", "email submitted", "email.send",
                        CapabilityMetadata.TargetKind.EMAIL_ADDRESS, "alice@example.com",
                        EffectReceiptV1.Status.ACCEPTED, "")), true, true, "definition");
        AtomicReference<Boolean> retryBlocked = new AtomicReference<>(false);
        ReasoningGateway reasoning = request -> {
            if (request.resumeCommand() == null) {
                request.events().emit("core.tool.started", 1, "framework.core",
                        fixture.json.createObjectNode().put("fingerprint", "first-call")
                                .put("effectKey", "same-email-effect").put("idempotent", false));
                request.events().emit("core.tool.receipt", 1, "framework.core",
                        fixture.json.createObjectNode().put("fingerprint", "first-call")
                                .put("invocationId", "first-call").put("tool", "email_send")
                                .put("operation", "send").put("target", "alice@example.com")
                                .put("status", "UNKNOWN").put("observedAt", "2026-01-01T00:00:00Z")
                                .put("evidenceRef", "mail:unknown"));
                request.events().emit("core.task.repair_requested", 1, "framework.springai",
                        fixture.json.createObjectNode().put("reason", "MISSING_TRUSTED_RECEIPT"));
                return CompletableFuture.completedFuture(ReasoningResult.waitingForInput(
                        JsonNodeFactory.instance.objectNode(), "wait"));
            }
            try {
                request.control().assertRepairRetryAllowed("second-call", "same-email-effect", false);
            } catch (ToolPermissionDeniedException expected) {
                retryBlocked.set(true);
            }
            return CompletableFuture.completedFuture(ReasoningResult.completed(
                    JsonNodeFactory.instance.objectNode().put("text", "effect requires observation")));
        };
        try (ExtensionManager extensions = fixture.extensionManager()) {
            RunId id;
            try (AgentEngine first = fixture.harnessEngine(extensions, reasoning)) {
                id = first.start(fixture.request("effect-replay").withAttribute(
                        TaskContractCompiler.ATTRIBUTE,
                        fixture.json.valueToTree(emailContract))).id();
            }
            try (AgentEngine restored = fixture.harnessEngine(extensions, reasoning)) {
                restored.resume(id, new ResumeCommand("user.input", JsonNodeFactory.instance.objectNode()))
                        .completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
                assertTrue(retryBlocked.get());
            }
        }
    }

    @Test
    void runEventsOutboxIdempotencyAndTerminalArbitrationShareOneStateMachine()
            throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        try (ExtensionManager extensions = fixture.extensionManager();
             AgentEngine engine = fixture.engine(extensions, request -> {
                 request.events().emit("core.reasoning.observed", 1, "test",
                         JsonNodeFactory.instance.objectNode().put("ok", true));
                 return CompletableFuture.completedFuture(ReasoningResult.completed(
                         JsonNodeFactory.instance.objectNode().put("text", "done")));
             })) {
            RunRequest request = fixture.request("same-command");
            var first = engine.start(request);
            var outcome = first.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(RunState.COMPLETED, outcome.state());
            assertEquals("done", outcome.output().path("text").asText());

            var duplicate = engine.start(request);
            assertEquals(first.id(), duplicate.id());
            assertEquals(RunState.COMPLETED,
                    duplicate.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());

            List<String> eventTypes = fixture.runs.eventsAfter(first.id(), 0).stream()
                    .map(event -> event.type()).toList();
            assertEquals(List.of("core.run.created", "core.run.started",
                    "core.reasoning.observed", "core.run.completed"), eventTypes);
            assertEquals(1, eventTypes.stream().filter("core.run.completed"::equals).count());
            assertEquals(eventTypes.size(), fixture.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM agent_run_outbox WHERE run_id = ?",
                    Integer.class, first.id().value()));
            assertFalse(engine.cancel(first.id(), new CancelReason("LATE", "already terminal")));
        }
    }

    @Test
    void jdbcToolStartBatchIsAtomicAndRejectsUncertainInputOnTheSameWindow() {
        Fixture fixture = new Fixture();
        RunId id = RunId.random();
        RunRequest request = fixture.request(null);
        fixture.runs.create(id, request, "test-plan", new RunEventDraft(
                "core.run.created", 1, "framework.core", null, null,
                JsonNodeFactory.instance.objectNode()));
        fixture.runs.append(id, Set.of(RunState.CREATED), RunState.RUNNING,
                new RunEventDraft("core.run.started", 1, "framework.core", null, null,
                        JsonNodeFactory.instance.objectNode()), null, null).orElseThrow();

        var first = fixture.runs.appendBatch(id, Set.of(RunState.RUNNING), RunState.RUNNING,
                toolStartBatch("first", "desktop:exact-window"));
        assertEquals(2, first.orElseThrow().size());
        fixture.runs.append(id, Set.of(RunState.RUNNING), RunState.RUNNING,
                new RunEventDraft("core.tool.receipt", 1, "framework.core", null, null,
                        JsonNodeFactory.instance.objectNode().put("invocationId", "first")
                                .put("status", "UNKNOWN").putObject("metadata")
                                .put("delivery", "MAYBE_SENT")), null, null).orElseThrow();
        int before = fixture.runs.eventsAfter(id, 0).size();
        assertThrows(ToolPermissionDeniedException.class,
                () -> fixture.runs.appendBatch(id, Set.of(RunState.RUNNING), RunState.RUNNING,
                        toolStartBatch("second", "desktop:exact-window")));
        assertEquals(before, fixture.runs.eventsAfter(id, 0).size(),
                "a rejected reservation must not leave half a tool step in the journal");
        assertEquals(before, fixture.jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_run_outbox WHERE run_id=?", Integer.class, id.value()));
        assertEquals(2, fixture.runs.appendBatch(id, Set.of(RunState.RUNNING),
                RunState.RUNNING, toolStartBatch("other", "desktop:another-window"))
                .orElseThrow().size());
    }

    @Test
    void onlyMatchedLaterFrameCanDurablyReconcileUnknownDesktopInput() {
        Fixture fixture = new Fixture();
        RunId id = RunId.random();
        fixture.runs.create(id, fixture.request(null), "test-plan", new RunEventDraft(
                "core.run.created", 1, "framework.core", null, null,
                JsonNodeFactory.instance.objectNode()));
        fixture.runs.append(id, Set.of(RunState.CREATED), RunState.RUNNING,
                new RunEventDraft("core.run.started", 1, "framework.core", null, null,
                        JsonNodeFactory.instance.objectNode()), null, null).orElseThrow();
        String actionFrame = UUID.randomUUID().toString();
        var beforeClick = JsonNodeFactory.instance.objectNode()
                .put("invocationId", "observe-before-click")
                .put("tool", "desktop_session_observe").put("target", "系统设置")
                .put("operation", "observe").put("status", "OBSERVED")
                .put("evidenceRef", "frame:before")
                .put("observedAt", "2025-12-31T23:59:59Z");
        beforeClick.putObject("metadata").put("targetId", "exact-window")
                .put("sessionId", "session-1").put("observationId", actionFrame)
                .put("windowGeneration", "4").put("contentRevision", "1")
                .put("capturedAtMillis", "1767225599000");
        fixture.runs.append(id, Set.of(RunState.RUNNING), RunState.RUNNING,
                new RunEventDraft("core.tool.receipt", 1, "framework.core", null, null,
                        beforeClick), null, null).orElseThrow();
        fixture.runs.appendBatch(id, Set.of(RunState.RUNNING), RunState.RUNNING,
                toolStartBatch("uncertain-click", "desktop:exact-window")).orElseThrow();
        String evidenceId = UUID.randomUUID().toString();
        EffectReconciliationV1 proof = new EffectReconciliationV1("uncertain-click",
                "session-1", "exact-window", actionFrame, evidenceId);
        assertTrue(fixture.runs.reconcileEffect(id, proof).isEmpty(),
                "a start alone cannot release an uncertain input");
        var action = JsonNodeFactory.instance.objectNode()
                .put("invocationId", "uncertain-click").put("operation", "click")
                .put("tool", "desktop_session_click").put("target", "系统设置")
                .put("status", "UNKNOWN")
                .put("observedAt", "2026-01-01T00:00:00Z");
        action.putObject("metadata").put("delivery", "MAYBE_SENT")
                .put("targetId", "exact-window").put("sessionId", "session-1")
                .put("observationId", actionFrame).put("windowGeneration", "4");
        fixture.runs.append(id, Set.of(RunState.RUNNING), RunState.RUNNING,
                new RunEventDraft("core.tool.receipt", 1, "framework.core", null, null,
                        action), null, null).orElseThrow();
        var observed = JsonNodeFactory.instance.objectNode()
                .put("invocationId", "observe-1").put("operation", "observe")
                .put("tool", "desktop_session_observe").put("target", "系统设置")
                .put("status", "OBSERVED")
                .put("evidenceRef", "frame:network")
                .put("observedAt", "2026-01-01T00:00:01Z");
        observed.putObject("metadata").put("targetId", "exact-window")
                .put("sessionId", "session-1").put("observationId", evidenceId)
                .put("windowGeneration", "4").put("capturedAtMillis", "1767225601000")
                .put("viewEvidence", "heading:1,2,10,10|content:2,20,20,20");
        fixture.runs.append(id, Set.of(RunState.RUNNING), RunState.RUNNING,
                new RunEventDraft("core.tool.receipt", 1, "framework.core", null, null,
                        observed), null, null).orElseThrow();
        assertTrue(fixture.runs.reconcileEffect(id,
                new EffectReconciliationV1("uncertain-click", "session-1",
                        "other-window", actionFrame, evidenceId)).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> fixture.runs.append(id,
                Set.of(RunState.RUNNING), RunState.RUNNING,
                new RunEventDraft("core.effect.reconciled", 1, "framework.core", null, null,
                        JsonNodeFactory.instance.objectNode()), null, null));
        assertTrue(fixture.runs.reconcileEffect(id, proof).isEmpty(),
                "a later frame alone does not prove the task-specific action postcondition");
        fixture.runs.append(id, Set.of(RunState.RUNNING), RunState.RUNNING,
                new RunEventDraft("core.task.outcome", 2, "framework.core", null, null,
                        JsonNodeFactory.instance.objectNode().put("outcome", "VERIFIED_COMPLETE")
                                .set("evidenceRefs", JsonNodeFactory.instance.arrayNode()
                                        .add("frame:unrelated"))),
                null, null).orElseThrow();
        assertTrue(fixture.runs.reconcileEffect(id, proof).isEmpty(),
                "an unrelated verified outcome cannot reconcile this action");
        fixture.runs.append(id, Set.of(RunState.RUNNING), RunState.RUNNING,
                new RunEventDraft("core.task.outcome", 2, "framework.core", null, null,
                        JsonNodeFactory.instance.objectNode().put("outcome", "VERIFIED_COMPLETE")
                                .set("evidenceRefs", JsonNodeFactory.instance.arrayNode()
                                        .add("frame:network"))),
                null, null).orElseThrow();
        assertTrue(fixture.runs.reconcileEffect(id,
                new EffectReconciliationV1("uncertain-click", "session-1",
                        "exact-window", UUID.randomUUID().toString(), evidenceId)).isEmpty(),
                "an action cannot be reconciled without its exact earlier trusted frame");
        int before = fixture.runs.eventsAfter(id, 0).size();
        var reconciled = fixture.runs.reconcileEffect(id, proof).orElseThrow();
        assertEquals("core.effect.reconciled", reconciled.type());
        assertEquals("SATISFIED", reconciled.payload().path("outcome").asText());
        assertTrue(fixture.runs.reconcileEffect(id, proof).isEmpty(),
                "one action is reconciled at most once");
        assertEquals(before + 1, fixture.runs.eventsAfter(id, 0).size());
        assertEquals(2, fixture.runs.appendBatch(id, Set.of(RunState.RUNNING),
                RunState.RUNNING, toolStartBatch("fresh-action", "desktop:exact-window"))
                .orElseThrow().size());
    }

    private static List<RunEventDraft> toolStartBatch(String invocationId, String resourceKey) {
        var step = JsonNodeFactory.instance.objectNode()
                .put("stepId", "tool/" + invocationId).put("kind", "TOOL")
                .set("input", JsonNodeFactory.instance.objectNode()
                        .put("invocationId", invocationId));
        var started = JsonNodeFactory.instance.objectNode()
                .put("tool", "desktop_session_click")
                .put("invocationId", invocationId).put("fingerprint", invocationId)
                .put("effectKey", invocationId).put("effectPolicy", "OBSERVATION_GATED")
                .put("resourceKey", resourceKey).put("idempotent", false);
        return List.of(new RunEventDraft("core.step.started", 1, "framework.core",
                        null, null, step),
                new RunEventDraft("core.tool.started", 1, "framework.core",
                        null, null, started));
    }

    private static ReasoningResult approvalResponse(
            com.javaclaw.framework.api.ToolApprovalChallenge challenge, String reason) {
        var output = JsonNodeFactory.instance.objectNode();
        output.set("approval", challenge.toJson());
        return ReasoningResult.waitingForApproval(output, reason);
    }

    /** Fake reasoning gateways still submit the same separate control event as the real model. */
    private static ReasoningGateway withClaimDone(ReasoningGateway delegate) {
        return request -> delegate.execute(request).thenApply(result -> {
            if (result.nextState() != RunState.COMPLETED) return result;
            var model = com.javaclaw.framework.api.StepId.random();
            StepEvents.started(request.events(), model,
                    com.javaclaw.framework.api.AgentStep.Kind.MODEL,
                    JsonNodeFactory.instance.objectNode(), null);
            StepEvents.completed(request.events(), model,
                    JsonNodeFactory.instance.objectNode(), null);
            String invocation = "fixture-decision-" + UUID.randomUUID();
            var control = com.javaclaw.framework.api.StepId.tool(request.runId(), invocation);
            var input = JsonNodeFactory.instance.objectNode().put("phase", "harness.decision")
                    .put("modelStepId", model.value()).put("invocationId", invocation);
            StepEvents.started(request.events(), control,
                    com.javaclaw.framework.api.AgentStep.Kind.ORCHESTRATION,
                    input, model.value());
            String message = result.output() == null ? "done"
                    : result.output().path("text").asText("done");
            var decision = new ModelDecisionV1(ModelDecisionV1.Decision.CLAIM_DONE,
                    message, List.of());
            var submitted = JsonNodeFactory.instance.objectNode()
                    .put("modelStepId", model.value()).put("invocationId", invocation);
            submitted.set("value", decision.toJson());
            request.events().emit("core.harness.decision_submitted", 1,
                    "framework.springai", submitted);
            StepEvents.completed(request.events(), control,
                    JsonNodeFactory.instance.objectNode(), null);
            return result;
        });
    }

    @Test
    void pausedRunRestoresItsPersistedPlanAndResumesAfterKernelRestart() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        try (ExtensionManager extensions = fixture.extensionManager()) {
            ReasoningGateway resumable = request -> CompletableFuture.completedFuture(
                    request.resumeCommand() == null
                            ? ReasoningResult.waitingForInput(
                            JsonNodeFactory.instance.objectNode().put("question", "continue?"), "input")
                            : ReasoningResult.completed(
                            JsonNodeFactory.instance.objectNode().put("text", "resumed")));
            var firstEngine = fixture.engine(extensions, resumable);
            var handle = firstEngine.start(fixture.request(null));
            assertEquals(RunState.WAITING_INPUT, fixture.runs.find(handle.id())
                    .orElseThrow().snapshot().state());
            firstEngine.close();
            assertEquals(RunState.PAUSED, fixture.runs.find(handle.id())
                    .orElseThrow().snapshot().state());

            try (AgentEngine restored = fixture.engine(extensions, resumable)) {
                assertEquals(1, restored.activeRunCount());
                var resumed = restored.resume(handle.id(),
                        new ResumeCommand("user.input",
                                JsonNodeFactory.instance.objectNode().put("text", "yes")));
                assertEquals(RunState.COMPLETED,
                        resumed.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
                assertEquals("resumed", restored.get(handle.id()).output().path("text").asText());
            }
        }
    }

    @Test
    void expiredPausedRunReturnsTerminalHandleWithoutResumingReasoning() throws Exception {
        MutableClock clock = new MutableClock();
        Fixture fixture = new Fixture(clock);
        fixture.publishDefinition(Map.of());
        AtomicInteger calls = new AtomicInteger();
        try (ExtensionManager extensions = fixture.extensionManager();
             AgentEngine engine = fixture.engine(extensions, request -> {
                 calls.incrementAndGet();
                 return CompletableFuture.completedFuture(new ReasoningResult(
                         RunState.PAUSED, null, "MODEL_BLOCKED"));
             })) {
            RunHandle handle = engine.start(fixture.request(null, timedBudget()));
            Instant originalDeadline = engine.deadline(handle.id()).orElseThrow();
            assertEquals(clock.instant().plus(Duration.ofMinutes(30)), originalDeadline);
            clock.advance(Duration.ofMinutes(30));
            assertTrue(engine.expired(handle.id()));

            RunHandle expired = engine.resume(handle.id(), new ResumeCommand("user.input",
                    JsonNodeFactory.instance.objectNode().put("text", "继续")));

            assertEquals(RunState.CANCELLED,
                    expired.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
            assertEquals(1, calls.get());
            assertEquals(0, engine.activeRunCount());
            assertEquals(originalDeadline, engine.deadline(handle.id()).orElseThrow());
            assertTrue(fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .noneMatch(event -> event.type().equals("core.run.resumed")));
            var cancellation = fixture.runs.eventsAfter(handle.id(), 0).getLast();
            assertEquals("core.run.cancelled", cancellation.type());
            assertEquals("RUN_TIMEOUT", cancellation.payload().path("code").asText());
            assertEquals(originalDeadline.toString(), cancellation.payload().path("deadline").asText());
            assertFalse(cancellation.payload().path("userInitiated").asBoolean());
        }
    }

    @Test
    void expiredRunIsTerminalAfterRestartAndPreservesUncertainEffectJournal() throws Exception {
        MutableClock clock = new MutableClock();
        Fixture fixture = new Fixture(clock);
        fixture.publishDefinition(Map.of());
        AtomicInteger calls = new AtomicInteger();
        ReasoningGateway reasoning = request -> {
            if (calls.incrementAndGet() > 1) {
                AtomicInteger dispatched = new AtomicInteger();
                assertEquals(0, request.control().toolCallCount());
                assertEquals(timedBudget().maxToolCalls(), request.control().remainingToolCalls());
                assertThrows(ToolPermissionDeniedException.class, () -> request.control().reserveEffect(
                        "new-click", "new-fingerprint", "new-effect", false,
                        ToolEffectPolicy.OBSERVATION_GATED, "desktop:exact-window", dispatched::incrementAndGet));
                assertEquals(0, dispatched.get(), "terminal expiry does not prove old input was not sent");
                assertDoesNotThrow(() -> request.control().assertRepairRetryAllowed("different-app-click",
                        "different-app-effect", false, ToolEffectPolicy.OBSERVATION_GATED,
                        "desktop:another-window"));
                return CompletableFuture.completedFuture(ReasoningResult.completed(
                        JsonNodeFactory.instance.objectNode().put("text", "new task")));
            }
            var modelStep = com.javaclaw.framework.api.StepId.random();
            StepEvents.started(request.events(), modelStep, com.javaclaw.framework.api.AgentStep.Kind.MODEL,
                    JsonNodeFactory.instance.objectNode(), null);
            StepEvents.completed(request.events(), modelStep, JsonNodeFactory.instance.objectNode(),
                    JsonNodeFactory.instance.objectNode().put("inputTokens", 3).put("outputTokens", 2)
                            .put("estimatedCostCny", new BigDecimal("0.15")));
            List<RunEventDraft> started = toolStartBatch("uncertain-click", "desktop:exact-window");
            request.control().recordToolCall("uncertain-click");
            request.control().reserveEffect("uncertain-click", "uncertain-click", "uncertain-click",
                    false, ToolEffectPolicy.OBSERVATION_GATED, "desktop:exact-window", () ->
                            request.events().toolStarted(started.get(0).payload(), started.get(1).payload()));
            var receipt = JsonNodeFactory.instance.objectNode().put("invocationId", "uncertain-click")
                    .put("fingerprint", "uncertain-click").put("tool", "desktop_session_click")
                    .put("operation", "click").put("status", "UNKNOWN");
            receipt.putObject("metadata").put("delivery", "MAYBE_SENT");
            request.events().emit("core.tool.receipt", 1, "framework.core", receipt);
            return CompletableFuture.completedFuture(new ReasoningResult(
                    RunState.PAUSED, null, "MODEL_BLOCKED"));
        };
        try (ExtensionManager extensions = fixture.extensionManager()) {
            AgentEngine first = fixture.engine(extensions, reasoning);
            RunHandle handle = first.start(fixture.request(null, timedBudget()));
            Instant originalDeadline = first.deadline(handle.id()).orElseThrow();
            String planId = first.get(handle.id()).executionPlanId();
            JsonNode originalPlan = fixture.plans.find(planId).orElseThrow().deepCopy();
            first.close();
            var journalBeforeRecovery = fixture.runs.eventsAfter(handle.id(), 0);
            clock.advance(Duration.ofDays(2));

            try (AgentEngine restored = fixture.engine(extensions, reasoning)) {
                assertEquals(RunState.CANCELLED, restored.get(handle.id()).state());
                assertEquals(0, restored.activeRunCount());
                assertTrue(restored.activeTurn(fixture.request(null).scope()).isEmpty());
                assertEquals(1, calls.get(), "expired recovery must not call a model or business tool");
                assertEquals(originalDeadline, restored.deadline(handle.id()).orElseThrow());
                assertEquals(originalPlan, fixture.plans.find(planId).orElseThrow());
                var recoveredJournal = fixture.runs.eventsAfter(handle.id(), 0);
                assertEquals(journalBeforeRecovery, recoveredJournal.subList(0, journalBeforeRecovery.size()));
                assertEquals(new RunUsageLedger.UsageSnapshot(3, 2, new BigDecimal("0.15")),
                        RunUsageRecovery.totals(recoveredJournal));
                assertTrue(recoveredJournal.stream().noneMatch(event ->
                        event.type().equals("core.effect.reconciled") || event.type().equals("core.run.resumed")));
                assertEquals("RUN_TIMEOUT", recoveredJournal.getLast().payload().path("code").asText());
                assertEquals(RunState.CANCELLED, restored.resume(handle.id(),
                        new ResumeCommand("user.input", JsonNodeFactory.instance.objectNode()))
                        .completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());

                RunHandle fresh = restored.start(fixture.request(null, timedBudget()));
                assertFalse(fresh.id().equals(handle.id()));
                assertEquals(RunState.COMPLETED,
                        fresh.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
                assertEquals(2, calls.get());
            }
        }
    }

    @Test
    void historicalDeadlineUsesOriginalCreationTimeAndLockedBudget() {
        MutableClock clock = new MutableClock();
        Fixture fixture = new Fixture(clock);
        fixture.publishDefinition(Map.of());
        try (ExtensionManager extensions = fixture.extensionManager()) {
            AgentEngine first = fixture.engine(extensions, request -> CompletableFuture.completedFuture(
                    new ReasoningResult(RunState.PAUSED, null, "MODEL_BLOCKED")));
            RunHandle handle = first.start(fixture.request(null, timedBudget()));
            Instant historicalDeadline = first.get(handle.id()).createdAt().plus(timedBudget().timeout());
            first.close();
            var created = (ObjectNode) fixture.runs.eventsAfter(handle.id(), 0).getFirst().payload().deepCopy();
            created.remove("deadline");
            fixture.jdbc.update("UPDATE agent_run_events SET payload_json=? WHERE run_id=? AND type='core.run.created'",
                    created.toString(), handle.id().value());
            clock.advance(Duration.ofMinutes(31));

            try (AgentEngine restored = fixture.engine(extensions, request ->
                    CompletableFuture.failedFuture(new AssertionError("expired run executed")))) {
                assertEquals(RunState.CANCELLED, restored.get(handle.id()).state());
                assertEquals(historicalDeadline, restored.deadline(handle.id()).orElseThrow());
                assertEquals("RUN_TIMEOUT", fixture.runs.eventsAfter(handle.id(), 0)
                        .getLast().payload().path("code").asText());
            }
        }
    }

    @Test
    void expiredRunDoesNotRequireMissingLockedExtensionToReleaseItsThread() {
        MutableClock clock = new MutableClock();
        Fixture fixture = new Fixture(clock);
        fixture.publishDefinition(Map.of("test.recovery", "1.0.0"));
        RunId id;
        try (ExtensionManager installed = fixture.extensionManager()) {
            installed.publish(List.of(ExtensionArtifact.builtin(new RecoveryExtension())));
            try (AgentEngine first = fixture.engine(installed, request -> CompletableFuture.completedFuture(
                    new ReasoningResult(RunState.PAUSED, null, "MODEL_BLOCKED")))) {
                id = first.start(fixture.request(null, timedBudget())).id();
            }
        }
        clock.advance(Duration.ofMinutes(31));
        try (ExtensionManager empty = fixture.extensionManager();
             AgentEngine restored = fixture.engine(empty, request ->
                     CompletableFuture.failedFuture(new AssertionError("expired run executed")))) {
            assertEquals(RunState.CANCELLED, restored.get(id).state());
            assertTrue(restored.activeTurn(fixture.request(null).scope()).isEmpty());
            assertEquals("RUN_TIMEOUT", fixture.runs.eventsAfter(id, 0)
                    .getLast().payload().path("code").asText());
        }
    }

    @Test
    void deadlineReachedDuringReasoningPublishesTimeoutInsteadOfGenericCancellation() throws Exception {
        MutableClock clock = new MutableClock();
        Fixture fixture = new Fixture(clock);
        fixture.publishDefinition(Map.of());
        CompletableFuture<ReasoningResult> pending = new CompletableFuture<>();
        try (ExtensionManager extensions = fixture.extensionManager();
             AgentEngine engine = fixture.engine(extensions, request -> pending)) {
            RunHandle handle = engine.start(fixture.request(null, timedBudget()));
            clock.advance(Duration.ofMinutes(30));
            pending.complete(ReasoningResult.completed(JsonNodeFactory.instance.objectNode().put("text", "late")));

            assertEquals(RunState.CANCELLED,
                    handle.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
            assertEquals("RUN_TIMEOUT", fixture.runs.eventsAfter(handle.id(), 0)
                    .getLast().payload().path("code").asText());
            assertNull(engine.get(handle.id()).output());
        }
    }

    @Test
    void explicitStopReasonSurvivesSynchronousProviderCancellationAndClockExpiry() throws Exception {
        MutableClock clock = new MutableClock();
        Fixture fixture = new Fixture(clock);
        fixture.publishDefinition(Map.of());
        CompletableFuture<ReasoningResult> pending = new CompletableFuture<>();
        try (ExtensionManager extensions = fixture.extensionManager();
             AgentEngine engine = fixture.engine(extensions, request -> {
                 request.control().onCancel(() -> pending.completeExceptionally(
                         new com.javaclaw.framework.spi.RunCancelledException()));
                 return pending;
             })) {
            RunHandle handle = engine.start(fixture.request(null, timedBudget()));
            clock.advance(Duration.ofMinutes(30));

            assertTrue(engine.cancel(handle.id(), CancelReason.requestedByUser()));

            assertEquals(RunState.CANCELLED,
                    handle.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
            var cancellations = fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.run.cancelled")).toList();
            assertEquals(1, cancellations.size());
            assertEquals("USER_REQUEST", cancellations.getFirst().payload().path("code").asText());
            assertTrue(cancellations.getFirst().payload().path("userInitiated").asBoolean());
        }
    }

    private static RunBudget timedBudget() {
        return new RunBudget(Duration.ofMinutes(30), 100, 100, 4, BigDecimal.valueOf(5));
    }

    @Test
    void recoveringNewPausedTurnRestoresTerminalPredecessorsUnresolvedEffects() throws Exception {
        MutableClock clock = new MutableClock();
        Fixture fixture = new Fixture(clock);
        fixture.publishDefinition(Map.of());
        AtomicInteger calls = new AtomicInteger();
        ReasoningGateway reasoning = request -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                emitDesktopEffect(request, "old-action", "desktop:old-window", "UNKNOWN", "MAYBE_SENT");
                return CompletableFuture.completedFuture(ReasoningResult.completed(null));
            }
            assertEquals(0, request.control().toolCallCount(), "old effects must not spend the new Run's calls");
            assertThrows(ToolPermissionDeniedException.class, () -> request.control().assertRepairRetryAllowed(
                    "fresh-action", "fresh-effect", false, ToolEffectPolicy.OBSERVATION_GATED,
                    "desktop:old-window"));
            return CompletableFuture.completedFuture(request.resumeCommand() == null
                    ? new ReasoningResult(RunState.PAUSED, null, "MODEL_BLOCKED")
                    : ReasoningResult.completed(null));
        };
        try (ExtensionManager extensions = fixture.extensionManager()) {
            RunId newTurn;
            Instant deadline;
            try (AgentEngine first = fixture.engine(extensions, reasoning)) {
                RunHandle predecessor = first.start(fixture.request(null, timedBudget()));
                assertEquals(RunState.COMPLETED, first.get(predecessor.id()).state());
                clock.advance(Duration.ofHours(1));
                RunHandle next = first.start(fixture.request(null, timedBudget()));
                newTurn = next.id();
                deadline = first.deadline(newTurn).orElseThrow();
                assertEquals(RunState.PAUSED, first.get(newTurn).state());
            }
            clock.advance(Duration.ofMinutes(5));
            try (AgentEngine restored = fixture.engine(extensions, reasoning)) {
                assertEquals(deadline, restored.deadline(newTurn).orElseThrow());
                assertEquals(RunState.COMPLETED, restored.resume(newTurn, new ResumeCommand("user.input",
                        JsonNodeFactory.instance.objectNode().put("text", "继续")))
                        .completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
                assertEquals(3, calls.get());
            }
        }
    }

    @Test
    void provenPreviousEffectDoesNotFenceNewTurnAfterRestart() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        AtomicInteger calls = new AtomicInteger();
        ReasoningGateway reasoning = request -> {
            if (calls.incrementAndGet() == 1)
                emitDesktopEffect(request, "old-action", "desktop:exact-window", "VERIFIED", "SENT");
            else {
                assertEquals(0, request.control().toolCallCount());
                assertDoesNotThrow(() -> request.control().assertRepairRetryAllowed("new-action",
                        "new-observation", false, ToolEffectPolicy.OBSERVATION_GATED, "desktop:exact-window"));
                assertThrows(ToolPermissionDeniedException.class,
                        () -> request.control().assertRepairRetryAllowed("old-action", "old-action",
                                false, ToolEffectPolicy.OBSERVATION_GATED, "desktop:exact-window"));
            }
            return CompletableFuture.completedFuture(ReasoningResult.completed(null));
        };
        try (ExtensionManager extensions = fixture.extensionManager()) {
            try (AgentEngine first = fixture.engine(extensions, reasoning)) {
                assertEquals(RunState.COMPLETED, first.start(fixture.request(null))
                        .completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
            }
            try (AgentEngine restored = fixture.engine(extensions, reasoning)) {
                assertEquals(RunState.COMPLETED, restored.start(fixture.request(null))
                        .completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
            }
            assertEquals(2, calls.get());
        }
    }

    @Test
    void newRootInheritsAttachedDescendantsButExcludesUnrelatedDetachedAndDeletedThreads() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        AtomicReference<RunId> parent = new AtomicReference<>();
        ReasoningGateway reasoning = request -> {
            String role = request.runRequest().attributes().getOrDefault("test.role",
                    JsonNodeFactory.instance.textNode("new-root")).asText();
            if (role.equals("old-root")) {
                parent.set(request.runId());
                return CompletableFuture.completedFuture(new ReasoningResult(RunState.PAUSED, null, "MODEL_BLOCKED"));
            } else if (!role.equals("new-root")) {
                emitDesktopEffect(request, "same-call-id", "desktop:" + role, "UNKNOWN", "MAYBE_SENT");
            } else {
                assertThrows(ToolPermissionDeniedException.class, () -> request.control().assertRepairRetryAllowed(
                        "fresh-action", "fresh-effect", false, ToolEffectPolicy.OBSERVATION_GATED,
                        "desktop:attached"));
                for (String excluded : List.of("unrelated", "detached", "deleted"))
                    assertDoesNotThrow(() -> request.control().assertRepairRetryAllowed("new-" + excluded,
                            "effect-" + excluded, false, ToolEffectPolicy.OBSERVATION_GATED,
                            "desktop:" + excluded));
                assertEquals(0, request.control().toolCallCount());
            }
            return CompletableFuture.completedFuture(ReasoningResult.completed(null));
        };
        try (ExtensionManager extensions = fixture.extensionManager()) {
            try (AgentEngine first = fixture.engine(extensions, reasoning)) {
                first.start(fixture.request(null).withAttribute("test.role",
                        JsonNodeFactory.instance.textNode("old-root")));
                for (String role : List.of("attached", "unrelated", "detached", "deleted")) {
                    RunRequest template = fixture.request(null);
                    RunRequest child = RunRequest.builder().agent(template.agent()).profile(template.profile())
                            .source(role.equals("unrelated") ? InvocationSource.chat()
                                    : InvocationSource.subAgent(parent.get().value()))
                            .scope(new RunScope("workspace", "user", role + "-thread"))
                            .input(InputBlock.text(role)).permissionCeiling(PermissionSet.UNRESTRICTED)
                            .linkage(role.equals("unrelated") ? null : new RunLinkage(parent.get(), null, null))
                            .attributes(Map.of("test.role", JsonNodeFactory.instance.textNode(role),
                                    "framework.detached", JsonNodeFactory.instance.booleanNode(role.equals("detached"))))
                            .build();
                    assertEquals(RunState.COMPLETED, first.start(child)
                            .completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
                }
                fixture.jdbc.update("UPDATE agent_threads SET status='DELETED' WHERE workspace_id=? AND user_id=? AND thread_id=?",
                        "workspace", "user", "deleted-thread");
                assertTrue(first.cancel(parent.get(), new CancelReason("TASK_SUPERSEDED", "new goal")));
            }
            try (AgentEngine restored = fixture.engine(extensions, reasoning)) {
                assertEquals(RunState.COMPLETED, restored.start(fixture.request(null))
                        .completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
            }
        }
    }

    private static void emitDesktopEffect(ReasoningRequest request, String invocation, String resource,
                                           String status, String delivery) {
        List<RunEventDraft> started = toolStartBatch(invocation, resource);
        request.control().recordToolCall(invocation);
        request.control().reserveEffect(invocation, invocation, invocation, false,
                ToolEffectPolicy.OBSERVATION_GATED, resource, () ->
                        request.events().toolStarted(started.get(0).payload(), started.get(1).payload()));
        var receipt = JsonNodeFactory.instance.objectNode().put("invocationId", invocation)
                .put("fingerprint", invocation).put("tool", "desktop_session_click")
                .put("operation", "click").put("status", status);
        receipt.putObject("metadata").put("delivery", delivery);
        request.events().emit("core.tool.receipt", 1, "framework.core", receipt);
    }

    @Test
    void approvalEventsUseCanonicalContractAndFingerprintControlsResume() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        var challenge = new com.javaclaw.framework.api.ToolApprovalChallenge(
                "sys_file_delete",
                JsonNodeFactory.instance.objectNode().put("path", "/tmp/test"),
                "fingerprint", "DOUBLE_CONFIRM", "delete file");
        try (ExtensionManager extensions = fixture.extensionManager();
             AgentEngine engine = fixture.engine(extensions, request ->
                     CompletableFuture.completedFuture(request.resumeCommand() == null
                             ? approvalResponse(challenge, "approval required")
                             : ReasoningResult.completed(
                             JsonNodeFactory.instance.objectNode().put("text", "approved"))))) {
            var handle = engine.start(fixture.request(null));
            assertEquals(RunState.WAITING_APPROVAL, engine.get(handle.id()).state());
            var waiting = fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.run.waiting_approval"))
                    .findFirst().orElseThrow();
            assertEquals("approval required", waiting.payload().path("reason").asText());
            assertEquals("sys_file_delete",
                    waiting.payload().path("approval").path("tool").asText());
            assertEquals("fingerprint",
                    waiting.payload().path("approval").path("fingerprint").asText());

            ObjectNode command = JsonNodeFactory.instance.objectNode();
            command.put("approved", true);
            command.put("fingerprint", "fingerprint");
            var resumed = engine.resume(handle.id(),
                    new ResumeCommand("tool.approval", command));
            assertEquals(RunState.COMPLETED,
                    resumed.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
        }
    }

    @Test
    void deniedApprovalCancelsInsteadOfRechallenging() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        var challenge = new com.javaclaw.framework.api.ToolApprovalChallenge(
                "email_send", JsonNodeFactory.instance.objectNode(),
                "fingerprint", "CONFIRM", "send email");
        try (ExtensionManager extensions = fixture.extensionManager();
             AgentEngine engine = fixture.engine(extensions, request ->
                     CompletableFuture.completedFuture(
                             approvalResponse(challenge, "approval required")))) {
            var handle = engine.start(fixture.request(null));
            ObjectNode command = JsonNodeFactory.instance.objectNode();
            command.put("approved", false);
            command.put("fingerprint", "fingerprint");

            var denied = engine.resume(handle.id(),
                    new ResumeCommand("tool.approval", command));

            assertEquals(RunState.CANCELLED,
                    denied.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
            assertEquals(1, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.run.waiting_approval")).count());
        }
    }

    @Test
    void replayDoesNotRestoreAnApprovalThatWasAlreadyUsed() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        var challenge = new com.javaclaw.framework.api.ToolApprovalChallenge(
                "email_send", JsonNodeFactory.instance.objectNode().put("to", "a@example.com"),
                "used-fingerprint", "CONFIRM", "send email");
        AtomicInteger resumedTurns = new AtomicInteger();
        ReasoningGateway reasoning = request -> {
            if (request.resumeCommand() == null) {
                return CompletableFuture.completedFuture(
                        approvalResponse(challenge, "approval"));
            }
            if (resumedTurns.getAndIncrement() == 0) {
                assertNotNull(request.approvedToolInvocation());
                ObjectNode started = JsonNodeFactory.instance.objectNode();
                started.put("tool", challenge.tool());
                started.put("fingerprint", challenge.fingerprint());
                started.put("invocationId", "used-call");
                started.set("arguments", challenge.arguments());
                request.events().emit("core.tool.started", 1, "test", started);
                return CompletableFuture.completedFuture(ReasoningResult.waitingForInput(
                        JsonNodeFactory.instance.objectNode().put("question", "next?"), "input"));
            }
            return CompletableFuture.completedFuture(ReasoningResult.completed(
                    JsonNodeFactory.instance.objectNode().put("text", "continued")));
        };

        try (ExtensionManager extensions = fixture.extensionManager()) {
            AgentEngine first = fixture.engine(extensions, reasoning);
            var handle = first.start(fixture.request(null));
            ObjectNode approval = JsonNodeFactory.instance.objectNode();
            approval.put("approved", true);
            approval.put("fingerprint", challenge.fingerprint());
            first.resume(handle.id(), new ResumeCommand("tool.approval", approval));
            assertEquals(RunState.WAITING_INPUT, first.get(handle.id()).state());
            first.close();

            try (AgentEngine restored = fixture.engine(extensions, reasoning)) {
                assertThrows(IllegalArgumentException.class, () -> restored.resume(
                        handle.id(), new ResumeCommand("tool.approval", approval)));
                var resumed = restored.resume(handle.id(), new ResumeCommand(
                        "user.input", JsonNodeFactory.instance.objectNode().put("text", "yes")));
                assertEquals(RunState.COMPLETED,
                        resumed.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
            }
        }
    }

    @Test
    void durableApprovalWithoutToolStartSurvivesKernelRestartExactlyOnce() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        var challenge = new com.javaclaw.framework.api.ToolApprovalChallenge(
                "sys_file_delete", JsonNodeFactory.instance.objectNode().put("path", "/tmp/test"),
                "pending-fingerprint", "DOUBLE_CONFIRM", "delete file");
        CompletableFuture<ReasoningResult> interruptedTurn = new CompletableFuture<>();
        ReasoningGateway firstReasoning = request -> request.resumeCommand() == null
                ? CompletableFuture.completedFuture(
                        approvalResponse(challenge, "approval"))
                : interruptedTurn;

        try (ExtensionManager extensions = fixture.extensionManager()) {
            AgentEngine first = fixture.engine(extensions, firstReasoning);
            var handle = first.start(fixture.request(null));
            ObjectNode approval = JsonNodeFactory.instance.objectNode();
            approval.put("approved", true);
            approval.put("fingerprint", challenge.fingerprint());
            first.resume(handle.id(), new ResumeCommand("tool.approval", approval));
            assertEquals(RunState.RUNNING, first.get(handle.id()).state());
            first.close();
            interruptedTurn.complete(ReasoningResult.completed(
                    JsonNodeFactory.instance.objectNode().put("text", "detached")));

            ReasoningGateway restoredReasoning = request -> {
                assertNotNull(request.approvedToolInvocation());
                assertEquals(challenge.fingerprint(), request.approvedToolInvocation()
                        .challenge().fingerprint());
                return CompletableFuture.completedFuture(ReasoningResult.completed(
                        JsonNodeFactory.instance.objectNode().put("text", "resumed")));
            };
            try (AgentEngine restored = fixture.engine(extensions, restoredReasoning)) {
                var resumed = restored.resume(handle.id(), new ResumeCommand(
                        "user.input", JsonNodeFactory.instance.objectNode()));
                assertEquals(RunState.COMPLETED,
                        resumed.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
            }
        }
    }

    @Test
    void waitingApprovalRejectsAnUnrelatedResumeCommand() {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        var challenge = new com.javaclaw.framework.api.ToolApprovalChallenge(
                "email_send", JsonNodeFactory.instance.objectNode(),
                "fingerprint", "CONFIRM", "send email");
        try (ExtensionManager extensions = fixture.extensionManager();
             AgentEngine engine = fixture.engine(extensions, request ->
                     CompletableFuture.completedFuture(approvalResponse(challenge, "approval")))) {
            var handle = engine.start(fixture.request(null));
            assertThrows(IllegalArgumentException.class, () -> engine.resume(
                    handle.id(), new ResumeCommand("user.input",
                            JsonNodeFactory.instance.objectNode().put("text", "bypass"))));
            assertEquals(RunState.WAITING_APPROVAL, engine.get(handle.id()).state());
        }
    }

    @Test
    void immediateApprovalFromTheWaitingEventQueuesAfterTheCurrentTurn() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        var challenge = new com.javaclaw.framework.api.ToolApprovalChallenge(
                "email_send", JsonNodeFactory.instance.objectNode(),
                "fast-fingerprint", "CONFIRM", "send email");
        CompletableFuture<ReasoningResult> firstTurn = new CompletableFuture<>();
        ReasoningGateway reasoning = request -> request.resumeCommand() == null
                ? firstTurn
                : CompletableFuture.completedFuture(ReasoningResult.completed(
                JsonNodeFactory.instance.objectNode().put("text", "approved")));
        AtomicReference<Throwable> resumeFailure = new AtomicReference<>();
        try (ExtensionManager extensions = fixture.extensionManager();
             ExecutorService executor = Executors.newSingleThreadExecutor();
             AgentEngine engine = fixture.engine(extensions, reasoning, executor)) {
            var handle = engine.start(fixture.request(null));
            var events = handle.events(0)
                    .filter(event -> event.type().equals("core.run.waiting_approval"))
                    .take(1)
                    .subscribe(event -> {
                        try {
                            ObjectNode approval = JsonNodeFactory.instance.objectNode();
                            approval.put("approved", true);
                            approval.put("fingerprint", challenge.fingerprint());
                            engine.resume(handle.id(), new ResumeCommand(
                                    "tool.approval", approval));
                        } catch (Throwable failure) {
                            resumeFailure.set(failure);
                        }
                    });
            try {
                firstTurn.complete(approvalResponse(challenge, "approval"));

                assertEquals(RunState.COMPLETED,
                        handle.completion().toCompletableFuture()
                                .get(2, TimeUnit.SECONDS).state());
                assertNull(resumeFailure.get());
            } finally {
                events.dispose();
            }
        }
    }

    @Test
    void cancellationRetainsExactExtensionPlanUntilInFlightReasoningExits() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of("test.lifecycle", "=1.0.0"));
        AtomicInteger v1Stops = new AtomicInteger();
        LifecycleExtension v1 = new LifecycleExtension("1.0.0", v1Stops);
        LifecycleExtension v2 = new LifecycleExtension("2.0.0", new AtomicInteger());
        CompletableFuture<ReasoningResult> pending = new CompletableFuture<>();
        try (ExtensionManager extensions = fixture.extensionManager()) {
            extensions.publish(List.of(ExtensionArtifact.builtin(v1)));
            try (AgentEngine engine = fixture.engine(extensions, request -> pending)) {
                var handle = engine.start(fixture.request(null));
                extensions.publish(List.of(ExtensionArtifact.builtin(v2)));

                assertTrue(engine.cancel(handle.id(),
                        new CancelReason("USER_CANCELLED", "stop")));
                assertEquals(RunState.CANCELLED,
                        handle.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
                assertEquals(0, v1Stops.get(),
                        "cancel must not unload code that is still on the execution stack");

                pending.complete(ReasoningResult.completed(
                        JsonNodeFactory.instance.objectNode().put("text", "late")));
                assertEquals(1, v1Stops.get(),
                        "the retired generation is released after reasoning unwinds");
            }
        }
    }

    @Test
    void recoveryBlocksExplicitlyWhenLockedExtensionArtifactIsMissing() {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of("test.recovery", "1.0.0"));
        var installed = fixture.extensionManager();
        installed.publish(List.of(ExtensionArtifact.builtin(new RecoveryExtension())));
        ReasoningGateway waiting = request -> CompletableFuture.completedFuture(
                ReasoningResult.waitingForInput(JsonNodeFactory.instance.objectNode(), "input"));
        var firstEngine = fixture.engine(installed, waiting);
        var handle = firstEngine.start(fixture.request(null));
        firstEngine.close();
        installed.close();

        try (ExtensionManager empty = fixture.extensionManager();
             AgentEngine restored = fixture.engine(empty, waiting)) {
            assertEquals(RunState.RECOVERY_BLOCKED_MISSING_EXTENSION,
                    restored.get(handle.id()).state());
            assertTrue(fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .anyMatch(event -> event.type().equals("core.run.recovery_blocked")
                            && event.payload().path("code").asText()
                            .equals("MISSING_LOCKED_EXTENSION")));
            assertTrue(restored.cancel(handle.id(),
                    new CancelReason("ABANDON_RECOVERY", "artifact intentionally removed")));
        }
    }

    @Test
    void exactExecutionPlanCodecAndSchemaOwnEveryExtensionEvent() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of("test.events", "=1.0.0"));
        try (ExtensionManager extensions = fixture.extensionManager()) {
            extensions.publish(List.of(ExtensionArtifact.builtin(new EventExtension())));
            try (AgentEngine engine = fixture.engine(extensions, request -> {
                request.events().emit("test.events.observed", 1, "test.events",
                        JsonNodeFactory.instance.objectNode().put("value", "normalized"));
                return CompletableFuture.completedFuture(ReasoningResult.completed(
                        JsonNodeFactory.instance.objectNode().put("text", "done")));
            })) {
                var handle = engine.start(fixture.request(null));
                assertEquals(RunState.COMPLETED,
                        handle.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
                var observed = fixture.runs.eventsAfter(handle.id(), 0).stream()
                        .filter(event -> event.type().equals("test.events.observed"))
                        .findFirst().orElseThrow();
                assertEquals("normalized", observed.payload().path("value").asText());
                assertEquals(1, observed.schemaVersion());
            }
        }
    }

    @Test
    void unregisteredExtensionEventFailsRunInsteadOfPoisoningSharedEventStream()
            throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        try (ExtensionManager extensions = fixture.extensionManager();
             AgentEngine engine = fixture.engine(extensions, request -> {
                 request.events().emit("test.unregistered.event", 1, "test",
                         JsonNodeFactory.instance.objectNode());
                 return CompletableFuture.completedFuture(ReasoningResult.completed(
                         JsonNodeFactory.instance.objectNode()));
             })) {
            var handle = engine.start(fixture.request(null));
            var outcome = handle.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(RunState.FAILED, outcome.state());
            assertTrue(outcome.error().contains("event type is not registered"));
            assertFalse(fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .anyMatch(event -> event.type().equals("test.unregistered.event")));
        }
    }

    @Test
    void budgetFailureEventIncludesTheExceededDimensionAndUsage() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        BudgetExceededException failure = BudgetExceededException.modelInputTokens(
                255_392, 250_000);
        try (ExtensionManager extensions = fixture.extensionManager();
             AgentEngine engine = fixture.engine(extensions,
                     request -> CompletableFuture.failedFuture(failure))) {
            var handle = engine.start(fixture.request(null));
            var outcome = handle.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertEquals(RunState.FAILED, outcome.state());
            var failed = fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.run.failed"))
                    .findFirst().orElseThrow();
            assertEquals(BudgetExceededException.class.getName(),
                    failed.payload().path("errorType").asText());
            assertEquals("MODEL_INPUT_TOKENS",
                    failed.payload().path("budgetKind").asText());
            assertEquals("255392", failed.payload().path("budgetActual").asText());
            assertEquals("250000", failed.payload().path("budgetLimit").asText());
        }
    }

    private static final class Fixture {
        private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        private final Clock clock;
        private final JdbcTemplate jdbc;
        private final DataSourceTransactionManager transactions;
        private final JdbcAgentDefinitionStore definitions;
        private final JdbcRunStore runs;
        private final JdbcExecutionPlanStore plans;

        private Fixture() {
            this(Clock.systemUTC());
        }

        private Fixture(Clock clock) {
            this.clock = clock;
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    "jdbc:h2:mem:engine-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
            new SchemaInitializer(dataSource).initialize();
            jdbc = new JdbcTemplate(dataSource);
            transactions = new DataSourceTransactionManager(dataSource);
            definitions = new JdbcAgentDefinitionStore(jdbc, transactions, json, clock);
            runs = new JdbcRunStore(jdbc, transactions, json, clock);
            plans = new JdbcExecutionPlanStore(jdbc, json, clock);
        }

        private ExtensionManager extensionManager() {
            return new ExtensionManager(new ExtensionContext(
                    clock, Runnable::run,
                    request -> CompletableFuture.failedFuture(
                            new AssertionError("model task not expected"))));
        }

        private void publishDefinition(Map<String, String> extensionRanges) {
            AgentDefinitionDraft agent = new AgentDefinitionDraft(
                    "test.agent", "Test Agent", "test:model", Map.of("system", "test"),
                    Map.of(), JsonNodeFactory.instance.objectNode(),
                    JsonNodeFactory.instance.objectNode(), RunBudget.UNBOUNDED,
                    JsonNodeFactory.instance.objectNode(), extensionRanges);
            definitions.saveAgentDraft("workspace", agent, false);
            definitions.publishAgent("workspace", agent.id());
            RunProfileDraft profile = new RunProfileDraft(
                    "test.profile", "Test Profile", PermissionSet.UNRESTRICTED,
                    RunBudget.UNBOUNDED, Map.of(), JsonNodeFactory.instance.objectNode());
            definitions.saveProfileDraft("workspace", profile, false);
            definitions.publishProfile("workspace", profile.id());
        }

        private RunRequest request(String idempotencyKey) {
            return request(idempotencyKey, RunBudget.UNBOUNDED);
        }

        private RunRequest request(String idempotencyKey, RunBudget budget) {
            return RunRequest.builder()
                    .agent(AgentDefinitionRef.latest("test.agent"))
                    .profile(RunProfileRef.latest("test.profile"))
                    .source(InvocationSource.chat())
                    .scope(new RunScope("workspace", "user", "session"))
                    .input(InputBlock.text("hello"))
                    .permissionCeiling(PermissionSet.UNRESTRICTED)
                    .budget(budget)
                    .idempotencyKey(idempotencyKey)
                    .build();
        }

        private AgentEngine engine(ExtensionManager extensions, ReasoningGateway reasoning) {
            return engine(extensions, reasoning, Runnable::run);
        }

        private AgentEngine engine(
                ExtensionManager extensions, ReasoningGateway reasoning, Executor executor) {
            return new AgentEngine(new AgentCompiler(definitions, extensions, json), runs, plans,
                    reasoning, executor, json, clock, new RunUsageLedger());
        }

        private AgentEngine engine(ExtensionManager extensions, ReasoningGateway reasoning,
                InteractionEventSource interactionEvents) {
            return engine(extensions, reasoning, Runnable::run, interactionEvents);
        }

        private AgentEngine engine(ExtensionManager extensions, ReasoningGateway reasoning,
                Executor executor, InteractionEventSource interactionEvents) {
            return new AgentEngine(new AgentCompiler(definitions, extensions, json), runs, plans,
                    reasoning, executor, json, clock, new RunUsageLedger(), null,
                    (request, evidence) -> { }, interactionEvents);
        }

        private AgentEngine harnessEngine(ExtensionManager extensions, ReasoningGateway reasoning) {
            ModelTaskGateway planner = task -> CompletableFuture.failedFuture(
                    new AssertionError("structured task should not call model planner"));
            return new AgentEngine(new AgentCompiler(definitions, extensions, json), runs, plans,
                    withClaimDone(reasoning), Runnable::run, json, clock, new RunUsageLedger(), planner);
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-30T10:00:00Z"));

        private void advance(Duration duration) { now.updateAndGet(value -> value.plus(duration)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return now.get(); }
    }

    private static final class RecoveryExtension implements AgentFrameworkExtension {
        private final ExtensionDescriptor descriptor = new ExtensionDescriptor(
                "test.recovery", SemanticVersion.parse("1.0.0"), ">=2.0.0 <3.0.0",
                ">=2.0.0 <3.0.0", List.of(), Set.of(), ExtensionScope.PLAN_SCOPED,
                HotUpdateCompatibility.PLAN_ISOLATED, 1, Map.of());

        @Override public ExtensionDescriptor descriptor() { return descriptor; }
        @Override public void register(ExtensionRegistrar registrar) { }
    }

    private static final class EventExtension implements AgentFrameworkExtension {
        private final ExtensionDescriptor descriptor = new ExtensionDescriptor(
                "test.events", SemanticVersion.parse("1.0.0"), ">=2.0.0 <3.0.0",
                ">=2.0.0 <3.0.0", List.of(), Set.of(), ExtensionScope.PLAN_SCOPED,
                HotUpdateCompatibility.PLAN_ISOLATED, 1, Map.of());

        @Override public ExtensionDescriptor descriptor() { return descriptor; }

        @Override
        public void register(ExtensionRegistrar registrar) {
            ObjectNode schema = JsonNodeFactory.instance.objectNode();
            schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
            schema.put("type", "object");
            schema.putObject("properties").putObject("value").put("type", "string");
            schema.putArray("required").add("value");
            schema.put("additionalProperties", false);
            registrar.eventType(new EventTypeDescriptor(
                    "test.events.observed", 1, schema), new EventCodec<EventPayload>() {
                @Override
                public JsonNode encode(EventPayload payload) {
                    return JsonNodeFactory.instance.objectNode().put("value", payload.value());
                }

                @Override
                public EventPayload decode(JsonNode payload) {
                    return new EventPayload(payload.path("value").asText());
                }
            });
        }
    }

    private static final class LifecycleExtension implements AgentFrameworkExtension {
        private final ExtensionDescriptor descriptor;
        private final AtomicInteger stops;

        private LifecycleExtension(String version, AtomicInteger stops) {
            this.descriptor = new ExtensionDescriptor(
                    "test.lifecycle", SemanticVersion.parse(version), ">=2.0.0 <3.0.0",
                    ">=2.0.0 <3.0.0", List.of(), Set.of(), ExtensionScope.PLAN_SCOPED,
                    HotUpdateCompatibility.PLAN_ISOLATED, 1, Map.of());
            this.stops = stops;
        }

        @Override public ExtensionDescriptor descriptor() { return descriptor; }
        @Override public void register(ExtensionRegistrar registrar) { }
        @Override public void stop() { stops.incrementAndGet(); }
    }

    private record EventPayload(String value) { }
}
