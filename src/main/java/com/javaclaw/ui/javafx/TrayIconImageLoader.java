package com.javaclaw.ui.javafx;

import java.awt.Dimension;
import java.awt.image.BufferedImage;

/** Renders a compact transparent brand mark specifically for the operating-system tray. */
final class TrayIconImageLoader {

    private static final int DEFAULT_SIZE = 24;
    private static final int SAMPLES_PER_AXIS = 4;
    private static final int OUTLINE = 0xff132969;
    private static final int CENTER = 0xff6366f1;
    private static final int[] SEGMENT_COLORS = {
            0xff22d3ee,
            0xff3b82f6,
            0xff7c3aed
    };

    private TrayIconImageLoader() {
    }

    static BufferedImage load() {
        return load(new Dimension(DEFAULT_SIZE, DEFAULT_SIZE));
    }

    /**
     * Uses a small supersampled software rasterizer rather than {@code Graphics2D}; loading the
     * artwork therefore cannot initialize the native AWT toolkit before tray installation.
     */
    static BufferedImage load(Dimension requested) {
        return load(requested, false);
    }

    /** Produces black-and-alpha artwork suitable for macOS native template rendering. */
    static BufferedImage loadTemplate(Dimension requested) {
        return load(requested, true);
    }

    private static BufferedImage load(Dimension requested, boolean template) {
        int width = Math.max(16, requested == null ? DEFAULT_SIZE : requested.width);
        int height = Math.max(16, requested == null ? DEFAULT_SIZE : requested.height);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        double size = Math.min(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, samplePixel(x, y, width, height, size, template));
            }
        }
        return image;
    }

    private static int samplePixel(
            int x, int y, int width, int height, double size, boolean template) {
        int alpha = 0;
        int red = 0;
        int green = 0;
        int blue = 0;
        int samples = SAMPLES_PER_AXIS * SAMPLES_PER_AXIS;
        for (int sampleY = 0; sampleY < SAMPLES_PER_AXIS; sampleY++) {
            for (int sampleX = 0; sampleX < SAMPLES_PER_AXIS; sampleX++) {
                double pixelX = x + (sampleX + 0.5) / SAMPLES_PER_AXIS;
                double pixelY = y + (sampleY + 0.5) / SAMPLES_PER_AXIS;
                double normalizedX = (pixelX - width / 2.0) * 2.0 / size;
                double normalizedY = (height / 2.0 - pixelY) * 2.0 / size;
                int color = colorAt(normalizedX, normalizedY);
                if (color == 0) continue;
                if (template) color = 0xff000000;
                alpha += 255;
                red += color >>> 16 & 0xff;
                green += color >>> 8 & 0xff;
                blue += color & 0xff;
            }
        }
        if (alpha == 0) return 0;
        int covered = alpha / 255;
        return alpha / samples << 24
                | red / covered << 16
                | green / covered << 8
                | blue / covered;
    }

    private static int colorAt(double x, double y) {
        double radius = Math.hypot(x, y);
        if (radius <= 0.13) return CENTER;
        if (radius <= 0.18) return OUTLINE;

        int segment = segmentAt(Math.toDegrees(Math.atan2(y, x)));
        if (segment < 0) return 0;
        double ringDistance = Math.abs(radius - 0.62);
        if (ringDistance <= 0.105) return SEGMENT_COLORS[segment];
        if (ringDistance <= 0.15) return OUTLINE;
        return 0;
    }

    private static int segmentAt(double angle) {
        double normalized = angle < 0 ? angle + 360.0 : angle;
        double[] centers = {60.0, 180.0, 300.0};
        for (int index = 0; index < centers.length; index++) {
            double distance = Math.abs(normalized - centers[index]);
            distance = Math.min(distance, 360.0 - distance);
            if (distance <= 39.0) return index;
        }
        return -1;
    }
}
