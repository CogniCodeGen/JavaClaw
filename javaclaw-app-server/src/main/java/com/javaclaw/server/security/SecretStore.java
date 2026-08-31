package com.javaclaw.server.security;

import java.time.Instant;
import java.util.Optional;

import com.javaclaw.core.api.ThreadId;

/** Encrypted secret boundary; protocol and diagnostics expose metadata only. */
public interface SecretStore {
    /** 按命名空间和名称加密保存或轮换凭据，返回 metadata；输入 char[] 的清空仍由调用方负责。 */
    SecretMetadata put(String namespace, String name, char[] value, String idempotencyKey);

    /** 仅向受信任服务端适配器解密凭据；不存在返回空值，调用方使用后必须清空返回 char[]，不得进入 Wire/日志。 */
    Optional<char[]> resolve(String namespace, String name);

    /** 查询凭据存在性和修订号；不解密正文，不存在返回 Optional.empty。 */
    Optional<SecretMetadata> metadata(String namespace, String name);

    /** 按 expectedRevision 删除凭据并支持幂等；版本不一致拒绝删除，返回是否移除记录。 */
    boolean remove(String namespace, String name, long expectedRevision, String idempotencyKey);

    /** 不携带幂等键的凭据删除入口，仍校验 expectedRevision。 */
    default boolean remove(String namespace, String name, long expectedRevision) {
        return remove(namespace, name, expectedRevision, null);
    }

    /** 生成新的外置主密钥版本并重加密现存凭据；先保存密钥再提交密文，返回新密钥版本而非凭据数量。 */
    int rotateMasterKey();

    /**
     * 可以安全用于配置查询的凭据 metadata，永不包含凭据值。
     *
     * @param namespace 凭据所属命名空间，与名称一起定位记录
     * @param name 展示名称
     * @param configured 是否已配置可用凭据；不包含凭据值
     * @param revision 持久修订号，用于乐观锁和缓存失效
     * @param updatedAt 最近更新时间；尚未配置的资源可为 null
     */
    record SecretMetadata(String namespace, String name, boolean configured, long revision, Instant updatedAt) {
        /** 要求命名空间、名称和正数修订号，防止无归属的凭据 metadata。 */
        public SecretMetadata {
            namespace = ThreadId.required(namespace, "namespace");
            name = ThreadId.required(name, "name");
            if (revision < 1) {
                throw new IllegalArgumentException("revision must be positive");
            }
        }
    }
}
