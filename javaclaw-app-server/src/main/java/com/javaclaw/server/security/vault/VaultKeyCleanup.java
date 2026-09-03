package com.javaclaw.server.security.vault;

import com.javaclaw.nativehost.credential.MasterKeyProtectionException;
import com.javaclaw.nativehost.credential.MasterKeyProtector;

/** 清理尚未被 H2 引用的主密钥包装。 */
final class VaultKeyCleanup {
    private VaultKeyCleanup() {}

    static void deleteUnreferenced(MasterKeyProtector protector, String keyId) {
        try {
            protector.delete(keyId);
        } catch (MasterKeyProtectionException ignored) {
            // H2 从未引用该 key；残留包装不具备解密任何 Vault 记录的能力。
        }
    }
}
