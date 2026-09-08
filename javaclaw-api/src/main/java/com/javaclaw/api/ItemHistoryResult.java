package com.javaclaw.api;

import java.util.List;

/**
 * 倒序查询后恢复为展示升序的 Item 页。
 *
 * @param items 本页有界 Item 摘要，按 Thread sequence 升序，不可空
 * @param latestSequence 同一读取事务观察到的 Thread 末端水位，空 Thread 为 0
 * @param hasEarlier 当前页之前是否还有 Item
 */
public record ItemHistoryResult(List<ItemHistoryEntry> items, long latestSequence, boolean hasEarlier) {
    /** 冻结列表并校验水位。 */
    public ItemHistoryResult {
        items = List.copyOf(items);
        if (latestSequence < 0) {
            throw new IllegalArgumentException("latestSequence must not be negative");
        }
    }
}
