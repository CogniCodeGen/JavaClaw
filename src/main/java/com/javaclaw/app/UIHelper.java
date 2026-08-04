package com.javaclaw.app;

import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TreeView;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * UI 样式辅助工具
 *
 * <p>为弹窗、右键菜单等组件统一加载 CSS 样式表。</p>
 *
 * @author JavaClaw
 */
public final class UIHelper {

    /** 标准弹窗在桌面大屏上的尺寸上限；小屏会继续按可视区域自适应缩小。 */
    private static final double DIALOG_MAX_WIDTH = 720;
    private static final double DIALOG_MAX_HEIGHT = 680;
    /** Alert 正文的舒适阅读宽度与最大可视高度，超出后在正文区内部滚动。 */
    private static final double ALERT_CONTENT_MIN_WIDTH = 320;
    private static final double ALERT_CONTENT_WIDTH = 500;
    private static final double ALERT_CONTENT_MAX_HEIGHT = 320;
    private static final String DIALOG_STYLE_MARKER = "jc-dialog-pane";

    private static final String CSS_PATH;

    static {
        var url = UIHelper.class.getResource("/css/chat.css");
        CSS_PATH = url != null ? url.toExternalForm() : null;
    }

    private UIHelper() {}

    /**
     * 为任意 JavaFX Dialog 应用统一样式与响应式尺寸约束。
     *
     * <p>自定义内容会放入无边框滚动容器，避免长表单或长说明把窗口撑出屏幕；
     * ListView / TextArea 等本身可滚动的控件会保留原结构。页脚按钮始终固定在内容区下方。</p>
     */
    public static void styleDialog(Dialog<?> dialog) {
        if (dialog == null) {
            return;
        }

        DialogPane pane = dialog.getDialogPane();
        if (CSS_PATH != null && !pane.getStylesheets().contains(CSS_PATH)) {
            pane.getStylesheets().add(CSS_PATH);
        }
        if (!pane.getStyleClass().contains("root")) {
            pane.getStyleClass().add("root");
        }
        dialog.setGraphic(null);

        prepareScrollableContent(pane);
        applyDialogBounds(dialog);

        if (!pane.getStyleClass().contains(DIALOG_STYLE_MARKER)) {
            pane.getStyleClass().add(DIALOG_STYLE_MARKER);
            dialog.showingProperty().addListener((obs, wasShowing, isShowing) -> {
                if (!isShowing) {
                    return;
                }
                // 调用方可能在 styleDialog 之后才设置 content，展示前再统一整理一次。
                prepareScrollableContent(pane);
                Platform.runLater(() -> constrainWindow(dialog));
            });
        }
    }

    /**
     * 为 Alert 弹窗应用统一样式。
     *
     * <p>标准正文会被替换为可换行、可滚动的阅读区；短文案仍保持紧凑，
     * 长文案只滚动正文而不会继续放大整个弹窗。</p>
     */
    public static void styleAlert(Alert alert) {
        if (alert == null) {
            return;
        }
        prepareAlertContent(alert);
        styleDialog(alert);
        // 移除系统默认图标，保持弹窗干净
        alert.setGraphic(null);
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

    private static void prepareAlertContent(Alert alert) {
        DialogPane pane = alert.getDialogPane();
        if (pane.getContent() != null) {
            return;
        }
        String message = alert.getContentText();
        if (message == null || message.isBlank()) {
            return;
        }

        Label label = new Label(message);
        label.setWrapText(true);
        label.setMinWidth(0);
        double contentWidth = Math.min(
                ALERT_CONTENT_WIDTH,
                Math.max(ALERT_CONTENT_MIN_WIDTH, label.prefWidth(-1)));
        label.setPrefWidth(contentWidth);
        label.setMaxWidth(Double.MAX_VALUE);
        label.getStyleClass().add("dialog-message");

        ScrollPane scroll = createDialogScrollPane(label, "dialog-message-scroll");
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportWidth(contentWidth);
        double preferredHeight = label.prefHeight(contentWidth);
        scroll.setPrefViewportHeight(Math.min(
                ALERT_CONTENT_MAX_HEIGHT,
                Math.max(36, preferredHeight)));
        scroll.setMaxHeight(ALERT_CONTENT_MAX_HEIGHT);
        pane.setContent(scroll);
    }

    private static void prepareScrollableContent(DialogPane pane) {
        Node content = pane.getContent();
        if (content == null
                || content instanceof ScrollPane
                || content instanceof TextArea
                || content instanceof ListView<?>
                || content instanceof TableView<?>
                || content instanceof TreeView<?>
                || content.getStyleClass().contains("dialog-no-auto-scroll")) {
            return;
        }

        ScrollPane scroll = createDialogScrollPane(content, "dialog-body-scroll");
        scroll.setMaxHeight(ALERT_CONTENT_MAX_HEIGHT + 120);
        pane.setContent(scroll);
    }

    private static ScrollPane createDialogScrollPane(Node content, String styleClass) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPannable(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setMinWidth(0);
        scroll.setMinHeight(0);
        scroll.setMaxWidth(Double.MAX_VALUE);
        scroll.getStyleClass().add(styleClass);
        return scroll;
    }

    private static void applyDialogBounds(Dialog<?> dialog) {
        Rectangle2D bounds = visualBounds(dialog);
        DialogPane pane = dialog.getDialogPane();
        pane.setMaxWidth(maxDialogWidth(bounds));
        pane.setMaxHeight(maxDialogHeight(bounds));
    }

    private static void constrainWindow(Dialog<?> dialog) {
        DialogPane pane = dialog.getDialogPane();
        if (pane.getScene() == null) {
            return;
        }
        Window window = pane.getScene().getWindow();
        if (!(window instanceof Stage stage)) {
            return;
        }

        Rectangle2D bounds = visualBounds(dialog);
        double maxWidth = maxDialogWidth(bounds);
        double maxHeight = maxDialogHeight(bounds);
        stage.setMaxWidth(maxWidth);
        stage.setMaxHeight(maxHeight);
        if (stage.getWidth() > maxWidth) {
            stage.setWidth(maxWidth);
        }
        if (stage.getHeight() > maxHeight) {
            stage.setHeight(maxHeight);
        }
    }

    private static Rectangle2D visualBounds(Dialog<?> dialog) {
        Window owner = dialog.getOwner();
        if (owner != null) {
            return Screen.getScreensForRectangle(
                            owner.getX(), owner.getY(), owner.getWidth(), owner.getHeight())
                    .stream()
                    .findFirst()
                    .orElse(Screen.getPrimary())
                    .getVisualBounds();
        }
        return Screen.getPrimary().getVisualBounds();
    }

    private static double maxDialogWidth(Rectangle2D bounds) {
        return Math.min(DIALOG_MAX_WIDTH, bounds.getWidth() * 0.88);
    }

    private static double maxDialogHeight(Rectangle2D bounds) {
        return Math.min(DIALOG_MAX_HEIGHT, bounds.getHeight() * 0.82);
    }
}
