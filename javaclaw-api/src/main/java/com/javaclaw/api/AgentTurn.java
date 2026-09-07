package com.javaclaw.api;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 单次 Turn 的不可变快照。
 *
 * @param id Turn 标识
 * @param threadId 所属 Thread
 * @param status 生命周期状态
 * @param revision 乐观锁版本，从 1 开始
 * @param budget 本 Turn 的冻结预算
 * @param role 冻结 Agent Role 引用
 * @param provider 冻结 Provider 与模型引用
 * @param permissionProfile 冻结权限配置引用
 * @param executionRoot 冻结的服务端权威执行根
 * @param promptManifestDigest Prompt 来源清单的 SHA-256
 * @param toolCatalogDigest 冻结工具目录的 SHA-256
 * @param errorCode 失败代码；未失败时为空
 * @param createdAt 创建时间
 * @param updatedAt 最近修改时间
 * @param resolvedConfig 已冻结配置的脱敏摘要，不可为空
 */
public record AgentTurn(
        TurnId id,
        ThreadId threadId,
        TurnStatus status,
        long revision,
        TurnBudget budget,
        AgentRoleRef role,
        ProviderRef provider,
        PermissionProfileRef permissionProfile,
        Path executionRoot,
        String promptManifestDigest,
        String toolCatalogDigest,
        Optional<String> errorCode,
        Instant createdAt,
        Instant updatedAt,
        ResolvedTurnConfigSummary resolvedConfig) {
    /** 校验 Turn 快照的不变量。 */
    public AgentTurn {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(status, "status");
        revision = Preconditions.positive(revision, "revision");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(permissionProfile, "permissionProfile");
        executionRoot = Objects.requireNonNull(executionRoot, "executionRoot")
                .toAbsolutePath()
                .normalize();
        promptManifestDigest = Preconditions.digest(promptManifestDigest, "promptManifestDigest");
        toolCatalogDigest = Preconditions.digest(toolCatalogDigest, "toolCatalogDigest");
        errorCode = Objects.requireNonNull(errorCode, "errorCode")
                .map(String::strip)
                .filter(value -> !value.isEmpty());
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(resolvedConfig, "resolvedConfig");
        // 重复的展示字段必须与冻结摘要一致，避免客户端看到与执行语义不同的配置。
        if (!role.equals(resolvedConfig.role())
                || !provider.equals(resolvedConfig.provider())
                || !permissionProfile.equals(resolvedConfig.permissionProfile())
                || !budget.equals(resolvedConfig.budget())
                || !promptManifestDigest.equals(resolvedConfig.promptManifestDigest())
                || !toolCatalogDigest.equals(resolvedConfig.toolCatalogDigest())) {
            throw new IllegalArgumentException("Turn fields must match the resolved configuration summary");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }
}
