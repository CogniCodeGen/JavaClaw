package com.javaclaw.api;

import java.time.Duration;
import java.util.Objects;

/**
 * 批处理 Sandbox 命令的有界结果。
 *
 * @param exitCode 子进程退出码；平台终止时为 -1
 * @param standardOutput 已截断到资源上限的输出
 * @param standardError 已截断到资源上限的错误输出
 * @param timedOut 是否因超时终止
 * @param cancelled 是否因 Turn 取消终止
 * @param elapsed 实际运行时间
 */
public record SandboxResult(
        int exitCode,
        byte[] standardOutput,
        byte[] standardError,
        boolean timedOut,
        boolean cancelled,
        Duration elapsed) {
    /** 复制输出并校验持续时间。 */
    public SandboxResult {
        standardOutput =
                Objects.requireNonNull(standardOutput, "standardOutput").clone();
        standardError = Objects.requireNonNull(standardError, "standardError").clone();
        if (elapsed == null || elapsed.isNegative()) {
            throw new IllegalArgumentException("elapsed must not be negative");
        }
    }

    @Override
    public byte[] standardOutput() {
        return standardOutput.clone();
    }

    @Override
    public byte[] standardError() {
        return standardError.clone();
    }
}
