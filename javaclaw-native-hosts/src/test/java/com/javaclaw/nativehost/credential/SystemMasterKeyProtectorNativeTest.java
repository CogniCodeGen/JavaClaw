package com.javaclaw.nativehost.credential;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 当前 Runner 的 Keychain、DPAPI 或 Secret Service 真实闭环烟测。 */
class SystemMasterKeyProtectorNativeTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void currentUserCredentialFacilityStoresLoadsReplacesAndDeletesMasterKey() {
        Assumptions.assumeTrue(Boolean.getBoolean("javaclaw.require.native.credential"));
        MasterKeyProtector protector = SystemMasterKeyProtector.create(
                System.getProperty("os.name"), temporaryDirectory, this::runWithoutSecretEcho);
        String keyId = "native-runner-" + UUID.randomUUID();
        byte[] first = randomKey();
        byte[] replacement = randomKey();
        byte[] loaded = null;
        try {
            protector.delete(keyId);
            assertTrue(protector.load(keyId).isEmpty());
            protector.store(keyId, first);
            loaded = protector.load(keyId).orElseThrow();
            assertTrue(MessageDigest.isEqual(first, loaded), "系统凭据读取值必须与存入值一致");
            Arrays.fill(loaded, (byte) 0);
            loaded = null;

            protector.store(keyId, replacement);
            loaded = protector.load(keyId).orElseThrow();
            assertTrue(MessageDigest.isEqual(replacement, loaded), "系统凭据替换后必须读取最新值");
        } finally {
            if (loaded != null) {
                Arrays.fill(loaded, (byte) 0);
            }
            Arrays.fill(first, (byte) 0);
            Arrays.fill(replacement, (byte) 0);
            protector.delete(keyId);
            Optional<byte[]> afterDelete = protector.load(keyId);
            afterDelete.ifPresent(value -> Arrays.fill(value, (byte) 0));
            assertTrue(afterDelete.isEmpty());
        }
    }

    private CredentialCommandRunner.Result runWithoutSecretEcho(List<String> command, byte[] input) {
        boolean macWrite = command.getFirst().equals("/usr/bin/security") && input.length > 0;
        if (macWrite) {
            assertTrue(command.equals(List.of("/usr/bin/security", "-q", "-i")), "Secret 只能进入 stdin");
        }
        CredentialCommandRunner.Result result = new SystemCredentialCommandRunner().run(command, input);
        if (macWrite) {
            byte[] output = result.standardOutput();
            try {
                assertEquals(0, output.length, "交互写入不得向 stdout 回显命令或 Secret");
            } catch (AssertionError failure) {
                result.close();
                throw failure;
            } finally {
                Arrays.fill(output, (byte) 0);
            }
        }
        return result;
    }

    private static byte[] randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }
}
