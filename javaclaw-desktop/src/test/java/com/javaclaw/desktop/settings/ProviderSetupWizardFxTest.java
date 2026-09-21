package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.ProviderConfigurationResult;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPreviewResult;
import com.javaclaw.client.RemoteRpcException;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.protocol.JsonRpcError;
import com.javaclaw.protocol.ProtocolErrorCode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderSetupWizardFxTest {
    @Test
    void 下一步只预览且最终保存不应用入口工作区并可取消启用() {
        FxTestSupport.run(() -> {
            var gateway = new ProviderConfigurationTestGateway();
            AtomicInteger completed = new AtomicInteger();
            AtomicInteger used = new AtomicInteger();
            ProviderSetupWizard.show(
                    null,
                    gateway,
                    new ProviderSetupTarget(Optional.empty(), Optional.empty(), "入口"),
                    completed::incrementAndGet,
                    used::incrementAndGet);
            Stage window = window();
            try {
                Parent root = window.getScene().getRoot();
                connect(root);
                assertEquals(0, gateway.saved.size());
                assertEquals(0, gateway.providerCredentialSetCalls);
                assertEquals("", text(root, "providerWizardSecret").getText());
                assertNull(root.lookup("#providerWizardWorkspace"));
                assertNull(root.lookup("#providerWizardCurrentModel"));
                manual(root, "manual-model");
                ((CheckBox) root.lookup("#providerWizardEnable")).setSelected(false);
                button(root, "providerWizardContinue").fire();
                assertEquals(ProviderLifecycle.DISABLED, gateway.configuration.lifecycle());
                assertEquals(1, gateway.saved.size());
                assertEquals(1, completed.get());
                assertEquals(0, used.get());
                assertEquals(0, gateway.uses);
                closeSaved(window, root);
            } finally {
                window.hide();
            }
        });
    }

    @Test
    void 目录读取期间仍可手动保存并取消迟到目录() {
        FxTestSupport.run(() -> {
            var gateway = new ProviderConfigurationTestGateway();
            var response = new CompletableFuture<ProviderModelPreviewResult>();
            gateway.previewResponses.add(response);
            Stage window = open(gateway);
            try {
                Parent root = window.getScene().getRoot();
                connect(root);
                expandManual(root);
                assertFalse(text(root, "providerWizardManualModel").isDisabled());
                manual(root, "manual-during-preview");
                button(root, "providerWizardContinue").fire();
                response.complete(gateway.previewResult(gateway.previews.getFirst()));
                assertEquals(
                        "manual-during-preview",
                        gateway.configuration.models().getFirst().modelId());
                assertTrue(gateway.cancellations.getFirst().isCancelled());
                closeSaved(window, root);
            } finally {
                window.hide();
            }
        });
    }

    @Test
    void 保存等待回执时锁住上下文和关闭且失败保留非秘密模型草稿() {
        FxTestSupport.run(() -> {
            var gateway = new ProviderConfigurationTestGateway();
            var response = new CompletableFuture<ProviderConfigurationResult>();
            gateway.saveResponses.add(response);
            Stage window = open(gateway);
            try {
                Parent root = window.getScene().getRoot();
                connect(root);
                manual(root, "retained-model");
                button(root, "providerWizardContinue").fire();
                assertTrue(button(root, "providerWizardBack").isDisabled());
                assertTrue(text(root, "providerWizardManualModel").isDisabled());
                // 模拟系统标题栏关闭请求；Stage.close() 是宿主强制隐藏，绕过 JavaFX 用户关闭事件。
                window.fireEvent(new javafx.stage.WindowEvent(window, javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST));
                assertTrue(window.isShowing());
                response.completeExceptionally(new RemoteRpcException(
                        new JsonRpcError(ProtocolErrorCode.REVISION_CONFLICT, "版本冲突", Optional.empty())));
                assertFalse(button(root, "providerWizardBack").isDisabled());
                assertEquals("返回填写密钥", button(root, "providerWizardContinue").getText());
                String failure = ((Label) root.lookup("#providerWizardStatus")).getText();
                assertTrue(failure.contains("尚未保存"));
                assertTrue(failure.contains("版本冲突"));
                button(root, "providerWizardContinue").fire();
                assertEquals(failure, ((Label) root.lookup("#providerWizardStatus")).getText());
                assertEquals(1, gateway.saved.size());
                assertEquals("", text(root, "providerWizardSecret").getText());
                text(root, "providerWizardSecret").setText("retry-key");
                button(root, "providerWizardContinue").fire();
                button(root, "providerWizardContinue").fire();
                assertEquals(
                        "retained-model",
                        gateway.configuration.models().getFirst().modelId());
                assertEquals(2, gateway.saved.size());
                closeSaved(window, root);
            } finally {
                window.hide();
            }
        });
    }

    @Test
    void 密钥库失败保留原因且刷新与迟到目录不会掩盖未保存状态() {
        FxTestSupport.run(() -> {
            var gateway = new ProviderConfigurationTestGateway();
            gateway.vaultStatus = new com.javaclaw.api.VaultStatus(
                    com.javaclaw.api.VaultState.LOCKED,
                    com.javaclaw.api.VaultLockReason.MASTER_KEY_MISSING,
                    0,
                    false,
                    java.time.Instant.EPOCH);
            gateway.refreshedVaultStatus = gateway.vaultStatus;
            var preview = new CompletableFuture<ProviderModelPreviewResult>();
            gateway.previewResponses.add(preview);
            Stage window = open(gateway);
            try {
                Parent root = window.getScene().getRoot();
                connect(root);
                manual(root, "retained-after-vault-failure");
                button(root, "providerWizardContinue").fire();
                String failure = ((Label) root.lookup("#providerWizardStatus")).getText();
                assertTrue(failure.startsWith("尚未保存："));
                assertEquals("返回填写密钥", button(root, "providerWizardContinue").getText());
                assertEquals(0, gateway.preparations);
                assertEquals(0, gateway.saved.size());
                preview.complete(gateway.previewResult(gateway.previews.getFirst()));
                assertEquals(failure, ((Label) root.lookup("#providerWizardStatus")).getText());
                button(root, "providerWizardDiscoverModels").fire();
                assertEquals(1, gateway.previews.size());
                assertEquals(failure, ((Label) root.lookup("#providerWizardStatus")).getText());
                assertEquals("下一步：获取模型", button(root, "providerWizardContinue").getText());
                assertEquals("", text(root, "providerWizardSecret").getText());
            } finally {
                window.hide();
            }
        });
    }

    @Test
    void 未确认回执只查询同一请求且取消按钮保持禁用() {
        FxTestSupport.run(() -> {
            var gateway = new ProviderConfigurationTestGateway();
            gateway.saveResponses.add(CompletableFuture.failedFuture(new IllegalStateException("连接断开")));
            Stage window = open(gateway);
            try {
                Parent root = window.getScene().getRoot();
                connect(root);
                manual(root, "unknown-model");
                button(root, "providerWizardContinue").fire();
                assertEquals("查询保存结果", button(root, "providerWizardContinue").getText());
                assertTrue(((DialogPane) root).lookupButton(ButtonType.CANCEL).isDisabled());
                button(root, "providerWizardContinue").fire();
                assertEquals(1, gateway.saved.size());
                assertEquals(2, gateway.queried.size());
                gateway.committed = gateway.result(gateway.configuration);
                button(root, "providerWizardContinue").fire();
                closeSaved(window, root);
                assertEquals(1, gateway.saved.size());
            } finally {
                window.hide();
            }
        });
    }

    @Test
    void 取消放弃确认保留窗口与草稿再次确认才清理并关闭() {
        FxTestSupport.run(() -> {
            var gateway = new ProviderConfigurationTestGateway();
            Stage window = open(gateway);
            Parent root = window.getScene().getRoot();
            try {
                text(root, "providerWizardAddress").setText("http://localhost:11434/v1");
                text(root, "providerWizardSecret").setText("unsaved-key");
                dismissConfirmation(false);
                ((Button) ((DialogPane) root).lookupButton(ButtonType.CANCEL)).fire();
                assertTrue(window.isShowing());
                assertEquals("unsaved-key", text(root, "providerWizardSecret").getText());
                dismissConfirmation(true);
                ((Button) ((DialogPane) root).lookupButton(ButtonType.CANCEL)).fire();
                assertFalse(window.isShowing());
                assertEquals("", text(root, "providerWizardSecret").getText());
                assertEquals(0, gateway.saved.size());
            } finally {
                window.hide();
            }
        });
    }

    @Test
    void 能力协商尚未完成或不支持时禁用下一步并解释原因() {
        FxTestSupport.run(() -> {
            var gateway = new ProviderConfigurationTestGateway();
            var capability = new CompletableFuture<Boolean>();
            gateway.supported = capability;
            Stage window = open(gateway);
            try {
                Parent root = window.getScene().getRoot();
                assertTrue(button(root, "providerWizardContinue").isDisabled());
                capability.complete(false);
                assertTrue(button(root, "providerWizardContinue").isDisabled());
                assertTrue(((javafx.scene.control.Label) root.lookup("#providerWizardDisabledReason"))
                        .getText()
                        .contains("升级"));
                assertEquals(0, gateway.saved.size());
            } finally {
                window.hide();
            }
        });
    }

    static Stage open(ProviderConfigurationTestGateway gateway) {
        ProviderSetupWizard.configure(null, gateway, Optional.empty(), 0, ignored -> {});
        return window();
    }

    static Stage window() {
        return Window.getWindows().stream()
                .filter(value -> value.getScene() != null && value.getScene().lookup("#providerWizardAddress") != null)
                .map(Stage.class::cast)
                .findFirst()
                .orElseThrow();
    }

    static void connect(Parent root) {
        text(root, "providerWizardAddress").setText("http://localhost:11434/v1");
        text(root, "providerWizardSecret").setText("temporary-key");
        button(root, "providerWizardContinue").fire();
    }

    static void manual(Parent root, String model) {
        expandManual(root);
        text(root, "providerWizardManualModel").setText(model);
        button(root, "providerWizardAddManual").fire();
    }

    static void expandManual(Parent root) {
        ((TitledPane) root.lookup("#providerWizardManualSection")).setExpanded(true);
        root.applyCss();
        root.layout();
    }

    static void closeSaved(Stage window, Parent root) {
        assertTrue(window.isShowing(), "保存后明确展示结果，由用户关闭");
        assertTrue(root.lookup("#providerWizardCompletion").isVisible());
        assertTrue(
                ((Label) root.lookup("#providerWizardSavedSummary")).getText().contains("已保存 1 个模型"));
        assertEquals("关闭", button(root, "providerWizardContinue").getText());
        assertFalse(button(root, "providerWizardBack").isVisible());
        button(root, "providerWizardContinue").fire();
        assertFalse(window.isShowing());
    }

    static TextField text(Parent root, String id) {
        return (TextField) root.lookup("#" + id);
    }

    static Button button(Parent root, String id) {
        return (Button) root.lookup("#" + id);
    }

    @SuppressWarnings("unchecked")
    static CheckBox modelChoice(Parent root, String id) {
        ListView<String> directory = (ListView<String>) root.lookup("#providerWizardModelsList");
        directory.scrollTo(id);
        root.applyCss();
        root.layout();
        directory.layout();
        return directory.lookupAll(".check-box").stream()
                .filter(CheckBox.class::isInstance)
                .map(CheckBox.class::cast)
                .filter(choice ->
                        id.equals(choice.getText()) || choice.getText().endsWith(" · " + id))
                .findFirst()
                .orElseThrow();
    }

    private static void dismissConfirmation(boolean discard) {
        Platform.runLater(() -> {
            List<Window> windows = List.copyOf(Window.getWindows());
            DialogPane confirmation = windows.stream()
                    .filter(value -> value.getScene() != null)
                    .map(value -> value.getScene().getRoot())
                    .filter(DialogPane.class::isInstance)
                    .map(DialogPane.class::cast)
                    .filter(pane -> "配置尚未保存".equals(pane.getHeaderText()))
                    .findFirst()
                    .orElseThrow();
            ((Button) confirmation.lookupButton(discard ? ButtonType.OK : ButtonType.CANCEL)).fire();
        });
    }
}
