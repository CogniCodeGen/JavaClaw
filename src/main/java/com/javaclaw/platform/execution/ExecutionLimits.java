package com.javaclaw.platform.execution;

/** 全局执行资源配额。 */
public record ExecutionLimits(
        int ioConcurrency,
        int cpuThreads,
        int cpuQueueCapacity,
        int browserConcurrency,
        int processConcurrency) {

    public ExecutionLimits {
        requirePositive(ioConcurrency, "ioConcurrency");
        requirePositive(cpuThreads, "cpuThreads");
        requirePositive(cpuQueueCapacity, "cpuQueueCapacity");
        requirePositive(browserConcurrency, "browserConcurrency");
        requirePositive(processConcurrency, "processConcurrency");
    }

    public static ExecutionLimits defaults() {
        int cpu = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        return new ExecutionLimits(256, cpu, 256, 4, 8);
    }

    private static void requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " 必须大于 0");
        }
    }
}
