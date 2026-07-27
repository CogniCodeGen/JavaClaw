package com.javaclaw.ui.javafx;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemTrayManagerTest {

    @Test
    void 托盘加载与主窗口相同的新版品牌图标() throws Exception {
        BufferedImage icon = TrayIconImageLoader.load();

        assertEquals(1254, icon.getWidth());
        assertEquals(1254, icon.getHeight());
        int center = icon.getRGB(icon.getWidth() / 2, icon.getHeight() / 2);
        int red = center >>> 16 & 0xff;
        int green = center >>> 8 & 0xff;
        int blue = center & 0xff;
        assertTrue(blue > green && blue > red, "中心应为新版蓝紫色品牌图形");
    }
}
