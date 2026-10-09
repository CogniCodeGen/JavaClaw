package com.javaclaw.ui.javafx.desktop;

import com.javaclaw.desktop.api.DesktopFrame;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/** Converts premultiplied BGRA without unpremultiplying or discarding native alpha. */
final class DesktopPreviewImage {
    private DesktopPreviewImage() { }

    static BufferedImage convert(DesktopFrame frame) {
        BufferedImage image = new BufferedImage(frame.width(), frame.height(),
                BufferedImage.TYPE_INT_ARGB_PRE);
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        byte[] bytes = frame.bgraPremultiplied();
        for (int y = 0; y < frame.height(); y++) {
            int offset = y * frame.stride();
            int output = y * frame.width();
            for (int x = 0; x < frame.width(); x++, offset += 4) {
                pixels[output + x] = (bytes[offset + 3] & 255) << 24
                        | (bytes[offset + 2] & 255) << 16
                        | (bytes[offset + 1] & 255) << 8 | bytes[offset] & 255;
            }
        }
        return image;
    }
}
