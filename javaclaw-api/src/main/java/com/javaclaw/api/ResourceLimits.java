package com.javaclaw.api;

/**
 * Sandbox 与 Worker 的有限资源上限。
 *
 * @param memoryBytes 最大内存字节数
 * @param outputBytes 最大标准输出与结果字节数
 * @param childProcesses 最大子进程数
 * @param openFiles 最大打开文件数
 */
public record ResourceLimits(long memoryBytes, long outputBytes, int childProcesses, int openFiles) {
    /** 校验资源上限均为正数。 */
    public ResourceLimits {
        memoryBytes = Preconditions.positive(memoryBytes, "memoryBytes");
        outputBytes = Preconditions.positive(outputBytes, "outputBytes");
        if (childProcesses < 1 || openFiles < 1) {
            throw new IllegalArgumentException("process and file limits must be positive");
        }
    }
}
