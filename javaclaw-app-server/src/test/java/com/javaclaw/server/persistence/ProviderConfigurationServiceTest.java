package com.javaclaw.server.persistence;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderConfiguration;
import com.javaclaw.api.ProviderConfigurationResult;
import com.javaclaw.api.ProviderConnectionSpec;
import com.javaclaw.api.ProviderCredentialChange;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.server.ProviderEndpointTestFixtures;
import com.javaclaw.server.security.vault.SecretVaultService;

import static com.javaclaw.server.persistence.ProviderConfigurationTestFixture.CLOCK;
import static com.javaclaw.server.persistence.ProviderConfigurationTestFixture.SECRET;
import static com.javaclaw.server.persistence.ProviderConfigurationTestFixture.apiKey;
import static com.javaclaw.server.persistence.ProviderConfigurationTestFixture.cleared;
import static com.javaclaw.server.persistence.ProviderConfigurationTestFixture.identity;
import static com.javaclaw.server.persistence.ProviderConfigurationTestFixture.none;
import static com.javaclaw.server.persistence.ProviderConfigurationTestFixture.secret;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderConfigurationServiceTest {
    @TempDir
    Path directory;

    @Test
    void 新建配置和密钥直接原子成为启用版本且候选构造时尚未写入数据库() {
        try (var fixture = new ProviderConfigurationTestFixture(directory)) {
            AtomicInteger activated = new AtomicInteger();
            AtomicReference<String> candidateSecret = new AtomicReference<>();
            fixture.providers.participate(candidate -> {
                assertTrue(fixture.providers.listLatest().isEmpty());
                assertEquals(0, fixture.vault.status().credentialCount());
                candidateSecret.set(fixture.vault.use(
                        candidate.spec().credential().orElseThrow(),
                        bytes -> new String(bytes, StandardCharsets.UTF_8)));
                return tracked(activated, new AtomicInteger());
            });
            byte[] plaintext = secret();

            var saved = fixture.service.save(
                    identity("create", 0), apiKey(0, ProviderCredentialChange.REPLACE, 0), plaintext);

            assertTrue(cleared(plaintext));
            assertEquals(SECRET, candidateSecret.get());
            assertEquals(1, activated.get());
            assertEquals(1, saved.provider().revision());
            assertEquals(ProviderLifecycle.ACTIVE, saved.provider().lifecycle());
            assertEquals(1, fixture.providers.listAllVersions().size());
            assertEquals(1, fixture.vault.status().credentialCount());
            assertEquals(saved, fixture.service.recover(identity("create", 0)).orElseThrow());
        }
    }

    @Test
    void 无鉴权完整保存不创建凭据且幂等重放不再增加版本() {
        try (var fixture = new ProviderConfigurationTestFixture(directory)) {
            var request = none(0, ProviderCredentialChange.KEEP, 0);
            var command = identity("no-secret", 0);
            var saved = fixture.service.save(command, request, null);

            assertEquals(saved, fixture.service.save(command, request, null));
            assertTrue(saved.credential().isEmpty());
            assertEquals(0, fixture.vault.status().credentialCount());
            assertEquals(1, fixture.providers.listAllVersions().size());
        }
    }

    @Test
    void 启用无鉴权服务原子改成APIKey且轮换只产生一个新配置版本() {
        try (var fixture = new ProviderConfigurationTestFixture(directory)) {
            fixture.service.save(identity("none", 0), none(0, ProviderCredentialChange.KEEP, 0), null);

            var keyed = fixture.service.save(
                    identity("add-key", 1), apiKey(1, ProviderCredentialChange.REPLACE, 0), secret());
            var rotated = fixture.service.save(
                    identity("rotate", 2), apiKey(2, ProviderCredentialChange.REPLACE, 1), secret());

            assertEquals(ProviderLifecycle.ACTIVE, keyed.provider().lifecycle());
            assertEquals(2, keyed.provider().revision());
            assertEquals(3, rotated.provider().revision());
            assertEquals(2, rotated.credential().orElseThrow().revision());
            assertEquals(
                    keyed.credential().orElseThrow().reference(),
                    rotated.credential().orElseThrow().reference());
            assertEquals(3, fixture.providers.listAllVersions().size());
            assertTrue(fixture.providers.listAllVersions().stream()
                    .allMatch(endpoint -> endpoint.lifecycle() == ProviderLifecycle.ACTIVE));
        }
    }

    @Test
    void 保留密钥需要精确凭据版本且不会再次写入Vault() {
        try (var fixture = new ProviderConfigurationTestFixture(directory)) {
            var keyed = fixture.service.save(
                    identity("keyed", 0), apiKey(0, ProviderCredentialChange.REPLACE, 0), secret());

            assertThrows(
                    PersistenceException.class,
                    () -> fixture.service.save(
                            identity("wrong-credential", 1), apiKey(1, ProviderCredentialChange.KEEP, 2), null));
            var kept = fixture.service.save(identity("keep", 1), apiKey(1, ProviderCredentialChange.KEEP, 1), null);

            assertEquals(keyed.credential(), kept.credential());
            assertEquals(2, kept.provider().revision());
            assertEquals(1, fixture.vault.status().credentialCount());
            assertTrue(fixture.service.recover(identity("wrong-credential", 1)).isEmpty());
        }
    }

    @Test
    void 改为无鉴权并明确清除密钥在一个事务内完成() {
        try (var fixture = new ProviderConfigurationTestFixture(directory)) {
            var keyed = fixture.service.save(
                    identity("keyed", 0), apiKey(0, ProviderCredentialChange.REPLACE, 0), secret());

            var result = fixture.service.save(identity("clear", 1), none(1, ProviderCredentialChange.CLEAR, 1), null);

            assertEquals(2, result.provider().revision());
            assertEquals(ProviderLifecycle.ACTIVE, result.provider().lifecycle());
            assertTrue(result.credential().isEmpty());
            assertTrue(result.provider().spec().credential().isEmpty());
            assertTrue(fixture.vault
                    .metadata(keyed.credential().orElseThrow().reference())
                    .isEmpty());
            assertEquals(0, fixture.vault.status().credentialCount());
        }
    }

    @Test
    void 数据库失败回滚配置密钥和回执且释放候选资源() {
        AtomicInteger failures = new AtomicInteger(1);
        try (var fixture = new ProviderConfigurationTestFixture(directory, () -> {
            if (failures.getAndDecrement() > 0) {
                throw new IllegalStateException("测试写入失败");
            }
        })) {
            AtomicInteger discarded = new AtomicInteger();
            AtomicInteger activated = new AtomicInteger();
            fixture.providers.participate(candidate -> tracked(activated, discarded));
            byte[] plaintext = secret();
            var command = identity("retry", 0);
            var request = apiKey(0, ProviderCredentialChange.REPLACE, 0);

            assertThrows(IllegalStateException.class, () -> fixture.service.save(command, request, plaintext));

            assertTrue(cleared(plaintext));
            assertTrue(fixture.providers.listLatest().isEmpty());
            assertEquals(0, fixture.vault.status().credentialCount());
            assertTrue(fixture.service.recover(command).isEmpty());
            assertEquals(1, discarded.get());
            assertEquals(0, activated.get());
            assertEquals(
                    1,
                    fixture.service.save(command, request, secret()).provider().revision());
        }
    }

    @Test
    void Adapter准备失败不会保留Provider密钥或成功回执() {
        try (var fixture = new ProviderConfigurationTestFixture(directory)) {
            fixture.providers.participate(candidate -> {
                throw new IllegalStateException("候选 Adapter 构造失败");
            });
            byte[] plaintext = secret();
            var command = identity("prepare-failed", 0);

            assertThrows(
                    IllegalStateException.class,
                    () -> fixture.service.save(command, apiKey(0, ProviderCredentialChange.REPLACE, 0), plaintext));

            assertTrue(cleared(plaintext));
            assertTrue(fixture.providers.listLatest().isEmpty());
            assertEquals(0, fixture.vault.status().credentialCount());
            assertTrue(fixture.service.recover(command).isEmpty());
        }
    }

    @Test
    void 重复创建过期编辑和归档对象都拒绝且不覆盖已有配置() {
        try (var fixture = new ProviderConfigurationTestFixture(directory)) {
            var saved = fixture.service.save(identity("first", 0), none(0, ProviderCredentialChange.KEEP, 0), null);
            assertThrows(
                    PersistenceException.class,
                    () -> fixture.service.save(
                            identity("duplicate", 0), none(0, ProviderCredentialChange.KEEP, 0), null));
            assertThrows(
                    PersistenceException.class,
                    () -> fixture.service.save(identity("stale", 2), none(2, ProviderCredentialChange.KEEP, 0), null));
            var archived = fixture.providers.archive(
                    new CommandIdentity("provider/archive", "archive", 1, "b".repeat(64)),
                    saved.provider().id());
            byte[] plaintext = secret();

            assertThrows(
                    PersistenceException.class,
                    () -> fixture.service.save(
                            identity("archived", 2), apiKey(2, ProviderCredentialChange.REPLACE, 0), plaintext));

            assertTrue(cleared(plaintext));
            assertEquals(archived, fixture.providers.listLatest().getFirst());
            assertEquals(0, fixture.vault.status().credentialCount());
        }
    }

    @Test
    void 共享凭据清除失败时数据库回滚并保留原密钥() throws Exception {
        try (var fixture = new ProviderConfigurationTestFixture(directory)) {
            var saved = fixture.service.save(
                    identity("keyed", 0), apiKey(0, ProviderCredentialChange.REPLACE, 0), secret());
            ProviderEndpoint other = new ProviderEndpoint(
                    "provider-other",
                    1,
                    ProviderLifecycle.ACTIVE,
                    saved.provider().spec(),
                    CLOCK.instant(),
                    CLOCK.instant());
            new H2Transactions(fixture.database).execute(connection -> {
                new ProviderCredentialTransactionPort(fixture.json).insert(connection, other);
                return null;
            });

            assertThrows(
                    PersistenceException.class,
                    () -> fixture.service.save(
                            identity("clear-shared", 1), none(1, ProviderCredentialChange.CLEAR, 1), null));

            assertTrue(fixture.vault
                    .metadata(saved.credential().orElseThrow().reference())
                    .isPresent());
            assertEquals(2, fixture.providers.listAllVersions().size());
            assertTrue(fixture.service.recover(identity("clear-shared", 1)).isEmpty());
        }
    }

    @Test
    void 已提交结果在服务重启和Vault关闭后仍可恢复且摘要冲突拒绝() {
        try (var fixture = new ProviderConfigurationTestFixture(directory)) {
            var command = identity("restart", 0);
            ProviderConfigurationResult saved =
                    fixture.service.save(command, apiKey(0, ProviderCredentialChange.REPLACE, 0), secret());
            assertEquals(saved, fixture.service.save(command, apiKey(0, ProviderCredentialChange.REPLACE, 0), null));
            fixture.vault.close();

            assertEquals(saved, fixture.service.recover(command).orElseThrow());
            try (var restarted = new SecretVaultService(
                    fixture.database, fixture.protector, fixture.json, CLOCK, new SecureRandom())) {
                var providers = new ProviderService(fixture.database, restarted, fixture.json, CLOCK);
                var service = new ProviderConfigurationService(
                        fixture.database,
                        providers,
                        restarted.providerCredentials(),
                        restarted::metadata,
                        fixture.json,
                        CLOCK);
                assertEquals(saved, service.recover(command).orElseThrow());
                assertEquals(1, providers.listAllVersions().size());
                assertThrows(
                        PersistenceException.class,
                        () -> service.recover(new CommandIdentity(
                                command.method(),
                                command.idempotencyKey(),
                                command.expectedRevision(),
                                "f".repeat(64))));
            }
        }
    }

    @Test
    void 非保存身份或不一致版本拒绝时仍清理传入的秘密() {
        try (var fixture = new ProviderConfigurationTestFixture(directory)) {
            byte[] plaintext = secret();
            assertThrows(
                    PersistenceException.class,
                    () -> fixture.service.save(
                            new CommandIdentity("provider/update", "wrong-method", 0, "a".repeat(64)),
                            apiKey(0, ProviderCredentialChange.REPLACE, 0),
                            plaintext));
            assertTrue(cleared(plaintext));
            byte[] mismatch = secret();
            assertThrows(
                    PersistenceException.class,
                    () -> fixture.service.save(
                            identity("wrong-revision", 1), apiKey(0, ProviderCredentialChange.REPLACE, 0), mismatch));
            assertTrue(cleared(mismatch));
            assertFalse(fixture.service.recover(identity("missing", 0)).isPresent());
        }
    }

    @Test
    void 凭据在预构造期间变化时最终锁内CAS拒绝旧KEEP并释放候选() {
        try (var fixture = new ProviderConfigurationTestFixture(directory)) {
            var saved = fixture.service.save(
                    identity("initial", 0), apiKey(0, ProviderCredentialChange.REPLACE, 0), secret());
            var reference = saved.credential().orElseThrow().reference();
            AtomicInteger discarded = new AtomicInteger();
            fixture.providers.participate(candidate -> {
                // 在预检查与最终提交之间注入独立凭据版本变化，验证最终 Vault 锁内仍检查精确版本。
                byte[] replacement = secret();
                try (var changed =
                        fixture.vault.providerCredentials().prepare(Optional.of(reference), 1, replacement)) {
                    fixture.vault
                            .providerCredentials()
                            .commit(
                                    identity("credential-race", 1),
                                    changed,
                                    CredentialMetadata.class,
                                    connection -> changed.metadata());
                } finally {
                    Arrays.fill(replacement, (byte) 0);
                }
                return tracked(new AtomicInteger(), discarded);
            });

            assertThrows(
                    PersistenceException.class,
                    () -> fixture.service.save(
                            identity("keep-after-race", 1), apiKey(1, ProviderCredentialChange.KEEP, 1), null));

            assertEquals(1, discarded.get());
            assertEquals(1, fixture.providers.listAllVersions().size());
            assertEquals(2, fixture.vault.metadata(reference).orElseThrow().revision());
            assertTrue(fixture.service.recover(identity("keep-after-race", 1)).isEmpty());
        }
    }

    @Test
    void 无凭据意图或无鉴权连接不能静默吞掉用户提交的秘密() {
        try (var fixture = new ProviderConfigurationTestFixture(directory)) {
            byte[] unexpected = secret();
            assertThrows(
                    PersistenceException.class,
                    () -> fixture.service.save(
                            identity("extra-secret", 0), none(0, ProviderCredentialChange.KEEP, 0), unexpected));
            assertTrue(cleared(unexpected));
            assertThrows(
                    PersistenceException.class,
                    () -> fixture.service.save(
                            identity("missing-secret", 0), apiKey(0, ProviderCredentialChange.REPLACE, 0), null));
            assertThrows(
                    PersistenceException.class,
                    () -> fixture.service.save(
                            identity("none-with-secret", 0), none(0, ProviderCredentialChange.REPLACE, 0), secret()));
            assertThrows(
                    PersistenceException.class,
                    () -> fixture.service.save(
                            identity("keyed-clear", 0), apiKey(0, ProviderCredentialChange.CLEAR, 0), null));
            assertTrue(fixture.providers.listLatest().isEmpty());
            assertEquals(0, fixture.vault.status().credentialCount());
        }
    }

    @Test
    void 未绑定凭据不能伪造旧凭据版本或直接启用APIKey连接() {
        try (var fixture = new ProviderConfigurationTestFixture(directory)) {
            assertThrows(
                    PersistenceException.class,
                    () -> fixture.service.save(
                            identity("clear-unbound", 0), none(0, ProviderCredentialChange.CLEAR, 0), null));
            assertThrows(
                    PersistenceException.class,
                    () -> fixture.service.save(
                            identity("unbound-revision", 0), apiKey(0, ProviderCredentialChange.REPLACE, 1), secret()));
            assertThrows(
                    PersistenceException.class,
                    () -> fixture.service.save(
                            identity("active-without-key", 0), apiKey(0, ProviderCredentialChange.KEEP, 0), null));
            assertTrue(fixture.providers.listLatest().isEmpty());
            assertEquals(0, fixture.vault.status().credentialCount());
        }
    }

    @Test
    void 无密钥保存失败也不留下配置或回执() {
        try (var fixture = new ProviderConfigurationTestFixture(directory, () -> {
            throw new IllegalStateException("无凭据配置写入失败");
        })) {
            var command = identity("none-failed", 0);
            assertThrows(
                    IllegalStateException.class,
                    () -> fixture.service.save(command, none(0, ProviderCredentialChange.KEEP, 0), null));
            assertTrue(fixture.providers.listLatest().isEmpty());
            assertTrue(fixture.service.recover(command).isEmpty());
        }
    }

    @Test
    void 保留密钥不能把已有秘密带到另一个地址或协议但允许调整显示和请求参数() {
        try (var fixture = new ProviderConfigurationTestFixture(directory)) {
            var saved = fixture.service.save(
                    identity("original", 0), apiKey(0, ProviderCredentialChange.REPLACE, 0), secret());
            var keep = apiKey(1, ProviderCredentialChange.KEEP, 1);
            ProviderConnectionSpec original = keep.connection();
            var otherAddress = new ProviderConnectionSpec(
                    original.displayName(),
                    original.adapter(),
                    Optional.of(URI.create("https://other.example.test/v1")),
                    original.authentication(),
                    original.timeout(),
                    original.maximumRetries(),
                    original.options());
            var otherAdapter = ProviderConnectionSpec.from(
                    ProviderEndpointTestFixtures.apiKeyChat("不同协议", ProviderAdapter.ANTHROPIC, "test-model"));

            assertThrows(
                    PersistenceException.class,
                    () -> fixture.service.save(identity("other-address", 1), withConnection(keep, otherAddress), null));
            assertThrows(
                    PersistenceException.class,
                    () -> fixture.service.save(identity("other-adapter", 1), withConnection(keep, otherAdapter), null));

            var parameters = new ProviderConnectionSpec(
                    "更新显示名称",
                    original.adapter(),
                    original.baseUri(),
                    original.authentication(),
                    Duration.ofSeconds(45),
                    1,
                    new ProviderAdapterOptions.OpenAiCompatible(Optional.of("organization"), Optional.empty()));
            var result = fixture.service.save(identity("metadata-only", 1), withConnection(keep, parameters), null);
            assertEquals("更新显示名称", result.provider().spec().displayName());
            assertEquals(saved.credential(), result.credential());
            assertEquals(2, fixture.providers.listAllVersions().size());
        }
    }

    private static ProviderConfiguration withConnection(
            ProviderConfiguration configuration, ProviderConnectionSpec connection) {
        return new ProviderConfiguration(
                configuration.providerId(),
                configuration.expectedRevision(),
                connection,
                configuration.models(),
                configuration.lifecycle(),
                configuration.credentialChange(),
                configuration.credentialExpectedRevision());
    }

    private static ProviderService.PreparedProviderChange tracked(AtomicInteger activated, AtomicInteger discarded) {
        return new ProviderService.PreparedProviderChange() {
            private boolean pending = true;

            @Override
            public void activate() {
                pending = false;
                activated.incrementAndGet();
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
}
