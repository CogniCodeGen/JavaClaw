package com.javaclaw.server.rpc;

import java.util.Objects;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.ApprovalPolicy;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ToolCatalogQueryResult;
import com.javaclaw.api.Workspace;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ToolRpcContracts;
import com.javaclaw.server.persistence.AgentRoleService;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.PermissionProfileService;
import com.javaclaw.server.turn.AgentConfigurationResolver;
import com.javaclaw.server.turn.ExtensionToolPlatform;

/** Protocol v3 Tool 设置候选目录到实时工具平台的薄映射。 */
public final class ToolRpcHandlers {
    private final CoreCommandService core;
    private final AgentRoleService roles;
    private final PermissionProfileService profiles;
    private final ExtensionToolPlatform tools;
    private final CanonicalJson json;

    /**
     * 创建 Tool handlers。
     *
     * @param core Core 查询服务
     * @param roles Agent Role 精确版本服务
     * @param profiles 权限服务
     * @param tools 工具目录平台
     * @param json 规范 JSON codec
     */
    public ToolRpcHandlers(
            CoreCommandService core,
            AgentRoleService roles,
            PermissionProfileService profiles,
            ExtensionToolPlatform tools,
            CanonicalJson json) {
        this.core = Objects.requireNonNull(core, "core");
        this.roles = Objects.requireNonNull(roles, "roles");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 注册 Tool Core 方法。
     *
     * @param routes Router Builder
     */
    public void register(RpcRouter.Builder routes) {
        Objects.requireNonNull(routes, "routes").register("tool/search", this::search);
    }

    private com.javaclaw.api.CanonicalPayload search(com.javaclaw.api.CanonicalPayload params) {
        ToolRpcContracts.CatalogQuery query = json.decode(params, ToolRpcContracts.CatalogQuery.class);
        Workspace workspace = core.findWorkspace(query.workspaceId())
                .orElseThrow(() -> new IllegalArgumentException("Workspace does not exist"));
        PermissionProfile permissions =
                profiles.resolve(query.permissionProfileId(), query.permissionProfileVersion(), workspace);
        ToolCatalogQueryResult result = query.agentRole()
                .map(reference -> executableCatalog(query, workspace, permissions, reference))
                .orElseGet(() -> tools.selectableCatalog(workspace.id(), permissions, query.query(), query.limit()));
        return json.encode(new ToolRpcContracts.SearchResult(result.catalogRevision(), result.tools()));
    }

    private ToolCatalogQueryResult executableCatalog(
            ToolRpcContracts.CatalogQuery query,
            Workspace workspace,
            PermissionProfile permissions,
            com.javaclaw.api.AgentRoleRef reference) {
        AgentRole profile = roles.requireAvailable(reference.id(), reference.revision());
        return tools.executableCatalog(
                workspace.id(), visibleTools(profile, permissions), query.query(), query.limit());
    }

    private static PermissionProfile visibleTools(AgentRole profile, PermissionProfile permissions) {
        return AgentConfigurationResolver.constrain(
                permissions,
                profile.spec().narrowing().capabilities(),
                ApprovalPolicy.NONE,
                AgentConfigurationResolver.roleConstraint(profile));
    }
}
