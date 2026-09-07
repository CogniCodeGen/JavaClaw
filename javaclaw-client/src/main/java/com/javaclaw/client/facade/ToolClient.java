package com.javaclaw.client.facade;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ToolCatalogQueryResult;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.protocol.ToolRpcContracts;

/** 查询指定 Workspace 和权限版本可选工具的强类型 facade。 */
public final class ToolClient {
    private final RpcClientConnection connection;

    /**
     * 创建工具目录 facade。
     *
     * @param connection 已初始化连接
     */
    public ToolClient(RpcClientConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * 搜索服务端当前工具目录；结果已经过指定 PermissionProfile 的风险与基础能力上限收窄。
     *
     * <p>返回候选项不代表已授权；只有用户选中并写入精确工具名白名单后，Turn 才能冻结和执行该工具。
     *
     * @param workspaceId 固定 Workspace
     * @param permissionProfile 精确权限配置版本
     * @param query 名称、说明或标签关键词；空字符串列出稳定排序的有界候选
     * @param limit 最大结果数，1 到 100
     * @return 当前可见的精确工具描述
     */
    public List<ToolDescriptor> search(
            WorkspaceId workspaceId, PermissionProfileRef permissionProfile, String query, int limit) {
        return catalog(workspaceId, permissionProfile, Optional.empty(), query, limit)
                .tools();
    }

    /**
     * 读取设置页面使用的工具目录与服务端权威目录版本。
     *
     * <p>提供 Agent Role 时，服务端会按 Role 的能力收窄范围收窄结果；省略时返回权限编辑器可选候选。
     *
     * @param workspaceId 固定 Workspace
     * @param permissionProfile 精确权限配置版本
     * @param agentRole 精确 Agent Role；权限编辑器传空值
     * @param query 名称、说明或标签关键词
     * @param limit 最大结果数，1 到 100
     * @return 权威目录版本与有界描述
     */
    public ToolCatalogQueryResult catalog(
            WorkspaceId workspaceId,
            PermissionProfileRef permissionProfile,
            Optional<AgentRoleRef> agentRole,
            String query,
            int limit) {
        PermissionProfileRef checked = Objects.requireNonNull(permissionProfile, "permissionProfile");
        ToolRpcContracts.CatalogQuery params = new ToolRpcContracts.CatalogQuery(
                Objects.requireNonNull(workspaceId, "workspaceId"),
                checked.id(),
                checked.version(),
                Objects.requireNonNull(agentRole, "agentRole"),
                query,
                limit);
        return connection
                .query("tool/search", params, ToolRpcContracts.SearchResult.class)
                .toApi();
    }
}
