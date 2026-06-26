package com.javaclaw.app;

import javafx.animation.ScaleTransition;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextInputDialog;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * UI 样式辅助工具
 *
 * <p>为弹窗、右键菜单等组件统一加载 CSS 样式表。</p>
 *
 * @author JavaClaw
 */
public final class UIHelper {

    private static final String CSS_PATH;

    static {
        var url = UIHelper.class.getResource("/css/chat.css");
        CSS_PATH = url != null ? url.toExternalForm() : null;
    }

    private UIHelper() {}

    /**
     * 为 Alert 弹窗应用统一样式
     */
    public static void styleAlert(Alert alert) {
        if (CSS_PATH != null) {
            alert.getDialogPane().getStylesheets().add(CSS_PATH);
        }
        // 移除系统默认图标，保持弹窗干净
        alert.setGraphic(null);
    }

    /**
     * 为 TextInputDialog 应用统一样式
     */
    public static void styleDialog(TextInputDialog dialog) {
        if (CSS_PATH != null) {
            dialog.getDialogPane().getStylesheets().add(CSS_PATH);
        }
        dialog.setGraphic(null);
    }

    /**
     * 创建统一样式的确认弹窗
     */
    public static Alert createConfirmAlert(String title, String content, Stage owner) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, content, ButtonType.OK, ButtonType.CANCEL);
        alert.setTitle(title);
        alert.setHeaderText(null);
        if (owner != null) {
            alert.initOwner(owner);
        }
        styleAlert(alert);
        return alert;
    }

    /**
     * 创建统一样式的警告弹窗
     */
    public static Alert createWarningAlert(String content, Stage owner) {
        Alert alert = new Alert(Alert.AlertType.WARNING, content, ButtonType.OK);
        alert.setHeaderText(null);
        if (owner != null) {
            alert.initOwner(owner);
        }
        styleAlert(alert);
        return alert;
    }

    /**
     * 创建统一样式的文本输入弹窗
     */
    public static TextInputDialog createTextInputDialog(String defaultValue, String title, String contentText, Stage owner) {
        TextInputDialog dialog = new TextInputDialog(defaultValue);
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.setContentText(contentText);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        styleDialog(dialog);
        return dialog;
    }

    /**
     * 创建统一样式的右键菜单
     */
    public static ContextMenu createContextMenu() {
        return new ContextMenu();
    }

    /**
     * 创建危险操作菜单项（红色文字）
     */
    public static MenuItem createDangerMenuItem(String text) {
        MenuItem item = new MenuItem(text);
        item.getStyleClass().add("menu-item-danger");
        return item;
    }

    /**
     * 为节点添加按下缩放效果（按下缩小到 95%，松开恢复）
     */
    public static void addPressEffect(Node node) {
        node.setOnMousePressed(e -> {
            ScaleTransition st = new ScaleTransition(Duration.millis(80), node);
            st.setToX(0.95);
            st.setToY(0.95);
            st.play();
        });
        node.setOnMouseReleased(e -> {
            ScaleTransition st = new ScaleTransition(Duration.millis(80), node);
            st.setToX(1.0);
            st.setToY(1.0);
            st.play();
        });
    }
}
