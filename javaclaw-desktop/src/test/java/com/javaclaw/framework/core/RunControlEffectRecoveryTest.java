package com.javaclaw.framework.core;

import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.spi.EffectReceiptV1;
import com.javaclaw.framework.spi.ToolEffectPolicy;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import static org.junit.jupiter.api.Assertions.*;

class RunControlEffectRecoveryTest {
    @Test
    void discoveryMayRevisitKnownLaunchButCannotResendAnUncertainOne() {
        for (String delivery : new String[] { "SENT", "NOT_SENT" }) {
            RunControl old = control();
            old.restoreEffectStart("launch", "first", "same-app", false, ToolEffectPolicy.LEGACY, "");
            old.restoreEffectReceipt("launch", EffectReceiptV1.Status.ACCEPTED, delivery);
            RunControl next = control();
            next.inheritUnresolvedEffects(old, "source");
            next.enterTaskRepair();
            assertDoesNotThrow(() -> next.assertRepairRetryAllowed("new", "same-app", false,
                    ToolEffectPolicy.DISCOVERY_GATED, ""));
            assertThrows(ToolPermissionDeniedException.class, () -> next.assertRepairRetryAllowed(
                    "new", "same-app", false, ToolEffectPolicy.LEGACY, ""),
                    "discovery policy must not relax ordinary non-idempotent tools");
        }
        for (EffectReceiptV1.Status status : new EffectReceiptV1.Status[] { null,
                EffectReceiptV1.Status.UNKNOWN, EffectReceiptV1.Status.ACCEPTED }) {
            RunControl old = control();
            old.restoreEffectStart("launch", "first", "same-app", false,
                    ToolEffectPolicy.DISCOVERY_GATED, "");
            if (status != null) old.restoreEffectReceipt("launch", status, "MAYBE_SENT");
            RunControl next = control();
            next.inheritUnresolvedEffects(old, "source");
            var blocked = assertThrows(PendingEffectObservationRequiredException.class,
                    () -> next.assertRepairRetryAllowed("new", "same-app", false,
                            ToolEffectPolicy.DISCOVERY_GATED, ""));
            assertEquals("source", blocked.sourceRunId());
            assertEquals("launch", blocked.invocationId());
            assertEquals(PendingEffectObservationRequiredException.Reason.DELIVERY_UNCERTAIN,
                    blocked.reason());
        }
    }

    @Test
    void inheritedAdmissionConsumesOnlyItsOriginalObservation() {
        RunControl old = input(EffectReceiptV1.Status.ACCEPTED, "SENT");
        RunControl next = control();
        next.inheritUnresolvedEffects(old, "source");
        assertDoesNotThrow(() -> nextInput(next, "fresh"));
        var duplicate = assertThrows(PendingEffectObservationRequiredException.class,
                () -> nextInput(next, "old"));
        assertEquals(PendingEffectObservationRequiredException.Reason.OBSERVATION_ALREADY_CONSUMED,
                duplicate.reason());
    }

    @Test
    void reconciliationIsQualifiedBySourceInvocationAndResource() {
        RunControl next = control();
        next.inheritUnresolvedEffects(input(EffectReceiptV1.Status.UNKNOWN, "MAYBE_SENT"), "one");
        next.inheritUnresolvedEffects(input(EffectReceiptV1.Status.UNKNOWN, "MAYBE_SENT"), "two");
        next.restoreEffectReconciliation("one", "call", "desktop:wrong-window");
        assertThrows(ToolPermissionDeniedException.class, () -> nextInput(next, "fresh"));
        next.restoreEffectReconciliation("one", "call", "desktop:window");
        var remaining = assertThrows(PendingEffectObservationRequiredException.class,
                () -> nextInput(next, "fresh"));
        assertEquals("two", remaining.sourceRunId());
        next.restoreEffectReconciliation("two", "call", "desktop:window");
        assertDoesNotThrow(() -> nextInput(next, "fresh"));
        assertThrows(ToolPermissionDeniedException.class, () -> nextInput(next, "old"));
    }

    private static RunControl input(EffectReceiptV1.Status status, String delivery) {
        RunControl control = control();
        control.restoreEffectStart("call", "old", "old", false,
                ToolEffectPolicy.OBSERVATION_GATED, "desktop:window");
        control.restoreEffectReceipt("call", status, delivery);
        return control;
    }
    private static void nextInput(RunControl control, String observationKey) {
        control.assertRepairRetryAllowed(observationKey, observationKey, false,
                ToolEffectPolicy.OBSERVATION_GATED, "desktop:window");
    }
    private static RunControl control() { return new RunControl(RunBudget.UNBOUNDED, Clock.systemUTC()); }
}
