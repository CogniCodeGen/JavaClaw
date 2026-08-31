package com.javaclaw.desktop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComposerActivityTest {
    @Test
    void exposesOperationSpecificCapabilities() {
        var idle = ComposerActivity.idle();
        var active = new ComposerActivity(ComposerActivity.Phase.ACTIVE, "turn");
        var waiting = new ComposerActivity(ComposerActivity.Phase.WAITING_INTERACTION, "turn");

        assertTrue(idle.allowsAttachments());
        assertFalse(idle.hasActiveTurn());
        assertTrue(active.acceptsSteering());
        assertTrue(active.canInterrupt());
        assertFalse(waiting.acceptsSteering());
        assertTrue(waiting.canInterrupt());
    }

    @Test
    void rejectsTurnStatesWithoutATurnIdentifier() {
        assertThrows(IllegalArgumentException.class, () -> new ComposerActivity(ComposerActivity.Phase.ACTIVE, ""));
    }
}
