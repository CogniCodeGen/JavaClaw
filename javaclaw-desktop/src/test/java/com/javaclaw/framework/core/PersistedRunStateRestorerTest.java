package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.spi.ToolEffectPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PersistedRunStateRestorerTest {
    @Test
    void cancelledUnknownEffectIsInheritedWithoutCountersApprovalsOrDeadline() {
        RunControl target = control();
        Instant deadline = target.deadline();
        ObjectNode approval = object().put("commandType", "tool.approval");
        approval.putObject("command").put("fingerprint", "old-fingerprint")
                .put("approved", true).put("humanApproved", true);
        PersistedRunStateRestorer.restoreUnresolvedEffects(List.of(
                event(1, "core.tool.started", start("old-action", "old-fingerprint",
                        "desktop:window-1")),
                event(2, "core.tool.receipt", receipt("old-action", "UNKNOWN", "MAYBE_SENT")),
                event(3, "core.run.resumed", approval),
                event(4, "core.run.cancelled", object().put("code", "TASK_SUPERSEDED"))), target);

        assertThrows(ToolPermissionDeniedException.class, () -> target.assertRepairRetryAllowed(
                "new-fingerprint", "new-effect", false, ToolEffectPolicy.OBSERVATION_GATED,
                "desktop:window-1"));
        assertDoesNotThrow(() -> target.assertRepairRetryAllowed("another-fingerprint",
                "another-effect", false, ToolEffectPolicy.OBSERVATION_GATED, "desktop:window-2"));
        assertEquals(0, target.toolCallCount());
        assertEquals(deadline, target.deadline());
        assertTrue(target.consumeToolApprovalGrant("desktop_session_click", "old-fingerprint").isEmpty());
    }

    @Test
    void missingReceiptInOldDesktopJournalRemainsConservativelyFenced() {
        ObjectNode old = start("old-action", "old-fingerprint", "desktop:window-1");
        old.remove("effectPolicy");
        old.remove("resourceKey");
        RunControl target = control();
        PersistedRunStateRestorer.restoreUnresolvedEffects(List.of(
                event(1, "core.tool.started", old),
                event(2, "core.run.cancelled", object().put("code", "RUN_TIMEOUT"))), target);
        assertThrows(ToolPermissionDeniedException.class, () -> target.assertRepairRetryAllowed(
                "new-fingerprint", "new-effect", false, ToolEffectPolicy.OBSERVATION_GATED,
                "desktop:other-window"), "old starts lacking a resource token use desktop:unknown");
        assertEquals(0, target.toolCallCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_SENT", "VERIFIED", "OBSERVED", "IDEMPOTENT", "ENSURE_STATE"})
    void establishedOrSafeEffectsDoNotBecomeInheritedUncertainty(String disposition) {
        ObjectNode started = start("old-action", "old-fingerprint", "desktop:window-1");
        ObjectNode completed = receipt("old-action", "FAILED", "NOT_SENT");
        if (disposition.equals("IDEMPOTENT")) {
            started.put("idempotent", true);
            completed = receipt("old-action", "UNKNOWN", "MAYBE_SENT");
        } else if (disposition.equals("ENSURE_STATE")) {
            started.put("effectPolicy", "ENSURE_STATE");
            completed = receipt("old-action", "UNKNOWN", "MAYBE_SENT");
        } else if (!disposition.equals("NOT_SENT")) {
            completed = receipt("old-action", disposition, "SENT");
        }
        RunControl target = control();
        PersistedRunStateRestorer.restoreUnresolvedEffects(List.of(
                event(1, "core.tool.started", started),
                event(2, "core.tool.receipt", completed)), target);
        assertDoesNotThrow(() -> target.assertRepairRetryAllowed("fresh-fingerprint",
                "fresh-effect", false, ToolEffectPolicy.OBSERVATION_GATED, "desktop:window-1"));
        if (disposition.equals("VERIFIED") || disposition.equals("OBSERVED")) {
            assertThrows(ToolPermissionDeniedException.class,
                    () -> target.assertRepairRetryAllowed("old-fingerprint", "old-effect", false,
                            ToolEffectPolicy.OBSERVATION_GATED, "desktop:window-1"));
        }
        assertEquals(0, target.toolCallCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MATCHED", "WRONG_TARGET", "WRONG_ACTION", "WRONG_PRODUCER", "WRONG_SCHEMA"})
    void onlyExactTrustedReconciliationClearsInheritedUncertainty(String disposition) {
        ObjectNode reconciliation = object().put("outcome", "SATISFIED")
                .put("actionInvocationId", disposition.equals("WRONG_ACTION") ? "other-action" : "old-action")
                .put("targetId", disposition.equals("WRONG_TARGET") ? "window-2" : "window-1");
        RunEventEnvelope reconciled = new RunEventEnvelope("old-run", 3, Instant.EPOCH,
                "core.effect.reconciled", disposition.equals("WRONG_SCHEMA") ? 2 : 1,
                disposition.equals("WRONG_PRODUCER") ? "model" : "framework.core",
                null, null, reconciliation);
        RunControl target = control();
        PersistedRunStateRestorer.restoreUnresolvedEffects(List.of(
                event(1, "core.tool.started", start("old-action", "old-fingerprint", "desktop:window-1")),
                event(2, "core.tool.receipt", receipt("old-action", "UNKNOWN", "MAYBE_SENT")),
                reconciled), target);
        Runnable attempt = () -> target.assertRepairRetryAllowed("new-fingerprint", "new-effect",
                false, ToolEffectPolicy.OBSERVATION_GATED, "desktop:window-1");
        if (disposition.equals("MATCHED")) assertDoesNotThrow(attempt::run);
        else assertThrows(ToolPermissionDeniedException.class, attempt::run);
    }

    @Test
    void untrustedReceiptCannotDowngradeAnOldUnresolvedStart() {
        RunControl target = control();
        RunEventEnvelope claimed = new RunEventEnvelope("old-run", 2, Instant.EPOCH,
                "core.tool.receipt", 1, "model", null, null,
                receipt("old-action", "FAILED", "NOT_SENT"));
        PersistedRunStateRestorer.restoreUnresolvedEffects(List.of(
                event(1, "core.tool.started", start("old-action", "old-fingerprint", "desktop:window-1")),
                claimed), target);
        assertThrows(ToolPermissionDeniedException.class, () -> target.assertRepairRetryAllowed(
                "new-fingerprint", "new-effect", false, ToolEffectPolicy.OBSERVATION_GATED,
                "desktop:window-1"));
    }

    @Test
    void identicalInvocationTokensFromDifferentRunsKeepBothResourceBarriers() {
        RunControl target = control();
        PersistedRunStateRestorer.restoreUnresolvedEffects(List.of(
                event(1, "core.tool.started", start("provider-call", "first-fingerprint", "desktop:window-1"))), target);
        PersistedRunStateRestorer.restoreUnresolvedEffects(List.of(
                new RunEventEnvelope("second-run", 1, Instant.EPOCH, "core.tool.started", 1,
                        "framework.core", null, null,
                        start("provider-call", "second-fingerprint", "desktop:window-2"))), target);
        for (String resource : List.of("desktop:window-1", "desktop:window-2")) {
            assertThrows(ToolPermissionDeniedException.class, () -> target.assertRepairRetryAllowed(
                    "new-fingerprint", "new-effect", false, ToolEffectPolicy.OBSERVATION_GATED, resource));
        }
        assertEquals(0, target.toolCallCount());
    }

    @Test
    void mixedRunJournalsCannotAliasTheirEffectTokens() {
        RunControl target = control();
        assertThrows(IllegalArgumentException.class, () ->
                PersistedRunStateRestorer.restoreUnresolvedEffects(List.of(
                        event(1, "core.tool.started", start("call", "fingerprint", "desktop:window-1")),
                        new RunEventEnvelope("other-run", 2, Instant.EPOCH, "core.tool.receipt", 1,
                                "framework.core", null, null, receipt("call", "FAILED", "NOT_SENT"))), target));
    }

    @Test
    void currentRunReconciliationCannotClearAnInheritedInvocationWithTheSameToken() {
        RunControl target = control();
        PersistedRunStateRestorer.restoreUnresolvedEffects(List.of(
                event(1, "core.tool.started", start("shared-call", "old-fingerprint", "desktop:window-1")),
                event(2, "core.tool.receipt", receipt("shared-call", "UNKNOWN", "MAYBE_SENT"))), target);
        target.restoreEffectStart("shared-call", "current-fingerprint", "current-effect", false,
                ToolEffectPolicy.OBSERVATION_GATED, "desktop:window-1");
        target.restoreEffectReceipt("shared-call", com.javaclaw.framework.spi.EffectReceiptV1.Status.UNKNOWN,
                "MAYBE_SENT");
        target.restoreEffectReconciliation("shared-call", "desktop:window-1");
        assertThrows(ToolPermissionDeniedException.class, () -> target.assertRepairRetryAllowed(
                "next-fingerprint", "next-effect", false, ToolEffectPolicy.OBSERVATION_GATED,
                "desktop:window-1"), "a current Run's checkpoint proves only its own action");
    }

    private static RunControl control() { return new RunControl(RunBudget.UNBOUNDED, Clock.systemUTC()); }
    private static ObjectNode object() { return JsonNodeFactory.instance.objectNode(); }
    private static ObjectNode start(String invocation, String fingerprint, String resource) {
        return object().put("tool", "desktop_session_click").put("invocationId", invocation)
                .put("fingerprint", fingerprint).put("effectKey", "old-effect")
                .put("idempotent", false).put("effectPolicy", "OBSERVATION_GATED")
                .put("resourceKey", resource);
    }
    private static ObjectNode receipt(String invocation, String status, String delivery) {
        ObjectNode receipt = object().put("invocationId", invocation).put("status", status);
        receipt.putObject("metadata").put("delivery", delivery);
        return receipt;
    }
    private static RunEventEnvelope event(long sequence, String type, ObjectNode payload) {
        return new RunEventEnvelope("old-run", sequence, Instant.EPOCH, type, 1,
                "framework.core", null, null, payload);
    }
}
