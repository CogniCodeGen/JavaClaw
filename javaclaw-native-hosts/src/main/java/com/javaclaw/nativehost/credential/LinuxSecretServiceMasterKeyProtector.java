package com.javaclaw.nativehost.credential;

import java.util.List;
import java.util.Optional;

/** Linux Secret Service 实现；使用 secret-tool 的 stdin Secret 通道。 */
final class LinuxSecretServiceMasterKeyProtector implements MasterKeyProtector {
    private static final int ITEM_NOT_FOUND = 1;
    private final CredentialCommandRunner runner;

    LinuxSecretServiceMasterKeyProtector(CredentialCommandRunner runner) {
        this.runner = runner;
    }

    @Override
    public Optional<byte[]> load(String keyId) {
        try (CredentialCommandRunner.Result result = runner.run(
                List.of(
                        "secret-tool",
                        "lookup",
                        "application",
                        "javaclaw",
                        "purpose",
                        "master-key",
                        "id",
                        SystemMasterKeyProtector.keyId(keyId)),
                new byte[0])) {
            return SystemMasterKeyProtector.load(result, ITEM_NOT_FOUND);
        }
    }

    @Override
    public void store(String keyId, byte[] key) {
        byte[] input = SystemMasterKeyProtector.encode(key);
        try {
            try (CredentialCommandRunner.Result result = runner.run(
                    List.of(
                            "secret-tool",
                            "store",
                            "--label=JavaClaw v5 master key",
                            "application",
                            "javaclaw",
                            "purpose",
                            "master-key",
                            "id",
                            SystemMasterKeyProtector.keyId(keyId)),
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
                List.of(
                        "secret-tool",
                        "clear",
                        "application",
                        "javaclaw",
                        "purpose",
                        "master-key",
                        "id",
                        SystemMasterKeyProtector.keyId(keyId)),
                new byte[0])) {
            if (result.exitCode() != 0 && result.exitCode() != ITEM_NOT_FOUND) {
                throw new MasterKeyProtectionException("Linux Secret Service 拒绝删除主密钥");
            }
        }
    }
}
