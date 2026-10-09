package com.javaclaw.desktop.nativebridge;

import com.javaclaw.desktop.api.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DesktopPublicGeometryTest {
    @Test void frameGeometryRejectsOutOfFrameContentAndInvalidScale() {
        var geometry = new DesktopFrameGeometry(800, 600, 20, 10, 1600, 1200, true);
        var frame = new DesktopFrame("target", 1, 1, 1640, 1220, 6560,
                new byte[6560 * 1220], 1, geometry);
        assertSame(geometry, frame.geometry());
        assertEquals(800, geometry.logicalWidth());
        assertTrue(geometry.alphaReliable());
        assertThrows(IllegalArgumentException.class, () -> new DesktopFrame("target", 1, 1,
                1600, 1200, 6400, new byte[6400 * 1200], 1, geometry));
        assertThrows(IllegalArgumentException.class,
                () -> new DesktopFrameGeometry(Double.NaN, 600, 0, 0, 1600, 1200, true));
        assertFalse(new DesktopFrameGeometry(1, 1, Integer.MAX_VALUE, 0, 1, 1, false)
                .fits(Integer.MAX_VALUE, 1), "geometry arithmetic must not wrap");
    }

    @Test void writeCapabilityAloneCannotClaimInsertionOrReplacement() {
        DesktopFrame frame = new DesktopFrame("target", 1, 1, 10, 10, 40, new byte[400], 1);
        var legacy = new DesktopElement("obs:e1", "text", "", 0, 0, 5, 5, DesktopElement.WRITE);
        var set = new DesktopElement("obs:e1", "text", "", 0, 0, 5, 5, DesktopElement.SET_TEXT);
        DesktopAction insert = new DesktopAction(DesktopAction.Kind.TYPE, 1, 1, 0, 0, 0,
                " ", 1, "obs", "obs:e1", 1, DesktopAction.TextOperation.INSERT_TEXT);
        DesktopAction clear = new DesktopAction(DesktopAction.Kind.TYPE, 1, 1, 0, 0, 0,
                "", 1, "obs", "obs:e1", 1, DesktopAction.TextOperation.SET_TEXT);
        assertFalse(DesktopInputCapabilities.forElements(frame, List.of(legacy)).supports(insert, List.of(legacy)));
        assertFalse(DesktopInputCapabilities.forElements(frame, List.of(set)).supports(insert, List.of(set)));
        assertTrue(DesktopInputCapabilities.forElements(frame, List.of(set)).supports(clear, List.of(set)));
    }
}
