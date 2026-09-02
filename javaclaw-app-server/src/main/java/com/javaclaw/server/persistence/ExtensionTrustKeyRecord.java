package com.javaclaw.server.persistence;

import java.time.Instant;
import java.util.Objects;

import com.javaclaw.protocol.BundleRpcContracts;

/**
 * 第三方 Bundle Trust Key 的持久化记录。
 *
 * @param metadata 可公开展示的密钥元数据
 * @param encodedKey X.509 DER 编码 Ed25519 公钥；构造与读取均复制
 */
public record ExtensionTrustKeyRecord(BundleRpcContracts.TrustKey metadata, byte[] encodedKey) {
    /** 校验并取得公钥字节所有权。 */
    public ExtensionTrustKeyRecord {
        Objects.requireNonNull(metadata, "metadata");
        encodedKey = Objects.requireNonNull(encodedKey, "encodedKey").clone();
        if (encodedKey.length < 1 || encodedKey.length > 4096) {
            throw new IllegalArgumentException("encoded key size is invalid");
        }
    }

    @Override
    public byte[] encodedKey() {
        return encodedKey.clone();
    }

    /**
     * 建立初次导入记录。
     *
     * @param keyId 密钥标识
     * @param fingerprint DER 内容指纹
     * @param attachmentDigest 来源 Attachment
     * @param encodedKey DER 公钥
     * @param now 创建时间
     * @return ACTIVE revision 1 记录
     */
    public static ExtensionTrustKeyRecord active(
            String keyId, String fingerprint, String attachmentDigest, byte[] encodedKey, Instant now) {
        return new ExtensionTrustKeyRecord(
                new BundleRpcContracts.TrustKey(
                        keyId, fingerprint, attachmentDigest, 1, BundleRpcContracts.TrustState.ACTIVE, now, now),
                encodedKey);
    }
}
