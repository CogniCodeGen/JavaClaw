package com.javaclaw.desktop.settings;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PrivateNetworkPurpose;
import com.javaclaw.api.ProfileLifecycle;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.SecurityGrantState;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.UnattendedToolGrantDraft;
import com.javaclaw.api.VaultLockReason;
import com.javaclaw.api.VaultManagementAction;
import com.javaclaw.api.VaultManagementReceipt;
import com.javaclaw.api.VaultState;
import com.javaclaw.api.VaultStatus;
import com.javaclaw.api.Workspace;
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
            fieldByPrompt(root, "例如 openai-primary").setText("provider-secondary");
            fieldByPrompt(root, "用户可见名称").setText("Secondary");
            combo(root, ProviderAdapter.class).setValue(ProviderAdapter.OPENAI_COMPATIBLE);
            fieldByPrompt(root, "官方默认地址可留空；自定义地址必须是 HTTP(S)").setText("https://secondary.example.test/v1");
            textAreaByPrompt(root, "每行一个 Provider 原生 model ID").setText("chat-model\nembed-model");
            combo(root, ProviderLifecycle.class).setValue(ProviderLifecycle.ACTIVE);
            button(root, "保存 Provider").fire();

            assertEquals(2, gateway.providers.size());
            assertFalse(page.dirty());
            button(root, "配置").fire();
            PasswordField secret = password(root, "providerSecretInput");
            secret.setText("temporary-secret");
            button(root, "写入 Secret").fire();
            assertEquals(1, gateway.providerCredentialSetCalls);
            assertTrue(labels(root).contains("已安全配置"));
            button(root, "本地检查").fire();
            assertTrue(labels(root).stream().anyMatch(value -> value.contains("READY")));

            fieldByPrompt(root, "60").setText("not-a-number");
            button(root, "保存 Provider").fire();
            assertTrue(page.dirty());
            button(root, "放弃更改").fire();
            button(root, "清除").fire();
            assertEquals(1, gateway.providerCredentialClearCalls);
            button(root, "归档当前 Provider").fire();
            assertEquals(ProviderLifecycle.ARCHIVED, gateway.providers.getLast().lifecycle());
        });
    }

    @Test
    void profile页保存精确Provider权限与预算并拒绝非法数字() {
        FxTestSupport.run(() -> {
            TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
            AgentProfileSettingsPage page = new AgentProfileSettingsPage(
                    gateway,
                    emptyGateway(PromptPreviewSettingsGateway.class),
                    emptyGateway(PromptOptimizationSettingsGateway.class));
            Parent root = attach(page);

            button(root, "新建").fire();
            fieldByPrompt(root, "例如 coding-default").setText("coding-default");
            fieldByPrompt(root, "用户可见名称").setText("Coding Default");
            textAreaByPrompt(root, "只描述角色与工作偏好，不授予工具权限").setText("保持实现清晰。 ");
            combo(root, ProviderEndpoint.class).setValue(gateway.providers.getFirst());
            combo(root, String.class).setValue("fake-model");
            combo(root, PermissionProfile.class).setValue(gateway.permissions.getFirst());
            textAreaByPrompt(root, "每行一个完整工具名；空集合表示不向模型公开工具").setText("core/tool/search");
            List<TextField> budget = textFieldsWithoutPrompt(root);
            setTexts(budget, "8000", "2000", "6", "1", "120");
            combo(root, ProfileLifecycle.class).setValue(ProfileLifecycle.ACTIVE);
            button(root, "保存 Profile").fire();

            assertEquals(1, gateway.profiles.size());
            AgentProfile saved = gateway.profiles.getFirst();
            assertEquals(
                    gateway.providers.getFirst().revision(),
                    saved.spec().provider().endpointRevision());
            assertEquals(
                    gateway.permissions.getFirst().version(),
                    saved.spec().permissionProfile().version());

            budget.get(3).setText(Long.toString((long) Integer.MAX_VALUE + 1));
            button(root, "保存 Profile").fire();
            assertTrue(page.dirty());
            assertTrue(labels(root).stream().anyMatch(value -> value.contains("childThreads")));
            button(root, "放弃更改").fire();
            button(root, "归档当前 Profile").fire();
            assertEquals(ProfileLifecycle.ARCHIVED, gateway.profiles.getFirst().lifecycle());
        });
    }

    @Test
    void permission页clone新版本并呈现五层有效权限() {
        FxTestSupport.run(() -> {
            TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
            PermissionProfileSettingsPage page = new PermissionProfileSettingsPage(gateway);
            Parent root = attach(page);

            assertTrue(fieldByPrompt(root, "clone 后输入新的稳定 ID").isDisabled());
            button(root, "Clone 为新配置").fire();
            fieldByPrompt(root, "clone 后输入新的稳定 ID").setText("workspace-safe");
            checkBox(root, "允许删除").setSelected(true);
            checkBox(root, "允许跟随符号链接").setSelected(true);
            checkBox(root, "允许 PTY").setSelected(true);
            combo(root, ToolRisk.class).setValue(ToolRisk.NETWORK);
            combo(root, ApprovalRequirement.class).setValue(ApprovalRequirement.EVERY_CALL);
            button(root, "保存新版本").fire();

            assertFalse(page.dirty());
            assertTrue(gateway.permissions.stream().anyMatch(value -> value.id().equals("workspace-safe")));
            combo(root, ToolRisk.class).setValue(ToolRisk.PROCESS);
            button(root, "保存新版本").fire();
            assertTrue(labels(root).stream().anyMatch(value -> value.contains("v1 → v2")));

            List<CheckBox> optionalLayers = checkBoxes(root, "应用");
            optionalLayers.getFirst().setSelected(true);
            optionalLayers.getLast().setSelected(true);
            button(root, "计算有效权限").fire();
            assertTrue(labels(root).stream().anyMatch(value -> value.contains("SYSTEM_CEILING")));
            assertTrue(labels(root).stream().anyMatch(value -> value.contains("TOOL_DECLARATION")));
        });
    }

    @Test
    void permission页拒绝非法草稿并可移除预览中的可选层() {
        FxTestSupport.run(() -> {
            PermissionProfileSettingsPage page = new PermissionProfileSettingsPage(new TestCoreSettingsGateway());
            Parent root = attach(page);

            button(root, "Clone 为新配置").fire();
            TextField id = fieldByPrompt(root, "clone 后输入新的稳定 ID");
            id.setText("bad id");
            button(root, "保存新版本").fire();
            assertTrue(labels(root).stream().anyMatch(value -> value.contains("PermissionProfile ID")));

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
        });
    }

    @Test
    void workspace页分别保存名称和默认Profile且保留离页草稿() {
        FxTestSupport.run(() -> {
            TestCoreSettingsGateway gateway = new TestCoreSettingsGateway();
            AgentProfile profile = gateway.createProfile(
                            "workspace-profile", TestCoreSettingsGateway.profileSpec(), CommandOptions.create(0))
                    .toCompletableFuture()
                    .join();
            WorkspaceSettingsPage page = new WorkspaceSettingsPage(gateway);
            Parent root = attach(page);

            TextField name = fieldByAccessibleText(root, "Workspace 名称");
            name.setText("新工作区");
            assertTrue(page.dirty());
            page.warnUnsavedChanges();
            assertTrue(labels(root).stream().anyMatch(value -> value.contains("请先保存或丢弃")));
            button(root, "保存名称").fire();
            combo(root, AgentProfile.class).setValue(profile);
            button(root, "保存默认 Profile").fire();

            assertEquals("新工作区", gateway.lastWorkspaceName);
            assertEquals(profile.revision(), gateway.lastWorkspaceProfile.revision());
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
            Parent networkRoot = attach(networkPage);
            assertTrue(labels(networkRoot).contains(SecurityGrantState.ACTIVE.name()));
            fieldByAccessibleText(networkRoot, "精确 HTTPS Origin").setText("https://site.example.test");
            textAreaByAccessibleText(networkRoot, "DNS 地址集合").setText("10.0.0.9\nfd00::9");
            button(networkRoot, "生成确认预览").fire();
            assertTrue(labels(networkRoot).contains("https://site.example.test"));
            networkPage.warnUnsavedChanges();
            networkPage.discardDraft();
            gateway.revokePrivateNetworkGrant(networkGrant, CommandOptions.create(networkGrant.revision()))
                    .toCompletableFuture()
                    .join();
            networkPage.activate();
            assertTrue(labels(networkRoot).contains(SecurityGrantState.REVOKED.name()));

            UnattendedToolGrantSettingsPage unattendedPage = new UnattendedToolGrantSettingsPage(gateway);
            Parent unattendedRoot = attach(unattendedPage);
            assertTrue(labels(unattendedRoot).contains(SecurityGrantState.ACTIVE.name()));
            fieldByAccessibleText(unattendedRoot, "Schedule 定义 ID").setText("nightly-review-2");
            assertTrue(unattendedPage.dirty());
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

            assertTrue(labels(root).contains("VAULT_LOCKED"));
            Button reset = button(root, "永久 Reset Vault");
            assertTrue(reset.isDisabled());
            fieldByAccessibleText(root, "Vault reset 危险确认").setText("RESET VAULT");
            assertFalse(reset.isDisabled());
            reset.fire();

            assertTrue(labels(root).contains("READY"));
            assertTrue(labels(root).stream().anyMatch(value -> value.contains("VAULT_RESET")));
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
        Parent root = (Parent) page.content();
        new Scene(root, 1_040, 720);
        page.activate();
        root.applyCss();
        return root;
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
