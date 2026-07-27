package com.javaclaw.ui.javafx;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;

/** 读取与主窗口共用的品牌图标资源，不初始化系统托盘。 */
final class TrayIconImageLoader {

    private static final String RESOURCE = "/images/javaclaw-app-icon-capabilities.png";

    private TrayIconImageLoader() {
    }

    static BufferedImage load() throws IOException {
        URL resource = TrayIconImageLoader.class.getResource(RESOURCE);
        if (resource == null) {
            throw new IOException("未找到托盘图标资源 " + RESOURCE);
        }
        BufferedImage source = ImageIO.read(resource);
        if (source == null) {
            throw new IOException("无法读取托盘图标资源 " + RESOURCE);
        }
        return source;
    }
}
