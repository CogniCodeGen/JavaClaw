package com.javaclaw.application.agent;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.framework.api.AgentClient;
import com.javaclaw.framework.api.CancelReason;
import com.javaclaw.framework.api.ResumeCommand;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunHandle;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunOutcome;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunSnapshot;
import com.javaclaw.framework.api.ToolApprovalChallenge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameworkToolApprovalCoordinatorTest {

    private boolean previousEnabled;
    private UserInteractionPort previousPort;
    private Object previousSettings;

    @BeforeEach
    void isolateConfirmationPolicy() throws Exception {
        previousEnabled = ToolConfirmationManager.isEnabled();
        previousPort = ToolConfirmationManager.getPort();
        Field settings = ToolConfirmationManager.class.getDeclaredField("settings");
        settings.setAccessible(true);
        previousSettings = settings.get(null);
        settings.set(null, null);
        ToolConfirmationManager.setEnabled(true);
        ToolConfirmationManager.setPort(null);
    }

    @AfterEach
    void restoreConfirmationPolicy() throws Exception {
        ToolConfirmationManager.setEnabled(previousEnabled);
        ToolConfirmationManager.setPort(previousPort);
        Field settings = ToolConfirmationManager.class.getDeclaredField("settings");
        settings.setAccessible(true);
        settings.set(null, previousSettings);
    }

    @Test
    void malformedDeniedAndApprovedEventsHaveExplicitOutcomes() {
        FakeAgents agents = new FakeAgents();
        StubHandle handle = new StubHandle("approval-run");

        FrameworkToolApprovalCoordinator.resolve(
                agents, handle, ToolCallOrigin.INTERACTIVE, object());
        assertEquals("TOOL_APPROVAL_EVENT_INVALID",
                agents.cancellations.getFirst().code());

        ToolConfirmationManager.setPort(new DecisionPort(false));
        FrameworkToolApprovalCoordinator.resolve(
                agents, handle, ToolCallOrigin.INTERACTIVE,
                event(new ToolApprovalChallenge(
                        "sys_file_write", object(), "deny-fingerprint",
                        "CONFIRM", "write file")));
        assertEquals("TOOL_APPROVAL_DENIED", agents.cancellations.getLast().code());

        ToolConfirmationManager.setPort(new DecisionPort(true));
        FrameworkToolApprovalCoordinator.resolve(
                agents, handle, ToolCallOrigin.INTERACTIVE,
                event(new ToolApprovalChallenge(
                        "sys_file_write", object().put("path", "target/file.txt"),
                        "human-fingerprint", "CONFIRM", "write file")));
        ResumeCommand human = agents.resumes.getFirst();
        assertTrue(human.payload().path("approved").asBoolean());
        assertEquals("human-fingerprint", human.payload().path("fingerprint").asText());
        assertTrue(human.payload().path("humanApproved").asBoolean());

        ToolConfirmationManager.setEnabled(false);
        FrameworkToolApprovalCoordinator.resolve(
                agents, handle, null,
                event(new ToolApprovalChallenge(
                        "sys_file_write", JsonNodeFactory.instance.arrayNode(),
                        "auto-fingerprint", "CONFIRM", "write file")));
        ResumeCommand automatic = agents.resumes.getLast();
        assertFalse(automatic.payload().path("humanApproved").asBoolean());
    }

    @Test
    void resumeFailureCancelsTheWaitingRun() {
        FakeAgents agents = new FakeAgents();
        agents.resumeFailure = new IllegalStateException("resume rejected");
        ToolConfirmationManager.setEnabled(false);

        FrameworkToolApprovalCoordinator.resolve(
                agents, new StubHandle("resume-failure"), ToolCallOrigin.UNKNOWN,
                event(new ToolApprovalChallenge(
                        "sys_file_write", object(), "resume-fingerprint",
                        "CONFIRM", "write file")));

        assertEquals("TOOL_APPROVAL_RESUME_FAILED",
                agents.cancellations.getFirst().code());
        assertTrue(agents.cancellations.getFirst().detail().contains("resume rejected"));
    }

    private static ObjectNode event(ToolApprovalChallenge challenge) {
        ObjectNode payload = object().put("reason", "approval required");
        payload.set("approval", challenge.toJson());
        return payload;
    }

    private static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    private static final class DecisionPort implements UserInteractionPort {
        private final boolean decision;

        private DecisionPort(boolean decision) {
            this.decision = decision;
        }

        @Override public boolean confirm(ConfirmRequest request) { return decision; }
        @Override public void notify(ToastRequest request) {}
    }

    private static final class FakeAgents implements AgentClient {
        private final List<CancelReason> cancellations = new ArrayList<>();
        private final List<ResumeCommand> resumes = new ArrayList<>();
        private RuntimeException resumeFailure;

        @Override public RunHandle start(RunRequest request) { throw new UnsupportedOperationException(); }

        @Override
        public RunHandle resume(RunId runId, ResumeCommand command) {
            if (resumeFailure != null) throw resumeFailure;
            resumes.add(command);
            return new StubHandle(runId.value());
        }

        @Override
        public boolean cancel(RunId runId, CancelReason reason) {
            cancellations.add(reason);
            return true;
        }

        @Override public RunSnapshot get(RunId runId) { return null; }
    }

    private record StubHandle(RunId id) implements RunHandle {
        private StubHandle(String id) { this(new RunId(id)); }
        @Override public Flux<RunEventEnvelope> events(long afterSequence) { return Flux.empty(); }
        @Override public CompletionStage<RunOutcome> completion() {
            return new CompletableFuture<>();
        }
    }
}
