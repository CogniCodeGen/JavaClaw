package com.javaclaw.server.security.vault;

import java.util.Arrays;
import java.util.Objects;

/**
 * H2 中保存的 Vault 密文结构。
 *
 * @param formatVersion 加密格式版本
 * @param nonce GCM 96-bit 随机 nonce
 * @param ciphertext 带认证标签的密文
 */
record EncryptedSecret(int formatVersion, byte[] nonce, byte[] ciphertext) {
    EncryptedSecret {
        if (formatVersion < 1) {
            throw new IllegalArgumentException("formatVersion must be positive");
        }
        nonce = Objects.requireNonNull(nonce, "nonce").clone();
        ciphertext = Objects.requireNonNull(ciphertext, "ciphertext").clone();
        if (nonce.length != VaultCipher.NONCE_BYTES || ciphertext.length < 16) {
            throw new IllegalArgumentException("encrypted secret shape is invalid");
        }
    }

    @Override
    public byte[] nonce() {
        return nonce.clone();
    }

    @Override
    public byte[] ciphertext() {
        return ciphertext.clone();
    }

    /** 清零当前对象持有的 nonce 与密文字节。 */
    void destroy() {
        Arrays.fill(nonce, (byte) 0);
        Arrays.fill(ciphertext, (byte) 0);
    }
}
