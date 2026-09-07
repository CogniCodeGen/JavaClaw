package com.javaclaw.runtime;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextBoundaryTest {
    @Test
    void invalidPolicyAndCompactionIdentityCannotEnterARestoredWindow() {
        assertThrows(IllegalArgumentException.class, () -> policy(3, 1, "TEST"));
        assertThrows(IllegalArgumentException.class, () -> policy(128, 0, "TEST"));
        assertThrows(IllegalArgumentException.class, () -> policy(128, 129, "TEST"));
        assertThrows(IllegalArgumentException.class, () -> policy(128, 32, " "));
        assertThrows(IllegalArgumentException.class, () -> new ModelContextPolicy(128, 32, "TEST", "unknown"));
        assertThrows(IllegalArgumentException.class, () -> new CompactionTicket(0, "a".repeat(64), false));
        assertThrows(IllegalArgumentException.class, () -> new CompactionTicket(1, "changed", true));
        var command = RuntimeFixtures.command(TurnStatus.QUEUED, RuntimeFixtures.budget());
        var window = new ConversationWindow(List.of(), Optional.empty(), 10, 5);
        assertThrows(IllegalArgumentException.class, () -> new CompactionRequest(command, window, 0, 5));
        assertThrows(IllegalArgumentException.class, () -> new CompactionRequest(command, window, 10, -1));
        assertThrows(IllegalArgumentException.class, () -> new ConversationWindow(List.of(), Optional.empty(), 10, -1));
        assertThrows(IllegalArgumentException.class, () -> new ConversationWindow(List.of(), Optional.empty(), 10, 11));
        var continued = window.append(
                List.of(new ModelMessage(
                        com.javaclaw.api.MessageRole.USER, "new", List.of(), Optional.empty(), Optional.empty())),
                4);
        assertEquals(5, continued.withProviderState(Optional.empty()).fixedInputTokens());
        assertEquals(14, continued.estimatedInputTokens());
        assertTrue(policy(129, 32, "TEST").inputCapacity(32) < 129 - 32);
    }

    @Test
    void fixedInstructionsOverBudgetStopBeforeCompactionOrModelInvocation() throws Exception {
        var environment = new RuntimeFixtures.HarnessEnvironment();
        var count = new AtomicInteger();
        environment.compactor = (command, window, model, token) -> {
            count.incrementAndGet();
            throw new AssertionError("不可通过压缩丢弃固定指令");
        };
        var command = command(environment, 30, "固定".repeat(60));
        var result = environment.harness().execute(command, new CancellationSource());
        assertEquals(TurnStatus.FAILED, result.status());
        assertEquals(0, count.get());
        assertTrue(environment.models.invocations.isEmpty());
    }

    @Test
    void softThresholdDoesNotDiscardFixedInstructionsWhenTheRequestStillFits() throws Exception {
        var environment = new RuntimeFixtures.HarnessEnvironment();
        environment.window = new ConversationWindow(List.of(), Optional.empty(), 80);
        environment.compactor = (command, window, model, token) -> {
            throw new AssertionError("固定指令超过压缩目标时，仍可容纳的完整窗口必须保留");
        };
        environment.models.outcomes.add(RuntimeFixtures.complete("保留了完整指令"));
        var result =
                environment.harness().execute(command(environment, 1000, "x".repeat(240)), new CancellationSource());
        assertEquals(TurnStatus.COMPLETED, result.status());
        assertEquals(1, environment.models.invocations.size());
    }

    @Test
    void unsuccessfulCompactionStopsOnceWithoutRepeatedCallsOrRefundingUsage() throws Exception {
        var environment = new RuntimeFixtures.HarnessEnvironment();
        environment.window = new ConversationWindow(List.of(), Optional.empty(), 100);
        var calls = new AtomicInteger();
        environment.compactor = (command, window, model, token) -> {
            calls.incrementAndGet();
            return new CompactionOutcome(
                    new ConversationWindow(List.of(), Optional.empty(), 1000),
                    new CorePayloads.Compaction("fixture", 100, "仍无法容纳", Optional.empty()));
        };
        var result = environment.harness().execute(command(environment, 1000, "system"), new CancellationSource());
        assertEquals(TurnStatus.FAILED, result.status());
        assertEquals(1, calls.get());
        assertTrue(environment.models.invocations.isEmpty());
    }

    private static ModelContextPolicy policy(long window, long output, String source) {
        return new ModelContextPolicy(window, output, source, ContextTokenEstimator.VERSION);
    }

    private static TurnExecutionCommand command(
            RuntimeFixtures.HarnessEnvironment environment, long input, String instructions) {
        var original = environment.command(TurnStatus.QUEUED, new TurnBudget(input, 100, 4, 0, Duration.ofMinutes(1)));
        return new TurnExecutionCommand(
                original.turn(),
                original.provider(),
                new ModelInstructions(instructions, "", ""),
                original.userMessage(),
                original.effectivePermissions(),
                original.toolCatalog(),
                policy(128, 32, "TEST"));
    }
}
