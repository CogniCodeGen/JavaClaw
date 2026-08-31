package com.javaclaw.server.extension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Trusted publisher keys prove provenance only; they never grant plugin permissions. */
public interface PluginTrustStore {
    /** 列出可信发布公钥及 metadata；签名信任与权限审批保持独立。 */
    List<TrustedKey> list();

    /** 按 keyId 查找信任公钥；不存在返回 Optional.empty。 */
    Optional<TrustedKey> find(String keyId);

    /** 按版本登记 X.509 编码公钥并支持幂等；不授予任何插件执行权限。 */
    TrustedKey add(String keyId, byte[] x509PublicKey, String label, long expectedRevision, String idempotencyKey);

    /** 按版本删除公钥信任并返回结果；后续验签不能继续依赖已移除来源。 */
    boolean remove(String keyId, long expectedRevision, String idempotencyKey);

    /**
     * 可信 Ed25519 公钥记录，字节数组与调用方隔离。
     *
     * @param keyId 信任公钥标识，不是凭据值
     * @param x509PublicKey 非空 X.509 编码公钥；构造和 accessor 均复制
     * @param label 公钥展示标签；可为空字符串
     * @param revision 乐观锁版本，更新时用于检测并发修改
     * @param createdAt 记录首次写入的时间
     */
    record TrustedKey(String keyId, byte[] x509PublicKey, String label, long revision, Instant createdAt) {
        /** 复制公钥字节并归一标签，防止外部修改已登记的信任材料。 */
        public TrustedKey {
            x509PublicKey = x509PublicKey.clone();
            label = label == null ? "" : label;
        }

        @Override
        public byte[] x509PublicKey() {
            return x509PublicKey.clone();
        }
    }
}
