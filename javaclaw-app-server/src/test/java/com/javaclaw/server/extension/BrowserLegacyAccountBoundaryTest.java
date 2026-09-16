package com.javaclaw.server.extension;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.SiteAccountContracts;
import com.javaclaw.server.site.account.SiteAccountLegacyAccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserLegacyAccountBoundaryTest {
    @TempDir
    Path directory;

    @Test
    void 重复人工登录保持同一账号且不允许跨Workspace或网站复用会话() throws Exception {
        try (var fixture = new BrowserAccountBoundaryFixture(directory, false);
                var legacy =
                        new SiteAccountLegacyAccess(fixture.base.host.accounts(), fixture.base.json, ignored -> {})) {
            var first = legacy.begin(fixture.base.workspace, fixture.account.siteId(), "login");
            assertEquals(first, legacy.begin(fixture.base.workspace, fixture.account.siteId(), "login"));
            assertThrows(
                    SecurityException.class,
                    () -> legacy.begin(WorkspaceId.random(), fixture.account.siteId(), "login"));
            assertThrows(SecurityException.class, () -> legacy.begin(fixture.base.workspace, "other-site", "login"));
            legacy.require(first);
            var identity = BrowserAccountBoundaryFixture.identity("legacy-save", 0);
            var saved = legacy.save("login", identity, BrowserAccountBoundaryFixture.STATE.clone());
            assertEquals(saved, legacy.recover("login", identity).orElseThrow());
            assertTrue(legacy.recover("login", BrowserAccountBoundaryFixture.identity("not-saved", 0))
                    .isEmpty());
            legacy.release("login");
            assertThrows(SecurityException.class, () -> legacy.recover("login", identity));
            assertThrows(
                    SecurityException.class,
                    () -> legacy.save("login", identity, BrowserAccountBoundaryFixture.STATE.clone()));
            legacy.release("login");
        }
    }

    @Test
    void 另一账号变更不取消当前登录而所选账号注销立即移除保存权() throws Exception {
        try (var fixture = new BrowserAccountBoundaryFixture(directory, false)) {
            CountDownLatch revoked = new CountDownLatch(1);
            AtomicReference<String> cancelled = new AtomicReference<>();
            try (var legacy = new SiteAccountLegacyAccess(fixture.base.host.accounts(), fixture.base.json, id -> {
                cancelled.set(id);
                revoked.countDown();
            })) {
                var binding = legacy.begin(fixture.base.workspace, fixture.account.siteId(), "login");
                var other = fixture.create("个人");
                fixture.base
                        .host
                        .accounts()
                        .command(
                                fixture.base.workspace,
                                "account/logout",
                                fixture.base.json.encode(
                                        new SiteAccountContracts.Selection(other.siteId(), other.accountId())),
                                BrowserAccountBoundaryFixture.identity("other-logout", other.revision()));
                assertEquals(null, cancelled.get());
                legacy.require(binding);
                fixture.base
                        .host
                        .accounts()
                        .command(
                                fixture.base.workspace,
                                "account/logout",
                                fixture.base.json.encode(fixture.selection()),
                                BrowserAccountBoundaryFixture.identity("logout", fixture.account.revision()));
                assertTrue(revoked.await(3, TimeUnit.SECONDS));
                assertEquals("login", cancelled.get());
                assertThrows(RuntimeException.class, () -> legacy.require(binding));
                assertThrows(
                        SecurityException.class,
                        () -> legacy.save(
                                "login",
                                BrowserAccountBoundaryFixture.identity("late-save", 0),
                                BrowserAccountBoundaryFixture.STATE.clone()));
                assertFalse(fixture.current().loginStateConfigured());
            }
        }
    }

    @Test
    void 已有账号但默认账号被删除时旧登录不能擅自挑选其他账号() throws Exception {
        try (var fixture = new BrowserAccountBoundaryFixture(directory, false);
                var legacy =
                        new SiteAccountLegacyAccess(fixture.base.host.accounts(), fixture.base.json, ignored -> {})) {
            var other = fixture.create("个人");
            fixture.base
                    .host
                    .accounts()
                    .command(
                            fixture.base.workspace,
                            "account/delete",
                            fixture.base.json.encode(fixture.selection()),
                            BrowserAccountBoundaryFixture.identity("delete", fixture.account.revision()));
            assertTrue(legacy.select(fixture.base.workspace, other.siteId())
                    .scope()
                    .isEmpty());
            assertThrows(
                    IllegalStateException.class, () -> legacy.begin(fixture.base.workspace, other.siteId(), "login"));
            assertEquals(
                    1,
                    fixture.base
                            .host
                            .accounts()
                            .list(fixture.base.workspace, other.siteId())
                            .accounts()
                            .size());
        }
    }
}
