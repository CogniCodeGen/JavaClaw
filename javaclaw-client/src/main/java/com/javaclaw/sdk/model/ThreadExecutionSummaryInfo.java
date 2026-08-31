package com.javaclaw.sdk.model;

import java.util.List;

/**
 * Thread 的类型化执行摘要。
 *
 * @param threadId Thread 标识
 * @param turns 按持久顺序排列的 Turn 元数据；构造时复制
 */
public record ThreadExecutionSummaryInfo(String threadId, List<TurnExecutionSummaryInfo> turns) {
    /** 固定 Turn 摘要集合。 */
    public ThreadExecutionSummaryInfo {
        turns = turns == null ? List.of() : List.copyOf(turns);
    }
}
