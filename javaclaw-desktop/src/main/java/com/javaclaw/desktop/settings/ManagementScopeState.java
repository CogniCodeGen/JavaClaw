package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.Workspace;

/**
 * 设置中心 Workspace 作用域的不可变状态。
 *
 * @param phase 目录读取阶段
 * @param workspaces 可选的活动 Workspace
 * @param selected 当前固定作用域
 * @param message 简明状态或错误
 * @param epoch 用于丢弃旧响应的请求代次
 */
record ManagementScopeState(
        SettingsLoadState phase, List<Workspace> workspaces, Optional<Workspace> selected, String message, long epoch) {
    ManagementScopeState {
        phase = Objects.requireNonNull(phase, "phase");
        workspaces = List.copyOf(workspaces);
        selected = Objects.requireNonNull(selected, "selected");
        message = Objects.requireNonNullElse(message, "");
    }

    static ManagementScopeState initial() {
        return new ManagementScopeState(SettingsLoadState.INITIAL, List.of(), Optional.empty(), "", 0);
    }

    boolean loading() {
        return phase == SettingsLoadState.LOADING;
    }

    /**
     * 返回设置中心已经固定的 Workspace 快照。
     *
     * <p>目录重载期间或读取失败后仍保留此值，页面据此保留草稿；它不表示当前允许写入。
     *
     * @return 已冻结的 Workspace；首次成功读取前为空
     */
    Optional<Workspace> frozenSelection() {
        return selected;
    }

    /** @return 固定 Workspace 仍在当前活动目录中时的可写作用域 */
    Optional<Workspace> availableSelection() {
        if (phase != SettingsLoadState.READY) {
            return Optional.empty();
        }
        return selected.flatMap(value -> workspaces.stream()
                .filter(candidate -> candidate.id().equals(value.id()))
                .findFirst());
    }
}
