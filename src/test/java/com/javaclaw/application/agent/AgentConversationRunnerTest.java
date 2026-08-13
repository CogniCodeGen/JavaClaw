package com.javaclaw.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.api.conversation.CancellationReason;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.api.conversation.ConversationHandle;
import com.javaclaw.api.conversation.ConversationOutcome;
import com.javaclaw.framework.api.AgentClient;
import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.CancelReason;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.ResumeCommand;
import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunHandle;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunLinkage;
import com.javaclaw.framework.api.RunOutcome;
import com.javaclaw.framework.api.RunProfileRef;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.api.RunSnapshot;
import com.javaclaw.framework.api.RunState;
import com.javaclaw.framework.api.ToolApprovalChallenge;
import com.javaclaw.framework.api.BudgetExceededException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentConversationRunnerTest {

    @Test
    void projectsEverySupportedEventShapeAndCompletesExactlyOnce() {
        FakeAgentClient agents = new FakeAgentClient();
        TestRunHandle run = agents.enqueue("events");
        RecordingCallbacks callbacks = new RecordingCallbacks();
        AgentConversationRunner runner = new AgentConversationRunner(agents, Runnable::run);

        runner.start(request("session-events"), ToolCallOrigin.INTERACTIVE, callbacks);
        assertTrue(runner.isRunning());

        run.emit("core.model.started", object());
        run.emit("core.tool.started", object().put("tool", "sys_file_read"));
        run.emit("core.tool.completed", object()
                .put("tool", "text-tool").put("output", "plain"));
        run.emit("core.tool.completed", object()
                .put("tool", "object-tool").set("output", object().put("ok", true)));
        run.emit("core.tool.completed", object().put("tool", "missing-output"));
        run.emit("core.tool.completed", object()
                .put("tool", "null-output").set("output", JsonNodeFactory.instance.nullNode()));
        run.emit("core.tool.failed", object().put("message", "synthetic failure"));
        run.emit("core.run.waiting_input", object().put("reason", "need answer"));

        ObjectNode textReply = object();
        textReply.set("output", object().put("text", "first reply"));
        run.emit("core.run.completed", textReply);
        ObjectNode fallbackReply = object();
        fallbackReply.set("output", object().put("text", "").put("value", "fallback reply"));
        run.emit("core.run.completed", fallbackReply);
        ObjectNode emptyReply = object();
        emptyReply.set("output", object().put("text", "").put("value", ""));
        run.emit("core.run.completed", emptyReply);
        run.emit("vendor.assessment", object().put("score", 1));
        run.emit("vendor.progress", object().put("step", 2));
        run.emit("core.run.started", object());

        run.complete(RunState.COMPLETED, null);
        assertFalse(runner.isRunning());
        assertTrue(callbacks.outcomes.getFirst() instanceof ConversationOutcome.Completed);
        assertEquals(1, callbacks.outcomes.size());
        assertTrue(callbacks.events.stream().anyMatch(event ->
                event instanceof ConversationEvent.ToolResult result
                        && result.toolName().equals("text-tool")
                        && result.result().equals("plain")));
        assertTrue(callbacks.events.stream().anyMatch(event ->
                event instanceof ConversationEvent.ToolResult result
                        && result.toolName().equals("object-tool")
                        && result.result().equals("{\"ok\":true}")));
        assertTrue(callbacks.events.stream().anyMatch(event ->
                event instanceof ConversationEvent.ToolResult result
                        && result.toolName().equals("missing-output")
                        && result.result().isEmpty()));
        assertTrue(callbacks.events.stream().anyMatch(event ->
                event instanceof ConversationEvent.Reply reply
                        && reply.chunk().equals("first reply")));
        assertTrue(callbacks.events.stream().anyMatch(event ->
                event instanceof ConversationEvent.Reply reply
                        && reply.chunk().equals("fallback reply")));
        assertEquals(2, callbacks.events.stream()
                .filter(ConversationEvent.Custom.class::isInstance).count());
    }

    @Test
    void waitingApprovalUsesCanonicalGrantAndMalformedEventsCancelSafely() {
        boolean previousEnabled = ToolConfirmationManager.isEnabled();
        try {
            ToolConfirmationManager.setEnabled(false);
            FakeAgentClient agents = new FakeAgentClient();
            TestRunHandle run = agents.enqueue("approval");
            RecordingCallbacks callbacks = new RecordingCallbacks();
            AgentConversationRunner runner = new AgentConversationRunner(agents, Runnable::run);
            runner.start(request("approval-session"), null, callbacks);

            ToolApprovalChallenge challenge = new ToolApprovalChallenge(
                    "sys_file_write", object().put("path", "target/out.txt"),
                    "approval-fingerprint", "CONFIRM", "write file");
            ObjectNode waiting = object().put("reason", "approval required");
            waiting.set("approval", challenge.toJson());
            run.emit("core.run.waiting_approval", waiting);

            assertEquals(1, agents.resumes.size());
            ResumeCommand command = agents.resumes.getFirst().command();
            assertTrue(command.payload().path("approved").asBoolean());
            assertEquals("approval-fingerprint",
                    command.payload().path("fingerprint").asText());
            assertFalse(command.payload().path("humanApproved").asBoolean());
            assertTrue(callbacks.events.stream().anyMatch(event ->
                    event instanceof ConversationEvent.Hint hint
                            && hint.text().contains("sys_file_write")));

            run.emit("core.run.waiting_approval", object());
            assertTrue(agents.cancellations.stream().anyMatch(cancel ->
                    cancel.reason().code().equals("TOOL_APPROVAL_EVENT_INVALID")));
            assertTrue(callbacks.events.stream().anyMatch(event ->
                    event instanceof ConversationEvent.Hint hint
                            && hint.text().contains("unknown")));

            run.complete(RunState.CANCELLED, null);
            assertFalse(runner.isRunning());
        } finally {
            ToolConfirmationManager.setEnabled(previousEnabled);
        }
    }

    @Test
    void clarificationWaitingInputIsProjectedAndTheSpentRunIsCancelled() {
        FakeAgentClient agents = new FakeAgentClient();
        TestRunHandle run = agents.enqueue("clarify");
        RecordingCallbacks callbacks = new RecordingCallbacks();
        AgentConversationRunner runner = new AgentConversationRunner(agents, Runnable::run);
        runner.start(request("clarify-session"), ToolCallOrigin.INTERACTIVE, callbacks);

        ObjectNode clarification = object().put("kind", "clarify_request");
        clarification.set("payload", object()
                .put("reason", "缺少格式")
                .put("question", "请选择 PDF 或 Markdown"));
        run.emit("core.tool.completed", object()
                .put("tool", "ask_user_clarification")
                .put("waitingInput", true)
                .set("output", clarification));
        ObjectNode waiting = object().put("reason", "缺少格式");
        waiting.set("output", clarification);
        run.emit("core.run.waiting_input", waiting);

        ConversationEvent.Custom event = callbacks.events.stream()
                .filter(ConversationEvent.Custom.class::isInstance)
                .map(ConversationEvent.Custom.class::cast)
                .findFirst().orElseThrow();
        assertEquals("clarify_request", event.kind());
        assertEquals("请选择 PDF 或 Markdown", event.payload().path("question").asText());
        assertFalse(callbacks.events.stream().anyMatch(value ->
                value instanceof ConversationEvent.ToolResult result
                        && result.toolName().equals("ask_user_clarification")));
        assertTrue(agents.cancellations.stream().anyMatch(cancel ->
                cancel.runId().equals(run.id())
                        && cancel.reason().code().equals("CLARIFICATION_REQUESTED")));

        run.complete(RunState.CANCELLED, null);
        assertInstanceOf(ConversationOutcome.Cancelled.class, callbacks.outcomes.getFirst());
        assertFalse(runner.isRunning());
    }

    @Test
    void mapsCancelledFailedAndExceptionalCompletions() {
        FakeAgentClient agents = new FakeAgentClient();
        AgentConversationRunner runner = new AgentConversationRunner(agents, Runnable::run);

        TestRunHandle cancelled = agents.enqueue("cancelled");
        RecordingCallbacks cancelledCallbacks = new RecordingCallbacks();
        runner.start(request("cancelled"), null, cancelledCallbacks);
        cancelled.complete(RunState.CANCELLED, null);
        assertInstanceOf(ConversationOutcome.Cancelled.class,
                cancelledCallbacks.outcomes.getFirst());

        TestRunHandle failedWithMessage = agents.enqueue("failed-message");
        RecordingCallbacks messageCallbacks = new RecordingCallbacks();
        runner.start(request("failed-message"), ToolCallOrigin.UNKNOWN, messageCallbacks);
        failedWithMessage.complete(RunState.FAILED, "framework failure");
        ConversationOutcome.Failed messageFailure = assertInstanceOf(
                ConversationOutcome.Failed.class, messageCallbacks.outcomes.getFirst());
        assertEquals("framework failure", messageFailure.error().getMessage());

        TestRunHandle failedWithoutMessage = agents.enqueue("failed-default");
        RecordingCallbacks defaultCallbacks = new RecordingCallbacks();
        runner.start(request("failed-default"), ToolCallOrigin.UNKNOWN, defaultCallbacks);
        failedWithoutMessage.complete(RunState.FAILED, null);
        ConversationOutcome.Failed defaultFailure = assertInstanceOf(
                ConversationOutcome.Failed.class, defaultCallbacks.outcomes.getFirst());
        assertEquals("Agent run failed", defaultFailure.error().getMessage());

        TestRunHandle structuredBudget = agents.enqueue("failed-budget");
        RecordingCallbacks budgetCallbacks = new RecordingCallbacks();
        runner.start(request("failed-budget"), ToolCallOrigin.UNKNOWN, budgetCallbacks);
        structuredBudget.emit("core.run.failed", object()
                .put("errorType", BudgetExceededException.class.getName())
                .put("message", "model input token budget exceeded: used=255392, limit=250000")
                .put("budgetKind", "MODEL_INPUT_TOKENS")
                .put("budgetActual", "255392")
                .put("budgetLimit", "250000"));
        structuredBudget.complete(RunState.FAILED,
                BudgetExceededException.class.getName() + ": model input token budget exceeded");
        BudgetExceededException budgetFailure = assertInstanceOf(
                BudgetExceededException.class,
                assertInstanceOf(ConversationOutcome.Failed.class,
                        budgetCallbacks.outcomes.getFirst()).error());
        assertEquals(BudgetExceededException.Kind.MODEL_INPUT_TOKENS, budgetFailure.kind());
        assertEquals("255392", budgetFailure.actual());
        assertEquals("250000", budgetFailure.limit());

        TestRunHandle legacyBudget = agents.enqueue("failed-legacy-budget");
        RecordingCallbacks legacyBudgetCallbacks = new RecordingCallbacks();
        runner.start(request("failed-legacy-budget"), ToolCallOrigin.UNKNOWN,
                legacyBudgetCallbacks);
        legacyBudget.complete(RunState.FAILED,
                "com.javaclaw.framework.core.BudgetExceededException: "
                        + "model usage budget exceeded");
        assertInstanceOf(BudgetExceededException.class,
                assertInstanceOf(ConversationOutcome.Failed.class,
                        legacyBudgetCallbacks.outcomes.getFirst()).error());

        IllegalStateException root = new IllegalStateException("root cause");
        TestRunHandle completionWrapped = agents.enqueue("completion-wrapped");
        RecordingCallbacks completionCallbacks = new RecordingCallbacks();
        runner.start(request("completion-wrapped"), ToolCallOrigin.UNKNOWN, completionCallbacks);
        completionWrapped.completion.completeExceptionally(new CompletionException(root));
        assertEquals(root, assertInstanceOf(ConversationOutcome.Failed.class,
                completionCallbacks.outcomes.getFirst()).error());

        TestRunHandle executionWrapped = agents.enqueue("execution-wrapped");
        RecordingCallbacks executionCallbacks = new RecordingCallbacks();
        runner.start(request("execution-wrapped"), ToolCallOrigin.UNKNOWN, executionCallbacks);
        executionWrapped.completion.completeExceptionally(new ExecutionException(root));
        assertEquals(root, assertInstanceOf(ConversationOutcome.Failed.class,
                executionCallbacks.outcomes.getFirst()).error());

        TestRunHandle directFailure = agents.enqueue("direct-failure");
        RecordingCallbacks directCallbacks = new RecordingCallbacks();
        runner.start(request("direct-failure"), ToolCallOrigin.UNKNOWN, directCallbacks);
        directFailure.completion.completeExceptionally(root);
        assertEquals(root, assertInstanceOf(ConversationOutcome.Failed.class,
                directCallbacks.outcomes.getFirst()).error());
        assertFalse(runner.isRunning());
    }

    @Test
    void startAndEventSubscriptionFailuresProduceTerminalFailures() {
        FakeAgentClient agents = new FakeAgentClient();
        agents.startFailure = new IllegalStateException("start rejected");
        AgentConversationRunner runner = new AgentConversationRunner(agents, Runnable::run);
        RecordingCallbacks startCallbacks = new RecordingCallbacks();

        ConversationHandle rejected = runner.start(
                request("start-failure"), ToolCallOrigin.UNKNOWN, startCallbacks);
        assertTrue(rejected.isTerminal());
        assertEquals("start rejected", assertInstanceOf(ConversationOutcome.Failed.class,
                startCallbacks.outcomes.getFirst()).error().getMessage());

        agents.startFailure = null;
        TestRunHandle attachFailure = agents.enqueue("attach-failure");
        attachFailure.eventsFailure = new IllegalStateException("events unavailable");
        RecordingCallbacks attachCallbacks = new RecordingCallbacks();
        ConversationHandle failedAttach = runner.start(
                request("attach-failure"), ToolCallOrigin.UNKNOWN, attachCallbacks);
        assertTrue(failedAttach.isTerminal());
        assertEquals("events unavailable", assertInstanceOf(ConversationOutcome.Failed.class,
                attachCallbacks.outcomes.getFirst()).error().getMessage());
        assertTrue(agents.cancellations.stream().anyMatch(cancel ->
                cancel.reason().code().equals("ADAPTER_START_FAILED")));

        TestRunHandle streamFailure = agents.enqueue("stream-failure");
        RecordingCallbacks streamCallbacks = new RecordingCallbacks();
        runner.start(request("stream-failure"), ToolCallOrigin.UNKNOWN, streamCallbacks);
        streamFailure.events.tryEmitError(new IllegalArgumentException("stream failed"));
        assertEquals("stream failed", assertInstanceOf(ConversationOutcome.Failed.class,
                streamCallbacks.outcomes.getFirst()).error().getMessage());
        streamFailure.complete(RunState.COMPLETED, null);

        runner.close();
        RecordingCallbacks closedCallbacks = new RecordingCallbacks();
        ConversationHandle closed = runner.start(
                request("closed"), ToolCallOrigin.UNKNOWN, closedCallbacks);
        assertTrue(closed.isTerminal());
        assertFalse(closed.cancel(CancellationReason.USER_REQUEST));
        assertTrue(assertInstanceOf(ConversationOutcome.Failed.class,
                closedCallbacks.outcomes.getFirst()).error().getMessage().contains("closed"));
    }

    @Test
    void cancellationCanTargetOneSessionOrAllActiveRuns() {
        FakeAgentClient agents = new FakeAgentClient();
        TestRunHandle first = agents.enqueue("first");
        TestRunHandle second = agents.enqueue("second");
        AgentConversationRunner runner = new AgentConversationRunner(agents, Runnable::run);
        ConversationHandle firstHandle = runner.start(
                request("session-a"), ToolCallOrigin.INTERACTIVE, new RecordingCallbacks());
        runner.start(request("session-b"), ToolCallOrigin.INTERACTIVE,
                new RecordingCallbacks());

        assertThrows(NullPointerException.class,
                () -> runner.cancelSession(null, CancellationReason.SESSION_SWITCH));
        assertTrue(runner.cancelSession("session-a", CancellationReason.SESSION_SWITCH));
        assertFalse(runner.cancelSession("missing", CancellationReason.SESSION_SWITCH));
        assertTrue(firstHandle.cancel(CancellationReason.USER_REQUEST));
        assertFalse(firstHandle.cancel(CancellationReason.USER_REQUEST));
        assertTrue(runner.cancel(CancellationReason.RUNTIME_REBUILD));
        assertTrue(runner.isRunning());

        runner.close();
        runner.close();
        assertTrue(agents.cancellations.stream().anyMatch(cancel ->
                cancel.reason().code().equals(CancellationReason.SHUTDOWN.name())));
        first.complete(RunState.CANCELLED, null);
        second.complete(RunState.CANCELLED, null);
        assertFalse(runner.isRunning());
    }

    private static RunRequest request(String sessionId) {
        return RunRequest.builder()
                .agent(AgentDefinitionRef.latest("system.default"))
                .profile(RunProfileRef.latest("chat"))
                .source(InvocationSource.chat())
                .scope(new RunScope("workspace", "user", sessionId))
                .input(InputBlock.text("hello"))
                .linkage(RunLinkage.root(null))
                .permissionCeiling(PermissionSet.NONE)
                .budget(RunBudget.UNBOUNDED)
                .build();
    }

    private static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    private static final class RecordingCallbacks implements ConversationCallbacks {
        private final List<ConversationEvent> events = new ArrayList<>();
        private final List<ConversationOutcome> outcomes = new ArrayList<>();

        @Override public void onEvent(ConversationEvent event) { events.add(event); }
        @Override public void onTerminal(ConversationOutcome outcome) { outcomes.add(outcome); }
    }

    private static final class TestRunHandle implements RunHandle {
        private final RunId id;
        private final Sinks.Many<RunEventEnvelope> events = Sinks.many().replay().all();
        private final CompletableFuture<RunOutcome> completion = new CompletableFuture<>();
        private long sequence;
        private RuntimeException eventsFailure;

        private TestRunHandle(String id) {
            this.id = new RunId(id);
        }

        @Override public RunId id() { return id; }

        @Override
        public Flux<RunEventEnvelope> events(long afterSequence) {
            if (eventsFailure != null) throw eventsFailure;
            return events.asFlux().filter(event -> event.sequence() > afterSequence);
        }

        @Override public CompletionStage<RunOutcome> completion() { return completion; }

        private void emit(String type, JsonNode payload) {
            Sinks.EmitResult result = events.tryEmitNext(new RunEventEnvelope(
                    id.value(), ++sequence, Instant.EPOCH, type, 1,
                    "test", null, null, payload));
            assertEquals(Sinks.EmitResult.OK, result);
        }

        private void complete(RunState state, String error) {
            completion.complete(new RunOutcome(id, state, null, error));
        }
    }

    private static final class FakeAgentClient implements AgentClient {
        private final ArrayDeque<TestRunHandle> starts = new ArrayDeque<>();
        private final Map<RunId, TestRunHandle> runs = new HashMap<>();
        private final List<CancelCall> cancellations = new ArrayList<>();
        private final List<ResumeCall> resumes = new ArrayList<>();
        private RuntimeException startFailure;

        private TestRunHandle enqueue(String id) {
            TestRunHandle handle = new TestRunHandle(id);
            starts.add(handle);
            runs.put(handle.id(), handle);
            return handle;
        }

        @Override
        public RunHandle start(RunRequest request) {
            if (startFailure != null) throw startFailure;
            return starts.removeFirst();
        }

        @Override
        public RunHandle resume(RunId runId, ResumeCommand command) {
            resumes.add(new ResumeCall(runId, command));
            return runs.get(runId);
        }

        @Override
        public boolean cancel(RunId runId, CancelReason reason) {
            cancellations.add(new CancelCall(runId, reason));
            return runs.containsKey(runId);
        }

        @Override public RunSnapshot get(RunId runId) { return null; }
    }

    private record CancelCall(RunId runId, CancelReason reason) {}
    private record ResumeCall(RunId runId, ResumeCommand command) {}
}
