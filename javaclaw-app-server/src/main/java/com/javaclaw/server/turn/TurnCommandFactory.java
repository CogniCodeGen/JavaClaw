package com.javaclaw.server.turn;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.AutomationExecutionSnapshot;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ResolvedTurnConfig;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorkspaceLifecycle;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.ToolCatalogPort;
import com.javaclaw.runtime.TurnExecutionCommand;
import com.javaclaw.server.instructions.ProjectInstructionResolver;
import com.javaclaw.server.instructions.ResolvedInstructions;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.ManagedWorktreeService;
import com.javaclaw.server.persistence.TurnPromptSnapshot;
import com.javaclaw.server.persistence.TurnStartRequest;

/** 将唯一解析器的结果冻结为 Prompt、目录和 Turn 命令；恢复只读取持久快照。 */
final class TurnCommandFactory {
    private final CoreCommandService core;
    private final TurnPlatformServices services;
    private final AgentConfigurationResolver configurations;
    private final ProjectInstructionResolver instructions;
    private final ManagedWorktreeService worktrees;
    private final ModelGateway models;
    private final ToolCatalogPort catalogs;
    private final PromptManifestAssembler prompts;
    private final CanonicalJson json;

    TurnCommandFactory(
            TurnPlatformServices services,
            ModelGateway models,
            ToolCatalogPort catalogs,
            String coreInstruction,
            CanonicalJson json) {
        Objects.requireNonNull(services, "services");
        this.services = services;
        core = services.core();
        configurations = new AgentConfigurationResolver(
                services.roles(), services.configurations(), services.permissions(), core);
        instructions = services.instructions();
        worktrees = services.worktrees();
        this.models = Objects.requireNonNull(models, "models");
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
        prompts = new PromptManifestAssembler(coreInstruction);
        this.json = Objects.requireNonNull(json, "json");
    }

    TurnStartRequest resolve(CoreRpcContracts.TurnStartPayload request, CorePayloads.Message message) {
        ThreadExecutionScope scope = executionScope(request.threadId());
        ResolvedAgentConfiguration resolved =
                configurations.resolve(scope, Optional.of(request.threadId()), request.execution());
        models.capabilities(resolved.provider().routeKey());
        ToolCatalogSnapshot catalog = catalogs.freeze(
                TurnId.random(), scope.workspace().id(), resolved.effectivePermissions(), new CancellationSource());
        resolved = resolved.withCatalog(catalog);
        catalog = boundedCatalog(catalog, resolved.effectivePermissions());
        CanonicalPayload prompt = json.encode(prompts.snapshot(resolved, resolveInstructions(scope)));
        return new TurnStartRequest(
                request.threadId(),
                resolved.freeze(prompt.sha256(), catalog.digest()),
                scope.root(),
                prompt,
                catalog,
                message,
                Optional.empty());
    }

    AutomationExecutionSnapshot freezeAutomation(
            WorkspaceId workspaceId, ExecutionOverrides selection, CancellationToken cancellation) {
        Workspace workspace = core.findWorkspace(workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace does not exist"));
        requireActive(workspace);
        ThreadExecutionScope scope = ThreadExecutionScope.workspace(workspace);
        ResolvedAgentConfiguration resolved = configurations.resolve(scope, Optional.empty(), selection);
        models.capabilities(resolved.provider().routeKey());
        ToolCatalogSnapshot catalog =
                catalogs.freeze(TurnId.random(), workspace.id(), resolved.effectivePermissions(), cancellation);
        resolved = resolved.withCatalog(catalog);
        catalog = boundedCatalog(catalog, resolved.effectivePermissions());
        CanonicalPayload prompt = json.encode(prompts.snapshot(resolved, resolveInstructions(scope)));
        core.freezePromptManifest(prompt);
        core.codingEnvironments()
                .freezeExecution(catalog.turnId(), workspace.id(), scope.root(), catalog.permissionCeiling());
        return new AutomationExecutionSnapshot(
                resolved.freeze(prompt.sha256(), catalog.digest()), catalog, Optional.empty());
    }

    AutomationExecutionSnapshot freezeChild(AgentTurn parent, ExecutionOverrides spawn) {
        ThreadExecutionScope scope = requireFrozenScope(parent);
        ResolvedTurnConfig parentConfig = core.resolvedConfig(parent.id());
        PermissionProfile current = ParentTurnPermissions.intersect(
                services, json, parent, configurations.restorePermissions(parentConfig, scope));
        ToolCatalogSnapshot parentCatalog =
                json.decode(core.toolCatalogSnapshot(parent.id()), ToolCatalogSnapshot.class);
        current = com.javaclaw.api.PermissionResolver.intersect(
                java.util.List.of(current, parentCatalog.permissionCeiling()));
        ResolvedAgentConfiguration child = configurations.resolveChild(scope, parentConfig, spawn, current);
        models.capabilities(child.provider().routeKey());
        ToolCatalogSnapshot catalog = catalogs.freeze(
                TurnId.random(), scope.workspace().id(), child.effectivePermissions(), new CancellationSource());
        child = child.withCatalog(catalog);
        catalog = boundedCatalog(catalog, child.effectivePermissions());
        CanonicalPayload prompt = json.encode(prompts.snapshot(child, resolveInstructions(scope)));
        core.freezePromptManifest(prompt);
        core.codingEnvironments()
                .freezeChild(catalog.turnId(), scope.workspace().id(), parent.id());
        return new AutomationExecutionSnapshot(
                child.freeze(prompt.sha256(), catalog.digest()), catalog, Optional.empty());
    }

    TurnStartRequest resolveOrchestrated(
            CoreRpcContracts.TurnStartPayload request,
            CorePayloads.Message message,
            AutomationExecutionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        ThreadExecutionScope scope = executionScope(request.threadId());
        ResolvedTurnConfig frozen = snapshot.configuration();
        requireRequestedRole(request, frozen);
        PermissionProfile current = ParentTurnPermissions.intersect(
                services, json, scope, configurations.restorePermissions(frozen, scope));
        models.capabilities(frozen.provider().routeKey());
        ToolCatalogSnapshot catalog = catalogs.bindFrozen(
                TurnId.random(), scope.workspace().id(), snapshot.toolCatalog(), current, new CancellationSource());
        CanonicalPayload prompt = core.promptManifest(frozen.promptManifestDigest());
        return new TurnStartRequest(
                request.threadId(),
                frozen,
                scope.root(),
                prompt,
                catalog,
                message,
                snapshot.unattendedExecutionScope(),
                Optional.of(core.codingEnvironments()
                        .execution(
                                snapshot.toolCatalog().turnId(),
                                scope.workspace().id())));
    }

    TurnExecutionCommand create(AgentTurn turn, CoreRpcContracts.TurnStartPayload request) {
        ResolvedTurnConfig frozen = core.resolvedConfig(turn.id());
        requireRequestedRole(request, frozen);
        if (!turn.threadId().equals(request.threadId())
                || !turn.resolvedConfig().equals(frozen.summary())) {
            throw new IllegalArgumentException("Turn 身份与冻结解析配置不一致");
        }
        CanonicalPayload payload = core.promptSnapshot(turn.id());
        if (!frozen.promptManifestDigest().equals(payload.sha256())) {
            throw new IllegalStateException("Turn Prompt 快照与摘要不一致");
        }
        TurnPromptSnapshot prompt = json.decode(payload, TurnPromptSnapshot.class);
        if (!prompt.role().equals(frozen.role()) || !prompt.provider().equals(frozen.provider())) {
            throw new IllegalStateException("Turn Prompt 与解析配置身份不一致");
        }
        ThreadExecutionScope scope = requireFrozenScope(turn);
        PermissionProfile current =
                ParentTurnPermissions.intersect(services, json, turn, configurations.restorePermissions(frozen, scope));
        models.capabilities(frozen.provider().routeKey());
        ToolCatalogSnapshot catalog = bindPersistedCatalog(turn, scope, current);
        return new TurnExecutionCommand(
                turn,
                frozen.provider(),
                prompt.modelInstructions(),
                request.message(),
                current,
                catalog,
                core.contexts().policy(turn.id()));
    }

    TurnExecutionCommand createOrchestrated(
            AgentTurn turn, CoreRpcContracts.TurnStartPayload request, AutomationExecutionSnapshot snapshot) {
        if (!core.resolvedConfig(turn.id()).equals(snapshot.configuration())) {
            throw new IllegalArgumentException("自动化 Turn 与活动 Execution 冻结配置不一致");
        }
        return create(turn, request);
    }

    private static ToolCatalogSnapshot boundedCatalog(ToolCatalogSnapshot catalog, PermissionProfile permissions) {
        return new ToolCatalogSnapshot(
                catalog.turnId(), catalog.catalogRevision(), catalog.tools(), permissions, catalog.capturedAt());
    }

    private ThreadExecutionScope executionScope(com.javaclaw.api.ThreadId threadId) {
        ThreadExecutionScope scope = ThreadExecutionScope.resolve(core, worktrees, threadId);
        requireActive(scope.workspace());
        return scope;
    }

    private ThreadExecutionScope requireFrozenScope(AgentTurn turn) {
        ThreadExecutionScope scope = executionScope(turn.threadId());
        if (!scope.root().equals(turn.executionRoot())) {
            throw new IllegalStateException("Turn executionRoot 与当前权威 Thread 绑定不一致");
        }
        return scope;
    }

    private ResolvedInstructions resolveInstructions(ThreadExecutionScope scope) {
        Optional<String> fallback =
                core.workspaceInstructionSettings(scope.workspace().id()).fallbackBasename();
        return instructions.resolve(scope.root(), scope.root(), fallback);
    }

    private ToolCatalogSnapshot bindPersistedCatalog(
            AgentTurn turn, ThreadExecutionScope scope, PermissionProfile currentPermissions) {
        ToolCatalogSnapshot frozen = json.decode(core.toolCatalogSnapshot(turn.id()), ToolCatalogSnapshot.class);
        if (!frozen.turnId().equals(turn.id()) || !frozen.digest().equals(turn.toolCatalogDigest())) {
            throw new IllegalStateException("Turn 冻结工具目录身份或摘要不匹配");
        }
        return catalogs.bindFrozen(
                turn.id(), scope.workspace().id(), frozen, currentPermissions, new CancellationSource());
    }

    private static void requireRequestedRole(CoreRpcContracts.TurnStartPayload request, ResolvedTurnConfig frozen) {
        if (request.execution()
                .role()
                .filter(value -> !value.equals(frozen.role()))
                .isPresent()) {
            throw new IllegalArgumentException("请求 Role 与冻结 Turn 不一致");
        }
    }

    private static void requireActive(Workspace workspace) {
        if (workspace.lifecycle() != WorkspaceLifecycle.ACTIVE) {
            throw new IllegalArgumentException("已归档 Workspace 不能执行新 Turn");
        }
    }
}
