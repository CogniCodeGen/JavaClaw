package com.javaclaw.agent.vision;

/** A visual candidate in the original desktop frame's pixel coordinates. */
public record DesktopVisualTarget(
        String label,
        String role,
        int x,
        int y,
        int width,
        int height,
        double confidence) {

    public int centerX() {
        return x + width / 2;
    }

    public int centerY() {
        return y + height / 2;
    }
}
