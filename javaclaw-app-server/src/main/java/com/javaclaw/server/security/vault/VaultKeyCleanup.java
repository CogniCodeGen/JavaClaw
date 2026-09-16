package com.javaclaw.server.security.vault;

import java.time.Clock;
import java.util.Optional;

import com.javaclaw.nativehost.credential.MasterKeyProtectionException;
import com.javaclaw.nativehost.credential.MasterKeyProtector;
import com.javaclaw.server.persistence.H2Transactions;

/** 清理无引用主密钥以及轮换后已退役的主密钥包装。 */
final class VaultKeyCleanup {
    private VaultKeyCleanup() {}

    static void cleanupPrevious(
            MasterKeyProtector protector, H2Transactions transactions, VaultRepository repository, Clock clock) {
        try {
            Optional<VaultRepository.KeyState> state =
                    transactions.execute(connection -> repository.keyState(connection, false));
            String previous = state.map(VaultRepository.KeyState::previousKeyId).orElse(null);
            if (previous == null) {
                return;
            }
            protector.delete(previous);
            transactions.execute(connection -> {
                repository.clearPreviousKey(connection, previous, clock.instant());
                return null;
            });
        } catch (MasterKeyProtectionException ignored) {
            // 保留 previous key id 供 Diagnostics 提示，后续 refresh 或轮换前重试。
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new VaultException("Secret Vault 事务失败", failure);
        }
    }

    static void deleteUnreferenced(MasterKeyProtector protector, String keyId) {
        try {
            protector.delete(keyId);
        } catch (MasterKeyProtectionException ignored) {
            // H2 从未引用该 key；残留包装不具备解密任何 Vault 记录的能力。
        }
    }
}
