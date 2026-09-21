package com.javaclaw.memory.store;

import com.javaclaw.memory.model.MemoryRoot;
import org.eclipse.store.storage.embedded.types.EmbeddedStorage;
import org.eclipse.store.storage.embedded.types.EmbeddedStorageManager;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

/** Metadata-only cleanup does not require an embedding model or rebuild vector indexes. */
public final class MemoryMetadataMaintenance {
    private MemoryMetadataMaintenance() {}
    public static void update(MemoryStore store, Function<MemoryRoot, List<Object>> change) {
        store.withProjectionLock(() -> persist(store.manager(), store.root(), change));
    }
    public static void update(Path directory, Function<MemoryRoot, List<Object>> change) {
        EmbeddedStorageManager manager = EmbeddedStorage.start(directory);
        try {
            MemoryRoot root = manager.root();
            if (root == null) return;
            MemoryStoreSchema.complete(root, manager);
            persist(manager, root, change);
        } finally { manager.shutdown(); }
    }
    private static void persist(EmbeddedStorageManager manager, MemoryRoot root,
                                Function<MemoryRoot, List<Object>> change) {
        for (Object changed : change.apply(root)) manager.store(changed);
    }
}
