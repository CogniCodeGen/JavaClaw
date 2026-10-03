package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.TaskResult;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Checks whether trusted post-repair evidence represents actual task progress. */
final class TaskRepairProgress {
    private TaskRepairProgress() { }

    /** A failed call or a fresh ID for the same view does not justify another model repair. */
    static boolean meaningfulRepairProgress(List<RunEventEnvelope> events,
            List<RunEventEnvelope> evidence, RunEventEnvelope repair, TaskResult current) {
        // TaskResult descriptions are display text, and may be duplicated or renamed.
        // Progress requires a fresh trusted receipt below; the Host evaluator still
        // makes the final criterion-by-ID acceptance decision.
        for (RunEventEnvelope event : evidence) {
            if (!trustedReceiptAfter(event, repair)) continue;
            String status = event.payload().path("status").asText("");
            String operation = event.payload().path("operation").asText("");
            if (status.equals("OBSERVED")
                    && (operation.equals("observe") || operation.equals("snapshot"))
                    && changedObservedState(evidence, event, repair)) return true;
            if ((status.equals("ACCEPTED") || status.equals("VERIFIED"))
                    && dispatchedBusinessAction(evidence, event, repair)) return true;
        }
        return false;
    }

    private static boolean trustedReceiptAfter(RunEventEnvelope event, RunEventEnvelope repair) {
        if (!event.type().equals("core.tool.receipt") || event.schemaVersion() != 1
                || !event.producer().equals("framework.core")) return false;
        return event.runId().equals(repair.runId())
                ? event.sequence() > repair.sequence()
                : event.timestamp().isAfter(repair.timestamp());
    }

    private static boolean dispatchedBusinessAction(List<RunEventEnvelope> evidence,
            RunEventEnvelope event, RunEventEnvelope repair) {
        JsonNode payload = event.payload();
        String operation = payload.path("operation").asText("");
        if (Set.of("observe", "snapshot", "probe", "targets", "read", "list",
                "search", "fetch", "fixed").contains(operation)) return false;
        JsonNode delivery = payload.path("metadata").path("delivery");
        if (delivery.isTextual()
                && Set.of("click", "type", "key", "scroll").contains(operation)
                && !delivery.asText().equals("SENT")) {
            return false;
        }
        // Reopening the same session or repeating an equivalent accepted operation does
        // not provide new evidence merely because it acquired a new invocation ID.
        return evidence.stream().noneMatch(previous -> previous != event
                && previous.type().equals("core.tool.receipt")
                && previous.producer().equals("framework.core")
                && !trustedReceiptAfter(previous, repair)
                && equivalentAction(previous.payload(), payload));
    }

    private static boolean equivalentAction(JsonNode previous, JsonNode current) {
        if (!previous.path("tool").asText("").equals(current.path("tool").asText(""))
                || !previous.path("operation").asText("").equals(
                        current.path("operation").asText(""))
                || !previous.path("target").asText("").equals(current.path("target").asText(""))
                || !previous.path("status").asText("").equals(current.path("status").asText(""))) {
            return false;
        }
        JsonNode before = previous.path("metadata");
        JsonNode after = current.path("metadata");
        return before.path("sessionId").asText("").equals(after.path("sessionId").asText(""))
                && before.path("observationId").asText("").equals(
                        after.path("observationId").asText(""));
    }

    private static boolean changedObservedState(List<RunEventEnvelope> evidence,
            RunEventEnvelope observed, RunEventEnvelope repair) {
        JsonNode payload = observed.payload();
        String state = observedState(payload);
        if (state.isBlank()) return false;
        RunEventEnvelope previous = evidence.stream()
                .filter(event -> event.type().equals("core.tool.receipt")
                        && event.producer().equals("framework.core")
                        && !trustedReceiptAfter(event, repair)
                        && event.payload().path("status").asText("").equals("OBSERVED")
                        && observationOwner(event.payload()).equals(observationOwner(payload)))
                .max(Comparator.comparing(RunEventEnvelope::timestamp)
                        .thenComparingLong(RunEventEnvelope::sequence)).orElse(null);
        return previous == null || !state.equals(observedState(previous.payload()));
    }

    private static String observationOwner(JsonNode payload) {
        JsonNode metadata = payload.path("metadata");
        return payload.path("tool").asText("") + '\u0000'
                + payload.path("target").asText("") + '\u0000'
                + metadata.path("sessionId").asText("") + '\u0000'
                + metadata.path("targetId").asText("");
    }

    private static String observedState(JsonNode payload) {
        JsonNode metadata = payload.path("metadata");
        String revision = metadata.path("contentRevision").asText("").strip();
        // The revision is tied to captured pixels. A model may rename the same
        // frame's subject, which must not turn a repeated observation into progress.
        if (!revision.isBlank()) return "revision:" + revision;
        String view = metadata.path("viewEvidence").asText("").strip();
        if (!view.isBlank()) return "view:" + view;
        return "";
    }

}
