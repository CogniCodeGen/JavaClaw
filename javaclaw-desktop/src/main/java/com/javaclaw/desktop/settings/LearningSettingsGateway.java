package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.concurrent.CompletionStage;

import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.MemoryContracts;
import com.javaclaw.client.CommandOptions;

/** 学习策略页面访问 Workspace 与 Memory 扩展的强类型异步边界。 */
public interface LearningSettingsGateway {
    /** @return 当前 Workspace 目录 */
    CompletionStage<List<Workspace>> workspaces();

    /**
     * 读取 Workspace 学习策略。
     *
     * @param workspaceId Workspace
     * @return 权威策略与 revision
     */
    CompletionStage<MemoryContracts.LearningSettings> read(WorkspaceId workspaceId);

    /**
     * 条件更新 Workspace 学习策略。
     *
     * @param workspaceId Workspace
     * @param policy 新策略
     * @param options 幂等键与当前 revision
     * @return 新 revision
     */
    CompletionStage<MemoryContracts.LearningSettings> update(
            WorkspaceId workspaceId, MemoryContracts.LearningPolicy policy, CommandOptions options);
}
