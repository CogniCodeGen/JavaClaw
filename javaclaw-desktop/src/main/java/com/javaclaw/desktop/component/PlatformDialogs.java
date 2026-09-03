package com.javaclaw.desktop.component;

import java.util.Objects;

import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TreeView;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.javaclaw.desktop.DesktopStylesheets;

/** 为 JavaClaw Desktop 拥有的 JavaFX Dialog 统一视觉、尺寸和文本输入交互。 */
public final class PlatformDialogs {
    private static final double DIALOG_MAX_WIDTH = 720;
    private static final double DIALOG_MAX_HEIGHT = 680;
    private static final double WIDE_DIALOG_MAX_WIDTH = 960;
    private static final double WIDE_DIALOG_MAX_HEIGHT = 840;
    private static final double MESSAGE_MIN_WIDTH = 320;
    private static final double MESSAGE_WIDTH = 500;
    private static final double MESSAGE_MAX_HEIGHT = 320;
    private static final double INPUT_WIDTH = 480;
    private static final String STYLE_MARKER = "jc-dialog-pane";
    private static final String WIDE_STYLE = "jc-dialog-wide";
    private static final String NO_AUTO_SCROLL_STYLE = "dialog-no-auto-scroll";
    private static final String PREPARED_KEY = PlatformDialogs.class.getName() + ".prepared";

    private PlatformDialogs() {}

    /**
     * 使用拥有节点所在窗口和 Scene 的动态外观统一 Dialog。
     *
     * @param dialog 待展示 Dialog
     * @param ownerNode 所属页面节点；尚未挂载时仍应用已保存外观
     */
    public static void style(Dialog<?> dialog, Node ownerNode) {
        Scene ownerScene = ownerNode == null ? null : ownerNode.getScene();
        Window owner = ownerScene == null ? null : ownerScene.getWindow();
        style(dialog, owner, ownerScene);
    }

    /**
     * 使用所属窗口的动态外观统一 Dialog。
     *
     * @param dialog 待展示 Dialog
     * @param owner 所属窗口；可空
     */
    public static void style(Dialog<?> dialog, Window owner) {
        style(dialog, owner, owner == null ? null : owner.getScene());
    }

    /**
     * 创建带用途说明、占位提示和非空门禁的文本输入 Dialog。
     *
     * @param ownerNode 所属页面节点
     * @param title 窗口标题
     * @param header 操作标题
     * @param instruction 输入值的用途和后续影响
     * @param promptText 输入框内的操作提示
     * @param initialValue 初始值；可空
     * @param actionLabel 确认按钮文字
     * @return 已应用平台样式的输入 Dialog
     */
    public static TextInputDialog requiredText(
            Node ownerNode,
            String title,
            String header,
            String instruction,
            String promptText,
            String initialValue,
            String actionLabel) {
        TextInputDialog dialog = new TextInputDialog(Objects.requireNonNullElse(initialValue, ""));
        dialog.setTitle(requireText(title, "title"));
        dialog.setHeaderText(requireText(header, "header"));
        configureTextBody(dialog, requireText(instruction, "instruction"), requireText(promptText, "promptText"));
        Button action = actionButton(dialog, actionLabel);
        TextField editor = dialog.getEditor();
        Runnable update = () -> action.setDisable(editor.getText().strip().isEmpty());
        editor.textProperty().addListener((ignored, previous, value) -> update.run());
        update.run();
        style(dialog, ownerNode);
        return dialog;
    }

    /**
     * 创建逐字确认 Dialog；确认按钮只在输入与预期文本完全一致时可用。
     *
     * @param ownerNode 所属页面节点
     * @param title 窗口标题
     * @param consequence 操作后果
     * @param expectedText 必须逐字输入的确认语句
     * @param actionLabel 最终动作按钮文字
     * @return 已应用平台样式和精确匹配门禁的输入 Dialog
     */
    public static TextInputDialog exactText(
            Node ownerNode, String title, String consequence, String expectedText, String actionLabel) {
        String expected = requireText(expectedText, "expectedText");
        String actionText = requireText(actionLabel, "actionLabel");
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle(requireText(title, "title"));
        dialog.setHeaderText(requireText(consequence, "consequence"));
        configureExactTextBody(dialog, expected, actionText);
        Button action = actionButton(dialog, actionText);
        TextField editor = dialog.getEditor();
        Runnable update = () -> action.setDisable(!expected.equals(editor.getText()));
        editor.textProperty().addListener((ignored, previous, value) -> update.run());
        update.run();
        style(dialog, ownerNode);
        return dialog;
    }

    private static void style(Dialog<?> dialog, Window requestedOwner, Scene ownerScene) {
        Dialog<?> checked = Objects.requireNonNull(dialog, "dialog");
        if (requestedOwner != null && checked.getOwner() == null) {
            checked.initOwner(requestedOwner);
        }
        DialogPane pane = checked.getDialogPane();
        addStyleClass(pane, "root");
        addStyleClass(pane, STYLE_MARKER);
        DesktopStylesheets.applyTo(pane);
        inheritOwnerAppearance(pane, ownerScene);
        checked.setGraphic(null);
        prepareContent(checked);
        applyDialogBounds(checked);
        if (pane.getProperties().putIfAbsent(PREPARED_KEY, Boolean.TRUE) == null) {
            checked.showingProperty().addListener((ignored, previous, showing) -> {
                if (showing) {
                    Scene currentOwnerScene = checked.getOwner() == null
                            ? ownerScene
                            : checked.getOwner().getScene();
                    inheritOwnerAppearance(pane, currentOwnerScene);
                    prepareContent(checked);
                    Platform.runLater(() -> constrainWindow(checked));
                }
            });
        }
    }

    private static void configureTextBody(TextInputDialog dialog, String instruction, String promptText) {
        TextField editor = dialog.getEditor();
        detach(editor);
        editor.setPromptText(promptText);
        editor.setAccessibleText(instruction);
        Label message = message(instruction, "dialog-message");
        message.setLabelFor(editor);
        VBox body = new VBox(10, message, editor);
        configureInputBody(dialog, body);
    }

    private static void configureExactTextBody(TextInputDialog dialog, String expected, String actionLabel) {
        TextField editor = dialog.getEditor();
        detach(editor);
        editor.setPromptText("在此逐字输入上方确认语句");
        editor.setAccessibleText("精确确认输入；必须与上方确认语句逐字一致");
        Label instruction = message("请在下方输入框中逐字输入确认语句。完全一致后“" + actionLabel + "”按钮才会启用。", "dialog-message");
        instruction.setLabelFor(editor);
        Label confirmation = message(expected, "platform-detail-title");
        confirmation.setAccessibleText("需要输入的确认语句：" + expected);
        Label hint = message("请保留大小写、空格和标点。", "sec-hint");
        VBox body = new VBox(8, instruction, confirmation, editor, hint);
        configureInputBody(dialog, body);
    }

    private static void configureInputBody(TextInputDialog dialog, VBox body) {
        body.setPrefWidth(INPUT_WIDTH);
        body.setMinWidth(0);
        body.getStyleClass().add(NO_AUTO_SCROLL_STYLE);
        dialog.getEditor().setMaxWidth(Double.MAX_VALUE);
        dialog.getDialogPane().setContent(body);
    }

    private static Button actionButton(TextInputDialog dialog, String actionLabel) {
        Button action = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        action.setText(requireText(actionLabel, "actionLabel"));
        return action;
    }

    private static void detach(Node node) {
        Parent parent = node.getParent();
        if (parent instanceof Pane pane) {
            pane.getChildren().remove(node);
        }
    }

    private static void prepareContent(Dialog<?> dialog) {
        if (dialog instanceof Alert alert) {
            prepareAlertContent(alert);
        }
        prepareScrollableContent(dialog.getDialogPane());
    }

    private static void prepareAlertContent(Alert alert) {
        alert.setGraphic(null);
        DialogPane pane = alert.getDialogPane();
        if (pane.getContent() != null) {
            return;
        }
        String content = alert.getContentText();
        if (content == null || content.isBlank()) {
            return;
        }
        Label label = message(content, "dialog-message");
        double width = Math.min(MESSAGE_WIDTH, Math.max(MESSAGE_MIN_WIDTH, label.prefWidth(-1)));
        label.setPrefWidth(width);
        label.setMaxWidth(Double.MAX_VALUE);
        ScrollPane scroll = scroll(label, "dialog-message-scroll");
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportWidth(width);
        scroll.setPrefViewportHeight(Math.min(MESSAGE_MAX_HEIGHT, Math.max(36, label.prefHeight(width))));
        scroll.setMaxHeight(MESSAGE_MAX_HEIGHT);
        pane.setContent(scroll);
    }

    private static void prepareScrollableContent(DialogPane pane) {
        Node content = pane.getContent();
        if (content == null
                || pane.getStyleClass().contains(WIDE_STYLE)
                || content instanceof ScrollPane
                || content instanceof TextArea
                || content instanceof ListView<?>
                || content instanceof TableView<?>
                || content instanceof TreeView<?>
                || content.getStyleClass().contains(NO_AUTO_SCROLL_STYLE)) {
            return;
        }
        ScrollPane scroll = scroll(content, "dialog-body-scroll");
        scroll.setMaxHeight(MESSAGE_MAX_HEIGHT + 120);
        pane.setContent(scroll);
    }

    private static ScrollPane scroll(Node content, String styleClass) {
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

    private static Label message(String text, String styleClass) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMinWidth(0);
        label.getStyleClass().add(styleClass);
        return label;
    }

    private static void inheritOwnerAppearance(DialogPane pane, Scene ownerScene) {
        if (ownerScene == null) {
            return;
        }
        for (String stylesheet : ownerScene.getStylesheets()) {
            if (!pane.getStylesheets().contains(stylesheet)) {
                pane.getStylesheets().add(stylesheet);
            }
        }
        pane.getStyleClass().removeIf(PlatformDialogs::isAppearanceClass);
        ownerScene.getRoot().getStyleClass().stream()
                .filter(PlatformDialogs::isAppearanceClass)
                .forEach(style -> addStyleClass(pane, style));
    }

    private static boolean isAppearanceClass(String value) {
        return value.startsWith("theme-") || value.startsWith("font-scale-") || value.startsWith("density-");
    }

    private static void addStyleClass(Parent parent, String styleClass) {
        if (!parent.getStyleClass().contains(styleClass)) {
            parent.getStyleClass().add(styleClass);
        }
    }

    private static void applyDialogBounds(Dialog<?> dialog) {
        DialogPane pane = dialog.getDialogPane();
        pane.setMaxWidth(pane.getStyleClass().contains(WIDE_STYLE) ? WIDE_DIALOG_MAX_WIDTH : DIALOG_MAX_WIDTH);
        pane.setMaxHeight(pane.getStyleClass().contains(WIDE_STYLE) ? WIDE_DIALOG_MAX_HEIGHT : DIALOG_MAX_HEIGHT);
    }

    private static void constrainWindow(Dialog<?> dialog) {
        DialogPane pane = dialog.getDialogPane();
        if (pane.getScene() == null || !(pane.getScene().getWindow() instanceof Stage stage)) {
            return;
        }
        Rectangle2D bounds = visualBounds(dialog);
        double maxWidth = maxDialogWidth(pane, bounds);
        double maxHeight = maxDialogHeight(pane, bounds);
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
        if (owner == null) {
            return Screen.getPrimary().getVisualBounds();
        }
        return Screen.getScreensForRectangle(owner.getX(), owner.getY(), owner.getWidth(), owner.getHeight()).stream()
                .findFirst()
                .orElseGet(Screen::getPrimary)
                .getVisualBounds();
    }

    private static double maxDialogWidth(DialogPane pane, Rectangle2D bounds) {
        double maximum = pane.getStyleClass().contains(WIDE_STYLE) ? WIDE_DIALOG_MAX_WIDTH : DIALOG_MAX_WIDTH;
        return Math.min(maximum, bounds.getWidth() * 0.88);
    }

    private static double maxDialogHeight(DialogPane pane, Rectangle2D bounds) {
        double maximum = pane.getStyleClass().contains(WIDE_STYLE) ? WIDE_DIALOG_MAX_HEIGHT : DIALOG_MAX_HEIGHT;
        return Math.min(maximum, bounds.getHeight() * 0.82);
    }

    private static String requireText(String value, String name) {
        String checked = Objects.requireNonNull(value, name).strip();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return checked;
    }
}
