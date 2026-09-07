package com.javaclaw.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Turn 创建时冻结的完整执行配置；更新 Role 或默认值不能改变本快照，实时撤权仍必须执行。
 *
 * @param role 精确 Role 版本
 * @param provider 精确 Provider 和模型
 * @param permissionProfile 精确权限版本
 * @param approvalPolicy 有效最低审批要求
 * @param budget 已分配的有限预算
 * @param effectiveCapabilities 冻结能力集合
 * @param reasoning 冻结推理偏好
 * @param permissionConstraint Role 的运行时权限上限
 * @param effectiveSkills 冻结 Skill 上限；缺省继承，空集合禁用全部
 * @param promptManifestDigest 已冻结 Prompt manifest 的 SHA-256
 * @param toolCatalogDigest 已冻结工具目录的 SHA-256
 * @param provenance 各字段的来源与 revision
 */
public record ResolvedTurnConfig(
        AgentRoleRef role,
        ProviderRef provider,
        PermissionProfileRef permissionProfile,
        ApprovalPolicy approvalPolicy,
        TurnBudget budget,
        Set<String> effectiveCapabilities,
        Optional<ReasoningPreference> reasoning,
        PermissionConstraint permissionConstraint,
        Optional<Set<String>> effectiveSkills,
        String promptManifestDigest,
        String toolCatalogDigest,
        List<ConfigurationProvenance> provenance) {
    /** 校验配置完整性并复制不可变集合。 */
    public ResolvedTurnConfig {
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
        Objects.requireNonNull(permissionConstraint, "permissionConstraint");
        effectiveSkills =
                Objects.requireNonNull(effectiveSkills, "effectiveSkills").map(Set::copyOf);
    }

    /** @return 根据模型来源生成的客户端安全摘要 */
    public ResolvedTurnConfigSummary summary() {
        boolean locked = provenance.stream()
                .anyMatch(value -> value.field().equals("provider") && value.source() == ConfigurationSource.ROLE);
        return summary(locked);
    }

    /**
     * 生成不包含 Prompt 正文和内部权限策略的客户端摘要。
     *
     * @param modelLocked 是否由 Role 固定模型
     * @return 可安全返回客户端的执行摘要
     */
    public ResolvedTurnConfigSummary summary(boolean modelLocked) {
        return new ResolvedTurnConfigSummary(
                role,
                provider,
                permissionProfile,
                approvalPolicy,
                budget,
                effectiveCapabilities,
                reasoning,
                promptManifestDigest,
                toolCatalogDigest,
                modelLocked,
                provenance);
    }
}
