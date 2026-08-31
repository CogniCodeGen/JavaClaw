package com.javaclaw.agent.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.core.api.AgentThread;
import com.javaclaw.core.api.AgentTurn;
import com.javaclaw.core.api.ApprovalPolicy;
import com.javaclaw.core.api.ThreadEvent;
import com.javaclaw.core.api.ThreadSnapshot;
import com.javaclaw.core.api.TurnConfig;
import com.javaclaw.core.api.TurnInput;
import com.javaclaw.core.api.TurnStartCommand;
import com.javaclaw.core.api.TurnStatus;
import com.javaclaw.core.api.Workspace;
import com.javaclaw.sandbox.api.SandboxPolicy;
import com.javaclaw.testkit.BlockingAgentKernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DefaultAgentRuntimeUnitTest {
    @TempDir
    Path workspaceRoot;

    @Test
    void directoryMaintenanceRejectsActiveTurnsAndBlocksNewThreadsUntilReleased() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var runtime = new DefaultAgentRuntime(
                new InMemoryThreadStore().persistence(),
                (context, sink) -> {
                    entered.countDown();
                    release.await();
                },
                new RuntimeEventBus())) {
            var workspace = runtime.createWorkspace("maintenance", workspaceRoot, "maintenance-workspace");
            var first = runtime.startThread(workspace.id(), "owner");
            var second = runtime.startThread(workspace.id(), "another user Thread");
            try (var lease = runtime.acquireInactiveDirectory(first.id())) {
                // 不仅阻止同一个 Thread；共享目录的新 Thread 或 fork 也不能在清理期间进入 Runtime。
                assertThrows(IllegalStateException.class, () -> runtime.startTurn(command(second, "blocked", "read")));
                assertThrows(IllegalStateException.class, () -> runtime.acquireInactiveDirectory(second.id()));
            }
            var turn = runtime.startTurn(command(second, "accepted", "read"));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> runtime.acquireInactiveDirectory(first.id()));
            release.countDown();
            assertEquals(TurnStatus.COMPLETED, awaitTerminal(runtime, turn).status());
        } finally {
            release.countDown();
        }
    }

    @Test
    void childrenSpendTheParentBudgetAndCannotRunWithoutAnActiveParent() throws Exception {
        CountDownLatch parentEntered = new CountDownLatch(1);
        CountDownLatch childEntered = new CountDownLatch(1);
        CountDownLatch parentRelease = new CountDownLatch(1);
        CountDownLatch childRelease = new CountDownLatch(1);
        AtomicReference<TurnScope> root = new AtomicReference<>();
        AtomicReference<TurnScope> childScope = new AtomicReference<>();
        try (DefaultAgentRuntime service = new DefaultAgentRuntime(
                new InMemoryThreadStore().persistence(),
                (context, sink) -> {
                    if (context.thread().parentThreadId() == null) {
                        root.set(context.scope());
                        parentEntered.countDown();
                        parentRelease.await();
                    } else {
                        childScope.set(context.scope());
                        context.scope().budget().consumeCall("child model");
                        context.scope().budget().chargeTokens(300);
                        childEntered.countDown();
                        childRelease.await();
                    }
                },
                new RuntimeEventBus())) {
            var workspace = service.createWorkspace("budget", workspaceRoot, "workspace-budget");
            var parent = service.startThread(workspace.id(), "parent");
            var child = service.startChildThread(parent.id(), "child", workspaceRoot);
            assertThrows(IllegalStateException.class, () -> service.startTurn(command(child, "orphan", "task")));
            var parentTurn = service.startTurn(command(
                    parent,
                    "parent",
                    "task",
                    Map.of("maxModelCalls", "6", "maxTokens", "1000", "maxDurationSeconds", "30")));
            assertTrue(parentEntered.await(2, TimeUnit.SECONDS));
            var childTurn = service.startTurn(command(
                    child,
                    "child",
                    "task",
                    Map.of("maxModelCalls", "1000", "maxTokens", "10000000", "maxDurationSeconds", "86400")));
            assertTrue(childEntered.await(2, TimeUnit.SECONDS));
            // 子 Profile 的巨大上限不会制造预算；一半余额留给父任务的后续工作和收尾。
            assertEquals(1, root.get().budget().usedCalls());
            assertEquals(300, root.get().budget().usedTokens());
            assertEquals(3, root.get().budget().remainingCalls());
            assertEquals(2, childScope.get().budget().remainingCalls());
            assertEquals(200, childScope.get().budget().remainingTokens());
            assertTrue(childScope.get().remainingNanos()
                    <= root.get().remainingNanos() + TimeUnit.MILLISECONDS.toNanos(10));
            childRelease.countDown();
            awaitTerminal(service, childTurn);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (root.get().budget().remainingCalls() != 5 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertEquals(5, root.get().budget().remainingCalls());
            assertEquals(700, root.get().budget().remainingTokens());
            parentRelease.countDown();
            assertEquals(
                    TurnStatus.COMPLETED, awaitTerminal(service, parentTurn).status());
        } finally {
            parentRelease.countDown();
            childRelease.countDown();
        }
    }

    @Test
    void cancellingAParentCancelsSubagentsButNotUserConversationForks() throws Exception {
        CountDownLatch childEntered = new CountDownLatch(1);
        CountDownLatch forkEntered = new CountDownLatch(1);
        AtomicInteger rootRuns = new AtomicInteger();
        try (DefaultAgentRuntime service = new DefaultAgentRuntime(
                new InMemoryThreadStore().persistence(),
                (context, sink) -> {
                    if (context.thread().parentThreadId() == null && rootRuns.incrementAndGet() == 1) {
                        return;
                    }
                    if (context.thread().forkedFromTurnId() != null) {
                        forkEntered.countDown();
                    } else if (context.thread().parentThreadId() != null) {
                        childEntered.countDown();
                    }
                    new CountDownLatch(1).await();
                },
                new RuntimeEventBus())) {
            var workspace = service.createWorkspace("fork", workspaceRoot, "workspace-fork");
            var parent = service.startThread(workspace.id(), "parent");
            var first = service.startTurn(command(parent, "first", "first"));
            awaitTerminal(service, first);
            var fork = service.forkThread(parent.id(), first.id(), "user fork", workspaceRoot);
            var forkTurn = service.startTurn(command(fork, "fork", "independent"));
            assertTrue(forkEntered.await(2, TimeUnit.SECONDS));
            var parentTurn = service.startTurn(command(parent, "parent-2", "more"));
            var child = service.startChildThread(parent.id(), "subagent", workspaceRoot);
            var childTurn = service.startTurn(command(child, "child", "bounded task"));
            assertTrue(childEntered.await(2, TimeUnit.SECONDS));
            assertTrue(service.interrupt(parentTurn.id()));
            assertEquals(
                    TurnStatus.INTERRUPTED, awaitTerminal(service, childTurn).status());
            assertFalse(service.readTurn(forkTurn.id()).orElseThrow().status().terminal());
            assertTrue(service.interrupt(forkTurn.id()));
        }
    }

    @Test
    void enforcesOneActiveTurnAndBuildsAUnifiedTranscriptWithoutH2() throws Exception {
        BlockingAgentKernel kernel = new BlockingAgentKernel();
        try (DefaultAgentRuntime service =
                new DefaultAgentRuntime(new InMemoryThreadStore().persistence(), kernel, new RuntimeEventBus(32))) {
            Workspace workspace = service.createWorkspace("unit", workspaceRoot, "workspace-key");
            AgentThread thread = service.startThread(workspace.id(), "unit thread");
            TurnStartCommand first = command(thread, "turn-key", "hello");
            AgentTurn accepted = service.startTurn(first);
            assertTrue(kernel.awaitEntered(2, TimeUnit.SECONDS));

            assertEquals(accepted.id(), service.startTurn(first).id());
            assertThrows(IllegalStateException.class, () -> service.startTurn(command(thread, "other-key", "other")));
            assertTrue(service.steer(accepted.id(), new TurnInput.Text("more")));
            assertTrue(service.interrupt(accepted.id()));

            ThreadSnapshot snapshot = service.readThread(thread.id()).orElseThrow();
            assertEquals(TurnStatus.INTERRUPTED, snapshot.turns().getFirst().status());
            assertEquals(
                    List.of("userMessage", "userMessage"),
                    snapshot.items().stream().map(value -> value.item().kind()).toList());
            List<Long> sequences = service.eventsAfter(thread.id(), 0, 100).stream()
                    .map(ThreadEvent::sequence)
                    .toList();
            assertEquals(
                    java.util.stream.LongStream.rangeClosed(1, sequences.size())
                            .boxed()
                            .toList(),
                    sequences);
        }
    }

    private static TurnStartCommand command(AgentThread thread, String idempotencyKey, String text) {
        return command(thread, idempotencyKey, text, Map.of());
    }

    private static TurnStartCommand command(
            AgentThread thread, String idempotencyKey, String text, Map<String, String> attributes) {
        TurnConfig config = new TurnConfig(
                "fake",
                "fake",
                "medium",
                thread.workingDirectory(),
                SandboxPolicy.readOnly(Set.of(thread.workingDirectory()), Set.of()),
                ApprovalPolicy.ON_RISK,
                Set.of(),
                attributes);
        return new TurnStartCommand(thread.id(), List.of(new TurnInput.Text(text)), config, idempotencyKey);
    }

    private static AgentTurn awaitTerminal(DefaultAgentRuntime service, AgentTurn turn) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        AgentTurn current;
        do {
            current = service.readTurn(turn.id()).orElseThrow();
            if (current.status().terminal()) {
                return current;
            }
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Turn did not finish: " + current.status());
    }
}
