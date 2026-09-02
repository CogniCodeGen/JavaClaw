package com.javaclaw.server.security.vault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.VaultLockReason;
import com.javaclaw.api.VaultManagementAction;
import com.javaclaw.api.VaultState;
import com.javaclaw.nativehost.credential.MasterKeyProtectionException;
import com.javaclaw.nativehost.credential.MasterKeyProtector;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.PersistenceException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretVaultServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T08:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    @Test
    void 创建轮换读取和清除只暴露脱敏元数据() {
        FakeProtector protector = new FakeProtector();
        try (SecretVaultService vault = vault(protector)) {
            byte[] first = "first-provider-secret".getBytes(StandardCharsets.UTF_8);
            CommandIdentity create = identity("credential/create", 0);
            CredentialMetadata created = vault.create(create, "provider", first);
            assertEquals(created, vault.create(create, "provider", null));
            assertEquals(java.util.List.of(created), vault.listMetadata("provider"));
            assertTrue(vault.listMetadata("site").isEmpty());

            AtomicReference<byte[]> callbackBytes = new AtomicReference<>();
            String opened = vault.use(created.reference(), bytes -> {
                callbackBytes.set(bytes);
                return new String(bytes, StandardCharsets.UTF_8);
            });
            assertEquals("first-provider-secret", opened);
            assertTrue(allZero(callbackBytes.get()));
            assertEquals(1, vault.status().credentialCount());

            CredentialMetadata rotated = vault.rotate(
                    identity("credential/rotate", created.revision()),
                    created.reference(),
                    "second-secret".getBytes(StandardCharsets.UTF_8));
            assertEquals(2, rotated.revision());
            assertEquals("second-secret", vault.use(created.reference(), SecretVaultServiceTest::text));
            assertThrows(
                    PersistenceException.class,
                    () -> vault.rotate(identity("credential/rotate", 1), created.reference(), new byte[] {1}));

            CommandIdentity clear = identity("credential/clear", rotated.revision());
            assertEquals(
                    rotated.revision(), vault.clear(clear, rotated.reference()).clearedRevision());
            assertEquals(
                    rotated.revision(), vault.clear(clear, rotated.reference()).clearedRevision());
            assertTrue(vault.metadata(rotated.reference()).isEmpty());
            assertTrue(vault.listMetadata("provider").isEmpty());
            assertThrows(
                    PersistenceException.class, () -> vault.use(rotated.reference(), SecretVaultServiceTest::text));
        }
    }

    @Test
    void 重启和主密钥轮换保持CredentialRef可用() {
        FakeProtector protector = new FakeProtector();
        CredentialMetadata metadata;
        try (SecretVaultService first = vault(protector)) {
            metadata = first.create(
                    identity("credential/create", 0), "mcp", "bearer-token".getBytes(StandardCharsets.UTF_8));
        }

        try (SecretVaultService restarted = vault(protector)) {
            assertEquals("bearer-token", restarted.use(metadata.reference(), SecretVaultServiceTest::text));
            String oldKeyId = protector.onlyKeyId();

            CommandIdentity rotation = identity("vault/masterKey/rotate", 0);
            var receipt = restarted.rotateMasterKey(rotation);

            assertEquals("bearer-token", restarted.use(metadata.reference(), SecretVaultServiceTest::text));
            assertEquals(VaultManagementAction.MASTER_KEY_ROTATED, receipt.action());
            assertEquals(1, receipt.affectedCredentialCount());
            assertEquals(receipt, restarted.rotateMasterKey(rotation));
            assertFalse(protector.keys.containsKey(oldKeyId));
            assertEquals(1, protector.keys.size());
            assertFalse(restarted.status().oldKeyCleanupPending());
        }
    }

    @Test
    void 旧主密钥删除失败时保留可恢复清理状态并拒绝连续轮换() {
        FakeProtector protector = new FakeProtector();
        try (SecretVaultService vault = vault(protector)) {
            vault.create(identity("credential/create", 0), "site", "storage-state".getBytes(StandardCharsets.UTF_8));
            protector.deleteFailures = 2;

            vault.rotateMasterKey(identity("vault/masterKey/rotate", 0));

            assertTrue(vault.status().oldKeyCleanupPending());
            assertThrows(VaultException.class, () -> vault.rotateMasterKey(identity("vault/masterKey/rotate", 0)));
            assertFalse(vault.refresh().oldKeyCleanupPending());
            assertEquals(1, protector.keys.size());
        }
    }

    @Test
    void 系统凭据不可用时锁定且恢复后可刷新() {
        FakeProtector protector = new FakeProtector();
        protector.storeUnavailable = true;
        try (SecretVaultService vault = vault(protector)) {
            assertEquals(VaultState.LOCKED, vault.status().state());
            assertEquals(
                    VaultLockReason.SYSTEM_CREDENTIAL_UNAVAILABLE,
                    vault.status().reason());
            assertThrows(
                    VaultException.class,
                    () -> vault.create(identity("credential/create", 0), "provider", new byte[] {1}));

            protector.storeUnavailable = false;
            assertEquals(VaultState.READY, vault.refresh().state());
            assertEquals(
                    1,
                    vault.create(identity("credential/create", 0), "provider", new byte[] {7})
                            .revision());
        }
    }

    @Test
    void 主密钥包装丢失时锁定但危险reset可使全部旧引用失效() {
        FakeProtector protector = new FakeProtector();
        CredentialMetadata metadata;
        try (SecretVaultService first = vault(protector)) {
            metadata = first.create(
                    identity("credential/create", 0), "oauth", "refresh-token".getBytes(StandardCharsets.UTF_8));
        }
        protector.keys.clear();

        try (SecretVaultService locked = vault(protector)) {
            assertEquals(VaultLockReason.MASTER_KEY_MISSING, locked.status().reason());
            assertThrows(VaultException.class, () -> locked.use(metadata.reference(), SecretVaultServiceTest::text));

            CommandIdentity reset = identity("vault/reset", 0);
            var receipt = locked.reset(reset);

            assertEquals(VaultState.READY, locked.status().state());
            assertEquals(VaultManagementAction.VAULT_RESET, receipt.action());
            assertEquals(1, receipt.affectedCredentialCount());
            assertEquals(receipt, locked.reset(reset));
            assertEquals(0, locked.status().credentialCount());
            assertTrue(locked.metadata(metadata.reference()).isEmpty());
        }
    }

    @Test
    void H2文件和异常中不出现Secret明文() throws Exception {
        FakeProtector protector = new FakeProtector();
        String marker = "DO-NOT-LEAK-THIS-SECRET-2026";
        try (SecretVaultService vault = vault(protector)) {
            vault.create(identity("credential/create", 0), "browser", marker.getBytes(StandardCharsets.UTF_8));
            PersistenceException error = assertThrows(
                    PersistenceException.class,
                    () -> vault.use(
                            new com.javaclaw.api.CredentialRef("browser", "missing"), SecretVaultServiceTest::text));
            assertFalse(error.toString().contains(marker));
        }

        Path root = temporaryDirectory.resolve("data-v5");
        try (var paths = Files.walk(root)) {
            assertTrue(paths.filter(Files::isRegularFile).noneMatch(path -> contains(path, marker)));
        }
    }

    @Test
    void 恢复接口可用性监听器和受检异常均保持FailClosed() {
        FakeProtector protector = new FakeProtector();
        AtomicInteger changes = new AtomicInteger();
        SecretVaultService vault = vault(protector);
        vault.onChange(changes::incrementAndGet);

        CommandIdentity create = identity("credential/create", 0);
        CredentialMetadata created = vault.create(create, "provider", "secret".getBytes(StandardCharsets.UTF_8));
        assertEquals(created, vault.recoverCredential(create).orElseThrow());
        assertTrue(vault.available(created.reference()));
        assertFalse(vault.available(new com.javaclaw.api.CredentialRef("provider", "missing")));
        assertThrows(
                VaultException.class,
                () -> vault.use(created.reference(), bytes -> {
                    throw new IOException("受检失败");
                }));

        CommandIdentity rotate = identity("credential/rotate", created.revision());
        CredentialMetadata rotated = vault.rotate(rotate, created.reference(), "next".getBytes(StandardCharsets.UTF_8));
        assertEquals(rotated, vault.recoverCredential(rotate).orElseThrow());
        CommandIdentity clear = identity("credential/clear", rotated.revision());
        var receipt = vault.clear(clear, rotated.reference());
        assertEquals(receipt, vault.recoverClear(clear).orElseThrow());
        assertEquals(3, changes.get());

        vault.close();
        vault.close();
        assertThrows(VaultException.class, () -> vault.metadata(rotated.reference()));
        assertThrows(VaultException.class, vault::refresh);
    }

    @Test
    void 无效主密钥和系统读取失败映射为明确锁定原因() {
        FakeProtector invalid = new FakeProtector();
        try (SecretVaultService ignored = vault(invalid)) {
            invalid.keys.put(invalid.onlyKeyId(), new byte[] {1, 2, 3});
        }
        try (SecretVaultService restarted = vault(invalid)) {
            assertEquals(VaultLockReason.MASTER_KEY_INVALID, restarted.status().reason());
        }

        FakeProtector unavailable = new FakeProtector();
        try (SecretVaultService ignored = vault(unavailable)) {
            unavailable.loadUnavailable = true;
        }
        try (SecretVaultService restarted = vault(unavailable)) {
            assertEquals(
                    VaultLockReason.SYSTEM_CREDENTIAL_UNAVAILABLE,
                    restarted.status().reason());
        }
    }

    @Test
    void 系统凭据拒绝轮换和Reset时不改变现有Vault() {
        FakeProtector protector = new FakeProtector();
        try (SecretVaultService vault = vault(protector)) {
            CredentialMetadata metadata = vault.create(
                    identity("credential/create", 0), "provider", "stable".getBytes(StandardCharsets.UTF_8));

            protector.storeFailures = 1;
            assertThrows(VaultException.class, () -> vault.rotateMasterKey(identity("vault/masterKey/rotate", 0)));
            assertEquals("stable", vault.use(metadata.reference(), SecretVaultServiceTest::text));

            protector.storeFailures = 1;
            assertThrows(VaultException.class, () -> vault.reset(identity("vault/reset", 0)));
            assertEquals("stable", vault.use(metadata.reference(), SecretVaultServiceTest::text));
            assertEquals(1, protector.keys.size());
        }
    }

    private SecretVaultService vault(FakeProtector protector) {
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v5"));
        database.initialize();
        return new SecretVaultService(database, protector, new CanonicalJson(), CLOCK, new SecureRandom());
    }

    private static CommandIdentity identity(String method, long expectedRevision) {
        return new CommandIdentity(method, UUID.randomUUID().toString(), expectedRevision, "0".repeat(64));
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

    private static boolean contains(Path path, String marker) {
        try {
            String content = new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
            return content.contains(marker);
        } catch (Exception failure) {
            throw new AssertionError("无法扫描测试数据库", failure);
        }
    }

    private static final class FakeProtector implements MasterKeyProtector {
        private final Map<String, byte[]> keys = new HashMap<>();
        private boolean storeUnavailable;
        private boolean loadUnavailable;
        private int storeFailures;
        private int deleteFailures;

        @Override
        public Optional<byte[]> load(String keyId) {
            if (loadUnavailable) {
                throw new MasterKeyProtectionException("test load unavailable");
            }
            byte[] value = keys.get(keyId);
            return value == null ? Optional.empty() : Optional.of(value.clone());
        }

        @Override
        public void store(String keyId, byte[] key) {
            if (storeUnavailable || storeFailures > 0) {
                if (storeFailures > 0) {
                    storeFailures--;
                }
                throw new MasterKeyProtectionException("test unavailable");
            }
            keys.put(keyId, key.clone());
        }

        @Override
        public void delete(String keyId) {
            if (deleteFailures > 0) {
                deleteFailures--;
                throw new MasterKeyProtectionException("test delete failure");
            }
            byte[] removed = keys.remove(keyId);
            if (removed != null) {
                Arrays.fill(removed, (byte) 0);
            }
        }

        private String onlyKeyId() {
            assertEquals(1, keys.size());
            return keys.keySet().iterator().next();
        }
    }
}
