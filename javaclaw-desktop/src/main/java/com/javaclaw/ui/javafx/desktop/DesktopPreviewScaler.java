package com.javaclaw.ui.javafx.desktop;

import com.javaclaw.desktop.api.DesktopFrame;

/** Converts a full-resolution capture to a small FX-ready BGRA buffer off the FX thread. */
final class DesktopPreviewScaler {
    private DesktopPreviewScaler() {}

    static ScaledFrame scale(DesktopFrame frame, int maxWidth, int maxHeight) {
        if (maxWidth < 1 || maxHeight < 1) throw new IllegalArgumentException("invalid preview size");
        double ratio = Math.min(1.0, Math.min((double) maxWidth / frame.width(),
                (double) maxHeight / frame.height()));
        int width = Math.max(1, (int) Math.round(frame.width() * ratio));
        int height = Math.max(1, (int) Math.round(frame.height() * ratio));
        byte[] source = frame.bgraPremultiplied();
        byte[] pixels = new byte[width * height * 4];
        for (int y = 0; y < height; y++) {
            int sourceY = Math.min(frame.height() - 1,
                    (int) (((long) y * 2 + 1) * frame.height() / (height * 2L)));
            for (int x = 0; x < width; x++) {
                int sourceX = Math.min(frame.width() - 1,
                        (int) (((long) x * 2 + 1) * frame.width() / (width * 2L)));
                int sourceOffset = sourceY * frame.stride() + sourceX * 4;
                int destinationOffset = (y * width + x) * 4;
                System.arraycopy(source, sourceOffset, pixels, destinationOffset, 4);
            }
        }
        return new ScaledFrame(frame.windowGeneration(), frame.capturedAtMillis(),
                width, height, pixels);
    }

    record ScaledFrame(long generation, long capturedAtMillis, int width, int height, byte[] bgra) {}
}
