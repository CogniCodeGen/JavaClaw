package com.javaclaw.application.agent;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.ToolContext;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class SubAgentApprovalObserverTest {
    @Test void onlyCurrentApprovalIsReplayedAndDuplicateObserversDoNotRequestItTwice() {
        boolean enabled = ToolConfirmationManager.isEnabled();
        ToolConfirmationManager.setEnabled(false);
        try {
            var agents = new Client();
            agents.run.emit("core.run.waiting_approval");
            agents.run.emit("core.run.resumed");
            agents.run.emit("core.run.waiting_approval");
            agents.state = RunState.WAITING_APPROVAL;
            try (var observer = new SubAgentApprovalObserver(() -> agents, Runnable::run)) {
                observer.accept(context("chat", true), agents.run);
                observer.accept(context("chat", true), agents.run);
                assertEquals(1, agents.resumes.size());
                assertEquals("tool.approval", agents.resumes.getFirst().type());
                assertEquals(1, agents.run.subscriptions);
                agents.run.completion.complete(new RunOutcome(agents.run.id, RunState.COMPLETED, null, null));
                agents.run.emit("core.run.waiting_approval");
                observer.accept(context("chat", true), agents.run);
                assertEquals(1, agents.resumes.size());
                assertEquals(1, agents.run.subscriptions);
            }
        } finally { ToolConfirmationManager.setEnabled(enabled); }
    }

    @Test void historicalApprovalsAreSkippedAndClosingRejectsLateSubscription() {
        var agents = new Client();
        agents.run.emit("core.run.waiting_approval");
        agents.run.emit("core.run.resumed");
        List<Runnable> pending = new ArrayList<>();
        var observer = new SubAgentApprovalObserver(() -> agents, pending::add);
        observer.accept(context("schedule", false), agents.run);
        assertTrue(pending.isEmpty());
        agents.run.emit("core.model.started");
        assertTrue(pending.isEmpty());
        agents.run.emit("core.run.waiting_approval");
        assertEquals(1, pending.size());
        observer.close();
        agents.run.emit("core.run.waiting_approval");
        observer.accept(context("schedule", false), agents.run);
        agents.run.completion.complete(new RunOutcome(agents.run.id, RunState.CANCELLED, null, null));
        pending.forEach(Runnable::run);
        assertEquals(1, pending.size());
        assertEquals(1, agents.run.subscriptions);
        assertTrue(agents.resumes.isEmpty());
    }

    @Test void approvalOriginPreservesEachEntryBoundaryAndNeverBorrowsAnotherTaskIdentity() {
        assertSame(ToolCallOrigin.INTERACTIVE, SubAgentApprovalObserver.origin(context("chat", true)));
        assertSame(ToolCallOrigin.INTERACTIVE, SubAgentApprovalObserver.origin(context("plan", false)));
        ToolCallOrigin scheduled = SubAgentApprovalObserver.origin(context("schedule", true));
        assertEquals(ToolCallOrigin.Kind.SCHEDULED, scheduled.kind());
        assertEquals("task", scheduled.taskId());
        assertNull(scheduled.workDir());
        for (String source : List.of("loop", "sdd", "workflow")) {
            ToolCallOrigin managed = SubAgentApprovalObserver.origin(context(source, true));
            assertEquals(ToolCallOrigin.Kind.MANAGED_TASK, managed.kind());
            assertEquals("task", managed.taskId());
            assertEquals("/project/task", managed.workDir());
        }
        assertNull(SubAgentApprovalObserver.origin(context("workflow", false)).workDir());
        assertSame(ToolCallOrigin.UNKNOWN, SubAgentApprovalObserver.origin(context("plugin", true)));
    }

    private static ToolContext context(String kind, boolean directory) {
        RunScope scope = new RunScope("workspace", "user", "parent");
        RunRequest request = RunRequest.builder().agent(AgentDefinitionRef.latest("system.default"))
                .profile(RunProfileRef.latest("chat")).source(new InvocationSource(kind, "task"))
                .scope(scope).input(InputBlock.text("parent task"))
                .attributes(directory ? Map.of("workDir", JsonNodeFactory.instance.textNode("/project/task")) : Map.of())
                .build();
        return new ToolContext(RunId.random(), scope, PermissionSet.NONE, () -> false, Instant.MAX, request);
    }

    private static final class Client implements AgentClient {
        private final Handle run = new Handle();
        private final List<ResumeCommand> resumes = new ArrayList<>();
        private RunState state = RunState.RUNNING;
        @Override public RunHandle start(RunRequest request) { return run; }
        @Override public RunHandle resume(RunId id, ResumeCommand command) { resumes.add(command); return run; }
        @Override public boolean cancel(RunId id, CancelReason reason) { return true; }
        @Override public RunSnapshot get(RunId id) {
            return new RunSnapshot(id, state, "plan", run.sequence, Instant.EPOCH, Instant.EPOCH, null, null, 1);
        }
    }
    private static final class Handle implements RunHandle {
        private final RunId id = RunId.random();
        private final Sinks.Many<RunEventEnvelope> events = Sinks.many().replay().all();
        private final CompletableFuture<RunOutcome> completion = new CompletableFuture<>();
        private long sequence;
        private int subscriptions;
        @Override public RunId id() { return id; }
        @Override public Flux<RunEventEnvelope> events(long after) {
            subscriptions++;
            return events.asFlux().filter(event -> event.sequence() > after);
        }
        @Override public java.util.concurrent.CompletionStage<RunOutcome> completion() { return completion; }
        private void emit(String type) {
            var challenge = new ToolApprovalChallenge("sys_file_write", JsonNodeFactory.instance.objectNode(),
                    "fingerprint", "CONFIRM", "write file");
            var payload = JsonNodeFactory.instance.objectNode().set("approval", challenge.toJson());
            assertEquals(Sinks.EmitResult.OK, events.tryEmitNext(new RunEventEnvelope(id.value(), ++sequence,
                    Instant.EPOCH, type, 1, "test", null, null, payload)));
        }
    }
}
