package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.TaskContractV1;
import com.javaclaw.framework.api.TaskContractV2;
import com.javaclaw.framework.api.TaskContractV3;
import com.javaclaw.framework.api.TaskResult;

import java.util.List;
import java.util.Optional;

/** Decodes only durable host-authored task contract and outcome events. */
final class TaskResultEventReader {
    private TaskResultEventReader() { }

    /** Uses the last durable contract or revision, so restart never replans an existing run. */
    static Optional<TaskContractV1> latestContract(
            List<RunEventEnvelope> events, ObjectMapper json) {
        TaskContractV1 latest = null;
        for (RunEventEnvelope event : events) {
            if (!(event.type().equals("core.task.contract")
                    || event.type().equals("core.task.contract_revised"))
                    || event.schemaVersion() != 1 || !event.producer().equals("framework.core")) continue;
            try { latest = json.treeToValue(event.payload(), TaskContractV1.class); }
            catch (Exception ignored) { /* malformed historical event grants no positive result */ }
        }
        return Optional.ofNullable(latest);
    }

    static Optional<TaskContractV2> latestContractV2(
            List<RunEventEnvelope> events, ObjectMapper json) {
        TaskContractV2 latest = null;
        for (RunEventEnvelope event : events) {
            if (!(event.type().equals("core.task.contract")
                    || event.type().equals("core.task.contract_revised"))
                    || event.schemaVersion() != 2 || !event.producer().equals("framework.core")) continue;
            try { latest = json.treeToValue(event.payload(), TaskContractV2.class); }
            catch (Exception ignored) { /* malformed historical event grants no positive result */ }
        }
        return Optional.ofNullable(latest);
    }

    static Optional<TaskContractV3> latestContractV3(
            List<RunEventEnvelope> events, ObjectMapper json) {
        TaskContractV3 latest = null;
        for (RunEventEnvelope event : events) {
            if (!(event.type().equals("core.task.contract")
                    || event.type().equals("core.task.contract_revised"))
                    || event.schemaVersion() != 3 || !event.producer().equals("framework.core")) continue;
            try { latest = json.treeToValue(event.payload(), TaskContractV3.class); }
            catch (Exception ignored) { /* malformed historical event grants no positive result */ }
        }
        return Optional.ofNullable(latest);
    }

    static Optional<TaskResult> latestOutcome(
            List<RunEventEnvelope> events, ObjectMapper json) {
        TaskResult latest = null;
        for (RunEventEnvelope event : events) {
            if (!event.type().equals("core.task.outcome")
                    || (event.schemaVersion() != 1 && event.schemaVersion() != 2
                            && event.schemaVersion() != 3)
                    || !event.producer().equals("framework.core")) continue;
            try { latest = json.treeToValue(event.payload(), TaskResult.class); }
            catch (Exception ignored) { /* malformed historical event grants no positive result */ }
        }
        return Optional.ofNullable(latest);
    }

}
