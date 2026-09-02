package com.javaclaw.extension.spi;

import java.util.Objects;

/**
 * 内置扩展声明的一类可恢复 Job 执行器。
 *
 * @param jobType 扩展内稳定 Job 类型
 * @param executor 每次只推进一个工作单元的执行器
 */
public record ExtensionJobRegistration(String jobType, ExtensionJobExecutor executor) {
    /** 校验类型和执行器。 */
    public ExtensionJobRegistration {
        jobType = Objects.requireNonNull(jobType, "jobType").strip();
        if (!jobType.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,239}")) {
            throw new IllegalArgumentException("jobType contains unsupported characters");
        }
        Objects.requireNonNull(executor, "executor");
    }
}
