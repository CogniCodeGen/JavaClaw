package com.javaclaw.core.api;

import java.util.List;
import java.util.Objects;

/**
 * Thread 及其已持久化 Turn/Item 的一致性读取结果，不包含瞬时 token delta。
 *
 * @param thread 所属 Thread 的非空状态快照
 * @param turns 按存储顺序返回的非空 Turn 列表，构造时复制
 * @param items 按存储顺序返回的非空 Item 列表，构造时复制
 */
public record ThreadSnapshot(AgentThread thread, List<AgentTurn> turns, List<StoredItem> items) {
    /** 复制 Turn/Item 列表，防止客户端侧投影修改持久快照。 */
    public ThreadSnapshot {
        thread = Objects.requireNonNull(thread, "thread");
        turns = List.copyOf(Objects.requireNonNull(turns, "turns"));
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }
}
