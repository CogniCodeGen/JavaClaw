package com.javaclaw.ui.javafx.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.javaclaw.desktop.api.DesktopAction;
import com.javaclaw.desktop.api.DesktopActionEvent;
import com.javaclaw.desktop.api.DesktopActionResult;
import com.javaclaw.desktop.api.DesktopVirtualInputState;
import org.junit.jupiter.api.Test;

class DesktopPreviewFeedbackTest {
    @Test
    void notSentClearsPreparationAndKeepsAnExplanation() {
        var feedback = feedback();
        feedback.accept(target(1), 0);
        assertNotNull(feedback.visibleInput(0));
        feedback.accept(result(1, DesktopActionResult.Status.FAILED,
                DesktopActionResult.Delivery.NOT_SENT, DesktopActionResult.Reason.INVALID_TARGET), 10);
        assertNull(feedback.visibleInput(10));
        assertEquals("未派发 · 目标不可用", feedback.text());
        assertFalse(feedback.accept(target(1), 20), "a late preparation cannot resurrect a rejected target");
    }

    @Test
    void terminalPositionAloneIsCompleteAndExpiresAfterItsDeliverySpecificDuration() {
        var feedback = feedback();
        feedback.accept(finished(1, DesktopActionResult.Delivery.SENT), 100);
        assertEquals("已派发，效果待观察", feedback.text());
        assertNotNull(feedback.visibleInput(1_000_000_099L));
        assertNull(feedback.visibleInput(1_000_000_100L));
        feedback.accept(finished(2, DesktopActionResult.Delivery.MAYBE_SENT), 2_000_000_000L);
        assertTrue(feedback.text().contains("结果未知"));
        assertNotNull(feedback.visibleInput(3_999_999_999L));
        assertNull(feedback.visibleInput(4_000_000_000L));
        assertTrue(feedback.text().contains("禁止自动重试"), "the warning survives marker expiration");
    }

    @Test
    void latestOnlyNotSentStateNeedsNoActionEventToClearAnOldPointer() {
        var feedback = feedback();
        feedback.accept(finished(1, DesktopActionResult.Delivery.SENT), 0);
        feedback.accept(finished(2, DesktopActionResult.Delivery.NOT_SENT), 10);
        assertNull(feedback.visibleInput(10));
        assertEquals("未派发", feedback.text());
    }

    @Test
    void actionReceiptCanFinishAVisibleTargetBeforeTheTerminalPositionArrives() {
        var feedback = feedback();
        feedback.accept(target(1), 0);
        feedback.accept(result(1, DesktopActionResult.Status.ACCEPTED,
                DesktopActionResult.Delivery.SENT, DesktopActionResult.Reason.NONE), 100);
        assertEquals(DesktopVirtualInputState.Phase.FINISHED, feedback.visibleInput(100).phase());
        feedback.accept(finished(1, DesktopActionResult.Delivery.SENT), 500_000_000L);
        assertNull(feedback.visibleInput(1_000_000_100L), "a late duplicate must not restart expiration");
    }

    @Test
    void receiptTakesPrecedenceOverALateConflictingTerminalPosition() {
        var feedback = feedback();
        feedback.accept(result(1, DesktopActionResult.Status.DENIED,
                DesktopActionResult.Delivery.NOT_SENT, DesktopActionResult.Reason.ACCESS_DENIED), 0);
        feedback.accept(finished(1, DesktopActionResult.Delivery.SENT), 10);
        assertNull(feedback.visibleInput(10));
        assertEquals("未派发 · 权限不可用", feedback.text());
    }

    @Test
    void newerAttemptWinsEvenWithEqualOrEarlierWallClockTimesAndOldExpiryCannotClearIt() {
        var feedback = feedback();
        feedback.accept(finished(1, DesktopActionResult.Delivery.SENT), 0);
        feedback.accept(finished(2, DesktopActionResult.Delivery.SENT), 900_000_000L);
        assertFalse(feedback.accept(result(1, DesktopActionResult.Status.FAILED,
                DesktopActionResult.Delivery.NOT_SENT, DesktopActionResult.Reason.PLATFORM_FAILURE), 950_000_000L));
        assertEquals(2, feedback.visibleInput(1_000_000_000L).actionId());
        assertFalse(feedback.accept(target(2), 1_100_000_000L));
    }

    @Test
    void newStartedAttemptClearsPreviousMarker() {
        var feedback = feedback();
        feedback.accept(finished(1, DesktopActionResult.Delivery.SENT), 0);
        feedback.accept(new DesktopActionEvent("session", DesktopAction.Kind.CLICK,
                DesktopActionEvent.Phase.STARTED, null, 7, 100, 2, null,
                DesktopActionResult.Reason.NONE), 10);
        assertNull(feedback.visibleInput(10));
        assertEquals("准备操作", feedback.text());
    }

    @Test
    void generationChangeAndPauseClearFeedbackWithoutLettingOldTargetsReturn() {
        var feedback = feedback();
        feedback.accept(finished(1, DesktopActionResult.Delivery.SENT), 0);
        feedback.generation(8);
        assertNull(feedback.visibleInput(10));
        assertFalse(feedback.accept(finished(1, DesktopActionResult.Delivery.SENT), 20));
        feedback.generation(7);
        feedback.accept(finished(2, DesktopActionResult.Delivery.SENT), 30);
        feedback.clearPointer();
        assertNull(feedback.visibleInput(40));
        assertFalse(feedback.accept(target(2), 50));
    }

    private static DesktopPreviewFeedback feedback() {
        var feedback = new DesktopPreviewFeedback();
        feedback.generation(7);
        return feedback;
    }

    private static DesktopVirtualInputState target(long actionId) {
        return new DesktopVirtualInputState("session", 7, 1, 1, true, 0,
                DesktopVirtualInputState.Phase.TARGETING, 100, actionId, null);
    }

    private static DesktopVirtualInputState finished(long actionId, DesktopActionResult.Delivery delivery) {
        return new DesktopVirtualInputState("session", 7, 1, 1, true, 0,
                DesktopVirtualInputState.Phase.FINISHED, 100, actionId, delivery);
    }

    private static DesktopActionEvent result(long actionId, DesktopActionResult.Status status,
            DesktopActionResult.Delivery delivery, DesktopActionResult.Reason reason) {
        return new DesktopActionEvent("session", DesktopAction.Kind.CLICK,
                DesktopActionEvent.Phase.FINISHED, status, 7, 100, actionId, delivery, reason);
    }
}
