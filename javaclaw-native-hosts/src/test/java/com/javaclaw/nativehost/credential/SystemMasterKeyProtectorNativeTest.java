package com.javaclaw.nativehost.credential;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 当前 Runner 的 Keychain、DPAPI 或 Secret Service 真实闭环烟测。 */
class SystemMasterKeyProtectorNativeTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void currentUserCredentialFacilityStoresLoadsReplacesAndDeletesMasterKey() {
        Assumptions.assumeTrue(Boolean.getBoolean("javaclaw.require.native.credential"));
        MasterKeyProtector protector = SystemMasterKeyProtector.create(temporaryDirectory);
        String keyId = "native-runner-" + UUID.randomUUID();
        byte[] first = randomKey();
        byte[] replacement = randomKey();
        byte[] loaded = null;
        try {
            protector.delete(keyId);
            assertTrue(protector.load(keyId).isEmpty());
            protector.store(keyId, first);
            loaded = protector.load(keyId).orElseThrow();
            assertArrayEquals(first, loaded);
            Arrays.fill(loaded, (byte) 0);
            loaded = null;

            protector.store(keyId, replacement);
            loaded = protector.load(keyId).orElseThrow();
            assertArrayEquals(replacement, loaded);
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

    private static byte[] randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }
}
