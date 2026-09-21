package com.javaclaw.desktop.settings;

import java.time.Instant;
import java.util.Optional;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderSettingsSecretInputTest {
    @Test
    void 管理列表不再常驻密钥输入且完整配置入口统一打开() {
        FxTestSupport.run(() -> {
            var page = new ProviderSettingsPage(new ProviderConfigurationTestGateway());
            Parent root = new VBox(page.content());
            new Scene(root, 1040, 720);
            try {
                page.activate();
                root.applyCss();
                root.layout();
                assertNull(root.lookup("#providerSecretInput"));
                assertTrue(root.lookup("#providerConfigureButton") != null);
                assertTrue(root.lookup("#providerServicesList") != null);
                assertTrue(root.lookup("#providerModelsTable") != null);
            } finally {
                page.dispose();
            }
        });
    }

    @Test
    void 已有密钥不回显且输入转移后立即清空PasswordField() {
        FxTestSupport.run(() -> {
            var form = new ProviderSetupConnectionForm();
            var spec = TestCoreSettingsFixtures.providerSpec(Optional.of(new CredentialRef("provider", "existing")));
            form.seed(new ProviderDraft(
                    "id",
                    "已配置",
                    spec.adapter(),
                    spec.baseUri().orElseThrow().toString(),
                    spec.authentication(),
                    spec.models(),
                    spec.credential(),
                    60,
                    0,
                    "",
                    "",
                    "",
                    com.javaclaw.api.ProviderReasoningSummary.AUTO,
                    ProviderLifecycle.DISABLED));
            new Scene(form, 600, 600);
            PasswordField input = (PasswordField) form.lookup("#providerWizardSecret");
            assertEquals("", input.getText());
            assertFalse(((CheckBox) form.lookup("#providerWizardReplaceSecret")).isSelected());
            input.setText("temporary-key");
            char[] captured = form.takeSecret();
            assertEquals("", input.getText());
            assertArrayEquals("temporary-key".toCharArray(), captured);
            java.util.Arrays.fill(captured, '\0');
        });
    }

    @Test
    void 编辑保留全部协议选项且无鉴权清除默认未确认() {
        FxTestSupport.run(() -> {
            var form = new ProviderSetupConnectionForm();
            var base = ProviderSetupWorkflowTest.draft(com.javaclaw.api.ProviderAuthentication.API_KEY);
            var draft = new ProviderDraft(
                    "id",
                    "编辑服务",
                    base.adapter(),
                    base.baseUri(),
                    base.authentication(),
                    base.models(),
                    Optional.of(new CredentialRef("provider", "existing")),
                    91,
                    2,
                    "org-preserved",
                    "project-preserved",
                    "",
                    base.reasoningSummary(),
                    ProviderLifecycle.DISABLED);
            var endpoint = new ProviderEndpoint(
                    "id", 3, ProviderLifecycle.DISABLED, draft.toSpec(), Instant.EPOCH, Instant.EPOCH);
            form.seed(ProviderDraft.from(endpoint));
            assertEquals("org-preserved", form.draft().organization());
            assertEquals("project-preserved", form.draft().project());
            assertEquals(91, form.draft().timeoutSeconds());
            assertEquals(2, form.draft().maximumRetries());
            assertFalse(form.clearingConfirmed());
        });
    }
}
