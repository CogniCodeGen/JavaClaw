package com.javaclaw.server.turn;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ApprovalRecord;
import com.javaclaw.api.ApprovalRequest;
import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CoreTools;
import com.javaclaw.api.EffectReceipt;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionResolver;
import com.javaclaw.api.ToolCallRequest;
import com.javaclaw.api.ToolCallResult;
import com.javaclaw.api.ToolCatalogQueryResult;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.UnattendedInvocationOutcome;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.extension.spi.ExtensionAccessDeniedException;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ToolRpcContracts;
import com.javaclaw.runtime.GovernedToolExecutor;
import com.javaclaw.runtime.ToolCatalogPort;
import com.javaclaw.runtime.ToolExecutionOutcome;
import com.javaclaw.runtime.TurnFailureException;
import com.javaclaw.server.extension.contract.ExtensionHost;
import com.javaclaw.server.mcp.McpService;
import com.javaclaw.server.persistence.ApprovalService;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.ExtensionCatalogRepository;
import com.javaclaw.server.persistence.ManagedWorktreeService;
import com.javaclaw.server.persistence.PermissionProfileService;
import com.javaclaw.server.security.grant.UnattendedToolGrantService;
import com.javaclaw.server.security.grant.UnattendedToolReservation;

/** 将 Core 搜索入口和启用的 Extension Tool 组合成冻结目录与治理执行器。 */
public final class ExtensionToolPlatform implements ToolCatalogPort, GovernedToolExecutor {
    private final ExtensionHost extensions;
    private final CoreCommandService core;
    private final ApprovalService approvals;
    private final PermissionProfileService profiles;
    private final ManagedWorktreeService worktrees;
    private final ExtensionCatalogRepository catalog;
    private final CanonicalJson json;
    private final Clock clock;
    private final UnattendedToolGrantService unattendedGrants;
    private final Optional<McpService> mcp;
    private final java.util.concurrent.atomic.AtomicReference<CollaborationToolExecutor> collaboration =
            new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * 创建工具平台。
     *
     * @param dependencies 工具发现、治理与执行所需的显式依赖
     */
    public ExtensionToolPlatform(Dependencies dependencies) {
        Dependencies checked = Objects.requireNonNull(dependencies, "dependencies");
        extensions = checked.extensions();
        core = checked.core();
        approvals = checked.approvals();
        profiles = checked.profiles();
        worktrees = checked.worktrees();
        catalog = checked.catalog();
        json = checked.json();
        clock = checked.clock();
        unattendedGrants = checked.unattendedGrants();
        mcp = checked.mcp();
    }

    /**
     * 工具平台的显式组合依赖。
     *
     * @param extensions 权威扩展 Host
     * @param core Core 查询服务
     * @param approvals 持久审批状态机
     * @param profiles 版本化权限服务
     * @param worktrees Managed Worktree 权威服务
     * @param catalog 内置能力实时目录
     * @param json 规范 JSON codec
     * @param clock 平台时钟
     * @param unattendedGrants Schedule 无人值守工具授权账本
     * @param mcp MCP Host；尚未装配时为空
     */
    public record Dependencies(
            ExtensionHost extensions,
            CoreCommandService core,
            ApprovalService approvals,
            PermissionProfileService profiles,
            ManagedWorktreeService worktrees,
            ExtensionCatalogRepository catalog,
            CanonicalJson json,
            Clock clock,
            UnattendedToolGrantService unattendedGrants,
            Optional<McpService> mcp) {
        /** 校验全部组合依赖，禁止通过 null 表示未装配能力。 */
        public Dependencies {
            Objects.requireNonNull(extensions, "extensions");
            Objects.requireNonNull(core, "core");
            Objects.requireNonNull(approvals, "approvals");
            Objects.requireNonNull(profiles, "profiles");
            Objects.requireNonNull(worktrees, "worktrees");
            Objects.requireNonNull(catalog, "catalog");
            Objects.requireNonNull(json, "json");
            Objects.requireNonNull(clock, "clock");
            Objects.requireNonNull(unattendedGrants, "unattendedGrants");
            mcp = Objects.requireNonNull(mcp, "mcp");
        }
    }

    /**
     * 在启动恢复前绑定唯一协作服务；未绑定时不公开协作工具。
     *
     * @param service 与 RPC 共用的权威协作用例
     */
    public void bindCollaboration(AgentCollaborationService service) {
        if (!collaboration.compareAndSet(null, new CollaborationToolExecutor(service, core, json, clock))) {
            throw new IllegalStateException("协作工具服务已经绑定");
        }
    }

    @Override
    public ToolCatalogSnapshot freeze(
            com.javaclaw.api.TurnId turnId, PermissionProfile permissions, CancellationToken cancellation) {
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(permissions, "permissions");
        Objects.requireNonNull(cancellation, "cancellation").throwIfCancelled();
        List<ToolDescriptor> tools = authorizedTools(permissions);
        return new ToolCatalogSnapshot(
                turnId, ToolCatalogQueries.revision(json, tools, permissions), tools, permissions, Instant.now(clock));
    }

    /** 重新绑定自动化冻结目录；实时目录任何差异均失败关闭，避免恢复时获得新工具或继续使用已变更工具。 */
    @Override
    public ToolCatalogSnapshot bindFrozen(
            com.javaclaw.api.TurnId turnId,
            WorkspaceId workspaceId,
            ToolCatalogSnapshot frozen,
            PermissionProfile currentPermissions,
            CancellationToken cancellation) {
        Objects.requireNonNull(frozen, "frozen");
        ToolCatalogSnapshot current = freeze(turnId, workspaceId, currentPermissions, cancellation);
        boolean frozenToolsStillValid = frozen.tools().stream().allMatch(tool -> containsExact(current, tool));
        if (!frozenToolsStillValid
                || !sameNonFilePermissions(current.permissionCeiling(), frozen.permissionCeiling())) {
            throw new TurnFailureException("TOOL_CATALOG_CHANGED", "自动化执行的冻结工具目录已变化或权限已撤销");
        }
        return new ToolCatalogSnapshot(
                turnId, frozen.catalogRevision(), frozen.tools(), current.permissionCeiling(), frozen.capturedAt());
    }

    private static boolean containsExact(ToolCatalogSnapshot current, ToolDescriptor frozen) {
        try {
            return current.require(frozen.identity()).equals(frozen);
        } catch (IllegalArgumentException missing) {
            return false;
        }
    }

    private static boolean sameNonFilePermissions(PermissionProfile current, PermissionProfile frozen) {
        return current.id().equals(frozen.id())
                && current.version() == frozen.version()
                && current.network().equals(frozen.network())
                && current.processes().equals(frozen.processes())
                && current.tools().equals(frozen.tools())
                && current.resources().equals(frozen.resources());
    }

    @Override
    public ToolCatalogSnapshot freeze(
            com.javaclaw.api.TurnId turnId,
            WorkspaceId workspaceId,
            PermissionProfile permissions,
            CancellationToken cancellation) {
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(permissions, "permissions");
        Objects.requireNonNull(cancellation, "cancellation").throwIfCancelled();
        List<ToolDescriptor> tools = authorizedTools(permissions, Optional.of(workspaceId));
        return new ToolCatalogSnapshot(
                turnId, ToolCatalogQueries.revision(json, tools, permissions), tools, permissions, Instant.now(clock));
    }

    @Override
    public List<ToolDescriptor> initialTools(ToolCatalogSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return snapshot.tools().stream()
                .filter(tool -> tool.identity().producerId().equals(CoreTools.PRODUCER_ID))
                .toList();
    }

    /**
     * 按当前扩展状态与指定权限搜索目录，供 Protocol v3 {@code tool/search} 使用。
     *
     * @param permissions 已按 Workspace 求交的权限
     * @param query 关键词
     * @param limit 最大结果数
     * @return 稳定名称排序的匹配项
     */
    public List<ToolDescriptor> search(PermissionProfile permissions, String query, int limit) {
        ToolRpcContracts.SearchArguments arguments = new ToolRpcContracts.SearchArguments(query, limit);
        return ToolCatalogQueries.search(authorizedTools(permissions), arguments);
    }

    /**
     * 返回权限编辑器可选择的候选，以及当前已授权目录的权威版本。
     *
     * <p>候选列表忽略尚未写入的工具名白名单，目录版本仍只覆盖当前真正可执行的工具，防止客户端根据分页结果伪造版本。
     *
     * @param workspaceId Workspace
     * @param permissions 当前精确权限
     * @param query 查询词
     * @param limit 最大结果数
     * @return 候选切片与权威目录版本
     */
    public ToolCatalogQueryResult selectableCatalog(
            WorkspaceId workspaceId, PermissionProfile permissions, String query, int limit) {
        ToolRpcContracts.SearchArguments arguments = new ToolRpcContracts.SearchArguments(query, limit);
        Optional<WorkspaceId> scope = Optional.of(Objects.requireNonNull(workspaceId, "workspaceId"));
        List<ToolDescriptor> executable = authorizedTools(permissions, scope);
        List<ToolDescriptor> candidates = ToolCatalogQueries.search(selectableTools(permissions, scope), arguments);
        return new ToolCatalogQueryResult(ToolCatalogQueries.revision(json, executable, permissions), candidates);
    }

    /**
     * 返回精确 Agent Profile 已收窄后的可执行工具目录。
     *
     * @param workspaceId Workspace
     * @param permissions 已与 Profile 可见工具求交的权限
     * @param query 查询词
     * @param limit 最大结果数
     * @return 可执行工具切片与同一完整目录的权威版本
     */
    public ToolCatalogQueryResult executableCatalog(
            WorkspaceId workspaceId, PermissionProfile permissions, String query, int limit) {
        ToolRpcContracts.SearchArguments arguments = new ToolRpcContracts.SearchArguments(query, limit);
        List<ToolDescriptor> executable = authorizedTools(permissions, Optional.of(workspaceId));
        return new ToolCatalogQueryResult(
                ToolCatalogQueries.revision(json, executable, permissions),
                ToolCatalogQueries.search(executable, arguments));
    }

    @Override
    public ToolExecutionOutcome execute(
            ToolCallRequest request,
            ToolDescriptor descriptor,
            ToolCatalogSnapshot snapshot,
            PermissionProfile permissions,
            CancellationToken cancellation)
            throws Exception {
        requireFrozen(request, descriptor, snapshot);
        Objects.requireNonNull(cancellation, "cancellation").throwIfCancelled();
        PermissionProfile current = currentPermissions(request, permissions, snapshot);
        requireAllowed(descriptor, current);
        SkillTurnCeiling.requireAllowed(core, json, request);
        if (descriptor.identity().equals(CoreTools.search().identity())) {
            return searchFrozen(request, snapshot);
        }
        Optional<UnattendedToolReservation> reservation = reserveUnattended(request, descriptor, snapshot);
        if (reservation.isEmpty() && requiresApproval(descriptor, current)) {
            Optional<ToolExecutionOutcome> rejected = approve(request, descriptor, cancellation);
            if (rejected.isPresent()) {
                return rejected.orElseThrow();
            }
            current = currentPermissions(request, permissions, snapshot);
            if (!isAllowed(descriptor, current)) {
                approvals.revokeApproved(approvalId(request), "批准后权限被实时撤销");
                requireAllowed(descriptor, current);
            }
        }
        if (reservation.isPresent()) {
            current = currentPermissions(request, permissions, snapshot);
            requireAllowed(descriptor, current);
        }
        return executeAuthorized(request, descriptor, current, cancellation, reservation);
    }

    private ToolExecutionOutcome executeAuthorized(
            ToolCallRequest request,
            ToolDescriptor descriptor,
            PermissionProfile current,
            CancellationToken cancellation,
            Optional<UnattendedToolReservation> reservation)
            throws Exception {
        try {
            ToolExecutionOutcome outcome;
            if (CollaborationTools.matches(descriptor)) {
                outcome = collaboration.get().execute(request, cancellation);
            } else if (isMcp(descriptor)) {
                outcome = executeMcp(request, descriptor, cancellation);
            } else if (descriptor.identity().equals(CoreTools.worktreeApply().identity())) {
                outcome = new WorktreeToolExecutor(core, worktrees, json, clock).execute(request);
            } else {
                var execution = extensions.executeToolWithFacts(request, descriptor, current, cancellation);
                ExtensionResponse response = SkillTurnCeiling.filter(core, json, request, execution.response());
                Optional<EffectReceipt> receipt = receipt(request, descriptor, response);
                ToolCallResult result =
                        new ToolCallResult(request.callId(), execution.success(), response.payload(), receipt);
                outcome = new ToolExecutionOutcome(result, List.of(), execution.facts());
            }
            completeReservation(reservation, descriptor, outcome);
            return outcome;
        } catch (Exception failure) {
            if (reservation.isEmpty()) {
                throw failure;
            }
            markUnknown(reservation.orElseThrow(), failure);
            TurnFailureException unknown = new TurnFailureException("UNKNOWN_OUTCOME", "无人值守工具结果未知；授权额度已消费且本次调用禁止自动重试");
            unknown.initCause(failure);
            throw unknown;
        }
    }

    private Optional<UnattendedToolReservation> reserveUnattended(
            ToolCallRequest request, ToolDescriptor descriptor, ToolCatalogSnapshot snapshot) {
        return core.unattendedExecutionScope(request.turnId()).map(scope -> {
            String invocationId = "schedule-tool-"
                    + json.encode(new UnattendedInvocationFingerprint(scope, request.idempotencyKey()))
                            .sha256();
            try {
                return unattendedGrants.reserve(
                        scope, descriptor, snapshot.catalogRevision(), request.arguments(), invocationId);
            } catch (SecurityException denied) {
                throw new TurnFailureException("UNATTENDED_GRANT_REQUIRED", "Schedule 工具调用缺少唯一、有效且精确匹配的无人值守授权");
            }
        });
    }

    private void completeReservation(
            Optional<UnattendedToolReservation> reservation, ToolDescriptor descriptor, ToolExecutionOutcome outcome) {
        if (reservation.isEmpty()) {
            return;
        }
        ToolCallResult result = outcome.result();
        if (result.success()
                && descriptor.risk() != ToolRisk.READ_ONLY
                && result.receipt().isEmpty()) {
            throw new IllegalStateException("有副作用的无人值守工具缺少 EffectReceipt");
        }
        UnattendedInvocationOutcome finalOutcome =
                result.success() ? UnattendedInvocationOutcome.SUCCEEDED : UnattendedInvocationOutcome.FAILED;
        unattendedGrants.recordOutcome(reservation.orElseThrow(), finalOutcome);
    }

    private void markUnknown(UnattendedToolReservation reservation, Exception original) {
        try {
            unattendedGrants.recordOutcome(reservation, UnattendedInvocationOutcome.UNKNOWN_OUTCOME);
        } catch (RuntimeException ledgerFailure) {
            original.addSuppressed(ledgerFailure);
        }
    }

    private ToolExecutionOutcome searchFrozen(ToolCallRequest request, ToolCatalogSnapshot snapshot) {
        ToolRpcContracts.SearchArguments arguments =
                json.decode(request.arguments(), ToolRpcContracts.SearchArguments.class);
        List<ToolDescriptor> found = ToolCatalogQueries.search(snapshot.tools(), arguments).stream()
                .filter(tool -> !tool.identity().equals(CoreTools.search().identity()))
                .toList();
        ToolCallResult result = new ToolCallResult(
                request.callId(),
                true,
                json.encode(new ToolRpcContracts.SearchResult(snapshot.catalogRevision(), found)),
                Optional.empty());
        return new ToolExecutionOutcome(result, found);
    }

    private PermissionProfile currentPermissions(
            ToolCallRequest request, PermissionProfile frozen, ToolCatalogSnapshot snapshot) {
        AgentTurn turn = core.findTurn(request.turnId())
                .orElseThrow(() -> new TurnFailureException("TURN_NOT_FOUND", "工具所属 Turn 不存在"));
        ThreadExecutionScope scope = ThreadExecutionScope.resolve(core, worktrees, turn.threadId());
        if (!scope.root().equals(turn.executionRoot())) {
            throw new TurnFailureException("EXECUTION_ROOT_CHANGED", "Turn executionRoot 与当前权威绑定不一致");
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
                PermissionResolver.intersect(List.of(frozen, snapshot.permissionCeiling(), current)));
    }

    private List<ToolDescriptor> authorizedTools(PermissionProfile permissions) {
        return authorizedTools(permissions, Optional.empty());
    }

    private List<ToolDescriptor> authorizedTools(PermissionProfile permissions, Optional<WorkspaceId> workspaceId) {
        Objects.requireNonNull(permissions, "permissions");
        return availableTools(workspaceId)
                .filter(tool -> isAllowed(tool, permissions))
                .sorted(Comparator.comparing(tool -> tool.identity().name()))
                .toList();
    }

    private List<ToolDescriptor> selectableTools(PermissionProfile permissions, Optional<WorkspaceId> workspaceId) {
        Objects.requireNonNull(permissions, "permissions");
        return availableTools(workspaceId)
                .filter(tool -> ToolSelectionPolicy.allows(tool, permissions))
                .sorted(Comparator.comparing(tool -> tool.identity().name()))
                .toList();
    }

    private Stream<ToolDescriptor> availableTools(Optional<WorkspaceId> workspaceId) {
        Stream<ToolDescriptor> platform =
                Stream.concat(Stream.of(CoreTools.search(), CoreTools.worktreeApply()), extensions.tools().stream());
        Stream<ToolDescriptor> remote = workspaceId.filter(ignored -> mcpEnabled()).flatMap(ignored -> mcp).stream()
                .flatMap(service -> service.toolDescriptors(workspaceId.orElseThrow()).stream());
        Stream<ToolDescriptor> agents =
                collaboration.get() == null ? Stream.empty() : CollaborationTools.all().stream();
        return Stream.concat(Stream.concat(platform, agents), remote);
    }

    private ToolExecutionOutcome executeMcp(
            ToolCallRequest request, ToolDescriptor descriptor, CancellationToken cancellation) throws Exception {
        catalog.requireEnabled(new ExtensionId(BuiltinExtensionIds.MCP));
        AgentTurn turn = core.findTurn(request.turnId())
                .orElseThrow(() -> new TurnFailureException("TURN_NOT_FOUND", "工具所属 Turn 不存在"));
        Workspace workspace = core.workspaceForThread(turn.threadId());
        com.javaclaw.api.McpInvocationResult response = mcp.orElseThrow(
                        () -> new TurnFailureException("MCP_UNAVAILABLE", "MCP Host 未装配"))
                .invoke(
                        request.turnId(),
                        workspace.id(),
                        descriptor,
                        request.arguments(),
                        request.idempotencyKey(),
                        cancellation);
        Optional<EffectReceipt> receipt = response.successful()
                ? Optional.of(new EffectReceipt(
                        request.idempotencyKey(),
                        descriptor.identity().name(),
                        request.arguments().sha256(),
                        response.payload().sha256(),
                        Instant.now(clock)))
                : Optional.empty();
        return ToolExecutionOutcome.resultOnly(
                new ToolCallResult(request.callId(), response.successful(), response.payload(), receipt));
    }

    private static boolean isMcp(ToolDescriptor descriptor) {
        return descriptor.identity().producerId().startsWith("mcp.");
    }

    private boolean mcpEnabled() {
        try {
            catalog.requireEnabled(new ExtensionId(BuiltinExtensionIds.MCP));
            return true;
        } catch (ExtensionAccessDeniedException disabled) {
            return false;
        }
    }

    private static void requireFrozen(
            ToolCallRequest request, ToolDescriptor descriptor, ToolCatalogSnapshot snapshot) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(snapshot, "snapshot");
        if (!request.turnId().equals(snapshot.turnId())
                || request.expectedCatalogRevision() != snapshot.catalogRevision()
                || !snapshot.require(request.tool()).equals(descriptor)) {
            throw new TurnFailureException("TOOL_CATALOG_CHANGED", "工具调用与本 Turn 冻结目录不一致");
        }
    }

    private static void requireAllowed(ToolDescriptor descriptor, PermissionProfile permissions) {
        if (!isAllowed(descriptor, permissions)) {
            throw new TurnFailureException("TOOL_PERMISSION_REVOKED", "工具权限已被撤销或风险超出上限");
        }
    }

    private static boolean isAllowed(ToolDescriptor descriptor, PermissionProfile permissions) {
        return permissions.tools().allowedTools().contains(descriptor.identity().name())
                && descriptor.risk().ordinal()
                        <= permissions.tools().maximumRisk().ordinal();
    }

    private Optional<ToolExecutionOutcome> approve(
            ToolCallRequest request, ToolDescriptor descriptor, CancellationToken cancellation)
            throws InterruptedException {
        AgentTurn turn = core.findTurn(request.turnId())
                .orElseThrow(() -> new TurnFailureException("TURN_NOT_FOUND", "工具所属 Turn 不存在"));
        Instant createdAt = Instant.now(clock);
        Instant expiresAt = earliest(
                createdAt.plus(Duration.ofMinutes(15)),
                turn.createdAt().plus(turn.budget().wallTime()));
        if (!expiresAt.isAfter(createdAt)) {
            throw new TurnFailureException("BUDGET_EXCEEDED", "Turn 在发起审批前已超过时间预算");
        }
        ApprovalRequest approval = new ApprovalRequest(
                approvalId(request),
                request.turnId(),
                request.callId(),
                descriptor.identity(),
                descriptor.risk(),
                descriptor.description() + "；风险等级：" + descriptor.risk().name(),
                request.arguments().sha256(),
                createdAt,
                expiresAt);
        ApprovalRecord resolved = approvals.await(approval, cancellation);
        cancellation.throwIfCancelled();
        return switch (resolved.state()) {
            case APPROVED -> {
                approvals.markApprovedExternalCall(approval);
                yield Optional.empty();
            }
            case DENIED -> Optional.of(rejected(request, "TOOL_APPROVAL_DENIED", "用户拒绝了本次工具调用"));
            case EXPIRED -> Optional.of(rejected(request, "TOOL_APPROVAL_EXPIRED", "工具审批已超过有效期"));
            case CANCELLED -> throw new TurnFailureException("TOOL_APPROVAL_CANCELLED", "工具审批随 Turn 取消");
            case PENDING, REVOKED -> throw new TurnFailureException("TOOL_APPROVAL_INVALID", "工具审批状态无效");
        };
    }

    private static boolean requiresApproval(ToolDescriptor descriptor, PermissionProfile permissions) {
        if (descriptor.identity().equals(CoreTools.worktreeApply().identity())) {
            return true;
        }
        ApprovalRequirement requirement = permissions.tools().approvalRequirement();
        return requirement == ApprovalRequirement.EVERY_CALL
                || requirement == ApprovalRequirement.RISKY && descriptor.risk() != ToolRisk.READ_ONLY;
    }

    private ToolExecutionOutcome rejected(ToolCallRequest request, String code, String message) {
        ToolCallResult result = new ToolCallResult(
                request.callId(), false, json.encode(new ApprovalFailure(code, message)), Optional.empty());
        return ToolExecutionOutcome.resultOnly(result);
    }

    private String approvalId(ToolCallRequest request) {
        return "approval-"
                + json.encode(new ApprovalFingerprint(
                                request.turnId(),
                                request.callId(),
                                request.tool(),
                                request.arguments().sha256()))
                        .sha256();
    }

    private static Instant earliest(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private Optional<EffectReceipt> receipt(
            ToolCallRequest request, ToolDescriptor descriptor, ExtensionResponse response) {
        if (descriptor.risk() == ToolRisk.READ_ONLY) {
            return Optional.empty();
        }
        return Optional.of(new EffectReceipt(
                request.idempotencyKey(),
                descriptor.identity().name(),
                request.arguments().sha256(),
                response.payload().sha256(),
                Instant.now(clock)));
    }

    private record ApprovalFingerprint(
            com.javaclaw.api.TurnId turnId, String callId, com.javaclaw.api.ToolIdentity tool, String requestDigest) {}

    private record UnattendedInvocationFingerprint(
            com.javaclaw.api.UnattendedExecutionScope scope, String toolCallIdempotencyKey) {}

    private record ApprovalFailure(String code, String message) {}
}
