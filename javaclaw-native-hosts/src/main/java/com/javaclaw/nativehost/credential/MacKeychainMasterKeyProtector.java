package com.javaclaw.nativehost.credential;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * macOS Keychain 通用密码项实现；完整写命令通过 security 交互模式的 stdin 提交，Secret 不进入进程 argv。
 *
 * <p>只发送一条命令后关闭 stdin，以该命令退出码判断结果；不追加 quit 或放宽 Keychain ACL。写后只读取同一标识确认保存值， 所有临时密钥字节在退出前清零。
 */
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
        byte[] expected = Objects.requireNonNull(key, "key").clone();
        byte[] input = new byte[0];
        try {
            input = storeCommand(service, expected);
            try (CredentialCommandRunner.Result result = runner.run(List.of("/usr/bin/security", "-q", "-i"), input)) {
                SystemMasterKeyProtector.requireSuccess(result);
            }
            verifyStoredKey(keyId, expected);
        } finally {
            Arrays.fill(input, (byte) 0);
            Arrays.fill(expected, (byte) 0);
        }
    }

    private byte[] storeCommand(String service, byte[] key) {
        // service 已通过 keyId 字符集校验，Base64 不含命令分隔符；末尾仅保留编码器附加的一次换行。
        byte[] prefix = ("add-generic-password -a " + ACCOUNT + " -s " + service + " -U -w ")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] encoded = SystemMasterKeyProtector.encode(key);
        try {
            byte[] input = Arrays.copyOf(prefix, prefix.length + encoded.length);
            System.arraycopy(encoded, 0, input, prefix.length, encoded.length);
            return input;
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    private void verifyStoredKey(String keyId, byte[] expected) {
        byte[] stored = load(keyId).orElseThrow(() -> new MasterKeyProtectionException("macOS Keychain 主密钥写入校验失败"));
        try {
            if (!MessageDigest.isEqual(expected, stored)) {
                throw new MasterKeyProtectionException("macOS Keychain 主密钥写入校验失败");
            }
        } finally {
            Arrays.fill(stored, (byte) 0);
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
