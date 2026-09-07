package com.javaclaw.api;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 某一作用域显式选择的执行字段；缺省字段向上继承，安全边界只能收窄。
 *
 * @param role 可选精确 Role
 * @param provider 可选精确 Provider 与模型
 * @param permissionProfile 可选精确权限配置
 * @param approvalPolicy 可选最低审批要求
 * @param budget 可选预算上限
 * @param visibleCapabilities 可选能力上限，存在但为空时禁用全部能力
 * @param reasoning 可选推理偏好
 */
public record ExecutionOverrides(
        Optional<AgentRoleRef> role,
        Optional<ProviderRef> provider,
        Optional<PermissionProfileRef> permissionProfile,
        Optional<ApprovalPolicy> approvalPolicy,
        Optional<TurnBudget> budget,
        Optional<Set<String>> visibleCapabilities,
        Optional<ReasoningPreference> reasoning) {
    /** 校验 Optional 容器，冻结能力集合。 */
    public ExecutionOverrides {
        role = Objects.requireNonNull(role, "role");
        provider = Objects.requireNonNull(provider, "provider");
        permissionProfile = Objects.requireNonNull(permissionProfile, "permissionProfile");
        approvalPolicy = Objects.requireNonNull(approvalPolicy, "approvalPolicy");
        budget = Objects.requireNonNull(budget, "budget");
        visibleCapabilities = Objects.requireNonNull(visibleCapabilities, "visibleCapabilities")
                .map(Set::copyOf);
        reasoning = Objects.requireNonNull(reasoning, "reasoning");
    }

    /** @return 全部继承上级作用域的选择 */
    public static ExecutionOverrides empty() {
        return new ExecutionOverrides(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }
}
