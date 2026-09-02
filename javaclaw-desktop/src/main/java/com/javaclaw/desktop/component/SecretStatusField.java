package com.javaclaw.desktop.component;

import java.util.Objects;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;

/** 只显示 Secret 配置状态，并提供替换和清除意图；永不显示保存值。 */
public final class SecretStatusField extends HBox {
    private final Label status = new Label();
    private final Button replaceButton;
    private final Button clearButton;
    private boolean configured;

    /**
     * 创建 Secret 状态字段。
     *
     * @param replace 请求替换 Secret
     * @param clear 请求清除 Secret
     */
    public SecretStatusField(Runnable replace, Runnable clear) {
        Runnable checkedReplace = Objects.requireNonNull(replace, "replace");
        Runnable checkedClear = Objects.requireNonNull(clear, "clear");
        PlatformComponentFactory components = new PlatformComponentFactory();
        replaceButton = components.action("替换", ActionStyle.SOFT, ActionSize.COMPACT);
        replaceButton.setOnAction(event -> checkedReplace.run());
        clearButton = components.action("清除", ActionStyle.GHOST, ActionSize.COMPACT);
        clearButton.setOnAction(event -> checkedClear.run());
        status.getStyleClass().add("platform-secret-status");
        getChildren().addAll(status, replaceButton, clearButton);
        setAlignment(Pos.CENTER_LEFT);
        getStyleClass().add("platform-secret-field");
        setConfigured(false);
    }

    /**
     * 更新是否存在 Secret；只呈现布尔状态。
     *
     * @param configured 是否已配置
     */
    public void setConfigured(boolean configured) {
        this.configured = configured;
        status.setText(configured ? "已安全配置" : "尚未配置");
        replaceButton.setText(configured ? "轮换" : "配置");
        clearButton.setDisable(!configured);
    }

    /**
     * 更新动作可用性；禁用时仍保留脱敏配置状态。
     *
     * @param disabled 是否禁用替换与清除
     */
    public void setActionsDisabled(boolean disabled) {
        replaceButton.setDisable(disabled);
        clearButton.setDisable(disabled || !configured);
    }

    /**
     * 显示不包含 Secret 的状态说明。
     *
     * @param text 脱敏说明
     */
    public void setStatusText(String text) {
        status.setText(Objects.requireNonNull(text, "text"));
    }
}
