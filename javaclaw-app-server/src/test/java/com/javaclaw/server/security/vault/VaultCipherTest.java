package com.javaclaw.server.security.vault;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VaultCipherTest {
    private final VaultCipher cipher = new VaultCipher(new SecureRandom());
    private final byte[] key = sequence(32);

    @Test
    void AESGCM往返且每次使用不同nonce() {
        byte[] secret = "provider-secret".getBytes(StandardCharsets.UTF_8);
        SecretBinding binding = new SecretBinding("provider", "ref-1", 1);

        EncryptedSecret first = cipher.encrypt(key, binding, secret);
        EncryptedSecret second = cipher.encrypt(key, binding, secret);

        assertArrayEquals(secret, cipher.decrypt(key, binding, first));
        assertFalse(java.util.Arrays.equals(first.nonce(), second.nonce()));
        assertFalse(java.util.Arrays.equals(secret, first.ciphertext()));
    }

    @Test
    void AAD密文和nonce任一变化都拒绝解密() {
        byte[] secret = "oauth-token".getBytes(StandardCharsets.UTF_8);
        SecretBinding binding = new SecretBinding("mcp", "ref-2", 4);
        EncryptedSecret encrypted = cipher.encrypt(key, binding, secret);
        byte[] changedCiphertext = encrypted.ciphertext();
        changedCiphertext[0] ^= 1;
        byte[] changedNonce = encrypted.nonce();
        changedNonce[0] ^= 1;

        assertThrows(VaultException.class, () -> cipher.decrypt(key, new SecretBinding("mcp", "ref-2", 5), encrypted));
        assertThrows(
                VaultException.class,
                () -> cipher.decrypt(
                        key,
                        binding,
                        new EncryptedSecret(encrypted.formatVersion(), encrypted.nonce(), changedCiphertext)));
        assertThrows(
                VaultException.class,
                () -> cipher.decrypt(
                        key,
                        binding,
                        new EncryptedSecret(encrypted.formatVersion(), changedNonce, encrypted.ciphertext())));
    }

    @Test
    void 拒绝错误密钥长度版本和资源标识() {
        SecretBinding binding = new SecretBinding("site", "ref-3", 1);
        EncryptedSecret encrypted = cipher.encrypt(key, binding, new byte[] {1});

        assertThrows(IllegalArgumentException.class, () -> cipher.encrypt(new byte[16], binding, new byte[] {1}));
        assertThrows(
                VaultException.class,
                () -> cipher.decrypt(key, binding, new EncryptedSecret(2, encrypted.nonce(), encrypted.ciphertext())));
        assertThrows(IllegalArgumentException.class, () -> new SecretBinding("../bad", "ref", 1));
    }

    private static byte[] sequence(int length) {
        byte[] result = new byte[length];
        for (int index = 0; index < length; index++) {
            result[index] = (byte) index;
        }
        return result;
    }
}
