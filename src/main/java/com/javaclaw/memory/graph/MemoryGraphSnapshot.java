package com.javaclaw.memory.graph;

import com.javaclaw.memory.model.*;
import com.javaclaw.memory.store.MemoryStore;
import java.util.List;

/** Complete graph after-state; serialized immediately before crossing the journal boundary. */
public record MemoryGraphSnapshot(long version, List<Fact> facts, List<Fact> pendingFacts,
        List<Episode> episodes, List<Episode> pendingEpisodes, List<EntityNode> entities,
        List<CorrectionRecord> corrections, Persona persona) {
    public static MemoryGraphSnapshot capture(MemoryStore store, long version) {
        return new MemoryGraphSnapshot(version, store.allFacts(), store.allPendingFacts(),
                store.allEpisodes(), store.allPendingEpisodes(), store.allEntities(),
                store.allCorrections(), store.getPersona());
    }
}
