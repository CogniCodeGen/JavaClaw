package com.javaclaw.desktop.settings;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CoreTools;
import com.javaclaw.api.PrivateNetworkPurpose;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.UnattendedToolGrantDraft;
import com.javaclaw.api.VaultLockReason;
import com.javaclaw.api.VaultManagementAction;
import com.javaclaw.api.VaultManagementReceipt;
import com.javaclaw.api.VaultState;
import com.javaclaw.api.VaultStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.builtin.contracts.ScheduleContracts;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.DesktopTestFixtures;
import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreSettingsPagesTest {
    @Test
    void provider页完成配置Secret探测与归档闭环() {
        FxTestSupport.run(() -> {
            TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
            ProviderSettingsPage page = new ProviderSettingsPage(gateway);
            Parent root = attach(page);

            button(root, "新建").fire();
            assertTrue(labels(root).stream().anyMatch(value -> value.startsWith("provider-")));
            assertTrue(button(root, "手工添加模型").isDisabled());
            fieldByPrompt(root, "用户可见名称").setText("Secondary");
            combo(root, ProviderAdapter.class).setValue(ProviderAdapter.OPENAI_COMPATIBLE);
            fieldByPrompt(root, "官方默认地址可留空；自定义地址必须是 HTTP(S)").setText("https://secondary.example.test/v1");
            button(root, "保存模型服务").fire();

            assertEquals(2, gateway.providers.size());
            assertFalse(page.dirty());
            assertTrue(button(root, "手工添加模型").isDisabled());
            button(root, "配置").fire();
            PasswordField secret = password(root, "providerSecretInput");
            secret.setText("temporary-secret");
            button(root, "写入密钥").fire();
            assertEquals(1, gateway.providerCredentialSetCalls);
            assertTrue(labels(root).contains("已安全配置"));
            assertFalse(button(root, "手工添加模型").isDisabled());
            completeModelDialog("chat-model", "Chat Model");
            button(root, "手工添加模型").fire();
            combo(root, ProviderLifecycle.class).setValue(ProviderLifecycle.ACTIVE);
            button(root, "保存模型服务").fire();
            button(root, "本地检查").fire();
            assertTrue(labels(root).stream().anyMatch(value -> value.contains("可以使用")));

            fieldByPrompt(root, "60").setText("not-a-number");
            button(root, "保存模型服务").fire();
            assertTrue(page.dirty());
            button(root, "放弃更改").fire();
            button(root, "清除").fire();
            assertEquals(1, gateway.providerCredentialClearCalls);
            confirmNextDangerDialog("归档当前模型服务");
            button(root, "归档当前模型服务").fire();
            assertEquals(ProviderLifecycle.ARCHIVED, gateway.providers.getLast().lifecycle());
        });
    }

    @Test
    void permission页clone新版本并呈现五层有效权限() {
        FxTestSupport.run(() -> {
            TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
            PermissionProfileSettingsPage page = new PermissionProfileSettingsPage(gateway);
            page.workspaceChanged(Optional.of(DesktopTestFixtures.workspace()));
            Parent root = attach(page);

            assertTrue(root.lookup("#toolCatalogList") instanceof ListView<?>);
            assertFalse(
                    nodes(root, TextArea.class).stream().anyMatch(value -> "允许工具".equals(value.getAccessibleText())));
            assertTrue(fieldByPrompt(root, "复制后输入新的稳定标识").isDisabled());
            button(root, "复制为新方案").fire();
            fieldByPrompt(root, "复制后输入新的稳定标识").setText("workspace-safe");
            checkBox(root, "允许删除").setSelected(true);
            checkBox(root, "允许跟随符号链接").setSelected(true);
            checkBox(root, "允许交互终端（PTY）").setSelected(true);
            combo(root, ToolRisk.class).setValue(ToolRisk.NETWORK);
            combo(root, ApprovalRequirement.class).setValue(ApprovalRequirement.EVERY_CALL);
            button(root, "保存新版本").fire();

            assertFalse(page.dirty());
            assertTrue(gateway.permissions.stream().anyMatch(value -> value.id().equals("workspace-safe")));
            combo(root, ToolRisk.class).setValue(ToolRisk.PROCESS);
            button(root, "保存新版本").fire();
            assertTrue(labels(root).stream().anyMatch(value -> value.contains("版本 1 → 版本 2")));

            List<CheckBox> optionalLayers = checkBoxes(root, "应用");
            optionalLayers.getFirst().setSelected(true);
            optionalLayers.getLast().setSelected(true);
            button(root, "计算有效权限").fire();
            assertTrue(labels(root).stream().anyMatch(value -> value.contains("平台安全上限")));
            assertTrue(labels(root).stream().anyMatch(value -> value.contains("工具自身限制")));
        });
    }

    @Test
    void permission页拒绝非法草稿并可移除预览中的可选层() {
        FxTestSupport.run(() -> {
            PermissionProfileSettingsPage page = new PermissionProfileSettingsPage(new TestCoreSettingsGateway());
            page.workspaceChanged(Optional.of(DesktopTestFixtures.workspace()));
            Parent root = attach(page);

            button(root, "复制为新方案").fire();
            TextField id = fieldByPrompt(root, "复制后输入新的稳定标识");
            id.setText("bad id");
            button(root, "保存新版本").fire();
            assertTrue(labels(root).stream().anyMatch(value -> value.contains("权限方案标识只能包含")));

            id.setText("narrow-preview");
            button(root, "保存新版本").fire();
            assertFalse(page.dirty());
            TextField processSeconds = fieldByPrompt(root, "30");
            processSeconds.setText("not-a-number");
            button(root, "保存新版本").fire();
            assertTrue(page.dirty());
            processSeconds.setText("30");
            button(root, "保存新版本").fire();
            assertFalse(page.dirty());

            List<CheckBox> optionalLayers = checkBoxes(root, "应用");
            optionalLayers.getFirst().setSelected(true);
            optionalLayers.getFirst().setSelected(false);
            optionalLayers.getLast().setSelected(true);
            optionalLayers.getLast().setSelected(false);
            button(root, "计算有效权限").fire();
            assertTrue(labels(root).stream().anyMatch(value -> value.contains("未提供")));

            combo(root, ToolRisk.class).setValue(ToolRisk.EXTERNAL_EFFECT);
            assertTrue(page.dirty());
            page.workspaceChanged(Optional.empty());
            assertTrue(page.dirty(), "Workspace 失效时必须保留权限草稿");
            assertTrue(button(root, "保存新版本").isDisabled());
            assertTrue(root.lookup("#toolCatalogList").isDisabled());
        });
    }

    @Test
    void workspace页分别保存名称和执行配置且名称保存保留执行草稿() {
        FxTestSupport.run(() -> {
            TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
            AgentRole profile = gateway.createRole(
                            "workspace-profile", TestCoreSettingsGateway.profileSpec(), CommandOptions.create(0))
                    .toCompletableFuture()
                    .join();
            WorkspaceSettingsPage page = new WorkspaceSettingsPage(gateway);
            page.workspaceChanged(Optional.of(DesktopTestFixtures.workspace()));
            Parent root = attach(page);

            TextField name = fieldByAccessibleText(root, "工作区名称");
            name.setText("新工作区");
            assertTrue(page.dirty());
            page.warnUnsavedChanges();
            assertTrue(labels(root).stream().anyMatch(value -> value.contains("请先保存或丢弃")));
            combo(root, AgentRole.class).setValue(profile);
            button(root, "保存名称").fire();
            assertTrue(page.dirty(), "名称的 revision 更新不能丢弃独立执行草稿");
            button(root, "保存执行默认配置").fire();

            assertEquals("新工作区", gateway.workspaceSettings.lastName);
            assertEquals(
                    profile.revision(),
                    gateway.workspaceSettings.lastExecution.role().orElseThrow().revision());
            name.setText("临时名称");
            page.discardDraft();
            assertFalse(page.dirty());
        });
    }

    @Test
    void 私网与无人值守页渲染权威授权并保护未确认草稿() {
        FxTestSupport.run(() -> {
            TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
            Workspace workspace = DesktopTestFixtures.workspace();
            var preview = gateway.previewPrivateNetworkGrant(
                            workspace.id(),
                            PrivateNetworkPurpose.MCP,
                            URI.create("https://mcp.example.test"),
                            Set.of("10.0.0.8"),
                            Optional.of(Duration.ofHours(1)))
                    .toCompletableFuture()
                    .join();
            var networkGrant = gateway.createPrivateNetworkGrant(preview, CommandOptions.create(0))
                    .toCompletableFuture()
                    .join();
            seedUnattended(gateway, workspace);

            PrivateNetworkGrantSettingsPage networkPage = new PrivateNetworkGrantSettingsPage(gateway);
            networkPage.workspaceChanged(Optional.of(workspace));
            Parent networkRoot = attach(networkPage);
            assertTrue(labels(networkRoot).contains("有效"));
            fieldByAccessibleText(networkRoot, "精确 HTTPS 来源地址").setText("https://site.example.test");
            textAreaByAccessibleText(networkRoot, "DNS 地址集合").setText("10.0.0.9\nfd00::9");
            button(networkRoot, "生成确认预览").fire();
            assertTrue(labels(networkRoot).contains("https://site.example.test"));
            networkPage.warnUnsavedChanges();
            networkPage.discardDraft();
            gateway.revokePrivateNetworkGrant(networkGrant, CommandOptions.create(networkGrant.revision()))
                    .toCompletableFuture()
                    .join();
            networkPage.activate();
            assertTrue(labels(networkRoot).contains("已撤销"));

            AgentRole profile = gateway.createRole(
                            "schedule-agent", TestCoreSettingsGateway.profileSpec(), CommandOptions.create(0))
                    .toCompletableFuture()
                    .join();
            ScheduleContracts.Definition schedule = TestScheduleDefinitions.turn(profile);
            UnattendedToolGrantSettingsPage unattendedPage = new UnattendedToolGrantSettingsPage(
                    gateway, ignored -> CompletableFuture.completedFuture(List.of(schedule)));
            unattendedPage.workspaceChanged(Optional.of(workspace));
            Parent unattendedRoot = attach(unattendedPage);
            assertTrue(unattendedRoot.lookup("#scheduleCatalogChoice") instanceof ComboBox<?>);
            assertTrue(unattendedRoot.lookup("#unattendedToolCatalogChoice") instanceof ComboBox<?>);
            assertTrue(unattendedRoot.lookup("#unattendedToolProducer") instanceof Label);
            assertTrue(unattendedRoot.lookup("#unattendedToolRevision") instanceof Label);
            assertTrue(unattendedRoot.lookup("#unattendedCatalogRevision") instanceof Label);
            assertTrue(unattendedRoot.lookup("#unattendedToolSchemaHash") instanceof Label);
            assertTrue(labels(unattendedRoot).contains("有效"));
            combo(unattendedRoot, ScheduleContracts.Definition.class).setValue(schedule);
            combo(unattendedRoot, com.javaclaw.api.ToolDescriptor.class).setValue(CoreTools.search());
            assertTrue(unattendedPage.dirty());
            unattendedPage.workspaceChanged(Optional.empty());
            assertTrue(unattendedPage.dirty(), "Workspace 失效时必须保留无人值守授权草稿");
            assertTrue(button(unattendedRoot, "审核并创建").isDisabled());
            assertTrue(combo(unattendedRoot, ScheduleContracts.Definition.class).isDisabled());
            unattendedPage.warnUnsavedChanges();
            assertTrue(labels(unattendedRoot).stream().anyMatch(value -> value.contains("请先创建或丢弃")));
            unattendedPage.discardDraft();
            assertFalse(unattendedPage.dirty());
        });
    }

    @Test
    void vault锁定时仍允许精确确认的永久Reset自救() {
        FxTestSupport.run(() -> {
            VaultSettingsPage page = new VaultSettingsPage(lockedVaultGateway());
            Parent root = attach(page);

            assertTrue(labels(root).contains("已锁定"));
            Button reset = button(root, "永久重置密钥库");
            assertTrue(reset.isDisabled());
            TextField expected = fieldByAccessibleText(root, "密钥库重置确认语句，可选择并复制");
            assertFalse(expected.isEditable());
            expected.selectAll();
            assertEquals("RESET VAULT", expected.getSelectedText());
            fieldByAccessibleText(root, "密钥库重置危险确认").setText("RESET VAULT");
            assertFalse(reset.isDisabled());
            reset.fire();

            assertTrue(labels(root).contains("可以使用"));
            assertTrue(labels(root).stream().anyMatch(value -> value.contains("已重置密钥库")));
        });
    }

    @Test
    void site永久清除确认语句可选择复制且仍要求精确匹配() {
        FxTestSupport.run(() -> {
            TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
            gateway.createCredential(
                            SiteContracts.SITE_CREDENTIAL_NAMESPACE,
                            "temporary-site-secret".toCharArray(),
                            CommandOptions.create(0))
                    .toCompletableFuture()
                    .join();
            SiteCredentialSettingsSection section = new SiteCredentialSettingsSection(gateway, () -> {});
            BorderPane root = new BorderPane(section.content());
            new Scene(root, 1_040, 720);
            section.activate();
            root.applyCss();

            TextField expected = fieldByAccessibleText(root, "网站凭据永久清除确认语句，可选择并复制");
            assertFalse(expected.isEditable());
            expected.selectAll();
            assertEquals("CLEAR SITE SECRET", expected.getSelectedText());
            Button clear = button(root, "永久清除");
            assertTrue(clear.isDisabled());
            TextField confirmation = fieldByAccessibleText(root, "网站密钥永久清除确认");
            confirmation.setText("CLEAR SITE SECRET ");
            assertTrue(clear.isDisabled());
            confirmation.setText("CLEAR SITE SECRET");
            assertFalse(clear.isDisabled());
        });
    }

    private static void seedUnattended(TestCoreSettingsGateway gateway, Workspace workspace) {
        UnattendedToolGrantDraft draft = new UnattendedToolGrantDraft(
                workspace.id(),
                "nightly-review",
                3,
                new ToolIdentity("builtin.skill", "review", 4),
                11,
                "a".repeat(64),
                new CanonicalPayload("{\"limit\":3,\"query\":\"stable\"}"),
                Set.of("query"),
                10,
                Duration.ofDays(7));
        gateway.createUnattendedToolGrant(draft, CommandOptions.create(0))
                .toCompletableFuture()
                .join();
    }

    private static CoreSettingsGateway lockedVaultGateway() {
        boolean[] reset = {false};
        Instant now = Instant.parse("2026-09-02T00:00:00Z");
        return (CoreSettingsGateway) Proxy.newProxyInstance(
                CoreSettingsGateway.class.getClassLoader(),
                new Class<?>[] {CoreSettingsGateway.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "vaultStatus" ->
                        CompletableFuture.completedFuture(
                                reset[0]
                                        ? new VaultStatus(VaultState.READY, VaultLockReason.NONE, 0, false, now)
                                        : new VaultStatus(
                                                VaultState.LOCKED, VaultLockReason.MASTER_KEY_MISSING, 3, false, now));
                    case "resetVault" -> {
                        reset[0] = true;
                        yield CompletableFuture.completedFuture(
                                new VaultManagementReceipt(VaultManagementAction.VAULT_RESET, 3, now));
                    }
                    case "toString" -> "LockedVaultGateway";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> CompletableFuture.failedFuture(new UnsupportedOperationException(method.getName()));
                });
    }

    private static Parent attach(ManagedSettingsPage page) {
        BorderPane root = new BorderPane();
        root.setCenter(page.content());
        page.actionContent().ifPresent(root::setBottom);
        new Scene(root, 1_040, 720);
        page.activate();
        root.applyCss();
        return root;
    }

    private static void confirmNextDangerDialog(String actionLabel) {
        Platform.runLater(() -> Window.getWindows().stream()
                .map(Window::getScene)
                .filter(Objects::nonNull)
                .map(Scene::getRoot)
                .filter(DialogPane.class::isInstance)
                .map(DialogPane.class::cast)
                .flatMap(dialog -> dialog.lookupAll(".button").stream())
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> actionLabel.equals(button.getText()))
                .findFirst()
                .ifPresent(Button::fire));
    }

    private static void completeModelDialog(String modelId, String displayName) {
        Platform.runLater(() -> Window.getWindows().stream()
                .map(Window::getScene)
                .filter(Objects::nonNull)
                .map(Scene::getRoot)
                .filter(DialogPane.class::isInstance)
                .map(DialogPane.class::cast)
                .findFirst()
                .ifPresent(dialog -> {
                    setLabeledText(dialog, "真实模型 ID", modelId);
                    setLabeledText(dialog, "显示名称", displayName);
                    ((Button) dialog.lookupButton(javafx.scene.control.ButtonType.OK)).fire();
                }));
    }

    private static void setLabeledText(DialogPane dialog, String label, String value) {
        dialog.lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(Label.class::cast)
                .filter(candidate -> label.equals(candidate.getText()))
                .map(Label::getLabelFor)
                .filter(TextField.class::isInstance)
                .map(TextField.class::cast)
                .findFirst()
                .orElseThrow()
                .setText(value);
    }

    @SuppressWarnings("unchecked")
    private static <T> T emptyGateway(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, arguments) -> {
            return switch (method.getName()) {
                case "workspaces" -> CompletableFuture.completedFuture(List.of(DesktopTestFixtures.workspace()));
                case "list" -> CompletableFuture.completedFuture(List.of());
                case "toString" -> "Empty" + type.getSimpleName();
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> CompletableFuture.failedFuture(new UnsupportedOperationException(method.getName()));
            };
        });
    }

    private static void setTexts(List<TextField> fields, String... values) {
        assertEquals(values.length, fields.size());
        for (int index = 0; index < values.length; index++) {
            fields.get(index).setText(values[index]);
        }
    }

    private static Button button(Parent root, String text) {
        return nodes(root, Button.class).stream()
                .filter(value -> text.equals(value.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少按钮: " + text));
    }

    private static TextField fieldByPrompt(Parent root, String prompt) {
        return nodes(root, TextField.class).stream()
                .filter(value -> prompt.equals(value.getPromptText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少字段: " + prompt));
    }

    private static TextField fieldByAccessibleText(Parent root, String accessibleText) {
        return nodes(root, TextField.class).stream()
                .filter(value -> accessibleText.equals(value.getAccessibleText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少字段: " + accessibleText));
    }

    private static PasswordField password(Parent root, String id) {
        return nodes(root, PasswordField.class).stream()
                .filter(value -> id.equals(value.getId()))
                .findFirst()
                .orElseThrow();
    }

    private static TextArea textAreaByPrompt(Parent root, String prompt) {
        return nodes(root, TextArea.class).stream()
                .filter(value -> prompt.equals(value.getPromptText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少文本区: " + prompt));
    }

    private static TextArea textAreaByAccessibleText(Parent root, String accessibleText) {
        return nodes(root, TextArea.class).stream()
                .filter(value -> accessibleText.equals(value.getAccessibleText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少文本区: " + accessibleText));
    }

    private static CheckBox checkBox(Parent root, String text) {
        return checkBoxes(root, text).getFirst();
    }

    private static List<CheckBox> checkBoxes(Parent root, String text) {
        return nodes(root, CheckBox.class).stream()
                .filter(value -> text.equals(value.getText()))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static <T> ComboBox<T> combo(Parent root, Class<T> itemType) {
        return (ComboBox<T>) nodes(root, ComboBox.class).stream()
                .filter(value -> !value.getItems().isEmpty())
                .filter(value -> itemType.isInstance(value.getItems().getFirst()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少选择器: " + itemType.getSimpleName()));
    }

    private static List<TextField> textFieldsWithoutPrompt(Parent root) {
        return nodes(root, TextField.class).stream()
                .filter(value ->
                        value.getPromptText() == null || value.getPromptText().isBlank())
                .toList();
    }

    private static List<String> labels(Parent root) {
        return nodes(root, Label.class).stream().map(Label::getText).toList();
    }

    private static <T extends Node> List<T> nodes(Parent root, Class<T> type) {
        ArrayList<T> result = new ArrayList<>();
        Queue<Node> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Node current = pending.remove();
            if (type.isInstance(current)) {
                result.add(type.cast(current));
            }
            if (current instanceof Parent parent) {
                pending.addAll(parent.getChildrenUnmodifiable());
            }
        }
        return List.copyOf(result);
    }
}
