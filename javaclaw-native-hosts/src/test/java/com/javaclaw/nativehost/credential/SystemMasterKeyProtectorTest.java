package com.javaclaw.nativehost.credential;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemMasterKeyProtectorTest {
    private static final byte[] KEY = bytes(32, 7);

    @TempDir
    Path temporaryDirectory;

    @Test
    void factory按平台选择实现并拒绝未知平台() {
        RecordingRunner runner = new RecordingRunner();

        assertInstanceOf(
                MacKeychainMasterKeyProtector.class,
                SystemMasterKeyProtector.create("Mac OS X", temporaryDirectory, runner));
        assertInstanceOf(
                LinuxSecretServiceMasterKeyProtector.class,
                SystemMasterKeyProtector.create("Linux", temporaryDirectory, runner));
        assertInstanceOf(
                WindowsDpapiMasterKeyProtector.class,
                SystemMasterKeyProtector.create("Windows 11", temporaryDirectory, runner));
        assertThrows(
                MasterKeyProtectionException.class,
                () -> SystemMasterKeyProtector.create("Plan 9", temporaryDirectory, runner));
    }

    @Test
    void macOS通过stdin存储并读取Keychain且允许幂等删除() {
        RecordingRunner runner = new RecordingRunner();
        MacKeychainMasterKeyProtector protector = new MacKeychainMasterKeyProtector(runner);
        runner.reply(0, new byte[0]);
        runner.reply(0, Base64.getEncoder().encode(KEY));
        runner.reply(44, new byte[0]);

        protector.store("installation", KEY);
        assertArrayEquals(KEY, decodeLine(runner.inputs().getFirst()));
        assertFalse(runner.commands().getFirst().contains(Base64.getEncoder().encodeToString(KEY)));
        assertArrayEquals(KEY, protector.load("installation").orElseThrow());
        protector.delete("installation");

        assertEquals("/usr/bin/security", runner.commands().getFirst().getFirst());
        assertEquals("-w", runner.commands().getFirst().getLast());
    }

    @Test
    void macOS区分未找到和真实错误() {
        RecordingRunner runner = new RecordingRunner();
        MacKeychainMasterKeyProtector protector = new MacKeychainMasterKeyProtector(runner);
        runner.reply(44, new byte[0]);
        runner.reply(2, new byte[0]);

        assertTrue(protector.load("missing").isEmpty());
        assertThrows(MasterKeyProtectionException.class, () -> protector.load("denied"));
    }

    @Test
    void linux使用SecretService属性并处理删除失败() {
        RecordingRunner runner = new RecordingRunner();
        LinuxSecretServiceMasterKeyProtector protector = new LinuxSecretServiceMasterKeyProtector(runner);
        runner.reply(0, new byte[0]);
        runner.reply(0, Base64.getEncoder().encode(KEY));
        runner.reply(3, new byte[0]);

        protector.store("linux-key", KEY);
        assertTrue(runner.commands().getFirst().contains("--label=JavaClaw v5 master key"));
        assertArrayEquals(KEY, protector.load("linux-key").orElseThrow());
        assertThrows(MasterKeyProtectionException.class, () -> protector.delete("linux-key"));
    }

    @Test
    void windows只把DPAPI密文写入文件并能解封() throws Exception {
        RecordingRunner runner = new RecordingRunner();
        byte[] wrapped = bytes(64, 19);
        runner.reply(0, Base64.getEncoder().encode(wrapped));
        runner.reply(0, Base64.getEncoder().encode(KEY));
        WindowsDpapiMasterKeyProtector protector =
                new WindowsDpapiMasterKeyProtector(temporaryDirectory.resolve("credentials"), runner);

        protector.store("windows-key", KEY);
        Path wrappedFile = temporaryDirectory.resolve("credentials/windows-key.dpapi");
        assertArrayEquals(wrapped, Files.readAllBytes(wrappedFile));
        assertArrayEquals(KEY, protector.load("windows-key").orElseThrow());
        assertArrayEquals(wrapped, decodeLine(runner.inputs().get(1)));

        protector.delete("windows-key");
        assertFalse(Files.exists(wrappedFile));
        assertTrue(protector.load("windows-key").isEmpty());
    }

    @Test
    void windows拒绝损坏包装和错误密钥长度() throws Exception {
        RecordingRunner runner = new RecordingRunner();
        Path directory = temporaryDirectory.resolve("credentials");
        Files.createDirectories(directory);
        Files.write(directory.resolve("broken.dpapi"), new byte[0]);
        Files.write(directory.resolve("short.dpapi"), new byte[] {1});
        runner.reply(0, Base64.getEncoder().encode(new byte[16]));
        WindowsDpapiMasterKeyProtector protector = new WindowsDpapiMasterKeyProtector(directory, runner);

        assertThrows(MasterKeyProtectionException.class, () -> protector.load("broken"));
        assertThrows(MasterKeyProtectionException.class, () -> protector.load("short"));
    }

    @Test
    void 编码校验长度标识和Base64格式() {
        assertThrows(IllegalArgumentException.class, () -> SystemMasterKeyProtector.encode(new byte[31]));
        assertThrows(IllegalArgumentException.class, () -> SystemMasterKeyProtector.keyId("../escape"));
        assertThrows(
                MasterKeyProtectionException.class,
                () -> SystemMasterKeyProtector.decode("not-base64".getBytes(StandardCharsets.US_ASCII)));

        byte[] line = SystemMasterKeyProtector.encode(KEY);
        assertArrayEquals(KEY, decodeLine(line));
    }

    @Test
    void 真实命令执行器有界传输stdin和stdout() {
        SystemCredentialCommandRunner runner = new SystemCredentialCommandRunner();
        byte[] input = "bounded".getBytes(StandardCharsets.UTF_8);

        try (CredentialCommandRunner.Result result = runner.run(List.of("/bin/sh", "-c", "cat"), input)) {
            assertEquals(0, result.exitCode());
            assertArrayEquals(input, result.standardOutput());
        }
    }

    private static byte[] decodeLine(byte[] line) {
        return Base64.getDecoder().decode(new String(line, StandardCharsets.US_ASCII).strip());
    }

    private static byte[] bytes(int length, int seed) {
        byte[] result = new byte[length];
        for (int index = 0; index < length; index++) {
            result[index] = (byte) (seed + index);
        }
        return result;
    }

    private static final class RecordingRunner implements CredentialCommandRunner {
        private final Deque<Result> replies = new ArrayDeque<>();
        private final List<List<String>> commands = new ArrayList<>();
        private final List<byte[]> inputs = new ArrayList<>();

        @Override
        public Result run(List<String> command, byte[] standardInput) {
            commands.add(List.copyOf(command));
            inputs.add(standardInput.clone());
            return replies.removeFirst();
        }

        void reply(int exitCode, byte[] output) {
            replies.addLast(new Result(exitCode, output));
        }

        List<List<String>> commands() {
            return commands;
        }

        List<byte[]> inputs() {
            return inputs;
        }
    }
}
