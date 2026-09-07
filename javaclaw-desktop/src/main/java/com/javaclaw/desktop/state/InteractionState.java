package com.javaclaw.desktop.state;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.ApprovalRecord;
import com.javaclaw.api.RoleLifecycle;

/**
 * 用户选择、等待审批和可恢复错误的不可变快照。
 *
 * @param roles 当前可用于新 Turn 的 Agent Role
 * @param selectedRole 当前显式选择；为空时由服务端解析 Thread 或 Workspace 默认绑定
 * @param pendingApprovals 等待用户决策的审批
 * @param inputs Workflow 与 MCP elicitation 等平台输入请求
 * @param busy 是否正在执行一次短客户端操作
 * @param error 最近一次可恢复错误；没有错误时为空
 */
public record InteractionState(
        List<AgentRole> roles,
        Optional<AgentRole> selectedRole,
        List<ApprovalRecord> pendingApprovals,
        InputInteractionState inputs,
        boolean busy,
        Optional<String> error) {
    /** 复制集合并校验选择与审批状态。 */
    public InteractionState {
        roles = List.copyOf(roles);
        selectedRole = Objects.requireNonNull(selectedRole, "selectedRole");
        pendingApprovals = List.copyOf(pendingApprovals);
        Objects.requireNonNull(inputs, "inputs");
        error = Objects.requireNonNull(error, "error").map(String::strip).filter(value -> !value.isEmpty());
        if (roles.stream().anyMatch(role -> role.lifecycle() != RoleLifecycle.ACTIVE)) {
            throw new IllegalArgumentException("roles contain unavailable lifecycle");
        }
        requireSelectedRole(roles, selectedRole);
        requirePendingApprovals(pendingApprovals);
    }

    private static void requireSelectedRole(List<AgentRole> roles, Optional<AgentRole> selectedRole) {
        boolean missing = selectedRole
                .filter(selected -> roles.stream()
                        .noneMatch(role -> role.id().equals(selected.id()) && role.revision() == selected.revision()))
                .isPresent();
        if (missing) {
            throw new IllegalArgumentException("selected role is not present");
        }
    }

    private static void requirePendingApprovals(List<ApprovalRecord> pendingApprovals) {
        if (pendingApprovals.stream().anyMatch(approval -> !approval.pending())) {
            throw new IllegalArgumentException("pending approval list contains terminal state");
        }
    }

    /** @return 默认交互状态 */
    public static InteractionState initial() {
        return new InteractionState(
                List.of(), Optional.empty(), List.of(), InputInteractionState.initial(), false, Optional.empty());
    }
}
