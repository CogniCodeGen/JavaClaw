package com.javaclaw.server.security.vault;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultChangeListenersConcurrencyTest {
    @Test
    void 前一变更成功而后一变更失败时不会留下永久关闭的门闩() throws Exception {
        VaultChangeListeners listeners = new VaultChangeListeners(() -> true);
        BlockingRebuild rebuild = new BlockingRebuild();
        listeners.addRuntime(() -> {}, rebuild::run);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        Object monitor = new Object();

        Thread first = start(firstFailure, () -> listeners.afterChange(monitor, () -> "committed"));
        assertTrue(rebuild.started.await(5, TimeUnit.SECONDS));
        AtomicBoolean secondMutationEntered = new AtomicBoolean();
        CountDownLatch secondStarted = new CountDownLatch(1);
        Thread second = start(secondFailure, () -> {
            secondStarted.countDown();
            listeners.afterChange(monitor, () -> {
                secondMutationEntered.set(true);
                throw new IllegalStateException("later mutation failed");
            });
        });
        assertTrue(secondStarted.await(5, TimeUnit.SECONDS));

        try {
            assertFalse(awaitTrue(secondMutationEntered));
        } finally {
            rebuild.release.countDown();
        }
        join(first);
        join(second);

        assertNull(firstFailure.get());
        assertInstanceOf(IllegalStateException.class, secondFailure.get());
        assertDoesNotThrow(listeners.runtimeGate()::requireOpenStamp);
    }

    @Test
    void 两个并发失败变更完成后门闩仍可用() throws Exception {
        VaultChangeListeners listeners = new VaultChangeListeners(() -> true);
        BlockingRebuild rebuild = new BlockingRebuild();
        listeners.addRuntime(() -> {}, rebuild::run);
        AtomicBoolean ready = new AtomicBoolean(true);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();

        Thread first = start(
                firstFailure,
                () -> listeners.afterAvailabilityRefresh(new Object(), ready::get, () -> {
                    ready.set(false);
                    throw new IllegalArgumentException("refresh failed after state change");
                }));
        assertTrue(rebuild.started.await(5, TimeUnit.SECONDS));
        AtomicBoolean secondMutationEntered = new AtomicBoolean();
        CountDownLatch secondStarted = new CountDownLatch(1);
        Thread second = start(secondFailure, () -> {
            secondStarted.countDown();
            listeners.afterChange(new Object(), () -> {
                secondMutationEntered.set(true);
                throw new IllegalStateException("second mutation failed");
            });
        });
        assertTrue(secondStarted.await(5, TimeUnit.SECONDS));

        try {
            assertFalse(awaitTrue(secondMutationEntered));
        } finally {
            rebuild.release.countDown();
        }
        join(first);
        join(second);

        assertInstanceOf(IllegalArgumentException.class, firstFailure.get());
        assertInstanceOf(IllegalStateException.class, secondFailure.get());
        assertDoesNotThrow(listeners.runtimeGate()::requireOpenStamp);
    }

    private static Thread start(AtomicReference<Throwable> failure, Runnable action) {
        return Thread.ofPlatform().start(() -> {
            try {
                action.run();
            } catch (Throwable caught) {
                failure.set(caught);
            }
        });
    }

    private static boolean awaitTrue(AtomicBoolean value) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!value.get() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return value.get();
    }

    private static void join(Thread thread) throws InterruptedException {
        thread.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(thread.isAlive());
    }

    private static final class BlockingRebuild {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private void run() {
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("等待测试释放重建超时");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待测试释放重建时被中断", failure);
            }
        }
    }
}
