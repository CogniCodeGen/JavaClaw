package com.javaclaw.nativehost.credential;

import java.util.List;
import java.util.Optional;

/** macOS Keychain 通用密码项实现；Secret 通过 stdin 传给 security，绝不进入 argv。 */
final class MacKeychainMasterKeyProtector implements MasterKeyProtector {
    private static final String ACCOUNT = "JavaClaw";
    private static final String SERVICE_PREFIX = "com.javaclaw.v6.master.";
    private static final int ITEM_NOT_FOUND = 44;

    private final CredentialCommandRunner runner;

    MacKeychainMasterKeyProtector(CredentialCommandRunner runner) {
        this.runner = runner;
    }

    @Override
    public Optional<byte[]> load(String keyId) {
        String service = service(keyId);
        try (CredentialCommandRunner.Result result = runner.run(
                List.of("/usr/bin/security", "find-generic-password", "-a", ACCOUNT, "-s", service, "-w"),
                new byte[0])) {
            return SystemMasterKeyProtector.load(result, ITEM_NOT_FOUND);
        }
    }

    @Override
    public void store(String keyId, byte[] key) {
        String service = service(keyId);
        byte[] input = SystemMasterKeyProtector.encode(key);
        try {
            try (CredentialCommandRunner.Result result = runner.run(
                    List.of("/usr/bin/security", "add-generic-password", "-a", ACCOUNT, "-s", service, "-U", "-w"),
                    input)) {
                SystemMasterKeyProtector.requireSuccess(result);
            }
        } finally {
            java.util.Arrays.fill(input, (byte) 0);
        }
    }

    @Override
    public void delete(String keyId) {
        try (CredentialCommandRunner.Result result = runner.run(
                List.of("/usr/bin/security", "delete-generic-password", "-a", ACCOUNT, "-s", service(keyId)),
                new byte[0])) {
            if (result.exitCode() != 0 && result.exitCode() != ITEM_NOT_FOUND) {
                throw new MasterKeyProtectionException("macOS Keychain 拒绝删除主密钥");
            }
        }
    }

    private static String service(String keyId) {
        return SERVICE_PREFIX + SystemMasterKeyProtector.keyId(keyId);
    }
}
