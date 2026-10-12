package com.javaclaw.loop;

import com.javaclaw.framework.api.TaskOutcome;
import com.javaclaw.framework.api.TaskResult;
import com.javaclaw.loop.model.LoopSpec;
import com.javaclaw.loop.model.StopReason;

import java.util.List;

/** Maps deterministic Loop checks to an explicit, conservative task result. */
final class LoopTaskResults {
    private LoopTaskResults() { }

    static TaskResult stopped(LoopSpec spec, int satisfied, StopReason reason, List<String> missing) {
        TaskOutcome outcome = satisfied > 0 ? TaskOutcome.PARTIAL : TaskOutcome.BLOCKED;
        List<String> unmet = missing.isEmpty()
                ? spec.criteria().stream().map(Object::toString).toList() : missing;
        return new TaskResult(outcome, unmet,
                reason == null ? "循环在达到目标前停止" : reason.description(), List.of());
    }
}
