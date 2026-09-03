package com.javaclaw.server.turn;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.AutomationExecutionSnapshot;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProfileLifecycle;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.ToolPermission;
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
import com.javaclaw.server.persistence.AgentProfileService;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.ManagedWorktreeService;
import com.javaclaw.server.persistence.PermissionProfileService;
import com.javaclaw.server.persistence.ProfileBindingService;
import com.javaclaw.server.persistence.TurnPromptSnapshot;
import com.javaclaw.server.persistence.TurnStartRequest;

/** 只使用服务器权威状态解析 Profile，并构造冻结的 Thin Harness 命令。 */
final class TurnCommandFactory {
    private final CoreCommandService core;
    private final AgentProfileService profiles;
    private final ProfileBindingService bindings;
    private final PermissionProfileService permissions;
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
        core = services.core();
        profiles = services.profiles();
        bindings = services.bindings();
        permissions = services.permissions();
        instructions = services.instructions();
        worktrees = services.worktrees();
        this.models = Objects.requireNonNull(models, "models");
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
        prompts = new PromptManifestAssembler(coreInstruction);
        this.json = Objects.requireNonNull(json, "json");
    }

    TurnStartRequest resolve(CoreRpcContracts.TurnStartPayload request, CorePayloads.Message message) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(message, "message");
        AgentProfile profile = bindings.resolve(request.threadId(), request.profile());
        requireActive(profile);
        ThreadExecutionScope scope = executionScope(request.threadId());
        PermissionProfile effective = effectivePermissions(profile, scope);
        ProviderRef provider = profile.spec().provider();
        models.capabilities(provider.routeKey());
        ToolCatalogSnapshot catalog =
                catalogs.freeze(TurnId.random(), scope.workspace().id(), effective, new CancellationSource());
        ResolvedInstructions resolvedInstructions = resolveInstructions(scope);
        return new TurnStartRequest(
                request.threadId(),
                profile.spec().budget(),
                new AgentProfileRef(profile.id(), profile.revision()),
                provider,
                profile.spec().permissionProfile(),
                scope.root(),
                promptSnapshot(profile, resolvedInstructions),
                catalog,
                message,
                Optional.empty());
    }

    AutomationExecutionSnapshot freezeAutomation(
            WorkspaceId workspaceId, AgentProfileRef reference, CancellationToken cancellation) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(reference, "reference");
        Workspace workspace = core.findWorkspace(workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace does not exist"));
        requireActive(workspace);
        AgentProfile profile = profiles.requireAvailable(reference.id(), reference.revision());
        models.capabilities(profile.spec().provider().routeKey());
        PermissionProfile effective = effectivePermissions(profile, workspace);
        ToolCatalogSnapshot catalog = catalogs.freeze(TurnId.random(), workspace.id(), effective, cancellation);
        return new AutomationExecutionSnapshot(
                reference,
                profile.spec().provider(),
                profile.spec().permissionProfile(),
                profile.spec().budget(),
                catalog,
                Optional.empty());
    }

    TurnStartRequest resolveOrchestrated(
            CoreRpcContracts.TurnStartPayload request,
            CorePayloads.Message message,
            AutomationExecutionSnapshot snapshot) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(message, "message");
        AgentProfile profile = requireAutomationProfile(request, snapshot);
        ThreadExecutionScope scope = executionScope(request.threadId());
        PermissionProfile effective = effectivePermissions(profile, scope);
        models.capabilities(snapshot.provider().routeKey());
        ToolCatalogSnapshot catalog = catalogs.bindFrozen(
                TurnId.random(), scope.workspace().id(), snapshot.toolCatalog(), effective, new CancellationSource());
        ResolvedInstructions resolvedInstructions = resolveInstructions(scope);
        return new TurnStartRequest(
                request.threadId(),
                snapshot.turnBudget(),
                snapshot.profile(),
                snapshot.provider(),
                snapshot.permissionProfile(),
                scope.root(),
                promptSnapshot(profile, resolvedInstructions),
                catalog,
                message,
                snapshot.unattendedExecutionScope());
    }

    TurnExecutionCommand create(AgentTurn turn, CoreRpcContracts.TurnStartPayload request) {
        Objects.requireNonNull(turn, "turn");
        Objects.requireNonNull(request, "request");
        requireRequestIdentity(turn, request);
        AgentProfile profile =
                profiles.require(turn.profile().id(), turn.profile().revision());
        CanonicalPayload promptPayload = core.promptSnapshot(turn.id());
        if (!turn.promptManifestDigest().equals(promptPayload.sha256())) {
            throw new IllegalStateException("Turn Prompt snapshot 与冻结摘要不匹配");
        }
        TurnPromptSnapshot prompt = json.decode(promptPayload, TurnPromptSnapshot.class);
        requireFrozenIdentity(turn, profile, prompt);
        ThreadExecutionScope scope = requireFrozenScope(turn);
        PermissionProfile effective = effectivePermissions(profile, scope);
        models.capabilities(turn.provider().routeKey());
        ToolCatalogSnapshot catalog = bindPersistedCatalog(turn, scope, effective);
        return new TurnExecutionCommand(
                turn, turn.provider(), prompt.systemInstruction(), request.message(), effective, catalog);
    }

    TurnExecutionCommand createOrchestrated(
            AgentTurn turn, CoreRpcContracts.TurnStartPayload request, AutomationExecutionSnapshot snapshot) {
        Objects.requireNonNull(turn, "turn");
        Objects.requireNonNull(request, "request");
        requireRequestIdentity(turn, request);
        AgentProfile profile = requireAutomationProfile(request, snapshot);
        CanonicalPayload promptPayload = core.promptSnapshot(turn.id());
        if (!turn.promptManifestDigest().equals(promptPayload.sha256())) {
            throw new IllegalStateException("Turn Prompt snapshot 与冻结摘要不匹配");
        }
        TurnPromptSnapshot prompt = json.decode(promptPayload, TurnPromptSnapshot.class);
        requireFrozenIdentity(turn, profile, prompt);
        requireTurnSnapshot(turn, snapshot);
        ThreadExecutionScope scope = requireFrozenScope(turn);
        PermissionProfile effective = effectivePermissions(profile, scope);
        models.capabilities(snapshot.provider().routeKey());
        ToolCatalogSnapshot catalog = bindPersistedCatalog(turn, scope, effective);
        return new TurnExecutionCommand(
                turn, snapshot.provider(), prompt.systemInstruction(), request.message(), effective, catalog);
    }

    private PermissionProfile effectivePermissions(AgentProfile profile, Workspace workspace) {
        PermissionProfileRef reference = profile.spec().permissionProfile();
        PermissionProfile resolved = permissions.resolve(reference.id(), reference.version(), workspace);
        return visibleTools(profile, resolved);
    }

    private PermissionProfile effectivePermissions(AgentProfile profile, ThreadExecutionScope scope) {
        PermissionProfileRef reference = profile.spec().permissionProfile();
        PermissionProfile resolved = permissions.resolveForExecution(
                reference.id(), reference.version(), scope.workspace(), scope.root(), scope.writable());
        return visibleTools(profile, resolved);
    }

    private static PermissionProfile visibleTools(AgentProfile profile, PermissionProfile resolved) {
        Set<String> visible = new HashSet<>(resolved.tools().allowedTools());
        visible.retainAll(profile.spec().visibleTools());
        ToolPermission tools = new ToolPermission(
                visible, resolved.tools().maximumRisk(), resolved.tools().approvalRequirement());
        return new PermissionProfile(
                resolved.id(),
                resolved.version(),
                resolved.files(),
                resolved.network(),
                resolved.processes(),
                tools,
                resolved.resources());
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

    private CanonicalPayload promptSnapshot(AgentProfile profile, ResolvedInstructions instructions) {
        return json.encode(prompts.snapshot(profile, instructions));
    }

    private ToolCatalogSnapshot bindPersistedCatalog(
            AgentTurn turn, ThreadExecutionScope scope, PermissionProfile currentPermissions) {
        ToolCatalogSnapshot frozen = json.decode(core.toolCatalogSnapshot(turn.id()), ToolCatalogSnapshot.class);
        boolean identityMatches =
                frozen.turnId().equals(turn.id()) && frozen.digest().equals(turn.toolCatalogDigest());
        if (!identityMatches) {
            throw new IllegalStateException("Turn 冻结工具目录身份或摘要不匹配");
        }
        return catalogs.bindFrozen(
                turn.id(), scope.workspace().id(), frozen, currentPermissions, new CancellationSource());
    }

    private AgentProfile requireAutomationProfile(
            CoreRpcContracts.TurnStartPayload request, AutomationExecutionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (request.profile().filter(snapshot.profile()::equals).isEmpty()) {
            throw new IllegalArgumentException("自动化 Turn 必须使用 Execution 冻结 Profile");
        }
        AgentProfile profile =
                profiles.require(snapshot.profile().id(), snapshot.profile().revision());
        requireActive(profile);
        boolean matches = snapshot.provider().equals(profile.spec().provider())
                && snapshot.permissionProfile().equals(profile.spec().permissionProfile())
                && snapshot.turnBudget().equals(profile.spec().budget());
        if (!matches) {
            throw new IllegalArgumentException("自动化执行快照与 Agent Profile 不一致");
        }
        return profile;
    }

    private static void requireTurnSnapshot(AgentTurn turn, AutomationExecutionSnapshot snapshot) {
        boolean matches = turn.profile().equals(snapshot.profile())
                && turn.provider().equals(snapshot.provider())
                && turn.permissionProfile().equals(snapshot.permissionProfile())
                && turn.budget().equals(snapshot.turnBudget());
        if (!matches) {
            throw new IllegalArgumentException("自动化 Turn 与 Execution 冻结快照不一致");
        }
    }

    private static void requireRequestIdentity(AgentTurn turn, CoreRpcContracts.TurnStartPayload request) {
        boolean explicitMatches =
                request.profile().isEmpty() || request.profile().orElseThrow().equals(turn.profile());
        if (!turn.threadId().equals(request.threadId()) || !explicitMatches) {
            throw new IllegalArgumentException("persisted Turn does not match dispatch request");
        }
    }

    private void requireFrozenIdentity(AgentTurn turn, AgentProfile profile, TurnPromptSnapshot prompt) {
        boolean valid = turn.profile().equals(new AgentProfileRef(profile.id(), profile.revision()))
                && turn.provider().equals(profile.spec().provider())
                && turn.permissionProfile().equals(profile.spec().permissionProfile())
                && turn.budget().equals(profile.spec().budget())
                && prompt.profile().equals(turn.profile())
                && prompt.provider().equals(turn.provider())
                && prompt.coreInstructionRevision().equals(CoreSystemInstruction.REVISION);
        if (!valid) {
            throw new IllegalArgumentException("persisted Turn configuration does not match Agent Profile");
        }
    }

    private static void requireActive(AgentProfile profile) {
        if (profile.lifecycle() != ProfileLifecycle.ACTIVE) {
            throw new IllegalArgumentException("new Turn requires an ACTIVE Agent Profile");
        }
    }

    private static void requireActive(Workspace workspace) {
        if (workspace.lifecycle() != WorkspaceLifecycle.ACTIVE) {
            throw new IllegalArgumentException("已归档 Workspace 不能启动新 Turn");
        }
    }
}
