package com.javaclaw.server.extension;

import java.time.Instant;

/**
 * Public-key metadata. Key material never crosses this service boundary.
 *
 * @param keyId 信任公钥标识，不是凭据值
 * @param label 公钥展示标签；可为空字符串
 * @param fingerprintSha256 公钥编码的 SHA-256 指纹
 * @param revision 持久修订号，用于乐观锁和缓存失效
 * @param createdAt 创建时间
 */
public record PluginTrustKeyView(
        String keyId, String label, String fingerprintSha256, long revision, Instant createdAt) {}
