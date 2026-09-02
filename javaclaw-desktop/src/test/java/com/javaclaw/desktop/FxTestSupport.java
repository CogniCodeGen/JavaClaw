package com.javaclaw.desktop;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import javafx.application.Platform;

public final class FxTestSupport {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static boolean started;

    private FxTestSupport() {}

    public static synchronized void start() {
        if (started) {
            return;
        }
        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(ready::countDown);
        await(ready);
        Platform.setImplicitExit(false);
        started = true;
    }

    public static void run(Runnable action) {
        start();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                completed.countDown();
            }
        });
        await(completed);
        if (failure.get() instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure.get() instanceof Error error) {
            throw error;
        }
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    public static <T> T call(Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        run(() -> result.set(action.get()));
        return result.get();
    }

    public static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("异步状态未在期限内到达");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError("测试等待被中断", failure);
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("JavaFX 测试调度超时");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("JavaFX 测试被中断", failure);
        }
    }
}
