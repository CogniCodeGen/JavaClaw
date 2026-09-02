package com.javaclaw.desktop.component;

import java.util.Objects;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/** 统一表达表单脏状态、异步进度、成功和失败的动作栏。 */
public final class AsyncActionBar extends HBox {
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Label status = new Label();

    /**
     * 创建动作栏；动作节点按传入顺序显示在右侧。
     *
     * @param actions 按钮或其他动作节点
     */
    public AsyncActionBar(Node... actions) {
        progress.setMaxSize(16, 16);
        progress.setVisible(false);
        progress.setManaged(false);
        status.getStyleClass().add("platform-action-status");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        getChildren().addAll(progress, status, spacer);
        for (Node action : Objects.requireNonNull(actions, "actions")) {
            getChildren().add(Objects.requireNonNull(action, "action"));
        }
        setAlignment(Pos.CENTER_LEFT);
        getStyleClass().add("platform-action-bar");
    }

    /**
     * 更新动作状态和简短说明。
     *
     * @param state 状态语义
     * @param message 说明，可为空字符串
     */
    public void show(ActionState state, String message) {
        ActionState checked = Objects.requireNonNull(state, "state");
        status.setText(Objects.requireNonNullElse(message, ""));
        status.getStyleClass().removeAll("platform-action-dirty", "platform-action-success", "platform-action-error");
        if (!checked.cssClass().isEmpty()) {
            status.getStyleClass().add(checked.cssClass());
        }
        progress.setVisible(checked == ActionState.PENDING);
        progress.setManaged(checked == ActionState.PENDING);
    }

    /** 表单和命令动作状态。 */
    public enum ActionState {
        /** 无提示。 */
        IDLE(""),
        /** 草稿尚未保存。 */
        DIRTY("platform-action-dirty"),
        /** 后台动作执行中。 */
        PENDING(""),
        /** 动作成功。 */
        SUCCESS("platform-action-success"),
        /** 动作失败。 */
        ERROR("platform-action-error");

        private final String cssClass;

        ActionState(String cssClass) {
            this.cssClass = cssClass;
        }

        private String cssClass() {
            return cssClass;
        }
    }
}
