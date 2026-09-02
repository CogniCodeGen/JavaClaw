package com.javaclaw.extension.spi;

import java.util.Objects;

/**
 * Supervisor 交给扩展的单工作单元执行上下文。
 *
 * @param job 已记录意图后的 Job 快照
 * @param unit 已持久化且可安全重放的工作单元
 */
public record ExtensionJobExecution(ExtensionJob job, ExtensionJobUnit unit) {
    /** 校验 Job 与单元关联。 */
    public ExtensionJobExecution {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(unit, "unit");
        if (!job.id().equals(unit.jobId()) || job.activeUnitSequence().orElse(-1L) != unit.sequence()) {
            throw new IllegalArgumentException("unit is not the active job unit");
        }
        if (unit.state() != ExtensionJobUnitState.INTENT_RECORDED) {
            throw new IllegalArgumentException("only recorded intent may execute");
        }
    }
}
