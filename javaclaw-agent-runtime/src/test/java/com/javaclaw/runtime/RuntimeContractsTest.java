package com.javaclaw.runtime;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ToolCallResult;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeContractsTest {
    @Test
    void modelUsageAddsIndependentCountersAndRejectsInvalidCounts() {
        ModelUsage usage = new ModelUsage(10, 5, 3, 2).plus(new ModelUsage(4, 2, 1, 1));

        assertEquals(new ModelUsage(14, 7, 4, 3), usage);
        assertEquals(new ModelUsage(0, 0, 0, 0), ModelUsage.zero());
        assertThrows(IllegalArgumentException.class, () -> new ModelUsage(-1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ModelUsage(1, 0, 0, 2));
    }

    @Test
    void messagesEnforceToolRoleIdentityAndAssistantCalls() {
        ModelToolCall call = call("call-1", "read");
        ModelMessage assistant = ModelMessage.assistant("working", List.of(call));
        ModelMessage tool = ModelMessage.tool(" call-1 ", " read ", "result");

        assertEquals(MessageRole.ASSISTANT, assistant.role());
        assertEquals(Optional.of("call-1"), tool.toolCallId());
        assertEquals(Optional.of("read"), tool.toolName());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ModelMessage(MessageRole.TOOL, "result", List.of(), Optional.empty(), Optional.of("read")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ModelMessage(MessageRole.USER, "text", List.of(), Optional.of("call"), Optional.of("read")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ModelMessage(MessageRole.USER, "text", List.of(call), Optional.empty(), Optional.empty()));
    }

    @Test
    void invocationAndResultRequireConsistentToolFinishReason() {
        ModelToolCall call = call("call-1", "read");
        ModelInvocation invocation = new ModelInvocation(
                " model ", "system", List.of(), List.of(RuntimeFixtures.tool("core", "read", 1)), 10);
        ModelInvocationResult toolResult = RuntimeFixtures.tools(List.of(call), new ModelUsage(1, 1, 0, 0));

        assertEquals("model", invocation.modelId());
        assertEquals(ModelFinishReason.TOOL_CALLS, toolResult.finishReason());
        assertThrows(IllegalArgumentException.class, () -> new ModelInvocation(" ", "system", List.of(), List.of(), 1));
        assertThrows(
                IllegalArgumentException.class, () -> new ModelInvocation("model", "system", List.of(), List.of(), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ModelInvocationResult(
                        "",
                        List.of(),
                        ModelUsage.zero(),
                        Optional.empty(),
                        Optional.empty(),
                        ModelFinishReason.TOOL_CALLS));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ModelInvocationResult(
                        "",
                        List.of(call),
                        ModelUsage.zero(),
                        Optional.empty(),
                        Optional.empty(),
                        ModelFinishReason.COMPLETE));
    }

    @Test
    void streamEventsAndToolCallsRejectEmptyPayloads() {
        ModelToolCall call = call("call-1", "read");

        assertEquals("text", new ModelStreamEvent.TextDelta("text").text());
        assertEquals("summary", new ModelStreamEvent.ReasoningSummaryDelta("summary").text());
        assertEquals(call, new ModelStreamEvent.ToolCallReady(call).call());
        assertEquals(ModelUsage.zero(), new ModelStreamEvent.Usage(ModelUsage.zero()).value());
        assertThrows(IllegalArgumentException.class, () -> new ModelStreamEvent.TextDelta(""));
        assertThrows(IllegalArgumentException.class, () -> new ModelStreamEvent.ReasoningSummaryDelta(""));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ModelToolCall(
                        " ", RuntimeFixtures.tool("core", "read", 1).identity(), RuntimeFixtures.payload()));
    }

    @Test
    void providerAndCompactionContractsPreserveOpaqueState() {
        ProviderState state = new ProviderState(" provider ", " v1 ", RuntimeFixtures.payload());
        NativeCompactionRequest request = new NativeCompactionRequest(TurnId.random(), " model ", state);
        NativeCompactionResult result = new NativeCompactionResult(state, 12);
        ConversationWindow window = new ConversationWindow(List.of(), Optional.of(state), 5);
        CompactionOutcome outcome = new CompactionOutcome(
                window, new CorePayloads.Compaction("summary", 5, "done", Optional.of(state.digest())));

        assertEquals(RuntimeFixtures.payload().sha256(), state.digest());
        assertEquals("model", request.modelId());
        assertEquals(12, result.consumedTokens());
        assertEquals(state, outcome.window().providerState().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> new ProviderState(" ", "v1", RuntimeFixtures.payload()));
        assertThrows(IllegalArgumentException.class, () -> new NativeCompactionRequest(TurnId.random(), " ", state));
        assertThrows(IllegalArgumentException.class, () -> new NativeCompactionResult(state, -1));
    }

    @Test
    void conversationWindowAppendsMessagesAndNeverSubtractsEstimate() {
        ConversationWindow original = new ConversationWindow(List.of(), Optional.empty(), 3);
        ConversationWindow appended = original.append(List.of(ModelMessage.assistant("hello", List.of())), -5);
        ProviderState state = new ProviderState("provider", "v1", RuntimeFixtures.payload());

        assertEquals(3, appended.estimatedInputTokens());
        assertEquals(1, appended.messages().size());
        assertEquals(
                state,
                appended.withProviderState(Optional.of(state)).providerState().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> new ConversationWindow(List.of(), Optional.empty(), -1));
    }

    @Test
    void turnCommandAndResultRequireQueuedInputsAndTerminalErrorAgreement() {
        TurnExecutionCommand command = RuntimeFixtures.command(TurnStatus.QUEUED, RuntimeFixtures.budget());
        TurnExecutionResult completed = new TurnExecutionResult(
                command.turn().id(),
                TurnStatus.COMPLETED,
                "done",
                ModelUsage.zero(),
                0,
                Optional.empty(),
                Optional.empty());

        assertEquals("model", command.provider().model());
        assertFalse(completed.errorCode().isPresent());
        assertThrows(
                IllegalArgumentException.class,
                () -> new TurnExecutionResult(
                        TurnId.random(),
                        TurnStatus.RUNNING,
                        "",
                        ModelUsage.zero(),
                        0,
                        Optional.empty(),
                        Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TurnExecutionResult(
                        TurnId.random(),
                        TurnStatus.FAILED,
                        "",
                        ModelUsage.zero(),
                        -1,
                        Optional.empty(),
                        Optional.of("FAILED")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TurnExecutionResult(
                        TurnId.random(),
                        TurnStatus.COMPLETED,
                        "",
                        ModelUsage.zero(),
                        0,
                        Optional.empty(),
                        Optional.of("FAILED")));
    }

    @Test
    void childReservationOutcomeAndStableFailureCodeValidateInputs() {
        ReservedChildBudget reservation = new ReservedChildBudget(10, 5);
        ToolCallResult result = new ToolCallResult("call", true, RuntimeFixtures.payload(), Optional.empty());
        ToolExecutionOutcome outcome = ToolExecutionOutcome.resultOnly(result);
        TurnFailureException failure = new TurnFailureException(" TOOL_FAILED ", "failed");

        assertEquals(10, reservation.inputTokens());
        assertTrue(outcome.revealedTools().isEmpty());
        assertEquals("TOOL_FAILED", failure.code());
        assertThrows(IllegalArgumentException.class, () -> new ReservedChildBudget(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new TurnFailureException(" ", "failed"));
    }

    @Test
    void capabilityValueAndFinishReasonRemainProviderNeutral() {
        ModelCapabilities capabilities = new ModelCapabilities(true, true, false, true, false, true, true);
        TurnBudget budget = new TurnBudget(10, 10, 1, 0, Duration.ofSeconds(1));

        assertTrue(capabilities.streaming());
        assertTrue(capabilities.nativeCompaction());
        assertEquals(0, budget.childThreads());
        assertEquals(5, ModelFinishReason.values().length);
    }

    private static ModelToolCall call(String callId, String toolName) {
        return new ModelToolCall(
                callId, RuntimeFixtures.tool("core", toolName, 1).identity(), RuntimeFixtures.payload());
    }
}
