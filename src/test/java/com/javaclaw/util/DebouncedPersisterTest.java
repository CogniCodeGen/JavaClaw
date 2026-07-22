package com.javaclaw.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebouncedPersisterTest {

    @Test
    void flush与调度保存不会并发执行() throws Exception {
        CountDownLatch scheduledEntered = new CountDownLatch(1);
        CountDownLatch releaseScheduled = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicBoolean overlapped = new AtomicBoolean();

        DebouncedPersister persister = new DebouncedPersister("test", Duration.ZERO, () -> {
            if (active.incrementAndGet() > 1) overlapped.set(true);
            try {
                scheduledEntered.countDown();
                releaseScheduled.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
            }
        });

        try {
            persister.request();
            assertTrue(scheduledEntered.await(1, TimeUnit.SECONDS));

            Thread flushThread = new Thread(persister::flush);
            flushThread.start();
            Thread.sleep(50);
            assertFalse(overlapped.get());

            releaseScheduled.countDown();
            flushThread.join(1_000);
            assertFalse(flushThread.isAlive());
            assertFalse(overlapped.get());
        } finally {
            releaseScheduled.countDown();
            persister.shutdown();
        }
    }

    @Test
    void shutdown后请求被安全忽略() {
        AtomicInteger calls = new AtomicInteger();
        DebouncedPersister persister = new DebouncedPersister(
                "test", Duration.ZERO, calls::incrementAndGet);

        persister.shutdown();
        persister.request();

        assertEquals(0, calls.get());
    }
}
