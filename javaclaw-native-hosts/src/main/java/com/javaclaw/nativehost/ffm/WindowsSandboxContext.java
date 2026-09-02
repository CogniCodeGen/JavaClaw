package com.javaclaw.nativehost.ffm;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Windows Sandbox 的进程上下文。
 *
 * @param workingDirectory 已存在的工作目录
 * @param environment 唯一允许传给目标进程的环境变量；空 Map 表示空环境
 */
public record WindowsSandboxContext(Path workingDirectory, Map<String, String> environment) {
    /** 规范化目录并复制环境，拒绝 Windows 环境块无法表达的名称和值。 */
    public WindowsSandboxContext {
        workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory")
                .toAbsolutePath()
                .normalize();
        environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        environment.forEach(WindowsSandboxContext::validateEntry);
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (environment.keySet().stream().anyMatch(name -> !names.add(name))) {
            throw new IllegalArgumentException("Windows sandbox environment names must be case-insensitively unique");
        }
    }

    private static void validateEntry(String name, String value) {
        if (name == null
                || name.isBlank()
                || name.indexOf('=') >= 0
                || name.indexOf('\0') >= 0
                || value == null
                || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Windows sandbox environment entry is invalid");
        }
    }
}
