package com.javaclaw.memory.store;

import org.eclipse.store.gigamap.jvector.VectorIndex;
import org.eclipse.store.gigamap.jvector.VectorIndexConfiguration;
import org.eclipse.store.gigamap.jvector.VectorIndices;
import org.eclipse.store.gigamap.jvector.VectorSimilarityFunction;
import org.eclipse.store.gigamap.jvector.Vectorizer;
import org.eclipse.store.gigamap.types.BitmapIndices;
import org.eclipse.store.gigamap.types.GigaMap;
import org.eclipse.store.gigamap.types.IndexerString;

/** Index setup for the current memory object graph. */
final class MemoryStoreIndexLifecycle {
    private static final String VECTOR_INDEX = "embedding";

    private MemoryStoreIndexLifecycle() {}

    static <E> VectorIndex<E> ensureIndex(GigaMap<E> map, Vectorizer<? super E> vectorizer,
                                          int dimension) {
        VectorIndices<E> indices = map.index().get(VectorIndices.Category());
        if (indices == null) indices = map.index().register(VectorIndices.Category());
        VectorIndex<E> index = indices.get(VECTOR_INDEX);
        if (index == null) {
            VectorIndexConfiguration configuration = VectorIndexConfiguration.forSmallDataset(
                    dimension, VectorSimilarityFunction.COSINE);
            index = indices.add(VECTOR_INDEX, configuration, vectorizer);
        }
        return index;
    }

    static <E> void ensureIdentityIndex(GigaMap<E> map, IndexerString<? super E> indexer) {
        BitmapIndices<E> indices = map.index().bitmap();
        indices.ensure(indexer);
        indices.setIdentityIndices(indexer);
        map.store();
    }
}
