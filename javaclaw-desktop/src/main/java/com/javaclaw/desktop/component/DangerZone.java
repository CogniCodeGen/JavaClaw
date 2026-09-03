package com.javaclaw.desktop.component;

import java.util.Objects;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;

/** 将不可逆或高影响操作集中展示的危险分区。 */
public final class DangerZone extends VBox {
    private final Button actionButton;

    /**
     * 创建危险分区。
     *
     * @param title 动作名称
     * @param description 后果说明
     * @param actionLabel 按钮文字
     * @param confirmation 执行前的明确确认策略
     * @param action 用户确认后执行的动作
     */
    public DangerZone(
            String title,
            String description,
            String actionLabel,
            DangerConfirmationPolicy confirmation,
            Runnable action) {
        DangerConfirmationRequest request = new DangerConfirmationRequest(title, description, actionLabel);
        DangerConfirmationPolicy checkedConfirmation = Objects.requireNonNull(confirmation, "confirmation");
        Runnable checkedAction = Objects.requireNonNull(action, "action");
        Label heading = new Label(request.title());
        heading.getStyleClass().add("platform-danger-title");
        Label detail = new Label(request.consequence());
        detail.setWrapText(true);
        detail.getStyleClass().add("platform-danger-detail");
        actionButton =
                new PlatformComponentFactory().action(request.actionLabel(), ActionStyle.DANGER, ActionSize.NORMAL);
        actionButton.setOnAction(event -> {
            if (checkedConfirmation.confirm(request)) {
                checkedAction.run();
            }
        });
        getChildren().addAll(heading, detail, actionButton);
        getStyleClass().add("platform-danger-zone");
    }

    /**
     * 更新危险动作是否可用，说明文本始终保留。
     *
     * @param disabled 是否禁用动作
     */
    public void setActionDisabled(boolean disabled) {
        actionButton.setDisable(disabled);
    }
}
