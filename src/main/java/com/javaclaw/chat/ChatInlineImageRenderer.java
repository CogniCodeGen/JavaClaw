package com.javaclaw.chat;

import com.javaclaw.ui.javafx.image.ImageViewerFactory;
import com.javaclaw.util.ProjectAccessPolicy;
import javafx.scene.Cursor;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Renders project-local image references discovered in chat text.
 *
 * <p>The renderer is stateless and must be called on the JavaFX application thread. Invalid,
 * inaccessible, and missing paths are ignored. It creates only dynamic image content; the owning
 * message and history layouts remain defined by FXML.</p>
 */
public final class ChatInlineImageRenderer {

    private static final Logger log = LoggerFactory.getLogger(ChatInlineImageRenderer.class);
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "bmp", "webp");
    private static final double PREVIEW_WIDTH = 400;

    private final ImageViewerFactory imageViewer;

    public ChatInlineImageRenderer(ImageViewerFactory imageViewer) {
        this.imageViewer = java.util.Objects.requireNonNull(imageViewer, "imageViewer");
    }

    /** Adds newly discovered images to a message container and records their canonical text paths. */
    public void displayInline(String text, VBox container, Set<String> displayedPaths) {
        if (container == null || text == null || displayedPaths == null) {
            return;
        }
        for (String path : extractPaths(text)) {
            if (displayedPaths.contains(path)) {
                continue;
            }
            ImageView preview = loadPreview(new File(path));
            if (preview != null) {
                container.getChildren().add(preview);
                displayedPaths.add(path);
                log.info("已内联显示图片: {}", path);
            }
        }
    }

    /** Loads persisted message images, omitting paths that are no longer safe or readable. */
    public List<ImageView> loadPersisted(List<String> paths) {
        List<ImageView> images = new ArrayList<>();
        if (paths == null) {
            return images;
        }
        for (String path : paths) {
            if (path == null) {
                continue;
            }
            ImageView preview = loadPreview(new File(path));
            if (preview != null) {
                images.add(preview);
            }
        }
        return images;
    }

    /** Enables the shared double-click image viewer behavior for an existing preview. */
    public void enableZoom(ImageView imageView, File file) {
        if (imageView == null || file == null) {
            return;
        }
        imageView.setCursor(Cursor.HAND);
        Tooltip.install(imageView, new Tooltip("双击查看大图（可缩放/拖拽）"));
        imageView.setOnMouseClicked(event -> {
            if (event.getButton() != MouseButton.PRIMARY || event.getClickCount() != 2) {
                return;
            }
            javafx.stage.Window owner = imageView.getScene() == null
                    ? null : imageView.getScene().getWindow();
            imageViewer.open(owner, file.toPath());
            event.consume();
        });
    }

    private ImageView loadPreview(File file) {
        if (!isReadableProjectImage(file)) {
            return null;
        }
        try {
            Image image = new Image(file.toURI().toString(), PREVIEW_WIDTH, 0, true, true);
            ImageView imageView = new ImageView(image);
            imageView.setFitWidth(PREVIEW_WIDTH);
            imageView.setPreserveRatio(true);
            imageView.setSmooth(true);
            imageView.getStyleClass().add("screenshot-image");
            enableZoom(imageView, file);
            return imageView;
        } catch (RuntimeException failure) {
            log.warn("图片预览加载失败: {}", file, failure);
            return null;
        }
    }

    private boolean isReadableProjectImage(File file) {
        return file != null
                && ProjectAccessPolicy.isProjectFilePath(file.toPath())
                && file.isFile();
    }

    static List<String> extractPaths(String text) {
        List<String> paths = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return paths;
        }
        int cursor = 0;
        while (cursor < text.length()) {
            if (text.charAt(cursor) != '/'
                    || (cursor > 0 && isPathCharacter(text.charAt(cursor - 1)))) {
                cursor++;
                continue;
            }
            int start = cursor++;
            while (cursor < text.length() && isPathCharacter(text.charAt(cursor))) {
                cursor++;
            }
            String candidate = text.substring(start, cursor);
            if (hasImageExtension(candidate)) {
                paths.add(candidate);
            }
        }
        return paths;
    }

    private static boolean isPathCharacter(char value) {
        return Character.isLetterOrDigit(value)
                || value == '/' || value == '.' || value == '_'
                || value == '-' || value == '~' || value == '+';
    }

    private static boolean hasImageExtension(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0
                && dot < path.length() - 1
                && IMAGE_EXTENSIONS.contains(path.substring(dot + 1).toLowerCase(java.util.Locale.ROOT));
    }
}
