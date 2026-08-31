package com.javaclaw.desktop;

import java.net.URI;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

/** 原 md-* CSS 的 FX 渲染器；解析不占 FX，有限队列和单元格代次避免滚动/新事件造成无界工作与旧结果覆盖。 */
final class MarkdownView extends VBox {
    private static final ThreadPoolExecutor PARSER = new ThreadPoolExecutor(
            2,
            2,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32),
            Thread.ofPlatform().name("desktop-markdown-", 0).daemon().factory(),
            new ThreadPoolExecutor.AbortPolicy());
    private long generation;
    private String previous;
    private boolean previousMarkdown;

    MarkdownView() {
        super(8);
        setMaxWidth(Double.MAX_VALUE);
        getStyleClass().add("md-bubble-host");
    }

    void show(String source, boolean markdown, Consumer<URI> openLink) {
        if (source.equals(previous) && markdown == previousMarkdown) {
            return;
        }
        previous = source;
        previousMarkdown = markdown;
        long accepted = ++generation;
        var plain = new Label(source.length() > 16_384 ? source.substring(0, 16_384) + "\n…查看内容可读取完整原文" : source);
        plain.setWrapText(true);
        plain.setMaxWidth(Double.MAX_VALUE);
        getChildren().setAll(plain);
        var copy = new MenuItem("复制原文");
        copy.setOnAction(ignored -> copy(source));
        var menu = new ContextMenu(copy);
        setOnContextMenuRequested(event -> menu.show(this, event.getScreenX(), event.getScreenY()));
        if (!markdown) {
            return;
        }
        try {
            PARSER.execute(() -> {
                var blocks = MarkdownDocument.parse(source);
                Platform.runLater(() -> {
                    if (accepted == generation) {
                        getChildren()
                                .setAll(blocks.stream()
                                        .map(block -> render(block, openLink))
                                        .toList());
                    }
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            // 保留已显示的原文。无需排队重试，更不能为不可见的旧单元格无限创建线程。
        }
    }

    void clear() {
        generation++;
        previous = null;
        getChildren().clear();
    }

    private static Node render(MarkdownDocument.Block block, Consumer<URI> openLink) {
        return switch (block.kind()) {
            case "code" -> code(block);
            case "table" -> table(block, openLink);
            case "rule" -> {
                var line = new Separator();
                line.getStyleClass().add("md-hr");
                yield line;
            }
            case "list" -> {
                var marker = new Text(block.marker());
                marker.getStyleClass().add("md-list-marker");
                var body = new VBox(5);
                body.getChildren()
                        .setAll(block.children().stream()
                                .map(child -> render(child, openLink))
                                .toList());
                HBox.setHgrow(body, Priority.ALWAYS);
                yield new HBox(10, marker, body);
            }
            default -> flow(block.spans(), block.kind(), block.level(), openLink);
        };
    }

    private static TextFlow flow(List<MarkdownDocument.Span> spans, String kind, int level, Consumer<URI> openLink) {
        var flow = new TextFlow();
        flow.setLineSpacing(4);
        flow.setMaxWidth(Double.MAX_VALUE);
        for (var span : spans) {
            var text = new Text(span.text());
            String css = span.link() != null
                    ? "md-link"
                    : span.style().code()
                            ? "md-inline-code"
                            : "quote".equals(kind)
                                    ? "md-quote-text"
                                    : "heading".equals(kind) ? "md-h" + Math.min(level, 4) : "md-body";
            text.getStyleClass().add(css);
            var style = new StringBuilder("-fx-font-size: ")
                    .append("heading".equals(kind) ? 24 - Math.min(level, 5) * 2 : 13)
                    .append("px;");
            if (span.style().bold() || "heading".equals(kind) || "header".equals(kind)) {
                style.append("-fx-font-weight: bold;");
            }
            if (span.style().italic()) {
                style.append("-fx-font-style: italic;");
            }
            if (span.style().code()) {
                style.append("-fx-font-family: monospace;");
            }
            text.setStyle(style.toString());
            text.setStrikethrough(span.style().strike());
            if (span.link() != null) {
                text.setUnderline(true);
                text.setCursor(javafx.scene.Cursor.HAND);
                Tooltip.install(text, new Tooltip(span.link().toString()));
                text.setOnMouseClicked(event -> {
                    if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                        openLink.accept(span.link());
                    }
                });
            }
            flow.getChildren().add(text);
        }
        if ("quote".equals(kind)) {
            flow.setPadding(new Insets(0, 0, 0, 16));
        }
        return flow;
    }

    private static Node code(MarkdownDocument.Block block) {
        String source = block.spans().getFirst().text();
        var language = new Label(block.language().split("\\s+", 2)[0]);
        language.getStyleClass().add("md-code-lang");
        var copy = ManagementForms.button("复制代码", () -> copy(source));
        copy.getStyleClass().setAll("md-code-copy");
        var content = new Text(source);
        content.getStyleClass().add("md-code-text");
        content.setStyle("-fx-font-family: monospace; -fx-font-size: 12px;");
        var scroll = new ScrollPane(new TextFlow(content));
        scroll.setFitToHeight(true);
        scroll.setPrefViewportHeight(Math.min(420, Math.max(36, source.lines().count() * 18)));
        scroll.setStyle("-fx-background: #27251F; -fx-background-color: transparent;");
        var card = new VBox(8, new HBox(12, language, copy), scroll);
        card.getStyleClass().add("md-code-card");
        return card;
    }

    private static Node table(MarkdownDocument.Block block, Consumer<URI> openLink) {
        var grid = new GridPane(1, 1);
        grid.getStyleClass().add("md-table");
        int row = 0;
        for (var values : block.children()) {
            int column = 0;
            for (var cell : values.children()) {
                var text = flow(cell.spans(), cell.kind(), 0, openLink);
                text.setMinWidth(100);
                text.setPrefWidth(200);
                var wrapper = new VBox(text);
                wrapper.getStyleClass().add("header".equals(cell.kind()) ? "md-table-header" : "md-table-cell");
                grid.add(wrapper, column++, row);
            }
            row++;
        }
        var scroll = new ScrollPane(grid);
        scroll.setFitToHeight(true);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("md-table-wrapper");
        scroll.setMaxHeight(480);
        return scroll;
    }

    private static void copy(String text) {
        var clipboard = new ClipboardContent();
        clipboard.putString(text);
        Clipboard.getSystemClipboard().setContent(clipboard);
    }
}
