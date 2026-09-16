package com.javaclaw.server.extension;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.builtin.contracts.BrowserCommands;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.SiteAccountContracts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserSessionAccountsTest {
    @TempDir
    Path directory;

    @Test
    void 明确选择表单后密码与登录态原子保存而公开结果不包含秘密() throws Exception {
        try (var fixture = new BrowserAccountBoundaryFixture(directory, true)) {
            var forms =
                    fixture.base.json.decode(fixture.manage("browser.login.forms"), BrowserCommands.LoginForms.class);
            assertEquals(
                    BrowserAccountBoundaryFixture.TARGET,
                    forms.forms().getFirst().target());
            var reply = fixture.manage("browser.capture");
            var saved = fixture.base.json.decode(reply, SiteAccountContracts.AccountProjection.class);
            assertTrue(saved.passwordConfigured());
            assertTrue(saved.loginStateConfigured());
            assertEquals(2, fixture.base.vault.status().credentialCount());
            assertEquals(1, fixture.saves);
            assertEquals(1, fixture.captures);
            assertFalse(reply.json().contains("alice"));
            assertFalse(reply.json().contains("private-password"));
            assertFalse(reply.json().contains("cookies"));
            fixture.privateBuffers.forEach(bytes -> assertArrayEquals(new byte[bytes.length], bytes));
            assertThrows(SecurityException.class, () -> fixture.accounts.requireCurrent(fixture.session));
        }
    }

    @Test
    void 仅保存登录态保持安全版本且后续续存使用更新的状态租约() throws Exception {
        try (var fixture = new BrowserAccountBoundaryFixture(directory, true)) {
            var saved = fixture.base.json.decode(
                    fixture.manage("browser.save"), SiteAccountContracts.AccountProjection.class);
            assertTrue(fixture.session.keepLogin);
            assertFalse(saved.passwordConfigured());
            assertTrue(saved.loginStateConfigured());
            assertEquals(fixture.account.securityRevision(), saved.securityRevision());
            assertEquals(saved.stateRevision(), fixture.session.stateLease.stateRevision());
            fixture.manage("browser.save");
            assertEquals(saved.stateRevision() + 1, fixture.current().stateRevision());
            fixture.accounts.withState(fixture.session, bytes -> {
                assertArrayEquals(BrowserAccountBoundaryFixture.STATE, bytes);
                return null;
            });
            fixture.accounts.requireSameAccount(fixture.session, Optional.of(fixture.selection()));
            assertThrows(
                    IllegalStateException.class,
                    () -> fixture.accounts.requireSameAccount(fixture.session, Optional.empty()));
            assertThrows(IllegalArgumentException.class, () -> fixture.manage("browser.unknown"));
        }
    }

    @Test
    void 用户操作必须使用未关闭未过期且当前代次的人工租约() throws Exception {
        try (var fixture = new BrowserAccountBoundaryFixture(directory, true)) {
            var original = fixture.session.access;
            for (Consumer<BrowserAccountBoundaryFixture> change : rejectedAccess()) {
                fixture.session.closed = false;
                fixture.session.closing = false;
                fixture.session.access = original;
                change.accept(fixture);
                assertThrows(SecurityException.class, () -> fixture.manage("browser.login.forms"));
            }
            fixture.session.access = original;
            var command = new BrowserCommands.Invocation("browser.login.forms", fixture.base.json.encode(Map.of()));
            assertThrows(
                    SecurityException.class,
                    () -> fixture.accounts.manage(
                            fixture.base.invocation("browser.login.forms", Map.of(), 2),
                            command,
                            fixture.session,
                            fixture.worker));
            var turn = fixture.base.turn();
            assertThrows(
                    SecurityException.class,
                    () -> fixture.accounts.manage(
                            fixture.base.modelInvocation(turn, "browser.login.forms", Map.of()),
                            command,
                            fixture.session,
                            fixture.worker));
            assertEquals(0, fixture.captures);
            assertEquals(0, fixture.saves);
        }
    }

    @Test
    void 私有捕获返回期间关闭交还或替换保存租约都不能提交() throws Exception {
        int index = 0;
        for (Consumer<BrowserAccountBoundaryFixture> change : invalidatedCallbacks()) {
            try (var fixture = new BrowserAccountBoundaryFixture(directory.resolve("capture-" + index++), true)) {
                fixture.beforeCapture = () -> change.accept(fixture);
                assertThrows(SecurityException.class, () -> fixture.manage("browser.capture"));
                assertFalse(fixture.current().passwordConfigured());
                assertFalse(fixture.current().loginStateConfigured());
                assertEquals(0, fixture.base.vault.status().credentialCount());
                fixture.privateBuffers.forEach(bytes -> assertArrayEquals(new byte[bytes.length], bytes));
            }
        }
    }

    @Test
    void 自动保存回调期间撤销会话身份不能写回旧Cookie() throws Exception {
        int index = 0;
        for (Consumer<BrowserAccountBoundaryFixture> change : invalidatedCallbacks()) {
            try (var fixture = new BrowserAccountBoundaryFixture(directory.resolve("save-" + index++), true)) {
                fixture.session.keepLogin = true;
                fixture.beforeSave = () -> change.accept(fixture);
                assertThrows(
                        SecurityException.class,
                        () -> fixture.accounts.saveIfSelected(fixture.session, fixture.session.access, fixture.worker));
                assertFalse(fixture.current().loginStateConfigured());
                fixture.privateBuffers.forEach(bytes -> assertArrayEquals(new byte[bytes.length], bytes));
            }
        }
    }

    @Test
    void 匿名会话不能保存或捕获账号且关闭会话不导出状态() throws Exception {
        try (var fixture = new BrowserAccountBoundaryFixture(directory, false)) {
            assertThrows(IllegalStateException.class, () -> fixture.manage("browser.capture"));
            assertThrows(IllegalStateException.class, () -> fixture.manage("browser.login.forms"));
            fixture.session.keepLogin = true;
            fixture.accounts.saveIfSelected(fixture.session, fixture.session.access, fixture.worker);
            assertEquals(0, fixture.saves);
        }
        try (var fixture = new BrowserAccountBoundaryFixture(directory.resolve("closed"), true)) {
            fixture.session.keepLogin = true;
            fixture.session.closed = true;
            fixture.accounts.saveIfSelected(fixture.session, fixture.session.access, fixture.worker);
            assertEquals(0, fixture.saves);
        }
    }

    @Test
    void 网站变更删除或账号撤权阻止表单准备和后台续存() throws Exception {
        int index = 0;
        for (String action :
                List.of("origin", "site-disabled", "site-deleted", "account-disabled", "account-deleted")) {
            try (var fixture = new BrowserAccountBoundaryFixture(directory.resolve("revoked-" + index++), true)) {
                revoke(fixture, action);
                assertThrows(RuntimeException.class, () -> fixture.manage("browser.login.forms"));
                fixture.session.keepLogin = true;
                assertThrows(
                        SecurityException.class,
                        () -> fixture.accounts.saveIfSelected(fixture.session, fixture.session.access, fixture.worker));
                assertEquals(0, fixture.saves);
            }
        }
    }

    @Test
    void 创建账号会话拒绝错误Origin和已禁用账号() throws Exception {
        try (var fixture = new BrowserAccountBoundaryFixture(directory, true)) {
            fixture.accounts.release(fixture.session);
            var request = new BrowserCommands.Open(
                    URI.create("https://other.example.com"), Optional.of(fixture.selection()), false);
            assertThrows(
                    SecurityException.class,
                    () -> fixture.accounts.create(
                            fixture.base.invocation("browser.open", request, 0), request, fixture.session.access));
            revoke(fixture, "account-disabled");
            var disabled = new BrowserCommands.Open(
                    InteractiveBrowserHostFixture.ORIGIN, Optional.of(fixture.selection()), false);
            assertThrows(
                    SecurityException.class,
                    () -> fixture.accounts.create(
                            fixture.base.invocation("browser.open", disabled, 0), disabled, fixture.session.access));
        }
    }

    private static List<Consumer<BrowserAccountBoundaryFixture>> rejectedAccess() {
        return List.of(
                fixture -> fixture.session.closed = true,
                fixture -> fixture.session.closing = true,
                fixture -> fixture.changeAccess(BrowserContracts.ControlMode.HUMAN, 2, Duration.ofSeconds(-1)),
                fixture -> fixture.changeAccess(BrowserContracts.ControlMode.ASSISTANT, 2, Duration.ofMinutes(1)));
    }

    private static List<Consumer<BrowserAccountBoundaryFixture>> invalidatedCallbacks() {
        return List.of(
                fixture -> fixture.session.closed = true,
                fixture -> fixture.changeAccess(BrowserContracts.ControlMode.ASSISTANT, 2, Duration.ofMinutes(1)),
                fixture -> fixture.session.stateLease = null);
    }

    private static void revoke(BrowserAccountBoundaryFixture fixture, String action) throws Exception {
        switch (action) {
            case "origin" -> fixture.site(2, true, URI.create("https://other.example.com"));
            case "site-disabled" -> fixture.site(2, false, InteractiveBrowserHostFixture.ORIGIN);
            case "site-deleted" -> fixture.deleteSite();
            case "account-disabled" ->
                fixture.base
                        .host
                        .accounts()
                        .command(
                                fixture.base.workspace,
                                "account/update",
                                fixture.base.json.encode(
                                        new SiteAccountContracts.UpdateRequest(fixture.selection(), "禁用", false)),
                                BrowserAccountBoundaryFixture.identity("disable", fixture.account.revision()));
            case "account-deleted" ->
                fixture.base
                        .host
                        .accounts()
                        .command(
                                fixture.base.workspace,
                                "account/delete",
                                fixture.base.json.encode(fixture.selection()),
                                BrowserAccountBoundaryFixture.identity("delete", fixture.account.revision()));
            default -> throw new IllegalArgumentException(action);
        }
    }
}
