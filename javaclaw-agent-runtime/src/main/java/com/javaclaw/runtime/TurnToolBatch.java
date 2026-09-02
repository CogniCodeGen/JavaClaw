package com.javaclaw.runtime;

import java.util.List;

/**
 * 一次模型结果中已持久化的工具调用批次。
 *
 * @param calls 按模型返回顺序排列的调用
 */
public record TurnToolBatch(List<ModelToolCall> calls) {
    /** 复制调用列表并拒绝空元素。 */
    public TurnToolBatch {
        calls = List.copyOf(calls);
        if (calls.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("tool batch must not contain null");
        }
    }

    /**
     * 返回空批次。
     *
     * @return 空批次
     */
    public static TurnToolBatch empty() {
        return new TurnToolBatch(List.of());
    }
}
