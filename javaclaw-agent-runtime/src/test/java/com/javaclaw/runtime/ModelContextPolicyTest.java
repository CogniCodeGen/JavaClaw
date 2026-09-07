package com.javaclaw.runtime;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.ToolCallResult;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelContextPolicyTest {
    @Test
    void 模型窗口独立于累计费用且每次工具回传后重新压缩() throws Exception {
        var environment = new RuntimeFixtures.HarnessEnvironment();
        var read = RuntimeFixtures.tool("core", "read", 1);
        environment.frozenTools.add(read);
        environment.initialTools.add(read);
        var calls = new AtomicInteger();
        environment.compactor = (command, window, gateway, cancellation) -> {
            calls.incrementAndGet();
            return new CompactionOutcome(
                    new ConversationWindow(List.of(), Optional.empty(), 1),
                    new CorePayloads.Compaction("test", window.estimatedInputTokens(), "摘要", Optional.empty()));
        };
        environment.tools = (request, descriptor, snapshot, permissions, cancellation) ->
                ToolExecutionOutcome.resultOnly(new ToolCallResult(
                        request.callId(),
                        true,
                        new com.javaclaw.api.CanonicalPayload("{\"value\":\"" + "长结果".repeat(100) + "\"}"),
                        Optional.empty()));
        for (int index = 0; index < 2; index++) {
            environment.models.outcomes.add(RuntimeFixtures.tools(
                    List.of(new ModelToolCall("call-" + index, read.identity(), RuntimeFixtures.payload())),
                    new ModelUsage(20, 1, 0, 0)));
        }
        environment.models.outcomes.add(RuntimeFixtures.complete("完成"));
        var original =
                environment.command(TurnStatus.QUEUED, new TurnBudget(10_000, 1_000, 4, 0, Duration.ofMinutes(1)));
        var command = new TurnExecutionCommand(
                original.turn(),
                original.provider(),
                original.instructions(),
                original.userMessage(),
                original.effectivePermissions(),
                original.toolCatalog(),
                new ModelContextPolicy(256, 128, "TEST", ContextTokenEstimator.VERSION));

        var result = environment.harness().execute(command, new CancellationSource());

        assertEquals(TurnStatus.COMPLETED, result.status());
        assertEquals(2, calls.get());
        assertEquals(42, result.usage().inputTokens());
        assertTrue(
                environment.models.invocations.stream().allMatch(invocation -> invocation.maximumOutputTokens() <= 64));
    }

    @Test
    void 原生预留排斥子任务且超估算的实际账单可以恢复但不能继续() {
        var account = new BudgetAccount(RuntimeFixtures.budget(), RuntimeFixtures.CLOCK);
        try (var reservation = account.reserveModel(90, 40)) {
            assertEquals(10, account.remainingInputTokens());
            assertThrows(BudgetExceededException.class, () -> account.reserveChild(new ReservedChildBudget(20, 1)));
        }
        var observed = new ModelUsage(101, 20, 31, 0);
        assertFalse(account.recordObservedUsage(observed));
        var recovered =
                new BudgetAccount(RuntimeFixtures.budget(), RuntimeFixtures.CLOCK, RuntimeFixtures.NOW, observed, 0);
        assertEquals(0, recovered.remainingOutputTokens());
        assertThrows(BudgetExceededException.class, () -> recovered.checkpoint(new CancellationSource()));
    }

    @Test
    void 中文和工具参数计入估算且未知窗口是显式保守策略() {
        assertEquals(4, ContextTokenEstimator.text("中文测试"));
        assertEquals(1, ContextTokenEstimator.text("abcd"));
        var call =
                new ModelToolCall("id", RuntimeFixtures.tool("core", "read", 1).identity(), RuntimeFixtures.payload());
        assertTrue(ContextTokenEstimator.messages(List.of(ModelMessage.assistant("", List.of(call)))) > 4);
        assertEquals(32_768, ModelContextPolicy.fallback().windowTokens());
        assertEquals("PLATFORM_FALLBACK_V1", ModelContextPolicy.fallback().source());
    }
}
