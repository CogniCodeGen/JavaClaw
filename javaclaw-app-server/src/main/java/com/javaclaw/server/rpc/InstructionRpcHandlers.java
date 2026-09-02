package com.javaclaw.server.rpc;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ManagedWorktree;
import com.javaclaw.api.ManagedWorktreeState;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceInstructionSettings;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.InstructionRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.instructions.ProjectInstructionResolver;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.ManagedWorktreeService;
import com.javaclaw.server.persistence.PersistenceException;

/** 项目约定管理查询的脱敏 RPC 映射。 */
public final class InstructionRpcHandlers {
    private final CoreCommandService core;
    private final ManagedWorktreeService worktrees;
    private final ProjectInstructionResolver resolver;
    private final CanonicalJson json;

    /**
     * 创建 handler。
     *
     * @param core Workspace 查询服务
     * @param worktrees 受管 Worktree 查询服务
     * @param resolver 与 Turn 启动共用的项目约定解析器
     * @param json 规范 JSON codec
     */
    public InstructionRpcHandlers(
            CoreCommandService core,
            ManagedWorktreeService worktrees,
            ProjectInstructionResolver resolver,
            CanonicalJson json) {
        this.core = Objects.requireNonNull(core, "core");
        this.worktrees = Objects.requireNonNull(worktrees, "worktrees");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 注册项目约定查询。
     *
     * @param routes Router Builder
     * @return 当前 Builder
     */
    public RpcRouter.Builder register(RpcRouter.Builder routes) {
        return Objects.requireNonNull(routes, "routes")
                .register("workspace/instructions/read", this::read)
                .register("workspace/instructions/settings/read", this::readSettings)
                .register("workspace/instructions/settings/update", this::updateSettings);
    }

    private CanonicalPayload read(CanonicalPayload params) {
        InstructionRpcContracts.ReadPayload query = json.decode(params, InstructionRpcContracts.ReadPayload.class);
        Workspace workspace = core.findWorkspace(query.workspaceId())
                .orElseThrow(() -> PersistenceException.invalidRequest("Workspace 不存在"));
        Optional<ManagedWorktree> worktree = query.worktreeId().map(worktrees::read);
        Path executionRoot =
                worktree.map(value -> executionRoot(workspace, value)).orElse(workspace.root());
        Path checkoutRoot = worktree.isPresent() ? executionRoot : workspace.root();
        Optional<String> fallback =
                core.workspaceInstructionSettings(workspace.id()).fallbackBasename();
        return json.encode(
                resolver.resolve(checkoutRoot, executionRoot, fallback).resolution());
    }

    private CanonicalPayload readSettings(CanonicalPayload params) {
        InstructionRpcContracts.SettingsReadPayload query =
                json.decode(params, InstructionRpcContracts.SettingsReadPayload.class);
        return json.encode(core.workspaceInstructionSettings(query.workspaceId()));
    }

    private CanonicalPayload updateSettings(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        InstructionRpcContracts.SettingsUpdatePayload payload =
                json.decode(command.payload(), InstructionRpcContracts.SettingsUpdatePayload.class);
        WorkspaceInstructionSettings result = core.updateWorkspaceInstructionSettings(
                CommandIdentity.from("workspace/instructions/settings/update", command, json),
                payload.workspaceId(),
                payload.fallbackBasename());
        return json.encode(result);
    }

    private static Path executionRoot(Workspace workspace, ManagedWorktree worktree) {
        if (!worktree.workspaceId().equals(workspace.id())) {
            throw PersistenceException.invalidRequest("Managed Worktree 不属于当前 Workspace");
        }
        if (worktree.state() == ManagedWorktreeState.CLEANED) {
            throw PersistenceException.invalidRequest("Managed Worktree 已清理");
        }
        return worktree.executionRoot();
    }
}
