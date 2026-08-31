package com.javaclaw.sandbox.api;

import java.time.Duration;
import java.util.Objects;

/**
 * Bounded output returned by a sandboxed command.
 *
 * @param exitCode 子进程退出码
 * @param stdout 标准输出；null 归一为空字符串
 * @param stderr 标准错误；null 归一为空字符串
 * @param timedOut 执行是否因超时结束
 * @param truncated 输出是否因大小上限而截断
 * @param duration 实际执行时长，非空
 * @param backend 实施约束的沙箱后端名称，非空
 */
public record SandboxResult(
        int exitCode,
        String stdout,
        String stderr,
        boolean timedOut,
        boolean truncated,
        Duration duration,
        String backend) {
    /** 归一缺失输出并要求时长与后端信息存在，保留超时/截断信息供审计。 */
    public SandboxResult {
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
        duration = Objects.requireNonNull(duration, "duration");
        backend = Objects.requireNonNull(backend, "backend");
    }
}
