package com.javaclaw.ui.javafx.interaction;

import com.javaclaw.api.interaction.ChoiceRequest;
import com.javaclaw.api.interaction.ConfirmDecision;
import com.javaclaw.api.interaction.ConfirmKind;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.SecretRequest;
import com.javaclaw.app.UIHelper;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 从 FXML 创建交互弹窗，并在每次交互结束后立即销毁 Controller。
 *
 * <p>所有方法必须在 FX 线程调用且会阻塞到用户响应。取消确认视为拒绝，取消选择或
 * 安全输入返回 {@code null}；安全输入 Controller 在结果取出和销毁时都会清空明文。</p>
 */
public final class InteractionDialogFactory {

    private static final URL CONFIRM_VIEW = resource("confirm-dialog.fxml");
    private static final URL CHOICE_VIEW = resource("choice-dialog.fxml");
    private static final URL SECRET_VIEW = resource("secret-dialog.fxml");

    private static final ButtonType ALLOW = new ButtonType(
            "同意", ButtonBar.ButtonData.OK_DONE);
    private static final ButtonType ALLOW_ONCE = new ButtonType(
            "同意一次", ButtonBar.ButtonData.OK_DONE);
    private static final ButtonType ALLOW_ALL = new ButtonType(
            "同意全部", ButtonBar.ButtonData.YES);
    private static final ButtonType DENY = new ButtonType(
            "拒绝", ButtonBar.ButtonData.CANCEL_CLOSE);

    private final SpringFxmlLoader loader;
    private final UIHelper ui;

    public InteractionDialogFactory(SpringFxmlLoader loader, UIHelper ui) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.ui = Objects.requireNonNull(ui, "ui");
    }

    public ConfirmDecision confirm(ConfirmRequest request) {
        Objects.requireNonNull(request, "request");
        ViewHandle<VBox> handle = load(CONFIRM_VIEW, "确认弹窗");
        List<Node> boundButtons = new ArrayList<>();
        try {
            ConfirmDialogController controller = handle.controller(ConfirmDialogController.class);
            controller.configure(request);
            Dialog<ConfirmDecision> dialog = new Dialog<>();
            dialog.setTitle(request.kind() == ConfirmKind.DOUBLE_CONFIRM
                    ? "二次确认（不可逆操作）" : "操作确认");
            dialog.setHeaderText(request.kind() == ConfirmKind.DOUBLE_CONFIRM
                    ? "即将执行不可逆高风险操作：" + request.toolName()
                    : "是否同意执行：" + request.toolName());
            dialog.getDialogPane().setContent(handle.root());
            dialog.getDialogPane().setPrefWidth(600);
            if (request.managedTask()) {
                dialog.getDialogPane().getButtonTypes().setAll(DENY, ALLOW_ONCE, ALLOW_ALL);
            } else {
                dialog.getDialogPane().getButtonTypes().setAll(DENY, ALLOW);
            }
            bindAllowButtons(dialog, controller, request.managedTask(), boundButtons);
            dialog.setResultConverter(InteractionDialogFactory::decision);
            ui.styleDialog(dialog);
            return dialog.showAndWait().orElse(ConfirmDecision.DENY);
        } finally {
            boundButtons.forEach(button -> button.disableProperty().unbind());
            handle.close();
        }
    }

    public String choose(ChoiceRequest request) {
        Objects.requireNonNull(request, "request");
        ViewHandle<VBox> handle = load(CHOICE_VIEW, "选择弹窗");
        Button accept = null;
        try {
            ChoiceDialogController controller = handle.controller(ChoiceDialogController.class);
            controller.configure(request);
            Dialog<String> dialog = new Dialog<>();
            dialog.setTitle(request.title().isBlank() ? "请选择" : request.title());
            dialog.setHeaderText(null);
            dialog.getDialogPane().setContent(handle.root());
            dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, ButtonType.OK);
            accept = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
            accept.disableProperty().bind(controller.selectedProperty().isNull());
            dialog.setResultConverter(button -> button == ButtonType.OK
                    ? controller.selectedId() : null);
            ui.styleDialog(dialog);
            return dialog.showAndWait().orElse(null);
        } finally {
            if (accept != null) accept.disableProperty().unbind();
            handle.close();
        }
    }

    public char[] requestSecret(SecretRequest request) {
        Objects.requireNonNull(request, "request");
        ViewHandle<VBox> handle = load(SECRET_VIEW, "安全输入弹窗");
        try {
            SecretDialogController controller = handle.controller(SecretDialogController.class);
            controller.configure(request);
            Dialog<char[]> dialog = new Dialog<>();
            dialog.setTitle(request.title().isBlank() ? "安全输入" : request.title());
            dialog.setHeaderText(null);
            dialog.getDialogPane().setContent(handle.root());
            dialog.getDialogPane().getButtonTypes().setAll(DENY, ALLOW);
            dialog.setResultConverter(button -> button == ALLOW
                    ? controller.takeSecret() : null);
            ui.styleDialog(dialog);
            return dialog.showAndWait().orElse(null);
        } finally {
            handle.close();
        }
    }

    private static void bindAllowButtons(
            Dialog<?> dialog,
            ConfirmDialogController controller,
            boolean managed,
            List<Node> boundButtons) {
        Node once = dialog.getDialogPane().lookupButton(managed ? ALLOW_ONCE : ALLOW);
        once.disableProperty().bind(controller.validBinding().not());
        boundButtons.add(once);
        if (managed) {
            Node all = dialog.getDialogPane().lookupButton(ALLOW_ALL);
            all.disableProperty().bind(controller.validBinding().not());
            boundButtons.add(all);
        }
    }

    private ViewHandle<VBox> load(URL resource, String label) {
        try {
            return loader.load(resource);
        } catch (IOException failure) {
            throw new UncheckedIOException("加载" + label + "失败", failure);
        }
    }

    private static ConfirmDecision decision(ButtonType button) {
        if (button == ALLOW_ALL) return ConfirmDecision.ALLOW_ALL;
        if (button == ALLOW_ONCE || button == ALLOW) return ConfirmDecision.ALLOW_ONCE;
        return ConfirmDecision.DENY;
    }

    private static URL resource(String name) {
        return Objects.requireNonNull(InteractionDialogFactory.class.getResource(
                "/fxml/interaction/" + name), "缺少 " + name);
    }
}
