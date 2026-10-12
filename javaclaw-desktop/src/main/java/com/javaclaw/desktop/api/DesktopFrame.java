package com.javaclaw.desktop.api;

/** Immutable BGRA premultiplied frame. Coordinates belong to this window generation. */
public record DesktopFrame(String targetId, long windowGeneration,
                           long capturedAtMillis, int width, int height, int stride,
                           byte[] bgraPremultiplied, long contentRevision, DesktopFrameGeometry geometry) {
    public DesktopFrame {
        if (width < 1 || height < 1 || stride < (long) width * 4 || bgraPremultiplied == null
                || bgraPremultiplied.length != (long) stride * height || contentRevision < 1)
            throw new IllegalArgumentException("invalid desktop frame");
        if (geometry != null && !geometry.fits(width, height))
            throw new IllegalArgumentException("frame geometry exceeds pixels");
        bgraPremultiplied = bgraPremultiplied.clone();
    }

    public DesktopFrame(String targetId, long windowGeneration, long capturedAtMillis,
                        int width, int height, int stride, byte[] bgraPremultiplied,
                        long contentRevision) {
        this(targetId, windowGeneration, capturedAtMillis, width, height, stride,
                bgraPremultiplied, contentRevision, null);
    }

    /** Compatibility constructor for in-memory fixtures and callers without a native revision. */
    public DesktopFrame(String targetId, long windowGeneration, long capturedAtMillis,
                        int width, int height, int stride, byte[] bgraPremultiplied) {
        this(targetId, windowGeneration, capturedAtMillis, width, height, stride,
                bgraPremultiplied, 1);
    }

    @Override public byte[] bgraPremultiplied() { return bgraPremultiplied.clone(); }

    /** Copies only the requested region, without exposing or cloning the entire captured frame. */
    public byte[] copyBgraRegion(int x, int y, int regionWidth, int regionHeight) {
        byte[] region = new byte[regionByteCount(x, y, regionWidth, regionHeight)];
        int rowBytes = regionWidth * 4;
        for (int row = 0; row < regionHeight; row++)
            System.arraycopy(bgraPremultiplied, (y + row) * stride + x * 4,
                    region, row * rowBytes, rowBytes);
        return region;
    }

    /** Compares compact BGRA evidence while keeping this frame's backing pixels private. */
    public boolean matchesBgraRegion(int x, int y, int regionWidth, int regionHeight, byte[] region) {
        int byteCount = regionByteCount(x, y, regionWidth, regionHeight);
        if (region == null || region.length != byteCount) return false;
        int rowBytes = regionWidth * 4;
        for (int row = 0; row < regionHeight; row++) {
            int sourceOffset = (y + row) * stride + x * 4;
            int regionOffset = row * rowBytes;
            if (!java.util.Arrays.equals(bgraPremultiplied, sourceOffset, sourceOffset + rowBytes,
                    region, regionOffset, regionOffset + rowBytes)) return false;
        }
        return true;
    }

    private int regionByteCount(int x, int y, int regionWidth, int regionHeight) {
        if (x < 0 || y < 0 || regionWidth < 1 || regionHeight < 1
                || (long) x + regionWidth > width || (long) y + regionHeight > height
                || (long) regionWidth * regionHeight > Integer.MAX_VALUE / 4)
            throw new IllegalArgumentException("frame region exceeds pixels or Java array size");
        return regionWidth * regionHeight * 4;
    }
}
