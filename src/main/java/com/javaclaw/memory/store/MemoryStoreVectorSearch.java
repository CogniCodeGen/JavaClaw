package com.javaclaw.memory.store;

import org.eclipse.store.gigamap.jvector.VectorIndex;
import org.eclipse.store.gigamap.jvector.VectorSearchResult;
import org.eclipse.store.gigamap.types.ScoredSearchResult;

import java.util.ArrayList;
import java.util.List;

/** Score-filtered vector search shared by facts, episodes and knowledge. */
final class MemoryStoreVectorSearch {
    private MemoryStoreVectorSearch() {}

    static <E> List<MemoryStore.Scored<E>> search(VectorIndex<E> index, float[] query,
                                                   int topK, double threshold) {
        List<MemoryStore.Scored<E>> out = new ArrayList<>();
        if (index == null || query == null || topK <= 0) return out;
        VectorSearchResult<E> result = index.search(query, topK);
        for (ScoredSearchResult.Entry<E> hit : result) {
            if (hit.score() >= threshold) {
                out.add(new MemoryStore.Scored<>(hit.entity(), hit.score()));
            }
        }
        return out;
    }
}
