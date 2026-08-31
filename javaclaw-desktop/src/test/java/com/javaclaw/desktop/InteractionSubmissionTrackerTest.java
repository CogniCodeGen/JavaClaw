package com.javaclaw.desktop;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractionSubmissionTrackerTest {
    @Test
    void acceptedRequestStaysBlockedUntilPersistentItemResolves() {
        var tracker = new InteractionSubmissionTracker();

        assertTrue(tracker.begin("approval-1"));
        assertFalse(tracker.begin("approval-1"));
        tracker.completed("approval-1", true);
        assertTrue(tracker.blocked("approval-1"));
        assertFalse(tracker.begin("approval-1"));

        tracker.reconcile(Set.of("approval-1"));
        assertFalse(tracker.blocked("approval-1"));
    }

    @Test
    void rejectedOrFailedRequestCanBeRetriedWithoutASecondInFlightSideEffect() {
        var tracker = new InteractionSubmissionTracker();

        assertTrue(tracker.begin("input-1"));
        tracker.completed("input-1", false);
        assertTrue(tracker.begin("input-1"));
        tracker.failed("input-1");
        assertFalse(tracker.blocked("input-1"));
        assertTrue(tracker.begin("input-1"));
    }
}
