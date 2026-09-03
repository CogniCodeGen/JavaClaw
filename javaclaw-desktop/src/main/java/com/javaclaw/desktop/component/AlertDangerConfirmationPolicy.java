package com.javaclaw.desktop.component;

import java.util.Objects;

import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;

/** 使用平台 JavaFX Alert 展示危险动作后果的确认策略。 */
public final class AlertDangerConfirmationPolicy implements DangerConfirmationPolicy {
    private final Node ownerNode;

    /**
     * 创建平台确认策略。
     *
     * @param ownerNode 用于解析所属窗口的页面节点；节点尚未挂载时显示无 owner 对话框
     */
    public AlertDangerConfirmationPolicy(Node ownerNode) {
        this.ownerNode = Objects.requireNonNull(ownerNode, "ownerNode");
    }

    @Override
    public boolean confirm(DangerConfirmationRequest request) {
        DangerConfirmationRequest checked = Objects.requireNonNull(request, "request");
        ButtonType confirm = new ButtonType(checked.actionLabel(), ButtonBar.ButtonData.OK_DONE);
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION, checked.consequence(), ButtonType.CANCEL, confirm);
        dialog.setTitle(checked.title());
        dialog.setHeaderText("请确认此危险操作");
        PlatformDialogs.style(dialog, ownerNode);
        return dialog.showAndWait().filter(confirm::equals).isPresent();
    }
}
