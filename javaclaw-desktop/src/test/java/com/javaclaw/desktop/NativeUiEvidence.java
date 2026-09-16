package com.javaclaw.desktop;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.util.Duration;

/** 仅在显式证据模式下展示真实 JavaFX 控件并输出截图，不连接外部服务。 */
public final class NativeUiEvidence {
    private NativeUiEvidence() {}

    /**
     * @param root 真实生产控件树
     * @param name 不含路径的证据文件名
     */
    public static void capture(Parent root, String name) {
        String directory = System.getProperty("javaclaw.browser.evidence", "");
        if (directory.isEmpty()) {
            return;
        }
        Scene scene = root.getScene();
        DesktopStylesheets.apply(scene);
        Stage window = new Stage();
        window.setTitle("JavaClaw · 浏览器账号交互验证");
        window.setScene(scene);
        try {
            window.show();
            root.applyCss();
            root.layout();
            awaitRender();
            var snapshot = scene.snapshot(null);
            int width = (int) snapshot.getWidth();
            int height = (int) snapshot.getHeight();
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            int[] pixels = new int[width * height];
            snapshot.getPixelReader()
                    .getPixels(
                            0, 0, width, height, javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, width);
            image.setRGB(0, 0, width, height, pixels, 0, width);
            Path target = Path.of(directory).resolve(name);
            Files.createDirectories(target.getParent());
            ImageIO.write(image, "png", target.toFile());
        } catch (Exception failure) {
            throw new IllegalStateException("无法保存 JavaFX 界面证据", failure);
        } finally {
            window.close();
        }
    }

    private static void awaitRender() {
        // 同一 Scene 在多个证据窗口间复用时，等待正常 pulse 提交控件皮肤和渲染树，避免截取上一个分区。
        Object key = new Object();
        PauseTransition pulse = new PauseTransition(Duration.millis(80));
        pulse.setOnFinished(ignored -> Platform.exitNestedEventLoop(key, null));
        pulse.play();
        Platform.enterNestedEventLoop(key);
    }
}
