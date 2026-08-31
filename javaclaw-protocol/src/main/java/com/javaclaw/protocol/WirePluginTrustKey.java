package com.javaclaw.protocol;

import java.time.Instant;

/**
 * 插件签名信任公钥的元数据；不包含私钥。
 *
 * @param keyId 信任公钥标识，不是私钥或凭据
 * @param label 公钥的展示标签
 * @param fingerprintSha256 公钥编码的 SHA-256 指纹
 * @param revision 资源修订号，更新时用作 expectedRevision
 * @param createdAt 创建时间；有效持久记录中非空
 */
public record WirePluginTrustKey(
        String keyId, String label, String fingerprintSha256, long revision, Instant createdAt) {}
