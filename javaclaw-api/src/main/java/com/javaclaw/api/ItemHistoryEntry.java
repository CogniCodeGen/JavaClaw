package com.javaclaw.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 历史窗口的有界展示投影，不能当作完整 ItemEnvelope 使用。
 *
 * @param id 权威来源 Item ID
 * @param turnId 来源 Turn
 * @param sequence Thread 序号
 * @param kind 展示类别
 * @param role 消息角色，非消息为空
 * @param summary 最多 4096 个 UTF-16 单位的摘要
 * @param bodyReference 消息全文引用，非消息为空
 * @param truncated 原文是否超出摘要
 * @param createdAt 来源时间
 * @param attachments 最多 32 个来源附件，完整附件关系仍属于原 Item
 * @param fileReferences 最多 32 个已识别文件选择器，预览时必须重新验证来源与权限
 */
public record ItemHistoryEntry(
        ItemId id,
        TurnId turnId,
        long sequence,
        String kind,
        Optional<MessageRole> role,
        String summary,
        Optional<DocumentReference> bodyReference,
        boolean truncated,
        Instant createdAt,
        List<AttachmentRef> attachments,
        List<DocumentReference> fileReferences) {
    /** 冻结投影中的列表，限制展示字段大小。 */
    public ItemHistoryEntry {
        attachments = List.copyOf(attachments);
        fileReferences = List.copyOf(fileReferences);
        if (summary.length() > 4096 || attachments.size() > 32 || fileReferences.size() > 32) {
            throw new IllegalArgumentException("history projection exceeds display limits");
        }
    }
}
