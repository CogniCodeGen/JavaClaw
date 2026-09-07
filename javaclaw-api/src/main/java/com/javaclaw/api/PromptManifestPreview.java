package com.javaclaw.api;

import java.util.List;
import java.util.Objects;

/**
 * 下一 Turn 的只读 Prompt provenance 预览。
 *
 * <p>{@code coreTemplate} 和 {@code developerInstructions} 是用户可审阅文本；项目约定、Skill 与 Context 只通过 {@code sources} 返回元数据。预估
 * token 不是 Provider 账单值。
 *
 * @param role 精确 Agent Role
 * @param provider 精确 Provider/model
 * @param permissionProfile 精确权限配置
 * @param sources 按 Prompt 拼接顺序排列的脱敏来源
 * @param manifestDigest 包含未回传正文的完整预览快照 SHA-256
 * @param estimatedInputTokens 本地保守 token 估算
 * @param tokenEstimator 估算算法稳定标识
 * @param coreTemplate 当前内置 Core 模板
 * @param developerInstructions 当前 Role 指令，可为空字符串
 */
public record PromptManifestPreview(
        AgentRoleRef role,
        ProviderRef provider,
        PermissionProfileRef permissionProfile,
        List<PromptSourceMetadata> sources,
        String manifestDigest,
        long estimatedInputTokens,
        String tokenEstimator,
        String coreTemplate,
        String developerInstructions) {
    /** 校验引用、摘要与只读文本。 */
    public PromptManifestPreview {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(permissionProfile, "permissionProfile");
        sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        manifestDigest = Preconditions.digest(manifestDigest, "manifestDigest");
        if (estimatedInputTokens < 1) {
            throw new IllegalArgumentException("estimatedInputTokens must be positive");
        }
        tokenEstimator = text(tokenEstimator, "tokenEstimator");
        coreTemplate = text(coreTemplate, "coreTemplate");
        developerInstructions = Objects.requireNonNull(developerInstructions, "developerInstructions");
    }

    private static String text(String value, String name) {
        String checked = Objects.requireNonNull(value, name).strip();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }
}
