package com.javaclaw.plugin.api.exec;

import java.util.Objects;

/**
 * 插件任务的只读上下文。
 *
 * <p>上下文只在任务调用期间有效，不得缓存到任务生命周期之外。长任务应同时响应线程中断，
 * 并定期检查 {@link #cancellation()}；宿主卸载插件时会同时发出两种取消信号。</p>
 *
 * @param taskId       宿主生成的稳定任务 ID
 * @param cancellation 协作取消信号
 */
public record TaskContext(String taskId, Cancellation cancellation) {

    public TaskContext {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        cancellation = Objects.requireNonNull(cancellation, "cancellation");
    }
}
