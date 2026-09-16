package com.javaclaw.server.extension;

import java.nio.charset.StandardCharsets;
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

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.TurnCancelledException;
import com.javaclaw.builtin.contracts.BrowserCommands;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.SiteAccountContracts;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.IsolatedServiceInvocation;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.H2ManagedExtensionStore;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractiveBrowserCredentialFillTest {
    @TempDir
    Path directory;

    @Test
    void Vault凭据经私有帧填入一次且成功回执不包含秘密() throws Exception {
        try (var fixture = new InteractiveBrowserHostFixture(directory)) {
            var account = account(fixture);
            var turn = fixture.turn();
            fixture.service.invoke(fixture.modelInvocation(turn, "browser_open", open(account)));
            var fill = fixture.modelInvocation(turn, "browser_fill_account", target());
            var result = fixture.service.invoke(fill);
            assertEquals(result, fixture.service.invoke(fill));
            assertEquals(1, fixture.fills);
            assertArrayEquals(new byte["alice\0private-password".length()], fixture.filledInput);
            assertFalse(result.json().contains("alice"));
            assertFalse(result.json().contains("private-password"));
        }
    }

    @Test
    void 填入前取消不能开始秘密操作且不重试旧身份() throws Exception {
        try (var fixture = new InteractiveBrowserHostFixture(directory)) {
            var account = account(fixture);
            var turn = fixture.turn();
            fixture.service.invoke(fixture.modelInvocation(turn, "browser_open", open(account)));
            var fill = fixture.modelInvocation(turn, "browser_fill_account", target());
            var cancellation = new CancellationSource();
            cancellation.cancel("cancelled before private dispatch");
            var cancelled = new IsolatedServiceInvocation(
                    fill.caller(),
                    fill.workspaceId(),
                    fill.effectivePermissions(),
                    fill.serviceId(),
                    fill.request(),
                    cancellation,
                    fill.scope());
            assertThrows(TurnCancelledException.class, () -> fixture.service.invoke(cancelled));
            assertThrows(IllegalStateException.class, () -> fixture.service.invoke(fill));
            assertEquals(0, fixture.fills);
        }
    }

    @Test
    void 人工接管使正在填入的旧秘密回执失效且清零私有数组() throws Exception {
        try (var fixture = new InteractiveBrowserHostFixture(directory)) {
            var account = account(fixture);
            var turn = fixture.turn();
            fixture.service.invoke(fixture.modelInvocation(turn, "browser_open", open(account)));
            fixture.blockAction = true;
            var fill = fixture.modelInvocation(turn, "browser_fill_account", target());
            var pending = CompletableFuture.runAsync(() -> {
                try {
                    fixture.service.invoke(fill);
                } catch (Exception failure) {
                    throw new CompletionException(failure);
                }
            });
            try {
                assertTrue(fixture.actionStarted.await(3, TimeUnit.SECONDS));
                fixture.service.invoke(fixture.invocation(
                        "browser.takeover", Map.of(), fixture.view.lease().generation()));
                fixture.actionGate.countDown();
                assertThrows(CompletionException.class, pending::join);
                assertEquals(
                        BrowserContracts.ControlMode.HUMAN, fixture.view.lease().mode());
                assertArrayEquals(new byte["alice\0private-password".length()], fixture.filledInput);
            } finally {
                fixture.actionGate.countDown();
            }
        }
    }

    private static SiteAccountContracts.AccountProjection account(InteractiveBrowserHostFixture fixture)
            throws Exception {
        var site = new SiteContracts.Site(
                "browser-fill-site",
                1,
                1,
                "账号填充",
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
        var created = fixture.host
                .accounts()
                .command(
                        fixture.workspace,
                        "account/create",
                        fixture.json.encode(new SiteAccountContracts.CreateRequest(site.id(), "工作")),
                        identity("create", 0));
        var scope = new SiteAccountContracts.AccountScope(fixture.workspace, site.id(), created.accountId());
        return fixture.host
                .accounts()
                .setCredential(
                        scope,
                        created.securityRevision(),
                        identity("secret", created.revision()),
                        "alice\0private-password".getBytes(StandardCharsets.UTF_8));
    }

    private static BrowserContracts.CredentialsTarget target() {
        return new BrowserContracts.CredentialsTarget("page", "user", "password");
    }

    private static BrowserCommands.Open open(SiteAccountContracts.AccountProjection account) {
        return new BrowserCommands.Open(
                InteractiveBrowserHostFixture.ORIGIN,
                Optional.of(new SiteAccountContracts.Selection(account.siteId(), account.accountId())),
                false);
    }

    private static CommandIdentity identity(String method, long revision) {
        return new CommandIdentity(
                "browser-fill-test/" + method, UUID.randomUUID().toString(), revision, "a".repeat(64));
    }
}
