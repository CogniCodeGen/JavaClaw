package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

import com.javaclaw.api.InstructionResolution;
import com.javaclaw.api.ManagedWorktree;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorkspaceInstructionSettings;
import com.javaclaw.api.WorktreeId;
import com.javaclaw.client.CommandOptions;

/** 项目约定设置页所需的最小异步 SDK 能力边界。 */
public interface InstructionSettingsGateway {
    /** @return Workspace 目录 */
    CompletionStage<List<Workspace>> workspaces();

    /** @param workspaceId Workspace @param includeCleaned 是否包含已清理记录 @return 受管 Worktree */
    CompletionStage<List<ManagedWorktree>> managedWorktrees(WorkspaceId workspaceId, boolean includeCleaned);

    /**
     * 读取与 Turn 启动相同规则的脱敏解析结果。
     *
     * @param workspaceId Workspace
     * @param worktreeId 可选受管 execution root
     * @return 不包含正文和绝对路径的结果
     */
    CompletionStage<InstructionResolution> instructionResolution(
            WorkspaceId workspaceId, Optional<WorktreeId> worktreeId);

    /** @param workspaceId Workspace @return 项目约定解析设置 */
    CompletionStage<WorkspaceInstructionSettings> instructionSettings(WorkspaceId workspaceId);

    /**
     * @param workspaceId Workspace
     * @param fallbackBasename 安全备用文件名；空值关闭 fallback
     * @param options 设置 revision 与幂等键
     * @return 新版本设置
     */
    CompletionStage<WorkspaceInstructionSettings> updateInstructionSettings(
            WorkspaceId workspaceId, Optional<String> fallbackBasename, CommandOptions options);
}
