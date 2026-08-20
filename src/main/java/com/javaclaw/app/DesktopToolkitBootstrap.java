package com.javaclaw.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.GraphicsEnvironment;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.util.Locale;
import java.util.Objects;

/** Prepares the macOS AWT application before JavaFX takes ownership of AppKit. */
final class DesktopToolkitBootstrap {

    static final String TEMPLATE_IMAGES_PROPERTY = "apple.awt.enableTemplateImages";
    private static final String UI_TEST_PROPERTY = "javaclaw.ui.test";
    private static final Logger log = LoggerFactory.getLogger(DesktopToolkitBootstrap.class);
    private static volatile boolean preparedForJavaFx;

    private DesktopToolkitBootstrap() {
    }

    /**
     * On macOS, AWT must join the native application before JavaFX starts. Initializing it later
     * can leave a {@link java.awt.TrayIcon} in Java's collection without a visible status item.
     */
    static boolean prepareForJavaFxLaunch() {
        boolean prepared = prepareForJavaFxLaunch(
                System.getProperty("os.name", ""),
                Boolean.getBoolean(UI_TEST_PROPERTY),
                GraphicsEnvironment.isHeadless(),
                () -> {
                    Toolkit.getDefaultToolkit();
                    if (SystemTray.isSupported()) SystemTray.getSystemTray();
                });
        preparedForJavaFx = prepared;
        return prepared;
    }

    static boolean isPreparedForJavaFx() {
        return preparedForJavaFx;
    }

    static boolean prepareForJavaFxLaunch(
            String osName, boolean uiTest, boolean headless, Runnable initializeAwt) {
        Objects.requireNonNull(initializeAwt, "initializeAwt");
        if (uiTest || headless || !isMac(osName)) return false;

        // CTrayIcon reads this property once when its native peer class initializes. Set it before
        // touching Toolkit so the monochrome artwork becomes a native light/dark template image.
        System.setProperty(TEMPLATE_IMAGES_PROPERTY, "true");
        try {
            initializeAwt.run();
            log.info("macOS AWT 已在 JavaFX 启动前初始化，托盘使用原生模板图标");
            return true;
        } catch (Throwable failure) {
            log.warn("macOS AWT 预初始化失败，关闭窗口时将回退为完整退出: {}",
                    failure.getMessage(), failure);
            return false;
        }
    }

    private static boolean isMac(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("mac");
    }
}
