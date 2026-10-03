package com.javaclaw.framework.core;

import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.spi.RunStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;

/** Collects durable parent and completed-child events in effect order for task acceptance. */
public final class TaskEvidenceCollector {
    private TaskEvidenceCollector() { }

    /** Returns parent events and terminal descendant events in chronological order. */
    public static List<RunEventEnvelope> collect(RunStore runs, RunId parentId) {
        Objects.requireNonNull(runs, "runs");
        Objects.requireNonNull(parentId, "parentId");
        List<RunEventEnvelope> events = new ArrayList<>();
        collectDescendants(runs, parentId, true, new HashSet<>(), events);
        // Run-local sequences cannot order receipts produced by another Run. Event times
        // establish the cross-Run order; observedAt breaks millisecond timestamp ties.
        events.sort(Comparator.comparing(RunEventEnvelope::timestamp)
                .thenComparing(TaskEvidenceCollector::observedAt)
                .thenComparing(event -> event.runId().equals(parentId.value()) ? 0 : 1)
                .thenComparing(RunEventEnvelope::runId)
                .thenComparingLong(RunEventEnvelope::sequence));
        return List.copyOf(events);
    }

    /** An unfinished descendant may still produce effects after evidence is collected. */
    public static boolean hasNonTerminalDescendant(RunStore runs, RunId parentId) {
        Objects.requireNonNull(runs, "runs");
        Objects.requireNonNull(parentId, "parentId");
        return hasNonTerminalDescendant(runs, parentId, new HashSet<>(Set.of(parentId)));
    }

    private static boolean hasNonTerminalDescendant(RunStore runs, RunId parentId,
            Set<RunId> visited) {
        for (var child : runs.childRuns(parentId)) {
            RunId childId = child.snapshot().id();
            if (!visited.add(childId) || !child.snapshot().state().terminal()
                    || hasNonTerminalDescendant(runs, childId, visited)) return true;
        }
        return false;
    }

    private static void collectDescendants(RunStore runs, RunId id, boolean root,
            Set<RunId> visited, List<RunEventEnvelope> events) {
        if (!visited.add(id)) return;
        if (!root && runs.find(id).map(run -> run.snapshot().state().terminal())
                .orElse(false) == false) return;
        events.addAll(runs.eventsAfter(id, 0));
        for (var child : runs.childRuns(id)) {
            collectDescendants(runs, child.snapshot().id(), false, visited, events);
        }
    }

    private static Instant observedAt(RunEventEnvelope event) {
        if (!event.type().equals("core.tool.receipt")) return event.timestamp();
        try {
            return Instant.parse(event.payload().path("observedAt").asText());
        } catch (RuntimeException ignored) {
            return event.timestamp();
        }
    }
}
