package com.javaclaw.server.security.vault;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AES-256-GCM Vault record 加解密；AAD 固定绑定资源身份与版本。 */
final class VaultCipher {
    static final int FORMAT_VERSION = 1;
    static final int KEY_BYTES = 32;
    static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecureRandom random;

    VaultCipher(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    EncryptedSecret encrypt(byte[] key, SecretBinding binding, byte[] plaintext) {
        byte[] checkedKey = key(key);
        byte[] checkedPlaintext = Objects.requireNonNull(plaintext, "plaintext").clone();
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, checkedKey, nonce, binding);
            return new EncryptedSecret(FORMAT_VERSION, nonce, cipher.doFinal(checkedPlaintext));
        } catch (GeneralSecurityException failure) {
            throw new VaultException("无法加密 Vault 记录", failure);
        } finally {
            Arrays.fill(checkedKey, (byte) 0);
            Arrays.fill(checkedPlaintext, (byte) 0);
        }
    }

    byte[] decrypt(byte[] key, SecretBinding binding, EncryptedSecret encrypted) {
        byte[] checkedKey = key(key);
        EncryptedSecret checked = Objects.requireNonNull(encrypted, "encrypted");
        if (checked.formatVersion() != FORMAT_VERSION) {
            Arrays.fill(checkedKey, (byte) 0);
            throw new VaultException("Vault 密文版本不受支持");
        }
        try {
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, checkedKey, checked.nonce(), binding);
            return cipher.doFinal(checked.ciphertext());
        } catch (AEADBadTagException tampered) {
            throw new VaultException("Vault 密文完整性校验失败", tampered);
        } catch (GeneralSecurityException failure) {
            throw new VaultException("无法解密 Vault 记录", failure);
        } finally {
            Arrays.fill(checkedKey, (byte) 0);
        }
    }

    private Cipher cipher(int mode, byte[] key, byte[] nonce, SecretBinding binding) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(binding.aad().getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private static byte[] key(byte[] value) {
        byte[] checked = Objects.requireNonNull(value, "key").clone();
        if (checked.length != KEY_BYTES) {
            Arrays.fill(checked, (byte) 0);
            throw new IllegalArgumentException("Vault master key must contain 32 bytes");
        }
        return checked;
    }
}
