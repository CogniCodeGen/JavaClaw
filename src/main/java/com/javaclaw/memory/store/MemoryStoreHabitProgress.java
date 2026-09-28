package com.javaclaw.memory.store;

import com.javaclaw.memory.model.MemoryStats;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Persisted habit-review cursor and provisional observations, guarded by the owning store lock. */
final class MemoryStoreHabitProgress {
    private final MemoryStore store;

    MemoryStoreHabitProgress(MemoryStore store) {
        this.store = store;
    }

    long lastReviewedAt() {
        return store.writeCall(() -> store.root().stats.lastHabitReviewAt);
    }

    MemoryStore.HabitReviewProgress progress() {
        return store.writeCall(this::progressUnsafe);
    }

    private MemoryStore.HabitReviewProgress progressUnsafe() {
        var stats = store.root().stats;
        return new MemoryStore.HabitReviewProgress(
                stats.habitCursorTimestamp, stats.habitCursorEvidenceKey,
                stats.pendingHabitEvidenceKeys == null ? List.of()
                        : List.copyOf(stats.pendingHabitEvidenceKeys),
                copyObservations(stats.pendingHabitObservations), stats.habitReviewRevision,
                stats.habitReviewDraining);
    }

    void withProgress(MemoryStore.HabitReviewProgress expected, Runnable mutation) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(mutation, "mutation");
        store.withProjectionLock(() -> {
            MemoryStore.HabitReviewProgress current = progressUnsafe();
            if (current.revision() != expected.revision()
                    || current.cursorTimestamp() != expected.cursorTimestamp()
                    || !current.cursorEvidenceKey().equals(expected.cursorEvidenceKey())
                    || current.draining() != expected.draining()) {
                throw new IllegalStateException("[" + store.label() + "] 习惯回顾进度已变化，需重新规划");
            }
            mutation.run();
        });
    }

    void noteOversized(List<String> evidenceKeys) {
        if (evidenceKeys == null || evidenceKeys.isEmpty()) return;
        store.withProjectionLock(() -> {
            List<String> pending = new ArrayList<>(store.root().stats.pendingHabitEvidenceKeys == null
                    ? List.of() : store.root().stats.pendingHabitEvidenceKeys);
            for (String key : evidenceKeys) {
                if (key != null && !pending.contains(key)) pending.add(key);
            }
            store.manager().store(pending);
            store.root().stats.pendingHabitEvidenceKeys = pending;
            store.manager().store(store.root().stats);
        });
    }

    void mark(long completedAt, long cursorTimestamp, String cursorEvidenceKey,
              List<String> reviewedKeys, List<MemoryStats.HabitObservation> observations,
              boolean caughtUp, String actor, String summary) {
        store.withProjectionLock(() -> {
            var stats = store.root().stats;
            List<String> pending = new ArrayList<>(stats.pendingHabitEvidenceKeys == null
                    ? List.of() : stats.pendingHabitEvidenceKeys);
            if (reviewedKeys != null) pending.removeAll(reviewedKeys);
            List<MemoryStats.HabitObservation> next = observations == null
                    ? copyObservations(stats.pendingHabitObservations)
                    : copyObservations(observations);
            store.manager().store(pending);
            for (MemoryStats.HabitObservation observation : next) store.manager().store(observation);
            store.manager().store(next);
            stats.pendingHabitEvidenceKeys = pending;
            stats.pendingHabitObservations = next;
            stats.habitCursorTimestamp = cursorTimestamp;
            stats.habitCursorEvidenceKey = cursorEvidenceKey;
            stats.habitReviewDraining = !caughtUp;
            stats.habitReviewRevision++;
            if (caughtUp) stats.lastHabitReviewAt = completedAt;
            store.manager().store(stats);
            store.appendChangeLog("HABIT_REVIEW", "Episode", null, actor, truncate(summary));
        });
    }

    void finishDrain(MemoryStore.HabitReviewProgress expected, long completedAt,
                     String actor, String summary) {
        withProgress(expected, () -> {
            var stats = store.root().stats;
            if (!stats.habitReviewDraining) return;
            stats.habitReviewDraining = false;
            stats.lastHabitReviewAt = completedAt;
            stats.habitReviewRevision++;
            store.manager().store(stats);
            store.appendChangeLog("HABIT_REVIEW", "Episode", null, actor, truncate(summary));
        });
    }

    static List<MemoryStats.HabitObservation> copyObservations(
            List<MemoryStats.HabitObservation> observations) {
        if (observations == null || observations.isEmpty()) return List.of();
        List<MemoryStats.HabitObservation> copy = new ArrayList<>(observations.size());
        for (MemoryStats.HabitObservation observation : observations) {
            if (observation == null) throw new IllegalStateException("习惯回顾线索台账包含空记录");
            copy.add(new MemoryStats.HabitObservation(observation.text, observation.evidenceKey));
        }
        return List.copyOf(copy);
    }

    private static String truncate(String text) {
        if (text == null) return "";
        return text.length() > 80 ? text.substring(0, 80) + "…" : text;
    }
}
