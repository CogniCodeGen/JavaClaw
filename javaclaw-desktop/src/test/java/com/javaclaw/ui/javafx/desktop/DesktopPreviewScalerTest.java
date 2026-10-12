package com.javaclaw.ui.javafx.desktop;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaclaw.desktop.api.DesktopFrame;
import org.junit.jupiter.api.Test;

class DesktopPreviewScalerTest {
    @Test
    void downscalesOffscreenBufferWithinPreviewBoundsAndPreservesGeneration() {
        byte[] pixels = new byte[16 * 2];
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 4; x++) {
                int offset = y * 16 + x * 4;
                pixels[offset] = (byte) (y * 10 + x);
                pixels[offset + 3] = (byte) 255;
            }
        }
        DesktopFrame full = new DesktopFrame("target", 7, 1234, 4, 2, 16, pixels);
        DesktopPreviewScaler.ScaledFrame small = DesktopPreviewScaler.scale(full, 2, 2);
        assertEquals(7, small.generation());
        assertEquals(1234, small.capturedAtMillis());
        assertEquals(2, small.width());
        assertEquals(1, small.height());
        assertArrayEquals(new byte[] {11, 0, 0, (byte) 255, 13, 0, 0, (byte) 255},
                small.bgra());
        assertTrue(small.bgra().length <= 2 * 2 * 4);
    }

    @Test
    void 视口放大时重新使用原始帧且不会超出原图分辨率() {
        byte[] pixels = new byte[1600 * 900 * 4];
        DesktopFrame source = new DesktopFrame("target", 8, 5678, 1600, 900, 1600 * 4, pixels);
        DesktopPreviewScaler.ScaledFrame mini = DesktopPreviewScaler.scale(source, 240, 120);
        DesktopPreviewScaler.ScaledFrame large = DesktopPreviewScaler.scale(source, 1200, 800);
        DesktopPreviewScaler.ScaledFrame full = DesktopPreviewScaler.scale(source, 3200, 1800);
        assertTrue(large.width() > mini.width());
        assertEquals(1200, large.width());
        assertEquals(675, large.height());
        assertEquals(1600, full.width());
        assertEquals(900, full.height());
        assertEquals(source.windowGeneration(), full.generation());
        assertEquals(source.capturedAtMillis(), full.capturedAtMillis());
    }
}
