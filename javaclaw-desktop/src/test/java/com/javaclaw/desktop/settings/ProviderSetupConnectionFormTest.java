package com.javaclaw.desktop.settings;

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderSetupConnectionFormTest {
    @Test
    void 百炼必须明确地域且业务空间地址原样保留() {
        FxTestSupport.run(() -> {
            var form = form();
            choose(form, "providerWizardPreset", ProviderPreset.BAILIAN);
            text(form, "providerWizardSecret").setText("fixture-key");
            assertFalse(form.validate(false));
            assertEquals("", form.draft().baseUri());
            choose(form, "providerWizardRegion", ProviderPreset.BailianRegion.SINGAPORE);
            assertEquals(
                    ProviderPreset.BailianRegion.SINGAPORE.baseUri(),
                    form.draft().baseUri());
            assertFalse(form.hasSecretInput(), "地域变化不能复用旧地域的临时密钥");
            choose(form, "providerWizardRegion", ProviderPreset.BailianRegion.CONSOLE);
            String workspace = "https://workspace.example.test/a-specific-workspace/compatible-mode/v1";
            text(form, "providerWizardAddress").setText(workspace);
            text(form, "providerWizardSecret").setText("workspace-key");
            assertTrue(form.validate(false));
            assertEquals(workspace, form.draft().baseUri());
        });
    }

    @Test
    void 预设只填连接字段且目的地切换清空临时密钥() {
        FxTestSupport.run(() -> {
            var form = form();
            AtomicInteger invalidated = new AtomicInteger();
            form.onDestinationChanged(invalidated::incrementAndGet);
            choose(form, "providerWizardPreset", ProviderPreset.DEEPSEEK);
            text(form, "providerWizardSecret").setText("first-key");
            text(form, "providerWizardName").setText("我的服务");
            assertTrue(form.hasSecretInput(), "名称不改变请求目的地");
            int before = invalidated.get();
            choose(form, "providerWizardPreset", ProviderPreset.GEMINI);
            assertFalse(form.hasSecretInput());
            assertTrue(invalidated.get() > before);
            assertEquals(ProviderAdapter.GOOGLE_GENAI, form.draft().adapter());
            assertEquals("v1beta", form.draft().apiVersion());
            assertTrue(form.draft().models().isEmpty(), "预设不得推断模型或模型能力");
            assertTrue(form.draft().credential().isEmpty());
            assertFalse(form.validate(false));
            assertTrue(form.validate(true), "工作流已保留密钥时不要求重复输入");
        });
    }

    @Test
    void 已有密钥经明确替换才展开且重新加载清除所有临时输入() {
        FxTestSupport.run(() -> {
            var form = form();
            var draft = ProviderSetupWorkflowTest.draft(ProviderAuthentication.API_KEY)
                    .withCredential(Optional.of(new CredentialRef("provider", "existing")));
            form.seed(draft);
            TextField input = text(form, "providerWizardSecret");
            assertFalse(input.isVisible());
            assertEquals("已配置", ((Label) form.lookup("#providerWizardSecretStatus")).getText());
            assertTrue(form.validate(false));
            form.focusSecret();
            assertTrue(input.isVisible());
            assertFalse(form.validate(false));
            input.setText("replacement");
            char[] captured = form.takeSecret();
            try {
                assertEquals("replacement", new String(captured));
                assertEquals("", input.getText());
            } finally {
                Arrays.fill(captured, '\0');
            }
            input.setText("discarded-on-seed");
            form.seed(draft);
            assertEquals("", input.getText());
            assertFalse(form.replacementRequested());
            assertFalse(form.clearingConfirmed());
        });
    }

    @Test
    void 协议可见且高级字段只显示相关协议选项() {
        FxTestSupport.run(() -> {
            var form = form();
            ((TitledPane) form.lookup("#providerWizardAdvanced")).setExpanded(true);
            choose(form, "providerWizardPreset", ProviderPreset.GEMINI);
            form.applyCss();
            form.layout();
            assertTrue(effectivelyVisible(form.lookup("#providerWizardAdapter")));
            assertTrue(effectivelyVisible(form.lookup("#providerWizardApiVersion")));
            assertFalse(effectivelyVisible(form.lookup("#providerWizardOrganization")));
            choose(form, "providerWizardPreset", ProviderPreset.ANTHROPIC);
            assertFalse(effectivelyVisible(form.lookup("#providerWizardApiVersion")));
            assertFalse(effectivelyVisible(form.lookup("#providerWizardReasoning")));
            choose(form, "providerWizardPreset", ProviderPreset.OPENAI);
            choose(form, "providerWizardAdapter", ProviderAdapter.OPENAI_RESPONSES);
            assertTrue(effectivelyVisible(form.lookup("#providerWizardOrganization")));
            assertTrue(effectivelyVisible(form.lookup("#providerWizardReasoning")));
        });
    }

    @Test
    void 错误定位不泄露地址中的凭据且无鉴权无需密钥() {
        FxTestSupport.run(() -> {
            var form = form();
            choose(form, "providerWizardPreset", ProviderPreset.OLLAMA);
            assertTrue(form.validate(false));
            assertFalse(text(form, "providerWizardSecret").isVisible());
            text(form, "providerWizardAddress").setText("https://alice:private-value@example.test/v1");
            assertFalse(form.validate(false));
            ((TitledPane) form.lookup("#providerWizardEndpointPreview")).setExpanded(true);
            form.applyCss();
            String preview = ((Label) form.lookup("#providerWizardEndpointRoutes")).getText();
            assertFalse(preview.contains("private-value"));
            text(form, "providerWizardAddress").setText("http://localhost:11434/v1");
            assertTrue(form.validate(false));
            ((TitledPane) form.lookup("#providerWizardAdvanced")).setExpanded(true);
            form.applyCss();
            text(form, "providerWizardTimeout").setText("not-a-number");
            assertFalse(form.validate(false));
        });
    }

    @Test
    void 改成无鉴权不隐式同意清除已有密钥() {
        FxTestSupport.run(() -> {
            var form = form();
            form.seed(ProviderSetupWorkflowTest.draft(ProviderAuthentication.API_KEY)
                    .withCredential(Optional.of(new CredentialRef("provider", "existing"))));
            choose(form, "providerWizardAuthentication", ProviderAuthentication.NONE);
            assertTrue(form.lookup("#providerWizardClearSecret").isVisible());
            assertFalse(((CheckBox) form.lookup("#providerWizardClearSecret")).isSelected());
            assertFalse(form.hasSecretInput());
            assertFalse(form.validate(false));
            ((CheckBox) form.lookup("#providerWizardClearSecret")).setSelected(true);
            assertTrue(form.validate(false));
        });
    }

    private static ProviderSetupConnectionForm form() {
        var form = new ProviderSetupConnectionForm();
        new Scene(form, 700, 620);
        return form;
    }

    private static TextField text(ProviderSetupConnectionForm form, String id) {
        return (TextField) form.lookup("#" + id);
    }

    @SuppressWarnings("unchecked")
    private static <T> void choose(ProviderSetupConnectionForm form, String id, T value) {
        ((ComboBox<T>) form.lookup("#" + id)).setValue(value);
    }

    private static boolean effectivelyVisible(Node node) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (!current.isVisible()) {
                return false;
            }
        }
        return node != null;
    }
}
