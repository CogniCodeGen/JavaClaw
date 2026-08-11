package com.javaclaw.chat;

import com.javaclaw.app.UiMotion;
import com.javaclaw.platform.fx.FxDispatcher;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** 当前会话的转录 FXML Controller；统一管理消息节点、尾部跟随和未读提示。 */
public final class ChatSessionController implements AutoCloseable {

    private static final double NEAR_BOTTOM_THRESHOLD_PX = 100.0;

    @FXML private StackPane root;
    @FXML private ScrollPane scrollPane;
    @FXML private VBox messageList;
    @FXML private VBox emptyState;
    @FXML private Button newMessagesButton;

    private final FxDispatcher fx;
    private final ChatSessionViewModel viewModel = new ChatSessionViewModel();
    private Timeline tailScrollAnimation;
    private boolean programmaticTailScroll;
    private boolean closed;

    @Autowired
    public ChatSessionController(FxDispatcher fx) {
        this.fx = Objects.requireNonNull(fx, "fx");
    }

    @FXML
    private void initialize() {
        configureUserScrolling();
        messageList.heightProperty().addListener((observable, previous, height) -> {
            if (viewModel.followTailProperty().get()) scrollToTail(true);
        });
        scrollPane.vvalueProperty().addListener((observable, previous, value) -> {
            if (programmaticTailScroll) return;
            viewModel.followTailProperty().set(isNearBottom());
            if (viewModel.followTailProperty().get()) viewModel.resetUnread();
        });
        messageList.getChildren().addListener(
                (javafx.collections.ListChangeListener<Node>) this::onMessagesChanged);
        emptyState.visibleProperty().bind(Bindings.isEmpty(messageList.getChildren()));
        emptyState.managedProperty().bind(emptyState.visibleProperty());
        newMessagesButton.textProperty().bind(Bindings.concat(
                "↓ ", viewModel.unreadMessagesProperty(), " 条新消息"));
        newMessagesButton.visibleProperty().bind(
                viewModel.unreadMessagesProperty().greaterThan(0));
        newMessagesButton.managedProperty().bind(newMessagesButton.visibleProperty());
    }

    StackPane root() {
        return root;
    }

    void addMessage(Node node) {
        messageList.getChildren().add(Objects.requireNonNull(node, "node"));
    }

    boolean removeContaining(Node descendant) {
        Node row = descendant;
        while (row != null && row.getParent() != messageList) row = row.getParent();
        return row != null && messageList.getChildren().remove(row);
    }

    List<Node> detachMessages() {
        List<Node> detached = new ArrayList<>(messageList.getChildren());
        messageList.getChildren().clear();
        return detached;
    }

    void setMessages(Collection<? extends Node> messages) {
        messageList.getChildren().setAll(messages);
    }

    void enterAtTail() {
        viewModel.followTailProperty().set(true);
        viewModel.resetUnread();
        fx.dispatch(() -> {
            if (viewModel.followTailProperty().get()) scrollToTail(false);
        });
    }

    ChatSessionViewModel viewModel() {
        return viewModel;
    }

    private void configureUserScrolling() {
        scrollPane.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, event -> {
            double deltaY = event.getDeltaY();
            if (deltaY == 0) return;
            beginUserScroll();
            double viewportHeight = scrollPane.getViewportBounds() == null
                    ? 0 : scrollPane.getViewportBounds().getHeight();
            double scrollable = messageList.getHeight() - viewportHeight;
            if (scrollable <= 0) return;
            double next = scrollPane.getVvalue() - deltaY / scrollable;
            scrollPane.setVvalue(Math.max(0, Math.min(1, next)));
            event.consume();
        });
        scrollPane.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
            if (isScrollBarTarget(event.getTarget())) beginUserScroll();
        });
        scrollPane.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            switch (event.getCode()) {
                case UP, DOWN, PAGE_UP, PAGE_DOWN, HOME, END, SPACE -> beginUserScroll();
                default -> { }
            }
        });
    }

    private void onMessagesChanged(
            javafx.collections.ListChangeListener.Change<? extends Node> change) {
        while (change.next()) {
            if (messageList.getChildren().isEmpty()) {
                viewModel.resetUnread();
            } else if (change.wasAdded() && !viewModel.followTailProperty().get()) {
                boolean firstUnread = viewModel.unreadMessagesProperty().get() == 0;
                viewModel.incrementUnread(change.getAddedSize());
                if (firstUnread) UiMotion.fadeIn(newMessagesButton);
            }
        }
    }

    @FXML
    private void newMessagesRequested() {
        scrollToTail(false);
    }

    private boolean isNearBottom() {
        double viewportHeight = scrollPane.getViewportBounds() == null
                ? 0 : scrollPane.getViewportBounds().getHeight();
        double scrollableHeight = Math.max(0, messageList.getHeight() - viewportHeight);
        if (scrollableHeight <= 0) return true;
        double currentY = scrollableHeight * scrollPane.getVvalue();
        return scrollableHeight - currentY <= NEAR_BOTTOM_THRESHOLD_PX;
    }

    private void scrollToTail(boolean animated) {
        stopTailScrollAnimation();
        viewModel.followTailProperty().set(true);
        programmaticTailScroll = true;
        if (!animated) {
            try {
                scrollPane.setVvalue(1.0);
            } finally {
                programmaticTailScroll = false;
            }
            viewModel.resetUnread();
            return;
        }

        Timeline animation = new Timeline(new KeyFrame(
                Duration.millis(150), new KeyValue(scrollPane.vvalueProperty(), 1.0)));
        tailScrollAnimation = animation;
        animation.setOnFinished(event -> {
            if (tailScrollAnimation == animation) tailScrollAnimation = null;
            programmaticTailScroll = false;
            if (isNearBottom()) viewModel.resetUnread();
        });
        animation.play();
    }

    private void beginUserScroll() {
        stopTailScrollAnimation();
    }

    private void stopTailScrollAnimation() {
        Timeline animation = tailScrollAnimation;
        tailScrollAnimation = null;
        if (animation != null) animation.stop();
        programmaticTailScroll = false;
    }

    private static boolean isScrollBarTarget(Object target) {
        if (!(target instanceof Node node)) return false;
        Node current = node;
        while (current != null) {
            if (current.getStyleClass().contains("scroll-bar")) return true;
            current = current.getParent();
        }
        return false;
    }

    boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        stopTailScrollAnimation();
    }
}
