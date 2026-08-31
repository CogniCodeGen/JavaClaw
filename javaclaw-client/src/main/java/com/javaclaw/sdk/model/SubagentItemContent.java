package com.javaclaw.sdk.model;

/**
 * 独立子 Thread 的调用与回收结果。
 *
 * @param childThreadId 子 Thread 标识
 * @param task 独立子任务
 * @param result 用户可读执行结果
 * @param document 完整原始 JSON，保留未知扩展
 */
public record SubagentItemContent(String childThreadId, String task, String result, JsonDocument document)
        implements ItemContent {
    @Override
    public String kind() {
        return "subagentCall";
    }
}
