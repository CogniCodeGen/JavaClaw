package com.javaclaw.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

class SingleInstanceCoordinatorTest {

    @TempDir
    Path dataDir;

    @Test
    void secondInstanceSignalsPrimaryWithoutOpeningDatabase() throws Exception {
        CountDownLatch shown = new CountDownLatch(1);
        try (SingleInstanceCoordinator primary = SingleInstanceCoordinator.acquire(dataDir)) {
            assertNotNull(primary);
            primary.setShowHandler(shown::countDown);

            SingleInstanceCoordinator secondary = SingleInstanceCoordinator.acquire(dataDir);
            assertNull(secondary);
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                shown.await();
            });
            assertFalse(Files.exists(dataDir.resolve("javaclaw.mv.db")));
        }
    }

    @Test
    void earlyShowRequestIsDeliveredAfterWindowHandlerRegisters() throws Exception {
        CountDownLatch shown = new CountDownLatch(1);
        try (SingleInstanceCoordinator primary = SingleInstanceCoordinator.acquire(dataDir)) {
            assertNotNull(primary);
            assertNull(SingleInstanceCoordinator.acquire(dataDir));
            primary.setShowHandler(shown::countDown);
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                shown.await();
            });
        }
    }

    @Test
    void lockCanBeAcquiredAgainAfterPrimaryCloses() throws Exception {
        SingleInstanceCoordinator first = SingleInstanceCoordinator.acquire(dataDir);
        assertNotNull(first);
        first.close();
        try (SingleInstanceCoordinator next = SingleInstanceCoordinator.acquire(dataDir)) {
            assertNotNull(next);
        }
    }
}
