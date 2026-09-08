package com.javaclaw.server.turn;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionResolver;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.CoreItemReader;
import com.javaclaw.server.persistence.ManagedWorktreeService;
import com.javaclaw.server.persistence.PermissionProfileService;

/**
 * 使用来源 Turn 的冻结证据授权历史文档阅读，不要求 Turn 仍在运行，也不重新授予临时 grant。
 *
 * <p>每次读取重放实时限制，再与句柄创建时的权限交集；权限扩张不能扩大旧句柄。根变更与父级取消均拒绝。
 */
public final class PreviewReadAuthority {
    private final CoreCommandService core;
    private final CoreItemReader items;
    private final ManagedWorktreeService worktrees;
    private final PermissionProfileService profiles;
    private final CanonicalJson json;

    /**
     * 创建只读权限用例，所有依赖均由 App Server 组合根提供。
     *
     * @param core Core 权威查询
     * @param items 精确 Item 证据查询
     * @param worktrees 受管工作树
     * @param profiles 历史和实时权限解析器
     * @param json 共享规范 codec
     */
    public PreviewReadAuthority(
            CoreCommandService core,
            CoreItemReader items,
            ManagedWorktreeService worktrees,
            PermissionProfileService profiles,
            CanonicalJson json) {
        this.core = Objects.requireNonNull(core, "core");
        this.items = Objects.requireNonNull(items, "items");
        this.worktrees = Objects.requireNonNull(worktrees, "worktrees");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 验证来源归属；读取消息本身不授权读取文件。
     *
     * @param workspaceId 当前 Workspace
     * @param itemId 来源 Item
     * @return 精确且归属匹配的来源
     */
    public ItemEnvelope source(WorkspaceId workspaceId, ItemId itemId) {
        ItemEnvelope item = items.findItem(itemId).orElseThrow(() -> new SecurityException("预览来源不存在"));
        AgentTurn turn = core.findTurn(item.turnId()).orElseThrow(() -> new SecurityException("来源 Turn 不存在"));
        if (!core.workspaceForThread(turn.threadId()).id().equals(workspaceId)) {
            throw new SecurityException("预览来源不属于当前 Workspace");
        }
        workspace(workspaceId);
        return item;
    }

    /**
     * 验证 Workspace 存在且未因原生恢复处于安全锁定状态。
     *
     * @param id Workspace ID
     * @return 当前权威 Workspace
     */
    public Workspace workspace(WorkspaceId id) {
        Workspace workspace = core.findWorkspace(id).orElseThrow(() -> new SecurityException("Workspace 不存在"));
        core.workspaceSecurity().requireUnlocked(id, workspace.root());
        return workspace;
    }

    /**
     * 计算历史来源允许的当前文件读取权限。
     *
     * @param workspaceId 当前 Workspace
     * @param itemId 原始来源 Item
     * @param ceiling 句柄第一次授权；首次解析为空
     * @return 仅用于固定文件 Worker 的根和只读权限
     */
    public Access current(WorkspaceId workspaceId, ItemId itemId, Optional<Access> ceiling) {
        ItemEnvelope item = source(workspaceId, itemId);
        AgentTurn turn = core.findTurn(item.turnId()).orElseThrow();
        ThreadExecutionScope scope = ThreadExecutionScope.resolve(core, worktrees, turn.threadId());
        if (!scope.root().equals(turn.executionRoot())) {
            throw new SecurityException("预览来源的执行根已变更");
        }
        core.workspaceSecurity().requireUnlocked(workspaceId, scope.root());
        var configuration = core.resolvedConfig(turn.id());
        PermissionProfile current = profiles.resolveForExecution(
                configuration.permissionProfile().id(),
                configuration.permissionProfile().version(),
                scope.workspace(),
                scope.root(),
                false);
        current = AgentConfigurationResolver.constrain(
                current,
                Optional.of(configuration.effectiveCapabilities()),
                configuration.approvalPolicy(),
                configuration.permissionConstraint());
        ToolCatalogSnapshot frozen = json.decode(core.toolCatalogSnapshot(turn.id()), ToolCatalogSnapshot.class);
        current = ParentTurnPermissions.intersect(
                new ParentTurnPermissions.Sources(core, profiles, worktrees, json),
                turn,
                PermissionResolver.intersect(List.of(current, frozen.permissionCeiling())));
        if (ceiling.isPresent()) {
            Access previous = ceiling.orElseThrow();
            if (!previous.root().equals(scope.root()) || !previous.item().id().equals(itemId)) {
                throw new SecurityException("预览句柄来源已变更");
            }
            current = PermissionResolver.intersect(List.of(current, previous.permission()));
        }
        return new Access(item, scope.root(), readOnly(current));
    }

    private static PermissionProfile readOnly(PermissionProfile permission) {
        return new PermissionProfile(
                permission.id(),
                permission.version(),
                new FilePermission(permission.files().readRoots(), List.of(), false, false),
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(Set.of(), false, permission.processes().maxRunTime()),
                permission.tools(),
                permission.resources());
    }

    /**
     * 不进入 RPC 的服务端授权证据。
     *
     * @param item 来源 Item
     * @param root 服务器确认的执行根
     * @param permission 不可扩张的只读 ceiling
     */
    public record Access(ItemEnvelope item, Path root, PermissionProfile permission) {
        /** 校验证据完整。 */
        public Access {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(permission, "permission");
        }
    }
}
