package com.javaclaw.memory.store;

import com.javaclaw.memory.model.AgentCheckpoint;

/** Working-memory checkpoint persistence on the owning store's write gate. */
final class MemoryStoreCheckpoints {
    private final MemoryStore store;

    MemoryStoreCheckpoints(MemoryStore store) {
        this.store = store;
    }

    void save(String key, String messagesJson) {
        store.withProjectionLock(() -> {
            store.root().working.put(key, new AgentCheckpoint(key, messagesJson));
            store.manager().store(store.root().working);
        });
    }

    AgentCheckpoint load(String key) {
        return store.writeCall(() -> store.root().working.get(key));
    }

    void remove(String key) {
        store.withProjectionLock(() -> {
            if (store.root().working.remove(key) != null) {
                store.manager().store(store.root().working);
                store.appendChangeLog("REMOVE", "Checkpoint", key, "user", "");
            }
        });
    }
}
