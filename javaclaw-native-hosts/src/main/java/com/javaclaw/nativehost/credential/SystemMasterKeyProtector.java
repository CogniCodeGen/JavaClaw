package com.javaclaw.nativehost.credential;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** 根据当前操作系统选择 Keychain、DPAPI 或 Secret Service 主密钥包装。 */
public final class SystemMasterKeyProtector {
    private SystemMasterKeyProtector() {}

    /**
     * 创建当前平台实现。
     *
     * @param dataRoot 规范化的 data-v6 根目录；Windows 只在其凭据子目录保存 DPAPI 密文
     * @return 当前用户级主密钥保护器
     */
    public static MasterKeyProtector create(Path dataRoot) {
        return create(System.getProperty("os.name", ""), dataRoot, new SystemCredentialCommandRunner());
    }

    static MasterKeyProtector create(String osName, Path dataRoot, CredentialCommandRunner runner) {
        String platform = Objects.requireNonNull(osName, "osName").toLowerCase(Locale.ROOT);
        Path checkedRoot =
                Objects.requireNonNull(dataRoot, "dataRoot").toAbsolutePath().normalize();
        if (platform.contains("mac") || platform.contains("darwin")) {
            return new MacKeychainMasterKeyProtector(runner);
        }
        if (platform.contains("win")) {
            return new WindowsDpapiMasterKeyProtector(checkedRoot.resolve("credentials"), runner);
        }
        if (platform.contains("linux")) {
            return new LinuxSecretServiceMasterKeyProtector(runner);
        }
        throw new MasterKeyProtectionException("当前操作系统不支持 Vault 主密钥封装");
    }

    static String keyId(String value) {
        String checked = Objects.requireNonNull(value, "keyId").strip();
        if (!checked.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")) {
            throw new IllegalArgumentException("keyId contains unsupported characters");
        }
        return checked;
    }

    static byte[] decode(byte[] encoded) {
        byte[] normalized = trimAsciiWhitespace(Objects.requireNonNull(encoded, "encoded"));
        try {
            return java.util.Base64.getDecoder().decode(normalized);
        } catch (IllegalArgumentException failure) {
            throw new MasterKeyProtectionException("系统凭据中的主密钥格式无效", failure);
        } finally {
            java.util.Arrays.fill(normalized, (byte) 0);
            java.util.Arrays.fill(encoded, (byte) 0);
        }
    }

    static byte[] encode(byte[] key) {
        byte[] checked = Objects.requireNonNull(key, "key").clone();
        try {
            if (checked.length != 32) {
                throw new IllegalArgumentException("master key must contain 32 bytes");
            }
            byte[] encoded = java.util.Base64.getEncoder().encode(checked);
            byte[] line = java.util.Arrays.copyOf(encoded, encoded.length + 1);
            line[line.length - 1] = '\n';
            java.util.Arrays.fill(encoded, (byte) 0);
            return line;
        } finally {
            java.util.Arrays.fill(checked, (byte) 0);
        }
    }

    static Optional<byte[]> load(CredentialCommandRunner.Result result, int notFoundCode) {
        if (result.exitCode() == notFoundCode) {
            return Optional.empty();
        }
        requireSuccess(result);
        byte[] decoded = decode(result.standardOutput());
        if (decoded.length != 32) {
            java.util.Arrays.fill(decoded, (byte) 0);
            throw new MasterKeyProtectionException("系统凭据中的主密钥长度无效");
        }
        return Optional.of(decoded);
    }

    static void requireSuccess(CredentialCommandRunner.Result result) {
        if (result.exitCode() != 0) {
            throw new MasterKeyProtectionException("系统凭据设施拒绝了主密钥操作");
        }
    }

    private static byte[] trimAsciiWhitespace(byte[] value) {
        int start = 0;
        int end = value.length;
        while (start < end && asciiWhitespace(value[start])) {
            start++;
        }
        while (end > start && asciiWhitespace(value[end - 1])) {
            end--;
        }
        return java.util.Arrays.copyOfRange(value, start, end);
    }

    private static boolean asciiWhitespace(byte value) {
        return value == ' ' || value == '\t' || value == '\r' || value == '\n';
    }
}
