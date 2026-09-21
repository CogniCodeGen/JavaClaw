package com.javaclaw.memory.store;

import com.javaclaw.memory.model.MemoryRoot;
import org.eclipse.store.gigamap.types.GigaMap;
import org.eclipse.store.storage.embedded.types.EmbeddedStorageManager;

/** Additive schema restoration for stores created by earlier releases. */
final class MemoryStoreSchema {
    private MemoryStoreSchema() {}
    static void complete(MemoryRoot root, EmbeddedStorageManager mgr) {
        boolean changed = false;
        if (root.pendingFacts == null) { root.pendingFacts = GigaMap.New(); changed = true; }
        if (root.pendingEpisodes == null) { root.pendingEpisodes = GigaMap.New(); changed = true; }
        if (root.corrections == null) { root.corrections = GigaMap.New(); changed = true; }
        if (root.working == null) { root.working = new java.util.HashMap<>(); changed = true; }
        if (root.appliedOperations == null) { root.appliedOperations = new java.util.HashSet<>(); changed = true; }
        if (root.migratedIds == null) { root.migratedIds = new java.util.HashSet<>(); changed = true; }
        if (root.pendingGraphSnapshots == null) { root.pendingGraphSnapshots = new java.util.HashMap<>(); changed = true; }
        if (root.stats == null) {
            root.stats = new com.javaclaw.memory.model.MemoryStats();
            changed = true;
        }
        if (changed) {
            mgr.store(root);
            
        }
    }
}
