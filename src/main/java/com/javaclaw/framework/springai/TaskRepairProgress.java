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
            if (newSatisfiedFileRead(events, evidence, event, repair, current)) return true;
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

    private static boolean newSatisfiedFileRead(List<RunEventEnvelope> events,
            List<RunEventEnvelope> evidence,
            RunEventEnvelope event, RunEventEnvelope repair, TaskResult current) {
        JsonNode payload = event.payload();
        String reference = payload.path("evidenceRef").asText("");
        // Only an actual host file observation selected by the strict frozen-contract
        // evaluator can advance a read task. A new invocation ID alone is insufficient.
        if (reference.isBlank() || !current.evidenceRefs().contains(reference)
                || !trustedFileRead(event)) return false;
        RunEventEnvelope previousReview = events.stream()
                .filter(review -> review.runId().equals(repair.runId())
                        && review.sequence() < repair.sequence()
                        && review.type().equals("core.task.review")
                        && review.schemaVersion() == 3
                        && review.producer().equals("framework.springai"))
                .max(Comparator.comparingLong(RunEventEnvelope::sequence)).orElse(null);
        Set<String> selectedBefore = new java.util.HashSet<>();
        if (previousReview != null) {
            previousReview.payload().path("evidenceRefs").forEach(
                    value -> { if (value.isTextual()) selectedBefore.add(value.asText()); });
        }
        // A formerly out-of-order read can become the new required ordered evidence.
        // Compare only receipts selected by the immediately preceding trusted review;
        // absent that review, retain the conservative all-history comparison.
        return evidence.stream().noneMatch(previous -> previous != event
                && !trustedReceiptAfter(previous, repair) && trustedFileRead(previous)
                && (previousReview == null || selectedBefore.contains(
                        previous.payload().path("evidenceRef").asText("")))
                && previous.payload().path("target").asText("").equals(
                        payload.path("target").asText(""))
                && previous.payload().path("metadata").path("fileContentSha256").asText("")
                        .equals(payload.path("metadata").path("fileContentSha256").asText(""))
                && previous.payload().path("metadata").path("fileContentCharacters").asText("")
                        .equals(payload.path("metadata").path("fileContentCharacters").asText("")));
    }

    private static boolean trustedFileRead(RunEventEnvelope event) {
        JsonNode payload = event.payload();
        JsonNode metadata = payload.path("metadata");
        return event.type().equals("core.tool.receipt") && event.schemaVersion() == 1
                && event.producer().equals("framework.core")
                && payload.path("tool").asText("").equals("sys_file_read")
                && payload.path("operation").asText("").equals("read")
                && payload.path("status").asText("").equals("OBSERVED")
                && !payload.path("target").asText("").isBlank()
                && metadata.path("fileContentFormat").asText("")
                        .equals("stripped-utf8-sha256-v1")
                && metadata.path("fileContentSha256").asText("").matches("[0-9a-f]{64}")
                && metadata.path("fileContentCharacters").asText("").matches("0|[1-9][0-9]*");
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
        // A desktop input being accepted proves dispatch, not visible task progress. Its fresh
        // subsequent observation must change real state; rotating handle IDs is insufficient.
        if (payload.path("tool").asText("").startsWith("desktop_session_")
                && Set.of("click", "type", "key", "scroll").contains(operation)) return false;
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
