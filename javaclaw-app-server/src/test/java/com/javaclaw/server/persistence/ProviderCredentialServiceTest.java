package com.javaclaw.server.persistence;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentProfileSpec;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderCredentialBinding;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderReadiness;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.nativehost.credential.MasterKeyProtector;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.ProviderEndpointTestFixtures;
import com.javaclaw.server.security.vault.SecretVaultService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderCredentialServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T08:00:00Z"), ZoneOffset.UTC);
    private static final String SECRET = "PROVIDER-ATOMIC-SECRET-DO-NOT-LEAK";

    @TempDir
    Path temporaryDirectory;

    @Test
    void 候选Adapter在提交前读取新Secret且绑定轮换清除均原子提交() {
        Fixture fixture = fixture(new ProviderCredentialTransactionPort(new CanonicalJson()));
        AtomicReference<String> preparedSecret = new AtomicReference<>();
        fixture.providers()
                .participate(candidate -> candidate
                        .spec()
                        .credential()
                        .map(reference ->
                                fixture.vault().use(reference, bytes -> preparedChange(preparedSecret, bytes)))
                        .orElseGet(() -> trackedChange(new AtomicInteger())));

        byte[] first = SECRET.getBytes(StandardCharsets.UTF_8);
        ProviderCredentialBinding bound = fixture.service().set(identity("set-1", 1), "provider-main", 1, 0, first);
        assertTrue(allZero(first));
        assertEquals(2, bound.provider().revision());
        assertEquals(1, bound.credential().revision());
        assertEquals(SECRET, preparedSecret.get());
        assertEquals(SECRET, fixture.vault().use(bound.credential().reference(), ProviderCredentialServiceTest::text));
        assertEquals(
                ProviderReadiness.DISABLED,
                fixture.providers()
                        .probe(new ProviderRef(
                                bound.provider().id(), bound.provider().revision(), "test-model"))
                        .readiness());

        byte[] second = "rotated-provider-secret".getBytes(StandardCharsets.UTF_8);
        ProviderCredentialBinding rotated = fixture.service()
                .set(
                        identity("set-2", 2),
                        "provider-main",
                        2,
                        bound.credential().revision(),
                        second);
        assertTrue(allZero(second));
        assertEquals(3, rotated.provider().revision());
        assertEquals(2, rotated.credential().revision());
        assertEquals(
                "rotated-provider-secret",
                fixture.vault().use(rotated.credential().reference(), ProviderCredentialServiceTest::text));

        var cleared = fixture.service()
                .clear(
                        clearIdentity("clear", 3),
                        "provider-main",
                        3,
                        rotated.credential().reference(),
                        2);
        assertEquals(4, cleared.provider().revision());
        assertTrue(cleared.provider().spec().credential().isEmpty());
        assertTrue(fixture.vault().metadata(rotated.credential().reference()).isEmpty());
        fixture.vault().close();
    }

    @Test
    void Provider写入失败会回滚Vault版本和幂等回执并释放候选Adapter() {
        CanonicalJson json = new CanonicalJson();
        AtomicInteger failures = new AtomicInteger(1);
        ProviderCredentialTransactionPort port = new ProviderCredentialTransactionPort(json, () -> {
            if (failures.getAndDecrement() > 0) {
                throw new IllegalStateException("injected provider insert failure");
            }
        });
        Fixture fixture = fixture(port);
        AtomicInteger discarded = new AtomicInteger();
        fixture.providers().participate(candidate -> trackedChange(discarded));
        CommandIdentity identity = identity("retry-after-rollback", 1);
        byte[] failedSecret = SECRET.getBytes(StandardCharsets.UTF_8);

        assertThrows(
                IllegalStateException.class,
                () -> fixture.service().set(identity, "provider-main", 1, 0, failedSecret));
        assertTrue(allZero(failedSecret));
        assertEquals(1, fixture.providers().listAllVersions().size());
        assertEquals(0, fixture.vault().status().credentialCount());
        assertTrue(fixture.service().recoverSet(identity).isEmpty());
        assertEquals(1, discarded.get());

        ProviderCredentialBinding retried =
                fixture.service().set(identity, "provider-main", 1, 0, SECRET.getBytes(StandardCharsets.UTF_8));
        assertEquals(2, retried.provider().revision());
        assertEquals(1, fixture.vault().status().credentialCount());
        fixture.vault().close();
    }

    @Test
    void 候选Adapter构造失败不会提交Provider或Vault() {
        Fixture fixture = fixture(new ProviderCredentialTransactionPort(new CanonicalJson()));
        fixture.providers()
                .participate(candidate -> fixture.vault()
                        .use(candidate.spec().credential().orElseThrow(), ignored -> {
                            throw new IllegalStateException("injected adapter preparation failure");
                        }));
        CommandIdentity identity = identity("adapter-rejected", 1);
        byte[] secret = SECRET.getBytes(StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, () -> fixture.service().set(identity, "provider-main", 1, 0, secret));
        assertTrue(allZero(secret));
        assertEquals(1, fixture.providers().listAllVersions().size());
        assertEquals(0, fixture.vault().status().credentialCount());
        assertTrue(fixture.service().recoverSet(identity).isEmpty());
        fixture.vault().close();
    }

    @Test
    void 清除发现其他权威Provider引用时原子拒绝并保留Secret() throws Exception {
        Fixture fixture = fixture(new ProviderCredentialTransactionPort(new CanonicalJson()));
        ProviderCredentialBinding binding = fixture.service()
                .set(identity("bind-shared-check", 1), "provider-main", 1, 0, SECRET.getBytes(StandardCharsets.UTF_8));
        ProviderEndpoint shadow = new ProviderEndpoint(
                "provider-shadow",
                1,
                ProviderLifecycle.ACTIVE,
                binding.provider().spec(),
                CLOCK.instant(),
                CLOCK.instant());
        new H2Transactions(fixture.database()).execute(connection -> {
            new ProviderCredentialTransactionPort(new CanonicalJson()).insert(connection, shadow);
            return null;
        });

        assertThrows(
                PersistenceException.class,
                () -> fixture.service()
                        .clear(
                                clearIdentity("reject-shared-clear", 2),
                                "provider-main",
                                2,
                                binding.credential().reference(),
                                1));
        assertTrue(fixture.vault().metadata(binding.credential().reference()).isPresent());
        assertEquals(2, fixture.providers().require("provider-main", 2).revision());
        fixture.vault().close();
    }

    @Test
    void 已提交命令重放和重启恢复都不再需要Secret明文() {
        MemoryProtector protector = new MemoryProtector();
        CanonicalJson json = new CanonicalJson();
        H2Database database = database();
        CommandIdentity identity = identity("restart-replay", 1);
        ProviderCredentialBinding committed;
        try (SecretVaultService vault = vault(database, protector, json)) {
            ProviderService providers = provider(database, vault, json);
            committed = new ProviderCredentialService(providers, vault.providerCredentials(), json, CLOCK)
                    .set(identity, "provider-main", 1, 0, SECRET.getBytes(StandardCharsets.UTF_8));
            assertEquals(
                    committed,
                    new ProviderCredentialService(providers, vault.providerCredentials(), json, CLOCK)
                            .set(identity, "provider-main", 1, 0, null));
        }

        try (SecretVaultService restarted = vault(database, protector, json)) {
            ProviderCredentialService service = new ProviderCredentialService(
                    new ProviderService(database, restarted, json, CLOCK),
                    restarted.providerCredentials(),
                    json,
                    CLOCK);
            assertEquals(committed, service.recoverSet(identity).orElseThrow());
            assertEquals(1, restarted.status().credentialCount());
        }
    }

    @Test
    void H2与异常信息均不包含ProviderSecret明文() throws Exception {
        Fixture fixture = fixture(new ProviderCredentialTransactionPort(new CanonicalJson()));
        fixture.service()
                .set(identity("plaintext-scan", 1), "provider-main", 1, 0, SECRET.getBytes(StandardCharsets.UTF_8));
        byte[] staleSecret = SECRET.getBytes(StandardCharsets.UTF_8);
        PersistenceException failure = assertThrows(
                PersistenceException.class,
                () -> fixture.service().set(identity("stale", 1), "provider-main", 1, 0, staleSecret));
        assertTrue(allZero(staleSecret));
        assertFalse(failure.toString().contains(SECRET));
        fixture.vault().close();

        try (var paths = Files.walk(temporaryDirectory.resolve("data-v5"))) {
            assertTrue(paths.filter(Files::isRegularFile).noneMatch(this::containsPlaintext));
        }
    }

    @Test
    void Credential实时失效会拒绝新Profile与已有Profile启动Turn() {
        Fixture fixture = fixture(new ProviderCredentialTransactionPort(new CanonicalJson()));
        ProviderCredentialBinding binding = fixture.service()
                .set(
                        identity("bind-profile-provider", 1),
                        "provider-main",
                        1,
                        0,
                        SECRET.getBytes(StandardCharsets.UTF_8));
        ProviderEndpoint active = fixture.providers()
                .update(
                        new CommandIdentity("provider/update", "enable-profile-provider", 2, "a".repeat(64)),
                        binding.provider().id(),
                        binding.provider().spec(),
                        ProviderLifecycle.ACTIVE);
        CanonicalJson json = new CanonicalJson();
        PermissionProfileService permissions = new PermissionProfileService(fixture.database(), json, CLOCK);
        permissions.installStandardProfile();
        AgentProfileService profiles =
                new AgentProfileService(fixture.database(), fixture.providers(), permissions, json, CLOCK);
        AgentProfileSpec profileSpec = new AgentProfileSpec(
                "Default",
                "执行用户任务。",
                new ProviderRef(active.id(), active.revision(), "test-model"),
                new PermissionProfileRef("standard", 1),
                Set.of(),
                new TurnBudget(4_000, 1_000, 4, 0, Duration.ofMinutes(1)));
        AgentProfile created = profiles.create(
                new CommandIdentity("profile/create", "profile-ready", 0, "b".repeat(64)),
                "profile-ready",
                profileSpec);

        fixture.vault().close();

        assertEquals(
                ProviderReadiness.CREDENTIAL_UNAVAILABLE,
                fixture.providers().probe(profileSpec.provider()).readiness());
        assertThrows(
                PersistenceException.class,
                () -> profiles.create(
                        new CommandIdentity("profile/create", "profile-rejected", 0, "c".repeat(64)),
                        "profile-rejected",
                        profileSpec));
        assertThrows(PersistenceException.class, () -> profiles.requireAvailable(created.id(), created.revision()));
    }

    private Fixture fixture(ProviderCredentialTransactionPort port) {
        CanonicalJson json = new CanonicalJson();
        H2Database database = database();
        SecretVaultService vault = vault(database, new MemoryProtector(), json);
        ProviderService providers = provider(database, vault, json);
        ProviderCredentialService service =
                new ProviderCredentialService(providers, vault.providerCredentials(), port, CLOCK);
        return new Fixture(database, providers, vault, service);
    }

    private ProviderService provider(H2Database database, CredentialAvailabilityPort credentials, CanonicalJson json) {
        ProviderService providers = new ProviderService(database, credentials, json, CLOCK);
        providers.create(identity("create-provider", 0), "provider-main", providerSpec(), ProviderLifecycle.DISABLED);
        return providers;
    }

    private H2Database database() {
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v5"));
        database.initialize();
        return database;
    }

    private static SecretVaultService vault(H2Database database, MemoryProtector protector, CanonicalJson json) {
        return new SecretVaultService(database, protector, json, CLOCK, new SecureRandom());
    }

    private static ProviderEndpointSpec providerSpec() {
        return ProviderEndpointTestFixtures.apiKeyChat("Provider", ProviderAdapter.OPENAI_COMPATIBLE, "test-model");
    }

    private static ProviderService.PreparedProviderChange preparedChange(
            AtomicReference<String> captured, byte[] bytes) {
        captured.set(text(bytes));
        return trackedChange(new AtomicInteger());
    }

    private static ProviderService.PreparedProviderChange trackedChange(AtomicInteger discarded) {
        return new ProviderService.PreparedProviderChange() {
            private boolean pending = true;

            @Override
            public void activate() {
                pending = false;
            }

            @Override
            public void close() {
                if (pending) {
                    pending = false;
                    discarded.incrementAndGet();
                }
            }
        };
    }

    private boolean containsPlaintext(Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1).contains(SECRET);
        } catch (Exception failure) {
            throw new AssertionError("无法扫描测试数据库", failure);
        }
    }

    private static CommandIdentity identity(String key, long providerRevision) {
        return new CommandIdentity(
                providerRevision == 0 ? "provider/create" : "provider/credential/set",
                key,
                providerRevision,
                "a".repeat(64));
    }

    private static CommandIdentity clearIdentity(String key, long providerRevision) {
        return new CommandIdentity("provider/credential/clear", key, providerRevision, "a".repeat(64));
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static boolean allZero(byte[] bytes) {
        for (byte value : bytes) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private record Fixture(
            H2Database database,
            ProviderService providers,
            SecretVaultService vault,
            ProviderCredentialService service) {}

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
