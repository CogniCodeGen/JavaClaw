package com.javaclaw.desktop.api;

/** A value-only accessibility element scoped to one desktop observation. */
public record DesktopElement(String id, String role, String label, int x, int y,
                             int width, int height, int actions) {
    public static final int PRESS = 1;
    public static final int WRITE = 2;
    public static final int SCROLL = 4;
    public static final int INSERT_TEXT = 8;
    public static final int SET_TEXT = 16;

    public DesktopElement {
        if (id == null || id.isBlank() || x < 0 || y < 0 || width < 1 || height < 1)
            throw new IllegalArgumentException("invalid desktop element");
        role = role == null ? "" : role;
        label = label == null ? "" : label;
    }

    public int centerX() { return x + width / 2; }
    public int centerY() { return y + height / 2; }
}
