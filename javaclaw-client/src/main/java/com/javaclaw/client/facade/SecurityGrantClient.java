package com.javaclaw.client.facade;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.PermissionDecisionTrace;
import com.javaclaw.api.PrivateNetworkGrant;
import com.javaclaw.api.PrivateNetworkGrantPreview;
import com.javaclaw.api.PrivateNetworkPurpose;
import com.javaclaw.api.SecurityGrantKind;
import com.javaclaw.api.UnattendedToolGrant;
import com.javaclaw.api.UnattendedToolGrantDraft;
import com.javaclaw.api.UnattendedToolGrantStatus;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.protocol.SecurityGrantRpcContracts;

/** 私网、Schedule 无人值守授权与权限决策审计的强类型 facade。 */
public final class SecurityGrantClient {
    private final RpcClientConnection connection;

    /**
     * 创建 facade。
     *
     * @param connection 已初始化连接
     */
    public SecurityGrantClient(RpcClientConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * 获取规范化私网授权预览；调用方必须展示并确认后才能创建。
     *
     * @param workspaceId 所属 Workspace
     * @param purpose MCP 或 Site
     * @param origin 精确 HTTPS Origin
     * @param dnsAddresses 当前 DNS 地址集合
     * @param validity 可选有效期；为空时一小时
     * @return 规范化预览及确认摘要
     */
    public PrivateNetworkGrantPreview previewPrivateNetwork(
            WorkspaceId workspaceId,
            PrivateNetworkPurpose purpose,
            URI origin,
            Set<String> dnsAddresses,
            Optional<Duration> validity) {
        return connection.query(
                "privateNetworkGrant/preview",
                new SecurityGrantRpcContracts.PrivateNetworkPreviewPayload(
                        workspaceId, purpose, origin, dnsAddresses, validity),
                PrivateNetworkGrantPreview.class);
    }

    /**
     * 提交已经人工确认的私网预览。
     *
     * @param preview 服务端预览
     * @param options expected revision 必须为 0
     * @return 创建的授权
     */
    public PrivateNetworkGrant createPrivateNetwork(PrivateNetworkGrantPreview preview, CommandOptions options) {
        return connection.command(
                "privateNetworkGrant/create",
                new SecurityGrantRpcContracts.PrivateNetworkCreatePayload(preview),
                options,
                PrivateNetworkGrant.class);
    }

    /**
     * 列出 Workspace 私网授权最新版本。
     *
     * @param workspaceId Workspace
     * @return 授权列表
     */
    public List<PrivateNetworkGrant> listPrivateNetwork(WorkspaceId workspaceId) {
        return connection
                .query(
                        "privateNetworkGrant/list",
                        new SecurityGrantRpcContracts.WorkspaceGrantQuery(workspaceId),
                        SecurityGrantRpcContracts.PrivateNetworkListResult.class)
                .grants();
    }

    /**
     * 读取私网授权历史。
     *
     * @param grantId 授权标识
     * @return revision 升序历史
     */
    public List<PrivateNetworkGrant> privateNetworkHistory(String grantId) {
        return connection
                .query(
                        "privateNetworkGrant/history",
                        new SecurityGrantRpcContracts.GrantHistoryQuery(grantId),
                        SecurityGrantRpcContracts.PrivateNetworkHistoryResult.class)
                .grants();
    }

    /**
     * 撤销私网授权。
     *
     * @param grantId 授权标识
     * @param options 当前 revision 与幂等键
     * @return tombstone 版本
     */
    public PrivateNetworkGrant revokePrivateNetwork(String grantId, CommandOptions options) {
        return connection.command(
                "privateNetworkGrant/revoke",
                new SecurityGrantRpcContracts.GrantRevokePayload(grantId),
                options,
                PrivateNetworkGrant.class);
    }

    /**
     * 创建 Schedule 无人值守授权。
     *
     * @param draft 用户确认配置
     * @param options expected revision 必须为 0
     * @return 创建的授权
     */
    public UnattendedToolGrant createUnattended(UnattendedToolGrantDraft draft, CommandOptions options) {
        return connection.command(
                "unattendedToolGrant/create",
                new SecurityGrantRpcContracts.UnattendedCreatePayload(draft),
                options,
                UnattendedToolGrant.class);
    }

    /**
     * 列出 Workspace 无人值守授权与余额。
     *
     * @param workspaceId Workspace
     * @return 授权状态
     */
    public List<UnattendedToolGrantStatus> listUnattended(WorkspaceId workspaceId) {
        return connection
                .query(
                        "unattendedToolGrant/list",
                        new SecurityGrantRpcContracts.WorkspaceGrantQuery(workspaceId),
                        SecurityGrantRpcContracts.UnattendedListResult.class)
                .grants();
    }

    /**
     * 读取无人值守授权历史。
     *
     * @param grantId 授权标识
     * @return revision 升序历史
     */
    public List<UnattendedToolGrant> unattendedHistory(String grantId) {
        return connection
                .query(
                        "unattendedToolGrant/history",
                        new SecurityGrantRpcContracts.GrantHistoryQuery(grantId),
                        SecurityGrantRpcContracts.UnattendedHistoryResult.class)
                .grants();
    }

    /**
     * 撤销无人值守授权。
     *
     * @param grantId 授权标识
     * @param options 当前 revision 与幂等键
     * @return tombstone 版本
     */
    public UnattendedToolGrant revokeUnattended(String grantId, CommandOptions options) {
        return connection.command(
                "unattendedToolGrant/revoke",
                new SecurityGrantRpcContracts.GrantRevokePayload(grantId),
                options,
                UnattendedToolGrant.class);
    }

    /**
     * 读取脱敏权限决策记录。
     *
     * @param workspaceId 所属 Workspace
     * @param kind 可选授权类型
     * @param grantId 可选授权标识
     * @param limit 返回上限
     * @return 新到旧排序的记录
     */
    public List<PermissionDecisionTrace> decisions(
            WorkspaceId workspaceId, Optional<SecurityGrantKind> kind, Optional<String> grantId, int limit) {
        return connection
                .query(
                        "permissionDecision/list",
                        new SecurityGrantRpcContracts.PermissionDecisionListPayload(workspaceId, kind, grantId, limit),
                        SecurityGrantRpcContracts.PermissionDecisionListResult.class)
                .traces();
    }
}
