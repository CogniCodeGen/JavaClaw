package com.javaclaw.app;

import com.javaclaw.platform.fx.FxDispatcher;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrayCloseCoordinatorTest {

    @Test
    void hidesOnlyAfterSuccessfulLiveTrayCheckAndCoalescesDuplicateCloseEvents() {
        TrayCloseCoordinator coordinator = new TrayCloseCoordinator(directFx(), 500);
        CompletableFuture<Boolean> availability = new CompletableFuture<>();
        AtomicInteger hidden = new AtomicInteger();
        AtomicInteger exited = new AtomicInteger();

        coordinator.request(() -> availability, () -> true,
                hidden::incrementAndGet, exited::incrementAndGet, ignored -> { });
        coordinator.request(() -> CompletableFuture.completedFuture(false), () -> false,
                hidden::incrementAndGet, exited::incrementAndGet, ignored -> { });
        assertTrue(coordinator.isPending());

        availability.complete(true);

        assertFalse(coordinator.isPending());
        assertEquals(1, hidden.get());
        assertEquals(0, exited.get());
    }

    @Test
    void failedOrStaleRegistrationPerformsFullExitInsteadOfHiding() {
        TrayCloseCoordinator coordinator = new TrayCloseCoordinator(directFx(), 500);
        AtomicInteger hidden = new AtomicInteger();
        AtomicInteger exited = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        coordinator.request(() -> CompletableFuture.completedFuture(true), () -> false,
                hidden::incrementAndGet, exited::incrementAndGet,
                ignored -> failures.incrementAndGet());

        assertEquals(0, hidden.get());
        assertEquals(1, exited.get());
        assertEquals(1, failures.get());
    }

    @Test
    void timeoutPerformsFullExitWithoutBlockingCaller() throws Exception {
        TrayCloseCoordinator coordinator = new TrayCloseCoordinator(directFx(), 20);
        CountDownLatch exited = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();

        coordinator.request(CompletableFuture<Boolean>::new, () -> true,
                () -> { }, exited::countDown, ignored -> failures.incrementAndGet());

        assertTrue(exited.await(1, TimeUnit.SECONDS));
        assertEquals(1, failures.get());
        assertFalse(coordinator.isPending());
    }

    @Test
    void synchronousTrayCheckFailureAlsoPerformsFullExit() {
        TrayCloseCoordinator coordinator = new TrayCloseCoordinator(directFx(), 500);
        AtomicInteger exited = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        coordinator.request(() -> { throw new IllegalStateException("broken tray"); }, () -> true,
                () -> { }, exited::incrementAndGet, ignored -> failures.incrementAndGet());

        assertEquals(1, exited.get());
        assertEquals(1, failures.get());
    }

    private static FxDispatcher directFx() {
        return new FxDispatcher(() -> true, Runnable::run);
    }
}
