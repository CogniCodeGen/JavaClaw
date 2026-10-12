package com.javaclaw.memory;

import com.javaclaw.memory.model.Episode;
import com.javaclaw.memory.model.Fact;
import com.javaclaw.memory.store.MemoryStore;

import java.util.ArrayList;
import java.util.List;

/** Merged persisted and pending memory views for user-facing reads. */
final class MemoryReadViews {
    private MemoryReadViews() { }

    static List<Fact> facts(MemoryStore store) {
        if (store == null) return List.of();
        List<Fact> out = new ArrayList<>(store.allFacts());
        out.addAll(store.allPendingFacts());
        return out;
    }

    static List<Episode> episodes(MemoryStore store) {
        if (store == null) return List.of();
        List<Episode> out = new ArrayList<>(store.allEpisodes());
        out.addAll(store.allPendingEpisodes());
        return out;
    }
}
