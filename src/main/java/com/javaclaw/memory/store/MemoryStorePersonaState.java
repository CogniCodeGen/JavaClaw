package com.javaclaw.memory.store;

import com.javaclaw.memory.model.Persona;

import java.util.function.Consumer;

/** Persona writes and audit performed on the memory store's write gate. */
final class MemoryStorePersonaState {
    private final MemoryStore store;

    MemoryStorePersonaState(MemoryStore store) {
        this.store = store;
    }

    Persona get() {
        return store.writeCall(() -> store.root().persona);
    }

    void set(String content, String actor) {
        store.withProjectionLock(() -> {
            Persona persona = store.root().persona;
            if (persona == null) {
                store.root().persona = new Persona(content);
            } else {
                persona.content = content;
                persona.updatedAt = System.currentTimeMillis();
            }
            store.manager().store(store.root().persona);
            store.manager().store(store.root());
            store.appendChangeLog("PERSONA_EDIT", "Persona", null, actor, truncate(content));
        });
    }

    void update(Consumer<Persona> mutator, String actor) {
        store.withProjectionLock(() -> {
            if (store.root().persona == null) store.root().persona = new Persona("");
            mutator.accept(store.root().persona);
            store.root().persona.updatedAt = System.currentTimeMillis();
            store.manager().store(store.root().persona);
            store.manager().store(store.root());
            store.appendChangeLog("PERSONA_EDIT", "Persona", null, actor,
                    truncate(store.root().persona.content));
        });
    }

    private static String truncate(String text) {
        if (text == null) return "";
        return text.length() > 80 ? text.substring(0, 80) + "…" : text;
    }
}
