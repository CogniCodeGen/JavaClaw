package com.javaclaw.agent.knowledge;

import java.time.Instant;
import java.util.List;

/** 记忆修订、来源和提案的持久边界；当前值与历史版本必须同事务保存。 */
public interface MemoryRepository {
    /** 读取当前记忆及固定状态、实体属性和来源；不存在时失败。 */
    MemoryDocument readMemory(String id);

    /** 返回不可变历史版本，删除当前条目不会抹去已有来源与版本。 */
    List<MemoryDocument> memoryHistory(String id);

    /** 显式用户编辑的写入口；乐观锁防止覆盖更新，来源引用必须属于同一工作区。 */
    MemoryDocument saveMemory(MemoryDraft draft, long expectedRevision, String idempotencyKey);

    /** 将选定历史版本复制为新版本；不回拨 revision，不改写历史。 */
    MemoryDocument restoreMemory(String id, long sourceRevision, long expectedRevision, String idempotencyKey);

    /** 创建可审阅提案；来源、冲突、固定内容及 Persona 决定是否可以低风险自动接受。 */
    MemoryProposal proposeMemory(
            MemoryDraft draft,
            long expectedTargetRevision,
            boolean permitLowRiskAutomatic,
            String reason,
            String idempotencyKey);

    /** 列出工作区的记忆提案，包括已接受与被拒绝记录。 */
    List<MemoryProposal> memoryProposals(String workspaceId);

    /** 显式接受或拒绝提案；接受时复核目标修订，过期提案不能覆盖用户新内容。 */
    MemoryProposal reviewMemoryProposal(String id, boolean accept, long expectedRevision, String idempotencyKey);

    /**
     * 记忆编辑或提取草稿；subject/attribute 用于同对象同属性冲突识别。
     *
     * @param id 当前条目标识，新建时可为空
     * @param workspaceId 所属工作区
     * @param kind FACT、EPISODE、ENTITY、RELATION、CORRECTION、PERSONA 或原有非空类别
     * @param subject 主体，手工一般记忆可为空
     * @param attribute 属性或关系谓词，可为空
     * @param content 正文，不能包含明文秘密
     * @param pinned 是否为用户固定条目
     * @param sourceItemIds 来源 Item，不接受助手建议作为事实来源
     */
    record MemoryDraft(
            String id,
            String workspaceId,
            String kind,
            String subject,
            String attribute,
            String content,
            boolean pinned,
            List<String> sourceItemIds) {
        /** 固定来源与字段；安全判断由服务端基于真实 Item 再核验。 */
        public MemoryDraft {
            workspaceId = com.javaclaw.core.api.ThreadId.required(workspaceId, "workspaceId");
            kind = com.javaclaw.core.api.ThreadId.required(kind, "kind");
            subject = subject == null ? "" : subject.strip();
            attribute = attribute == null ? "" : attribute.strip();
            content = com.javaclaw.core.api.ThreadId.required(content, "content");
            sourceItemIds = sourceItemIds == null ? List.of() : List.copyOf(sourceItemIds);
            if (content.length() > 1_000_000
                    || sourceItemIds.size() > 25
                    || subject.length() > 240
                    || attribute.length() > 240) {
                throw new IllegalArgumentException("memory draft exceeds bounded capacity");
            }
        }
    }

    /**
     * 当前值或历史版本，结构与来源在同一次写入中固定。
     *
     * @param draft 内容及元数据
     * @param revision 从 1 开始的不可变修订
     * @param createdAt 创建时间
     * @param updatedAt 本版本时间
     */
    record MemoryDocument(MemoryDraft draft, long revision, Instant createdAt, Instant updatedAt) {}

    /**
     * 待确认或已处理的记忆提案；source 证据与修订冲突都在接受时重新检查。
     *
     * @param id 提案标识
     * @param draft 建议内容
     * @param expectedTargetRevision 目标版本；新建为 0
     * @param state PENDING、ACCEPTED 或 REJECTED
     * @param reason 提案及风险说明
     * @param revision 提案乐观锁修订
     * @param createdAt 创建时间
     * @param updatedAt 最近状态变化时间
     */
    record MemoryProposal(
            String id,
            MemoryDraft draft,
            long expectedTargetRevision,
            String state,
            String reason,
            long revision,
            Instant createdAt,
            Instant updatedAt) {}
}
