package com.javaclaw.desktop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscriptFollowStateTest {
    @Test
    void userScrollPreventsPositionStealingAndCountsLogicalMessagesOnly() {
        var state = new TranscriptFollowState();
        state.reset(2, false);
        state.userScrolledUp();

        assertEquals(1, state.contentChanged(2, true, true).unread());
        assertEquals(1, state.contentChanged(2, true, true).unread(), "同一流式消息的 token delta 不重复累计");
        var update = state.contentChanged(3, false, true);

        assertFalse(update.following());
        assertEquals(2, update.unread());
    }

    @Test
    void returningNearTheBottomRestoresFollowingAndClearsUnread() {
        var state = new TranscriptFollowState();
        state.reset(1, false);
        state.userScrolledUp();
        state.contentChanged(2, false, true);

        var update = state.viewportChanged(0.5, 1.0, 1.0);

        assertTrue(update.following());
        assertEquals(0, update.unread());
    }
}
