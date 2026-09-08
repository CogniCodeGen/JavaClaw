package com.javaclaw.desktop.acceptance.chatdocument;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.web.WebView;

import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.protocol.CanonicalJson;

/** 原始 Scene 像素与描述并存的验收产物；不修改或比较既有 Golden 基线。 */
public final class AcceptanceCapture {
    private AcceptanceCapture() {}

    /**
     * 保存真实 Scene 快照；此证据不代表操作系统窗口合成或其他平台显示效果。
     *
     * @param scene 已展示的真实 Scene
     * @param name 稳定场景名，不含路径分隔符
     * @param observations 本场景的实际断言、对照信息或测量
     */
    public static void save(Scene scene, String name, Map<String, ?> observations) {
        WritableImage image = paintedScene(scene);
        BufferedImage pixels =
                new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < pixels.getHeight(); y++) {
            for (int x = 0; x < pixels.getWidth(); x++) {
                pixels.setRGB(x, y, image.getPixelReader().getArgb(x, y));
            }
        }
        try {
            Path directory =
                    Path.of(System.getProperty("javaclaw.acceptance.output", "target/acceptance/chat-document"));
            Files.createDirectories(directory);
            Path png = directory.resolve(name + ".png");
            ImageIO.write(pixels, "png", png.toFile());
            Map<String, Object> index = new LinkedHashMap<>();
            index.put("scene", name);
            index.put("png", png.getFileName().toString());
            index.put("width", pixels.getWidth());
            index.put("height", pixels.getHeight());
            index.put(
                    "sha256",
                    HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(png))));
            index.put("javafx", System.getProperty("javafx.version", "unknown"));
            index.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
            index.put("observations", observations);
            Files.writeString(
                    directory.resolve(name + ".json"),
                    new CanonicalJson().encode(index).json());
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new AssertionError("不能保存验收原始产物", failure);
        }
    }

    private static WritableImage paintedScene(Scene scene) {
        AtomicReference<WritableImage> accepted = new AtomicReference<>();
        // WebView 自身快照与 Scene 合成也可能相差一帧；必须验证将要保存的同一张 Scene 图。
        FxTestSupport.await(() -> FxTestSupport.call(() -> {
            WritableImage frame = scene.snapshot(null);
            boolean complete = scene.getRoot().lookupAll(".web-view").stream()
                    .filter(WebView.class::isInstance)
                    .map(WebView.class::cast)
                    .filter(AcceptanceCapture::visible)
                    .allMatch(web -> painted(frame, web));
            if (complete) {
                accepted.set(frame);
            }
            return complete;
        }));
        return accepted.get();
    }

    private static boolean visible(WebView web) {
        for (Node node = web; node != null; node = node.getParent()) {
            if (!node.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private static boolean painted(WritableImage image, WebView web) {
        Object content = web.getEngine()
                .executeScript("Boolean(document.querySelector('#surface')?.textContent.trim() ||"
                        + " document.querySelector('#surface img'))");
        if (!Boolean.TRUE.equals(content)) {
            return true;
        }
        Bounds bounds = web.localToScene(web.getBoundsInLocal());
        int left = Math.max(0, (int) bounds.getMinX() + 10);
        int top = Math.max(0, (int) bounds.getMinY() + 10);
        int right = Math.min((int) image.getWidth(), (int) bounds.getMaxX() - 10);
        int bottom = Math.min((int) image.getHeight(), (int) bounds.getMaxY() - 10);
        if (left >= right || top >= bottom) {
            return true;
        }
        int first = image.getPixelReader().getArgb(left, top);
        // 排除原生标题与边缘滚动条，不能用它们证明正文区域已经绘制。
        for (int y = top; y < bottom; y += 3) {
            for (int x = left; x < right; x += 3) {
                if (image.getPixelReader().getArgb(x, y) != first) {
                    return true;
                }
            }
        }
        return false;
    }
}
