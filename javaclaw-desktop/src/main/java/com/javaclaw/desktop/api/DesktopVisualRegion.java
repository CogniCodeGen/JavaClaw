package com.javaclaw.desktop.api;

/** A visual target bound to one observed frame; labels are untrusted screen data. */
public record DesktopVisualRegion(String id, String role, String label,
                                  int x, int y, int width, int height,
                                  double confidence) {
    public DesktopVisualRegion {
        if (id == null || id.isBlank() || id.length() > 128
                || x < 0 || y < 0 || width < 1 || height < 1
                || !Double.isFinite(confidence) || confidence < 0 || confidence > 1)
            throw new IllegalArgumentException("invalid desktop visual region");
        role = role == null ? "" : role;
        label = label == null ? "" : label;
        if (role.length() > 64 || label.length() > 256)
            throw new IllegalArgumentException("desktop visual label is too long");
    }

    public int centerX() { return x + width / 2; }
    public int centerY() { return y + height / 2; }
}
