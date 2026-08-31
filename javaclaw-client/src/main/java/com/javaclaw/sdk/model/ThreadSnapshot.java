package com.javaclaw.sdk.model;

import java.util.List;

/**
 * Thread、Turn 和 Item 的持久快照；瞬时生成内容单独通过 liveItems 恢复。
 *
 * @param thread Thread 状态快照
 * @param turns Turn 列表；构造时复制
 * @param items Item 列表；构造时复制
 */
public record ThreadSnapshot(ThreadInfo thread, List<TurnInfo> turns, List<ItemInfo> items) {
    /** 复制 Turn/Item 列表，固定一次读取结果的集合边界。 */
    public ThreadSnapshot {
        turns = turns == null ? List.of() : List.copyOf(turns);
        items = items == null ? List.of() : List.copyOf(items);
    }
}
