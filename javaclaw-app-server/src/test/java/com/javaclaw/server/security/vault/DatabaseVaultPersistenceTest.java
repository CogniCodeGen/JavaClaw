package com.javaclaw.server.security.vault;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.VaultLockReason;
import com.javaclaw.api.VaultState;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseVaultPersistenceTest {
    @TempDir
    Path directory;

    @Test
    void 新建重启轮换与幂等重置全程只依赖数据库() throws Exception {
        var fixture = new DatabaseVaultFixture(directory);
        CredentialMetadata credential;
        String firstKey;
        try (var vault = fixture.vault(fixture.local())) {
            assertEquals(VaultState.READY, vault.status().state());
            credential = fixture.create(vault, "local-secret");
            firstKey = fixture.activeKey();
        }
        try (var restarted = fixture.vault(fixture.local())) {
            assertEquals("local-secret", restarted.use(credential.reference(), DatabaseVaultFixture::text));
            var rotate = DatabaseVaultFixture.identity("vault/masterKey/rotate");
            var receipt = restarted.rotateMasterKey(rotate);
            String rotatedKey = fixture.activeKey();
            assertNotEquals(firstKey, rotatedKey);
            assertEquals(receipt, restarted.rotateMasterKey(rotate));
            assertEquals(rotatedKey, fixture.activeKey());
            assertEquals(credential, restarted.metadata(credential.reference()).orElseThrow());
            assertEquals("local-secret", restarted.use(credential.reference(), DatabaseVaultFixture::text));
            assertEquals(1, fixture.keyCount());
            assertFalse(restarted.status().oldKeyCleanupPending());
            var reset = DatabaseVaultFixture.identity("vault/reset");
            var resetReceipt = restarted.reset(reset);
            String resetKey = fixture.activeKey();
            assertEquals(resetReceipt, restarted.reset(reset));
            assertEquals(resetKey, fixture.activeKey());
            assertEquals(1, resetReceipt.affectedCredentialCount());
            assertTrue(restarted.metadata(credential.reference()).isEmpty());
            assertEquals(1, fixture.keyCount());
            fixture.create(restarted, "after-reset");
        }
        try (var again = fixture.vault(fixture.local())) {
            assertEquals(VaultState.READY, again.status().state());
            assertEquals(1, again.status().credentialCount());
        }
    }

    @Test
    void 轮换和重置事务失败保留旧主密钥密文并清理候选() throws Exception {
        var fixture = new DatabaseVaultFixture(directory);
        try (var vault = fixture.vault(fixture.local())) {
            var credential = fixture.create(vault, "stable");
            String active = fixture.activeKey();
            var before = fixture.secret(credential);
            fixture.sql("ALTER TABLE CORE.COMMAND_RESULT ADD CONSTRAINT TEST_REJECT_MANAGEMENT "
                    + "CHECK (METHOD_NAME NOT IN ('vault/reset','vault/masterKey/rotate'))");
            assertThrows(
                    VaultException.class,
                    () -> vault.rotateMasterKey(DatabaseVaultFixture.identity("vault/masterKey/rotate")));
            assertThrows(VaultException.class, () -> vault.reset(DatabaseVaultFixture.identity("vault/reset")));
            assertEquals(active, fixture.activeKey());
            assertEquals(1, fixture.keyCount());
            assertEquals("stable", vault.use(credential.reference(), DatabaseVaultFixture::text));
            assertArrayEquals(
                    before.encrypted().ciphertext(),
                    fixture.secret(credential).encrypted().ciphertext());
            assertFalse(vault.status().oldKeyCleanupPending());
        }
    }

    @Test
    void 主密钥丢失时锁定且刷新不会生成替代密钥或删除凭据() throws Exception {
        var fixture = new DatabaseVaultFixture(directory);
        var local = fixture.local();
        try (var vault = fixture.vault(local)) {
            fixture.create(vault, "missing-key");
        }
        String active = fixture.activeKey();
        local.delete(active);
        try (var restarted = fixture.vault(fixture.local())) {
            assertEquals(VaultState.LOCKED, restarted.status().state());
            assertEquals(VaultLockReason.MASTER_KEY_MISSING, restarted.status().reason());
            assertEquals(1, restarted.status().credentialCount());
            assertEquals(VaultState.LOCKED, restarted.refresh().state());
            assertEquals(0, fixture.keyCount());
            assertEquals(active, fixture.activeKey());
        }
    }

    @Test
    void 主密钥丢失后显式重置使旧引用失效并恢复本地保存() throws Exception {
        var fixture = new DatabaseVaultFixture(directory);
        CredentialMetadata credential;
        try (var vault = fixture.vault(fixture.local())) {
            credential = fixture.create(vault, "explicit-reset");
        }
        fixture.local().delete(fixture.activeKey());
        try (var vault = fixture.vault(fixture.local())) {
            assertEquals(VaultState.LOCKED, vault.status().state());
            var receipt = vault.reset(DatabaseVaultFixture.identity("vault/reset"));
            assertEquals(1, receipt.affectedCredentialCount());
            assertEquals(VaultState.READY, vault.status().state());
            assertTrue(vault.metadata(credential.reference()).isEmpty());
            assertEquals(1, fixture.keyCount());
            var replacement = fixture.create(vault, "replacement");
            assertEquals("replacement", vault.use(replacement.reference(), DatabaseVaultFixture::text));
        }
    }
}
