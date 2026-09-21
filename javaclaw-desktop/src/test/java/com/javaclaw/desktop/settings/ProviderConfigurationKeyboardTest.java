package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.ProviderConfigurationResult;
import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 通过真实 Scene 事件传播验证键盘路径，不直接调用取消或添加按钮来替代被测操作。 */
class ProviderConfigurationKeyboardTest {
    @Test
    void 无草稿时Esc关闭窗口并释放会话订阅() {
        FxTestSupport.run(() -> {
            var gateway = new ProviderConfigurationTestGateway();
            Stage window = ProviderSetupWizardFxTest.open(gateway);
            try {
                Parent root = ready(window);
                press(root.lookup("#providerWizardAddress"), KeyCode.ESCAPE);
                assertFalse(window.isShowing());
                assertTrue(gateway.subscriptionClosed);
                assertEquals(0, gateway.saved.size());
            } finally {
                window.hide();
            }
        });
    }

    @Test
    void 有草稿时Esc询问放弃且取消确认保留输入和窗口() {
        FxTestSupport.run(() -> {
            var gateway = new ProviderConfigurationTestGateway();
            Stage window = ProviderSetupWizardFxTest.open(gateway);
            try {
                Parent root = ready(window);
                TextField address = ProviderSetupWizardFxTest.text(root, "providerWizardAddress");
                TextField secret = ProviderSetupWizardFxTest.text(root, "providerWizardSecret");
                address.setText("https://keyboard.example/v1");
                secret.setText("keyboard-draft-key");
                AtomicBoolean confirmationObserved = cancelNextDiscardConfirmation();
                press(secret, KeyCode.ESCAPE);
                assertTrue(confirmationObserved.get(), "Esc 必须进入同一放弃草稿确认流程");
                assertTrue(window.isShowing());
                assertEquals("https://keyboard.example/v1", address.getText());
                assertEquals("keyboard-draft-key", secret.getText());
                assertEquals(0, gateway.saved.size());
            } finally {
                window.hide();
            }
        });
    }

    @Test
    void 手动模型输入Enter添加模型且不触发最终保存() {
        FxTestSupport.run(() -> {
            var gateway = new ProviderConfigurationTestGateway();
            Stage window = ProviderSetupWizardFxTest.open(gateway);
            try {
                Parent root = ready(window);
                ProviderSetupWizardFxTest.connect(root);
                ProviderSetupWizardFxTest.expandManual(root);
                TextField manual = ProviderSetupWizardFxTest.text(root, "providerWizardManualModel");
                manual.setText("keyboard-enter-model");
                press(manual, KeyCode.ENTER);
                assertEquals("", manual.getText());
                assertTrue(window.isShowing());
                assertEquals(0, gateway.saved.size(), "Enter 只确认手动输入，不能透传给窗口默认保存按钮");
                ProviderSetupWizardFxTest.button(root, "providerWizardContinue").fire();
                assertEquals(1, gateway.saved.size());
                assertEquals(
                        "keyboard-enter-model",
                        gateway.configuration.models().getFirst().modelId());
            } finally {
                window.hide();
            }
        });
    }

    @Test
    void 保存等待回执时Esc不能关闭或取消已发送请求() {
        FxTestSupport.run(() -> {
            var gateway = new ProviderConfigurationTestGateway();
            var response = new CompletableFuture<ProviderConfigurationResult>();
            gateway.saveResponses.add(response);
            var wizard = ProviderSetupWizard.configure(null, gateway, Optional.empty(), 0, ignored -> {});
            Stage window = ProviderSetupWizardFxTest.window();
            try {
                Parent root = ready(window);
                ProviderSetupWizardFxTest.connect(root);
                ProviderSetupWizardFxTest.manual(root, "pending-keyboard-model");
                ProviderSetupWizardFxTest.button(root, "providerWizardContinue").fire();
                press(root, KeyCode.ESCAPE);
                assertTrue(window.isShowing());
                assertTrue(wizard.pending());
                assertFalse(response.isDone());
                assertEquals(1, gateway.saved.size());
                response.complete(gateway.result(gateway.configuration));
                assertTrue(window.isShowing());
                assertFalse(wizard.pending());
                assertTrue(gateway.subscriptionClosed);
                press(root, KeyCode.ESCAPE);
                assertFalse(window.isShowing());
                assertEquals(1, gateway.saved.size());
            } finally {
                wizard.dispose();
            }
        });
    }

    private static Parent ready(Stage window) {
        Parent root = window.getScene().getRoot();
        root.applyCss();
        root.layout();
        return root;
    }

    private static void press(Node target, KeyCode code) {
        target.requestFocus();
        // 从控件向 Scene 传播，让 JavaFX TextField 行为及默认/取消按钮加速器共同处理键盘事件。
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", code, false, false, false, false));
    }

    private static AtomicBoolean cancelNextDiscardConfirmation() {
        AtomicBoolean observed = new AtomicBoolean();
        Platform.runLater(() -> {
            List<Window> windows = List.copyOf(Window.getWindows());
            windows.stream()
                    .filter(window -> window.getScene() != null)
                    .map(window -> window.getScene().getRoot())
                    .filter(DialogPane.class::isInstance)
                    .map(DialogPane.class::cast)
                    .filter(pane -> "配置尚未保存".equals(pane.getHeaderText()))
                    .findFirst()
                    .ifPresent(pane -> {
                        observed.set(true);
                        ((Button) pane.lookupButton(ButtonType.CANCEL)).fire();
                    });
        });
        return observed;
    }
}
