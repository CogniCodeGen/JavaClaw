package com.javaclaw.protocol;

import java.security.KeyPair;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

/** SDK 在 JSON-RPC 编码前使用的会话 Secret 封装器。 */
public final class SessionSecretSealer {
    private SessionSecretSealer() {}

    /**
     * 使用 initialize 公钥封装 PasswordField 等敏感输入。
     *
     * <p>调用方继续拥有 {@code secret}，应在本方法返回后清零；实现会清理自己创建的 UTF-8 明文与派生密钥。
     *
     * @param session initialize 返回的会话公钥
     * @param purpose 与目标 RPC 字段约定的精确用途
     * @param secret 待封装字符
     * @return 只含密文的 wire envelope
     */
    public static SealedSecret seal(SessionKeyInfo session, String purpose, char[] secret) {
        return seal(session, purpose, secret, new SecureRandom());
    }

    static SealedSecret seal(SessionKeyInfo session, String purpose, char[] secret, SecureRandom random) {
        SessionKeyInfo checkedSession = Objects.requireNonNull(session, "session");
        String checkedPurpose = SessionKeyInfo.identifier(purpose, "purpose", 240);
        Objects.requireNonNull(secret, "secret");
        SecureRandom checkedRandom = Objects.requireNonNull(random, "random");
        PublicKey serverKey = SessionSecretCryptography.publicKey(checkedSession.encodedPublicKey());
        KeyPair ephemeral = SessionSecretCryptography.keyPair(checkedRandom);
        String ephemeralPublicKey =
                SessionSecretCryptography.encode(ephemeral.getPublic().getEncoded());
        byte[] key = SessionSecretCryptography.derive(
                ephemeral.getPrivate(), serverKey, checkedSession.keyId(), checkedPurpose);
        byte[] nonce = new byte[SessionSecretCryptography.NONCE_BYTES];
        byte[] plaintext = null;
        byte[] aad = null;
        checkedRandom.nextBytes(nonce);
        try {
            plaintext = SessionSecretCryptography.utf8(secret);
            aad = SessionSecretCryptography.aad(checkedSession.keyId(), checkedPurpose, ephemeralPublicKey);
            byte[] ciphertext = SessionSecretCryptography.encrypt(key, nonce, aad, plaintext);
            try {
                return new SealedSecret(
                        checkedSession.keyId(),
                        checkedPurpose,
                        ephemeralPublicKey,
                        SessionSecretCryptography.encode(nonce),
                        SessionSecretCryptography.encode(ciphertext));
            } finally {
                Arrays.fill(ciphertext, (byte) 0);
            }
        } finally {
            Arrays.fill(key, (byte) 0);
            Arrays.fill(nonce, (byte) 0);
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
            if (aad != null) {
                Arrays.fill(aad, (byte) 0);
            }
        }
    }
}
