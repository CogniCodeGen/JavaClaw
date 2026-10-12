package com.javaclaw.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.spi.ThreadJournal;
import com.javaclaw.memory.graph.MemoryGraphSnapshot;
import com.javaclaw.memory.store.MemoryStore;

/** Graph-to-H2 bridge with a durable local outbox for the unavoidable cross-store commit gap. */
final class MemoryGraphJournal {
    private final ObjectMapper json;
    private final ThreadJournal journal;
    MemoryGraphJournal(ObjectMapper json, ThreadJournal journal) {
        this.json = java.util.Objects.requireNonNull(json);
        this.journal = java.util.Objects.requireNonNull(journal);
    }
    void bind(MemoryGraphScope scope, MemoryStore store) {
        if (scope.kind() != MemoryGraphScope.Kind.THREAD) return;
        store.withProjectionLock(() -> {
        flush(scope, store);
        store.observeMutations(changed -> {
            var root = changed.root();
            long version = ++root.graphVersion;
            String mutationId = java.util.UUID.randomUUID().toString();
            try {
                String snapshot = json.writeValueAsString(MemoryGraphSnapshot.capture(changed, version));
                root.pendingGraphSnapshots.put(mutationId, snapshot);
                changed.persistProjectionState();
                flush(scope, changed);
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("无法持久化记忆图谱变更", failure);
            }
        });
        });
    }
    private void flush(MemoryGraphScope scope, MemoryStore store) {
        var entries = new java.util.ArrayList<>(store.root().pendingGraphSnapshots.entrySet());
        entries.sort(java.util.Comparator.comparingLong(entry -> version(entry.getValue())));
        for (var entry : entries) {
            try {
                var payload = json.createObjectNode();
                payload.put("mutationId", entry.getKey());
                payload.set("snapshot", json.readTree(entry.getValue()));
                journal.appendOnce(new RunScope(scope.workspaceId(), scope.userId(), scope.threadId()),
                        entry.getKey(), "memory/graph-snapshot", payload);
                store.root().pendingGraphSnapshots.remove(entry.getKey());
                store.persistProjectionState();
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("无法恢复待提交的图谱快照", failure);
            }
        }
    }
    private long version(String snapshot) {
        try { return json.readTree(snapshot).path("version").asLong(); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
}
