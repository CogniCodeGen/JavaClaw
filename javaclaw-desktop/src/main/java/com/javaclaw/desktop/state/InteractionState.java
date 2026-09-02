package com.javaclaw.desktop.state;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.ApprovalRecord;
import com.javaclaw.api.ProfileLifecycle;

/**
 * 用户选择、等待审批和可恢复错误的不可变快照。
 *
 * @param profiles 当前可用于新 Turn 的 Agent Profile
 * @param selectedProfile 当前显式选择；为空时由服务端解析 Thread 或 Workspace 默认绑定
 * @param pendingApprovals 等待用户决策的审批
 * @param inputs Workflow 与 MCP elicitation 等平台输入请求
 * @param busy 是否正在执行一次短客户端操作
 * @param error 最近一次可恢复错误；没有错误时为空
 */
public record InteractionState(
        List<AgentProfile> profiles,
        Optional<AgentProfile> selectedProfile,
        List<ApprovalRecord> pendingApprovals,
        InputInteractionState inputs,
        boolean busy,
        Optional<String> error) {
    /** 复制集合并校验选择与审批状态。 */
    public InteractionState {
        profiles = List.copyOf(profiles);
        selectedProfile = Objects.requireNonNull(selectedProfile, "selectedProfile");
        pendingApprovals = List.copyOf(pendingApprovals);
        Objects.requireNonNull(inputs, "inputs");
        error = Objects.requireNonNull(error, "error").map(String::strip).filter(value -> !value.isEmpty());
        if (profiles.stream().anyMatch(profile -> profile.lifecycle() != ProfileLifecycle.ACTIVE)) {
            throw new IllegalArgumentException("profiles contain unavailable lifecycle");
        }
        requireSelectedProfile(profiles, selectedProfile);
        requirePendingApprovals(pendingApprovals);
    }

    private static void requireSelectedProfile(List<AgentProfile> profiles, Optional<AgentProfile> selectedProfile) {
        boolean missing = selectedProfile
                .filter(selected -> profiles.stream()
                        .noneMatch(profile ->
                                profile.id().equals(selected.id()) && profile.revision() == selected.revision()))
                .isPresent();
        if (missing) {
            throw new IllegalArgumentException("selected profile is not present");
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
