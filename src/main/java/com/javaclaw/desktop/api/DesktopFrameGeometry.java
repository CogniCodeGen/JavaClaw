package com.javaclaw.desktop.api;

/** Geometry captured atomically with a frame; logical dimensions are device-independent. */
public record DesktopFrameGeometry(double logicalWidth, double logicalHeight,
        int contentX, int contentY, int contentWidth, int contentHeight, boolean alphaReliable) {
    public DesktopFrameGeometry {
        if (!Double.isFinite(logicalWidth) || !Double.isFinite(logicalHeight)
                || logicalWidth <= 0 || logicalHeight <= 0 || contentX < 0 || contentY < 0
                || contentWidth < 1 || contentHeight < 1)
            throw new IllegalArgumentException("invalid frame geometry");
    }

    public boolean fits(int frameWidth, int frameHeight) {
        return (long) contentX + contentWidth <= frameWidth
                && (long) contentY + contentHeight <= frameHeight;
    }
}
