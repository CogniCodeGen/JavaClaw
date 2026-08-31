package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.agent.automation.AutomationKind;
import com.javaclaw.agent.automation.AutomationRepository;
import com.javaclaw.agent.automation.AutomationService;
import com.javaclaw.agent.conversation.ProfileRepository;
import com.javaclaw.agent.conversation.ProfileService;
import com.javaclaw.agent.runtime.DefaultAgentRuntime;
import com.javaclaw.agent.runtime.RuntimeEventBus;
import com.javaclaw.core.api.ApprovalPolicy;
import com.javaclaw.core.api.ProfileKind;
import com.javaclaw.core.api.TurnId;
import com.javaclaw.sandbox.api.SandboxMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomationLifecycleTest {
    @TempDir
    Path temporary;

    @Test
    void concurrentStartReusesOneThreadAndTerminalJournalStateUnlocksEditing() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (var store = new H2Persistence(temporary.resolve("data"));
                var runtime = new DefaultAgentRuntime(
                        store.runtime(), (context, sink) -> release.await(), new RuntimeEventBus());
                var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var workspace = runtime.createWorkspace("automation", temporary.resolve("workspace"), "workspace");
            var profiles = profiles(store);
            var repository = new H2AutomationRepository(store.database());
            var service = service(repository, profiles, runtime);
            var draft = new AutomationRepository.AutomationDraft(
                    "aut_test",
                    AutomationKind.LOOP,
                    "loop",
                    workspace.id().value(),
                    "profile_loop",
                    "bounded test task",
                    "{}");
            service.putAutomation(draft, 0, "save");
            var first = workers.submit(() -> service.startAutomation("aut_test", "start-key"));
            var second = workers.submit(() -> service.startAutomation("aut_test", "start-key"));
            var accepted = first.get(3, TimeUnit.SECONDS);
            assertEquals(accepted.id(), second.get(3, TimeUnit.SECONDS).id());
            assertEquals(1, runtime.listThreads(true).size());
            assertEquals("RUNNING", service.readAutomation("aut_test").status());
            release.countDown();
            awaitTerminal(runtime, accepted.id());
            var completed = service.readAutomation("aut_test");
            assertEquals("COMPLETED", completed.status());
            assertNull(completed.activeTurnId());
            var updated = service.putAutomation(draft, completed.revision(), "edit-after-run");
            assertEquals(completed.revision() + 1, updated.revision());
            assertEquals(
                    accepted.id(),
                    service.startAutomation("aut_test", "start-key").id());
            assertEquals(
                    1,
                    runtime.readThread(accepted.threadId())
                            .orElseThrow()
                            .turns()
                            .size());
        } finally {
            release.countDown();
        }
    }

    @Test
    void manualAndTimerOverlapShareStableThreadAndIdempotentReplayIsNotSkip() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (var store = new H2Persistence(temporary.resolve("schedule-data"));
                var runtime = new DefaultAgentRuntime(
                        store.runtime(), (context, sink) -> release.await(), new RuntimeEventBus());
                var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var workspace = runtime.createWorkspace("schedule", temporary.resolve("schedule-workspace"), "workspace");
            var repository = new H2AutomationRepository(store.database());
            var service = service(repository, profiles(store), runtime);
            service.putSchedule(
                    new AutomationRepository.ScheduleDraft(
                            "sch_test",
                            "daily",
                            workspace.id().value(),
                            "profile_schedule",
                            "bounded summary",
                            "0 0 9 * * ?",
                            "Asia/Shanghai",
                            true),
                    0,
                    "schedule-save");
            var first = workers.submit(() -> service.triggerSchedule("sch_test", true, "timer-fire"));
            var second = workers.submit(() -> service.triggerSchedule("sch_test", false, "manual-fire"));
            var results = List.of(first.get(3, TimeUnit.SECONDS), second.get(3, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(result -> result.skipped()).count());
            assertEquals(1, runtime.listThreads(true).size());
            String successfulKey = results.getFirst().skipped() ? "manual-fire" : "timer-fire";
            var replay = service.triggerSchedule("sch_test", false, successfulKey);
            assertFalse(replay.skipped());
            assertEquals(
                    results.stream()
                            .filter(result -> !result.skipped())
                            .findFirst()
                            .orElseThrow()
                            .turn()
                            .id(),
                    replay.turn().id());
            assertEquals(
                    1,
                    runtime.readThread(replay.turn().threadId())
                            .orElseThrow()
                            .turns()
                            .size());
        } finally {
            release.countDown();
        }
    }

    private static ProfileService profiles(H2Persistence store) {
        var profiles = new ProfileService(new H2ProfileRepository(store.database()), Set.of());
        for (ProfileKind kind : List.of(ProfileKind.LOOP, ProfileKind.SCHEDULE)) {
            String id = "profile_" + kind.name().toLowerCase(java.util.Locale.ROOT);
            profiles.put(
                    new ProfileRepository.ProfileDraft(
                            id, id, kind, "fake", "fake", "", Set.of(), SandboxMode.READ_ONLY, 4, 4, Map.of()),
                    0,
                    "save-" + id);
        }
        return profiles;
    }

    @Test
    void resumeCreatesANewTurnButDoesNotRepeatTheCompletedLoopStepOrResetItsBudget() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var answers = new java.util.concurrent.atomic.AtomicInteger();
        var kernel = new com.javaclaw.agent.kernel.AgentLoopKernel(
                request -> {
                    calls.incrementAndGet();
                    return new com.javaclaw.core.api.ModelResponse(
                            "已生成结果，等待真实确认", "", List.of(), new com.javaclaw.core.api.ModelUsage(10, 10, 0));
                },
                (context, events) -> new com.javaclaw.agent.tool.TurnToolSession() {
                    @Override
                    public List<com.javaclaw.core.api.ToolDescriptor> availableTools() {
                        return List.of();
                    }

                    @Override
                    public com.javaclaw.core.api.ToolExecutionResult execute(com.javaclaw.core.api.ModelToolCall call) {
                        throw new AssertionError("本场景不需要外部工具");
                    }
                },
                List.of(),
                com.javaclaw.agent.context.AttachmentInputResolver.UNAVAILABLE,
                null,
                (request, announce) -> {
                    announce.run();
                    return answers.incrementAndGet() == 1
                            ? new com.javaclaw.agent.tool.UserInputGateway.Response("", true)
                            : new com.javaclaw.agent.tool.UserInputGateway.Response("确认", false);
                });
        try (var store = new H2Persistence(temporary.resolve("resume-data"));
                var runtime = new DefaultAgentRuntime(store.runtime(), kernel, new RuntimeEventBus())) {
            var workspace = runtime.createWorkspace("resume", temporary.resolve("resume-workspace"), "workspace");
            var service = service(new H2AutomationRepository(store.database()), profiles(store), runtime);
            service.putAutomation(
                    new AutomationRepository.AutomationDraft(
                            "resume-loop",
                            AutomationKind.LOOP,
                            "需确认的 Loop",
                            workspace.id().value(),
                            "profile_loop",
                            "完成一次任务",
                            "{}"),
                    0,
                    "save");
            var first = service.startAutomation("resume-loop", "first");
            awaitTerminal(runtime, first.id());
            assertEquals(
                    com.javaclaw.core.api.TurnStatus.INTERRUPTED,
                    runtime.readTurn(first.id()).orElseThrow().status());
            var resumed = service.resumeAutomation("resume-loop", "resume");
            awaitTerminal(runtime, resumed.id());
            assertEquals(
                    com.javaclaw.core.api.TurnStatus.COMPLETED,
                    runtime.readTurn(resumed.id()).orElseThrow().status());
            assertEquals(1, calls.get(), "已完成的模型步骤不应因等待确认而重复执行");
            assertEquals(2, answers.get());
            assertEquals("3", resumed.config().attributes().get("maxModelCalls"));
            assertEquals(first.threadId(), resumed.threadId());
            assertEquals(
                    resumed.id(),
                    service.resumeAutomation("resume-loop", "resume").id());
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class, () -> service.resumeAutomation("resume-loop", "again"));
        }
    }

    private static AutomationService service(
            H2AutomationRepository repository, ProfileService profiles, DefaultAgentRuntime runtime) {
        return new AutomationService(repository, runtime, runtime, runtime, (profileId, workspace, requiredKind) -> {
            var resolved = profiles.resolve(profileId, workspace, ApprovalPolicy.NEVER, "medium");
            assertEquals(requiredKind, resolved.profile().kind());
            return resolved;
        });
    }

    private static void awaitTerminal(DefaultAgentRuntime runtime, TurnId turnId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!runtime.readTurn(turnId).orElseThrow().status().terminal() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(runtime.readTurn(turnId).orElseThrow().status().terminal());
    }
}
