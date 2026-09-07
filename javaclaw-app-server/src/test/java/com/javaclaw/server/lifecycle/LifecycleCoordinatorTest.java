package com.javaclaw.server.lifecycle;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.LifecycleLeaseRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LifecycleCoordinatorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void serverStartsIdleCountdownWithoutSyntheticClient() throws Exception {
        try (LifecycleCoordinator coordinator = coordinator(Duration.ofMillis(30))) {
            assertTrue(awaitShutdown(coordinator, Duration.ofSeconds(1)));
        }
    }

    @Test
    void connectedClientPreventsIdleShutdownUntilFullDelayAfterDisconnect() throws Exception {
        try (LifecycleCoordinator coordinator = coordinator(Duration.ofMillis(40))) {
            LifecycleCoordinator.Lease client = coordinator.clientConnected();
            Thread.sleep(70);
            assertFalse(coordinator.shutdownRequested());

            client.close();

            assertTrue(awaitShutdown(coordinator, Duration.ofSeconds(1)));
        }
    }

    @Test
    void activityLeaseSurvivesClientDisconnectAndReleaseRestartsDelay() throws Exception {
        try (LifecycleCoordinator coordinator = coordinator(Duration.ofMillis(35))) {
            LifecycleCoordinator.Lease client = coordinator.clientConnected();
            LifecycleCoordinator.Lease activity =
                    coordinator.acquireActivity("turn:one", "TURN", Duration.ofMinutes(1));
            client.close();
            Thread.sleep(70);
            assertFalse(coordinator.shutdownRequested());

            activity.close();

            assertTrue(awaitShutdown(coordinator, Duration.ofSeconds(1)));
        }
    }

    @Test
    void expiredCrashLeaseIsPurgedBeforeIdleShutdown() throws Exception {
        H2Database database = database();
        LifecycleLeaseRepository repository = new LifecycleLeaseRepository(database, Clock.systemUTC());
        repository.acquire("stale-turn", "TURN", Duration.ofMillis(20));
        Thread.sleep(30);
        try (LifecycleCoordinator coordinator = new LifecycleCoordinator(repository, Duration.ofMillis(30))) {
            coordinator.clientConnected().close();

            assertTrue(awaitShutdown(coordinator, Duration.ofSeconds(1)));
        }
    }

    @Test
    void statusAndLeaseCloseAreIdempotentAndClosedCoordinatorRejectsNewWork() {
        LifecycleCoordinator coordinator = coordinator(Duration.ofSeconds(1));
        LifecycleCoordinator.Lease client = coordinator.clientConnected();
        LifecycleCoordinator.Lease activity = coordinator.acquireActivity("turn:status", "TURN", Duration.ofMinutes(1));

        assertEquals(new LifecycleCoordinator.Status(1, 1), coordinator.status());
        client.close();
        client.close();
        activity.close();
        activity.close();
        assertEquals(new LifecycleCoordinator.Status(0, 0), coordinator.status());

        coordinator.close();
        coordinator.close();
        assertTrue(coordinator.shutdownRequested());
        assertThrows(IllegalStateException.class, coordinator::clientConnected);
        assertThrows(
                IllegalStateException.class,
                () -> coordinator.acquireActivity("turn:closed", "TURN", Duration.ofMinutes(1)));
        assertThrows(
                IllegalStateException.class,
                () -> coordinator.acquirePersistentActivity("schedule:closed", "SCHEDULE"));
    }

    @Test
    void persistentActivityAndRepositoryRenewalHaveExplicitOwnership() {
        H2Database database = database();
        LifecycleLeaseRepository repository = new LifecycleLeaseRepository(database, Clock.systemUTC());
        try (LifecycleCoordinator coordinator = new LifecycleCoordinator(repository, Duration.ofSeconds(1))) {
            LifecycleCoordinator.Lease persistent = coordinator.acquirePersistentActivity("schedule:one", "SCHEDULE");
            assertEquals(1, coordinator.status().activeLeases());
            persistent.close();
            persistent.close();
            assertEquals(0, coordinator.status().activeLeases());
        }

        UUID lease = repository.acquire("owner", "TEST", Duration.ofMinutes(1));
        assertTrue(repository.renew(lease, Duration.ofMinutes(2)));
        repository.release(lease);
        repository.release(lease);
        assertFalse(repository.renew(lease, Duration.ofMinutes(1)));
    }

    @Test
    void controlledShutdownRejectsLeasesAndAdditionalClients() {
        try (LifecycleCoordinator coordinator = coordinator(Duration.ofSeconds(1))) {
            LifecycleCoordinator.ShutdownDecision withoutControl = coordinator.requestControlledShutdown();
            assertFalse(withoutControl.accepted());
            assertEquals("未检测到托盘控制连接", withoutControl.reason());
        }

        try (LifecycleCoordinator coordinator = coordinator(Duration.ofSeconds(1));
                LifecycleCoordinator.Lease control = coordinator.clientConnected();
                LifecycleCoordinator.Lease desktop = coordinator.clientConnected();
                LifecycleCoordinator.Lease activity =
                        coordinator.acquireActivity("turn:one", "TURN", Duration.ofMinutes(1))) {
            LifecycleCoordinator.ShutdownDecision withLease = coordinator.requestControlledShutdown();

            assertFalse(withLease.accepted());
            assertEquals("存在活动 Turn、交互或 Schedule lease", withLease.reason());
            activity.close();

            LifecycleCoordinator.ShutdownDecision withDesktop = coordinator.requestControlledShutdown();

            assertFalse(withDesktop.accepted());
            assertEquals("仍有其他客户端连接 App Server", withDesktop.reason());
        }
    }

    @Test
    void controlledShutdownAllowsOnlyControlConnectionAndFlushesResponse() throws Exception {
        try (LifecycleCoordinator coordinator = coordinator(Duration.ofSeconds(1));
                LifecycleCoordinator.Lease ignored = coordinator.clientConnected()) {
            LifecycleCoordinator.ShutdownDecision decision = coordinator.requestControlledShutdown();

            assertTrue(decision.accepted());
            assertFalse(coordinator.shutdownRequested());
            assertThrows(IllegalStateException.class, coordinator::clientConnected);
            assertThrows(
                    IllegalStateException.class,
                    () -> coordinator.acquireActivity("turn:late", "TURN", Duration.ofMinutes(1)));
            assertEquals(0, coordinator.status().activeLeases());
            assertFalse(coordinator.requestControlledShutdown().accepted());
            assertTrue(awaitShutdown(coordinator, Duration.ofSeconds(1)));
        }
    }

    @Test
    void invalidDurationsNamesAndCountsAreRejected() {
        LifecycleLeaseRepository repository = new LifecycleLeaseRepository(database(), Clock.systemUTC());

        assertThrows(IllegalArgumentException.class, () -> new LifecycleCoordinator(repository, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new LifecycleCoordinator(repository, Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class, () -> repository.acquire(" ", "TURN", Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> repository.acquire("owner", " ", Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> repository.acquire("owner", "TURN", Duration.ZERO));
        assertThrows(NullPointerException.class, () -> repository.renew(null, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class, () -> repository.release(null));
        assertThrows(IllegalArgumentException.class, () -> new LifecycleCoordinator.Status(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new LifecycleCoordinator.Status(0, -1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LifecycleCoordinator.ShutdownDecision(true, new LifecycleCoordinator.Status(0, 0), "原因"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LifecycleCoordinator.ShutdownDecision(false, new LifecycleCoordinator.Status(0, 0), ""));
    }

    private LifecycleCoordinator coordinator(Duration idleDelay) {
        return new LifecycleCoordinator(new LifecycleLeaseRepository(database(), Clock.systemUTC()), idleDelay);
    }

    private H2Database database() {
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        return database;
    }

    private boolean awaitShutdown(LifecycleCoordinator coordinator, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!coordinator.shutdownRequested() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        return coordinator.shutdownRequested();
    }
}
