package com.javaclaw.server.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.extension.spi.ExtensionDescriptor;
import com.javaclaw.extension.spi.ExtensionState;
import com.javaclaw.protocol.BundleRpcContracts;

/**
 * 第三方 Bundle 的持久化安装状态。
 *
 * @param descriptor 平台扩展描述
 * @param state 实时状态
 * @param manifestDigest 已签名 manifest 摘要
 * @param signingKeyId 管理员信任密钥
 * @param installDirectory installed 根下目录名
 * @param permissions 已确认的权限审阅快照
 * @param failureCount 连续失败次数
 * @param healthState 最近健康状态
 * @param lastHealthAt 最近健康探测时间
 * @param nextRetryAt 最早自动重试时间
 * @param lastFailure 最近一次脱敏失败
 * @param pendingTrashName REMOVING 状态已分配的 Trash 目录
 */
public record ThirdPartyExtensionRecord(
        ExtensionDescriptor descriptor,
        ExtensionState state,
        String manifestDigest,
        String signingKeyId,
        String installDirectory,
        BundleRpcContracts.PermissionReview permissions,
        int failureCount,
        BundleRpcContracts.HealthState healthState,
        Optional<Instant> lastHealthAt,
        Optional<Instant> nextRetryAt,
        Optional<String> lastFailure,
        Optional<String> pendingTrashName) {
    /** 固定可空容器并校验记录。 */
    public ThirdPartyExtensionRecord {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(manifestDigest, "manifestDigest");
        Objects.requireNonNull(signingKeyId, "signingKeyId");
        Objects.requireNonNull(installDirectory, "installDirectory");
        Objects.requireNonNull(permissions, "permissions");
        if (failureCount < 0) {
            throw new IllegalArgumentException("failureCount must not be negative");
        }
        Objects.requireNonNull(healthState, "healthState");
        lastHealthAt = Objects.requireNonNull(lastHealthAt, "lastHealthAt");
        nextRetryAt = Objects.requireNonNull(nextRetryAt, "nextRetryAt");
        lastFailure = Objects.requireNonNull(lastFailure, "lastFailure");
        pendingTrashName = Objects.requireNonNull(pendingTrashName, "pendingTrashName");
    }
}
