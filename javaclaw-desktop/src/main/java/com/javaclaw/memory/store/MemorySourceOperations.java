package com.javaclaw.memory.store;

import com.javaclaw.memory.model.Fact;
import java.util.ArrayList;

/** Source-level idempotency for graph projections and fork-preserved evidence identities. */
final class MemorySourceOperations {
    private MemorySourceOperations() {}
    static void mergeFact(MemoryStore store, Fact fact, String actor, String evidenceKey) {
        store.writeCall(() -> {
            if (fact.evidenceKeys != null && fact.evidenceKeys.contains(evidenceKey)) return null;
            store.updateFact(fact, f -> {
                if (f.evidenceKeys == null) f.evidenceKeys = new ArrayList<>();
                f.evidenceKeys.add(evidenceKey);
                f.mergeCount++;
            }, actor);
            return null;
        });
    }
}
