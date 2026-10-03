package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.AgentStep;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.StepId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OnDemandDesktopSessionRecoveryTest {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final RunId RUN = new RunId("session-recovery");
    private static final String OBSERVE = "desktop_session_observe";

    @Test
    void invalidNameAndGuessedUuidRecoverFromBoundHostFailuresWithoutReusingEitherToken() {
        for (String invalid : List.of("QQ", "8cf50eba-8701-4aec-97e5-c5d1a23953d5", "Reader")) {
            var step = failure(invalid, "ACCESS_DENIED");
            var events = List.of(receipt(RUN, "framework.core", OBSERVE, "FAILED", 2));
            var cursor = ComputerUseSessionCursor.derive(List.of(step), events, null, List.of(inventory()));
            assertEquals(ComputerUseSessionCursor.Phase.RECOVER_SESSION, cursor.phase());
            assertEquals("desktop_session_targets", cursor.requiredTool());
            assertEquals("", cursor.sessionId());
            assertEquals("", cursor.observationId());
            assertFalse(cursor.inputAllowed());
            assertTrue(cursor.sessionExpired());
        }
    }

    @Test
    void missingLiveHandlePreflightReplacesObserveWithReadOnlyDiscovery() {
        var bootstrap = ComputerUseSessionCursor.derive(List.of(), List.of(), null, List.of(inventory()));
        var preflight = ComputerUseContextSelection.beforeSessionUse(bootstrap,
                List.of(OBSERVE), List.of(inventory()));
        assertNotNull(preflight);
        assertEquals("desktop_session_targets", preflight.requiredTool());
        assertEquals("", preflight.sessionId());
        assertFalse(preflight.inputAllowed());
        assertNull(ComputerUseContextSelection.beforeSessionUse(bootstrap,
                List.of("sys_file_read"), List.of(inventory())), "unrelated work is not desktop work");
        assertNull(ComputerUseContextSelection.beforeSessionUse(bootstrap,
                List.of(OBSERVE), List.of()), "an unknown inventory cannot prove expiry");
    }

    @Test
    void permissionFailureOnAStillLiveSessionIsNotSessionExpiry() {
        var live = inventory();
        live.withArray("sessionIds").add("live-session");
        assertEquals(0, sequence(failure("live-session", "ACCESS_DENIED"),
                receipt(RUN, "framework.core", OBSERVE, "FAILED", 2), List.of(live)));
    }

    @Test
    void extensionForeignRunAndMismatchedReceiptCannotDriveRecovery() {
        var step = failure("invalid", "ACCESS_DENIED");
        for (var wrong : List.of(receipt(new RunId("foreign"), "framework.core", OBSERVE, "FAILED", 2),
                receipt(RUN, "extension.provider", OBSERVE, "FAILED", 2),
                receipt(RUN, "framework.core", "desktop_session_snapshot", "FAILED", 2),
                receipt(RUN, "framework.core", OBSERVE, "UNKNOWN", 2))) {
            assertEquals(0, sequence(step, wrong, List.of(inventory())));
        }
    }

    @Test
    void onlyBoundRawTypedErrorsAreUsedNeverErrorMessagesOrModelProjection() {
        var receipt = receipt(RUN, "framework.core", OBSERVE, "FAILED", 2);
        var mismatched = failure("invalid", "ACCESS_DENIED");
        var mismatchOutput = (ObjectNode) mismatched.output();
        ((ObjectNode) mismatchOutput.path("rawOutput")).put("sessionId", "another-session");
        mismatched = withOutput(mismatched, mismatchOutput);
        assertEquals(0, sequence(mismatched, receipt, List.of(inventory())));

        var prose = failure("invalid", "PLATFORM_FAILURE");
        var proseOutput = (ObjectNode) prose.output();
        ((ObjectNode) proseOutput.path("rawOutput")).put("detail", "session expired; ACCESS_DENIED");
        proseOutput.set("modelOutput", rawError("invalid", "SESSION_EXPIRED"));
        prose = withOutput(prose, proseOutput);
        assertEquals(0, sequence(prose, receipt, List.of(inventory())));
        var noRaw = failure("invalid", "SESSION_EXPIRED");
        var noRawOutput = (ObjectNode) noRaw.output();
        noRawOutput.remove("rawOutput");
        noRaw = withOutput(noRaw, noRawOutput);
        assertEquals(0, sequence(noRaw, receipt, List.of(inventory())));
    }

    @Test
    void partialMalformedAndConflictingInventoriesCannotCertifySessionExpiry() {
        var partial = inventory().put("complete", false);
        var malformed = inventory();
        malformed.withArray("sessionIds").add(42);
        var other = inventory();
        other.withArray("sessionIds").add("different-live-session");
        var step = failure("invalid", "ACCESS_DENIED");
        var receipt = receipt(RUN, "framework.core", OBSERVE, "FAILED", 2);
        for (List<JsonNode> context : List.of(List.<JsonNode>of(partial), List.<JsonNode>of(malformed),
                List.<JsonNode>of(inventory(), other))) {
            assertEquals(0, sequence(step, receipt, context));
            assertFalse(OnDemandDesktopSessionRecovery.noLiveSessions(context));
        }
    }

    @Test
    void aSuccessfulBoundOpenAfterTheFailureRetiresRecoveryButStillRequiresObservation() {
        var failed = failure("invalid", "ACCESS_DENIED");
        var args = NODES.objectNode().put("targetId", "real-window");
        var data = NODES.objectNode().put("schemaVersion", 1).put("kind", "desktop.session")
                .put("sessionId", "new-host-session");
        var open = tool(3, "open", "desktop_session_open", "SUCCEEDED", args, data);
        var opened = receipt(RUN, "framework.core", "desktop_session_open", "ACCEPTED", 4);
        var openedPayload = (ObjectNode) opened.payload();
        openedPayload.put("invocationId", "open").put("operation", "open")
                .set("metadata", NODES.objectNode().put("sessionId", "new-host-session").put("targetId", "real-window"));
        opened = new RunEventEnvelope(opened.runId(), opened.sequence(), opened.timestamp(), opened.type(),
                opened.schemaVersion(), opened.producer(), opened.correlationId(), opened.causationId(), openedPayload);
        var live = inventory();
        live.withArray("sessionIds").add("new-host-session");
        var cursor = ComputerUseSessionCursor.derive(List.of(failed, open),
                List.of(receipt(RUN, "framework.core", OBSERVE, "FAILED", 2), opened), null, List.of(live));
        assertFalse(cursor.sessionExpired());
        assertEquals(ComputerUseSessionCursor.Phase.OBSERVE, cursor.phase());
        assertEquals("new-host-session", cursor.sessionId());
        assertEquals(OBSERVE, cursor.requiredTool());
        assertFalse(cursor.inputAllowed());
    }

    private static long sequence(AgentStep step, RunEventEnvelope receipt, List<JsonNode> runtime) {
        return OnDemandDesktopSessionRecovery.invalidSessionFailureSequence(List.of(step), List.of(receipt), runtime);
    }

    private static AgentStep withOutput(AgentStep step, ObjectNode output) {
        return new AgentStep(step.id(), step.threadId(), step.turnId(), step.kind(), step.state(),
                step.causationStepId(), step.input(), output, step.usage(), step.error(),
                step.startedAt(), step.endedAt(), step.startSequence(), step.lastSequence());
    }

    private static ObjectNode inventory() {
        var result = NODES.objectNode().put("kind", "desktop.sessions.current").put("known", true)
                .put("complete", true);
        result.putArray("sessionIds");
        return result;
    }

    private static ObjectNode rawError(String session, String code) {
        return NODES.objectNode().put("schemaVersion", 1).put("kind", "desktop.error").put("tool", OBSERVE)
                .put("sessionId", session).put("errorCode", code).put("admission", "FAILED");
    }

    private static AgentStep failure(String session, String code) {
        return tool(1, "observe", OBSERVE, "FAILED", NODES.objectNode().put("sessionId", session), rawError(session, code));
    }

    private static AgentStep tool(long sequence, String id, String tool, String status,
            ObjectNode arguments, ObjectNode raw) {
        var input = NODES.objectNode().put("tool", tool).put("invocationId", id);
        input.set("arguments", arguments);
        var output = NODES.objectNode().put("status", status);
        output.set("rawOutput", raw);
        return new AgentStep(StepId.tool(RUN, id), "thread", RUN, AgentStep.Kind.TOOL,
                AgentStep.State.COMPLETED, null, input, output, null, null,
                Instant.EPOCH, Instant.EPOCH, sequence, sequence + 1);
    }

    private static RunEventEnvelope receipt(RunId run, String producer, String tool, String status, long sequence) {
        var payload = NODES.objectNode().put("invocationId", "observe").put("tool", tool).put("status", status)
                .put("operation", tool.substring("desktop_session_".length()));
        return new RunEventEnvelope(run.value(), sequence, Instant.EPOCH, "core.tool.receipt", 1,
                producer, null, null, payload);
    }
}
