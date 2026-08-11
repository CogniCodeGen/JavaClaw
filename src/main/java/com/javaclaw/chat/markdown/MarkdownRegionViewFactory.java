package com.javaclaw.chat.markdown;

import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import com.javaclaw.platform.fx.FxDispatcher;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.Objects;

/**
 * 将 Markdown 中的延迟区域填充到 FXML 定义的固定结构中。
 *
 * <p>本对象由 Spring 单例管理，但每次调用都会创建独立视图。调用只允许发生在
 * JavaFX Application Thread；解析阶段可以安全地捕获本工厂，但不得提前调用。</p>
 */
public final class MarkdownRegionViewFactory {

    private static final String FXML_ROOT = "/fxml/chat/markdown/";

    private final SpringFxmlLoader loader;
    private final FxDispatcher fx;

    public MarkdownRegionViewFactory(SpringFxmlLoader loader, FxDispatcher fx) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.fx = Objects.requireNonNull(fx, "fx");
    }

    Region createCodeCard(
            String code,
            String language,
            MarkdownParagraphRenderer.RenderStyleSnapshot style) {
        requireFxThread();
        VBox root = load("code-card.fxml", VBox.class);
        Label languageLabel = requireNode(root, "codeLanguage", Label.class);
        Button copyButton = requireNode(root, "copyCode", Button.class);
        TextFlow codeFlow = requireNode(root, "codeFlow", TextFlow.class);

        languageLabel.setText(language == null || language.isEmpty() ? "code" : language);
        Text text = new Text(Objects.requireNonNullElse(code, ""));
        text.getStyleClass().add("md-code-text");
        text.setStyle("-fx-font-family: '" + style.monoFamily() + "'; -fx-font-size: "
                + format(style.fontSize() - 2) + ";");
        codeFlow.getChildren().setAll(text);
        String idleButtonText = copyButton.getText();
        copyButton.setOnAction(event -> copyCode(idleButtonText, code, copyButton));
        return root;
    }

    Region createHorizontalRule() {
        requireFxThread();
        return load("horizontal-rule.fxml", VBox.class);
    }

    Region createImage(String url) {
        requireFxThread();
        VBox root = load("image.fxml", VBox.class);
        ImageView imageView = requireNode(root, "markdownImage", ImageView.class);
        try {
            Image image = new Image(url, true);
            imageView.setImage(image);
            Runnable fit = () -> {
                double width = image.getWidth();
                if (width > 0) imageView.setFitWidth(Math.min(width, 460));
            };
            if (image.getProgress() >= 1.0) {
                fit.run();
            } else {
                image.progressProperty().addListener((observable, previous, progress) -> {
                    if (progress.doubleValue() >= 1.0) fit.run();
                });
            }
            imageView.setOnMouseClicked(event -> openLocalImageOnDoubleClick(
                    event.getClickCount(), url, imageView));
        } catch (RuntimeException ignored) {
            // 非法 URL 只影响当前图片，不能中断整条 Markdown 消息。
        }
        return root;
    }

    Region createTable(
            TableData data,
            MarkdownParagraphRenderer.RenderStyleSnapshot style) {
        requireFxThread();
        return MarkdownTableView.create(loader, fx, data, style);
    }

    private static void copyCode(String previousText, String code, Button button) {
        ClipboardContent clipboard = new ClipboardContent();
        clipboard.putString(Objects.requireNonNullElse(code, ""));
        Clipboard.getSystemClipboard().setContent(clipboard);
        button.setText("已复制");
        javafx.animation.PauseTransition reset =
                new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1.5));
        reset.setOnFinished(ignored -> button.setText(previousText));
        reset.play();
    }

    private static void openLocalImageOnDoubleClick(
            int clickCount, String url, ImageView imageView) {
        if (clickCount != 2 || url == null || !url.startsWith("file:")) return;
        try {
            java.io.File file = new java.io.File(URI.create(url));
            if (file.exists() && imageView.getScene() != null) {
                com.javaclaw.chat.ImageViewerDialog.show(
                        imageView.getScene().getWindow(), file);
            }
        } catch (RuntimeException ignored) {
            // 非法本地 URI 不影响其余 Markdown。
        }
    }

    private <N extends Node> N load(String name, Class<N> rootType) {
        URL resource = Objects.requireNonNull(
                MarkdownRegionViewFactory.class.getResource(FXML_ROOT + name),
                "Markdown FXML 不存在: " + name);
        try (ViewHandle<Node> handle = loader.load(resource)) {
            Node root = handle.root();
            if (!rootType.isInstance(root)) {
                throw new IllegalStateException(
                        "Markdown FXML 根节点类型错误: " + name + ", actual="
                                + root.getClass().getName());
            }
            return rootType.cast(root);
        } catch (IOException failure) {
            throw new IllegalStateException("加载 Markdown FXML 失败: " + name, failure);
        }
    }

    static <N extends Node> N requireNode(Parent root, String id, Class<N> type) {
        Node match = findById(root, id);
        if (!type.isInstance(match)) {
            throw new IllegalStateException(
                    "FXML 节点缺失或类型错误: #" + id + ", expected=" + type.getName());
        }
        return type.cast(match);
    }

    private static Node findById(Node node, String id) {
        if (id.equals(node.getId())) return node;
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Node match = findById(child, id);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static String format(double value) {
        return value == Math.floor(value) ? String.valueOf((int) value) : String.valueOf(value);
    }

    private static void requireFxThread() {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("Markdown Region 必须在 JavaFX Application Thread 创建");
        }
    }
}
