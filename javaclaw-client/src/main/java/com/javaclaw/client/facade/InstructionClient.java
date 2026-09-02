package com.javaclaw.client.facade;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.InstructionResolution;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorkspaceInstructionSettings;
import com.javaclaw.api.WorktreeId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.protocol.InstructionRpcContracts;

/** 项目约定脱敏解析结果的强类型 SDK facade。 */
public final class InstructionClient {
    private final RpcClientConnection connection;

    /** @param connection 已初始化连接 */
    public InstructionClient(RpcClientConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * 预览下一 Turn 对指定 execution root 的项目约定解析结果。
     *
     * <p>返回值不包含正文和绝对路径；为空的 Worktree 表示 Workspace 根。
     *
     * @param workspaceId Workspace
     * @param worktreeId 可选受管 Worktree
     * @return 脱敏解析清单
     */
    public InstructionResolution read(WorkspaceId workspaceId, Optional<WorktreeId> worktreeId) {
        return connection.query(
                "workspace/instructions/read",
                new InstructionRpcContracts.ReadPayload(workspaceId, worktreeId),
                InstructionResolution.class);
    }

    /**
     * 读取 Workspace 的安全 fallback 文件名设置。
     *
     * @param workspaceId Workspace
     * @return 设置自身的权威 revision
     */
    public WorkspaceInstructionSettings readSettings(WorkspaceId workspaceId) {
        return connection.query(
                "workspace/instructions/settings/read",
                new InstructionRpcContracts.SettingsReadPayload(workspaceId),
                WorkspaceInstructionSettings.class);
    }

    /**
     * 更新 Workspace 的安全 fallback 文件名。
     *
     * @param workspaceId Workspace
     * @param fallbackBasename 安全 basename；空值关闭 fallback
     * @param options 幂等键与设置自身的 expected revision
     * @return 新版本设置
     */
    public WorkspaceInstructionSettings updateSettings(
            WorkspaceId workspaceId, Optional<String> fallbackBasename, CommandOptions options) {
        return connection.command(
                "workspace/instructions/settings/update",
                new InstructionRpcContracts.SettingsUpdatePayload(workspaceId, fallbackBasename),
                options,
                WorkspaceInstructionSettings.class);
    }
}
