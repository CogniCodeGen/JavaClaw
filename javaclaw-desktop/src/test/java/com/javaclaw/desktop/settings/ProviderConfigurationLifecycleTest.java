package com.javaclaw.desktop.settings;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.ProviderConfigurationResult;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderModelDiscoveryCandidate;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.component.PlatformComponentFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderConfigurationLifecycleTest {
    @Test
    void 已绑定密钥元数据尚未读取时禁用配置且失败明确提示刷新() {
        FxTestSupport.run(() -> {
            var gateway = new MetadataGateway();
            var page = new ProviderSettingsPage(gateway);
            Parent root = attach(page);
            try {
                Button configure = (Button) root.lookup("#providerConfigureButton");
                Label reason = (Label) root.lookup("#providerConfigurationReason");
                assertTrue(configure.isDisabled());
                assertTrue(reason.getText().contains("正在读取"));
                gateway.metadata.completeExceptionally(new IllegalStateException("元数据读取失败"));
                assertTrue(configure.isDisabled());
                assertTrue(reason.getText().contains("刷新"));
                gateway.metadata = CompletableFuture.completedFuture(
                        Optional.of(new CredentialMetadata(gateway.reference, 4, Instant.EPOCH)));
                button(root, "刷新").fire();
                assertFalse(configure.isDisabled());
            } finally {
                page.dispose();
            }
        });
    }

    @Test
    void 外层已确认放弃草稿时直接清理秘密并关闭不再二次确认() {
        FxTestSupport.run(() -> {
            var page = new ProviderSettingsPage(new ProviderConfigurationTestGateway());
            Parent root = attach(page);
            try {
                ((Button) root.lookup("#providerCreateButton")).fire();
                root.applyCss();
                root.layout();
                TextField secret = ProviderSetupWizardFxTest.text(root, "providerWizardSecret");
                secret.setText("discarded-key");
                assertTrue(page.dirty());
                page.discardDraft();
                assertEquals(null, root.lookup("#providerConfigurationEditor"));
                assertEquals("", secret.getText());
                assertFalse(page.dirty());
                assertFalse(root.lookup("#providerServicesList").isDisabled());
            } finally {
                page.dispose();
            }
        });
    }

    @Test
    void 宿主销毁不声称撤销已发送保存且迟到结果不复活窗口() {
        FxTestSupport.run(() -> {
            var gateway = new ProviderConfigurationTestGateway();
            var response = new CompletableFuture<ProviderConfigurationResult>();
            gateway.saveResponses.add(response);
            var completed = new AtomicInteger();
            var wizard = ProviderSetupWizard.configure(
                    null, gateway, Optional.empty(), 0, ignored -> completed.incrementAndGet());
            Stage window = ProviderSetupWizardFxTest.window();
            Parent root = window.getScene().getRoot();
            ProviderSetupWizardFxTest.connect(root);
            ProviderSetupWizardFxTest.manual(root, "committing-model");
            ProviderSetupWizardFxTest.button(root, "providerWizardContinue").fire();
            assertEquals(1, gateway.saved.size());
            wizard.dispose();
            assertFalse(window.isShowing());
            assertTrue(gateway.subscriptionClosed);
            response.complete(gateway.result(gateway.configuration));
            assertEquals(0, completed.get());
            assertEquals(1, gateway.saved.size());
            assertEquals(0, gateway.uses);
        });
    }

    @Test
    void 目录回执保留正在编辑的维度节点与光标选择() {
        FxTestSupport.run(() -> {
            var form = new ProviderSetupModelForm(new PlatformComponentFactory(), () -> {});
            new Scene(form, 600, 500);
            form.seed(List.of(new ProviderModelSpec(
                    "embedding", "向量模型", Set.of(ProviderModelPurpose.EMBEDDING), OptionalInt.of(1024))));
            form.applyCss();
            form.layout();
            TextField dimension = dimensions(form);
            dimension.setText("768");
            dimension.selectRange(1, 3);
            form.candidates(List.of(new ProviderModelDiscoveryCandidate(
                    "new-model", "新发现模型", Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty())));
            assertSame(dimension, dimensions(form));
            assertEquals("768", dimension.getText());
            assertEquals(new javafx.scene.control.IndexRange(1, 3), dimension.getSelection());
            assertEquals(OptionalInt.of(768), form.selectedModels().getFirst().embeddingDimensions());
        });
    }

    @Test
    void 手动模型输入计入草稿并阻止忽略输入保存而搜索不算编辑() {
        FxTestSupport.run(() -> {
            var form = new ProviderSetupModelForm(new PlatformComponentFactory(), () -> {});
            new Scene(form, 600, 500);
            form.seed(ProviderSetupWorkflowTest.MODELS);
            AtomicInteger edits = new AtomicInteger();
            form.onChanged(edits::incrementAndGet);
            ((TextField) form.lookup("#providerWizardSearch")).setText("搜索条件");
            assertEquals(0, edits.get());
            ProviderSetupWizardFxTest.expandManual(form);
            TextField manual = (TextField) form.lookup("#providerWizardManualModel");
            manual.setText("not-yet-added");
            assertEquals(1, edits.get());
            var failure =
                    org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, form::selectedModels);
            assertTrue(failure.getMessage().contains("添加"));
            manual.clear();
            assertEquals(ProviderSetupWorkflowTest.MODELS, form.selectedModels());
        });
    }

    private static TextField dimensions(Parent root) {
        return (TextField) root.lookup("#providerWizardModelDimensions");
    }

    private static Parent attach(ProviderSettingsPage page) {
        var root = new BorderPane(page.content());
        new Scene(root, 1040, 720);
        page.activate();
        root.applyCss();
        root.layout();
        return root;
    }

    private static Button button(Parent root, String text) {
        return root.lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(value -> text.equals(value.getText()))
                .findFirst()
                .orElseThrow();
    }

    private static final class MetadataGateway extends ProviderConfigurationTestGateway {
        private final CredentialRef reference = new CredentialRef("provider", "already-bound");
        private CompletableFuture<Optional<CredentialMetadata>> metadata = new CompletableFuture<>();

        private MetadataGateway() {
            ProviderEndpoint old = providers.getFirst();
            providers.set(
                    0,
                    new ProviderEndpoint(
                            old.id(),
                            old.revision(),
                            old.lifecycle(),
                            ProviderDraft.from(old)
                                    .withCredential(Optional.of(reference))
                                    .toSpec(),
                            old.createdAt(),
                            old.updatedAt()));
        }

        @Override
        public CompletionStage<Optional<CredentialMetadata>> credential(CredentialRef ignored) {
            return metadata;
        }
    }
}
