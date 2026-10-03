package com.javaclaw.desktop.api;

/** Immutable BGRA premultiplied frame. Coordinates belong to this window generation. */
public record DesktopFrame(String targetId, long windowGeneration,
                           long capturedAtMillis, int width, int height, int stride,
                           byte[] bgraPremultiplied, long contentRevision) {
    public DesktopFrame {
        if (width < 1 || height < 1 || stride < width * 4 || bgraPremultiplied == null
                || bgraPremultiplied.length != (long) stride * height || contentRevision < 1)
            throw new IllegalArgumentException("invalid desktop frame");
        bgraPremultiplied = bgraPremultiplied.clone();
    }

    /** Compatibility constructor for in-memory fixtures and callers without a native revision. */
    public DesktopFrame(String targetId, long windowGeneration, long capturedAtMillis,
                        int width, int height, int stride, byte[] bgraPremultiplied) {
        this(targetId, windowGeneration, capturedAtMillis, width, height, stride,
                bgraPremultiplied, 1);
    }

    @Override public byte[] bgraPremultiplied() { return bgraPremultiplied.clone(); }
}
