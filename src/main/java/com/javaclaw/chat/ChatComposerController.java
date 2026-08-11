package com.javaclaw.chat;

import com.javaclaw.app.UIHelper;
import com.javaclaw.platform.desktop.ProjectAttachmentPicker;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;
import org.fxmisc.richtext.InlineCssTextArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** 输入、附件与发送状态的 FXML Controller；业务发送仍由父 Chat Controller 协调。 */
public final class ChatComposerController implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ChatComposerController.class);
    private static final String ATTACHMENT_FXML = "/fxml/chat/attachment-preview-item.fxml";

    @FXML private VBox root;
    @FXML private HBox typingIndicator;
    @FXML private Label typingTextLabel;
    @FXML private Label typingDotOne;
    @FXML private Label typingDotTwo;
    @FXML private Label typingDotThree;
    @FXML private FlowPane attachmentPreviewPane;
    @FXML private InlineCssTextArea inputField;
    @FXML private Label inputPlaceholder;
    @FXML private Button sendButton;

    private final SpringFxmlLoader loader;
    private final ProjectAttachmentPicker attachmentPicker;
    private final ChatComposerViewModel viewModel = new ChatComposerViewModel();
    private final List<ViewHandle<StackPane>> attachmentViews = new ArrayList<>();
    private Runnable sendAction = () -> { };
    private Runnable stopAction = () -> { };
    private Supplier<String> recallPrevious = () -> null;
    private Timeline typingAnimation;
    private boolean closed;

    @Autowired
    public ChatComposerController(
            SpringFxmlLoader loader,
            ProjectAttachmentPicker attachmentPicker) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.attachmentPicker = Objects.requireNonNull(attachmentPicker, "attachmentPicker");
    }

    @FXML
    private void initialize() {
        configureTypingAnimation();
        inputField.addEventFilter(KeyEvent.KEY_PRESSED, this::onInputKeyPressed);
        inputField.totalHeightEstimateProperty().addListener((observable, previous, height) -> {
            if (height != null) {
                inputField.setPrefHeight(Math.max(56, Math.min(240, height.doubleValue() + 28)));
            }
        });
        inputPlaceholder.visibleProperty().bind(Bindings.createBooleanBinding(
                () -> inputField.getLength() == 0, inputField.lengthProperty()));
        typingIndicator.visibleProperty().bind(viewModel.thinkingProperty());
        typingIndicator.managedProperty().bind(viewModel.thinkingProperty());
        typingTextLabel.textProperty().bind(viewModel.thinkingTextProperty());
        viewModel.streamingProperty().addListener(
                (observable, previous, streaming) -> renderStreaming(streaming));
        viewModel.attachments().addListener(
                (javafx.collections.ListChangeListener<File>) change -> refreshAttachmentViews());
        UIHelper.addPressEffect(sendButton);
        renderStreaming(false);
    }

    void setOnSend(Runnable action) {
        sendAction = Objects.requireNonNull(action, "action");
    }

    void setOnStop(Runnable action) {
        stopAction = Objects.requireNonNull(action, "action");
    }

    void setRecallPrevious(Supplier<String> recall) {
        recallPrevious = Objects.requireNonNull(recall, "recall");
    }

    String trimmedInput() {
        return inputField.getText().trim();
    }

    int inputLength() {
        return inputField.getLength();
    }

    void clearInput() {
        inputField.clear();
    }

    void replaceInput(String text) {
        String value = Objects.requireNonNullElse(text, "");
        inputField.replaceText(0, inputField.getLength(), value);
        inputField.moveTo(inputField.getLength());
    }

    void insertInputAtStart(String text) {
        inputField.replaceText(0, 0, Objects.requireNonNullElse(text, ""));
        inputField.moveTo(inputField.getLength());
    }

    void focusInput() {
        inputField.requestFocus();
    }

    List<File> attachmentSnapshot() {
        return viewModel.attachmentSnapshot();
    }

    int attachmentCount() {
        return viewModel.attachments().size();
    }

    boolean hasAttachments() {
        return !viewModel.attachments().isEmpty();
    }

    void clearAttachments() {
        viewModel.attachments().clear();
    }

    void setThinkingVisible(boolean visible) {
        viewModel.thinkingProperty().set(visible);
        if (visible) typingAnimation.play();
        else typingAnimation.stop();
    }

    void setThinkingText(String text) {
        viewModel.thinkingTextProperty().set(Objects.requireNonNullElse(
                text, "助手正在思考中..."));
    }

    void setStreaming(boolean streaming) {
        viewModel.streamingProperty().set(streaming);
    }

    void showInputError() {
        com.javaclaw.app.UiMotion.error(inputField);
    }

    @FXML
    private void addAttachmentRequested() {
        Window owner = root.getScene() == null ? null : root.getScene().getWindow();
        ProjectAttachmentPicker.Selection selection = attachmentPicker.select(owner);
        viewModel.addAttachments(selection.files());
        if (selection.rejectedCount() > 0) showRejectedAttachmentWarning(owner, selection.rejectedCount());
    }

    @FXML
    private void sendOrStopRequested() {
        if (viewModel.streamingProperty().get()) stopAction.run();
        else sendAction.run();
    }

    private void onInputKeyPressed(KeyEvent event) {
        if (event.getCode() == KeyCode.ENTER) {
            if (event.isShiftDown() || event.isShortcutDown()) {
                inputField.insertText(inputField.getCaretPosition(), "\n");
            } else {
                sendAction.run();
            }
            event.consume();
        } else if (event.getCode() == KeyCode.ESCAPE) {
            if (inputField.getLength() > 0) inputField.clear();
            else if (viewModel.streamingProperty().get()) stopAction.run();
            else return;
            event.consume();
        } else if (event.getCode() == KeyCode.UP && inputField.getLength() == 0) {
            String previous = recallPrevious.get();
            if (previous != null) {
                replaceInput(previous);
                event.consume();
            }
        }
    }

    private void configureTypingAnimation() {
        typingAnimation = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(typingDotOne.opacityProperty(), 0.3),
                        new KeyValue(typingDotTwo.opacityProperty(), 0.3),
                        new KeyValue(typingDotThree.opacityProperty(), 0.3)),
                new KeyFrame(Duration.millis(200),
                        new KeyValue(typingDotOne.opacityProperty(), 1.0)),
                new KeyFrame(Duration.millis(400),
                        new KeyValue(typingDotOne.opacityProperty(), 0.3),
                        new KeyValue(typingDotTwo.opacityProperty(), 1.0)),
                new KeyFrame(Duration.millis(600),
                        new KeyValue(typingDotTwo.opacityProperty(), 0.3),
                        new KeyValue(typingDotThree.opacityProperty(), 1.0)),
                new KeyFrame(Duration.millis(800),
                        new KeyValue(typingDotThree.opacityProperty(), 0.3)));
        typingAnimation.setCycleCount(Animation.INDEFINITE);
    }

    private void renderStreaming(boolean streaming) {
        inputField.setDisable(streaming);
        sendButton.setDisable(false);
        sendButton.setText(streaming ? "停止" : "发送");
        sendButton.getStyleClass().removeAll("send-button", "stop-button");
        sendButton.getStyleClass().add(streaming ? "stop-button" : "send-button");
        sendButton.setTooltip(streaming ? new Tooltip("中断当前对话（Esc）") : null);
    }

    private void refreshAttachmentViews() {
        closeAttachmentViews();
        attachmentPreviewPane.getChildren().clear();
        boolean visible = !viewModel.attachments().isEmpty();
        attachmentPreviewPane.setVisible(visible);
        attachmentPreviewPane.setManaged(visible);
        if (!visible) return;

        URL resource = Objects.requireNonNull(
                ChatComposerController.class.getResource(ATTACHMENT_FXML), ATTACHMENT_FXML);
        for (File file : viewModel.attachments()) {
            ViewHandle<StackPane> handle = null;
            try {
                handle = loader.load(resource);
                handle.controller(AttachmentPreviewItemController.class).configure(
                        file, () -> viewModel.attachments().remove(file));
                attachmentViews.add(handle);
                attachmentPreviewPane.getChildren().add(handle.root());
            } catch (IOException | RuntimeException failure) {
                if (handle != null) closeFailedAttachmentView(handle, failure);
                log.warn("加载附件预览失败: {}", file.getName(), failure);
            }
        }
    }

    private static void closeFailedAttachmentView(
            ViewHandle<StackPane> handle, Throwable originalFailure) {
        try {
            handle.close();
        } catch (RuntimeException closeFailure) {
            originalFailure.addSuppressed(closeFailure);
        }
    }

    private static void showRejectedAttachmentWarning(Window owner, int rejected) {
        Alert alert = new Alert(Alert.AlertType.WARNING,
                "严格项目隔离已拒绝 " + rejected + " 个项目外或受管配置目录中的附件。",
                ButtonType.OK);
        if (owner != null) alert.initOwner(owner);
        alert.setHeaderText("只能选择当前项目内文件");
        alert.showAndWait();
    }

    private void closeAttachmentViews() {
        RuntimeException firstFailure = null;
        for (int index = attachmentViews.size() - 1; index >= 0; index--) {
            try {
                attachmentViews.get(index).close();
            } catch (RuntimeException failure) {
                if (firstFailure == null) firstFailure = failure;
                else firstFailure.addSuppressed(failure);
            }
        }
        attachmentViews.clear();
        if (firstFailure != null) log.warn("释放附件预览 Controller 失败", firstFailure);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (typingAnimation != null) typingAnimation.stop();
        closeAttachmentViews();
        if (inputField != null) inputField.dispose();
    }

    boolean isClosed() {
        return closed;
    }
}
