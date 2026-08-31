package com.javaclaw.sdk.model;

import java.time.Instant;
import java.util.List;

/**
 * Memory v1 增量详情 DTO，不包含数据库或运行时类型。
 *
 * @param id 条目标识，新建草稿可为空
 * @param workspaceId 工作区标识
 * @param kind 记忆种类
 * @param subject 主体
 * @param attribute 属性或关系
 * @param content 正文
 * @param pinned 用户是否固定
 * @param sourceItemIds 来源引用
 * @param revision 持久修订，草稿为 0
 * @param createdAt 创建时间，草稿可为空
 * @param updatedAt 版本时间，草稿可为空
 */
public record MemoryDetailInfo(
        String id,
        String workspaceId,
        String kind,
        String subject,
        String attribute,
        String content,
        boolean pinned,
        List<String> sourceItemIds,
        long revision,
        Instant createdAt,
        Instant updatedAt) {
    /** 防御性复制来源，保留空集合与新建草稿语义。 */
    public MemoryDetailInfo {
        sourceItemIds = sourceItemIds == null ? List.of() : List.copyOf(sourceItemIds);
    }
}
