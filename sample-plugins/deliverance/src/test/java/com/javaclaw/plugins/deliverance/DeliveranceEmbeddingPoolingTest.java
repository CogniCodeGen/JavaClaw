package com.javaclaw.plugins.deliverance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeliveranceEmbeddingPoolingTest {

    @Test
    void autoUsesLastTokenForQwen3() {
        assertEquals("LAST", DeliveranceEngine.resolvedPooling("qwen3", null));
        assertEquals("LAST", DeliveranceEngine.resolvedPooling("QWEN3", "AUTO"));
    }

    @Test
    void autoPreservesAveragePoolingForExistingEmbeddingFamilies() {
        assertEquals("AVG", DeliveranceEngine.resolvedPooling("bert", null));
        assertEquals("AVG", DeliveranceEngine.resolvedPooling("bert", "auto"));
    }

    @Test
    void explicitPoolingRemainsSupportedAndUnknownValuesFailFast() {
        assertEquals("LAST", DeliveranceEngine.resolvedPooling("bert", "last"));
        assertEquals("MODEL", DeliveranceEngine.resolvedPooling("bert", "MODEL"));
        assertThrows(IllegalArgumentException.class,
                () -> DeliveranceEngine.resolvedPooling("qwen3", "median"));
    }
}
