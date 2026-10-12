package com.javaclaw.ui.javafx.image;

import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import com.javaclaw.platform.fx.FxDispatcher;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** 创建非模态图片查看窗口；每次打开都拥有独立 FXML 与缩放状态。 */
public final class ImageViewerFactory {

    private static final Logger log = LoggerFactory.getLogger(ImageViewerFactory.class);
    private static final URL VIEW = Objects.requireNonNull(
            ImageViewerFactory.class.getResource("/fxml/image/image-viewer.fxml"),
            "缺少 image-viewer.fxml");

    private final SpringFxmlLoader loader;
    private final FxDispatcher fx;

    public ImageViewerFactory(SpringFxmlLoader loader, FxDispatcher fx) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.fx = Objects.requireNonNull(fx, "fx");
    }

    /** 必须在 FX 线程调用；无效或无法解码的文件返回 {@code null}，不创建窗口。 */
    public ImageViewerView open(Window owner, Path file) {
        if (file == null || !Files.isRegularFile(file)) return null;
        Image image;
        try {
            image = new Image(file.toUri().toString(), false);
        } catch (RuntimeException failure) {
            log.warn("打开图片查看窗口失败: {}", file, failure);
            return null;
        }
        if (image.isError()) {
            log.warn("图片加载出错，无法查看: {}", file);
            return null;
        }

        ViewHandle<BorderPane> handle;
        try {
            handle = loader.load(VIEW);
        } catch (IOException failure) {
            throw new UncheckedIOException("加载图片查看 FXML 失败", failure);
        }
        try {
            Stage stage = new Stage();
            Window resolvedOwner = owner == null ? resolveOwner() : owner;
            if (resolvedOwner != null) {
                stage.initOwner(resolvedOwner);
                stage.initModality(Modality.NONE);
            }
            String fileName = Objects.requireNonNullElse(file.getFileName(), file).toString();
            stage.setTitle("图片查看 — " + fileName);
            Scene scene = new Scene(handle.root(), 900, 680);
            URL css = ImageViewerFactory.class.getResource("/css/chat.css");
            if (css != null) scene.getStylesheets().add(css.toExternalForm());
            stage.setScene(scene);
            ImageViewerView view = new ImageViewerView(stage, handle);
            ImageViewerController controller = handle.controller(ImageViewerController.class);
            controller.configure(image, fileName, stage::close);
            view.show();
            fx.dispatchLater(controller::fitToWindow);
            return view;
        } catch (RuntimeException | Error failure) {
            try {
                handle.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /** 优先使用聚焦窗口，其次使用任意可见窗口作为拥有者。 */
    private static Window resolveOwner() {
        Window visible = null;
        for (Window window : Window.getWindows()) {
            if (!window.isShowing()) continue;
            if (window.isFocused()) return window;
            if (visible == null) visible = window;
        }
        return visible;
    }
}
