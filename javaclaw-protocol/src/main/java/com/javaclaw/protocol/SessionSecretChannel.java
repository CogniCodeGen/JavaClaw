package com.javaclaw.protocol;

import java.security.KeyPair;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * App Server 单条连接拥有的 X25519 Secret 解封通道。
 *
 * <p>成功解封的 envelope 指纹会在当前连接内记为已消费，重复提交一律拒绝。集合达到上限后客户端必须重连，避免无界 session 状态。关闭连接会释放 JCA 私钥对象和 replay 集合；JCA Provider
 * 不提供可移植的原位私钥清零 API。
 */
public final class SessionSecretChannel implements AutoCloseable {
    private static final int MAX_CONSUMED_ENVELOPES = 4_096;

    private final String keyId;
    private final SessionKeyInfo publicKey;
    private final Set<String> consumed = new LinkedHashSet<>();
    private KeyPair keyPair;

    private SessionSecretChannel(KeyPair keyPair) {
        this.keyPair = Objects.requireNonNull(keyPair, "keyPair");
        keyId = "session-" + UUID.randomUUID();
        publicKey = new SessionKeyInfo(
                SessionKeyInfo.ALGORITHM,
                keyId,
                SessionSecretCryptography.encode(keyPair.getPublic().getEncoded()));
    }

    /**
     * 为一条新连接创建独立解封通道。
     *
     * @return 未共享私钥的新通道
     */
    public static SessionSecretChannel open() {
        return open(new SecureRandom());
    }

    static SessionSecretChannel open(SecureRandom random) {
        return new SessionSecretChannel(SessionSecretCryptography.keyPair(Objects.requireNonNull(random, "random")));
    }

    /**
     * 返回可以放入 initialize result 的公钥信息。
     *
     * @return 当前连接公钥
     */
    public synchronized SessionKeyInfo publicKey() {
        requireOpen();
        return publicKey;
    }

    /**
     * 校验用途、会话、认证标签和 replay 后解封 Secret。
     *
     * <p>调用方拥有返回数组，写入 Vault 或下游受信端口后必须立即清零。
     *
     * @param sealed 客户端封装的密文
     * @param expectedPurpose 当前 RPC 字段约定的精确用途
     * @return UTF-8 Secret 明文字节
     */
    public synchronized byte[] unseal(SealedSecret sealed, String expectedPurpose) {
        requireOpen();
        SealedSecret checked = Objects.requireNonNull(sealed, "sealed");
        String purpose = SessionKeyInfo.identifier(expectedPurpose, "expectedPurpose", 240);
        requireEnvelopeIdentity(checked, purpose);
        if (consumed.size() >= MAX_CONSUMED_ENVELOPES) {
            throw new SecretSealingException("当前会话的 Secret 写入次数已达上限，请重新连接");
        }
        String fingerprint = SessionSecretCryptography.fingerprint(checked);
        if (consumed.contains(fingerprint)) {
            throw new SecretSealingException("SealedSecret 已在当前会话中使用");
        }
        byte[] plaintext = decrypt(checked, purpose);
        consumed.add(fingerprint);
        return plaintext;
    }

    /** 关闭当前连接的解封能力并丢弃 replay 状态。 */
    @Override
    public synchronized void close() {
        keyPair = null;
        consumed.clear();
    }

    private byte[] decrypt(SealedSecret sealed, String purpose) {
        PublicKey ephemeral = SessionSecretCryptography.publicKey(sealed.ephemeralPublicKey());
        byte[] key = SessionSecretCryptography.derive(keyPair.getPrivate(), ephemeral, keyId, purpose);
        byte[] nonce = SessionSecretCryptography.decode(sealed.nonce(), 12, "nonce");
        byte[] ciphertext = SessionSecretCryptography.decode(
                sealed.ciphertext(), SessionSecretCryptography.MAX_PLAINTEXT_BYTES + 16, "ciphertext");
        byte[] aad = SessionSecretCryptography.aad(keyId, purpose, sealed.ephemeralPublicKey());
        try {
            if (nonce.length != SessionSecretCryptography.NONCE_BYTES || ciphertext.length < 17) {
                throw new SecretSealingException("SealedSecret shape is invalid");
            }
            return SessionSecretCryptography.decrypt(key, nonce, aad, ciphertext);
        } finally {
            Arrays.fill(key, (byte) 0);
            Arrays.fill(nonce, (byte) 0);
            Arrays.fill(ciphertext, (byte) 0);
            Arrays.fill(aad, (byte) 0);
        }
    }

    private void requireEnvelopeIdentity(SealedSecret sealed, String purpose) {
        if (!keyId.equals(sealed.keyId())) {
            throw new SecretSealingException("SealedSecret 不属于当前会话");
        }
        if (!purpose.equals(sealed.purpose())) {
            throw new SecretSealingException("SealedSecret purpose 不匹配");
        }
    }

    private void requireOpen() {
        if (keyPair == null) {
            throw new SecretSealingException("会话 Secret 通道已关闭");
        }
    }
}
