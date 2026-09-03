package com.javaclaw.desktop.settings;

import java.util.Objects;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
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

    ViewSchemaFeedbackPane(PlatformComponentFactory components) {
        this.components = Objects.requireNonNull(components, "components");
    }

    void showContent(Node rendered) {
        getChildren().setAll(Objects.requireNonNull(rendered, "rendered"));
    }

    void showLoading(String detail) {
        getChildren().setAll(components.feedback(FeedbackKind.LOADING, "正在加载", detail));
    }

    void showEmpty(String heading, String detail) {
        getChildren().setAll(components.feedback(FeedbackKind.EMPTY, heading, detail));
    }

    void showPending(Node rendered, String operation) {
        VBox pending = components.feedback(FeedbackKind.LOADING, "正在执行", "正在提交 “" + operation + "”，请稍候。");
        pending.getStyleClass().add("platform-conflict-overlay");
        showOverlay(rendered, pending);
    }

    void showRetry(String heading, String detail, Runnable retryAction) {
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
        getChildren().setAll(components.feedback(FeedbackKind.ERROR, heading, detail));
    }

    private void showOverlay(Node rendered, Node overlay) {
        if (rendered == null) {
            getChildren().setAll(overlay);
        } else {
            getChildren().setAll(rendered, overlay);
        }
    }
}
