package com.javaclaw.server.extension;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.browser.protocol.BrowserWorkerProtocol;
import com.javaclaw.builtin.contracts.SiteAccountContracts;
import com.javaclaw.builtin.contracts.SiteAccountContracts.AccountProjection;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.server.persistence.CommandIdentity;

import static com.javaclaw.server.extension.SiteBrowserServiceTest.identity;
import static com.javaclaw.server.extension.SiteBrowserServiceTest.invocation;
import static com.javaclaw.server.extension.SiteBrowserServiceTest.site;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteBrowserDefaultAccountTest {
    private static final WorkspaceId WORKSPACE = WorkspaceId.parse("f199f4f0-10b2-4d48-9941-d44571acf8d4");
    private static final byte[] STATE = "{\"cookies\":[],\"origins\":[]}".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path directory;

    @Test
    void 旧Snapshot使用默认账号的最新状态且注销删除后不回退单凭据() throws Exception {
        try (var fixture = SiteBrowserServiceTest.fixture(directory)) {
            CredentialMetadata secret = fixture.vault()
                    .create(
                            identity("state"),
                            SiteContracts.BROWSER_CREDENTIAL_NAMESPACE,
                            "{\"cookies\":[],\"legacy\":true}".getBytes(StandardCharsets.UTF_8));
            SiteContracts.Site site = site(
                    1,
                    1,
                    new SiteContracts.SiteCredential(
                            SiteContracts.CredentialKind.BROWSER_STORAGE,
                            Optional.of(secret.reference()),
                            Optional.empty()),
                    true);
            fixture.put(site, 0);
            fixture.service().bindAccounts(fixture.accounts());
            AccountProjection account =
                    fixture.accounts().list(WORKSPACE, site.id()).accounts().getFirst();
            var scope = new SiteAccountContracts.AccountScope(WORKSPACE, site.id(), account.accountId());
            var lease = fixture.accounts().acquireStateLease(scope, "new-browser");
            AccountProjection saved = fixture.accounts().saveState(lease, identity("save-state"), STATE.clone());
            fixture.accounts().releaseStateLease(lease);

            fixture.service().snapshot(invocation(fixture, site));
            assertEquals(new String(STATE, StandardCharsets.UTF_8), fixture.browser().lastState);
            AccountProjection loggedOut = control(fixture, saved, "account/logout");
            fixture.service().snapshot(invocation(fixture, site));
            assertEquals("", fixture.browser().lastState);
            control(fixture, loggedOut, "account/delete");
            fixture.service().snapshot(invocation(fixture, site));
            assertEquals("", fixture.browser().lastState);
            assertTrue(fixture.accounts().list(WORKSPACE, site.id()).accounts().isEmpty());
        }
    }

    @Test
    void 旧人工登录保存默认账号且保留Bearer与SiteAuthority并可幂等重放() throws Exception {
        try (var fixture = SiteBrowserServiceTest.fixture(directory)) {
            CredentialMetadata token = fixture.vault()
                    .create(
                            identity("token"),
                            SiteContracts.SITE_CREDENTIAL_NAMESPACE,
                            "original-bearer".getBytes(StandardCharsets.UTF_8));
            SiteContracts.Site site = site(
                    1,
                    1,
                    new SiteContracts.SiteCredential(
                            SiteContracts.CredentialKind.BEARER, Optional.of(token.reference()), Optional.empty()),
                    true);
            fixture.put(site, 0);
            fixture.service().bindAccounts(fixture.accounts());
            fixture.browser().savedState = STATE.clone();
            SiteContracts.LoginBeginTask begin = new SiteContracts.LoginBeginTask(
                    site, "44c6dc48-cba8-483e-9908-f93fc03f27f1", Duration.ofMinutes(1));
            fixture.service().loginBegin(invocation(fixture, begin));
            SiteContracts.LoginSaveTask save =
                    new SiteContracts.LoginSaveTask("44c6dc48-cba8-483e-9908-f93fc03f27f1", "save", "a".repeat(64));
            var payload = fixture.service().loginSave(invocation(fixture, save));
            var result = fixture.json().decode(payload, SiteContracts.LoginSaveCommit.class);
            assertEquals(site, result.site());
            assertEquals(payload, fixture.service().loginSave(invocation(fixture, save)));
            assertTrue(fixture.accounts()
                    .list(WORKSPACE, site.id())
                    .accounts()
                    .getFirst()
                    .loginStateConfigured());
            fixture.browser().networkRequests = List.of(new BrowserWorkerProtocol.NetworkRequest(
                    URI.create("https://docs.example.com/page"), "GET", Map.of()));
            fixture.service().snapshot(invocation(fixture, site));
            assertEquals(new String(STATE, StandardCharsets.UTF_8), fixture.browser().lastState);
            assertEquals(
                    List.of("Bearer original-bearer"),
                    fixture.broker().requests.getFirst().headers().get("authorization"));
            fixture.service()
                    .loginBegin(invocation(
                            fixture,
                            new SiteContracts.LoginBeginTask(
                                    site, "172128a5-3eeb-439f-9ea5-6f65beb94199", Duration.ofMinutes(1))));
            assertEquals(new String(STATE, StandardCharsets.UTF_8), fixture.browser().lastState);
        }
    }

    @Test
    void 账号DNS后注销在Socket前阻断且禁用账号不回退旧状态() throws Exception {
        try (var fixture = SiteBrowserServiceTest.fixture(directory)) {
            SiteContracts.Site site = site(1, 1, SiteContracts.SiteCredential.none(), true);
            fixture.put(site, 0);
            fixture.service().bindAccounts(fixture.accounts());
            var payload = fixture.json().encode(new SiteAccountContracts.CreateRequest(site.id(), "账号"));
            AccountProjection account =
                    fixture.accounts().command(WORKSPACE, "account/create", payload, identity("create"));
            fixture.browser().networkRequests = List.of(new BrowserWorkerProtocol.NetworkRequest(
                    URI.create("https://docs.example.com/page"), "GET", Map.of()));
            fixture.broker().beforeRealtime = () -> control(fixture, account, "account/logout");
            assertThrows(RuntimeException.class, () -> fixture.service().snapshot(invocation(fixture, site)));
            assertFalse(fixture.broker().socketOpened);
            AccountProjection current =
                    fixture.accounts().list(WORKSPACE, site.id()).accounts().getFirst();
            var disabled = fixture.json()
                    .encode(new SiteAccountContracts.UpdateRequest(
                            new SiteAccountContracts.Selection(site.id(), current.accountId()), current.name(), false));
            fixture.accounts()
                    .command(
                            WORKSPACE,
                            "account/update",
                            disabled,
                            new CommandIdentity("update", "disabled", current.revision(), disabled.sha256()));
            assertThrows(SecurityException.class, () -> fixture.service().snapshot(invocation(fixture, site)));
        }
    }

    @Test
    void Bearer网络等待不占Vault锁且并发注销在Socket前生效() throws Exception {
        try (var fixture = SiteBrowserServiceTest.fixture(directory);
                var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            CredentialMetadata token = fixture.vault()
                    .create(
                            identity("token"),
                            SiteContracts.SITE_CREDENTIAL_NAMESPACE,
                            "bearer-token".getBytes(StandardCharsets.UTF_8));
            SiteContracts.Site site = site(
                    1,
                    1,
                    new SiteContracts.SiteCredential(
                            SiteContracts.CredentialKind.BEARER, Optional.of(token.reference()), Optional.empty()),
                    true);
            fixture.put(site, 0);
            fixture.service().bindAccounts(fixture.accounts());
            var payload = fixture.json().encode(new SiteAccountContracts.CreateRequest(site.id(), "账号"));
            AccountProjection account =
                    fixture.accounts().command(WORKSPACE, "account/create", payload, identity("create"));
            var scope = new SiteAccountContracts.AccountScope(WORKSPACE, site.id(), account.accountId());
            var lease = fixture.accounts().acquireStateLease(scope, "saved-state");
            AccountProjection saved = fixture.accounts().saveState(lease, identity("save-state"), STATE.clone());
            fixture.accounts().releaseStateLease(lease);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch released = new CountDownLatch(1);
            fixture.browser().networkRequests = List.of(new BrowserWorkerProtocol.NetworkRequest(
                    URI.create("https://docs.example.com/page"), "GET", Map.of()));
            fixture.broker().beforeRealtime = () -> {
                entered.countDown();
                await(released);
            };
            var snapshot = workers.submit(() -> fixture.service().snapshot(invocation(fixture, site)));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                // 请求仍停在 Broker 时，注销必须完成实际 Vault 清除，而非只先改 UI 状态。
                workers.submit(() -> control(fixture, saved, "account/logout")).get(5, TimeUnit.SECONDS);
            } finally {
                released.countDown();
            }
            assertThrows(ExecutionException.class, () -> snapshot.get(5, TimeUnit.SECONDS));
            assertFalse(fixture.broker().socketOpened);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Broker 等待未释放");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static AccountProjection control(
            SiteBrowserServiceTest.Fixture fixture, AccountProjection account, String op) {
        var payload = fixture.json().encode(new SiteAccountContracts.Selection(account.siteId(), account.accountId()));
        return fixture.accounts()
                .command(
                        WORKSPACE,
                        op,
                        payload,
                        new CommandIdentity(op, op + account.revision(), account.revision(), payload.sha256()));
    }
}
