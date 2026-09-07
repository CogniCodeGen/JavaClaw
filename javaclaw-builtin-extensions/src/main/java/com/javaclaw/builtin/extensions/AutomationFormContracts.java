package com.javaclaw.builtin.extensions;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ApprovalPolicy;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ReasoningPreference;
import com.javaclaw.builtin.contracts.OrchestrationContracts;

/** 动态管理表单的独立权威选择；只转换表现层形状，配置仍由服务端唯一解析。 */
final class AutomationFormContracts {
    private AutomationFormContracts() {}

    /**
     * 自动化启动表单，各引用来自互相独立的权威目录。
     *
     * @param definitionId 业务定义标识
     * @param role 精确角色
     * @param provider 精确模型
     * @param permissionProfile 精确权限
     * @param approvalPolicy 可收窄审批要求
     * @param reasoning 推理偏好
     * @param maximumTurns 总 Turn 上限
     * @param inputTokens 输入 token 总上限
     * @param outputTokens 输出 token 总上限
     * @param toolCalls 工具调用总上限
     */
    record StartPayload(
            String definitionId,
            AgentRoleRef role,
            ProviderRef provider,
            PermissionProfileRef permissionProfile,
            ApprovalPolicy approvalPolicy,
            ReasoningPreference reasoning,
            int maximumTurns,
            long inputTokens,
            long outputTokens,
            int toolCalls) {
        OrchestrationContracts.StartRequest toRequest() {
            return new OrchestrationContracts.StartRequest(
                    definitionId,
                    execution(role, provider, permissionProfile, approvalPolicy, reasoning),
                    new OrchestrationContracts.ExecutionBudget(maximumTurns, inputTokens, outputTokens, toolCalls));
        }
    }

    static ExecutionOverrides execution(
            AgentRoleRef role,
            ProviderRef provider,
            PermissionProfileRef permissions,
            ApprovalPolicy approval,
            ReasoningPreference reasoning) {
        return new ExecutionOverrides(
                Optional.of(Objects.requireNonNull(role, "role")),
                Optional.of(Objects.requireNonNull(provider, "provider")),
                Optional.of(Objects.requireNonNull(permissions, "permissionProfile")),
                Optional.of(Objects.requireNonNull(approval, "approvalPolicy")),
                Optional.empty(),
                Optional.empty(),
                Optional.of(Objects.requireNonNull(reasoning, "reasoning")));
    }
}
