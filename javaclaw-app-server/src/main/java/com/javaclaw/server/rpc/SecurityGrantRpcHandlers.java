package com.javaclaw.server.rpc;

import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.SecurityGrantRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.security.grant.PrivateNetworkGrantService;
import com.javaclaw.server.security.grant.SecurityGrantAuditService;
import com.javaclaw.server.security.grant.UnattendedToolGrantService;

/** 安全授权管理方法到独立应用服务的薄 RPC registrar。 */
public final class SecurityGrantRpcHandlers {
    private final PrivateNetworkGrantService privateNetwork;
    private final UnattendedToolGrantService unattended;
    private final SecurityGrantAuditService audit;
    private final CanonicalJson json;

    /**
     * 创建 registrar。
     *
     * @param privateNetwork 私网授权服务
     * @param unattended 无人值守授权服务
     * @param audit 决策审计读取服务
     * @param json 规范 JSON codec
     */
    public SecurityGrantRpcHandlers(
            PrivateNetworkGrantService privateNetwork,
            UnattendedToolGrantService unattended,
            SecurityGrantAuditService audit,
            CanonicalJson json) {
        this.privateNetwork = Objects.requireNonNull(privateNetwork, "privateNetwork");
        this.unattended = Objects.requireNonNull(unattended, "unattended");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 注册全部安全授权 Protocol v3 方法。
     *
     * @param routes 组合根路由 Builder
     * @return 当前 Builder
     */
    public RpcRouter.Builder register(RpcRouter.Builder routes) {
        return routes.register("privateNetworkGrant/preview", this::previewPrivateNetwork)
                .register("privateNetworkGrant/list", this::listPrivateNetwork)
                .register("privateNetworkGrant/history", this::privateNetworkHistory)
                .register("privateNetworkGrant/create", this::createPrivateNetwork)
                .register("privateNetworkGrant/revoke", this::revokePrivateNetwork)
                .register("unattendedToolGrant/list", this::listUnattended)
                .register("unattendedToolGrant/history", this::unattendedHistory)
                .register("unattendedToolGrant/create", this::createUnattended)
                .register("unattendedToolGrant/revoke", this::revokeUnattended)
                .register("permissionDecision/list", this::listDecisions);
    }

    private CanonicalPayload previewPrivateNetwork(CanonicalPayload params) {
        SecurityGrantRpcContracts.PrivateNetworkPreviewPayload payload =
                json.decode(params, SecurityGrantRpcContracts.PrivateNetworkPreviewPayload.class);
        return json.encode(privateNetwork.preview(
                payload.workspaceId(),
                payload.purpose(),
                payload.origin(),
                payload.dnsAddresses(),
                payload.validity()));
    }

    private CanonicalPayload listPrivateNetwork(CanonicalPayload params) {
        SecurityGrantRpcContracts.WorkspaceGrantQuery query =
                json.decode(params, SecurityGrantRpcContracts.WorkspaceGrantQuery.class);
        return json.encode(
                new SecurityGrantRpcContracts.PrivateNetworkListResult(privateNetwork.listLatest(query.workspaceId())));
    }

    private CanonicalPayload privateNetworkHistory(CanonicalPayload params) {
        SecurityGrantRpcContracts.GrantHistoryQuery query =
                json.decode(params, SecurityGrantRpcContracts.GrantHistoryQuery.class);
        return json.encode(
                new SecurityGrantRpcContracts.PrivateNetworkHistoryResult(privateNetwork.history(query.grantId())));
    }

    private CanonicalPayload createPrivateNetwork(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        SecurityGrantRpcContracts.PrivateNetworkCreatePayload payload =
                json.decode(command.payload(), SecurityGrantRpcContracts.PrivateNetworkCreatePayload.class);
        return json.encode(privateNetwork.create(
                CommandIdentity.from("privateNetworkGrant/create", command, json), payload.preview()));
    }

    private CanonicalPayload revokePrivateNetwork(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        SecurityGrantRpcContracts.GrantRevokePayload payload =
                json.decode(command.payload(), SecurityGrantRpcContracts.GrantRevokePayload.class);
        return json.encode(privateNetwork.revoke(
                CommandIdentity.from("privateNetworkGrant/revoke", command, json), payload.grantId()));
    }

    private CanonicalPayload listUnattended(CanonicalPayload params) {
        SecurityGrantRpcContracts.WorkspaceGrantQuery query =
                json.decode(params, SecurityGrantRpcContracts.WorkspaceGrantQuery.class);
        return json.encode(
                new SecurityGrantRpcContracts.UnattendedListResult(unattended.listLatest(query.workspaceId())));
    }

    private CanonicalPayload unattendedHistory(CanonicalPayload params) {
        SecurityGrantRpcContracts.GrantHistoryQuery query =
                json.decode(params, SecurityGrantRpcContracts.GrantHistoryQuery.class);
        return json.encode(new SecurityGrantRpcContracts.UnattendedHistoryResult(unattended.history(query.grantId())));
    }

    private CanonicalPayload createUnattended(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        SecurityGrantRpcContracts.UnattendedCreatePayload payload =
                json.decode(command.payload(), SecurityGrantRpcContracts.UnattendedCreatePayload.class);
        return json.encode(
                unattended.create(CommandIdentity.from("unattendedToolGrant/create", command, json), payload.draft()));
    }

    private CanonicalPayload revokeUnattended(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        SecurityGrantRpcContracts.GrantRevokePayload payload =
                json.decode(command.payload(), SecurityGrantRpcContracts.GrantRevokePayload.class);
        return json.encode(unattended.revoke(
                CommandIdentity.from("unattendedToolGrant/revoke", command, json), payload.grantId()));
    }

    private CanonicalPayload listDecisions(CanonicalPayload params) {
        SecurityGrantRpcContracts.PermissionDecisionListPayload payload =
                json.decode(params, SecurityGrantRpcContracts.PermissionDecisionListPayload.class);
        return json.encode(new SecurityGrantRpcContracts.PermissionDecisionListResult(
                audit.list(payload.workspaceId(), payload.grantKind(), payload.grantId(), payload.limit())));
    }
}
