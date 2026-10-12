package com.javaclaw.ui.javafx.desktop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DesktopPreviewFrameGateTest {
    @Test
    void lateOldFrameCannotReappearAfterPauseAndResume() {
        DesktopPreviewFrameGate gate = new DesktopPreviewFrameGate();
        long inFlight = gate.version();
        assertTrue(gate.accepts(100, inFlight));

        gate.pause(200);
        assertFalse(gate.accepts(100, inFlight));
        assertFalse(gate.accepts(200, gate.version()));
        assertFalse(gate.accepts(201, inFlight), "in-flight scaling from before pause is invalid");
        assertTrue(gate.accepts(201, gate.version()), "newer capture may resume the preview");

        long beforeDispose = gate.version();
        gate.invalidate();
        assertFalse(gate.accepts(201, beforeDispose));
    }
}
