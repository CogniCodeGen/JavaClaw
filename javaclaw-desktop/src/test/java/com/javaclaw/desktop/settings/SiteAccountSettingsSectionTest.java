package com.javaclaw.desktop.settings;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.SiteAccountContracts;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.DesktopNotificationSubscription;
import com.javaclaw.desktop.DesktopTestFixtures;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.NativeUiEvidence;
import com.javaclaw.desktop.view.ViewAttachmentUploadRequest;
import com.javaclaw.desktop.view.ViewCommandInvocation;
import com.javaclaw.desktop.view.ViewData;
import com.javaclaw.desktop.view.ViewLoadRequest;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteAccountSettingsSectionTest {
    @Test
    void 密码只交给私有Gateway且提交后立即清空并冻结账号() {
        FxTestSupport.run(() -> {
            Gateway gateway = new Gateway();
            SiteAccountSettingsSection section = new SiteAccountSettingsSection(gateway, () -> {});
            Parent root = attach(section);
            initialize(section);
            field(root, "输入用户名；已有用户名不从密钥库回读").setText("alice");
            PasswordField password = nodes(root, PasswordField.class).getFirst();
            password.setText("private-password");
            button(root, "保存用户名密码").fire();
            assertEquals("alice", gateway.submittedUser);
            assertEquals("private-password", gateway.submittedPassword);
            assertArrayEquals(new char[5], gateway.userArray);
            assertArrayEquals(new char[16], gateway.passwordArray);
            assertEquals("", password.getText());
            assertEquals("", field(root, "输入用户名；已有用户名不从密钥库回读").getText());
            assertTrue(section.pending());
            assertTrue(button(root, "创建账号").isDisabled());
            assertEquals(0, gateway.ordinaryCommands);
            gateway.saved.complete(gateway.account);
            assertFalse(section.pending());
            assertFalse(section.dirty());
            NativeUiEvidence.capture(root, "site-account-settings.png");
        });
    }

    @Test
    void 切换Workspace清空秘密且迟到回执不能重建旧选择() {
        FxTestSupport.run(() -> {
            Gateway gateway = new Gateway();
            SiteAccountSettingsSection section = new SiteAccountSettingsSection(gateway, () -> {});
            Parent root = attach(section);
            initialize(section);
            field(root, "输入用户名；已有用户名不从密钥库回读").setText("alice");
            nodes(root, PasswordField.class).getFirst().setText("private-password");
            button(root, "保存用户名密码").fire();
            section.workspaceChanged(Optional.empty());
            gateway.saved.complete(gateway.account);
            assertEquals("", field(root, "例如：工作账号；创建时作为新账号名称").getText());
            assertTrue(button(root, "保存用户名密码").isDisabled());
            assertTrue(button(root, "设为默认").isDisabled());
            assertFalse(section.pending());
            assertFalse(section.dirty());
        });
    }

    @Test
    void 分别显示密码和登录态真实保存时间而不冒用账号更新时间() {
        FxTestSupport.run(() -> {
            Gateway gateway = new Gateway();
            SiteAccountSettingsSection section = new SiteAccountSettingsSection(gateway, () -> {});
            Parent root = attach(section);
            initialize(section);
            String displayed =
                    "密码保存时间：" + savedTime(Gateway.PASSWORD_TIME) + "\n登录态保存时间：" + savedTime(Gateway.STATE_TIME);
            assertTrue(nodes(root, Label.class).stream()
                    .anyMatch(label -> label.getText().equals(displayed)));
            assertFalse(displayed.contains(savedTime(gateway.account.updatedAt())));
            NativeUiEvidence.capture(root, "site-account-settings.png");
            section.workspaceChanged(Optional.empty());
            assertFalse(nodes(root, Label.class).stream()
                    .anyMatch(label -> label.getText().contains(savedTime(Gateway.PASSWORD_TIME))));
        });
    }

    @Test
    void 父级网站是唯一选择且展开前不查询目录或账号() {
        FxTestSupport.run(() -> {
            Gateway gateway = new Gateway();
            SiteAccountSettingsSection section = new SiteAccountSettingsSection(gateway, () -> {});
            Parent root = attach(section);
            section.workspaceChanged(Optional.of(DesktopTestFixtures.workspace()));
            section.setSite(Optional.of(site("site")));
            assertTrue(gateway.queriedSites.isEmpty());
            assertTrue(button(root, "刷新账号").isDisabled());
            section.activate();
            assertEquals(List.of("site"), gateway.queriedSites);
            assertEquals(1, nodes(root, ComboBox.class).size());
            section.setSite(Optional.of(site("second")));
            assertEquals(List.of("site", "second"), gateway.queriedSites);
            assertEquals("第二网站账号", field(root, "例如：工作账号；创建时作为新账号名称").getText());
            section.setSite(Optional.empty());
            assertEquals("", field(root, "例如：工作账号；创建时作为新账号名称").getText());
            assertTrue(button(root, "创建账号").isDisabled());
            assertFalse(section.pending());
        });
    }

    @Test
    void 取消账号切换保留行字段和提交目标且确认后统一丢弃() {
        FxTestSupport.run(() -> {
            Gateway gateway = new Gateway();
            SiteAccountSettingsSection section = new SiteAccountSettingsSection(gateway, () -> {});
            Parent root = attach(section);
            initialize(section);
            AtomicBoolean allow = new AtomicBoolean();
            section.setContextGuard(() -> false, () -> false, () -> {
                if (allow.get()) {
                    section.discardDraft();
                }
                return allow.get();
            });
            TextField name = field(root, "例如：工作账号；创建时作为新账号名称");
            name.setText("未保存名称");
            nodes(root, ComboBox.class).getFirst().getSelectionModel().select(1);
            assertEquals(gateway.account, nodes(root, ComboBox.class).getFirst().getValue());
            assertEquals("未保存名称", name.getText());
            button(root, "刷新账号").fire();
            assertEquals(1, gateway.queriedSites.size());
            assertTrue(section.dirty());
            allow.set(true);
            nodes(root, ComboBox.class).getFirst().getSelectionModel().select(1);
            assertEquals("其他账号", name.getText());
            assertFalse(section.dirty());
        });
    }

    @Test
    void 普通保存失败保留草稿且保存期间不能切换账号() {
        FxTestSupport.run(() -> {
            Gateway gateway = new Gateway();
            SiteAccountSettingsSection section = new SiteAccountSettingsSection(gateway, () -> {});
            Parent root = attach(section);
            initialize(section);
            field(root, "例如：工作账号；创建时作为新账号名称").setText("修改账号");
            assertTrue(nodes(root, PasswordField.class).getFirst().isDisabled());
            assertTrue(button(root, "保存用户名密码").isDisabled());
            button(root, "保存账号").fire();
            assertTrue(section.pending());
            nodes(root, ComboBox.class).getFirst().getSelectionModel().select(1);
            assertEquals(gateway.account, nodes(root, ComboBox.class).getFirst().getValue());
            assertEquals(
                    Map.of("siteId", "site", "accountId", "account"),
                    gateway.invocation.arguments().get("selection"));
            gateway.executed.completeExceptionally(new IllegalStateException("账号版本冲突"));
            assertFalse(section.pending());
            assertTrue(section.dirty());
            assertEquals("修改账号", field(root, "例如：工作账号；创建时作为新账号名称").getText());
            assertTrue(nodes(root, Label.class).stream()
                    .anyMatch(label -> label.getText().equals("账号版本冲突")));
            assertFalse(button(root, "保存账号").isDisabled());
        });
    }

    @Test
    void 密码草稿和其他分区草稿阻止会覆盖输入的保存() {
        FxTestSupport.run(() -> {
            Gateway gateway = new Gateway();
            SiteAccountSettingsSection section = new SiteAccountSettingsSection(gateway, () -> {});
            Parent root = attach(section);
            initialize(section);
            TextField username = field(root, "输入用户名；已有用户名不从密钥库回读");
            username.setText("alice");
            nodes(root, PasswordField.class).getFirst().setText("secret");
            assertTrue(field(root, "例如：工作账号；创建时作为新账号名称").isDisabled());
            assertTrue(button(root, "创建账号").isDisabled());
            assertTrue(button(root, "保存账号").isDisabled());
            assertTrue(button(root, "注销登录态").isDisabled());
            AtomicBoolean otherDirty = new AtomicBoolean(true);
            AtomicBoolean otherPending = new AtomicBoolean();
            section.setContextGuard(otherDirty::get, otherPending::get, () -> true);
            assertTrue(button(root, "保存用户名密码").isDisabled());
            otherDirty.set(false);
            section.refreshContext();
            assertFalse(button(root, "保存用户名密码").isDisabled());
            otherPending.set(true);
            section.refreshContext();
            assertTrue(button(root, "保存用户名密码").isDisabled());
            otherPending.set(false);
            section.refreshContext();
            button(root, "保存用户名密码").fire();
            gateway.saved.completeExceptionally(new IllegalStateException("权限已撤销"));
            assertFalse(section.pending());
            assertEquals("", username.getText());
            assertEquals("", nodes(root, PasswordField.class).getFirst().getText());
            assertArrayEquals(new char[6], gateway.passwordArray);
        });
    }

    @Test
    void 第二网站响应先到时第一网站迟到结果不会覆盖且后台完成只在FX更新() {
        Gateway gateway = new Gateway();
        CompletableFuture<ExtensionRpcContracts.CallResult> first = new CompletableFuture<>();
        gateway.pendingQueries = Map.of("site", first);
        SiteAccountSettingsSection section = FxTestSupport.call(() -> {
            SiteAccountSettingsSection created = new SiteAccountSettingsSection(gateway, () -> {});
            attach(created);
            initialize(created);
            assertTrue(created.pending());
            created.setSite(Optional.of(site("second")));
            assertTrue(first.isCancelled());
            assertFalse(created.pending());
            return created;
        });
        first.complete(gateway.accountResult("site"));
        FxTestSupport.run(() -> {
            Parent root = (Parent) section.content();
            assertEquals("第二网站账号", field(root, "例如：工作账号；创建时作为新账号名称").getText());
            section.dispose();
            section.activate();
            assertTrue(button(root, "刷新账号").isDisabled());
        });
    }

    @Test
    void 收起后清空秘密并拒绝迟到回执且重新展开按当前网站重查() {
        FxTestSupport.run(() -> {
            Gateway gateway = new Gateway();
            SiteAccountSettingsSection section = new SiteAccountSettingsSection(gateway, () -> {});
            Parent root = attach(section);
            initialize(section);
            field(root, "输入用户名；已有用户名不从密钥库回读").setText("alice");
            nodes(root, PasswordField.class).getFirst().setText("secret");
            button(root, "保存用户名密码").fire();
            section.deactivate();
            assertFalse(gateway.saved.isCancelled());
            gateway.saved.complete(gateway.account);
            assertFalse(section.pending());
            assertFalse(section.dirty());
            assertTrue(button(root, "刷新账号").isDisabled());
            section.activate();
            assertEquals(2, gateway.queriedSites.size());
            section.invalidateCache();
            field(root, "例如：工作账号；创建时作为新账号名称").setText("未保存");
            section.activate();
            assertEquals(2, gateway.queriedSites.size());
            section.setSite(Optional.of(site("site")));
            assertEquals("未保存", field(root, "例如：工作账号；创建时作为新账号名称").getText());
        });
    }

    @Test
    void 当前网站后台账号回执在FX线程应用且关闭取消未完成读取() {
        Gateway gateway = new Gateway();
        CompletableFuture<ExtensionRpcContracts.CallResult> reading = new CompletableFuture<>();
        gateway.pendingQueries = Map.of("site", reading);
        AtomicBoolean fxOnly = new AtomicBoolean(true);
        SiteAccountSettingsSection section = FxTestSupport.call(() -> {
            SiteAccountSettingsSection created = new SiteAccountSettingsSection(gateway, () -> {});
            attach(created);
            created.setStateChanged(() -> fxOnly.compareAndSet(true, Platform.isFxApplicationThread()));
            initialize(created);
            return created;
        });
        reading.complete(gateway.accountResult("site"));
        FxTestSupport.run(() -> {
            assertFalse(section.pending());
            assertTrue(fxOnly.get());
            assertEquals(
                    "工作",
                    field((Parent) section.content(), "例如：工作账号；创建时作为新账号名称").getText());
            CompletableFuture<ExtensionRpcContracts.CallResult> next = new CompletableFuture<>();
            gateway.pendingQueries = Map.of("site", next);
            section.invalidateCache();
            section.activate();
            assertTrue(section.pending());
            section.dispose();
            assertTrue(next.isCancelled());
            assertFalse(section.pending());
        });
    }

    private static void initialize(SiteAccountSettingsSection section) {
        section.workspaceChanged(Optional.of(DesktopTestFixtures.workspace()));
        section.setSite(Optional.of(site("site")));
        section.activate();
    }

    private static SiteContracts.Projection site(String id) {
        URI origin = URI.create("https://" + id + ".example.com");
        return new SiteContracts.Projection(id, 1, 1, "网站", origin, Set.of(origin), false, false, true, Instant.EPOCH);
    }

    private static String savedTime(Instant time) {
        return DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss z").format(time.atZone(ZoneId.systemDefault()));
    }

    private static Parent attach(SiteAccountSettingsSection section) {
        Parent root = (Parent) section.content();
        new Scene(root, 900, 700);
        root.applyCss();
        return root;
    }

    private static Button button(Parent root, String text) {
        return nodes(root, Button.class).stream()
                .filter(value -> value.getText().equals(text))
                .findFirst()
                .orElseThrow();
    }

    private static TextField field(Parent root, String prompt) {
        return nodes(root, TextField.class).stream()
                .filter(value -> value.getPromptText().equals(prompt))
                .findFirst()
                .orElseThrow();
    }

    private static <T extends Node> List<T> nodes(Node root, Class<T> type) {
        List<T> found = new ArrayList<>();
        if (type.isInstance(root)) {
            found.add(type.cast(root));
        }
        if (root instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> found.addAll(nodes(child, type)));
        }
        return found;
    }

    private static final class Gateway implements ExtensionSettingsGateway {
        private static final Instant PASSWORD_TIME = Instant.parse("2026-09-13T01:00:00Z");
        private static final Instant STATE_TIME = Instant.parse("2026-09-14T02:00:00Z");
        private final CanonicalJson json = new CanonicalJson();
        private final SiteAccountContracts.AccountProjection account = new SiteAccountContracts.AccountProjection(
                "account", "site", 1, 1, 1, "工作", true, true, true, true, Instant.EPOCH);
        private final CompletableFuture<SiteAccountContracts.AccountProjection> saved = new CompletableFuture<>();
        private String submittedUser;
        private String submittedPassword;
        private char[] userArray;
        private char[] passwordArray;
        private int ordinaryCommands;
        private final List<String> queriedSites = new ArrayList<>();
        private Map<String, CompletableFuture<ExtensionRpcContracts.CallResult>> pendingQueries = Map.of();
        private final CompletableFuture<ExtensionRpcContracts.CallResult> executed = new CompletableFuture<>();
        private ViewCommandInvocation invocation;

        @Override
        public CompletableFuture<ExtensionRpcContracts.CallResult> query(
                WorkspaceId workspace, String extension, String operation, CanonicalPayload arguments) {
            assertEquals("account/list", operation);
            String siteId = json.decode(arguments, SiteAccountContracts.ListRequest.class)
                    .siteId();
            queriedSites.add(siteId);
            return pendingQueries.getOrDefault(siteId, CompletableFuture.completedFuture(accountResult(siteId)));
        }

        private ExtensionRpcContracts.CallResult accountResult(String siteId) {
            List<SiteAccountContracts.AccountProjection> loaded = siteId.equals("site")
                    ? List.of(
                            account,
                            new SiteAccountContracts.AccountProjection(
                                    "other", "site", 1, 1, 1, "其他账号", true, false, false, false, Instant.EPOCH))
                    : List.of(new SiteAccountContracts.AccountProjection(
                            "second-account", siteId, 1, 1, 1, "第二网站账号", true, true, false, false, Instant.EPOCH));
            var savedTimes = siteId.equals("site")
                    ? Map.of(
                            account.accountId(),
                            new SiteAccountContracts.AccountSavedTimes(
                                    Optional.of(PASSWORD_TIME), Optional.of(STATE_TIME)))
                    : Map.<String, SiteAccountContracts.AccountSavedTimes>of();
            var result = new SiteAccountContracts.AccountList(loaded, savedTimes);
            return new ExtensionRpcContracts.CallResult(json.encode(result), 1);
        }

        @Override
        public CompletableFuture<SiteAccountContracts.AccountProjection> setAccountCredential(
                WorkspaceId workspace,
                SiteAccountContracts.CredentialRequest request,
                char[] username,
                char[] password,
                CommandOptions options) {
            submittedUser = new String(username);
            submittedPassword = new String(password);
            userArray = username;
            passwordArray = password;
            return saved;
        }

        @Override
        public CompletableFuture<List<ExtensionRpcContracts.ViewDocument>> list(String extensionId) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<ViewData> load(
                WorkspaceId workspaceId,
                ExtensionRpcContracts.ViewDocument document,
                ViewSchema schema,
                ViewLoadRequest request) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletableFuture<ExtensionRpcContracts.CallResult> execute(
                WorkspaceId workspaceId, String extensionId, ViewCommandInvocation invocation) {
            ordinaryCommands++;
            this.invocation = invocation;
            return executed;
        }

        @Override
        public CompletableFuture<AttachmentRef> upload(WorkspaceId workspaceId, ViewAttachmentUploadRequest request) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public DesktopNotificationSubscription subscribe(
                WorkspaceId workspaceId, String extensionId, Consumer<ExtensionRpcContracts.ExtensionEvent> listener) {
            return () -> {};
        }
    }
}
