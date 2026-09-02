package com.javaclaw.client.extension;

import java.util.Map;
import java.util.Optional;

import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.SkillContracts;
import com.javaclaw.builtin.contracts.SkillTransferContracts;
import com.javaclaw.client.CommandOptions;

/** Skill Proposal、Draft、Published 与 Turn 冻结目录的强类型 SDK facade。 */
public final class SkillClient {
    private final BuiltinClientCalls calls;

    /**
     * 创建 Skill facade。
     *
     * @param extensions 通用扩展客户端
     */
    public SkillClient(ExtensionClient extensions) {
        calls = new BuiltinClientCalls(extensions, BuiltinExtensionIds.SKILL);
    }

    /**
     * 读取 Draft。
     *
     * @param workspaceId Workspace
     * @param id Skill 标识
     * @return Draft
     */
    public SkillContracts.Draft readDraft(WorkspaceId workspaceId, String id) {
        return calls.query(workspaceId, "draft/read", new SkillContracts.Key(id), SkillContracts.Draft.class);
    }

    /**
     * 分页列出 Draft。
     *
     * @param workspaceId Workspace
     * @param afterKey 排他游标
     * @param limit 页大小
     * @return Draft 页
     */
    public TypedDocumentPage<SkillContracts.Draft> listDrafts(WorkspaceId workspaceId, String afterKey, int limit) {
        return calls.page(
                workspaceId,
                "draft/list",
                new DocumentContracts.PageRequest(afterKey, limit),
                SkillContracts.Draft.class);
    }

    /**
     * 创建或条件更新 Draft 文本，不会修改资源或自动发布。
     *
     * @param workspaceId Workspace
     * @param request Draft 内容
     * @param options 幂等键与当前 Draft revision
     * @return 新 Draft
     */
    public SkillContracts.Draft saveContent(
            WorkspaceId workspaceId, SkillContracts.SaveContentRequest request, CommandOptions options) {
        return calls.command(workspaceId, "draft/save-content", request, options, SkillContracts.Draft.class);
    }

    /**
     * 把当前 Workspace 上传返回的 Attachment 加入 Draft。
     *
     * @param workspaceId Workspace
     * @param request Draft、资源标识与 Attachment 引用
     * @param options 幂等键与当前 Draft revision
     * @return 更新后的 Draft
     */
    public SkillContracts.Draft addResource(
            WorkspaceId workspaceId, SkillContracts.AddResourceRequest request, CommandOptions options) {
        return calls.command(workspaceId, "draft/resource/add", request, options, SkillContracts.Draft.class);
    }

    /**
     * 从 Draft 移除资源。
     *
     * @param workspaceId Workspace
     * @param request Draft 与资源标识
     * @param options 幂等键与当前 Draft revision
     * @return 更新后的 Draft
     */
    public SkillContracts.Draft removeResource(
            WorkspaceId workspaceId, SkillContracts.RemoveResourceRequest request, CommandOptions options) {
        return calls.command(workspaceId, "draft/resource/remove", request, options, SkillContracts.Draft.class);
    }

    /**
     * 从 Workspace-owned v5 Markdown 或 Bundle Attachment 导入 Draft。
     *
     * <p>不读取旧 Bundle；Bundle 内资源会分别写入 Core Attachment 并绑定当前 Workspace。
     *
     * @param workspaceId Workspace
     * @param request 已上传 Attachment
     * @param options 幂等键与目标 Draft 当前 revision；新建时为零
     * @return 导入格式和 Draft
     */
    public SkillTransferContracts.ImportResult importDraft(
            WorkspaceId workspaceId, SkillTransferContracts.ImportRequest request, CommandOptions options) {
        return calls.command(workspaceId, "skill/import", request, options, SkillTransferContracts.ImportResult.class);
    }

    /**
     * 写入 Draft tombstone。
     *
     * @param workspaceId Workspace
     * @param id Skill 标识
     * @param options 幂等键与当前 Draft revision
     * @return 删除确认
     */
    public DocumentContracts.Deleted tombstoneDraft(WorkspaceId workspaceId, String id, CommandOptions options) {
        return calls.command(
                workspaceId, "draft/tombstone", new SkillContracts.Key(id), options, DocumentContracts.Deleted.class);
    }

    /**
     * 从历史恢复 Draft 为新 revision。
     *
     * @param workspaceId Workspace
     * @param request 来源历史版本
     * @param options 幂等键与 tombstone/current revision
     * @return 恢复后的 Draft
     */
    public SkillContracts.Draft restoreDraft(
            WorkspaceId workspaceId, SkillContracts.DraftRestoreRequest request, CommandOptions options) {
        return calls.command(workspaceId, "draft/restore", request, options, SkillContracts.Draft.class);
    }

    /**
     * 读取 Draft 不可变历史。
     *
     * @param workspaceId Workspace
     * @param request 历史游标
     * @return 历史页
     */
    public SkillContracts.DraftHistoryPage draftHistory(
            WorkspaceId workspaceId, SkillContracts.HistoryRequest request) {
        return calls.query(workspaceId, "draft/history", request, SkillContracts.DraftHistoryPage.class);
    }

    /**
     * 显式发布精确 Draft revision；发布后默认禁用。
     *
     * @param workspaceId Workspace
     * @param request 精确 Draft 身份
     * @param options 幂等键与当前 Published revision
     * @return 新 Published 快照
     */
    public SkillContracts.PublishedSkill publish(
            WorkspaceId workspaceId, SkillContracts.PublishRequest request, CommandOptions options) {
        return calls.command(workspaceId, "publish", request, options, SkillContracts.PublishedSkill.class);
    }

    /**
     * 实时启用或禁用 Published Skill。
     *
     * @param workspaceId Workspace
     * @param request 目标状态
     * @param options 幂等键与当前 Published revision
     * @return 新 Published revision
     */
    public SkillContracts.PublishedSkill setEnabled(
            WorkspaceId workspaceId, SkillContracts.EnableRequest request, CommandOptions options) {
        return calls.command(workspaceId, "enable", request, options, SkillContracts.PublishedSkill.class);
    }

    /**
     * 把精确 Published revision 导出为 Workspace-owned Core Attachment。
     *
     * <p>包含资源的 Skill 必须选择 v5 Bundle；Markdown 只携带元数据和指令。
     *
     * @param workspaceId Workspace
     * @param request Skill 标识与导出格式
     * @param options 幂等键与 Published 当前 revision
     * @return 内容寻址导出引用
     */
    public SkillTransferContracts.ExportResult exportPublished(
            WorkspaceId workspaceId, SkillTransferContracts.ExportRequest request, CommandOptions options) {
        return calls.command(workspaceId, "skill/export", request, options, SkillTransferContracts.ExportResult.class);
    }

    /**
     * 管理端读取当前 Published 快照。
     *
     * @param workspaceId Workspace
     * @param id Skill 标识
     * @return Published 快照
     */
    public SkillContracts.PublishedSkill readPublished(WorkspaceId workspaceId, String id) {
        return calls.query(
                workspaceId, "published/read", new SkillContracts.Key(id), SkillContracts.PublishedSkill.class);
    }

    /**
     * 分页列出 Published 快照。
     *
     * @param workspaceId Workspace
     * @param afterKey 排他游标
     * @param limit 页大小
     * @return Published 页
     */
    public TypedDocumentPage<SkillContracts.PublishedSkill> listPublished(
            WorkspaceId workspaceId, String afterKey, int limit) {
        return calls.page(
                workspaceId,
                "published/list",
                new DocumentContracts.PageRequest(afterKey, limit),
                SkillContracts.PublishedSkill.class);
    }

    /**
     * 在 Turn 首次发现时建立服务端权威冻结目录并检索摘要。
     *
     * @param workspaceId Workspace
     * @param turnId Turn
     * @param request 检索条件
     * @return 冻结目录匹配项与摘要
     */
    public SkillContracts.SearchResult search(
            WorkspaceId workspaceId, TurnId turnId, SkillContracts.SearchRequest request) {
        return calls.query(
                workspaceId,
                Optional.empty(),
                Optional.of(turnId),
                "search",
                request,
                SkillContracts.SearchResult.class);
    }

    /**
     * 按冻结目录中的精确 revision/digest 读取 Skill。
     *
     * @param workspaceId Workspace
     * @param turnId Turn
     * @param request 精确 Published 身份
     * @return 仍启用且未变化的 Published Skill
     */
    public SkillContracts.PublishedSkill readFrozen(
            WorkspaceId workspaceId, TurnId turnId, SkillContracts.PublishedReadRequest request) {
        return calls.query(
                workspaceId,
                Optional.empty(),
                Optional.of(turnId),
                "published/read",
                request,
                SkillContracts.PublishedSkill.class);
    }

    /**
     * 提交生成内容提案，提案不能直接发布。
     *
     * @param workspaceId Workspace
     * @param request 提案内容
     * @param options 创建幂等键，expected revision 必须为 0
     * @return 待处理提案
     */
    public SkillContracts.Proposal propose(
            WorkspaceId workspaceId, SkillContracts.ProposeRequest request, CommandOptions options) {
        return calls.command(workspaceId, "proposal/submit", request, options, SkillContracts.Proposal.class);
    }

    /**
     * 读取提案。
     *
     * @param workspaceId Workspace
     * @param id 提案标识
     * @return 提案
     */
    public SkillContracts.Proposal readProposal(WorkspaceId workspaceId, String id) {
        return calls.query(workspaceId, "proposal/read", new SkillContracts.Key(id), SkillContracts.Proposal.class);
    }

    /**
     * 分页列出提案。
     *
     * @param workspaceId Workspace
     * @param afterKey 排他游标
     * @param limit 页大小
     * @return 提案页
     */
    public TypedDocumentPage<SkillContracts.Proposal> listProposals(
            WorkspaceId workspaceId, String afterKey, int limit) {
        return calls.page(
                workspaceId,
                "proposal/list",
                new DocumentContracts.PageRequest(afterKey, limit),
                SkillContracts.Proposal.class);
    }

    /**
     * 采纳提案为 Draft，不会发布或启用。
     *
     * @param workspaceId Workspace
     * @param id 提案标识
     * @param options 幂等键与提案 revision
     * @return 已采纳提案
     */
    public SkillContracts.Proposal adoptProposal(WorkspaceId workspaceId, String id, CommandOptions options) {
        return decideProposal(workspaceId, "proposal/adopt", id, options);
    }

    /**
     * 拒绝提案。
     *
     * @param workspaceId Workspace
     * @param id 提案标识
     * @param options 幂等键与提案 revision
     * @return 已拒绝提案
     */
    public SkillContracts.Proposal rejectProposal(WorkspaceId workspaceId, String id, CommandOptions options) {
        return decideProposal(workspaceId, "proposal/reject", id, options);
    }

    /**
     * 查询资源执行边界。
     *
     * @param workspaceId Workspace
     * @return 当前不可执行原因
     */
    public SkillContracts.ResourceExecutionAvailability resourceExecutionAvailability(WorkspaceId workspaceId) {
        return calls.query(
                workspaceId,
                "resource/execution/availability",
                Map.of(),
                SkillContracts.ResourceExecutionAvailability.class);
    }

    private SkillContracts.Proposal decideProposal(
            WorkspaceId workspaceId, String operation, String id, CommandOptions options) {
        return calls.command(
                workspaceId,
                operation,
                new SkillContracts.ProposalDecision(id),
                options,
                SkillContracts.Proposal.class);
    }
}
