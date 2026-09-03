package com.javaclaw.api;

import java.util.Locale;
import java.util.Objects;

/**
 * 由应用代码版本化维护的 Agent Profile 创建预设。
 *
 * <p>预设只提供创建普通 Profile 所需的初始值，不参与 Turn 的角色分支或运行时授权。
 *
 * @param id 稳定预设标识
 * @param revision 预设版本，从 1 开始
 * @param displayName 用户可见名称
 * @param description 简短职责说明
 * @param systemInstruction 可审阅的初始 system instruction
 * @param digest system instruction 的小写 SHA-256
 * @param defaultBudget 建议的 Turn 默认预算
 */
public record AgentProfilePreset(
        String id,
        long revision,
        String displayName,
        String description,
        String systemInstruction,
        String digest,
        TurnBudget defaultBudget) {
    /** 校验预设身份、内容摘要和预算。 */
    public AgentProfilePreset {
        id = Preconditions.identifier(id, "id");
        revision = Preconditions.positive(revision, "revision");
        displayName = Preconditions.boundedText(displayName, "displayName", 1_000);
        description = Preconditions.boundedText(description, "description", 4_000);
        systemInstruction = Preconditions.boundedText(systemInstruction, "systemInstruction", 1_048_576);
        digest = Preconditions.text(digest, "digest").toLowerCase(Locale.ROOT);
        if (!digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("digest must be a lowercase SHA-256 value");
        }
        Objects.requireNonNull(defaultBudget, "defaultBudget");
    }
}
