package com.javaclaw.framework.springai;

import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.core.TaskEvidenceCollector;
import com.javaclaw.framework.spi.RunStore;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Model-visible references are copied only from durable host receipts. */
final class HarnessDecisionEvidence {
    private static final Set<String> TRUSTED_STATUSES =
            Set.of("ACCEPTED", "OBSERVED", "VERIFIED");

    private HarnessDecisionEvidence() { }

    static List<String> trustedRefs(RunStore runs, RunId runId) {
        Objects.requireNonNull(runs, "runs");
        Objects.requireNonNull(runId, "runId");
        return refs(TaskEvidenceCollector.collect(runs, runId));
    }

    /** A tool response must not acquire evidence from another invocation or Run. */
    static List<String> currentInvocationRefs(
            RunStore runs, RunId runId, String invocationId) {
        Objects.requireNonNull(runs, "runs");
        Objects.requireNonNull(runId, "runId");
        if (invocationId == null || invocationId.isBlank()) return List.of();
        return refs(runs.eventsAfter(runId, 0).stream()
                .filter(event -> runId.value().equals(event.runId())
                        && invocationId.equals(
                                event.payload().path("invocationId").asText("")))
                .toList());
    }

    private static List<String> refs(List<RunEventEnvelope> events) {
        var refs = new LinkedHashSet<String>();
        for (var event : events) {
            if (!event.type().equals("core.tool.receipt") || event.schemaVersion() != 1
                    || !event.producer().equals("framework.core")) continue;
            var payload = event.payload();
            if (!TRUSTED_STATUSES.contains(payload.path("status").asText(""))) continue;
            var value = payload.path("evidenceRef");
            if (value.isTextual() && !value.asText().isBlank()
                    && value.asText().length() <= 256) refs.add(value.asText());
        }
        return List.copyOf(refs);
    }
}
