package com.javaclaw.client.facade;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.javaclaw.api.Workspace;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.protocol.CoreRpcContracts;

/** Workspace Core 方法的强类型 facade。 */
public final class WorkspaceClient {
    private final RpcClientConnection connection;

    /**
     * 创建 facade。
     *
     * @param connection 已初始化连接
     */
    public WorkspaceClient(RpcClientConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * 列出全部 Workspace。
     *
     * @return 不可变 Workspace 快照
     */
    public List<Workspace> list() {
        return connection
                .query("workspace/list", Map.of(), CoreRpcContracts.WorkspaceListResult.class)
                .workspaces();
    }

    /**
     * 创建 Workspace。
     *
     * @param name 用户可见名称
     * @param root 绝对根目录
     * @param options 幂等键与创建 revision 0
     * @return 已创建 Workspace
     */
    public Workspace create(String name, Path root, CommandOptions options) {
        return connection.command(
                "workspace/create", new CoreRpcContracts.WorkspaceCreatePayload(name, root), options, Workspace.class);
    }

    /**
     * 重命名 Workspace，不改变根目录。
     *
     * @param workspace 当前 Workspace
     * @param name 新名称
     * @param options 幂等键与当前 revision
     * @return 新版本快照
     */
    public Workspace rename(Workspace workspace, String name, CommandOptions options) {
        Workspace current = Objects.requireNonNull(workspace, "workspace");
        return connection.command(
                "workspace/rename",
                new CoreRpcContracts.WorkspaceRenamePayload(current.id(), name),
                options,
                Workspace.class);
    }

    /**
     * 归档 Workspace 登记；此操作永不删除用户目录。
     *
     * @param workspace 当前 Workspace
     * @param options 幂等键与当前 revision
     * @return 归档版本快照
     */
    public Workspace archive(Workspace workspace, CommandOptions options) {
        Workspace current = Objects.requireNonNull(workspace, "workspace");
        return connection.command(
                "workspace/archive",
                new CoreRpcContracts.WorkspaceArchivePayload(current.id()),
                options,
                Workspace.class);
    }
}
