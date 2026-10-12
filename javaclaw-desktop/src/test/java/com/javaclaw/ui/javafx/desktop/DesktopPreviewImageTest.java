package com.javaclaw.ui.javafx.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javaclaw.desktop.api.DesktopFrame;
import java.awt.image.DataBufferInt;
import org.junit.jupiter.api.Test;

class DesktopPreviewImageTest {
    @Test
    void preservesPremultipliedNativeAlphaAndSkipsStridePadding() {
        byte[] bytes = {16, 32, 64, (byte) 128, 0, 0, 0, 0, 99, 99, 99, 99,
                3, 2, 1, (byte) 255, 8, 7, 6, (byte) 255, 99, 99, 99, 99};
        var image = DesktopPreviewImage.convert(new DesktopFrame("target", 1, 1, 2, 2, 12, bytes));
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        assertTrue(image.isAlphaPremultiplied());
        assertEquals(0x80402010, pixels[0]);
        assertEquals(0, pixels[1]);
        assertEquals(0xff010203, pixels[2]);
        assertEquals(0xff060708, pixels[3]);
    }
}
