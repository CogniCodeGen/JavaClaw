package com.javaclaw.desktop.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderConfigurationRecoveryTest {
    @Test
    void 预览后取消替换密钥即撤销已转移秘密且保存保留原凭据和模型() {
        FxTestSupport.run(() -> {
            var gateway = new ProviderConfigurationTestGateway();
            ProviderEndpoint source = configured(gateway);
            try (Fixture fixture = fixture(gateway, Optional.of(source))) {
                fixture.expand("providerConnectionSection");
                CheckBox replace = (CheckBox) fixture.root().lookup("#providerWizardReplaceSecret");
                replace.setSelected(true);
                fixture.text("providerWizardSecret").setText("cancelled-replacement-key");
                fixture.button("providerWizardDiscoverModels").fire();
                assertEquals(
                        ProviderCredentialChange.REPLACE,
                        gateway.previews.getFirst().credentialChange());
                assertEquals("", fixture.text("providerWizardSecret").getText());

                replace.setSelected(false);
                fixture.button("providerConfigurationSave").fire();

                assertEquals(1, gateway.saved.size());
                assertEquals(ProviderCredentialChange.KEEP, gateway.configuration.credentialChange());
                assertEquals(0, gateway.submittedSecret.length, "取消替换后不得提交已预览的新密钥");
                assertEquals(0, gateway.vaultStatusCalls);
                assertEquals(source.spec().models(), gateway.configuration.models());
                assertEquals(
                        source.spec().credential(),
                        gateway.committed.provider().spec().credential());
                assertEquals(1, fixture.completed().size());
            }
        });
    }

    @Test
    void 预览后只修改名称仍保留明确选择的替换密钥() {
        FxTestSupport.run(() -> {
            var gateway = new ProviderConfigurationTestGateway();
            ProviderEndpoint source = configured(gateway);
            try (Fixture fixture = fixture(gateway, Optional.of(source))) {
                fixture.expand("providerConnectionSection");
                ((CheckBox) fixture.root().lookup("#providerWizardReplaceSecret")).setSelected(true);
                fixture.text("providerWizardSecret").setText("retained-replacement-key");
                fixture.button("providerWizardDiscoverModels").fire();

                fixture.text("providerWizardName").setText("预览后修改的名称");
                fixture.button("providerConfigurationSave").fire();

                assertEquals(1, gateway.saved.size());
                assertEquals(ProviderCredentialChange.REPLACE, gateway.configuration.credentialChange());
                assertTrue(gateway.submittedSecret.length > 0);
                assertEquals("预览后修改的名称", gateway.committed.provider().spec().displayName());
                assertEquals(source.spec().models(), gateway.configuration.models());
                assertEquals(1, fixture.completed().size());
            }
        });
    }

    @Test
    void 未提交草稿重连保留名称地址模型且原临时密钥清除后必须重输() {
        FxTestSupport.run(() -> {
            var gateway = new ProviderConfigurationTestGateway();
            try (Fixture fixture = fixture(gateway, Optional.empty())) {
                fixture.text("providerWizardName").setText("重连保留草稿");
                fixture.text("providerWizardAddress").setText("http://localhost:11434/v1");
                fixture.text("providerWizardSecret").setText("invalidated-temporary-key");
                fixture.button("providerWizardDiscoverModels").fire();
                fixture.expand("providerWizardManualSection");
                fixture.text("providerWizardManualModel").setText("retained-after-reconnect");
                fixture.button("providerWizardAddManual").fire();

                gateway.invalidated.run();
                fixture.button("providerConfigurationReconnect").fire();

                assertEquals("重连保留草稿", fixture.text("providerWizardName").getText());
                assertEquals(
                        "http://localhost:11434/v1",
                        fixture.text("providerWizardAddress").getText());
                assertEquals("", fixture.text("providerWizardSecret").getText());
                assertTrue(((ListView<?>) fixture.root().lookup("#providerWizardModelsList"))
                        .getItems()
                        .contains("retained-after-reconnect"));
                assertFalse(fixture.button("providerConfigurationSave").isDisabled());
                fixture.button("providerConfigurationSave").fire();
                assertTrue(gateway.saved.isEmpty(), "重连不得恢复旧临时密钥或绕过重新输入");
                assertEquals(0, gateway.preparations);
                fixture.text("providerWizardSecret").setText("new-session-key");
                fixture.button("providerConfigurationSave").fire();
                assertEquals(1, gateway.saved.size());
                assertEquals(
                        "retained-after-reconnect",
                        gateway.configuration.models().getFirst().modelId());
                assertEquals(1, fixture.completed().size());
            }
        });
    }

    private static ProviderEndpoint configured(ProviderConfigurationTestGateway gateway) {
        ProviderEndpoint endpoint = TestCoreSettingsFixtures.provider(
                3,
                TestCoreSettingsFixtures.providerSpec(Optional.of(new CredentialRef("provider", "existing"))),
                ProviderLifecycle.ACTIVE);
        gateway.providers.clear();
        gateway.providers.add(endpoint);
        return endpoint;
    }

    private static Fixture fixture(ProviderConfigurationTestGateway gateway, Optional<ProviderEndpoint> source) {
        List<ProviderConfigurationResult> completed = new ArrayList<>();
        var editor = new ProviderConfigurationEditor(
                gateway, source, source.isPresent() ? 1 : 0, completed::add, () -> {}, () -> {});
        BorderPane root = new BorderPane(editor);
        root.setBottom(editor.actionContent());
        new Scene(root, 1040, 900);
        root.applyCss();
        root.layout();
        return new Fixture(editor, root, completed);
    }

    /** 内存编辑器夹具；根节点与结果列表均非空，关闭只释放本次编辑会话。 */
    private record Fixture(ProviderConfigurationEditor editor, Parent root, List<ProviderConfigurationResult> completed)
            implements AutoCloseable {
        private TextField text(String id) {
            return (TextField) root.lookup("#" + id);
        }

        private Button button(String id) {
            return (Button) root.lookup("#" + id);
        }

        private void expand(String id) {
            ((TitledPane) root.lookup("#" + id)).setExpanded(true);
            root.applyCss();
            root.layout();
        }

        @Override
        public void close() {
            editor.close();
        }
    }
}
