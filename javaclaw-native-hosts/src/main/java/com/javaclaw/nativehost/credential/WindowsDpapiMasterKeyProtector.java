package com.javaclaw.nativehost.credential;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;

/** Windows DPAPI CurrentUser 实现；磁盘只保存 DPAPI 密文。 */
final class WindowsDpapiMasterKeyProtector implements MasterKeyProtector {
    private static final String PROTECT_SCRIPT = String.join(
            ";",
            "$value=[Console]::In.ReadToEnd().Trim()",
            "$plain=[Convert]::FromBase64String($value)",
            "$scope=[Security.Cryptography.DataProtectionScope]::CurrentUser",
            "$wrapped=[Security.Cryptography.ProtectedData]::Protect($plain,$null,$scope)",
            "[Console]::Out.Write([Convert]::ToBase64String($wrapped))");
    private static final String UNPROTECT_SCRIPT = String.join(
            ";",
            "$value=[Console]::In.ReadToEnd().Trim()",
            "$wrapped=[Convert]::FromBase64String($value)",
            "$scope=[Security.Cryptography.DataProtectionScope]::CurrentUser",
            "$plain=[Security.Cryptography.ProtectedData]::Unprotect($wrapped,$null,$scope)",
            "[Console]::Out.Write([Convert]::ToBase64String($plain))");

    private final Path directory;
    private final CredentialCommandRunner runner;

    WindowsDpapiMasterKeyProtector(Path directory, CredentialCommandRunner runner) {
        this.directory = directory;
        this.runner = runner;
    }

    @Override
    public Optional<byte[]> load(String keyId) {
        Path file = file(keyId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        byte[] wrapped = read(file);
        byte[] input = line(java.util.Base64.getEncoder().encode(wrapped));
        java.util.Arrays.fill(wrapped, (byte) 0);
        try {
            try (CredentialCommandRunner.Result result = runner.run(command(UNPROTECT_SCRIPT), input)) {
                SystemMasterKeyProtector.requireSuccess(result);
                byte[] key = SystemMasterKeyProtector.decode(result.standardOutput());
                if (key.length != 32) {
                    java.util.Arrays.fill(key, (byte) 0);
                    throw new MasterKeyProtectionException("DPAPI 主密钥长度无效");
                }
                return Optional.of(key);
            }
        } finally {
            java.util.Arrays.fill(input, (byte) 0);
        }
    }

    @Override
    public void store(String keyId, byte[] key) {
        byte[] input = SystemMasterKeyProtector.encode(key);
        try {
            try (CredentialCommandRunner.Result result = runner.run(command(PROTECT_SCRIPT), input)) {
                SystemMasterKeyProtector.requireSuccess(result);
                byte[] wrapped = SystemMasterKeyProtector.decode(result.standardOutput());
                try {
                    writeAtomically(file(keyId), wrapped);
                } finally {
                    java.util.Arrays.fill(wrapped, (byte) 0);
                }
            }
        } finally {
            java.util.Arrays.fill(input, (byte) 0);
        }
    }

    @Override
    public void delete(String keyId) {
        try {
            Files.deleteIfExists(file(keyId));
        } catch (IOException failure) {
            throw new MasterKeyProtectionException("无法删除 DPAPI 主密钥包装", failure);
        }
    }

    private Path file(String keyId) {
        return directory.resolve(SystemMasterKeyProtector.keyId(keyId) + ".dpapi");
    }

    private byte[] read(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0 || bytes.length > 4096) {
                throw new MasterKeyProtectionException("DPAPI 主密钥包装大小无效");
            }
            return bytes;
        } catch (IOException failure) {
            throw new MasterKeyProtectionException("无法读取 DPAPI 主密钥包装", failure);
        }
    }

    private void writeAtomically(Path file, byte[] content) {
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, ".master-key-", ".tmp");
            Files.write(temporary, content);
            move(temporary, file);
        } catch (IOException failure) {
            throw new MasterKeyProtectionException("无法保存 DPAPI 主密钥包装", failure);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // 临时文件只包含 DPAPI 密文；启动时目录清理由宿主统一处理。
                }
            }
        }
    }

    private void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static List<String> command(String script) {
        return List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script);
    }

    private static byte[] line(byte[] encoded) {
        byte[] result = java.util.Arrays.copyOf(encoded, encoded.length + 1);
        result[result.length - 1] = '\n';
        java.util.Arrays.fill(encoded, (byte) 0);
        return result;
    }
}
