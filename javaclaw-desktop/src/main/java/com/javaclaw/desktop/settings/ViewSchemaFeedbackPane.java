package com.javaclaw.desktop.settings;

import java.util.Objects;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.desktop.component.PlatformComponentFactory.FeedbackKind;

/** ViewSchema 页面的加载、空状态、重试和草稿遮罩容器。 */
final class ViewSchemaFeedbackPane extends StackPane {
    private final PlatformComponentFactory components;
    private Node blocked;
    private boolean previouslyDisabled;
    private ViewPageFocus focus;
    private final javafx.event.EventHandler<KeyEvent> blockedKeys = KeyEvent::consume;

    ViewSchemaFeedbackPane(PlatformComponentFactory components) {
        this.components = Objects.requireNonNull(components, "components");
    }

    void showContent(Node rendered) {
        keepContent(Objects.requireNonNull(rendered, "rendered"));
        restoreInteraction();
    }

    boolean interactionBlocked() {
        return blocked != null;
    }

    void showLoading(String detail) {
        restoreInteraction();
        getChildren().setAll(components.feedback(FeedbackKind.LOADING, "正在加载", detail));
    }

    void showLoading(Node rendered, String detail) {
        VBox loading = components.feedback(FeedbackKind.LOADING, "正在加载", detail);
        loading.getStyleClass().add("platform-conflict-overlay");
        showOverlay(rendered, loading);
    }

    void showEmpty(String heading, String detail) {
        restoreInteraction();
        getChildren().setAll(components.feedback(FeedbackKind.EMPTY, heading, detail));
    }

    void showPending(Node rendered, String operation) {
        VBox pending = components.feedback(FeedbackKind.LOADING, "正在执行", "正在提交 “" + operation + "”，请稍候。");
        pending.getStyleClass().add("platform-conflict-overlay");
        showOverlay(rendered, pending);
    }

    void showRetry(String heading, String detail, Runnable retryAction) {
        restoreInteraction();
        Button retry = components.action("重试", ActionStyle.PRIMARY, ActionSize.NORMAL);
        retry.setOnAction(
                event -> Objects.requireNonNull(retryAction, "retryAction").run());
        VBox feedback = components.feedback(FeedbackKind.ERROR, heading, detail);
        feedback.getChildren().add(retry);
        getChildren().setAll(feedback);
    }

    void showDraftOverlay(
            Node rendered, String heading, String detail, Runnable continueEditing, Runnable reloadAction) {
        Button keep = components.action("继续编辑", ActionStyle.GHOST, ActionSize.NORMAL);
        keep.setOnAction(event ->
                Objects.requireNonNull(continueEditing, "continueEditing").run());
        HBox actions = new HBox(8, keep);
        if (reloadAction != null) {
            Button reload = components.action("丢弃并重新加载", ActionStyle.PRIMARY, ActionSize.NORMAL);
            reload.setOnAction(event -> reloadAction.run());
            actions.getChildren().add(reload);
        }
        actions.setAlignment(Pos.CENTER_RIGHT);
        VBox overlay = components.feedback(FeedbackKind.ERROR, heading, detail);
        overlay.getChildren().add(actions);
        overlay.getStyleClass().add("platform-conflict-overlay");
        showOverlay(rendered, overlay);
    }

    void showFatal(String heading, String detail) {
        restoreInteraction();
        getChildren().setAll(components.feedback(FeedbackKind.ERROR, heading, detail));
    }

    private void keepContent(Node rendered) {
        if (!getChildren().isEmpty() && getChildren().getFirst() == rendered) {
            getChildren().remove(1, getChildren().size());
        } else {
            getChildren().setAll(rendered);
        }
    }

    private void showOverlay(Node rendered, Node overlay) {
        restoreInteraction();
        if (rendered == null) {
            getChildren().setAll(overlay);
        } else {
            keepContent(rendered);
            blocked = rendered;
            previouslyDisabled = rendered.isDisable();
            focus = ViewPageFocus.capture(rendered);
            // 遮罩必须同时阻止已聚焦控件的键盘输入；仅覆盖鼠标命中区域不足以保护待提交草稿。
            rendered.addEventFilter(KeyEvent.ANY, blockedKeys);
            rendered.setDisable(true);
            getChildren().add(overlay);
            overlay.setFocusTraversable(true);
            overlay.requestFocus();
        }
    }

    /** 加载完成、失败或取消后归还原交互状态；不能把原本禁用的内容错误启用。 */
    void restoreInteraction() {
        if (blocked != null) {
            blocked.removeEventFilter(KeyEvent.ANY, blockedKeys);
            blocked.setDisable(previouslyDisabled);
            if (focus != null) {
                focus.restore(blocked);
            }
            blocked = null;
            focus = null;
        }
    }
}
