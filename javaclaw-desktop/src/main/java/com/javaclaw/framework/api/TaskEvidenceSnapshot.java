package com.javaclaw.framework.api;

import java.util.List;
import java.util.Objects;

/** 宿主重新核验后的任务证据快照；持久终态或模型声明本身不能生成正向结果。 */
public record TaskEvidenceSnapshot(RunId runId, TaskContractV3 contract,
                                   List<RunEventEnvelope> events, TaskResult result) {
    public TaskEvidenceSnapshot {
        runId = Objects.requireNonNull(runId, "runId");
        events = List.copyOf(events);
        result = Objects.requireNonNull(result, "result");
    }
}
