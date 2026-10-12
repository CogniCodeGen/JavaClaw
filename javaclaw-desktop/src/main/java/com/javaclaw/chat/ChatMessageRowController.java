package com.javaclaw.chat;

import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.fxmisc.richtext.InlineCssTextArea;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/** FXML Controller：把一条不可变消息快照映射到静态消息结构。 */
public final class ChatMessageRowController implements AutoCloseable {

    @FXML private StackPane root;
    @FXML private HBox normalRow;
    @FXML private Label userAvatar;
    @FXML private Label assistantAvatar;
    @FXML private Label authorLabel;
    @FXML private Label modelBadge;
    @FXML private Label timeLabel;
    @FXML private javafx.scene.layout.Region headerSpacer;
    @FXML private Label metaLabel;
    @FXML private FlowPane attachmentFlow;
    @FXML private InlineCssTextArea plainTextArea;
    @FXML private StackPane markdownHost;
    @FXML private VBox extraImagesHost;
    @FXML private HBox systemRow;
    @FXML private InlineCssTextArea systemTextArea;
    @FXML private Label systemTimeLabel;
    @FXML private HBox welcomeRow;
    @FXML private InlineCssTextArea welcomeTextArea;

    private final MarkdownBubbleFactory markdownBubbles;
    private final ChatMessageRowViewModel viewModel = new ChatMessageRowViewModel();
    private final AtomicBoolean closed = new AtomicBoolean();
    private MarkdownBubble markdownBubble;

    @Autowired
    public ChatMessageRowController(MarkdownBubbleFactory markdownBubbles) {
        this.markdownBubbles = Objects.requireNonNull(markdownBubbles, "markdownBubbles");
    }

    @FXML
    private void initialize() {
        bindVisibility(normalRow, viewModel.normalProperty());
        bindVisibility(systemRow, viewModel.systemProperty());
        bindVisibility(welcomeRow, viewModel.welcomeProperty());
        bindVisibility(userAvatar, viewModel.userProperty());
        bindVisibility(assistantAvatar, viewModel.assistantProperty());
        bindVisibility(modelBadge, viewModel.assistantProperty());
        bindVisibility(headerSpacer, viewModel.assistantProperty());
        bindVisibility(metaLabel, viewModel.assistantProperty());
        bindVisibility(plainTextArea, viewModel.plainTextProperty());
        bindVisibility(markdownHost, viewModel.markdownProperty());
        bindVisibility(attachmentFlow, viewModel.attachmentsProperty());
        authorLabel.textProperty().bind(viewModel.authorProperty());
        modelBadge.textProperty().bind(viewModel.modelProperty());
        timeLabel.textProperty().bind(viewModel.timestampProperty());
        metaLabel.textProperty().bind(viewModel.metadataProperty());
        systemTimeLabel.textProperty().bind(viewModel.timestampProperty());
    }

    void configure(
            ChatMessageRowFactory.Variant variant,
            ChatMessage message,
            String agentName,
            String modelName,
            String metadata,
            List<? extends Node> extraImages,
            BiConsumer<ImageView, File> imageZoom) {
        Objects.requireNonNull(variant, "variant");
        Objects.requireNonNull(message, "message");
        String content = message.getContent() == null ? "" : message.getContent();
        String author = variant == ChatMessageRowFactory.Variant.USER ? "You" : agentName;
        viewModel.configure(variant, author, modelName, message.getFormattedTime(), metadata,
                !content.isEmpty(), !message.getAttachments().isEmpty());
        switch (variant) {
            case USER -> {
                BubbleTextAreaSupport.configure(plainTextArea, content, 520, null);
                populateAttachments(message.getAttachments(), imageZoom);
            }
            case ASSISTANT -> configureMarkdown(content);
            case SYSTEM -> BubbleTextAreaSupport.configure(systemTextArea, content, 450,
                    "-fx-fill: #27251F; -fx-font-style: italic;");
            case WELCOME -> BubbleTextAreaSupport.configure(
                    welcomeTextArea, content, 450, null);
        }
        extraImagesHost.getChildren().setAll(extraImages == null ? List.of() : extraImages);
        extraImagesHost.setVisible(!extraImagesHost.getChildren().isEmpty());
        extraImagesHost.setManaged(extraImagesHost.isVisible());
    }

    void attach(ChatMessageRowView view) {
        root.getProperties().put("chatMessageRowView", view);
    }

    MarkdownBubble markdownBubble() {
        return markdownBubble;
    }

    private void configureMarkdown(String content) {
        markdownBubble = markdownBubbles.create(520);
        markdownBubble.replaceText(content);
        markdownHost.getChildren().setAll(markdownBubble.getView());
    }

    private void populateAttachments(
            List<File> attachments, BiConsumer<ImageView, File> imageZoom) {
        attachmentFlow.getChildren().clear();
        for (File file : attachments) {
            if (ChatMessage.isImageFile(file)) {
                ImageView image = new ImageView(new Image(
                        file.toURI().toString(), 140, 180, true, true));
                image.setFitWidth(140);
                image.setFitHeight(180);
                image.setPreserveRatio(true);
                image.getStyleClass().add("attachment-thumbnail");
                imageZoom.accept(image, file);
                attachmentFlow.getChildren().add(image);
            } else {
                String extension = ChatMessage.getFileExtension(file).toUpperCase();
                Label label = new Label((extension.isEmpty() ? "FILE" : extension)
                        + " " + file.getName());
                label.getStyleClass().add("attachment-file-label");
                attachmentFlow.getChildren().add(label);
            }
        }
    }

    private static void bindVisibility(
            Node node, javafx.beans.value.ObservableBooleanValue visible) {
        node.visibleProperty().bind(visible);
        node.managedProperty().bind(visible);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (markdownBubble != null) markdownBubble.dispose();
    }

    boolean isClosed() {
        return closed.get();
    }
}
