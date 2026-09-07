package com.javaclaw.api;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 可复用的 Agent 行为定义，不携带权限授权、凭据或预算。
 *
 * @param name 用户可见名称，不可为空
 * @param description 职责说明，可为空字符串
 * @param developerInstructions Role 开发者指令，可为空字符串
 * @param model 固定模型选择，未锁定时为空
 * @param reasoning 推理偏好，继承时为空
 * @param narrowing 能力和 Skill 的收窄上限
 * @param permissionConstraint 继承或只读权限上限
 * @param extensions 不执行的命名空间 TOML 正文；未注册 Schema 前仅用于无损交换
 */
public record AgentRoleSpec(
        String name,
        String description,
        String developerInstructions,
        Optional<ModelPreference> model,
        Optional<ReasoningPreference> reasoning,
        CapabilityNarrowing narrowing,
        PermissionConstraint permissionConstraint,
        Map<String, String> extensions) {
    /** 校验角色正文和收窄配置并复制扩展数据。 */
    public AgentRoleSpec {
        name = Preconditions.boundedText(name, "name", 1000);
        description = Objects.requireNonNull(description, "description").strip();
        developerInstructions = Objects.requireNonNull(developerInstructions, "developerInstructions")
                .strip();
        if (description.length() > 4000 || developerInstructions.length() > 1_048_576) {
            throw new IllegalArgumentException("Role text exceeds limit");
        }
        model = Objects.requireNonNull(model, "model");
        reasoning = Objects.requireNonNull(reasoning, "reasoning");
        Objects.requireNonNull(narrowing, "narrowing");
        Objects.requireNonNull(permissionConstraint, "permissionConstraint");
        extensions = Map.copyOf(Objects.requireNonNull(extensions, "extensions"));
        extensions.forEach((key, value) -> {
            Preconditions.identifier(key, "extension namespace");
            Objects.requireNonNull(value, "extension content");
        });
    }
}
