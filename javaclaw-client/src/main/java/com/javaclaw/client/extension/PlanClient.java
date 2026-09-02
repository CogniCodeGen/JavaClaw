package com.javaclaw.client.extension;

import java.util.Optional;

import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.OrchestrationContracts;
import com.javaclaw.builtin.contracts.PlanContracts;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.extension.spi.ExtensionExecutionReceipt;

/** Plan Definition 与可恢复 Execution 的强类型 SDK facade。 */
public final class PlanClient {
    private final AutomationClientSupport<PlanContracts.Definition, PlanContracts.ManagementSaveRequest> support;
    private final BuiltinClientCalls calls;

    PlanClient(ExtensionClient extensions) {
        support = new AutomationClientSupport<>(
                extensions,
                BuiltinExtensionIds.PLAN,
                PlanContracts.Definition.class,
                "definition/create",
                "definition/update",
                PlanContracts.ManagementSaveRequest::id);
        calls = new BuiltinClientCalls(extensions, BuiltinExtensionIds.PLAN);
    }

    /** @return 指定 Plan Definition */
    public PlanContracts.Definition read(WorkspaceId workspaceId, String id) {
        return support.read(workspaceId, id);
    }

    /** @return 稳定键分页的 Plan Definition */
    public TypedDocumentPage<PlanContracts.Definition> list(WorkspaceId workspaceId, String afterKey, int limit) {
        return support.list(workspaceId, afterKey, limit);
    }

    /** @return 由服务端生成 revision、摘要和时间的 Plan Definition */
    public PlanContracts.Definition create(
            WorkspaceId workspaceId, PlanContracts.ManagementSaveRequest request, CommandOptions options) {
        return support.create(workspaceId, request, options);
    }

    /** @return 按 expected revision 更新后的 Plan Definition */
    public PlanContracts.Definition update(
            WorkspaceId workspaceId, PlanContracts.ManagementSaveRequest request, CommandOptions options) {
        return support.update(workspaceId, request, options);
    }

    /** @return 删除确认 */
    public DocumentContracts.Deleted delete(WorkspaceId workspaceId, String id, CommandOptions options) {
        return support.delete(workspaceId, id, options);
    }

    /** @return 已事务提交且不含内部恢复 payload 的 Execution 摘要 */
    public ExtensionExecutionReceipt start(
            WorkspaceId workspaceId,
            Optional<ThreadId> parentThreadId,
            OrchestrationContracts.StartRequest request,
            CommandOptions options) {
        return support.start(workspaceId, parentThreadId, request, options);
    }

    /**
     * 提交模型生成的 Plan Proposal；不会直接写入 Definition。
     *
     * @param workspaceId Workspace
     * @param request 提案及精确目标 revision
     * @param options 创建幂等键，expected revision 必须为 0
     * @return 等待人工处理的 Proposal
     */
    public PlanContracts.Proposal propose(
            WorkspaceId workspaceId, PlanContracts.ProposeRequest request, CommandOptions options) {
        return calls.command(workspaceId, "proposal/submit", request, options, PlanContracts.Proposal.class);
    }

    /**
     * 读取 Plan Proposal。
     *
     * @param workspaceId Workspace
     * @param id Proposal 标识
     * @return 权威 Proposal
     */
    public PlanContracts.Proposal readProposal(WorkspaceId workspaceId, String id) {
        return calls.query(workspaceId, "proposal/read", new DocumentContracts.Key(id), PlanContracts.Proposal.class);
    }

    /**
     * 分页列出 Plan Proposal。
     *
     * @param workspaceId Workspace
     * @param afterKey 排他游标
     * @param limit 页大小
     * @return Proposal 页
     */
    public TypedDocumentPage<PlanContracts.Proposal> listProposals(
            WorkspaceId workspaceId, String afterKey, int limit) {
        return calls.page(
                workspaceId,
                "proposal/list",
                new DocumentContracts.PageRequest(afterKey, limit),
                PlanContracts.Proposal.class);
    }

    /**
     * 人工采纳精确 Proposal，并在同一事务写入目标 Definition。
     *
     * @param workspaceId Workspace
     * @param id Proposal 标识
     * @param options 幂等键与 Proposal expected revision
     * @return 已采纳 Proposal
     */
    public PlanContracts.Proposal adoptProposal(WorkspaceId workspaceId, String id, CommandOptions options) {
        return decideProposal(workspaceId, "proposal/adopt", id, options);
    }

    /**
     * 人工拒绝精确 Proposal。
     *
     * @param workspaceId Workspace
     * @param id Proposal 标识
     * @param options 幂等键与 Proposal expected revision
     * @return 已拒绝 Proposal
     */
    public PlanContracts.Proposal rejectProposal(WorkspaceId workspaceId, String id, CommandOptions options) {
        return decideProposal(workspaceId, "proposal/reject", id, options);
    }

    private PlanContracts.Proposal decideProposal(
            WorkspaceId workspaceId, String operation, String id, CommandOptions options) {
        return calls.command(
                workspaceId, operation, new PlanContracts.ProposalDecision(id), options, PlanContracts.Proposal.class);
    }
}
