package com.javaclaw.desktop.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.ProviderConfigurationResult;
import com.javaclaw.api.ProviderCredentialChange;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderConfigurationEditorTest {
    @Test
    void 无需读取目录即可手动添加并原子保存且不自动使用模型() {
        FxTestSupport.run(() -> {
            var gateway = new ProviderConfigurationTestGateway();
            try (Fixture fixture = fixture(gateway, Optional.empty())) {
                connection(fixture.root());
                manual(fixture.root(), "manual-without-discovery");
                save(fixture.root()).fire();

                assertEquals(0, gateway.previews.size());
                assertEquals(1, gateway.preparations);
                assertEquals(1, gateway.saved.size());
                assertEquals(1, fixture.completed().size());
                assertEquals(
                        "manual-without-discovery",
                        gateway.configuration.models().getFirst().modelId());
                assertEquals(ProviderLifecycle.ACTIVE, gateway.configuration.lifecycle());
                assertEquals(ProviderCredentialChange.REPLACE, gateway.configuration.credentialChange());
                assertEquals("", text(fixture.root(), "providerWizardSecret").getText());
                assertTrue(secretCleared(gateway.submittedSecret));
                assertFalse(fixture.editor().dirty());
                assertEquals(0, gateway.uses);
            }
        });
    }

    @Test
    void 保存准备异常不透出类名秘密且未提交请求不查询回执重填密钥可保留模型() {
        FxTestSupport.run(() -> {
            var gateway = new ProviderConfigurationTestGateway();
            String sensitive = "private-fixture-secret";
            gateway.preparationResponse = CompletableFuture.failedFuture(new IllegalArgumentException(
                    "com.javaclaw.client.facade.PreparedProviderConfiguration$DigestInput setAccess " + sensitive));
            try (Fixture fixture = fixture(gateway, Optional.empty())) {
                connection(fixture.root());
                manual(fixture.root(), "retained-after-prepare-failure");
                save(fixture.root()).fire();
                assertEquals(1, gateway.preparations);
                assertTrue(gateway.saved.isEmpty());
                assertTrue(gateway.queried.isEmpty(), "未完成密封准备的请求没有可查询的提交身份");
                assertTrue(status(fixture.root()).contains("配置未能提交"));
                assertTrue(status(fixture.root()).contains("填写内容已保留"));
                assertFalse(labels(fixture.root()).contains("DigestInput"));
                assertFalse(labels(fixture.root()).contains(sensitive));
                assertFalse(labels(fixture.root()).contains("setAccess"));
                assertTrue(fixture.editor().dirty());
                assertFalse(fixture.editor().pending());
                assertEquals("", text(fixture.root(), "providerWizardSecret").getText());
                assertTrue(((ListView<?>) fixture.root().lookup("#providerWizardModelsList"))
                        .getItems()
                        .contains("retained-after-prepare-failure"));
                gateway.preparationResponse = null;
                text(fixture.root(), "providerWizardSecret").setText("replacement-fixture-key");
                save(fixture.root()).fire();
                assertEquals(2, gateway.preparations);
                assertEquals(1, gateway.saved.size());
                assertEquals(1, fixture.completed().size());
                assertEquals(
                        "retained-after-prepare-failure",
                        gateway.configuration.models().getFirst().modelId());
                assertTrue(gateway.queried.isEmpty());
            }
        });
    }

    @Test
    void 断线与明确不支持显示不同原因且断线清除临时输入() {
        FxTestSupport.run(() -> {
            var disconnected = new ProviderConfigurationTestGateway();
            try (Fixture fixture = fixture(disconnected, Optional.empty())) {
                connection(fixture.root());
                manual(fixture.root(), "retained-after-disconnect");
                disconnected.invalidated.run();
                assertTrue(status(fixture.root()).contains("连接已失效"));
                assertFalse(status(fixture.root()).contains("升级"));
                assertTrue(save(fixture.root()).isDisabled());
                assertTrue(
                        button(fixture.root(), "providerConfigurationReconnect").isVisible());
                assertEquals("", text(fixture.root(), "providerWizardSecret").getText());
                assertTrue(((ListView<?>) fixture.root().lookup("#providerWizardModelsList"))
                        .getItems()
                        .contains("retained-after-disconnect"));
            }
            var unsupported = new ProviderConfigurationTestGateway();
            unsupported.supported = CompletableFuture.completedFuture(false);
            try (Fixture fixture = fixture(unsupported, Optional.empty())) {
                assertTrue(status(fixture.root()).contains("升级 App Server"));
                assertTrue(save(fixture.root()).isDisabled());
                assertFalse(
                        button(fixture.root(), "providerConfigurationReconnect").isVisible());
            }
        });
    }

    @Test
    void 能力检测失败不误报服务端需要升级() {
        FxTestSupport.run(() -> {
            var gateway = new ProviderConfigurationTestGateway();
            gateway.supported = CompletableFuture.failedFuture(new IllegalStateException("private-connection-detail"));
            try (Fixture fixture = fixture(gateway, Optional.empty())) {
                assertTrue(status(fixture.root()).contains("无法检查"));
                assertFalse(status(fixture.root()).contains("升级"));
                assertFalse(labels(fixture.root()).contains("private-connection-detail"));
                assertTrue(
                        button(fixture.root(), "providerConfigurationReconnect").isVisible());
                assertTrue(save(fixture.root()).isDisabled());
            }
        });
    }

    @Test
    void 保存结果未知锁住草稿且后续只查询同一回执不重复提交() {
        FxTestSupport.run(() -> {
            var gateway = new ProviderConfigurationTestGateway();
            gateway.saveResponses.add(CompletableFuture.failedFuture(new IllegalStateException("transport closed")));
            try (Fixture fixture = fixture(gateway, Optional.empty())) {
                connection(fixture.root());
                manual(fixture.root(), "unknown-commit-model");
                save(fixture.root()).fire();
                assertTrue(fixture.editor().pending());
                assertEquals("查询保存结果", save(fixture.root()).getText());
                assertTrue(
                        button(fixture.root(), "providerConfigurationDiscard").isDisabled());
                assertTrue(text(fixture.root(), "providerWizardAddress").isDisabled());
                assertTrue(fixture.root().lookup("#providerWizardModelsList").isDisabled());
                assertEquals(1, gateway.saved.size());
                assertEquals(1, gateway.queried.size());
                save(fixture.root()).fire();
                save(fixture.root()).fire();
                assertEquals(1, gateway.preparations);
                assertEquals(1, gateway.saved.size());
                assertEquals(3, gateway.queried.size());
                gateway.queried.forEach(value -> assertSame(gateway.saved.getFirst(), value));
                gateway.committed = gateway.result(gateway.configuration);
                save(fixture.root()).fire();
                assertEquals(1, fixture.completed().size());
                assertFalse(fixture.editor().pending());
                assertFalse(fixture.editor().dirty());
                assertEquals(1, gateway.saved.size());
            }
        });
    }

    @Test
    void 已有服务停用与重新启用保留模型和已有密钥() {
        FxTestSupport.run(() -> {
            var gateway = new ProviderConfigurationTestGateway();
            ProviderEndpoint active = TestCoreSettingsFixtures.provider(
                    3,
                    TestCoreSettingsFixtures.providerSpec(Optional.of(new CredentialRef("provider", "existing"))),
                    ProviderLifecycle.ACTIVE);
            gateway.providers.clear();
            gateway.providers.add(active);
            ProviderEndpoint disabled;
            try (Fixture fixture = fixture(gateway, Optional.of(active))) {
                assertFalse(fixture.editor().dirty());
                ((CheckBox) fixture.root().lookup("#providerWizardEnable")).setSelected(false);
                save(fixture.root()).fire();
                disabled = fixture.completed().getFirst().provider();
                assertEquals(ProviderLifecycle.DISABLED, disabled.lifecycle());
                assertEquals(active.spec().models(), disabled.spec().models());
                assertEquals(ProviderCredentialChange.KEEP, gateway.configuration.credentialChange());
            }
            try (Fixture fixture = fixture(gateway, Optional.of(disabled))) {
                ((CheckBox) fixture.root().lookup("#providerWizardEnable")).setSelected(true);
                save(fixture.root()).fire();
                ProviderEndpoint enabled = fixture.completed().getFirst().provider();
                assertEquals(ProviderLifecycle.ACTIVE, enabled.lifecycle());
                assertEquals(active.spec().models(), enabled.spec().models());
                assertEquals(disabled.revision(), gateway.configuration.expectedRevision());
                assertEquals(ProviderCredentialChange.KEEP, gateway.configuration.credentialChange());
                assertEquals(2, gateway.saved.size());
                assertEquals(0, gateway.uses);
            }
        });
    }

    private static Fixture fixture(ProviderConfigurationTestGateway gateway, Optional<ProviderEndpoint> source) {
        List<ProviderConfigurationResult> completed = new ArrayList<>();
        var editor = new ProviderConfigurationEditor(
                gateway, source, source.isPresent() ? 1 : 0, completed::add, () -> {}, () -> {});
        BorderPane root = new BorderPane(editor);
        root.setBottom(editor.actionContent());
        new Scene(root, 1040, 720);
        root.applyCss();
        root.layout();
        return new Fixture(editor, root, completed);
    }

    private static void connection(Parent root) {
        text(root, "providerWizardAddress").setText("http://localhost:11434/v1");
        text(root, "providerWizardSecret").setText("fixture-key");
    }

    private static void manual(Parent root, String id) {
        ((TitledPane) root.lookup("#providerWizardManualSection")).setExpanded(true);
        root.applyCss();
        root.layout();
        text(root, "providerWizardManualModel").setText(id);
        button(root, "providerWizardAddManual").fire();
    }

    private static Button save(Parent root) {
        return button(root, "providerConfigurationSave");
    }

    private static Button button(Parent root, String id) {
        return (Button) root.lookup("#" + id);
    }

    private static TextField text(Parent root, String id) {
        return (TextField) root.lookup("#" + id);
    }

    private static String status(Parent root) {
        return ((Label) root.lookup(".platform-action-status")).getText();
    }

    private static String labels(Parent root) {
        return root.lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(Label.class::cast)
                .map(Label::getText)
                .reduce("", (first, second) -> first + "\n" + second);
    }

    private static boolean secretCleared(char[] secret) {
        for (char character : secret) {
            if (character != '\0') {
                return false;
            }
        }
        return true;
    }

    /** 编辑器及其可查询根节点、成功回调结果；均不为空，关闭时清除临时秘密和订阅。 */
    private record Fixture(ProviderConfigurationEditor editor, Parent root, List<ProviderConfigurationResult> completed)
            implements AutoCloseable {
        @Override
        public void close() {
            editor.close();
        }
    }
}
