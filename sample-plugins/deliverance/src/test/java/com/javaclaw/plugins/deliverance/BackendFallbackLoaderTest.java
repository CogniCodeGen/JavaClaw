package com.javaclaw.plugins.deliverance;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendFallbackLoaderTest {

    @Test
    void closesFailedSimdThenUsesJvectorForAnyModelLoader() {
        Backend simd = new Backend("simd");
        Backend jvector = new Backend("jvector");

        var loaded = BackendFallbackLoader.load(true, () -> simd, () -> jvector, backend -> {
            if (backend == simd) throw new IllegalStateException("simd failed");
            return "model";
        });

        assertTrue(simd.closed.get());
        assertFalse(jvector.closed.get());
        assertEquals("model", loaded.model());
        assertEquals(jvector, loaded.backend());
    }

    @Test
    void closesBothBackendsWhenFallbackAlsoFails() {
        Backend simd = new Backend("simd");
        Backend jvector = new Backend("jvector");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> BackendFallbackLoader.load(true, () -> simd, () -> jvector,
                        backend -> { throw new IllegalStateException(backend.name); }));

        assertTrue(simd.closed.get());
        assertTrue(jvector.closed.get());
        assertEquals(1, failure.getSuppressed().length);
    }

    private static final class Backend implements AutoCloseable {
        private final String name;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Backend(String name) { this.name = name; }
        @Override public void close() { closed.set(true); }
    }
}
