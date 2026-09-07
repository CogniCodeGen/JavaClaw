package com.javaclaw.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 客户端可审阅的执行配置摘要，不暴露 Prompt 正文、凭据或内部权限策略。
 *
 * @param role 精确 Role 版本
 * @param provider 精确 Provider 和模型
 * @param permissionProfile 精确权限版本
 * @param approvalPolicy 有效最低审批要求
 * @param budget 已分配的有限预算
 * @param effectiveCapabilities 冻结能力集合
 * @param reasoning 冻结推理偏好
 * @param promptManifestDigest Prompt manifest 的 SHA-256
 * @param toolCatalogDigest 工具目录的 SHA-256
 * @param modelLocked 是否由 Role 固定模型
 * @param provenance 脱敏字段来源与 revision
 */
public record ResolvedTurnConfigSummary(
        AgentRoleRef role,
        ProviderRef provider,
        PermissionProfileRef permissionProfile,
        ApprovalPolicy approvalPolicy,
        TurnBudget budget,
        Set<String> effectiveCapabilities,
        Optional<ReasoningPreference> reasoning,
        String promptManifestDigest,
        String toolCatalogDigest,
        boolean modelLocked,
        List<ConfigurationProvenance> provenance) {
    /** 校验摘要并复制集合。 */
    public ResolvedTurnConfigSummary {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(permissionProfile, "permissionProfile");
        Objects.requireNonNull(approvalPolicy, "approvalPolicy");
        Objects.requireNonNull(budget, "budget");
        effectiveCapabilities = Set.copyOf(Objects.requireNonNull(effectiveCapabilities, "effectiveCapabilities"));
        reasoning = Objects.requireNonNull(reasoning, "reasoning");
        promptManifestDigest = Preconditions.digest(promptManifestDigest, "promptManifestDigest");
        toolCatalogDigest = Preconditions.digest(toolCatalogDigest, "toolCatalogDigest");
        provenance = List.copyOf(Objects.requireNonNull(provenance, "provenance"));
    }
}
