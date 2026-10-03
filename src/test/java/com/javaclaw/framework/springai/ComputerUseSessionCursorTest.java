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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComputerUseSessionCursorTest {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final RunId RUN = new RunId("computer-use-run");
    private static final String SESSION = "native-session";
    private static final String TARGET = "native-window";
    private static final String FRAME = "e6d6d461-5ce7-463a-9928-2ac25d7a1380";
    private static final String UPDATED_FRAME = "4da656ec-e1de-4b9f-9a8c-98411d101eba";

    @Test
    void unrelatedToolsDoNotEngageComputerUse() {
        var step = tool(1, "read", "read_file", "SUCCEEDED", NODES.objectNode());
        var cursor = derive(List.of(step), List.of(), null, List.of());
        assertEquals(ComputerUseSessionCursor.Phase.BOOTSTRAP, cursor.phase());
        assertFalse(cursor.engaged());
        assertFalse(cursor.requiresTool());
        assertFalse(cursor.inputAllowed());
    }

    @Test
    void acceptedLaunchBindsNormalizedArgumentsAndTrustedWindowIdentity() {
        ObjectNode data = NODES.objectNode().put("schemaVersion", 1).put("kind", "desktop.launch")
                .put("requestedApplication", "com.example.reader").put("applicationId", "com.example.reader")
                .put("processId", 42);
        data.putArray("targets").addObject().put("targetId", TARGET).put("applicationId", "com.example.reader")
                .put("processId", 42).put("visible", true).put("systemSurface", false);
        var launch = withArguments(tool(1, "launch", "desktop_session_launch_application", "SUCCEEDED", data),
                NODES.objectNode().put("application", "  com.example.reader \n"));
        var receipt = receipt(2, "launch", "desktop_session_launch_application", "ACCEPTED",
                NODES.objectNode().put("requestedApplication", "com.example.reader").put("delivery", "SENT"));
        var cursor = derive(List.of(launch), List.of(receipt), null, List.of());
        assertEquals(ComputerUseSessionCursor.Phase.OPEN_SESSION, cursor.phase());
        assertEquals(TARGET, cursor.targetId());
    }

    @Test
    void matchingOpenRequiresObservationAndCannotAuthorizeInputByItself() {
        var cursor = derive(List.of(open(1)), List.of(openReceipt(2)), null, List.of());
        assertEquals(ComputerUseSessionCursor.Phase.OBSERVE, cursor.phase());
        assertEquals("desktop_session_observe", cursor.requiredTool());
        assertEquals(SESSION, cursor.sessionId());
        assertFalse(cursor.inputAllowed());
    }

    @Test
    void trustedObservedFrameAllowsGroundedInput() {
        var cursor = derive(List.of(open(1), observe(3)),
                List.of(openReceipt(2), observeReceipt(4)), observation(), List.of());
        assertEquals(ComputerUseSessionCursor.Phase.READY, cursor.phase());
        assertEquals(FRAME, cursor.observationId());
        assertTrue(cursor.inputAllowed());
        assertFalse(cursor.requiresTool());
    }

    @Test
    void readOnlySessionMayObserveWithoutImplicitlyRequestingControl() {
        var cursor = derive(List.of(openWithGrant(false), observe(3)),
                List.of(openReceiptWithGrant("false"), observeReceipt(4)), observation(), List.of());
        assertEquals(ComputerUseSessionCursor.Phase.READY, cursor.phase());
        assertEquals(ComputerUseSessionCursor.ControlAccess.READ_ONLY, cursor.controlAccess());
        assertFalse(cursor.inputAllowed());
        assertFalse(cursor.requiresTool());
        assertTrue(cursor.needsControl());
        assertEquals(null, ComputerUseContextSelection.beforeControlUse(cursor,
                List.of("desktop_session_observe", "read_file")));
        var upgrade = ComputerUseContextSelection.beforeControlUse(cursor, List.of("desktop_session_click"));
        assertEquals(ComputerUseSessionCursor.Phase.OPEN_CONTROL, upgrade.phase());
        assertEquals("desktop_session_open", upgrade.requiredTool());
        assertEquals(TARGET, upgrade.targetId());
        assertEquals("", upgrade.observationId());
    }

    @Test
    void requestedControlAndIncompleteOrConflictingEvidenceNeverGrantControl() {
        for (var grant : java.util.Arrays.asList((Boolean) null, false)) {
            var opened = openWithGrant(grant);
            var cursor = derive(List.of(opened, observe(3)),
                    List.of(openReceiptWithGrant(grant == null ? null : "true"), observeReceipt(4)),
                    observation(), List.of());
            assertEquals(ComputerUseSessionCursor.ControlAccess.UNKNOWN, cursor.controlAccess());
            assertFalse(cursor.inputAllowed(), "requested control is not actual host authorization");
        }
        var steps = List.of(open(1), observe(3));
        var paired = sessionJournal(steps, List.of(openReceipt(2), observeReceipt(4)));
        List<List<RunEventEnvelope>> invalid = new ArrayList<>();
        invalid.add(List.of(openReceipt(2), observeReceipt(4)));
        var duplicate = new ArrayList<>(paired);
        duplicate.add(paired.stream().filter(event -> event.type().equals("core.tool.receipt")
                && event.payload().path("invocationId").asText().equals("open")).findFirst().orElseThrow());
        invalid.add(duplicate);
        for (JsonNode marker : List.of(NODES.booleanNode(false), NODES.textNode("true"))) {
            invalid.add(paired.stream().map(event -> {
                if (!event.type().equals("core.tool.started")) return event;
                ObjectNode payload = (ObjectNode) event.payload();
                payload.set("trustedDesktopTool", marker);
                return new RunEventEnvelope(event.runId(), event.sequence(), event.timestamp(), event.type(),
                        event.schemaVersion(), event.producer(), event.correlationId(), event.causationId(), payload);
            }).toList());
        }
        for (var events : invalid) {
            var cursor = ComputerUseSessionCursor.derive(steps, events, observation(), List.of());
            assertEquals(ComputerUseSessionCursor.ControlAccess.UNKNOWN, cursor.controlAccess());
            assertFalse(cursor.inputAllowed());
        }
    }

    @Test
    void sessionControlRequiredUsesOpenUpgradeInsteadOfPermissionProbeRetry() {
        ObjectNode raw = NODES.objectNode().put("schemaVersion", 1).put("kind", "desktop.action")
                .put("sessionId", SESSION).put("targetId", TARGET).put("observationId", FRAME)
                .put("reason", "SESSION_CONTROL_REQUIRED").put("nextStep", "OPEN_SESSION")
                .put("delivery", "NOT_SENT").put("dispatchAttempted", false);
        var click = withArguments(tool(5, "click", "desktop_session_click", "DENIED", raw),
                NODES.objectNode().put("sessionId", SESSION).put("observationId", FRAME));
        var failure = receipt(6, "click", "desktop_session_click", "FAILED", NODES.objectNode()
                .put("sessionId", SESSION).put("targetId", TARGET).put("observationId", FRAME)
                .put("reasonCode", "SESSION_CONTROL_REQUIRED").put("nextStep", "OPEN_SESSION")
                .put("delivery", "NOT_SENT").put("dispatchAttempted", "false"));
        var probe = tool(7, "probe", "desktop_session_probe", "SUCCEEDED",
                NODES.objectNode().put("automation", "READY"));
        var cursor = derive(List.of(openWithGrant(false), observe(3), click, probe),
                List.of(openReceiptWithGrant("false"), observeReceipt(4), failure), observation(), List.of());
        assertEquals(ComputerUseSessionCursor.Phase.OPEN_CONTROL, cursor.phase());
        assertEquals("desktop_session_open", cursor.requiredTool());
        assertTrue(cursor.pendingInvocationIds().isEmpty(), "host certified no dispatch");
        assertFalse(cursor.inputAllowed());
    }

    @Test
    void observationWithoutMatchingJournalStepAndHostReceiptCannotAuthorizeInput() {
        var cursor = derive(List.of(), List.of(), observation(), List.of());
        assertFalse(cursor.inputAllowed());
        assertEquals("", cursor.observationId());
    }

    @Test
    void receiptCannotAuthorizeADifferentRawObservation() {
        AgentStep mismatched = withArguments(tool(3, "observe", "desktop_session_observe", "SUCCEEDED",
                observationData(UPDATED_FRAME)), NODES.objectNode().put("sessionId", SESSION));
        var cursor = derive(List.of(open(1), mismatched),
                List.of(openReceipt(2), observeReceipt(4)), observation(), List.of());
        assertFalse(cursor.inputAllowed());
        assertEquals("", cursor.observationId());
    }

    @Test
    void newScreenshotDoesNotClearUnknownDelivery() {
        var action = action(5, "UNKNOWN", "MAYBE_SENT", "RECONCILE");
        var cursor = derive(List.of(open(1), action.step(), observe(7)),
                List.of(openReceipt(2), action.receipt(), observeReceipt(8)),
                observation(), List.of());
        assertEquals(ComputerUseSessionCursor.Phase.RECONCILE, cursor.phase());
        assertEquals(List.of("click"), cursor.pendingInvocationIds());
        assertFalse(cursor.inputAllowed());
    }

    @Test
    void missingActionReceiptRemainsFencedAfterNewScreenshot() {
        var action = action(5, "UNKNOWN", "MAYBE_SENT", "RECONCILE");
        var cursor = derive(List.of(open(1), action.step(), observe(7)),
                List.of(openReceipt(2), observeReceipt(8)), observation(), List.of());
        assertEquals(List.of("click"), cursor.pendingInvocationIds());
        assertFalse(cursor.inputAllowed());
    }

    @Test
    void pairedLaterHostObservationProvidesNewBaselineWithoutClaimingUnknownEffectSucceeded() {
        var fixture = baselineFixture(2_500, TARGET, SESSION, UPDATED_FRAME);
        var cursor = derive(fixture.steps(), fixture.events(), fixture.observation(), List.of());
        assertEquals(ComputerUseSessionCursor.Phase.READY, cursor.phase());
        assertTrue(cursor.inputAllowed());
        assertEquals(List.of("click"), cursor.pendingInvocationIds(), "the old business outcome stays UNKNOWN");
        assertEquals(List.of("click"), cursor.observedPendingInvocationIds());
        assertFalse(fixture.events().stream().anyMatch(event -> event.type().equals("core.effect.reconciled")));
    }

    @Test
    void baselineCannotUseOldCaptureOtherTargetOrUnpairedRawFrame() {
        for (var fixture : List.of(
                baselineFixture(2_000, TARGET, SESSION, UPDATED_FRAME),
                baselineFixture(2_500, "unrelated-window", SESSION, UPDATED_FRAME),
                baselineFixture(2_500, TARGET, SESSION, FRAME))) {
            var cursor = derive(fixture.steps(), fixture.events(), fixture.observation(), List.of());
            assertFalse(cursor.inputAllowed());
            assertTrue(cursor.observedPendingInvocationIds().isEmpty());
        }
        var valid = baselineFixture(2_500, TARGET, SESSION, UPDATED_FRAME);
        var withoutCompletion = valid.events().stream()
                .filter(event -> !event.type().equals("core.tool.completed")).toList();
        var cursor = derive(valid.steps(), withoutCompletion, valid.observation(), List.of());
        assertFalse(cursor.inputAllowed(), "a receipt and a model-visible screenshot do not form host proof");
    }

    @Test
    void reopenedSessionMayObserveOldUnknownInputOnTheSamePhysicalTarget() {
        String recoveredSession = "recovered-session";
        var fixture = baselineFixture(2_500, TARGET, recoveredSession, UPDATED_FRAME);
        var opened = withArguments(tool(10, "open-recovered", "desktop_session_open", "SUCCEEDED",
                NODES.objectNode().put("schemaVersion", 1).put("kind", "desktop.session")
                        .put("sessionId", recoveredSession).put("controlGranted", true)
                        .set("target", NODES.objectNode().put("targetId", TARGET))),
                NODES.objectNode().put("targetId", TARGET).put("control", true));
        List<AgentStep> steps = new ArrayList<>(fixture.steps());
        steps.add(opened);
        List<RunEventEnvelope> events = new ArrayList<>(fixture.events());
        ObjectNode upgradedMetadata = NODES.objectNode().put("sessionId", recoveredSession)
                .put("targetId", TARGET).put("controlGranted", "true");
        var upgradedReceipt = receipt(11, "open-recovered", "desktop_session_open", "ACCEPTED", upgradedMetadata);
        ObjectNode upgradedPayload = (ObjectNode) upgradedReceipt.payload();
        upgradedPayload.put("operation", "open");
        events.add(event(RUN, 11, "core.tool.receipt", "framework.core", upgradedPayload));
        var inventory = inventory(true, true);
        inventory.withArray("sessionIds").add(recoveredSession);
        var cursor = derive(steps, events, fixture.observation(),
                List.of(inventory));
        assertTrue(cursor.inputAllowed());
        assertEquals(recoveredSession, cursor.sessionId());
        assertEquals(List.of("click"), cursor.pendingInvocationIds());
        assertEquals(List.of("click"), cursor.observedPendingInvocationIds());
    }

    @Test
    void coreBoundLostResultMayUsePairedCurrentBaselineWhileKeepingUnknownOutcome() {
        var fixture = baselineFixture(2_500, TARGET, SESSION, UPDATED_FRAME);
        var events = fixture.events().stream().filter(event -> !event.type().equals("core.tool.receipt")
                || !event.payload().path("invocationId").asText().equals("click")).toList();
        var pending = derive(fixture.steps(), events, fixture.observation(), List.of());
        assertEquals(ComputerUseSessionCursor.Phase.RECONCILE, pending.phase());
        var ready = ComputerUseContextSelection.applyObservationBaseline(pending, events, true, false);
        assertEquals(ComputerUseSessionCursor.Phase.READY, ready.phase());
        assertTrue(ready.inputAllowed());
        assertEquals(List.of("click"), ready.pendingInvocationIds());
        assertEquals(List.of("click"), ready.observedPendingInvocationIds());
        assertFalse(ComputerUseContextSelection.applyObservationBaseline(pending, events, false, false).inputAllowed());
        assertFalse(ComputerUseContextSelection.applyObservationBaseline(pending, events, true, true).inputAllowed());
        assertFalse(ComputerUseContextSelection.applyObservationBaseline(pending, List.of(), true, false).inputAllowed());
    }

    @Test
    void failedActionCertifyingNotSentDoesNotCreateUnknownEffect() {
        var action = action(5, "FAILED", "NOT_SENT", "NONE");
        var cursor = derive(List.of(open(1), action.step(), observe(7)),
                List.of(openReceipt(2), action.receipt(), observeReceipt(8)),
                observation(), List.of());
        assertTrue(cursor.pendingInvocationIds().isEmpty());
        assertTrue(cursor.inputAllowed());
    }

    @Test
    void failedActionWithoutDeliveryProofRemainsFenced() {
        var action = action(5, "FAILED", "", "NONE");
        var cursor = derive(List.of(open(1), action.step(), observe(7)),
                List.of(openReceipt(2), action.receipt(), observeReceipt(8)),
                observation(), List.of());
        assertEquals(List.of("click"), cursor.pendingInvocationIds());
        assertFalse(cursor.inputAllowed());
    }

    @Test
    void receiptFromAnotherProducerCannotCertifyActionWasNotSent() {
        var action = action(5, "FAILED", "NOT_SENT", "NONE");
        var forged = event(RUN, 6, "core.tool.receipt", "extension.provider",
                action.receipt().payload());
        var cursor = derive(List.of(open(1), action.step(), observe(7)),
                List.of(openReceipt(2), forged, observeReceipt(8)), observation(), List.of());
        assertEquals(List.of("click"), cursor.pendingInvocationIds());
        assertFalse(cursor.inputAllowed());
    }

    @Test
    void receiptFromAnotherRunCannotCertifyActionWasNotSent() {
        var action = action(5, "FAILED", "NOT_SENT", "NONE");
        var foreign = event(new RunId("other-run"), 6, "core.tool.receipt", "framework.core",
                action.receipt().payload());
        var cursor = derive(List.of(open(1), action.step(), observe(7)),
                List.of(openReceipt(2), foreign, observeReceipt(8)), observation(), List.of());
        assertEquals(List.of("click"), cursor.pendingInvocationIds());
        assertFalse(cursor.inputAllowed());
    }

    @Test
    void reconciliationFromAnotherRunCannotClearUnknownInput() {
        var action = action(5, "UNKNOWN", "MAYBE_SENT", "RECONCILE");
        ObjectNode proof = NODES.objectNode().put("actionInvocationId", "click")
                .put("outcome", "SATISFIED").put("sessionId", SESSION)
                .put("targetId", TARGET).put("actionObservationId", FRAME)
                .put("evidenceObservationId", FRAME);
        var foreign = event(new RunId("other-run"), 9, "core.effect.reconciled",
                "framework.core", proof);
        var cursor = derive(List.of(open(1), action.step(), observe(7)),
                List.of(openReceipt(2), action.receipt(), observeReceipt(8), foreign),
                observation(), List.of());
        assertEquals(List.of("click"), cursor.pendingInvocationIds());
        assertFalse(cursor.inputAllowed());
    }

    @Test
    void openReceiptMustBindTheRequestedTarget() {
        ObjectNode metadata = NODES.objectNode().put("sessionId", "unrelated-session")
                .put("targetId", "unrelated-window");
        var unrelated = receipt(2, "open", "desktop_session_open", "ACCEPTED", metadata);
        var cursor = derive(List.of(open(1)), List.of(unrelated), null, List.of());
        assertEquals("", cursor.sessionId());
        assertFalse(cursor.inputAllowed());
    }

    @Test
    void observeReceiptMustBindTheRequestedSession() {
        ObjectNode metadata = NODES.objectNode().put("sessionId", "unrelated-session")
                .put("targetId", "unrelated-window").put("observationId", FRAME);
        var unrelated = receipt(4, "observe", "desktop_session_observe", "OBSERVED", metadata);
        var cursor = derive(List.of(observe(3)), List.of(unrelated), null, List.of());
        assertEquals("", cursor.sessionId());
        assertFalse(cursor.inputAllowed());
    }

    @Test
    void completeCurrentInventoryInvalidatesOldNativeSessionAndFrame() {
        var cursor = derive(List.of(open(1), observe(3)),
                List.of(openReceipt(2), observeReceipt(4)), observation(),
                List.of(inventory(true, true)));
        assertEquals(ComputerUseSessionCursor.Phase.RECOVER_SESSION, cursor.phase());
        assertEquals("desktop_session_targets", cursor.requiredTool());
        assertTrue(cursor.sessionExpired());
        assertEquals("", cursor.observationId());
        assertFalse(cursor.inputAllowed());
    }

    @Test
    void incompleteOrUnknownInventoryCannotDeclareNativeSessionExpired() {
        for (var inventory : List.of(inventory(true, false), inventory(false, true))) {
            var cursor = derive(List.of(open(1), observe(3)),
                    List.of(openReceipt(2), observeReceipt(4)), observation(), List.of(inventory));
            assertFalse(cursor.sessionExpired());
            assertTrue(cursor.inputAllowed());
        }
    }

    @Test
    void liveSessionIncludedInCompleteInventoryRemainsUsable() {
        var inventory = inventory(true, true);
        inventory.withArray("sessionIds").add(SESSION);
        var cursor = derive(List.of(open(1), observe(3)),
                List.of(openReceipt(2), observeReceipt(4)), observation(), List.of(inventory));
        assertFalse(cursor.sessionExpired());
        assertTrue(cursor.inputAllowed());
    }

    @Test
    void expiredSessionAdvancesToOpenAfterTrustedRediscovery() {
        ObjectNode targets = NODES.objectNode().put("schemaVersion", 1)
                .put("kind", "desktop.targets");
        targets.putArray("targets").addObject().put("targetId", TARGET)
                .put("processId", 202).put("application", "Reader")
                .put("visible", true).put("systemSurface", false);
        var discovered = tool(5, "targets", "desktop_session_targets", "SUCCEEDED", targets);
        var discoveredReceipt = receipt(6, "targets", "desktop_session_targets", "OBSERVED",
                NODES.objectNode());
        var cursor = derive(List.of(open(1), observe(3), discovered),
                List.of(openReceipt(2), observeReceipt(4), discoveredReceipt), observation(),
                List.of(inventory(true, true)));
        assertEquals("desktop_session_open", cursor.requiredTool());
        assertFalse(cursor.inputAllowed());
    }

    @Test
    void inheritedUnknownInputRecoversThroughTargetsAndOpenEvenWhenAnOlderHandleIsStillLive() {
        ObjectNode targets = NODES.objectNode().put("schemaVersion", 1).put("kind", "desktop.targets");
        targets.putArray("targets").addObject().put("targetId", TARGET)
                .put("processId", 202).put("visible", true).put("systemSurface", false);
        var discovered = tool(5, "targets", "desktop_session_targets", "SUCCEEDED", targets);
        var discoveredReceipt = receipt(6, "targets", "desktop_session_targets", "OBSERVED",
                NODES.objectNode());
        var inventory = inventory(true, true);
        inventory.withArray("sessionIds").add("historical-session");
        var cursor = ComputerUseSessionCursor.derive(List.of(discovered), List.of(discoveredReceipt),
                null, List.of(inventory), true);
        assertEquals(ComputerUseSessionCursor.Phase.RECOVER_SESSION, cursor.phase());
        assertEquals("desktop_session_open", cursor.requiredTool());
        assertEquals(TARGET, cursor.targetId());
        assertEquals("", cursor.sessionId());
        assertFalse(cursor.inputAllowed());
    }

    @Test
    void expiredSessionCannotAdvanceToOpenFromAnEmptyTargetDirectory() {
        ObjectNode targets = NODES.objectNode().put("schemaVersion", 1)
                .put("kind", "desktop.targets");
        targets.putArray("targets");
        var discovered = tool(5, "targets", "desktop_session_targets", "SUCCEEDED", targets);
        var discoveredReceipt = receipt(6, "targets", "desktop_session_targets", "OBSERVED",
                NODES.objectNode());
        var cursor = derive(List.of(open(1), observe(3), discovered),
                List.of(openReceipt(2), observeReceipt(4), discoveredReceipt), observation(),
                List.of(inventory(true, true)));
        assertEquals(ComputerUseSessionCursor.Phase.RECOVER_SESSION, cursor.phase());
        assertEquals("desktop_session_targets", cursor.requiredTool());
        assertFalse(cursor.inputAllowed());
    }

    @Test
    void successfulCloseCannotAdoptItsOldFrameAgain() {
        var closed = tool(5, "close", "desktop_session_close", "SUCCEEDED", NODES.objectNode());
        closed = withArguments(closed, NODES.objectNode().put("sessionId", SESSION));
        var cursor = derive(List.of(open(1), observe(3), closed),
                List.of(openReceipt(2), observeReceipt(4), receipt(6, "close", "desktop_session_close",
                        "ACCEPTED", NODES.objectNode().put("sessionId", SESSION))), observation(), List.of());
        assertFalse(cursor.inputAllowed());
        assertEquals("", cursor.observationId());
    }

    @Test
    void actionReceiptForOtherFrameCannotCertifyNotSent() {
        var action = action(5, "FAILED", "NOT_SENT", "NONE");
        var payload = (ObjectNode) action.receipt().payload();
        ((ObjectNode) payload.path("metadata")).put("observationId", UPDATED_FRAME);
        var mismatch = event(RUN, 6, "core.tool.receipt", "framework.core", payload);
        var cursor = derive(List.of(open(1), action.step(), observe(7)),
                List.of(openReceipt(2), mismatch, observeReceipt(8)), observation(), List.of());
        assertEquals(List.of("click"), cursor.pendingInvocationIds());
        assertFalse(cursor.inputAllowed());
    }

    @Test
    void acceptedActionWithoutSentProofRemainsFenced() {
        var action = action(5, "ACCEPTED", "", "NONE");
        var cursor = derive(List.of(open(1), action.step(), observe(7)),
                List.of(openReceipt(2), action.receipt(), observeReceipt(8)), observation(), List.of());
        assertEquals(List.of("click"), cursor.pendingInvocationIds());
        assertFalse(cursor.inputAllowed());
    }

    @Test
    void matchingTrustedReconciliationOfLaterObservedFrameClearsUnknownEffect() {
        var action = action(5, "UNKNOWN", "MAYBE_SENT", "RECONCILE");
        var updated = updatedObserve(7);
        var proof = reconciliation(9, TARGET);
        var cursor = derive(List.of(open(1), action.step(), updated),
                List.of(openReceipt(2), action.receipt(), updatedReceipt(8), proof),
                updatedObservation(), List.of());
        assertTrue(cursor.pendingInvocationIds().isEmpty());
        assertTrue(cursor.inputAllowed());
    }

    @Test
    void reconciliationForOtherWindowCannotClearUnknownEffect() {
        var action = action(5, "UNKNOWN", "MAYBE_SENT", "RECONCILE");
        var cursor = derive(List.of(open(1), action.step(), updatedObserve(7)),
                List.of(openReceipt(2), action.receipt(), updatedReceipt(8),
                        reconciliation(9, "other-window")), updatedObservation(), List.of());
        assertEquals(List.of("click"), cursor.pendingInvocationIds());
        assertFalse(cursor.inputAllowed());
    }

    @Test
    void reconciliationWithoutMatchingLaterObservedReceiptCannotClearUnknownEffect() {
        var action = action(5, "UNKNOWN", "MAYBE_SENT", "RECONCILE");
        var cursor = derive(List.of(open(1), action.step(), updatedObserve(7)),
                List.of(openReceipt(2), action.receipt(), reconciliation(9, TARGET)),
                updatedObservation(), List.of());
        assertEquals(List.of("click"), cursor.pendingInvocationIds());
        assertFalse(cursor.inputAllowed());
    }

    @Test
    void reconciliationReceiptMustMatchTheLaterRawObservation() {
        var action = action(5, "UNKNOWN", "MAYBE_SENT", "RECONCILE");
        AgentStep mismatched = withArguments(tool(7, "observe-updated", "desktop_session_observe",
                "SUCCEEDED", observationData(FRAME)), NODES.objectNode().put("sessionId", SESSION));
        var cursor = derive(List.of(open(1), action.step(), mismatched),
                List.of(openReceipt(2), action.receipt(), updatedReceipt(8), reconciliation(9, TARGET)),
                updatedObservation(), List.of());
        assertEquals(List.of("click"), cursor.pendingInvocationIds());
        assertFalse(cursor.inputAllowed());
    }

    private static RunEventEnvelope reconciliation(long sequence, String target) {
        return event(RUN, sequence, "core.effect.reconciled", "framework.core",
                NODES.objectNode().put("actionInvocationId", "click").put("outcome", "SATISFIED")
                        .put("sessionId", SESSION).put("targetId", target)
                        .put("actionObservationId", FRAME).put("evidenceObservationId", UPDATED_FRAME));
    }

    private static BaselineFixture baselineFixture(long capturedAt, String target,
            String session, String observationId) {
        var action = action(5, "UNKNOWN", "MAYBE_SENT", "OBSERVE");
        ObjectNode actionPayload = (ObjectNode) action.receipt().payload();
        actionPayload.put("operation", "click").put("observedAt", Instant.ofEpochMilli(2_000).toString());
        ((ObjectNode) actionPayload.path("metadata")).put("windowGeneration", "1");
        var actionReceipt = new RunEventEnvelope(RUN.value(), 6, Instant.ofEpochMilli(2_000),
                "core.tool.receipt", 1, "framework.core", "test", "test", actionPayload);
        ObjectNode raw = observationData(observationId).put("targetId", target).put("sessionId", session)
                .put("protocol", "computer-use").put("application", "Example")
                .put("controlGranted", true)
                .put("windowGeneration", 1).put("contentRevision", 2).put("capturedAtMillis", capturedAt);
        raw.putObject("frame").put("targetId", target).put("windowGeneration", 1)
                .put("contentRevision", 2).put("capturedAtMillis", capturedAt).put("width", 800).put("height", 600);
        var observed = withArguments(tool(20, "observe-fresh", "desktop_session_observe", "SUCCEEDED", raw),
                NODES.objectNode().put("sessionId", session));
        ObjectNode metadata = NODES.objectNode().put("targetId", target).put("sessionId", session)
                .put("observationId", observationId).put("windowGeneration", "1")
                .put("controlGranted", "true")
                .put("contentRevision", "2").put("capturedAtMillis", Long.toString(capturedAt));
        var originalReceipt = receipt(22, "observe-fresh", "desktop_session_observe", "OBSERVED", metadata);
        ObjectNode receiptPayload = (ObjectNode) originalReceipt.payload();
        receiptPayload.put("operation", "observe").put("target", "Example")
                .put("evidenceRef", "core.tool.completed:" + RUN.value() + ":observe-fresh")
                .put("observedAt", Instant.ofEpochMilli(3_000).toString());
        var receipt = new RunEventEnvelope(RUN.value(), 22, Instant.ofEpochMilli(3_000),
                "core.tool.receipt", 1, "framework.core", "test", "test", receiptPayload);
        ObjectNode start = NODES.objectNode().put("tool", "desktop_session_observe")
                .put("invocationId", "observe-fresh");
        start.set("arguments", NODES.objectNode().put("sessionId", session));
        ObjectNode completion = NODES.objectNode().put("tool", "desktop_session_observe")
                .put("invocationId", "observe-fresh").put("status", "SUCCEEDED");
        completion.set("output", raw);
        List<RunEventEnvelope> events = List.of(openReceipt(2), actionReceipt,
                new RunEventEnvelope(RUN.value(), 20, Instant.ofEpochMilli(2_000),
                        "core.tool.started", 1, "framework.core", "test", "test", start),
                new RunEventEnvelope(RUN.value(), 21, Instant.ofEpochMilli(3_000),
                        "core.tool.completed", 2, "framework.core", "test", "test", completion), receipt);
        var observation = new OnDemandHistoryCatalog.DesktopObservation(null, session, target,
                observationId, "", "", raw);
        return new BaselineFixture(List.of(open(1), action.step(), observed), events, observation);
    }

    private static AgentStep updatedObserve(long sequence) {
        return withArguments(tool(sequence, "observe-updated", "desktop_session_observe", "SUCCEEDED",
                observationData(UPDATED_FRAME)), NODES.objectNode().put("sessionId", SESSION));
    }

    private static RunEventEnvelope updatedReceipt(long sequence) {
        return receipt(sequence, "observe-updated", "desktop_session_observe", "OBSERVED",
                NODES.objectNode().put("sessionId", SESSION).put("targetId", TARGET)
                        .put("observationId", UPDATED_FRAME));
    }

    private static OnDemandHistoryCatalog.DesktopObservation updatedObservation() {
        return new OnDemandHistoryCatalog.DesktopObservation(null, SESSION, TARGET, UPDATED_FRAME,
                "", "", observationData(UPDATED_FRAME));
    }

    private static ComputerUseSessionCursor derive(List<AgentStep> steps,
            List<RunEventEnvelope> events, OnDemandHistoryCatalog.DesktopObservation observation,
            List<JsonNode> runtime) {
        return ComputerUseSessionCursor.derive(steps, sessionJournal(steps, events), observation, runtime);
    }

    private static List<RunEventEnvelope> sessionJournal(List<AgentStep> steps, List<RunEventEnvelope> events) {
        List<RunEventEnvelope> journal = new ArrayList<>(events);
        for (AgentStep step : steps) {
            if (!step.input().path("tool").asText().equals("desktop_session_open")) continue;
            String invocation = step.input().path("invocationId").asText();
            if (events.stream().anyMatch(event -> event.type().equals("core.tool.started")
                    && event.payload().path("invocationId").asText().equals(invocation))) continue;
            for (int index = 0; index < journal.size(); index++) {
                RunEventEnvelope receipt = journal.get(index);
                if (!receipt.type().equals("core.tool.receipt")
                        || !receipt.payload().path("invocationId").asText().equals(invocation)) continue;
                ObjectNode payload = (ObjectNode) receipt.payload();
                payload.put("evidenceRef", "core.tool.completed:" + RUN.value() + ":" + invocation);
                journal.set(index, new RunEventEnvelope(receipt.runId(), receipt.sequence() + 2,
                        receipt.timestamp(), receipt.type(), receipt.schemaVersion(), receipt.producer(),
                        receipt.correlationId(), receipt.causationId(), payload));
                ObjectNode start = NODES.objectNode().put("tool", "desktop_session_open").put("invocationId", invocation);
                start.set("arguments", step.input().path("arguments"));
                ObjectNode completed = NODES.objectNode().put("tool", "desktop_session_open")
                        .put("invocationId", invocation).put("status", "SUCCEEDED");
                completed.set("output", step.output().path("rawOutput"));
                journal.add(event(RUN, receipt.sequence(), "core.tool.started", "framework.core", start));
                journal.add(new RunEventEnvelope(RUN.value(), receipt.sequence() + 1, receipt.timestamp(),
                        "core.tool.completed", 2, "framework.core", "test", "test", completed));
                break;
            }
        }
        return List.copyOf(journal);
    }

    private static OnDemandHistoryCatalog.DesktopObservation observation() {
        return new OnDemandHistoryCatalog.DesktopObservation(null, SESSION, TARGET, FRAME,
                "", "", observationData(FRAME));
    }

    private static ObjectNode observationData(String frame) {
        return NODES.objectNode().put("schemaVersion", 1).put("kind", "desktop.observation")
                .put("sessionId", SESSION).put("targetId", TARGET).put("observationId", frame);
    }

    private static ObjectNode inventory(boolean known, boolean complete) {
        ObjectNode data = NODES.objectNode().put("kind", "desktop.sessions.current")
                .put("known", known).put("complete", complete);
        data.putArray("sessionIds");
        return data;
    }

    private static AgentStep open(long sequence) {
        var data = NODES.objectNode().put("schemaVersion", 1).put("kind", "desktop.session")
                .put("sessionId", SESSION).put("controlGranted", true);
        data.putObject("target").put("targetId", TARGET);
        return withArguments(tool(sequence, "open", "desktop_session_open", "SUCCEEDED",
                data), NODES.objectNode().put("targetId", TARGET).put("control", true));
    }

    private static AgentStep openWithGrant(Boolean grant) {
        ObjectNode raw = (ObjectNode) open(1).output().path("rawOutput");
        if (grant == null) raw.remove("controlGranted");
        else raw.put("controlGranted", grant);
        return withArguments(tool(1, "open", "desktop_session_open", "SUCCEEDED", raw),
                NODES.objectNode().put("targetId", TARGET).put("control", true));
    }

    private static RunEventEnvelope openReceiptWithGrant(String grant) {
        ObjectNode payload = (ObjectNode) openReceipt(2).payload();
        ObjectNode metadata = (ObjectNode) payload.path("metadata");
        if (grant == null) metadata.remove("controlGranted");
        else metadata.put("controlGranted", grant);
        return event(RUN, 2, "core.tool.receipt", "framework.core", payload);
    }

    private static AgentStep observe(long sequence) {
        return withArguments(tool(sequence, "observe", "desktop_session_observe", "SUCCEEDED",
                observationData(FRAME)), NODES.objectNode().put("sessionId", SESSION));
    }

    private static RunEventEnvelope openReceipt(long sequence) {
        var original = receipt(sequence, "open", "desktop_session_open", "ACCEPTED",
                NODES.objectNode().put("sessionId", SESSION).put("targetId", TARGET).put("controlGranted", "true"));
        ObjectNode payload = (ObjectNode) original.payload();
        payload.put("operation", "open");
        return event(RUN, sequence, "core.tool.receipt", "framework.core", payload);
    }

    private static RunEventEnvelope observeReceipt(long sequence) {
        return receipt(sequence, "observe", "desktop_session_observe", "OBSERVED",
                NODES.objectNode().put("sessionId", SESSION).put("targetId", TARGET)
                        .put("observationId", FRAME));
    }

    private static Action action(long sequence, String status, String delivery, String nextStep) {
        AgentStep step = withArguments(tool(sequence, "click", "desktop_session_click",
                status.equals("ACCEPTED") ? "SUCCEEDED" : "UNCERTAIN", NODES.objectNode()),
                NODES.objectNode().put("sessionId", SESSION).put("observationId", FRAME));
        ObjectNode metadata = NODES.objectNode().put("sessionId", SESSION).put("targetId", TARGET)
                .put("observationId", FRAME).put("nextStep", nextStep);
        if (!delivery.isBlank()) metadata.put("delivery", delivery);
        return new Action(step, receipt(sequence + 1, "click", "desktop_session_click", status, metadata));
    }

    private static AgentStep tool(long sequence, String invocation, String name,
            String status, ObjectNode data) {
        ObjectNode input = NODES.objectNode().put("tool", name).put("invocationId", invocation);
        input.putObject("arguments");
        ObjectNode output = NODES.objectNode().put("status", status);
        output.set("rawOutput", data);
        return new AgentStep(StepId.tool(RUN, invocation), "thread", RUN,
                AgentStep.Kind.TOOL, AgentStep.State.COMPLETED, null, input, output,
                null, null, Instant.EPOCH, Instant.EPOCH, sequence, sequence + 1);
    }

    private static AgentStep withArguments(AgentStep step, ObjectNode arguments) {
        ObjectNode input = (ObjectNode) step.input();
        input.set("arguments", arguments);
        return new AgentStep(step.id(), step.threadId(), step.turnId(), step.kind(), step.state(),
                step.causationStepId(), input, step.output(), step.usage(), step.error(),
                step.startedAt(), step.endedAt(), step.startSequence(), step.lastSequence());
    }

    private static RunEventEnvelope receipt(long sequence, String invocation,
            String name, String status, ObjectNode metadata) {
        ObjectNode payload = NODES.objectNode().put("invocationId", invocation)
                .put("tool", name).put("status", status).put("evidenceRef", "receipt:" + invocation);
        payload.set("metadata", metadata);
        return event(RUN, sequence, "core.tool.receipt", "framework.core", payload);
    }

    private static RunEventEnvelope event(RunId run, long sequence, String type,
            String producer, JsonNode payload) {
        return new RunEventEnvelope(run.value(), sequence, Instant.EPOCH, type, 1, producer,
                "test", "test", payload);
    }

    private record Action(AgentStep step, RunEventEnvelope receipt) { }
    private record BaselineFixture(List<AgentStep> steps, List<RunEventEnvelope> events,
            OnDemandHistoryCatalog.DesktopObservation observation) { }
}
