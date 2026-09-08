package com.javaclaw.extension.spi;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.javaclaw.api.ItemId;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;

/** 按成功完成提交顺序分页读取公开文本证据；不暴露 Core 存储或领域学习策略。 */
public interface ConversationEvidencePort {
    /** @return 未装配证据能力时明确拒绝的端口；不得降级为任意存储读取 */
    static ConversationEvidencePort unavailable() {
        return new ConversationEvidencePort() {
            @Override
            public long committedUpperBound(WorkspaceId workspaceId) {
                throw new IllegalStateException("Conversation evidence port is unavailable");
            }

            @Override
            public Page scan(
                    WorkspaceId workspaceId, Cursor after, long upperSequence, Instant completedAfter, int limit) {
                throw new IllegalStateException("Conversation evidence port is unavailable");
            }
        };
    }

    /**
     * 读取已提交的完成序号上界；事务内尚未完成的 Turn 不得占用可见序号。
     *
     * @param workspaceId 当前授权 Workspace
     * @return 已提交上界，空工作空间为零
     */
    long committedUpperBound(WorkspaceId workspaceId);

    /**
     * 在冻结上界内读取有限文本。实现排除未完成、隐藏、工具参数与学习生成 Turn。
     *
     * @param workspaceId 当前授权 Workspace
     * @param after 已消费游标，不含该位置
     * @param upperSequence 冻结完成序号上界
     * @param completedAfter 完成时间下界，包含该时间
     * @param limit 最多返回的 Item 数，范围 1 到 200
     * @return 按游标递增的证据页
     */
    Page scan(WorkspaceId workspaceId, Cursor after, long upperSequence, Instant completedAfter, int limit);

    /**
     * 完成提交顺序和该 Turn 的 Item 顺序。
     *
     * @param completionSequence 完成序号，初始为零
     * @param itemSequence Item 序号，初始为零
     */
    record Cursor(long completionSequence, long itemSequence) implements Comparable<Cursor> {
        /** 校验非负序号。 */
        public Cursor {
            if (completionSequence < 0 || itemSequence < 0) {
                throw new IllegalArgumentException("evidence cursor must not be negative");
            }
        }

        @Override
        public int compareTo(Cursor other) {
            int completion = Long.compare(completionSequence, other.completionSequence);
            return completion != 0 ? completion : Long.compare(itemSequence, other.itemSequence);
        }
    }

    /** 证据的权威来源类别；助手自述不能自动作为事实接受。 */
    enum SourceKind {
        USER_TEXT,
        ASSISTANT_TEXT,
        PLATFORM_OBSERVATION
    }

    /**
     * 有界公开证据；超长条目返回空文本及完整哈希，调用方必须显式记录跳过。
     *
     * @param cursor 稳定位置
     * @param threadId 来源 Thread
     * @param turnId 来源 Turn
     * @param itemId 来源 Item
     * @param sourceKind 来源类别
     * @param text 完整文本或超长条目的空字符串
     * @param sha256 完整原文摘要
     * @param oversized 是否因单条过长省略文本
     */
    record Evidence(
            Cursor cursor,
            ThreadId threadId,
            TurnId turnId,
            ItemId itemId,
            SourceKind sourceKind,
            String text,
            String sha256,
            boolean oversized) {
        /** 校验证据身份与有界正文。 */
        public Evidence {
            Objects.requireNonNull(cursor, "cursor");
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(turnId, "turnId");
            Objects.requireNonNull(itemId, "itemId");
            Objects.requireNonNull(sourceKind, "sourceKind");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(sha256, "sha256");
            if (!sha256.matches("[0-9a-f]{64}") || text.length() > 48000 || (oversized && !text.isEmpty())) {
                throw new IllegalArgumentException("evidence text or digest is invalid");
            }
        }
    }

    /**
     * 一页证据；next 表示已扫描位置，过滤后为空也可前进；没有可扫描条目时才保持原游标。 hasMore 仅描述冻结上界内余量。
     *
     * @param evidence 最多 200 条公开文本
     * @param next 下一次读取位置
     * @param hasMore 冻结区间是否仍有证据
     */
    record Page(List<Evidence> evidence, Cursor next, boolean hasMore) {
        /** 冻结证据页并校验容量。 */
        public Page {
            evidence = List.copyOf(evidence);
            Objects.requireNonNull(next, "next");
            if (evidence.size() > 200) {
                throw new IllegalArgumentException("evidence page exceeds 200 items");
            }
        }
    }
}
