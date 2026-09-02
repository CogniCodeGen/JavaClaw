package com.javaclaw.desktop.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.javaclaw.api.ItemEnvelope;

/**
 * 当前 Thread 的 ItemEnvelope 投影与分页游标。
 *
 * @param items 按 sequence 严格递增的 Item
 * @param nextSequence 已接收的最大 sequence；空列表时为零
 * @param following 是否在新 Item 到达后自动滚动到底部
 */
public record TranscriptState(List<ItemEnvelope> items, long nextSequence, boolean following) {
    /** 复制 Item 并校验严格递增 sequence。 */
    public TranscriptState {
        items = List.copyOf(items);
        if (nextSequence < 0) {
            throw new IllegalArgumentException("nextSequence must not be negative");
        }
        long previous = 0;
        for (ItemEnvelope item : items) {
            Objects.requireNonNull(item, "item");
            if (item.sequence() <= previous || item.sequence() > nextSequence) {
                throw new IllegalArgumentException("transcript sequence must be strictly increasing");
            }
            previous = item.sequence();
        }
    }

    /** @return 空 Transcript */
    public static TranscriptState empty() {
        return new TranscriptState(List.of(), 0, true);
    }

    /**
     * 追加下一页；重复或倒序页会被拒绝。
     *
     * @param page 新 Item
     * @param cursor 服务端返回游标
     * @return 合并快照
     */
    public TranscriptState append(List<ItemEnvelope> page, long cursor) {
        List<ItemEnvelope> copy = List.copyOf(page);
        if (cursor < nextSequence || (!copy.isEmpty() && copy.getFirst().sequence() <= nextSequence)) {
            throw new IllegalArgumentException("transcript page is stale or overlaps current items");
        }
        ArrayList<ItemEnvelope> merged = new ArrayList<>(items.size() + copy.size());
        merged.addAll(items);
        merged.addAll(copy);
        return new TranscriptState(merged, cursor, following);
    }
}
