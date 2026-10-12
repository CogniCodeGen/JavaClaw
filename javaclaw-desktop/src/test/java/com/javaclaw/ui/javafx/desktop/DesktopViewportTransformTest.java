package com.javaclaw.ui.javafx.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaclaw.desktop.api.DesktopFrame;
import com.javaclaw.desktop.api.DesktopFrameGeometry;
import java.awt.geom.Point2D;
import org.junit.jupiter.api.Test;

class DesktopViewportTransformTest {
    @Test
    void retinaPixelsAndCaptureInsetsMapToLogicalContentAndRoundTrip() {
        DesktopFrame frame = new DesktopFrame("target", 1, 1, 2004, 1006, 8016,
                new byte[8016 * 1006], 1, new DesktopFrameGeometry(1000, 500, 2, 3, 2000, 1000, true));
        DesktopViewportTransform mapping = DesktopViewportTransform.fit(frame, 10, 20, 1000, 500);
        assertTrue(mapping.geometryKnown());
        assertEquals(1000, mapping.width());
        Point2D.Double displayed = mapping.sourceToView(1002, 503);
        assertEquals(510, displayed.x);
        assertEquals(270, displayed.y);
        Point2D.Double source = mapping.viewToSource(1, displayed.x, displayed.y).orElseThrow();
        assertEquals(1002, source.x);
        assertEquals(503, source.y);
        assertFalse(mapping.containsSource(0, 0));
        assertTrue(mapping.viewToSource(2, displayed.x, displayed.y).isEmpty(),
                "a coordinate from another frame generation cannot be reused");
    }

    @Test
    void letterboxBarsNeverMapToTargetAndMissingGeometryIsExplicit() {
        DesktopFrame frame = new DesktopFrame("target", 1, 1, 200, 100, 800, new byte[80000]);
        DesktopViewportTransform mapping = DesktopViewportTransform.fit(frame, 0, 0, 100, 100);
        assertFalse(mapping.geometryKnown());
        assertEquals(25, mapping.y());
        assertEquals(50, mapping.height());
        assertTrue(mapping.viewToSource(1, 50, 10).isEmpty());
        assertTrue(mapping.viewToSource(1, 100, 50).isEmpty());
        assertEquals(100, mapping.viewToSource(1, 50, 50).orElseThrow().x);
    }

    @Test
    void normalSourceScaleKeepsSmallTargetsAtOneToOneButStillShrinksOversizedTargets() {
        DesktopFrame small = new DesktopFrame("target", 1, 1, 80, 40, 320, new byte[12800], 1,
                new DesktopFrameGeometry(40, 20, 0, 0, 80, 40, true));
        DesktopViewportTransform mapping = DesktopViewportTransform.fit(small, 0, 0, 238, 80, 1);
        assertEquals(40, mapping.width());
        assertEquals(20, mapping.height());
        assertEquals(99, mapping.x());
        assertEquals(30, mapping.y());
        DesktopViewportTransform smallerScreen = DesktopViewportTransform.fit(small, 0, 0, 20, 8, 1);
        assertEquals(16, smallerScreen.width());
        assertEquals(8, smallerScreen.height());
    }
}
