package com.javaclaw.server.rpc;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.Workspace;
import com.javaclaw.protocol.AttachmentRpcContracts;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.DiagnosticsRpcContracts;
import com.javaclaw.protocol.ProviderRpcContracts;
import com.javaclaw.protocol.WorktreeRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.diagnostics.DiagnosticsService;
import com.javaclaw.server.persistence.ApprovalService;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CommandLocks;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.ManagedWorktreeService;
import com.javaclaw.server.persistence.PersistenceException;
import com.javaclaw.server.persistence.ProviderService;
import com.javaclaw.server.persistence.RolloutCommandService;
import com.javaclaw.server.persistence.TurnStartRequest;
import com.javaclaw.server.persistence.TurnStreamService;
import com.javaclaw.server.turn.TurnDispatcher;

/** Core RPC 方法到平台用例的薄映射。 */
public final class CoreRpcHandlers {
    private final CoreCommandService core;
    private final CanonicalJson json;
    private final TurnDispatcher turns;
    private final RolloutCommandService rollouts;
    private final ApprovalService approvals;
    private final AttachmentService attachments;
    private final ProviderService providers;
    private final ManagedWorktreeService worktrees;
    private final DiagnosticsService diagnostics;
    private final Optional<TurnStreamService> streams;

    /**
     * 创建 Core handlers。
     *
     * @param platform Core 平台用例
     * @param json 共享 JSON codec
     * @param interactions Turn 与人工交互用例
     */
    public CoreRpcHandlers(PlatformServices platform, CanonicalJson json, InteractionServices interactions) {
        this(platform, json, interactions, Optional.empty());
    }

    /**
     * 创建具有公开流提交唤醒的 Core handlers。
     *
     * @param platform Core 平台用例
     * @param json 共享 JSON codec
     * @param interactions Turn 与人工交互用例
     * @param streams 组合根共享流；旧宿主为空时依靠持久水位兜底
     */
    public CoreRpcHandlers(
            PlatformServices platform,
            CanonicalJson json,
            InteractionServices interactions,
            Optional<TurnStreamService> streams) {
        this.streams = Objects.requireNonNull(streams, "streams");
        PlatformServices checkedPlatform = Objects.requireNonNull(platform, "platform");
        InteractionServices checkedInteractions = Objects.requireNonNull(interactions, "interactions");
        core = checkedPlatform.core();
        attachments = checkedPlatform.attachments();
        providers = checkedPlatform.providers();
        worktrees = checkedPlatform.worktrees();
        diagnostics = checkedPlatform.diagnostics();
        this.json = Objects.requireNonNull(json, "json");
        turns = checkedInteractions.turns();
        rollouts = checkedInteractions.rollouts();
        approvals = checkedInteractions.approvals();
    }

    /**
     * 注册当前已经实现的 Core 方法。
     *
     * @param builder Router Builder
     */
    public void register(RpcRouter.Builder builder) {
        builder.register("workspace/list", this::listWorkspaces)
                .register("workspace/create", this::createWorkspace)
                .register("workspace/rename", this::renameWorkspace)
                .register("workspace/archive", this::archiveWorkspace)
                .register("thread/list", this::listThreads)
                .register("thread/create", this::createThread)
                .register("thread/read", this::readThread)
                .register("turn/start", this::startTurn)
                .register("turn/read", this::readTurn)
                .register("turn/cancel", this::cancelTurn)
                .register("item/list", this::listItems)
                .register("attachment/read", this::readAttachment)
                .register("attachment/metadata", this::attachmentMetadata)
                .register("attachment/readChunk", this::attachmentChunk)
                .register("provider/list", this::listProviders)
                .register("provider/read", this::readProvider)
                .register("provider/create", this::createProvider)
                .register("provider/update", this::updateProvider)
                .register("provider/archive", this::archiveProvider)
                .register("provider/status", this::probeProvider)
                .register("provider/probe", this::probeProvider)
                .register("approval/list", this::listApprovals)
                .register("approval/resolve", this::resolveApproval)
                .register("worktree/list", this::listWorktrees)
                .register("worktree/read", this::readWorktree)
                .register("worktree/interrupt", this::interruptWorktree)
                .register("worktree/patch/export", this::exportWorktreePatch)
                .register("worktree/backup", this::backupWorktree)
                .register("worktree/cleanup", this::cleanupWorktree)
                .register("diagnostics/read", this::readDiagnostics)
                .register("diagnostics/loginStartup/repair", this::repairLoginStartup)
                .register("thread/rollout/export", this::exportRollout);
        registerAttachmentUploads(builder);
    }

    private void registerAttachmentUploads(RpcRouter.Builder builder) {
        builder.register("attachment/upload/begin", this::beginAttachmentUpload)
                .register("attachment/upload/chunk", this::appendAttachmentChunk)
                .register("attachment/upload/complete", this::completeAttachmentUpload)
                .register("attachment/upload/abort", this::abortAttachmentUpload)
                .register("attachment/upload/read", this::readAttachmentUpload);
    }

    private CanonicalPayload listWorkspaces(CanonicalPayload params) {
        requireEmpty(params);
        return json.encode(new CoreRpcContracts.WorkspaceListResult(core.listWorkspaces()));
    }

    private CanonicalPayload createWorkspace(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        CoreRpcContracts.WorkspaceCreatePayload payload =
                json.decode(command.payload(), CoreRpcContracts.WorkspaceCreatePayload.class);
        Workspace workspace = core.createWorkspace(
                CommandIdentity.from("workspace/create", command, json),
                payload.name(),
                payload.root(),
                payload.execution());
        return json.encode(workspace);
    }

    private CanonicalPayload renameWorkspace(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        CoreRpcContracts.WorkspaceRenamePayload payload =
                json.decode(command.payload(), CoreRpcContracts.WorkspaceRenamePayload.class);
        return json.encode(core.renameWorkspace(
                CommandIdentity.from("workspace/rename", command, json), payload.workspaceId(), payload.name()));
    }

    private CanonicalPayload archiveWorkspace(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        CoreRpcContracts.WorkspaceArchivePayload payload =
                json.decode(command.payload(), CoreRpcContracts.WorkspaceArchivePayload.class);
        return json.encode(
                core.archiveWorkspace(CommandIdentity.from("workspace/archive", command, json), payload.workspaceId()));
    }

    private CanonicalPayload listThreads(CanonicalPayload params) {
        CoreRpcContracts.WorkspaceQuery query = json.decode(params, CoreRpcContracts.WorkspaceQuery.class);
        return json.encode(new CoreRpcContracts.ThreadListResult(core.listThreads(query.workspaceId())));
    }

    private CanonicalPayload createThread(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        CoreRpcContracts.ThreadCreatePayload payload =
                json.decode(command.payload(), CoreRpcContracts.ThreadCreatePayload.class);
        ConversationThread thread = core.createThread(
                CommandIdentity.from("thread/create", command, json),
                payload.workspaceId(),
                payload.parentThreadId(),
                payload.executionIntent(),
                payload.title());
        provisionIsolatedThread(command, thread);
        return json.encode(thread);
    }

    private void provisionIsolatedThread(WriteCommand command, ConversationThread thread) {
        if (thread.executionIntent() != ThreadExecutionIntent.ISOLATED_WRITE) {
            return;
        }
        ThreadId parent = thread.parentThreadId().orElseThrow();
        WorktreeProvisionFingerprint fingerprint =
                new WorktreeProvisionFingerprint(thread.workspaceId(), parent, thread.id());
        CommandIdentity identity = new CommandIdentity(
                "worktree/provision",
                command.idempotencyKey() + ":managed-worktree",
                0,
                json.encode(fingerprint).sha256());
        worktrees.provisionForChild(identity, thread.workspaceId(), parent, thread.id());
    }

    private CanonicalPayload readThread(CanonicalPayload params) {
        CoreRpcContracts.ThreadQuery query = json.decode(params, CoreRpcContracts.ThreadQuery.class);
        return json.encode(
                core.findThread(query.threadId()).orElseThrow(() -> PersistenceException.invalidRequest("Thread 不存在")));
    }

    private CanonicalPayload startTurn(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        CommandIdentity identity = CommandIdentity.from("turn/start", command, json);
        synchronized (CommandLocks.forKey(identity.idempotencyKey())) {
            Optional<AgentTurn> recovered = core.recoverTurnStart(identity);
            if (recovered.isPresent()) {
                AgentTurn turn = recovered.orElseThrow();
                turns.resume(turn.id());
                return json.encode(new CoreRpcContracts.TurnStartResult(turn, turn.resolvedConfig()));
            }
            return startNewTurn(command, identity);
        }
    }

    /** 同一幂等键的首次解析、提交和派发受共享锁串行保护；配置解析不占用数据库事务。 */
    private CanonicalPayload startNewTurn(WriteCommand command, CommandIdentity identity) {
        CoreRpcContracts.TurnStartPayload payload =
                json.decode(command.payload(), CoreRpcContracts.TurnStartPayload.class);
        CorePayloads.Message message = new CorePayloads.Message(
                MessageRole.USER, payload.message(), payload.attachments(), java.util.Optional.empty());
        TurnStartRequest request = turns.resolve(payload, message);
        AgentTurn turn = core.startTurn(identity, request);
        turns.dispatch(turn, payload);
        return json.encode(new CoreRpcContracts.TurnStartResult(turn, turn.resolvedConfig()));
    }

    private CanonicalPayload readTurn(CanonicalPayload params) {
        CoreRpcContracts.TurnQuery query = json.decode(params, CoreRpcContracts.TurnQuery.class);
        return json.encode(
                core.findTurn(query.turnId()).orElseThrow(() -> PersistenceException.invalidRequest("Turn 不存在")));
    }

    private CanonicalPayload cancelTurn(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        CoreRpcContracts.TurnCancelPayload payload =
                json.decode(command.payload(), CoreRpcContracts.TurnCancelPayload.class);
        AgentTurn turn = core.requestTurnCancellation(
                CommandIdentity.from("turn/cancel", command, json), payload.turnId(), payload.reason());
        // 仅在用例事务成功返回后唤醒；发送仍在连接 drain 中进行。
        streams.ifPresent(value -> value.committed(turn.id()));
        turns.cancel(turn.id(), payload.reason());
        return json.encode(turn);
    }

    private CanonicalPayload listItems(CanonicalPayload params) {
        CoreRpcContracts.ItemList query = json.decode(params, CoreRpcContracts.ItemList.class);
        List<ItemEnvelope> items = core.listItems(query.threadId(), query.afterSequence(), query.limit());
        long next = items.isEmpty() ? query.afterSequence() : items.getLast().sequence();
        return json.encode(new CoreRpcContracts.ItemListResult(items, next));
    }

    private CanonicalPayload beginAttachmentUpload(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        AttachmentRpcContracts.BeginPayload payload =
                json.decode(command.payload(), AttachmentRpcContracts.BeginPayload.class);
        return json.encode(attachments.beginUpload(
                payload.scope(),
                CommandIdentity.from("attachment/upload/begin", command, json),
                payload.mediaType(),
                payload.expectedDigest(),
                payload.expectedSizeBytes()));
    }

    private CanonicalPayload appendAttachmentChunk(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        AttachmentRpcContracts.ChunkPayload payload =
                json.decode(command.payload(), AttachmentRpcContracts.ChunkPayload.class);
        return json.encode(attachments.appendUploadChunk(
                payload.scope(),
                CommandIdentity.from("attachment/upload/chunk", command, json),
                payload.uploadId(),
                payload.chunkIndex(),
                payload.content()));
    }

    private CanonicalPayload completeAttachmentUpload(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        AttachmentRpcContracts.CompletePayload payload =
                json.decode(command.payload(), AttachmentRpcContracts.CompletePayload.class);
        return json.encode(attachments.completeUpload(
                payload.scope(),
                CommandIdentity.from("attachment/upload/complete", command, json),
                payload.uploadId()));
    }

    private CanonicalPayload abortAttachmentUpload(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        AttachmentRpcContracts.AbortPayload payload =
                json.decode(command.payload(), AttachmentRpcContracts.AbortPayload.class);
        return json.encode(attachments.abortUpload(
                payload.scope(),
                CommandIdentity.from("attachment/upload/abort", command, json),
                payload.uploadId(),
                payload.reason()));
    }

    private CanonicalPayload readAttachmentUpload(CanonicalPayload params) {
        AttachmentRpcContracts.UploadReadPayload payload =
                json.decode(params, AttachmentRpcContracts.UploadReadPayload.class);
        return json.encode(attachments.readUpload(payload.scope(), payload.uploadId()));
    }

    private CanonicalPayload attachmentMetadata(CanonicalPayload params) {
        var payload = json.decode(params, AttachmentRpcContracts.ReadPayload.class);
        return json.encode(attachments.readMetadata(payload.scope(), payload.digest()));
    }

    private CanonicalPayload attachmentChunk(CanonicalPayload params) {
        var payload = json.decode(params, AttachmentRpcContracts.DownloadChunkPayload.class);
        return json.encode(attachments.readChunk(
                payload.scope(), payload.digest(), payload.offsetBytes(), payload.maximumBytes()));
    }

    private CanonicalPayload readAttachment(CanonicalPayload params) {
        AttachmentRpcContracts.ReadPayload payload = json.decode(params, AttachmentRpcContracts.ReadPayload.class);
        return json.encode(attachments.read(payload.scope(), payload.digest()));
    }

    private CanonicalPayload listProviders(CanonicalPayload params) {
        requireEmpty(params);
        return json.encode(new ProviderRpcContracts.ProviderListResult(providers.listLatest()));
    }

    private CanonicalPayload readProvider(CanonicalPayload params) {
        ProviderRpcContracts.ProviderReadPayload payload =
                json.decode(params, ProviderRpcContracts.ProviderReadPayload.class);
        return json.encode(providers.require(payload.id(), payload.revision()));
    }

    private CanonicalPayload createProvider(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        ProviderRpcContracts.ProviderCreatePayload payload =
                json.decode(command.payload(), ProviderRpcContracts.ProviderCreatePayload.class);
        return json.encode(providers.create(
                CommandIdentity.from("provider/create", command, json),
                payload.id(),
                payload.spec(),
                payload.lifecycle()));
    }

    private CanonicalPayload updateProvider(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        ProviderRpcContracts.ProviderUpdatePayload payload =
                json.decode(command.payload(), ProviderRpcContracts.ProviderUpdatePayload.class);
        return json.encode(providers.update(
                CommandIdentity.from("provider/update", command, json),
                payload.id(),
                payload.spec(),
                payload.lifecycle()));
    }

    private CanonicalPayload archiveProvider(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        ProviderRpcContracts.ProviderArchivePayload payload =
                json.decode(command.payload(), ProviderRpcContracts.ProviderArchivePayload.class);
        return json.encode(providers.archive(CommandIdentity.from("provider/archive", command, json), payload.id()));
    }

    private CanonicalPayload probeProvider(CanonicalPayload params) {
        ProviderRpcContracts.ProviderProbePayload payload =
                json.decode(params, ProviderRpcContracts.ProviderProbePayload.class);
        return json.encode(providers.probe(payload.provider()));
    }

    private CanonicalPayload exportRollout(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        CoreRpcContracts.RolloutExportPayload payload =
                json.decode(command.payload(), CoreRpcContracts.RolloutExportPayload.class);
        return json.encode(rollouts.export(
                CommandIdentity.from("thread/rollout/export", command, json),
                payload.threadId(),
                payload.outputFile()));
    }

    private CanonicalPayload listApprovals(CanonicalPayload params) {
        CoreRpcContracts.ApprovalListPayload payload = json.decode(params, CoreRpcContracts.ApprovalListPayload.class);
        return json.encode(
                new CoreRpcContracts.ApprovalListResult(approvals.list(payload.turnId(), payload.includeResolved())));
    }

    private CanonicalPayload resolveApproval(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        CoreRpcContracts.ApprovalResolvePayload payload =
                json.decode(command.payload(), CoreRpcContracts.ApprovalResolvePayload.class);
        com.javaclaw.api.ApprovalRecord resolved =
                approvals.resolve(CommandIdentity.from("approval/resolve", command, json), payload);
        turns.resume(resolved.request().turnId());
        return json.encode(resolved);
    }

    private CanonicalPayload listWorktrees(CanonicalPayload params) {
        WorktreeRpcContracts.ListPayload payload = json.decode(params, WorktreeRpcContracts.ListPayload.class);
        return json.encode(
                new WorktreeRpcContracts.ListResult(worktrees.list(payload.workspaceId(), payload.includeCleaned())));
    }

    private CanonicalPayload readWorktree(CanonicalPayload params) {
        WorktreeRpcContracts.ReadPayload payload = json.decode(params, WorktreeRpcContracts.ReadPayload.class);
        return json.encode(worktrees.read(payload.worktreeId()));
    }

    private CanonicalPayload interruptWorktree(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        WorktreeRpcContracts.InterruptPayload payload =
                json.decode(command.payload(), WorktreeRpcContracts.InterruptPayload.class);
        com.javaclaw.server.persistence.ManagedWorktreeInterruptResult result = worktrees.interrupt(
                CommandIdentity.from("worktree/interrupt", command, json), payload.worktreeId(), payload.reason());
        result.turnId().ifPresent(turnId -> {
            streams.ifPresent(value -> value.committed(turnId));
            turns.cancel(turnId, payload.reason());
        });
        return json.encode(result.worktree());
    }

    private CanonicalPayload exportWorktreePatch(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        WorktreeRpcContracts.ArtifactPayload payload =
                json.decode(command.payload(), WorktreeRpcContracts.ArtifactPayload.class);
        return json.encode(worktrees.exportPatch(
                CommandIdentity.from("worktree/patch/export", command, json), payload.worktreeId()));
    }

    private CanonicalPayload backupWorktree(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        WorktreeRpcContracts.ArtifactPayload payload =
                json.decode(command.payload(), WorktreeRpcContracts.ArtifactPayload.class);
        return json.encode(
                worktrees.backup(CommandIdentity.from("worktree/backup", command, json), payload.worktreeId()));
    }

    private CanonicalPayload cleanupWorktree(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        WorktreeRpcContracts.CleanupPayload payload =
                json.decode(command.payload(), WorktreeRpcContracts.CleanupPayload.class);
        return json.encode(
                worktrees.cleanup(CommandIdentity.from("worktree/cleanup", command, json), payload.worktreeId()));
    }

    private CanonicalPayload readDiagnostics(CanonicalPayload params) {
        requireEmpty(params);
        return json.encode(diagnostics.read());
    }

    private CanonicalPayload repairLoginStartup(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        json.decode(command.payload(), DiagnosticsRpcContracts.LoginStartupRepairPayload.class);
        return json.encode(
                diagnostics.repairLoginStartup(CommandIdentity.from("diagnostics/loginStartup/repair", command, json)));
    }

    /**
     * 数据与本机副作用用例分组。
     *
     * @param core Workspace、Thread、Turn、Item
     * @param attachments 内容寻址附件
     * @param providers Provider 版本服务
     * @param worktrees Git Worktree
     * @param diagnostics 非敏感诊断
     */
    public record PlatformServices(
            CoreCommandService core,
            AttachmentService attachments,
            ProviderService providers,
            ManagedWorktreeService worktrees,
            DiagnosticsService diagnostics) {
        /** 校验平台用例。 */
        public PlatformServices {
            Objects.requireNonNull(core, "core");
            Objects.requireNonNull(attachments, "attachments");
            Objects.requireNonNull(providers, "providers");
            Objects.requireNonNull(worktrees, "worktrees");
            Objects.requireNonNull(diagnostics, "diagnostics");
        }
    }

    /**
     * Turn 与人工交互用例分组。
     *
     * @param turns Turn 调度器
     * @param rollouts Rollout 导出
     * @param approvals 持久审批
     */
    public record InteractionServices(TurnDispatcher turns, RolloutCommandService rollouts, ApprovalService approvals) {
        /** 校验交互用例。 */
        public InteractionServices {
            Objects.requireNonNull(turns, "turns");
            Objects.requireNonNull(rollouts, "rollouts");
            Objects.requireNonNull(approvals, "approvals");
        }
    }

    private void requireEmpty(CanonicalPayload params) {
        if (!"{}".equals(params.json())) {
            throw new IllegalArgumentException("params must be empty");
        }
    }

    private record WorktreeProvisionFingerprint(
            com.javaclaw.api.WorkspaceId workspaceId,
            com.javaclaw.api.ThreadId parentThreadId,
            com.javaclaw.api.ThreadId childThreadId) {}
}
