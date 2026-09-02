package com.javaclaw.api;

import java.time.Duration;
import java.util.Set;

/**
 * 受控进程能力。
 *
 * @param executables 允许的可执行文件规范名称；空集合表示禁止启动
 * @param allowPty 是否允许交互 PTY
 * @param maxRunTime 单次进程最大运行时间
 */
public record ProcessPermission(Set<String> executables, boolean allowPty, Duration maxRunTime) {
    /** 复制允许列表并校验时限。 */
    public ProcessPermission {
        executables = executables.stream()
                .map(value -> Preconditions.text(value, "executable"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (maxRunTime == null || maxRunTime.isZero() || maxRunTime.isNegative()) {
            throw new IllegalArgumentException("maxRunTime must be positive");
        }
    }
}
