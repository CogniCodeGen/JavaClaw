package com.javaclaw.desktop.settings;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.DialogPane;
import javafx.scene.image.PixelFormat;
import javafx.util.Duration;

import com.javaclaw.desktop.FxTestSupport;

/** 固定数据的登记窗口证据入口；只展示生产控件，不连接浏览器、服务器或模型。 */
public final class SiteRegistrationWindowEvidence {
    private SiteRegistrationWindowEvidence() {}

    /**
     * 在常规与窄窗口中输出真实 JavaFX 截图。
     *
     * @param arguments 不读取；输出目录由 javaclaw.browser.evidence 指定
     */
    public static void main(String[] arguments) {
        try {
            FxTestSupport.run(() -> {
                for (int width : List.of(480, 640)) {
                    var gateway = new SiteRegistrationTestGateway();
                    var dialog = SiteRegistrationDialogTest.dialog(gateway);
                    try {
                        DialogPane pane =
                                SiteRegistrationDialogTest.registrationWindows().getFirst();
                        pane.getScene().getWindow().setWidth(width);
                        pane.getScene().getWindow().setHeight(width == 480 ? 400 : 620);
                        capture(pane, "registration-empty-" + width + ".png");
                        SiteRegistrationDialogTest.field(pane, "添加网站地址").setText("https://z.example.com/login");
                        SiteRegistrationDialogTest.button(pane, "打开隔离浏览器").fire();
                        capture(pane, "registration-active-" + width + ".png");
                    } finally {
                        dialog.dispose();
                    }
                }
            });
        } finally {
            Platform.exit();
        }
    }

    static void capture(DialogPane pane, String name) {
        String directory = System.getProperty("javaclaw.browser.evidence", "");
        if (directory.isEmpty()) {
            return;
        }
        pane.applyCss();
        pane.layout();
        Object key = new Object();
        PauseTransition pulse = new PauseTransition(Duration.millis(80));
        pulse.setOnFinished(ignored -> Platform.exitNestedEventLoop(key, null));
        pulse.play();
        Platform.enterNestedEventLoop(key);
        var snapshot = pane.getScene().snapshot(null);
        int width = (int) snapshot.getWidth();
        int height = (int) snapshot.getHeight();
        int[] pixels = new int[width * height];
        snapshot.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, pixels, 0, width);
        try {
            Path target = Path.of(directory).resolve(name);
            Files.createDirectories(target.getParent());
            ImageIO.write(image, "png", target.toFile());
        } catch (Exception failure) {
            throw new IllegalStateException("无法保存网站登记窗口证据", failure);
        }
    }
}
