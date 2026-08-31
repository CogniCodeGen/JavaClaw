package com.javaclaw.agent.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.core.api.AgentThread;
import com.javaclaw.core.api.AgentTurn;
import com.javaclaw.core.api.ApprovalPolicy;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.core.api.TurnConfig;
import com.javaclaw.core.api.TurnInput;
import com.javaclaw.core.api.TurnStartCommand;
import com.javaclaw.core.api.TurnStatus;
import com.javaclaw.core.api.Workspace;
import com.javaclaw.sandbox.api.SandboxPolicy;
import com.javaclaw.server.persistence.H2Persistence;
import com.javaclaw.testkit.BlockingAgentKernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultAgentRuntimeIntegrationTest {
    @TempDir
    Path temporary;

    @Test
    void enforcesOneActiveTurnWhilePreservingIdempotentRetries() throws Exception {
        BlockingAgentKernel kernel = new BlockingAgentKernel();
        try (DefaultAgentRuntime service = service(kernel)) {
            AgentThread thread = startThread(service, "test");
            TurnStartCommand first = command(thread, "request-1", "hello");
            AgentTurn accepted = service.startTurn(first);
            assertTrue(kernel.awaitEntered(2, TimeUnit.SECONDS));

            AgentTurn duplicate = service.startTurn(first);
            assertEquals(accepted.id(), duplicate.id());
            assertThrows(IllegalStateException.class, () -> service.startTurn(command(thread, "request-2", "other")));
            assertEquals(
                    1,
                    service.readThread(thread.id()).orElseThrow().turns().size(),
                    "a rejected turn must not leave a queued record behind");

            assertTrue(service.steer(accepted.id(), new TurnInput.Text("more context")));
            assertTrue(service.interrupt(accepted.id()));
            assertEquals(
                    TurnStatus.INTERRUPTED,
                    service.readThread(thread.id())
                            .orElseThrow()
                            .turns()
                            .getFirst()
                            .status());
            assertEquals(
                    List.of("userMessage", "userMessage"),
                    service.readThread(thread.id()).orElseThrow().items().stream()
                            .map(item -> item.item().kind())
                            .toList());
        }
    }

    @Test
    void enforcesWorkspaceAndSubagentHardQuotasBeforePersistingTurns() throws Exception {
        BlockingAgentKernel kernel = new BlockingAgentKernel();
        H2Persistence store = new H2Persistence(temporary.resolve("quota-data-v4"));
        try (store;
                DefaultAgentRuntime service = new DefaultAgentRuntime(
                        store.runtime(), kernel, new RuntimeEventBus(32), new RuntimeLimits(1, 2))) {
            AgentThread parent = startThread(service, "parent");
            AgentTurn parentTurn = service.startTurn(command(parent, "parent-turn", "seed"));
            assertTrue(kernel.awaitEntered(2, TimeUnit.SECONDS));

            // 配额针对受管子智能体，不把具有独立生命周期的用户 fork 充当测试替身。
            AgentThread firstChild = service.startChildThread(parent.id(), "child-1", parent.workingDirectory());
            AgentThread secondChild = service.startChildThread(parent.id(), "child-2", parent.workingDirectory());
            service.startTurn(command(firstChild, "child-one", "work"));

            assertThrows(
                    IllegalStateException.class, () -> service.startTurn(command(secondChild, "child-two", "work")));
            assertEquals(
                    0,
                    service.readThread(secondChild.id()).orElseThrow().turns().size(),
                    "the rejected child turn is not persisted");
            assertTrue(service.interrupt(parentTurn.id()));
            assertFalse(service.interrupt(parentTurn.id()), "parent turn is already terminal");
        }
    }

    @Test
    void configuredLimitsCannotRaiseBuiltInSafetyCeilings() {
        assertThrows(IllegalArgumentException.class, () -> new RuntimeLimits(5, 8));
        assertThrows(IllegalArgumentException.class, () -> new RuntimeLimits(4, 9));
    }

    @Test
    void publishesACompletedTranscriptAfterKernelRelease() throws Exception {
        BlockingAgentKernel kernel = new BlockingAgentKernel();
        try (DefaultAgentRuntime service = service(kernel)) {
            AgentThread thread = startThread(service, "test");
            AgentTurn accepted = service.startTurn(command(thread, "request-1", "hello"));
            assertTrue(kernel.awaitEntered(2, TimeUnit.SECONDS));
            kernel.release();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            TurnStatus status;
            do {
                status = service.readThread(thread.id())
                        .orElseThrow()
                        .turns()
                        .getFirst()
                        .status();
                if (status.terminal()) {
                    break;
                }
                Thread.sleep(10);
            } while (System.nanoTime() < deadline);

            assertEquals(TurnStatus.COMPLETED, status);
            assertEquals(
                    List.of("userMessage", "agentMessage"),
                    service.readThread(thread.id()).orElseThrow().items().stream()
                            .map(item -> item.item().kind())
                            .toList());
            assertTrue(service.eventsAfter(thread.id(), 0, 100).stream()
                    .anyMatch(event -> "turn/completed".equals(event.type())));
        }
    }

    @Test
    void pausesAndResumesATurnForDurableUserInput() throws Exception {
        CountDownLatch requested = new CountDownLatch(1);
        CompletableFuture<String> response = new CompletableFuture<>();
        com.javaclaw.agent.kernel.AgentKernel kernel = (context, sink) -> {
            sink.append(new ThreadItem.UserInputRequest("input_runtime", "Choose", List.of("yes", "no")));
            requested.countDown();
            sink.append(new ThreadItem.UserInputResponse("input_runtime", response.get(2, TimeUnit.SECONDS), false));
        };
        try (DefaultAgentRuntime service = new DefaultAgentRuntime(
                new H2Persistence(temporary.resolve("input-data-v4")).runtime(), kernel, new RuntimeEventBus(32))) {
            AgentThread thread = startThread(service, "input");
            service.startTurn(command(thread, "input-key", "ask"));
            assertTrue(requested.await(2, TimeUnit.SECONDS));
            assertEquals(
                    TurnStatus.WAITING_FOR_INPUT,
                    service.readThread(thread.id())
                            .orElseThrow()
                            .turns()
                            .getFirst()
                            .status());

            assertTrue(service.respondToUserInput("input_runtime", "yes", false));
            response.complete("yes");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (service.readThread(thread.id())
                                    .orElseThrow()
                                    .turns()
                                    .getFirst()
                                    .status()
                            != TurnStatus.COMPLETED
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }

            var snapshot = service.readThread(thread.id()).orElseThrow();
            assertEquals(TurnStatus.COMPLETED, snapshot.turns().getFirst().status());
            assertEquals(
                    List.of("userMessage", "userInputRequest", "userInputResponse"),
                    snapshot.items().stream().map(item -> item.item().kind()).toList());
        }
    }

    @Test
    void locallyDeniedOrTimedOutApprovalCannotRemainDurablyPending() throws Exception {
        CountDownLatch requested = new CountDownLatch(1);
        CountDownLatch resolve = new CountDownLatch(1);
        com.javaclaw.agent.kernel.AgentKernel kernel = (context, sink) -> {
            sink.append(new ThreadItem.ApprovalRequest("approval_timeout", "test timeout", "HIGH"));
            requested.countDown();
            resolve.await(2, TimeUnit.SECONDS);
            sink.approvalResolved("approval_timeout", false);
            sink.append(new ThreadItem.DynamicToolCall("command", Map.of("status", "denied")));
        };
        try (DefaultAgentRuntime service = new DefaultAgentRuntime(
                new H2Persistence(temporary.resolve("approval-timeout-data-v4")).runtime(),
                kernel,
                new RuntimeEventBus(32))) {
            AgentThread thread = startThread(service, "approval");
            service.startTurn(command(thread, "approval-key", "run"));
            assertTrue(requested.await(2, TimeUnit.SECONDS));
            assertEquals(
                    TurnStatus.WAITING_FOR_APPROVAL,
                    service.readThread(thread.id())
                            .orElseThrow()
                            .turns()
                            .getFirst()
                            .status());
            resolve.countDown();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (service.readThread(thread.id())
                                    .orElseThrow()
                                    .turns()
                                    .getFirst()
                                    .status()
                            != TurnStatus.COMPLETED
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(
                    TurnStatus.COMPLETED,
                    service.readThread(thread.id())
                            .orElseThrow()
                            .turns()
                            .getFirst()
                            .status());
            assertFalse(service.respondToApproval("approval_timeout", true));
            assertTrue(service.eventsAfter(thread.id(), 0, 100).stream()
                    .anyMatch(event -> "approval/resolved".equals(event.type())
                            && "false".equals(event.payload().get("approved"))));
        }
    }

    @Test
    void failureWhileWaitingForApprovalAlwaysReachesATerminalState() throws Exception {
        com.javaclaw.agent.kernel.AgentKernel kernel = (context, sink) -> {
            sink.append(new ThreadItem.ApprovalRequest("approval_failure", "fail after request", "HIGH"));
            throw new IllegalStateException("simulated kernel failure");
        };
        try (DefaultAgentRuntime service = new DefaultAgentRuntime(
                new H2Persistence(temporary.resolve("approval-failure-data-v4")).runtime(),
                kernel,
                new RuntimeEventBus(32))) {
            AgentThread thread = startThread(service, "approval failure");
            service.startTurn(command(thread, "approval-failure-key", "run"));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            TurnStatus status;
            do {
                status = service.readThread(thread.id())
                        .orElseThrow()
                        .turns()
                        .getFirst()
                        .status();
                if (status.terminal()) {
                    break;
                }
                Thread.sleep(10);
            } while (System.nanoTime() < deadline);

            assertEquals(TurnStatus.FAILED, status);
            assertFalse(
                    service.respondToApproval("approval_failure", true),
                    "a terminal turn must reject late approval responses");
            assertEquals(
                    List.of("userMessage", "approvalRequest", "error"),
                    service.readThread(thread.id()).orElseThrow().items().stream()
                            .map(item -> item.item().kind())
                            .toList());
            assertTrue(service.eventsAfter(thread.id(), 0, 100).stream()
                    .anyMatch(event -> "approval/cancelled".equals(event.type())));
        }
    }

    @Test
    void schedulerRejectionIsPersistedAndPublishedAsFailed() {
        H2Persistence store = new H2Persistence(temporary.resolve("scheduler-rejection-data-v4"));
        var rejectedExecutor = Executors.newSingleThreadExecutor();
        rejectedExecutor.shutdown();
        try (store;
                DefaultAgentRuntime service = new DefaultAgentRuntime(
                        store.runtime(),
                        (context, sink) -> {},
                        new RuntimeEventBus(32),
                        RuntimeLimits.defaults(),
                        rejectedExecutor)) {
            AgentThread thread = startThread(service, "rejected");

            assertThrows(
                    java.util.concurrent.RejectedExecutionException.class,
                    () -> service.startTurn(command(thread, "rejected-key", "run")));

            assertEquals(
                    TurnStatus.FAILED,
                    service.readThread(thread.id())
                            .orElseThrow()
                            .turns()
                            .getFirst()
                            .status());
            assertTrue(service.eventsAfter(thread.id(), 0, 100).stream()
                    .anyMatch(event -> "turn/completed".equals(event.type())
                            && TurnStatus.FAILED.name().equals(event.payload().get("status"))));
            assertTrue(store.outbox().unpublishedEvents(10).isEmpty());
        }
    }

    @Test
    void interruptionCancelsDurableUserInputAndRejectsLateResponses() throws Exception {
        CountDownLatch requested = new CountDownLatch(1);
        CountDownLatch waitForever = new CountDownLatch(1);
        com.javaclaw.agent.kernel.AgentKernel kernel = (context, sink) -> {
            sink.append(new ThreadItem.UserInputRequest("input_interrupt", "Wait", List.of()));
            requested.countDown();
            waitForever.await();
        };
        try (DefaultAgentRuntime service = new DefaultAgentRuntime(
                new H2Persistence(temporary.resolve("input-interrupt-data-v4")).runtime(),
                kernel,
                new RuntimeEventBus(32))) {
            AgentThread thread = startThread(service, "input interrupt");
            AgentTurn turn = service.startTurn(command(thread, "input-interrupt-key", "ask"));
            assertTrue(requested.await(2, TimeUnit.SECONDS));

            assertTrue(service.interrupt(turn.id()));

            assertEquals(
                    TurnStatus.INTERRUPTED,
                    service.readThread(thread.id())
                            .orElseThrow()
                            .turns()
                            .getFirst()
                            .status());
            assertFalse(service.respondToUserInput("input_interrupt", "late", false));
            assertTrue(service.eventsAfter(thread.id(), 0, 100).stream()
                    .anyMatch(event -> "userInput/cancelled".equals(event.type())));
        }
    }

    private DefaultAgentRuntime service(BlockingAgentKernel kernel) {
        return new DefaultAgentRuntime(
                new H2Persistence(temporary.resolve("data-v4")).runtime(), kernel, new RuntimeEventBus(32));
    }

    private AgentThread startThread(DefaultAgentRuntime service, String title) {
        Workspace workspace = service.listWorkspaces().stream()
                .findFirst()
                .orElseGet(() -> service.createWorkspace("workspace", temporary, "test-workspace"));
        return service.startThread(workspace.id(), title);
    }

    private TurnStartCommand command(AgentThread thread, String key, String text) {
        return new TurnStartCommand(
                thread.id(),
                List.of(new TurnInput.Text(text)),
                new TurnConfig(
                        "model",
                        "provider",
                        "medium",
                        temporary,
                        SandboxPolicy.readOnly(Set.of(temporary), Set.of()),
                        ApprovalPolicy.ON_RISK,
                        Set.of(),
                        Map.of()),
                key);
    }
}
