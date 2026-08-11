package com.javaclaw.chat;

import com.javaclaw.app.UiMotion;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.geometry.Side;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.DataFormat;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** FXML Controller：协调单条流式助手消息的局部交互和展示生命周期。 */
public final class AssistantMessageController implements AutoCloseable {

    @FXML private HBox root;
    @FXML private Label agentNameLabel;
    @FXML private Label modelBadge;
    @FXML private Label timeLabel;
    @FXML private Label metaLabel;
    @FXML private VBox toolResultsBox;
    @FXML private VBox unifiedBubble;
    @FXML private HBox generationPlaceholder;
    @FXML private Label generationDotOne;
    @FXML private Label generationDotTwo;
    @FXML private Label generationDotThree;
    @FXML private StackPane replyHost;
    @FXML private Button adoptButton;
    @FXML private Button moreButton;
    @FXML private ContextMenu moreMenu;

    private final MarkdownBubbleFactory markdownBubbles;
    private final AssistantMessageViewModel viewModel = new AssistantMessageViewModel();
    private final AtomicBoolean closed = new AtomicBoolean();
    private MarkdownBubble replyBubble;
    private Timeline placeholderAnimation;
    private Runnable regenerateAction = () -> { };
    private Consumer<String> quoteAction = ignored -> { };
    private Consumer<String> saveAction = ignored -> { };
    private Runnable deleteAction = () -> { };
    private Runnable adoptionAction;

    @Autowired
    public AssistantMessageController(MarkdownBubbleFactory markdownBubbles) {
        this.markdownBubbles = Objects.requireNonNull(markdownBubbles, "markdownBubbles");
    }

    @FXML
    private void initialize() {
        replyBubble = markdownBubbles.create(520);
        replyHost.getChildren().setAll(replyBubble.getView());
        agentNameLabel.textProperty().bind(viewModel.agentNameProperty());
        modelBadge.textProperty().bind(viewModel.modelNameProperty());
        timeLabel.textProperty().bind(viewModel.timestampProperty());
        metaLabel.textProperty().bind(viewModel.metadataProperty());
        bindManagedVisibility(toolResultsBox, viewModel.toolsVisibleProperty());
        bindManagedVisibility(replyHost, viewModel.replyVisibleProperty());
        bindManagedVisibility(unifiedBubble, viewModel.replyCardVisibleProperty());
        adoptButton.disableProperty().bind(
                Bindings.not(viewModel.adoptionEnabledProperty()));
        startPlaceholderAnimation();
    }

    void configure(String agentName, String modelName, String timestamp) {
        viewModel.configure(agentName, modelName, timestamp);
    }

    void attach(AssistantMessageView view) {
        root.getProperties().put("assistantMessageView", view);
    }

    HBox root() {
        return root;
    }

    MarkdownBubble replyBubble() {
        return replyBubble;
    }

    VBox toolResultsBox() {
        return toolResultsBox;
    }

    VBox replyContentBox() {
        return unifiedBubble;
    }

    void setRegenerateAction(Runnable action) {
        regenerateAction = Objects.requireNonNull(action, "action");
    }

    void setQuoteAction(Consumer<String> action) {
        quoteAction = Objects.requireNonNull(action, "action");
    }

    void setSaveAction(Consumer<String> action) {
        saveAction = Objects.requireNonNull(action, "action");
    }

    void setDeleteAction(Runnable action) {
        deleteAction = Objects.requireNonNull(action, "action");
    }

    void enableAdoption(Runnable action) {
        adoptionAction = Objects.requireNonNull(action, "action");
        viewModel.enableAdoption();
    }

    void revealReply() {
        stopPlaceholderAnimation();
        generationPlaceholder.setVisible(false);
        generationPlaceholder.setManaged(false);
        viewModel.revealReply();
    }

    void showTools() {
        viewModel.showTools();
    }

    void hideReplyCard() {
        revealReply();
        viewModel.hideReplyCard();
    }

    void setMetadata(String metadata) {
        viewModel.metadataProperty().set(metadata == null ? "—" : metadata);
    }

    @FXML
    private void adoptRequested() {
        if (adoptionAction == null || !viewModel.adoptionEnabledProperty().get()) return;
        adoptionAction.run();
        adoptButton.setText("✓ 已采纳");
        viewModel.adoptionEnabledProperty().set(false);
        UiMotion.success(adoptButton);
    }

    @FXML
    private void regenerateRequested() {
        regenerateAction.run();
    }

    @FXML
    private void quoteRequested() {
        String text = replyBubble.getText();
        if (text != null && !text.isEmpty()) quoteAction.accept(text);
    }

    @FXML
    private void showMoreMenu() {
        moreMenu.show(moreButton, Side.BOTTOM, 0, 0);
    }

    @FXML
    private void copyRequested() {
        String text = replyBubble.getText();
        if (text == null || text.isEmpty()) return;
        Clipboard.getSystemClipboard().setContent(Map.of(DataFormat.PLAIN_TEXT, text));
        UiMotion.success(moreButton);
    }

    @FXML
    private void saveRequested() {
        String text = replyBubble.getText();
        if (text != null && !text.isEmpty()) saveAction.accept(text);
    }

    @FXML
    private void deleteRequested() {
        moreMenu.hide();
        deleteAction.run();
    }

    private void startPlaceholderAnimation() {
        placeholderAnimation = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(generationDotOne.opacityProperty(), 0.3),
                        new KeyValue(generationDotTwo.opacityProperty(), 0.3),
                        new KeyValue(generationDotThree.opacityProperty(), 0.3)),
                new KeyFrame(Duration.millis(200),
                        new KeyValue(generationDotOne.opacityProperty(), 1.0)),
                new KeyFrame(Duration.millis(400),
                        new KeyValue(generationDotOne.opacityProperty(), 0.3),
                        new KeyValue(generationDotTwo.opacityProperty(), 1.0)),
                new KeyFrame(Duration.millis(600),
                        new KeyValue(generationDotTwo.opacityProperty(), 0.3),
                        new KeyValue(generationDotThree.opacityProperty(), 1.0)),
                new KeyFrame(Duration.millis(800),
                        new KeyValue(generationDotThree.opacityProperty(), 0.3)));
        placeholderAnimation.setCycleCount(Timeline.INDEFINITE);
        placeholderAnimation.play();
    }

    private static void bindManagedVisibility(
            javafx.scene.Node node, javafx.beans.value.ObservableBooleanValue visible) {
        node.visibleProperty().bind(visible);
        node.managedProperty().bind(visible);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        stopPlaceholderAnimation();
        moreMenu.hide();
        regenerateAction = () -> { };
        quoteAction = ignored -> { };
        saveAction = ignored -> { };
        deleteAction = () -> { };
        adoptionAction = null;
        if (replyBubble != null) replyBubble.dispose();
        disposeNestedMarkdown(toolResultsBox);
    }

    private static void disposeNestedMarkdown(javafx.scene.Node node) {
        if (node.hasProperties()
                && node.getProperties().get("markdownBubble") instanceof MarkdownBubble bubble) {
            bubble.dispose();
            return;
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                disposeNestedMarkdown(child);
            }
        }
    }

    private void stopPlaceholderAnimation() {
        Timeline animation = placeholderAnimation;
        placeholderAnimation = null;
        if (animation != null) animation.stop();
    }

    boolean isClosed() {
        return closed.get();
    }
}
