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
import java.net.Socket;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

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
    void newLauncherCanStillWakeLegacyTwoLineEndpoint() throws Exception {
        Path lockPath = dataDir.resolve("javaclaw.instance.lock");
        Path endpointPath = dataDir.resolve("javaclaw.instance.endpoint");
        String token = "legacy-token";
        AtomicReference<String> command = new AtomicReference<>();
        try (FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock();
             ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            Files.writeString(endpointPath,
                    token + "\n" + server.getLocalPort() + "\n", StandardCharsets.UTF_8);
            Thread responder = new Thread(() -> respondAsLegacy(server, token, command));
            responder.start();

            assertNull(SingleInstanceCoordinator.acquire(dataDir, "new-build"));
            responder.join(2000);
            assertEquals("SHOW", command.get());
        }
    }

    private static void respondAsLegacy(
            ServerSocket server, String token, AtomicReference<String> command) {
        try (Socket socket = server.accept();
             BufferedReader reader = new BufferedReader(new InputStreamReader(
                     socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                     socket.getOutputStream(), StandardCharsets.UTF_8))) {
            assertEquals(token, reader.readLine());
            command.set(reader.readLine());
            writer.write("OK\n");
            writer.flush();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
