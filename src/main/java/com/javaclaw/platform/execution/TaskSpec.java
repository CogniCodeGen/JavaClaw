package com.javaclaw.platform.execution;

import java.time.Duration;
import java.util.Objects;

/**
 * 一次任务的不可变调度要求。
 *
 * @param name             可诊断的任务名称
 * @param workload         资源类型
 * @param timeout          从提交时开始计算的超时；零表示不自动超时
 * @param serializationKey 浏览器任务的串行键；同键任务永不并发，其他类型忽略
 */
public record TaskSpec(
        String name,
        Workload workload,
        Duration timeout,
        String serializationKey) {

    public TaskSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("任务名称不能为空");
        }
        workload = Objects.requireNonNull(workload, "workload");
        timeout = timeout == null ? Duration.ZERO : timeout;
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("任务超时不能为负数");
        }
        serializationKey = serializationKey == null || serializationKey.isBlank()
                ? null : serializationKey;
    }

    public static TaskSpec io(String name) {
        return new TaskSpec(name, Workload.IO, Duration.ZERO, null);
    }

    public static TaskSpec cpu(String name) {
        return new TaskSpec(name, Workload.CPU, Duration.ZERO, null);
    }

    public static TaskSpec browser(String name, String pageOrContextId) {
        return new TaskSpec(name, Workload.BROWSER, Duration.ZERO, pageOrContextId);
    }

    public static TaskSpec process(String name) {
        return new TaskSpec(name, Workload.PROCESS, Duration.ZERO, null);
    }

    public TaskSpec withTimeout(Duration value) {
        return new TaskSpec(name, workload, value, serializationKey);
    }
}
