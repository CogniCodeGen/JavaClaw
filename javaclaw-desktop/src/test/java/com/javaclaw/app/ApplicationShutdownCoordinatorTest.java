package com.javaclaw.app;

import com.javaclaw.platform.fx.FxDispatcher;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationShutdownCoordinatorTest {

    @Test
    void dispatchesUiCleanupToFxBeforeClosingBackendAndRunsEachPhaseOnce() {
        AtomicBoolean onFxThread = new AtomicBoolean(false);
        List<String> events = new ArrayList<>();
        FxDispatcher fx = new FxDispatcher(onFxThread::get, action -> {
            onFxThread.set(true);
            try {
                action.run();
            } finally {
                onFxThread.set(false);
            }
        });
        ApplicationShutdownCoordinator coordinator = new ApplicationShutdownCoordinator(fx);

        coordinator.closeUi(() -> {
            assertTrue(onFxThread.get());
            events.add("ui");
        }).join();
        coordinator.closeUi(() -> events.add("duplicate-ui")).join();
        coordinator.closeBackend(() -> events.add("backend"));
        coordinator.closeBackend(() -> events.add("duplicate-backend"));

        assertEquals(List.of("ui", "backend"), events);
    }

    @Test
    void refusesBackendCleanupUntilQueuedUiCleanupCompletes() {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        FxDispatcher fx = new FxDispatcher(() -> false, queued::set);
        ApplicationShutdownCoordinator coordinator = new ApplicationShutdownCoordinator(fx);
        List<String> events = new ArrayList<>();

        coordinator.closeUi(() -> events.add("ui"));
        assertThrows(IllegalStateException.class,
                () -> coordinator.closeBackend(() -> events.add("early-backend")));

        queued.get().run();
        coordinator.closeBackend(() -> events.add("backend"));
        assertEquals(List.of("ui", "backend"), events);
    }

    @Test
    void doesNotCloseBackendAfterUiCleanupFailure() {
        FxDispatcher fx = new FxDispatcher(() -> true, Runnable::run);
        ApplicationShutdownCoordinator coordinator = new ApplicationShutdownCoordinator(fx);

        assertThrows(RuntimeException.class, () -> coordinator.closeUi(() -> {
            throw new IllegalStateException("context menu close failed");
        }).join());
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> coordinator.closeBackend(() -> { }));
        assertEquals("JavaFX 视图清理失败", failure.getMessage());
    }

    @Test
    void processExitClosesBackendEvenWhenFxNeverRunsQueuedCleanup() {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        FxDispatcher fx = new FxDispatcher(() -> false, queued::set);
        ApplicationShutdownCoordinator coordinator = new ApplicationShutdownCoordinator(fx);
        List<String> events = new ArrayList<>();

        coordinator.closeUi(() -> events.add("ui"));
        coordinator.closeBackendForProcessExit(() -> events.add("backend"));
        coordinator.closeBackendForProcessExit(() -> events.add("duplicate-backend"));

        assertEquals(List.of("backend"), events);
    }

    @Test
    void duplicateProcessExitWaitsForInProgressBackendClose() throws Exception {
        FxDispatcher fx = new FxDispatcher(() -> true, Runnable::run);
        ApplicationShutdownCoordinator coordinator = new ApplicationShutdownCoordinator(fx);
        coordinator.closeUi(() -> { }).join();
        CountDownLatch backendStarted = new CountDownLatch(1);
        CountDownLatch duplicateStarted = new CountDownLatch(1);
        CountDownLatch releaseBackend = new CountDownLatch(1);
        AtomicBoolean duplicateReturned = new AtomicBoolean();

        CompletableFuture<Void> first = CompletableFuture.runAsync(() ->
                coordinator.closeBackend(() -> {
                    backendStarted.countDown();
                    try {
                        releaseBackend.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                }));
        assertTrue(backendStarted.await(2, TimeUnit.SECONDS));
        CompletableFuture<Void> duplicate = CompletableFuture.runAsync(() -> {
            duplicateStarted.countDown();
            coordinator.closeBackendForProcessExit(() -> { });
            duplicateReturned.set(true);
        });

        try {
            assertTrue(duplicateStarted.await(2, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> duplicate.get(100, TimeUnit.MILLISECONDS));
            assertFalse(duplicateReturned.get());
        } finally {
            releaseBackend.countDown();
        }
        first.get(2, TimeUnit.SECONDS);
        duplicate.get(2, TimeUnit.SECONDS);
        assertTrue(duplicateReturned.get());
    }
}
