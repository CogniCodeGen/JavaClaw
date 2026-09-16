package com.javaclaw.server.extension;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.builtin.contracts.BrowserCommands;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.SiteAccountContracts;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.H2ManagedExtensionStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractiveBrowserAccountLifecycleTest {
    @TempDir
    Path directory;

    @Test
    void 账号注销关闭真实宿主会话且拒绝正在执行的旧观察() throws Exception {
        try (var fixture = new InteractiveBrowserHostFixture(directory)) {
            var account = account(fixture);
            var turn = fixture.turn();
            fixture.service.invoke(fixture.modelInvocation(turn, "browser_open", open(account, false)));
            fixture.blockAction = true;
            var action = fixture.modelInvocation(
                    turn,
                    "browser_act",
                    new BrowserCommands.Act(
                            BrowserContracts.Action.simple(BrowserContracts.Operation.SNAPSHOT), Optional.empty()));
            var reply = CompletableFuture.runAsync(() -> {
                try {
                    fixture.service.invoke(action);
                } catch (Exception failure) {
                    throw new CompletionException(failure);
                }
            });
            try {
                assertTrue(fixture.actionStarted.await(2, TimeUnit.SECONDS));
                fixture.host
                        .accounts()
                        .command(
                                fixture.workspace,
                                "account/logout",
                                fixture.json.encode(selection(account)),
                                identity("logout", account.revision()));
                assertTrue(fixture.sessionClosed.await(5, TimeUnit.SECONDS), "账号服务真实撤权事件必须回收活跃Worker");
                fixture.actionGate.countDown();
                assertThrows(CompletionException.class, reply::join);
                assertEquals(1, fixture.closes);
                assertEquals(0, fixture.saves, "注销回收不能把旧Cookie写回Vault");
            } finally {
                fixture.actionGate.countDown();
            }
        }
    }

    @Test
    void 交还后无网络租约仍在用户关闭时私有保存所选账号登录态() throws Exception {
        try (var fixture = new InteractiveBrowserHostFixture(directory)) {
            var account = account(fixture);
            fixture.service.invoke(fixture.invocation("browser.open", open(account, true), 0));
            fixture.service.invoke(fixture.invocation(
                    "browser.return", Map.of(), fixture.view.lease().generation()));
            assertEquals(BrowserContracts.ControlMode.NONE, fixture.view.lease().mode());
            fixture.service.invoke(fixture.invocation(
                    "browser.close", Map.of(), fixture.view.lease().generation()));
            var saved = fixture.host
                    .accounts()
                    .list(fixture.workspace, account.siteId())
                    .accounts()
                    .getFirst();
            assertEquals(1, fixture.saves);
            assertTrue(saved.loginStateConfigured());
            assertEquals(account.securityRevision(), saved.securityRevision());
            assertEquals(account.stateRevision() + 1, saved.stateRevision());
        }
    }

    private static SiteAccountContracts.AccountProjection account(InteractiveBrowserHostFixture fixture)
            throws Exception {
        var site = new SiteContracts.Site(
                "browser-site",
                1,
                1,
                "浏览器测试",
                InteractiveBrowserHostFixture.ORIGIN,
                Set.of(InteractiveBrowserHostFixture.ORIGIN),
                SiteContracts.SiteCredential.none(),
                Optional.empty(),
                true,
                fixture.host.clock().instant());
        new H2ManagedExtensionStore(fixture.host.database(), fixture.host.clock())
                .inTransaction(new ExtensionId(BuiltinExtensionIds.SITE), tx -> {
                    tx.put("documents." + fixture.workspace, site.id(), 0, fixture.json.encode(site));
                    return null;
                });
        return fixture.host
                .accounts()
                .command(
                        fixture.workspace,
                        "account/create",
                        fixture.json.encode(new SiteAccountContracts.CreateRequest(site.id(), "工作")),
                        identity("create", 0));
    }

    private static BrowserCommands.Open open(SiteAccountContracts.AccountProjection account, boolean keepLogin) {
        return new BrowserCommands.Open(
                InteractiveBrowserHostFixture.ORIGIN, Optional.of(selection(account)), keepLogin);
    }

    private static SiteAccountContracts.Selection selection(SiteAccountContracts.AccountProjection account) {
        return new SiteAccountContracts.Selection(account.siteId(), account.accountId());
    }

    private static CommandIdentity identity(String method, long revision) {
        return new CommandIdentity(
                "browser-account-test/" + method, UUID.randomUUID().toString(), revision, "a".repeat(64));
    }
}
