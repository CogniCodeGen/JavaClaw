package com.javaclaw.runtime;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.ToolCallResult;
import com.javaclaw.api.TurnStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnContinuationBoundaryTest {
    @Test
    void 恢复已提交续接请求时不再次调用模型或外部工具() throws Exception {
        var environment = new RuntimeFixtures.HarnessEnvironment();
        environment.tools = new GovernedToolExecutor() {
            @Override
            public boolean shouldYield(com.javaclaw.api.TurnId turnId) {
                return true;
            }

            @Override
            public ToolExecutionOutcome execute(
                    com.javaclaw.api.ToolCallRequest request,
                    com.javaclaw.api.ToolDescriptor descriptor,
                    com.javaclaw.api.ToolCatalogSnapshot snapshot,
                    com.javaclaw.api.PermissionProfile permissions,
                    com.javaclaw.api.CancellationToken cancellation) {
                throw new AssertionError("持久续接请求不应再次执行外部工具");
            }
        };
        var result = environment
                .harness()
                .execute(environment.command(TurnStatus.RUNNING, RuntimeFixtures.budget()), new CancellationSource());
        assertEquals(TurnStatus.COMPLETED, result.status());
        assertEquals(0, environment.models.invocations.size());
    }

    @Test
    void trustedYieldCommitsUnexecutedResultsForRemainingCallsAndNeverInvokesModelAgain() throws Exception {
        var environment = new RuntimeFixtures.HarnessEnvironment();
        var tool = RuntimeFixtures.tool("core", "read", 1);
        environment.frozenTools.add(tool);
        environment.initialTools.add(tool);
        var first = new ModelToolCall("first", tool.identity(), RuntimeFixtures.payload());
        var second = new ModelToolCall("second", tool.identity(), RuntimeFixtures.payload());
        environment.models.outcomes.add(RuntimeFixtures.tools(List.of(first, second), new ModelUsage(2, 1, 0, 0)));
        AtomicInteger executed = new AtomicInteger();
        environment.tools = (request, descriptor, snapshot, permissions, cancellation) -> {
            executed.incrementAndGet();
            return new ToolExecutionOutcome(
                    new ToolCallResult(request.callId(), true, RuntimeFixtures.payload(), Optional.empty()),
                    List.of(),
                    List.of(),
                    List.of(),
                    true);
        };
        var result = environment
                .harness()
                .execute(environment.command(TurnStatus.QUEUED, RuntimeFixtures.budget()), new CancellationSource());
        assertEquals(TurnStatus.COMPLETED, result.status());
        assertEquals(1, executed.get());
        assertEquals(1, environment.models.invocations.size());
        assertEquals(2, result.toolCalls());
        assertEquals(
                2,
                environment.journal.items.stream()
                        .filter(item -> item.kind().equals("tool-result"))
                        .count());
        assertTrue(environment.journal.items.toString().contains("TURN_CONTINUED"));
    }
}
