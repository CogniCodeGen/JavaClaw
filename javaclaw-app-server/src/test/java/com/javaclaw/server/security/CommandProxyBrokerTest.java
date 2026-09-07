package com.javaclaw.server.security;

import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandProxyBrokerTest {
    @Test
    void forwardsBothDirectionsWithHalfCloseAndPinsTheResolvedNumericAddress() throws Exception {
        try (var fixture = new CommandProxyFixture()) {
            var reply = fixture.echo();
            var lease = fixture.lease(100, 2, Duration.ofSeconds(5), () -> {});
            try (Socket client = fixture.connect(lease, "REPO.EXAMPLE:443")) {
                assertTrue(CommandProxyFixture.response(client).startsWith("HTTP/1.1 200 "));
                client.getOutputStream().write("ping".getBytes(StandardCharsets.US_ASCII));
                client.shutdownOutput();
                assertEquals("ping", new String(client.getInputStream().readAllBytes(), StandardCharsets.US_ASCII));
            }
            assertEquals("ping", reply.get(2, TimeUnit.SECONDS));
            assertEquals(8, lease.transferredBytes());
            assertEquals(
                    CommandProxyFixture.publicAddress(),
                    fixture.targets.element().getAddress());
            assertEquals(443, fixture.targets.element().getPort());
            CommandProxyFixture.await(() -> fixture.opened.stream().allMatch(Socket::isClosed));
        }
    }

    @Test
    void rejectsOtherPortsSiblingHostsAndSuffixTricksBeforeDnsOrConnect() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        try (var fixture = new CommandProxyFixture((host, deadline, token) -> {
            resolutions.incrementAndGet();
            return List.of(CommandProxyFixture.publicAddress());
        })) {
            var lease = fixture.lease(100, 2, Duration.ofSeconds(5), () -> {});
            for (String target : List.of(
                    "repo.example:444",
                    "other.example:443",
                    "repo.example.evil:443",
                    "sub.repo.example:443",
                    "127.0.0.1:443")) {
                try (Socket client = fixture.connect(lease, target)) {
                    assertTrue(CommandProxyFixture.response(client).startsWith("HTTP/1.1 403 "), target);
                    CommandProxyFixture.closed(client);
                }
            }
            assertEquals(0, resolutions.get());
            assertTrue(fixture.opened.isEmpty());
            assertTrue(lease.active());
        }
    }

    @Test
    void rejectsTheEntireDnsAnswerWhenAnyAddressIsNonPublic() throws Exception {
        for (String denied : List.of("127.0.0.1", "10.1.2.3", "169.254.169.254", "::1", "fc00::1", "fe80::1")) {
            try (var fixture = new CommandProxyFixture((host, deadline, token) ->
                    List.of(CommandProxyFixture.publicAddress(), InetAddress.getByName(denied)))) {
                var lease = fixture.lease(100, 2, Duration.ofSeconds(5), () -> {});
                try (Socket client = fixture.connect(lease, "repo.example:443")) {
                    assertTrue(CommandProxyFixture.response(client).startsWith("HTTP/1.1 403 "), denied);
                    CommandProxyFixture.closed(client);
                }
                assertTrue(fixture.opened.isEmpty());
            }
        }
    }

    @Test
    void resolvesEveryNewTunnelAndDoesNotReuseAFormerlyPublicDnsAnswer() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        try (var fixture = new CommandProxyFixture((host, deadline, token) -> List.of(
                lookups.incrementAndGet() == 1
                        ? CommandProxyFixture.publicAddress()
                        : InetAddress.getByName("127.0.0.1")))) {
            var lease = fixture.lease(100, 2, Duration.ofSeconds(5), () -> {});
            try (Socket first = fixture.connect(lease, "repo.example:443")) {
                assertTrue(CommandProxyFixture.response(first).startsWith("HTTP/1.1 200 "));
                try (Socket second = fixture.connect(lease, "repo.example:443")) {
                    assertTrue(CommandProxyFixture.response(second).startsWith("HTTP/1.1 403 "));
                }
            }
            assertEquals(2, lookups.get());
            assertEquals(1, fixture.targets.size());
        }
    }

    @Test
    void exceedingTheSharedBidirectionalByteBudgetClosesAllTunnels() throws Exception {
        try (var fixture = new CommandProxyFixture()) {
            var reply = fixture.echo();
            var lease = fixture.lease(6, 2, Duration.ofSeconds(5), () -> {});
            try (Socket client = fixture.connect(lease, "repo.example:443")) {
                assertTrue(CommandProxyFixture.response(client).startsWith("HTTP/1.1 200 "));
                client.getOutputStream().write("ping".getBytes(StandardCharsets.US_ASCII));
                client.shutdownOutput();
                CommandProxyFixture.closed(client);
            }
            assertEquals("ping", reply.get(2, TimeUnit.SECONDS));
            CommandProxyFixture.await(() -> !lease.active());
            assertEquals(4, lease.transferredBytes());
            assertTrue(fixture.opened.stream().allMatch(Socket::isClosed));
        }
    }

    @Test
    void connectionLimitRejectsConcurrentTunnelsWithoutRevokingTheLease() throws Exception {
        try (var fixture = new CommandProxyFixture()) {
            var lease = fixture.lease(100, 1, Duration.ofSeconds(5), () -> {});
            try (Socket first = fixture.connect(lease, "repo.example:443")) {
                assertTrue(CommandProxyFixture.response(first).startsWith("HTTP/1.1 200 "));
                try (Socket second = fixture.connect(lease, "repo.example:443")) {
                    CommandProxyFixture.closed(second);
                }
                assertEquals(1, fixture.opened.size());
                assertTrue(lease.active());
            }
            lease.close();
            assertFalse(lease.active());
        }
    }

    @Test
    void pendingRequestHeadersConsumeAConnectionSlot() throws Exception {
        try (var fixture = new CommandProxyFixture()) {
            var lease = fixture.lease(100, 1, Duration.ofSeconds(5), () -> {});
            try (Socket pending = new Socket()) {
                pending.connect(lease.endpoint());
                pending.getOutputStream().write("CONNECT".getBytes(StandardCharsets.US_ASCII));
                try (Socket second = fixture.connect(lease, "repo.example:443")) {
                    CommandProxyFixture.closed(second);
                }
                assertTrue(fixture.opened.isEmpty());
                assertTrue(lease.active());
            }
        }
    }

    @Test
    void rejectsMalformedConnectWithoutOpeningAnUpstream() throws Exception {
        try (var fixture = new CommandProxyFixture()) {
            var lease = fixture.lease(100, 2, Duration.ofSeconds(5), () -> {});
            try (Socket client = new Socket()) {
                client.connect(lease.endpoint());
                client.setSoTimeout(3_000);
                client.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                assertTrue(CommandProxyFixture.response(client).startsWith("HTTP/1.1 403 "));
            }
            assertTrue(fixture.opened.isEmpty());
        }
    }

    @Test
    void closesTheClientWhenEveryValidatedAddressFailsToConnect() throws Exception {
        try (var fixture = new CommandProxyFixture()) {
            fixture.server.close();
            var lease = fixture.lease(100, 2, Duration.ofSeconds(5), () -> {});
            try (Socket client = fixture.connect(lease, "repo.example:443")) {
                assertTrue(CommandProxyFixture.response(client).startsWith("HTTP/1.1 403 "));
                CommandProxyFixture.closed(client);
            }
            CommandProxyFixture.await(() -> fixture.opened.stream().allMatch(Socket::isClosed));
            assertEquals(0, lease.transferredBytes());
        }
    }
}
