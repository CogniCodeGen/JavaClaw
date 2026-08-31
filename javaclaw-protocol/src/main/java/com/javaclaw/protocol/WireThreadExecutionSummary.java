package com.javaclaw.protocol;

import java.util.List;

/**
 * Thread 的类型化执行摘要；turns 按持久顺序返回并在构造时复制。
 *
 * @param threadId Thread 标识
 * @param turns Turn 安全摘要
 */
public record WireThreadExecutionSummary(String threadId, List<WireTurnExecutionSummary> turns) {
    /** 固定 Turn 摘要集合，避免序列化期间被调用方修改。 */
    public WireThreadExecutionSummary {
        turns = turns == null ? List.of() : List.copyOf(turns);
    }
}
