package com.javaclaw.chat;

import com.javaclaw.app.UiMotion;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.binding.Bindings;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.DataFormat;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** FXML Controller：只协调一块可折叠 Markdown 结果的展示操作。 */
public final class ExpandableMarkdownBlockController implements AutoCloseable {

    private static final PseudoClass PLAN_AGENT = PseudoClass.getPseudoClass("plan-agent");

    @FXML private VBox root;
    @FXML private Label titleLabel;
    @FXML private Button collapseButton;
    @FXML private Button copyButton;
    @FXML private Tooltip collapseTooltip;
    @FXML private Tooltip copyTooltip;
    @FXML private VBox contentBox;
    @FXML private StackPane replyHost;

    private final MarkdownBubbleFactory markdownBubbles;
    private final ExpandableMarkdownBlockViewModel viewModel =
            new ExpandableMarkdownBlockViewModel();
    private final AtomicBoolean closed = new AtomicBoolean();
    private MarkdownBubble bubble;
    private Timeline copyFeedback;

    @Autowired
    public ExpandableMarkdownBlockController(MarkdownBubbleFactory markdownBubbles) {
        this.markdownBubbles = Objects.requireNonNull(markdownBubbles, "markdownBubbles");
    }

    @FXML
    private void initialize() {
        bubble = markdownBubbles.create(540);
        replyHost.getChildren().setAll(bubble.getView());
        titleLabel.textProperty().bind(viewModel.titleProperty());
        contentBox.visibleProperty().bind(viewModel.contentVisibleBinding());
        contentBox.managedProperty().bind(contentBox.visibleProperty());
        collapseButton.textProperty().bind(Bindings.when(viewModel.expandedProperty())
                .then("▼").otherwise("▶"));
    }

    void configure(
            ExpandableMarkdownBlockFactory.Variant variant,
            String title,
            boolean contentInitiallyAvailable) {
        root.pseudoClassStateChanged(PLAN_AGENT,
                variant == ExpandableMarkdownBlockFactory.Variant.PLAN_AGENT);
        if (variant == ExpandableMarkdownBlockFactory.Variant.PLAN_AGENT) {
            collapseTooltip.setText("折叠 / 展开此发言");
            copyTooltip.setText("复制此发言文本");
        }
        viewModel.configure(title, contentInitiallyAvailable);
    }

    void attach(ExpandableMarkdownBlockView view) {
        root.getProperties().put("expandableMarkdownBlockView", view);
    }

    VBox root() {
        return root;
    }

    VBox contentBox() {
        return contentBox;
    }

    MarkdownBubble bubble() {
        return bubble;
    }

    void revealContent() {
        viewModel.revealContent();
    }

    @FXML
    private void collapseRequested() {
        viewModel.toggleExpanded();
    }

    @FXML
    private void copyRequested() {
        String text = bubble.getText();
        if (text == null || text.isEmpty()) {
            UiMotion.error(copyButton);
            return;
        }
        Clipboard.getSystemClipboard().setContent(Map.of(DataFormat.PLAIN_TEXT, text));
        copyButton.setText("已复制");
        UiMotion.success(copyButton);
        if (copyFeedback != null) copyFeedback.stop();
        copyFeedback = new Timeline(new KeyFrame(
                Duration.seconds(1.5), event -> copyButton.setText("复制")));
        copyFeedback.play();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (copyFeedback != null) copyFeedback.stop();
        copyFeedback = null;
        if (bubble != null) bubble.dispose();
    }

    boolean isClosed() {
        return closed.get();
    }
}
