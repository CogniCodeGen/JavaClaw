package com.javaclaw.runtime;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.EffectReceipt;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.ToolCallRequest;
import com.javaclaw.api.ToolCallResult;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultTurnHarnessTest {
    @Test
    void completesOneModelInvocationAndPersistsAssistantMessage() throws Exception {
        RuntimeFixtures.HarnessEnvironment environment = new RuntimeFixtures.HarnessEnvironment();
        environment.models.outcomes.add(RuntimeFixtures.complete("done"));

        TurnExecutionResult result = execute(environment);

        assertEquals(TurnStatus.COMPLETED, result.status());
        assertEquals("done", result.assistantText());
        assertEquals(new ModelUsage(2, 3, 0, 0), result.usage());
        assertEquals(50, environment.models.invocations.getFirst().maximumOutputTokens());
        assertEquals(List.of(TurnStatus.RUNNING, TurnStatus.COMPLETED), nextStatuses(environment));
        assertEquals(List.of("message"), itemKinds(environment));
    }

    @Test
    void executesVisibleToolRevealsFrozenToolsAndPersistsReceipt() throws Exception {
        RuntimeFixtures.HarnessEnvironment environment = new RuntimeFixtures.HarnessEnvironment();
        ToolDescriptor read = RuntimeFixtures.tool("core", "read", 1);
        ToolDescriptor search = RuntimeFixtures.tool("core", "search", 1);
        ModelToolCall call = call("call-1", read);
        environment.frozenTools.addAll(List.of(read, search));
        environment.initialTools.add(read);
        environment.models.outcomes.add(RuntimeFixtures.tools(List.of(call), new ModelUsage(2, 1, 0, 0)));
        environment.models.outcomes.add(RuntimeFixtures.complete("finished"));
        environment.tools = (request, descriptor, snapshot, permissions, cancellation) -> {
            ToolCallResult result = resultWithReceipt(request);
            return new ToolExecutionOutcome(result, List.of(search));
        };

        TurnExecutionResult result = execute(environment);

        assertEquals(TurnStatus.COMPLETED, result.status());
        assertEquals(1, result.toolCalls());
        assertEquals(
                List.of(read, search), environment.models.invocations.get(1).tools());
        assertEquals(List.of("tool-call", "tool-result", "effect-receipt", "message"), itemKinds(environment));
        assertTrue(environment.journal.items.stream().allMatch(item -> item.status() == ItemStatus.COMPLETED));
    }

    @Test
    void receiptCheckpointRestoresBudgetAndDoesNotRepeatToolExecution() throws Exception {
        RuntimeFixtures.HarnessEnvironment environment = new RuntimeFixtures.HarnessEnvironment();
        ToolDescriptor read = RuntimeFixtures.tool("core", "read", 1);
        ModelToolCall call = call("call-1", read);
        environment.frozenTools.add(read);
        environment.initialTools.add(read);
        environment.window = new ConversationWindow(
                List.of(
                        ModelMessage.assistant("", List.of(call)),
                        ModelMessage.tool(
                                "call-1", "read", RuntimeFixtures.payload().json())),
                Optional.empty(),
                8);
        ModelUsage committedUsage = new ModelUsage(3, 2, 0, 0);
        environment.journal.recovery = new TurnRecoverySnapshot(
                TurnExecutionPhase.READY_FOR_MODEL,
                committedUsage,
                1,
                1,
                TurnToolBatch.empty(),
                0,
                new TurnVisibleTools(List.of(read.identity())),
                java.util.Set.of("call-1"),
                "",
                Optional.empty());
        environment.models.outcomes.add(RuntimeFixtures.complete("done"));
        AtomicInteger executions = new AtomicInteger();
        environment.tools = (request, descriptor, snapshot, permissions, cancellation) -> {
            executions.incrementAndGet();
            return ToolExecutionOutcome.resultOnly(
                    new ToolCallResult(request.callId(), true, RuntimeFixtures.payload(), Optional.empty()));
        };

        TurnExecutionResult result =
                execute(environment, environment.command(TurnStatus.RUNNING, RuntimeFixtures.budget()));

        assertEquals(TurnStatus.COMPLETED, result.status());
        assertEquals(0, executions.get());
        assertEquals(committedUsage.plus(new ModelUsage(2, 3, 0, 0)), result.usage());
        assertEquals(1, result.toolCalls());
    }

    @Test
    void preModelCheckpointSafelyResumesRunningTurn() throws Exception {
        RuntimeFixtures.HarnessEnvironment environment = new RuntimeFixtures.HarnessEnvironment();
        environment.models.outcomes.add(RuntimeFixtures.complete("resumed"));

        TurnExecutionResult result =
                execute(environment, environment.command(TurnStatus.RUNNING, RuntimeFixtures.budget()));

        assertEquals(TurnStatus.COMPLETED, result.status());
        assertEquals("resumed", result.assistantText());
        assertEquals(1, environment.models.invocations.size());
    }

    @Test
    void inFlightExternalCallsFailClosedWithoutReplayAndKeepBudgetEvidence() throws Exception {
        RuntimeFixtures.HarnessEnvironment model = new RuntimeFixtures.HarnessEnvironment();
        model.journal.recovery = inFlight(TurnExecutionPhase.MODEL_IN_FLIGHT, ModelUsage.zero(), 0);

        TurnExecutionResult modelResult = execute(model, model.command(TurnStatus.RUNNING, RuntimeFixtures.budget()));

        assertFailure("UNKNOWN_OUTCOME", modelResult);
        assertTrue(model.models.invocations.isEmpty());

        RuntimeFixtures.HarnessEnvironment tool = new RuntimeFixtures.HarnessEnvironment();
        AtomicInteger executions = new AtomicInteger();
        tool.tools = (request, descriptor, snapshot, permissions, cancellation) -> {
            executions.incrementAndGet();
            return ToolExecutionOutcome.resultOnly(
                    new ToolCallResult(request.callId(), true, RuntimeFixtures.payload(), Optional.empty()));
        };
        ToolDescriptor read = RuntimeFixtures.tool("core", "read", 1);
        ModelToolCall call = call("unknown-call", read);
        ModelUsage usage = new ModelUsage(4, 2, 1, 0);
        tool.frozenTools.add(read);
        tool.journal.recovery = new TurnRecoverySnapshot(
                TurnExecutionPhase.TOOL_IN_FLIGHT,
                usage,
                1,
                1,
                new TurnToolBatch(List.of(call)),
                0,
                new TurnVisibleTools(List.of(read.identity())),
                java.util.Set.of(call.callId()),
                "",
                Optional.of("b".repeat(64)));

        TurnExecutionResult toolResult = execute(tool, tool.command(TurnStatus.RUNNING, RuntimeFixtures.budget()));

        assertFailure("UNKNOWN_OUTCOME", toolResult);
        assertEquals(usage, toolResult.usage());
        assertEquals(1, toolResult.toolCalls());
        assertEquals(0, executions.get());
    }

    @Test
    void compactsOversizedContextAndRejectsStillOversizedResult() throws Exception {
        RuntimeFixtures.HarnessEnvironment compacted = new RuntimeFixtures.HarnessEnvironment();
        compacted.window = new ConversationWindow(List.of(), Optional.empty(), 101);
        compacted.models.outcomes.add(RuntimeFixtures.complete("done"));

        TurnExecutionResult completed = execute(compacted);

        assertEquals(TurnStatus.COMPLETED, completed.status());
        assertEquals("compaction", compacted.journal.items.getFirst().kind());

        RuntimeFixtures.HarnessEnvironment oversized = new RuntimeFixtures.HarnessEnvironment();
        oversized.window = new ConversationWindow(List.of(), Optional.empty(), 101);
        oversized.compactor = (command, window, gateway, cancellation) -> new CompactionOutcome(
                new ConversationWindow(List.of(), Optional.empty(), 101),
                new com.javaclaw.api.CorePayloads.Compaction("summary", 101, "still large", Optional.empty()));

        assertFailure("BUDGET_EXCEEDED", execute(oversized));
    }

    @Test
    void restoresNativeConversationAndPersistsReturnedState() throws Exception {
        RuntimeFixtures.NativeQueueModelGateway models = new RuntimeFixtures.NativeQueueModelGateway();
        RuntimeFixtures.HarnessEnvironment environment = new RuntimeFixtures.HarnessEnvironment(models);
        ProviderState original = new ProviderState("provider", "v1", RuntimeFixtures.payload());
        ProviderState updated = new ProviderState("provider", "v2", RuntimeFixtures.payload());
        environment.window = new ConversationWindow(List.of(), Optional.of(original), 1);
        models.outcomes.add(result("done", ModelFinishReason.COMPLETE, new ModelUsage(2, 3, 0, 0), updated));

        TurnExecutionResult result = execute(environment);

        assertEquals(updated, result.providerState().orElseThrow());
        assertEquals(List.of(original), models.continuedStates);
        assertEquals(List.of(updated), environment.journal.providerStates);
    }

    @Test
    void rejectsOpaqueStateWhenCapabilityOrNativePortIsMissing() throws Exception {
        ProviderState state = new ProviderState("provider", "v1", RuntimeFixtures.payload());
        RuntimeFixtures.HarnessEnvironment noCapability = new RuntimeFixtures.HarnessEnvironment();
        noCapability.window = new ConversationWindow(List.of(), Optional.of(state), 1);
        assertFailure("PROVIDER_STATE_UNSUPPORTED", execute(noCapability));

        RuntimeFixtures.QueueModelGateway noPort = new RuntimeFixtures.QueueModelGateway();
        noPort.capabilities = new ModelCapabilities(true, true, true, true, true, true, false);
        RuntimeFixtures.HarnessEnvironment unsupported = new RuntimeFixtures.HarnessEnvironment(noPort);
        unsupported.window = new ConversationWindow(List.of(), Optional.of(state), 1);
        assertFailure("PROVIDER_STATE_UNSUPPORTED", execute(unsupported));
    }

    @Test
    void hidesToolsWhenModelCapabilityIsMissing() throws Exception {
        RuntimeFixtures.HarnessEnvironment environment = new RuntimeFixtures.HarnessEnvironment();
        ToolDescriptor tool = RuntimeFixtures.tool("core", "read", 1);
        environment.frozenTools.add(tool);
        environment.initialTools.add(tool);
        environment.models.capabilities = new ModelCapabilities(true, false, true, true, true, false, false);
        environment.models.outcomes.add(RuntimeFixtures.complete("done"));

        assertEquals(TurnStatus.COMPLETED, execute(environment).status());
        assertTrue(environment.models.invocations.getFirst().tools().isEmpty());
    }

    @Test
    void rejectsUnexpectedToolCallFromModelWithoutCapability() throws Exception {
        RuntimeFixtures.HarnessEnvironment environment = new RuntimeFixtures.HarnessEnvironment();
        ToolDescriptor tool = RuntimeFixtures.tool("core", "read", 1);
        environment.frozenTools.add(tool);
        environment.initialTools.add(tool);
        environment.models.capabilities = new ModelCapabilities(true, false, true, true, true, false, false);
        environment.models.outcomes.add(
                RuntimeFixtures.tools(List.of(call("call-1", tool)), new ModelUsage(1, 1, 0, 0)));

        assertFailure("MODEL_TOOL_CALL_UNSUPPORTED", execute(environment));
    }

    @Test
    void rejectsDuplicateUndiscoveredAndMismatchedToolResults() throws Exception {
        RuntimeFixtures.HarnessEnvironment duplicate = toolEnvironment("same", "same");
        assertFailure("DUPLICATE_TOOL_CALL", execute(duplicate));

        RuntimeFixtures.HarnessEnvironment undiscovered = toolEnvironment("hidden");
        undiscovered.initialTools.clear();
        assertFailure("TOOL_NOT_DISCOVERED", execute(undiscovered));

        RuntimeFixtures.HarnessEnvironment mismatch = toolEnvironment("call-1");
        mismatch.tools = (request, descriptor, snapshot, permissions, cancellation) -> ToolExecutionOutcome.resultOnly(
                new ToolCallResult("other", true, RuntimeFixtures.payload(), Optional.empty()));
        assertFailure("TOOL_RESULT_MISMATCH", execute(mismatch));
    }

    @Test
    void rejectsEffectReceiptThatDoesNotDescribeCommittedCall() throws Exception {
        RuntimeFixtures.HarnessEnvironment environment = toolEnvironment("call-1");
        environment.tools = (request, descriptor, snapshot, permissions, cancellation) -> {
            EffectReceipt receipt = new EffectReceipt(
                    request.idempotencyKey(),
                    request.tool().name(),
                    "0".repeat(64),
                    RuntimeFixtures.payload().sha256(),
                    RuntimeFixtures.NOW);
            return ToolExecutionOutcome.resultOnly(
                    new ToolCallResult(request.callId(), true, RuntimeFixtures.payload(), Optional.of(receipt)));
        };

        assertFailure("EFFECT_RECEIPT_MISMATCH", execute(environment));
    }

    @Test
    void mapsModelFinishAndBudgetFailuresToStableCodes() throws Exception {
        assertFailure("MODEL_OUTPUT_TRUNCATED", executeWithResult(result("partial", ModelFinishReason.LENGTH)));
        assertFailure("MODEL_CONTENT_FILTERED", executeWithResult(result("blocked", ModelFinishReason.CONTENT_FILTER)));

        ModelInvocationResult excessive =
                result("large", ModelFinishReason.COMPLETE, new ModelUsage(1, 51, 0, 0), null);
        assertFailure("BUDGET_EXCEEDED", executeWithResult(excessive));

        RuntimeFixtures.HarnessEnvironment exhausted = toolEnvironment("call-1");
        exhausted.models.outcomes.clear();
        ToolDescriptor tool = exhausted.initialTools.getFirst();
        exhausted.models.outcomes.add(RuntimeFixtures.tools(List.of(call("call-1", tool)), new ModelUsage(1, 1, 0, 0)));
        TurnBudget budget = new TurnBudget(100, 1, 2, 1, java.time.Duration.ofMinutes(1));
        assertFailure("BUDGET_EXCEEDED", execute(exhausted, exhausted.command(TurnStatus.QUEUED, budget)));
    }

    @Test
    void mapsCancellationInterruptionAndUnexpectedFailure() throws Exception {
        RuntimeFixtures.HarnessEnvironment cancelled = new RuntimeFixtures.HarnessEnvironment();
        CancellationSource cancellation = new CancellationSource();
        cancellation.cancel("user");
        assertEquals(TurnStatus.CANCELLED, execute(cancelled, cancellation).status());

        RuntimeFixtures.HarnessEnvironment interrupted = new RuntimeFixtures.HarnessEnvironment();
        interrupted.models.outcomes.add(new InterruptedException("interrupted"));
        try {
            assertFailure("TURN_INTERRUPTED", execute(interrupted));
        } finally {
            assertTrue(Thread.interrupted());
        }

        RuntimeFixtures.HarnessEnvironment unexpected = new RuntimeFixtures.HarnessEnvironment();
        unexpected.contexts = (command, token) -> {
            throw new IllegalStateException("broken");
        };
        assertFailure("TURN_EXECUTION_FAILED", execute(unexpected));
    }

    @Test
    void interruptionAfterCancellationReturnsCancelledTerminalState() throws Exception {
        CancellationSource cancellation = new CancellationSource();
        RuntimeFixtures.QueueModelGateway models = new RuntimeFixtures.QueueModelGateway() {
            @Override
            public ModelInvocationResult invoke(
                    TurnId turnId, ModelInvocation invocation, ModelEventSink events, CancellationToken token)
                    throws Exception {
                cancellation.cancel("stop");
                throw new InterruptedException("cancelled");
            }
        };
        RuntimeFixtures.HarnessEnvironment environment = new RuntimeFixtures.HarnessEnvironment(models);
        try {
            assertEquals(
                    TurnStatus.CANCELLED, execute(environment, cancellation).status());
        } finally {
            assertTrue(Thread.interrupted());
        }
    }

    @Test
    void rejectsTurnOutsideQueuedOrRunningState() {
        RuntimeFixtures.HarnessEnvironment environment = new RuntimeFixtures.HarnessEnvironment();

        assertThrows(
                IllegalArgumentException.class,
                () -> environment
                        .harness()
                        .execute(
                                environment.command(TurnStatus.WAITING, RuntimeFixtures.budget()),
                                new CancellationSource()));
        assertFalse(environment.journal.transitions.iterator().hasNext());
    }

    private static RuntimeFixtures.HarnessEnvironment toolEnvironment(String... callIds) {
        RuntimeFixtures.HarnessEnvironment environment = new RuntimeFixtures.HarnessEnvironment();
        ToolDescriptor tool = RuntimeFixtures.tool("core", "read", 1);
        environment.frozenTools.add(tool);
        environment.initialTools.add(tool);
        List<ModelToolCall> calls = java.util.Arrays.stream(callIds)
                .map(callId -> call(callId, tool))
                .toList();
        environment.models.outcomes.add(RuntimeFixtures.tools(calls, new ModelUsage(1, 1, 0, 0)));
        environment.models.outcomes.add(RuntimeFixtures.complete("done"));
        return environment;
    }

    private static TurnExecutionResult executeWithResult(ModelInvocationResult modelResult) throws Exception {
        RuntimeFixtures.HarnessEnvironment environment = new RuntimeFixtures.HarnessEnvironment();
        environment.models.outcomes.add(modelResult);
        return execute(environment);
    }

    private static TurnRecoverySnapshot inFlight(TurnExecutionPhase phase, ModelUsage usage, int toolCalls) {
        return new TurnRecoverySnapshot(
                phase,
                usage,
                toolCalls,
                1,
                TurnToolBatch.empty(),
                0,
                TurnVisibleTools.empty(),
                java.util.Set.of(),
                "",
                Optional.of("a".repeat(64)));
    }

    private static TurnExecutionResult execute(RuntimeFixtures.HarnessEnvironment environment) throws Exception {
        return execute(environment, new CancellationSource());
    }

    private static TurnExecutionResult execute(
            RuntimeFixtures.HarnessEnvironment environment, CancellationSource cancellation) throws Exception {
        return execute(environment, environment.command(TurnStatus.QUEUED, RuntimeFixtures.budget()), cancellation);
    }

    private static TurnExecutionResult execute(
            RuntimeFixtures.HarnessEnvironment environment, TurnExecutionCommand command) throws Exception {
        return execute(environment, command, new CancellationSource());
    }

    private static TurnExecutionResult execute(
            RuntimeFixtures.HarnessEnvironment environment,
            TurnExecutionCommand command,
            CancellationSource cancellation)
            throws Exception {
        return environment.harness().execute(command, cancellation);
    }

    private static ModelToolCall call(String callId, ToolDescriptor tool) {
        return new ModelToolCall(callId, tool.identity(), RuntimeFixtures.payload());
    }

    private static ModelInvocationResult result(String text, ModelFinishReason reason) {
        return result(text, reason, new ModelUsage(1, 1, 0, 0), null);
    }

    private static ModelInvocationResult result(
            String text, ModelFinishReason reason, ModelUsage usage, ProviderState state) {
        return new ModelInvocationResult(
                text, List.of(), usage, Optional.of("summary"), Optional.ofNullable(state), reason);
    }

    private static ToolCallResult resultWithReceipt(ToolCallRequest request) {
        EffectReceipt receipt = new EffectReceipt(
                request.idempotencyKey(),
                request.tool().name(),
                request.arguments().sha256(),
                RuntimeFixtures.payload().sha256(),
                RuntimeFixtures.NOW);
        return new ToolCallResult(request.callId(), true, RuntimeFixtures.payload(), Optional.of(receipt));
    }

    private static List<TurnStatus> nextStatuses(RuntimeFixtures.HarnessEnvironment environment) {
        return environment.journal.transitions.stream()
                .map(RuntimeFixtures.Transition::next)
                .toList();
    }

    private static List<String> itemKinds(RuntimeFixtures.HarnessEnvironment environment) {
        return environment.journal.items.stream()
                .map(RuntimeFixtures.JournalItem::kind)
                .toList();
    }

    private static void assertFailure(String expectedCode, TurnExecutionResult result) {
        assertEquals(TurnStatus.FAILED, result.status());
        assertEquals(Optional.of(expectedCode), result.errorCode());
    }
}
