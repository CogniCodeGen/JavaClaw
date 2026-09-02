package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.Workspace;
import com.javaclaw.builtin.contracts.MemoryContracts;

/**
 * Workspace 学习策略页面的不可变状态。
 *
 * @param phase 异步阶段
 * @param workspaces Workspace 目录
 * @param workspace 当前 Workspace
 * @param saved 服务端权威设置
 * @param draft 用户草稿
 * @param message 状态说明
 * @param epoch 最新请求序号
 */
public record LearningSettingsState(
        SettingsLoadState phase,
        List<Workspace> workspaces,
        Optional<Workspace> workspace,
        Optional<MemoryContracts.LearningSettings> saved,
        MemoryContracts.LearningPolicy draft,
        String message,
        long epoch) {
    /** 复制集合并校验完整状态。 */
    public LearningSettingsState {
        Objects.requireNonNull(phase, "phase");
        workspaces = List.copyOf(workspaces);
        workspace = Objects.requireNonNull(workspace, "workspace");
        saved = Objects.requireNonNull(saved, "saved");
        Objects.requireNonNull(draft, "draft");
        message = Objects.requireNonNullElse(message, "");
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch must not be negative");
        }
    }

    /** @return 尚未读取的初始状态 */
    public static LearningSettingsState initial() {
        return new LearningSettingsState(
                SettingsLoadState.INITIAL,
                List.of(),
                Optional.empty(),
                Optional.empty(),
                MemoryContracts.LearningPolicy.SUGGEST,
                "",
                0);
    }

    /** @return 当前策略是否有未保存修改 */
    public boolean dirty() {
        return saved.map(value -> value.policy() != draft).orElse(false);
    }
}
