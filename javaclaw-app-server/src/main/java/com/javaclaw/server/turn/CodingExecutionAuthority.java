package com.javaclaw.server.turn;

import java.util.List;
import java.util.Objects;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionResolver;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.ExtensionCatalogRepository;
import com.javaclaw.server.persistence.ManagedWorktreeService;
import com.javaclaw.server.persistence.PermissionProfileService;

/** 将 Coding 的长生命周期操作持续限制在真实 Turn、执行根及父任务授权内。 */
public final class CodingExecutionAuthority {
    private final CoreCommandService core;
    private final PermissionProfileService profiles;
    private final ManagedWorktreeService worktrees;
    private final ExtensionCatalogRepository catalog;
    private final CanonicalJson json;

    /**
     * 创建实时权限读取端口。
     *
     * @param core Turn 与 Workspace 权威服务
     * @param profiles 权限版本及撤销服务
     * @param worktrees 工作树权威绑定
     * @param catalog 扩展实时状态
     * @param json 规范编解码
     */
    public CodingExecutionAuthority(
            CoreCommandService core,
            PermissionProfileService profiles,
            ManagedWorktreeService worktrees,
            ExtensionCatalogRepository catalog,
            CanonicalJson json) {
        this.core = Objects.requireNonNull(core, "core");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.worktrees = Objects.requireNonNull(worktrees, "worktrees");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 检查启用状态、Turn 生命周期、根目录和全部父任务权限；绝不授予新权限。
     *
     * @param turnId 权威 Turn
     * @param frozen 本次已批准工具调用的有效权限
     * @return 冻结权限与实时权限的交集
     */
    public PermissionProfile current(TurnId turnId, PermissionProfile frozen) {
        catalog.requireEnabled(new ExtensionId(CodingContracts.EXTENSION_ID), CodingContracts.REVISION);
        AgentTurn turn = core.findTurn(turnId).orElseThrow(() -> new SecurityException("Coding Turn 不存在"));
        if (turn.status() != TurnStatus.RUNNING && turn.status() != TurnStatus.WAITING) {
            throw new SecurityException("Coding Turn 已终结或尚未启动");
        }
        ThreadExecutionScope scope = ThreadExecutionScope.resolve(core, worktrees, turn.threadId());
        core.workspaceSecurity().requireUnlocked(scope.workspace().id(), turn.executionRoot());
        if (!scope.root().equals(turn.executionRoot())) {
            throw new SecurityException("Coding 执行根绑定已变化");
        }
        PermissionProfile current = profiles.resolveForExecution(
                turn.permissionProfile().id(),
                turn.permissionProfile().version(),
                scope.workspace(),
                scope.root(),
                scope.writable());
        return ParentTurnPermissions.intersect(
                new ParentTurnPermissions.Sources(core, profiles, worktrees, json),
                turn,
                PermissionResolver.intersect(List.of(frozen, current)));
    }

    /**
     * 返回经过 ManagedWorktreeService 权威绑定验证的隔离工作树身份。
     *
     * @param turnId 真实 Turn 标识
     * @return 仅真实 ISOLATED_WRITE 工作树为 true；目录名称本身不授予例外
     */
    public boolean managedWorktree(TurnId turnId) {
        AgentTurn turn = core.findTurn(turnId).orElseThrow(() -> new SecurityException("Coding Turn 不存在"));
        ThreadExecutionScope scope = ThreadExecutionScope.resolve(core, worktrees, turn.threadId());
        if (!scope.root().equals(turn.executionRoot())) {
            throw new SecurityException("Coding 工作树执行根绑定已变化");
        }
        return scope.isolatedWrite();
    }

    /**
     * 校验已有活动资源的权限未缩小；任何收窄均终结资源，后续调用重新审批。
     *
     * @param turnId 权威 Turn
     * @param frozen 启动资源时的有效权限
     */
    public void requireUnchanged(TurnId turnId, PermissionProfile frozen) {
        PermissionProfile current = current(turnId, frozen);
        if (!current.files().equals(frozen.files())
                || !current.processes().equals(frozen.processes())
                || !current.network().equals(frozen.network())
                || !current.tools().equals(frozen.tools())
                || !current.resources().equals(frozen.resources())) {
            throw new SecurityException("Coding 活动资源的权限已收窄");
        }
    }

    /**
     * 将可信清理异常写入持久安全锁；已启动的长任务在下一次实时权限检查中收到撤销。
     *
     * @param turnId 服务端绑定的真实 Turn
     * @param failure 原生失败或聚合异常，可为空
     * @return 是否发现并记录权限恢复失败
     */
    public boolean reportIsolationFailure(TurnId turnId, Throwable failure) {
        AgentTurn turn = core.findTurn(turnId).orElseThrow(() -> new SecurityException("Coding Turn 不存在"));
        var workspace = core.workspaceForThread(turn.threadId());
        return core.workspaceSecurity()
                .quarantine(workspace.id(), java.util.Optional.of(turnId), turn.executionRoot(), failure);
    }

    /**
     * 读取历史执行证据时重新检查原 Turn 的根绑定和权限撤销，不把资源 ID 当成授权。
     *
     * @param turnId 证据所属 Turn
     * @param workspaceId 当前管理调用的 Workspace
     */
    public void requireEvidence(TurnId turnId, com.javaclaw.api.WorkspaceId workspaceId) {
        AgentTurn turn = core.findTurn(turnId).orElseThrow(() -> new SecurityException("证据 Turn 不存在"));
        ThreadExecutionScope scope = ThreadExecutionScope.resolve(core, worktrees, turn.threadId());
        if (!scope.workspace().id().equals(workspaceId) || !scope.root().equals(turn.executionRoot())) {
            throw new SecurityException("证据 Workspace 或执行根不匹配");
        }
        PermissionProfile current = profiles.resolveForExecution(
                turn.permissionProfile().id(),
                turn.permissionProfile().version(),
                scope.workspace(),
                scope.root(),
                scope.writable());
        if (current.files().readRoots().stream().noneMatch(scope.root()::startsWith)) {
            throw new SecurityException("当前权限不能读取执行根证据");
        }
    }
}
