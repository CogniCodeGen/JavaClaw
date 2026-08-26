package com.javaclaw.framework.core;

import com.javaclaw.framework.api.RunId;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultRunResourceRegistryTest {

    @Test
    void sharesOneResourceWithinARunAndClosesItOnRelease() {
        DefaultRunResourceRegistry registry = new DefaultRunResourceRegistry();
        RunId run = new RunId("resource-run");
        AtomicInteger creations = new AtomicInteger();
        AtomicBoolean closed = new AtomicBoolean();

        TestResource first = registry.forRun(run).getOrCreate(
                "test", TestResource.class,
                () -> new TestResource(creations.incrementAndGet(), closed));
        TestResource second = registry.forRun(run).getOrCreate(
                "test", TestResource.class,
                () -> new TestResource(creations.incrementAndGet(), closed));

        assertSame(first, second);
        assertEquals(1, creations.get());
        assertEquals(1, registry.activeScopeCount());

        registry.release(run);

        assertTrue(closed.get());
        assertEquals(0, registry.activeScopeCount());
        TestResource nextLifetime = registry.forRun(run).getOrCreate(
                "test", TestResource.class,
                () -> new TestResource(creations.incrementAndGet(), new AtomicBoolean()));
        assertNotSame(first, nextLifetime);
        assertEquals(2, creations.get());
        registry.close();
    }

    private record TestResource(int sequence, AtomicBoolean closed) implements AutoCloseable {
        @Override public void close() {
            closed.set(true);
        }
    }
}
