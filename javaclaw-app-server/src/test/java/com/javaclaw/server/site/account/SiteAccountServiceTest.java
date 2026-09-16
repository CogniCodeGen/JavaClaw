package com.javaclaw.server.site.account;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.SiteAccountContracts;
import com.javaclaw.builtin.contracts.SiteAccountContracts.AccountProjection;
import com.javaclaw.builtin.contracts.SiteAccountContracts.AccountScope;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.nativehost.credential.MasterKeyProtector;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2ManagedExtensionStore;
import com.javaclaw.server.persistence.PersistenceException;
import com.javaclaw.server.security.vault.SecretVaultService;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteAccountServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-14T00:00:00Z"), ZoneOffset.UTC);
    private static final WorkspaceId WORKSPACE = WorkspaceId.parse("7527756d-4881-4a7f-aa87-f01f38919061");
    private static final URI ORIGIN = URI.create("https://example.com");
    private static final byte[] STATE = "{\"cookies\":[],\"origins\":[]}".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path temporaryDirectory;

    @Test
    void 旧单登录态只迁移一次且删除账号后不会复活() throws Exception {
        try (Fixture fixture = fixture(true)) {
            var first = fixture.accounts.list(WORKSPACE, "site");
            assertEquals(1, first.accounts().size());
            AccountProjection account = first.accounts().getFirst();
            assertTrue(account.defaultAccount());
            assertTrue(account.loginStateConfigured());
            assertEquals(first, fixture.accounts.list(WORKSPACE, "site"));
            fixture.control(account, "account/delete", "delete");
            assertTrue(fixture.accounts.list(WORKSPACE, "site").accounts().isEmpty());
            assertEquals(0, fixture.vault.status().credentialCount());
            SiteAccountService reopened = new SiteAccountService(fixture.database, fixture.vault, fixture.json, CLOCK);
            assertTrue(reopened.list(WORKSPACE, "site").accounts().isEmpty());
        }
    }

    @Test
    void 密码和登录态独立保存且自动续存不会撤销正在使用的会话() throws Exception {
        try (Fixture fixture = fixture(false)) {
            AccountProjection account = fixture.create("工作", "create");
            AccountScope scope = scope(account);
            byte[] login = "alice\0correct-password".getBytes(StandardCharsets.UTF_8);
            AccountProjection saved = fixture.accounts.setCredential(
                    scope, account.securityRevision(), identity("secret", account.revision()), login);
            assertArrayEquals(new byte[login.length], login);
            assertTrue(saved.passwordConfigured());
            assertFalse(saved.loginStateConfigured());
            assertFalse(fixture.json.encode(saved).json().contains("correct-password"));
            assertFalse(fixture.json.encode(saved).json().contains("reference"));
            assertEquals(
                    "alice\0correct-password",
                    fixture.accounts.useCredential(
                            scope, saved.securityRevision(), bytes -> new String(bytes, StandardCharsets.UTF_8)));
            AtomicInteger invalidations = new AtomicInteger();
            AtomicInteger providerReloads = new AtomicInteger();
            fixture.accounts.onSecurityChanged(ignored -> invalidations.incrementAndGet());
            fixture.vault.onRuntimeChange(providerReloads::incrementAndGet, providerReloads::incrementAndGet);
            var lease = fixture.accounts.acquireStateLease(scope, "session-1");
            byte[] state = STATE.clone();
            AccountProjection stateSaved = fixture.accounts.saveState(lease, identity("state-1", 0), state);
            assertArrayEquals(new byte[state.length], state);
            assertEquals(saved.securityRevision(), stateSaved.securityRevision());
            assertEquals(saved.stateRevision() + 1, stateSaved.stateRevision());
            assertEquals(0, invalidations.get());
            assertEquals(0, providerReloads.get());
            assertThrows(
                    SecurityException.class,
                    () -> fixture.accounts.saveState(lease, identity("stale", 0), STATE.clone()));
            var nextLease = fixture.accounts.acquireStateLease(scope, "session-1");
            assertEquals(stateSaved.stateRevision(), nextLease.stateRevision());
            assertArrayEquals(STATE, fixture.accounts.useState(scope, stateSaved.securityRevision(), byte[]::clone));
            assertThrows(IllegalStateException.class, () -> fixture.accounts.acquireStateLease(scope, "session-2"));
            fixture.accounts.releaseStateLease(lease);
            assertTrue(fixture.accounts.acquireStateLease(scope, "session-2").generation() > lease.generation());
        }
    }

    @Test
    void 注销和禁用拒绝迟到保存且不影响其他账号() throws Exception {
        try (Fixture fixture = fixture(false)) {
            AccountProjection first = fixture.create("工作", "create-1");
            AccountProjection second = fixture.create("个人", "create-2");
            var oldLease = fixture.accounts.acquireStateLease(scope(first), "one");
            var independent = fixture.accounts.acquireStateLease(scope(second), "two");
            AccountProjection loggedOut = fixture.control(first, "account/logout", "logout");
            assertEquals(first.securityRevision() + 1, loggedOut.securityRevision());
            assertThrows(
                    SecurityException.class,
                    () -> fixture.accounts.saveState(oldLease, identity("late", 0), STATE.clone()));
            AccountProjection updatedSecond =
                    fixture.accounts.saveState(independent, identity("independent", 0), STATE.clone());
            assertTrue(updatedSecond.loginStateConfigured());
            var request = new SiteAccountContracts.UpdateRequest(selection(loggedOut), "工作", false);
            fixture.accounts.command(
                    WORKSPACE,
                    "account/update",
                    fixture.json.encode(request),
                    identity("disable", loggedOut.revision()));
            assertThrows(SecurityException.class, () -> fixture.accounts.acquireStateLease(scope(first), "new"));
            assertEquals(2, fixture.accounts.list(WORKSPACE, "site").accounts().size());
        }
    }

    @Test
    void 改密原子清除旧登录态且重试不创建第二份密码() throws Exception {
        try (Fixture fixture = fixture(false)) {
            AccountProjection account = fixture.create("工作", "create");
            var lease = fixture.accounts.acquireStateLease(scope(account), "one");
            AccountProjection stateSaved = fixture.accounts.saveState(lease, identity("state", 0), STATE.clone());
            CommandIdentity command = identity("password", stateSaved.revision());
            byte[] secret = "u\0p".getBytes(StandardCharsets.UTF_8);
            AccountProjection saved =
                    fixture.accounts.setCredential(scope(account), stateSaved.securityRevision(), command, secret);
            assertTrue(saved.passwordConfigured());
            assertFalse(saved.loginStateConfigured());
            assertEquals(1, fixture.vault.status().credentialCount());
            assertEquals(
                    saved,
                    fixture.accounts.setCredential(
                            scope(account),
                            stateSaved.securityRevision(),
                            command,
                            "ignored".getBytes(StandardCharsets.UTF_8)));
            assertEquals(1, fixture.vault.status().credentialCount());
            assertThrows(
                    SecurityException.class,
                    () -> fixture.accounts.saveState(lease, identity("late-state", 0), STATE.clone()));
        }
    }

    @Test
    void 网站权限改变和错误Workspace不能复用账号() throws Exception {
        try (Fixture fixture = fixture(false)) {
            AccountProjection account = fixture.create("工作", "create");
            var lease = fixture.accounts.acquireStateLease(scope(account), "one");
            fixture.putSite(
                    new SiteContracts.Site(
                            "site",
                            2,
                            2,
                            "Site",
                            ORIGIN,
                            Set.of(ORIGIN),
                            SiteContracts.SiteCredential.none(),
                            Optional.empty(),
                            false,
                            CLOCK.instant()),
                    1);
            assertThrows(
                    SecurityException.class,
                    () -> fixture.accounts.saveState(lease, identity("revoked", 0), STATE.clone()));
            var wrong = new AccountScope(
                    WorkspaceId.parse("63684c58-ea67-4651-a774-319df35c435f"), "site", account.accountId());
            assertThrows(PersistenceException.class, () -> fixture.accounts.acquireStateLease(wrong, "other"));
        }
    }

    @Test
    void 默认选择显式切换且非法秘密不会持久化() throws Exception {
        try (Fixture fixture = fixture(false)) {
            AccountProjection first = fixture.create("工作", "create-1");
            AccountProjection second = fixture.create("个人", "create-2");
            fixture.control(second, "account/default", "default");
            List<AccountProjection> listed =
                    fixture.accounts.list(WORKSPACE, "site").accounts();
            assertEquals(
                    List.of(second.accountId()),
                    listed.stream()
                            .filter(AccountProjection::defaultAccount)
                            .map(AccountProjection::accountId)
                            .toList());
            byte[] invalid = "not-a-private-login-bundle".getBytes(StandardCharsets.UTF_8);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.accounts.setCredential(
                            scope(first), first.securityRevision(), identity("bad", first.revision()), invalid));
            assertArrayEquals(new byte[invalid.length], invalid);
            assertEquals(0, fixture.vault.status().credentialCount());
        }
    }

    @Test
    void 阻塞密码消费不会阻止并发注销并且迟到结果失效() throws Exception {
        assertConcurrentRevocation(false);
    }

    @Test
    void 阻塞登录态消费不会阻止并发注销并且迟到结果失效() throws Exception {
        assertConcurrentRevocation(true);
    }

    private void assertConcurrentRevocation(boolean storageState) throws Exception {
        try (Fixture fixture = fixture(storageState);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AccountProjection account = storageState
                    ? fixture.accounts.list(WORKSPACE, "site").accounts().getFirst()
                    : fixture.create("工作", "create");
            if (!storageState) {
                account = fixture.accounts.setCredential(
                        scope(account),
                        account.securityRevision(),
                        identity("password", account.revision()),
                        "u\0p".getBytes(StandardCharsets.UTF_8));
            }
            AccountProjection selected = account;
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch released = new CountDownLatch(1);
            AtomicReference<byte[]> consumed = new AtomicReference<>();
            var read = executor.submit(() -> {
                com.javaclaw.server.security.vault.SecretOperation<Integer> blocked = bytes -> {
                    consumed.set(bytes);
                    entered.countDown();
                    assertTrue(released.await(10, TimeUnit.SECONDS));
                    return bytes.length;
                };
                return storageState
                        ? fixture.accounts.useState(scope(selected), selected.securityRevision(), blocked)
                        : fixture.accounts.useCredential(scope(selected), selected.securityRevision(), blocked);
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                // 注销必须在 callback 仍等待 Worker 时完成，证明没有持有账号锁或 Vault 锁。
                var logout = executor.submit(() -> fixture.control(selected, "account/logout", "logout"));
                assertEquals(
                        selected.securityRevision() + 1,
                        logout.get(5, TimeUnit.SECONDS).securityRevision());
            } finally {
                released.countDown();
            }
            ExecutionException failure = assertThrows(ExecutionException.class, () -> read.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof PersistenceException);
            assertArrayEquals(new byte[consumed.get().length], consumed.get());
        }
    }

    @Test
    void 领域提交失败时账号密码与清除登录态一起回滚() throws Exception {
        try (Fixture fixture = fixture(true)) {
            var scoped = fixture.vault.scopedCredentials();
            var state = fixture.vault.listMetadata("browser").getFirst();
            CommandIdentity save = identity("atomic-failure", 0);
            try (var password = scoped.prepare("site-login", Optional.empty(), new byte[] {1, 2});
                    var clearState = scoped.prepareClear(state)) {
                assertThrows(
                        IllegalStateException.class,
                        () -> scoped.commit(
                                save, List.of(password, clearState), AccountProjection.class, connection -> {
                                    throw new IllegalStateException("账号文档 CAS 失败");
                                }));
                assertTrue(
                        fixture.vault.metadata(password.metadata().reference()).isEmpty());
                assertEquals(Optional.of(state), fixture.vault.metadata(state.reference()));
                assertArrayEquals(STATE, fixture.vault.use(state.reference(), byte[]::clone));
                assertTrue(scoped.recover(save, AccountProjection.class).isEmpty());
            }
        }
    }

    @Test
    void 直接Vault轮换旧绑定不能复用且仅撤销拥有该引用的账号() throws Exception {
        try (Fixture fixture = fixture(true)) {
            AccountProjection account =
                    fixture.accounts.list(WORKSPACE, "site").accounts().getFirst();
            AccountProjection other = fixture.create("另一个", "create");
            var lease = fixture.accounts.acquireStateLease(scope(account), "one");
            var otherLease = fixture.accounts.acquireStateLease(scope(other), "two");
            List<AccountScope> changes = new ArrayList<>();
            fixture.accounts.onSecurityChanged(changes::add);
            var state = fixture.vault.listMetadata("browser").getFirst();
            fixture.vault.rotate(identity("external-state", state.revision()), state.reference(), STATE.clone());
            assertEquals(List.of(scope(account)), changes);
            assertThrows(
                    SecurityException.class,
                    () -> fixture.accounts.useState(scope(account), account.securityRevision(), byte[]::clone));
            assertThrows(
                    SecurityException.class,
                    () -> fixture.accounts.saveState(lease, identity("late", 0), STATE.clone()));
            assertTrue(fixture.accounts
                    .saveState(otherLease, identity("other", 0), STATE.clone())
                    .loginStateConfigured());
        }
    }

    @Test
    void 旧Origin密码确认不能写入改域后的网站() throws Exception {
        try (Fixture fixture = fixture(false)) {
            AccountProjection account = fixture.create("工作", "create");
            URI changed = URI.create("https://different.example.com");
            fixture.putSite(
                    new SiteContracts.Site(
                            "site",
                            2,
                            2,
                            "Site",
                            changed,
                            Set.of(changed),
                            SiteContracts.SiteCredential.none(),
                            Optional.empty(),
                            true,
                            CLOCK.instant()),
                    1);
            byte[] pending = "u\0p".getBytes(StandardCharsets.UTF_8);
            assertThrows(
                    SecurityException.class,
                    () -> fixture.accounts.setCredential(
                            scope(account),
                            account.securityRevision(),
                            1,
                            identity("old-confirmation", account.revision()),
                            pending));
            assertArrayEquals(new byte[pending.length], pending);
            assertEquals(0, fixture.vault.status().credentialCount());
            assertThrows(
                    SecurityException.class,
                    () -> fixture.accounts.setCredential(
                            scope(account),
                            account.securityRevision(),
                            identity("old-internal", account.revision()),
                            "u\0p".getBytes(StandardCharsets.UTF_8)));
            var saved = fixture.accounts.setCredential(
                    scope(account),
                    account.securityRevision(),
                    2,
                    identity("new-confirmation", account.revision()),
                    "u\0p".getBytes(StandardCharsets.UTF_8));
            assertTrue(saved.passwordConfigured());
            assertEquals(1, fixture.vault.status().credentialCount());
        }
    }

    @Test
    void 确认网页登录时密码和Cookie原子保存且旧写租约失效() throws Exception {
        try (Fixture fixture = fixture(true)) {
            AccountProjection before =
                    fixture.accounts.list(WORKSPACE, "site").accounts().getFirst();
            var lease = fixture.accounts.acquireStateLease(scope(before), "login-session");
            byte[] credentials = "u\0p".getBytes(StandardCharsets.UTF_8);
            byte[] state = STATE.clone();
            CommandIdentity save = identity("combined-login", before.revision());
            AccountProjection saved = fixture.accounts.setLogin(lease, save, credentials, state);
            assertTrue(saved.passwordConfigured());
            assertTrue(saved.loginStateConfigured());
            assertEquals(before.securityRevision() + 1, saved.securityRevision());
            assertEquals(before.stateRevision() + 1, saved.stateRevision());
            assertEquals(2, fixture.vault.status().credentialCount());
            assertArrayEquals(new byte[credentials.length], credentials);
            assertArrayEquals(new byte[state.length], state);
            assertArrayEquals(STATE, fixture.accounts.useState(scope(saved), saved.securityRevision(), byte[]::clone));
            assertEquals(saved, fixture.accounts.setLogin(lease, save, new byte[] {1}, new byte[] {2}));
            assertThrows(
                    SecurityException.class,
                    () -> fixture.accounts.setLogin(
                            lease,
                            identity("late-combined", saved.revision()),
                            "u\0p".getBytes(StandardCharsets.UTF_8),
                            STATE.clone()));
        }
    }

    @Test
    void 保存时间来自秘密元数据而重命名不改写时间且注销清除登录态时间() throws Exception {
        try (Fixture fixture = fixture(false)) {
            AccountProjection account = fixture.create("工作", "times-create");
            var empty = new SiteAccountContracts.AccountSavedTimes(Optional.empty(), Optional.empty());
            assertEquals(
                    empty, fixture.accounts.list(WORKSPACE, "site").savedTimes().get(account.accountId()));
            AccountProjection password = fixture.accounts.setCredential(
                    scope(account),
                    account.securityRevision(),
                    identity("times-password", account.revision()),
                    "u\0p".getBytes(StandardCharsets.UTF_8));
            var passwordTime =
                    new SiteAccountContracts.AccountSavedTimes(Optional.of(CLOCK.instant()), Optional.empty());
            assertEquals(
                    passwordTime,
                    fixture.accounts.list(WORKSPACE, "site").savedTimes().get(account.accountId()));
            var lease = fixture.accounts.acquireStateLease(scope(password), "times-session");
            AccountProjection saved = fixture.accounts.saveState(lease, identity("times-state", 0), STATE.clone());
            fixture.accounts.releaseStateLease(lease);
            Clock later = Clock.offset(CLOCK, Duration.ofMinutes(5));
            SiteAccountService renamedService =
                    new SiteAccountService(fixture.database, fixture.vault, fixture.json, later);
            AccountProjection renamed = renamedService.command(
                    WORKSPACE,
                    "account/update",
                    fixture.json.encode(new SiteAccountContracts.UpdateRequest(selection(saved), "重命名", true)),
                    identity("times-rename", saved.revision()));
            assertEquals(later.instant(), renamed.updatedAt());
            var expected = new SiteAccountContracts.AccountSavedTimes(
                    Optional.of(CLOCK.instant()), Optional.of(CLOCK.instant()));
            assertEquals(
                    expected,
                    renamedService.list(WORKSPACE, "site").savedTimes().get(account.accountId()));
            renamedService.command(
                    WORKSPACE,
                    "account/logout",
                    fixture.json.encode(selection(renamed)),
                    identity("times-logout", renamed.revision()));
            assertEquals(
                    passwordTime,
                    renamedService.list(WORKSPACE, "site").savedTimes().get(account.accountId()));
        }
    }

    private Fixture fixture(boolean legacy) throws Exception {
        CanonicalJson json = new CanonicalJson();
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        SecretVaultService vault =
                new SecretVaultService(database, new MemoryProtector(), json, CLOCK, new SecureRandom());
        SiteContracts.SiteCredential credential = SiteContracts.SiteCredential.none();
        if (legacy) {
            CredentialMetadata state = vault.create(identity("legacy", 0), "browser", STATE.clone());
            credential = new SiteContracts.SiteCredential(
                    SiteContracts.CredentialKind.BROWSER_STORAGE, Optional.of(state.reference()), Optional.empty());
        }
        Fixture fixture = new Fixture(database, vault, json, new SiteAccountService(database, vault, json, CLOCK));
        fixture.putSite(
                new SiteContracts.Site(
                        "site",
                        1,
                        1,
                        "Site",
                        ORIGIN,
                        Set.of(ORIGIN),
                        credential,
                        Optional.empty(),
                        true,
                        CLOCK.instant()),
                0);
        return fixture;
    }

    private static CommandIdentity identity(String key, long revision) {
        return new CommandIdentity("site-account-test/" + key, key, revision, "a".repeat(64));
    }

    private static AccountScope scope(AccountProjection account) {
        return new AccountScope(WORKSPACE, account.siteId(), account.accountId());
    }

    private static SiteAccountContracts.Selection selection(AccountProjection account) {
        return new SiteAccountContracts.Selection(account.siteId(), account.accountId());
    }

    private record Fixture(
            H2Database database, SecretVaultService vault, CanonicalJson json, SiteAccountService accounts)
            implements AutoCloseable {
        private AccountProjection create(String name, String key) {
            return accounts.command(
                    WORKSPACE,
                    "account/create",
                    json.encode(new SiteAccountContracts.CreateRequest("site", name)),
                    identity(key, 0));
        }

        private AccountProjection control(AccountProjection account, String operation, String key) {
            return accounts.command(
                    WORKSPACE, operation, json.encode(selection(account)), identity(key, account.revision()));
        }

        private void putSite(SiteContracts.Site site, long expected) throws Exception {
            new H2ManagedExtensionStore(database, CLOCK)
                    .inTransaction(new ExtensionId(BuiltinExtensionIds.SITE), tx -> {
                        tx.put("documents." + WORKSPACE.value(), site.id(), expected, json.encode(site));
                        return null;
                    });
        }

        @Override
        public void close() {
            vault.close();
        }
    }

    private static final class MemoryProtector implements MasterKeyProtector {
        private final Map<String, byte[]> keys = new HashMap<>();

        @Override
        public Optional<byte[]> load(String keyId) {
            return Optional.ofNullable(keys.get(keyId)).map(byte[]::clone);
        }

        @Override
        public void store(String keyId, byte[] key) {
            keys.put(keyId, key.clone());
        }

        @Override
        public void delete(String keyId) {
            byte[] removed = keys.remove(keyId);
            if (removed != null) {
                Arrays.fill(removed, (byte) 0);
            }
        }
    }
}
