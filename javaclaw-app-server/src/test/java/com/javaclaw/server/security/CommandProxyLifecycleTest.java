package com.javaclaw.server.security;

import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CancellationSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandProxyLifecycleTest {
    @Test
    void cancellationClosesEstablishedStreamsAndListenerWithoutMoreTraffic() throws Exception {
        try (var fixture = new CommandProxyFixture()) {
            var lease = fixture.lease(100, 2, Duration.ofSeconds(5), () -> {});
            var endpoint = lease.endpoint();
            try (Socket client = fixture.connect(lease, "repo.example:443");
                    Socket peer = fixture.server.accept()) {
                assertTrue(CommandProxyFixture.response(client).startsWith("HTTP/1.1 200 "));
                peer.setSoTimeout(3_000);
                fixture.cancellation.cancel("test cancellation");
                CommandProxyFixture.closed(client);
                CommandProxyFixture.closed(peer);
                CommandProxyFixture.await(() -> !lease.active());
                try (Socket late = new Socket()) {
                    assertThrows(IOException.class, () -> late.connect(endpoint, 500));
                }
            }
        }
    }

    @Test
    void revokedAuthorityAndWallClockDeadlineCloseEvenIdleTunnels() throws Exception {
        for (boolean revoke : List.of(true, false)) {
            AtomicBoolean authorized = new AtomicBoolean(true);
            try (var fixture = new CommandProxyFixture()) {
                var lease = fixture.lease(100, 2, Duration.ofSeconds(5), () -> {
                    if (!authorized.get()) {
                        throw new SecurityException("revoked");
                    }
                });
                try (Socket client = fixture.connect(lease, "repo.example:443")) {
                    assertTrue(CommandProxyFixture.response(client).startsWith("HTTP/1.1 200 "));
                    if (revoke) {
                        authorized.set(false);
                    } else {
                        fixture.clock.advance(Duration.ofSeconds(6));
                    }
                    CommandProxyFixture.closed(client);
                    CommandProxyFixture.await(() -> !lease.active());
                }
            }
        }
    }

    @Test
    void clockRollbackCannotExtendTheOriginalMonotonicLifetime() throws Exception {
        try (var fixture = new CommandProxyFixture()) {
            var lease = fixture.lease(100, 2, Duration.ofMillis(400), () -> {});
            fixture.clock.advance(Duration.ofDays(-1));
            CommandProxyFixture.await(() -> !lease.active());
        }
    }

    @Test
    void anIdleTunnelExpiresWithoutRevokingOtherFutureConnections() throws Exception {
        try (var fixture = new CommandProxyFixture()) {
            var initial = fixture.grant(100, 2, Duration.ofSeconds(5));
            var shortIdle = new CommandNetworkGrant(
                    initial.id(),
                    initial.turnId(),
                    initial.operationId(),
                    initial.commandDigest(),
                    initial.destinations(),
                    new CommandNetworkGrant.Limits(100, 2, Duration.ofMillis(100)),
                    initial.expiresAt());
            var lease = fixture.broker.open(shortIdle, fixture.cancellation, () -> {});
            try (Socket client = fixture.connect(lease, "repo.example:443")) {
                assertTrue(CommandProxyFixture.response(client).startsWith("HTTP/1.1 200 "));
                CommandProxyFixture.closed(client);
            }
            assertTrue(lease.active());
            CommandProxyFixture.await(() -> fixture.opened.stream().allMatch(Socket::isClosed));
        }
    }

    @Test
    void closeAlsoClosesASocketCreatedAfterTheCloseSnapshot() throws Exception {
        CountDownLatch creating = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        Socket lateSocket = new Socket();
        try (var fixture = new CommandProxyFixture();
                var broker = new CommandProxyBroker(
                        fixture.clock, (host, deadline, token) -> List.of(CommandProxyFixture.publicAddress()), () -> {
                            creating.countDown();
                            awaitLatch(resume);
                            return lateSocket;
                        })) {
            var lease = broker.open(fixture.grant(100, 2, Duration.ofSeconds(5)), fixture.cancellation, () -> {});
            try (Socket client = fixture.connect(lease, "repo.example:443")) {
                assertTrue(creating.await(2, TimeUnit.SECONDS));
                lease.close();
                resume.countDown();
                CommandProxyFixture.await(lateSocket::isClosed);
                CommandProxyFixture.closed(client);
            } finally {
                resume.countDown();
                lateSocket.close();
            }
        }
    }

    @Test
    void cancellationDuringDnsPreventsSubsequentTransportCreation() throws Exception {
        CountDownLatch resolving = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        try (var fixture = new CommandProxyFixture((host, deadline, token) -> {
            resolving.countDown();
            awaitLatch(resume);
            return List.of(CommandProxyFixture.publicAddress());
        })) {
            var lease = fixture.lease(100, 2, Duration.ofSeconds(5), () -> {});
            try (Socket client = fixture.connect(lease, "repo.example:443")) {
                assertTrue(resolving.await(2, TimeUnit.SECONDS));
                fixture.cancellation.cancel("cancel DNS");
                resume.countDown();
                String response = CommandProxyFixture.response(client);
                assertTrue(response.isEmpty() || response.startsWith("HTTP/1.1 403 "));
                CommandProxyFixture.closed(client);
                CommandProxyFixture.await(() -> !lease.active());
                assertTrue(fixture.opened.isEmpty());
            } finally {
                resume.countDown();
            }
        }
    }

    @Test
    void brokerCloseIsIdempotentAndRejectsNewOrAlreadyUnauthorizedLeases() throws Exception {
        try (var fixture = new CommandProxyFixture()) {
            assertThrows(
                    IOException.class,
                    () -> fixture.lease(100, 1, Duration.ofSeconds(5), () -> {
                        throw new SecurityException("denied");
                    }));
            CancellationSource cancelled = new CancellationSource();
            cancelled.cancel("already cancelled");
            assertThrows(
                    IOException.class,
                    () -> fixture.broker.open(fixture.grant(100, 1, Duration.ofSeconds(5)), cancelled, () -> {}));
            var first = fixture.lease(100, 1, Duration.ofSeconds(5), () -> {});
            var second = fixture.lease(100, 1, Duration.ofSeconds(5), () -> {});
            fixture.broker.close();
            fixture.broker.close();
            assertFalse(first.active());
            assertFalse(second.active());
            assertThrows(IllegalStateException.class, () -> fixture.lease(100, 1, Duration.ofSeconds(5), () -> {}));
        }
    }

    private static void awaitLatch(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                throw new IOException("test latch timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
        }
    }
}
