package com.javaclaw.memory.store;

import com.javaclaw.memory.graph.MemoryGraphSnapshot;
import com.javaclaw.memory.model.*;
import java.util.HashMap;
import java.util.Map;

/** Rebind all references into the destination graph; no cross-EclipseStore object sharing. */
final class MemorySnapshotStore {
    private MemorySnapshotStore() {}
    static void restore(MemoryStore store, MemoryGraphSnapshot snapshot) {
        MemoryRoot replacement = new MemoryRoot();
        replacement.graphVersion = snapshot.version();
        Map<String, Episode> episodes = new HashMap<>();
        for (Episode episode : snapshot.episodes()) {
            episode.pending = false;
            episode.entityId = replacement.episodes.add(episode);
            episodes.put(episode.id, episode);
        }
        for (Episode episode : snapshot.pendingEpisodes()) {
            episode.pending = true;
            episode.entityId = replacement.pendingEpisodes.add(episode);
            episodes.put(episode.id, episode);
        }
        Map<String, EntityNode> entities = new HashMap<>();
        for (EntityNode entity : snapshot.entities()) {
            entity.entityId = replacement.entities.add(entity);
            entities.put(entity.id, entity);
        }
        for (Fact fact : snapshot.facts()) {
            rebind(fact, episodes, entities);
            fact.pending = false;
            fact.entityId = replacement.facts.add(fact);
        }
        for (Fact fact : snapshot.pendingFacts()) {
            rebind(fact, episodes, entities);
            fact.pending = true;
            fact.entityId = replacement.pendingFacts.add(fact);
        }
        for (CorrectionRecord correction : snapshot.corrections()) {
            correction.entityId = replacement.corrections.add(correction);
        }
        replacement.persona = snapshot.persona();
        store.replaceRoot(replacement);
    }
    private static void rebind(Fact fact, Map<String, Episode> episodes, Map<String, EntityNode> entities) {
        if (fact.source != null) fact.source = episodes.get(fact.source.id);
        if (fact.about != null) fact.about = fact.about.stream().map(entity -> entities.get(entity.id))
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
    }
}
