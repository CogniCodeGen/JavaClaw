package com.javaclaw.platform.fx;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FxDispatcherTest {

    @Test
    void runsImmediatelyOnFxThread() {
        AtomicBoolean called = new AtomicBoolean(false);
        FxDispatcher dispatcher = new FxDispatcher(() -> true, ignored -> {
            throw new AssertionError("不应排队");
        });

        dispatcher.dispatch(() -> called.set(true));

        assertTrue(called.get());
    }

    @Test
    void queuesFromBackgroundAndCompletesCallResult() {
        Queue<Runnable> queue = new ArrayDeque<>();
        FxDispatcher dispatcher = new FxDispatcher(() -> false, queue::add);

        var result = dispatcher.call(() -> "done");

        assertFalse(result.isDone());
        assertEquals(1, queue.size());
        queue.remove().run();
        assertEquals("done", result.join());
    }
}
