package com.javaclaw.client.extension;

import java.util.Map;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.MemoryContracts;
import com.javaclaw.client.CommandOptions;

/** Memory 当前值、历史、学习设置与 Proposal 的强类型 SDK facade。 */
public final class MemoryClient {
    private final BuiltinClientCalls calls;

    /**
     * 创建 Memory facade。
     *
     * @param extensions 通用扩展客户端
     */
    public MemoryClient(ExtensionClient extensions) {
        calls = new BuiltinClientCalls(extensions, BuiltinExtensionIds.MEMORY);
    }

    /**
     * 读取当前记忆。
     *
     * @param workspaceId Workspace
     * @param id 记忆标识
     * @return 当前记忆
     */
    public MemoryContracts.Memory read(WorkspaceId workspaceId, String id) {
        return calls.query(workspaceId, "read", new MemoryContracts.Key(id), MemoryContracts.Memory.class);
    }

    /**
     * 分页列出当前记忆。
     *
     * @param workspaceId Workspace
     * @param afterKey 排他游标
     * @param limit 页大小
     * @return 强类型页面
     */
    public TypedDocumentPage<MemoryContracts.Memory> list(WorkspaceId workspaceId, String afterKey, int limit) {
        return calls.page(
                workspaceId, "list", new DocumentContracts.PageRequest(afterKey, limit), MemoryContracts.Memory.class);
    }

    /**
     * 显式创建一条记忆。
     *
     * @param workspaceId Workspace
     * @param request 用户确认的内容
     * @param options 创建幂等键，expected revision 必须为 0
     * @return 新记忆
     */
    public MemoryContracts.Memory create(
            WorkspaceId workspaceId, MemoryContracts.CreateRequest request, CommandOptions options) {
        return calls.command(workspaceId, "create", request, options, MemoryContracts.Memory.class);
    }

    /**
     * 条件纠错或编辑记忆。
     *
     * @param workspaceId Workspace
     * @param request 新内容
     * @param options 幂等键与当前 revision
     * @return 新 revision
     */
    public MemoryContracts.Memory update(
            WorkspaceId workspaceId, MemoryContracts.UpdateRequest request, CommandOptions options) {
        return calls.command(workspaceId, "update", request, options, MemoryContracts.Memory.class);
    }

    /**
     * 修改固定状态。
     *
     * @param workspaceId Workspace
     * @param request 固定状态
     * @param options 幂等键与当前 revision
     * @return 新 revision
     */
    public MemoryContracts.Memory pin(
            WorkspaceId workspaceId, MemoryContracts.PinRequest request, CommandOptions options) {
        return calls.command(workspaceId, "pin", request, options, MemoryContracts.Memory.class);
    }

    /**
     * 写入 tombstone，不删除历史。
     *
     * @param workspaceId Workspace
     * @param id 记忆标识
     * @param options 幂等键与当前 revision
     * @return 删除确认
     */
    public DocumentContracts.Deleted tombstone(WorkspaceId workspaceId, String id, CommandOptions options) {
        return calls.command(
                workspaceId, "tombstone", new MemoryContracts.Key(id), options, DocumentContracts.Deleted.class);
    }

    /**
     * 从历史内容恢复为新 revision。
     *
     * @param workspaceId Workspace
     * @param request 来源版本
     * @param options 幂等键与 tombstone/current revision
     * @return 恢复后的记忆
     */
    public MemoryContracts.Memory restore(
            WorkspaceId workspaceId, MemoryContracts.RestoreRequest request, CommandOptions options) {
        return calls.command(workspaceId, "restore", request, options, MemoryContracts.Memory.class);
    }

    /**
     * 读取不可变历史。
     *
     * @param workspaceId Workspace
     * @param request 历史游标
     * @return 历史页
     */
    public MemoryContracts.HistoryPage history(WorkspaceId workspaceId, MemoryContracts.HistoryRequest request) {
        return calls.query(workspaceId, "history", request, MemoryContracts.HistoryPage.class);
    }

    /**
     * 检索已确认的当前记忆。
     *
     * @param workspaceId Workspace
     * @param request 检索条件
     * @return 匹配结果
     */
    public MemoryContracts.SearchResult search(WorkspaceId workspaceId, MemoryContracts.SearchRequest request) {
        return calls.query(workspaceId, "search", request, MemoryContracts.SearchResult.class);
    }

    /**
     * 读取 Workspace 学习策略。
     *
     * @param workspaceId Workspace
     * @return 当前策略
     */
    public MemoryContracts.LearningSettings learningSettings(WorkspaceId workspaceId) {
        return calls.query(workspaceId, "settings/read", Map.of(), MemoryContracts.LearningSettings.class);
    }

    /**
     * 条件更新 Workspace 学习策略。
     *
     * @param workspaceId Workspace
     * @param update 新策略
     * @param options 幂等键与设置 revision
     * @return 新设置
     */
    public MemoryContracts.LearningSettings updateLearningSettings(
            WorkspaceId workspaceId, MemoryContracts.LearningSettingsUpdate update, CommandOptions options) {
        return calls.command(workspaceId, "settings/update", update, options, MemoryContracts.LearningSettings.class);
    }

    /**
     * 提交可审计的学习候选。
     *
     * @param workspaceId Workspace
     * @param request 候选与来源
     * @param options 创建幂等键，expected revision 必须为 0
     * @return 策略判定结果
     */
    public MemoryContracts.LearningResult propose(
            WorkspaceId workspaceId, MemoryContracts.LearningProposalRequest request, CommandOptions options) {
        return calls.command(workspaceId, "proposal/submit", request, options, MemoryContracts.LearningResult.class);
    }

    /**
     * 读取学习提案。
     *
     * @param workspaceId Workspace
     * @param id 提案标识
     * @return 提案
     */
    public MemoryContracts.Proposal readProposal(WorkspaceId workspaceId, String id) {
        return calls.query(workspaceId, "proposal/read", new MemoryContracts.Key(id), MemoryContracts.Proposal.class);
    }

    /**
     * 分页列出学习提案。
     *
     * @param workspaceId Workspace
     * @param afterKey 排他游标
     * @param limit 页大小
     * @return 提案页
     */
    public TypedDocumentPage<MemoryContracts.Proposal> listProposals(
            WorkspaceId workspaceId, String afterKey, int limit) {
        return calls.page(
                workspaceId,
                "proposal/list",
                new DocumentContracts.PageRequest(afterKey, limit),
                MemoryContracts.Proposal.class);
    }

    /**
     * 人工接受待处理提案。
     *
     * @param workspaceId Workspace
     * @param id 提案标识
     * @param options 幂等键与提案 revision
     * @return 已更新提案
     */
    public MemoryContracts.Proposal acceptProposal(WorkspaceId workspaceId, String id, CommandOptions options) {
        return decideProposal(workspaceId, "proposal/accept", id, options);
    }

    /**
     * 人工拒绝待处理提案。
     *
     * @param workspaceId Workspace
     * @param id 提案标识
     * @param options 幂等键与提案 revision
     * @return 已更新提案
     */
    public MemoryContracts.Proposal rejectProposal(WorkspaceId workspaceId, String id, CommandOptions options) {
        return decideProposal(workspaceId, "proposal/reject", id, options);
    }

    /**
     * 读取 Workspace 记忆统计。
     *
     * @param workspaceId Workspace
     * @return 当前统计
     */
    public MemoryContracts.Stats stats(WorkspaceId workspaceId) {
        return calls.query(workspaceId, "stats", Map.of(), MemoryContracts.Stats.class);
    }

    private MemoryContracts.Proposal decideProposal(
            WorkspaceId workspaceId, String operation, String id, CommandOptions options) {
        return calls.command(
                workspaceId,
                operation,
                new MemoryContracts.ProposalDecision(id),
                options,
                MemoryContracts.Proposal.class);
    }
}
