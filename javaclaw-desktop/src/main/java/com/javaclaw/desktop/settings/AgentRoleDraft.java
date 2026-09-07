package com.javaclaw.desktop.settings;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleSpec;
import com.javaclaw.api.CapabilityNarrowing;
import com.javaclaw.api.ModelPreference;
import com.javaclaw.api.PermissionConstraint;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ReasoningPreference;
import com.javaclaw.api.RoleLifecycle;

/**
 * Agent Role 表单草稿；角色只允许收窄能力，不携带权限授权、凭据或预算。
 *
 * @param id 稳定标识
 * @param name 显示名称
 * @param description 角色用途
 * @param developerInstructions 开发者指令
 * @param provider 可选固定模型；为空时继承执行配置
 * @param reasoning 可选推理偏好
 * @param capabilities 每行一个能力名；星号表示继承，空白表示全部禁用
 * @param skills 每行一个 Skill 名；星号表示继承，空白表示全部禁用
 * @param constraint 权限收窄方式
 * @param lifecycle 生命周期
 * @param extensions 不执行的命名空间扩展，编辑时原样保留
 */
public record AgentRoleDraft(
        String id,
        String name,
        String description,
        String developerInstructions,
        Optional<ProviderRef> provider,
        Optional<ReasoningPreference> reasoning,
        String capabilities,
        String skills,
        PermissionConstraint constraint,
        RoleLifecycle lifecycle,
        Map<String, String> extensions) {
    /** 保留用户草稿并防御性复制扩展。 */
    public AgentRoleDraft {
        id = Objects.requireNonNullElse(id, "");
        name = Objects.requireNonNullElse(name, "");
        description = Objects.requireNonNullElse(description, "");
        developerInstructions = Objects.requireNonNullElse(developerInstructions, "");
        provider = Objects.requireNonNull(provider, "provider");
        reasoning = Objects.requireNonNull(reasoning, "reasoning");
        capabilities = Objects.requireNonNullElse(capabilities, "*");
        skills = Objects.requireNonNullElse(skills, "*");
        constraint = Objects.requireNonNull(constraint, "constraint");
        lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        extensions = Map.copyOf(extensions);
    }

    /** @return 默认继承模型和能力的新角色草稿 */
    public static AgentRoleDraft empty() {
        return new AgentRoleDraft(
                "",
                "",
                "",
                "",
                Optional.empty(),
                Optional.empty(),
                "*",
                "*",
                PermissionConstraint.INHERIT,
                RoleLifecycle.ACTIVE,
                Map.of());
    }

    /** @param role 权威角色快照 @return 保留全部配置的表单草稿 */
    public static AgentRoleDraft from(AgentRole role) {
        AgentRoleSpec spec = Objects.requireNonNull(role, "role").spec();
        return new AgentRoleDraft(
                role.id(),
                spec.name(),
                spec.description(),
                spec.developerInstructions(),
                spec.model().map(ModelPreference::provider),
                spec.reasoning(),
                format(spec.narrowing().capabilities()),
                format(spec.narrowing().skills()),
                spec.permissionConstraint(),
                role.lifecycle(),
                spec.extensions());
    }

    /** @return 通过领域校验的角色配置 */
    public AgentRoleSpec toSpec() {
        return new AgentRoleSpec(
                name,
                description,
                developerInstructions,
                provider.map(ModelPreference::new),
                reasoning,
                new CapabilityNarrowing(parse(capabilities), parse(skills)),
                constraint,
                extensions);
    }

    private static Optional<Set<String>> parse(String value) {
        if (value.strip().equals("*")) {
            return Optional.empty();
        }
        return Optional.of(Arrays.stream(value.split("[,\\n]"))
                .map(String::strip)
                .filter(item -> !item.isEmpty())
                .collect(Collectors.toUnmodifiableSet()));
    }

    private static String format(Optional<Set<String>> values) {
        return values.map(items -> items.stream().sorted().collect(Collectors.joining("\n")))
                .orElse("*");
    }
}
