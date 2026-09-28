package com.javaclaw.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

class SingleInstanceCoordinatorTest {

    @TempDir
    Path dataDir;

    @Test
    void buildFingerprintIsRequired() {
        assertThrows(IllegalArgumentException.class,
                () -> SingleInstanceCoordinator.acquire(dataDir, " "));
    }

    @Test
    void secondInstanceSignalsPrimaryWithoutOpeningDatabase() throws Exception {
        CountDownLatch shown = new CountDownLatch(1);
        try (SingleInstanceCoordinator primary =
                     SingleInstanceCoordinator.acquire(dataDir, "test-build")) {
            assertNotNull(primary);
            primary.setShowHandler(shown::countDown);

            SingleInstanceCoordinator secondary =
                    SingleInstanceCoordinator.acquire(dataDir, "test-build");
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
        try (SingleInstanceCoordinator primary =
                     SingleInstanceCoordinator.acquire(dataDir, "test-build")) {
            assertNotNull(primary);
            assertNull(SingleInstanceCoordinator.acquire(dataDir, "test-build"));
            primary.setShowHandler(shown::countDown);
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                shown.await();
            });
        }
    }

    @Test
    void lockCanBeAcquiredAgainAfterPrimaryCloses() throws Exception {
        SingleInstanceCoordinator first = SingleInstanceCoordinator.acquire(dataDir, "test-build");
        assertNotNull(first);
        first.close();
        try (SingleInstanceCoordinator next =
                     SingleInstanceCoordinator.acquire(dataDir, "test-build")) {
            assertNotNull(next);
        }
    }

    @Test
    void differentBuildNotifiesPrimaryToRequestManualRestart() throws Exception {
        CountDownLatch mismatch = new CountDownLatch(1);
        CountDownLatch shown = new CountDownLatch(1);
        try (SingleInstanceCoordinator primary =
                     SingleInstanceCoordinator.acquire(dataDir, "build-a")) {
            assertNotNull(primary);
            primary.setShowHandler(shown::countDown);
            primary.setBuildMismatchHandler(mismatch::countDown);

            assertNull(SingleInstanceCoordinator.acquire(dataDir, "build-b"));
            assertTrue(mismatch.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1L, shown.getCount(), "不同构建不得伪装成普通窗口唤起");
        }
    }

    @Test
    void sameBuildStillUsesNormalShowNotification() throws Exception {
        CountDownLatch shown = new CountDownLatch(1);
        CountDownLatch mismatch = new CountDownLatch(1);
        try (SingleInstanceCoordinator primary =
                     SingleInstanceCoordinator.acquire(dataDir, "same-build")) {
            assertNotNull(primary);
            primary.setShowHandler(shown::countDown);
            primary.setBuildMismatchHandler(mismatch::countDown);

            assertNull(SingleInstanceCoordinator.acquire(dataDir, "same-build"));
            assertTrue(shown.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1L, mismatch.getCount());
        }
    }

    @Test
    void buildMismatchBeforeWindowRegistrationIsDeliveredLater() throws Exception {
        CountDownLatch mismatch = new CountDownLatch(1);
        try (SingleInstanceCoordinator primary =
                     SingleInstanceCoordinator.acquire(dataDir, "old-build")) {
            assertNotNull(primary);
            assertNull(SingleInstanceCoordinator.acquire(dataDir, "new-build"));
            primary.setBuildMismatchHandler(mismatch::countDown);
            assertTrue(mismatch.await(2, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test
    void twoLineEndpointIsNotAccepted() throws Exception {
        Path lockPath = dataDir.resolve("javaclaw.instance.lock");
        Path endpointPath = dataDir.resolve("javaclaw.instance.endpoint");
        String token = "outdated-token";
        try (FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock();
             ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            Files.writeString(endpointPath,
                    token + "\n" + server.getLocalPort() + "\n", StandardCharsets.UTF_8);
            assertNull(SingleInstanceCoordinator.acquire(dataDir, "current-build"));
            server.setSoTimeout(100);
            assertThrows(SocketTimeoutException.class, server::accept);
        }
    }
}
