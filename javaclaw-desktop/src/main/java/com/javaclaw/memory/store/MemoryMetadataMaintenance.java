package com.javaclaw.memory.store;

import com.javaclaw.memory.model.MemoryRoot;
import com.javaclaw.memory.model.Fact;
import com.javaclaw.memory.model.Episode;
import java.util.ArrayList;
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
            persist(manager, root, change);
        } finally { manager.shutdown(); }
    }
    private static void persist(EmbeddedStorageManager manager, MemoryRoot root,
                                Function<MemoryRoot, List<Object>> change) {
        for (Object changed : change.apply(root)) manager.store(changed);
    }

    /** 仅在持有所属存储写锁的启动恢复中调用，不执行嵌入或重建索引。 */
    static RecoveryCounts recoverPromotedEntities(MemoryRoot root, int dimension,
            java.util.function.LongFunction<float[]> factVector,
            java.util.function.LongFunction<float[]> episodeVector,
            java.util.function.Consumer<Object> persist) {
        record StoredEntity<E>(long entityId, E entity) {}
        List<StoredEntity<Fact>> facts = new ArrayList<>();
        root.facts.iterateIndexed((entityId, fact) -> {
            if (fact.pending || fact.entityId != entityId || !validStoredEmbedding(fact.embedding, dimension)) {
                facts.add(new StoredEntity<>(entityId, fact));
            }
        });
        for (StoredEntity<Fact> entry : facts) {
            Fact fact = entry.entity();
            float[] embedding = validStoredEmbedding(fact.embedding, dimension)
                    ? fact.embedding : factVector.apply(entry.entityId());
            if (!validStoredEmbedding(embedding, dimension)) {
                throw new IllegalStateException("已索引事实缺少可恢复的向量: " + fact.id);
            }
            fact.entityId = entry.entityId();
            fact.pending = false;
            fact.embedding = embedding;
            persist.accept(fact); // 仅恢复字段与已有映射和索引一致，不改变二者。
        }

        List<StoredEntity<Episode>> episodes = new ArrayList<>();
        root.episodes.iterateIndexed((entityId, episode) -> {
            if (episode.pending || episode.entityId != entityId || !validStoredEmbedding(episode.embedding, dimension)) {
                episodes.add(new StoredEntity<>(entityId, episode));
            }
        });
        for (StoredEntity<Episode> entry : episodes) {
            Episode episode = entry.entity();
            float[] embedding = validStoredEmbedding(episode.embedding, dimension)
                    ? episode.embedding : episodeVector.apply(entry.entityId());
            if (!validStoredEmbedding(embedding, dimension)) {
                throw new IllegalStateException("已索引情景缺少可恢复的向量: " + episode.id);
            }
            episode.entityId = entry.entityId();
            episode.pending = false;
            episode.embedding = embedding;
            persist.accept(episode); // 情景没有可供 GigaMap.update 使用的身份位图索引。
        }
        return new RecoveryCounts(facts.size(), episodes.size());
    }

    private static boolean validStoredEmbedding(float[] embedding, int dimension) {
        if (embedding == null || embedding.length != dimension) return false;
        for (float value : embedding) if (!Float.isFinite(value)) return false;
        return true;
    }

    record RecoveryCounts(int facts, int episodes) { }
}
