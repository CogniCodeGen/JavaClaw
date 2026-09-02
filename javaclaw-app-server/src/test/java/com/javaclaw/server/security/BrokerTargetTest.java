package com.javaclaw.server.security;

import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.NetworkPermission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrokerTargetTest {
    private static final InetAddress PUBLIC = address("8.8.8.8");

    @Test
    void resolveNormalizesDefaultPortsPathsQueriesAndDuplicateAddresses() throws Exception {
        BrokerTarget https = resolve(
                "https://EXAMPLE.com",
                new NetworkPermission(Set.of("example.com"), Set.of(443), true),
                List.of(PUBLIC, PUBLIC));
        BrokerTarget http = resolve(
                "http://example.com:8080/path?q=1",
                new NetworkPermission(Set.of("example.com"), Set.of(8080), false),
                List.of(PUBLIC));

        assertTrue(https.tls());
        assertEquals(443, https.port());
        assertEquals("/", https.requestTarget());
        assertEquals("example.com", https.hostHeader());
        assertEquals(1, https.addresses().size());
        assertFalse(http.tls());
        assertEquals("/path?q=1", http.requestTarget());
        assertEquals("example.com:8080", http.hostHeader());
    }

    @Test
    void hostHeaderBracketsIpv6AndOmitsOnlyDefaultPorts() {
        BrokerTarget defaultIpv6 = new BrokerTarget(
                URI.create("https://[2001:4860:4860::8888]/"), "2001:4860:4860::8888", 443, true, List.of(PUBLIC));
        BrokerTarget customIpv6 = new BrokerTarget(
                URI.create("http://[2001:4860:4860::8888]:8080/"),
                "2001:4860:4860::8888",
                8080,
                false,
                List.of(PUBLIC));

        assertEquals("[2001:4860:4860::8888]", defaultIpv6.hostHeader());
        assertEquals("[2001:4860:4860::8888]:8080", customIpv6.hostHeader());
        assertEquals(
                "example.com",
                new BrokerTarget(URI.create("http://example.com"), "example.com", 80, false, List.of(PUBLIC))
                        .hostHeader());
    }

    @Test
    void resolveUnwrapsIpv6LiteralAndBuildsQueryOnlyTarget() throws Exception {
        InetAddress globalIpv6 = address("2001:4860:4860::8888");
        BrokerTarget target = resolve(
                "https://[2001:4860:4860::8888]?page=1",
                new NetworkPermission(Set.of("2001:4860:4860::8888"), Set.of(443), true),
                List.of(globalIpv6));

        assertEquals("2001:4860:4860::8888", target.host());
        assertEquals("/?page=1", target.requestTarget());
        assertEquals("[2001:4860:4860::8888]", target.hostHeader());
    }

    @Test
    void systemResolverReturnsAddressesAndPropagatesDnsFailureAndCancellation() throws Exception {
        BrokerTarget.SystemHostResolver resolver = new BrokerTarget.SystemHostResolver();
        CancellationSource active = new CancellationSource();

        assertFalse(resolver.resolve("localhost", Duration.ofSeconds(1), active).isEmpty());
        assertThrows(Exception.class, () -> resolver.resolve("\u0000", Duration.ofSeconds(1), active));

        CancellationSource cancelled = new CancellationSource();
        cancelled.cancel("stop");
        assertThrows(Exception.class, () -> resolver.resolve("localhost", Duration.ofSeconds(1), cancelled));
    }

    @Test
    void systemResolverBoundsSlowLookupAndWrapsUnexpectedFailure() {
        BrokerTarget.SystemHostResolver slow = new BrokerTarget.SystemHostResolver(host -> {
            try {
                Thread.sleep(Duration.ofSeconds(10));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new java.io.InterruptedIOException("lookup interrupted");
            }
            return new InetAddress[] {PUBLIC};
        });
        BrokerTarget.SystemHostResolver broken = new BrokerTarget.SystemHostResolver(host -> {
            throw new IllegalStateException("broken resolver");
        });

        assertThrows(
                SocketTimeoutException.class,
                () -> slow.resolve("example.com", Duration.ZERO, new CancellationSource()));
        assertThrows(
                SocketTimeoutException.class,
                () -> slow.resolve("example.com", Duration.ofMillis(3), new CancellationSource()));
        assertThrows(
                SocketTimeoutException.class,
                () -> slow.resolve("example.com", Duration.ofMillis(101), new CancellationSource()));
        Exception failure = assertThrows(
                Exception.class, () -> broken.resolve("example.com", Duration.ofSeconds(1), new CancellationSource()));
        assertTrue(failure.getCause() instanceof IllegalStateException);
    }

    @Test
    void resolveRejectsAmbiguousUrisPermissionsAndDnsResults() {
        NetworkPermission https = new NetworkPermission(Set.of("example.com"), Set.of(443), true);

        assertRejected("ftp://example.com", https, List.of(PUBLIC));
        assertRejected("https://user@example.com", https, List.of(PUBLIC));
        assertRejected("https://example.com/path#fragment", https, List.of(PUBLIC));
        assertRejected("https:///path", https, List.of(PUBLIC));
        assertRejected(
                "https://example.com", new NetworkPermission(Set.of("other.com"), Set.of(443), true), List.of(PUBLIC));
        assertRejected(
                "https://example.com", new NetworkPermission(Set.of("example.com"), Set.of(80), true), List.of(PUBLIC));
        assertRejected(
                "http://example.com", new NetworkPermission(Set.of("example.com"), Set.of(80), true), List.of(PUBLIC));
        assertRejected(
                "https://example.com",
                new NetworkPermission(Set.of("example.com"), Set.of(NetworkPermission.ANY_PORT), true),
                List.of(PUBLIC));
        assertRejected("https://example.com", https, List.of());
        assertRejected("https://example.com", https, List.of(address("127.0.0.1")));
    }

    @Test
    void resolveRejectsDnsExpansionBeyondThePinnedLimit() {
        ArrayList<InetAddress> addresses = new ArrayList<>();
        for (int value = 1; value <= 33; value++) {
            addresses.add(address("8.8.8." + value));
        }

        assertRejected(
                "https://example.com", new NetworkPermission(Set.of("example.com"), Set.of(443), true), addresses);
    }

    @Test
    void constructorRejectsInvalidPortAndEmptyPinnedSet() {
        URI uri = URI.create("https://example.com");
        assertThrows(
                IllegalArgumentException.class, () -> new BrokerTarget(uri, "example.com", 0, true, List.of(PUBLIC)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrokerTarget(uri, "example.com", 65_536, true, List.of(PUBLIC)));
        assertThrows(IllegalArgumentException.class, () -> new BrokerTarget(uri, "example.com", 443, true, List.of()));
    }

    @Test
    void privateGrantPinsMixedAddressesAndBuildsExactIpv4AndIpv6Origins() throws Exception {
        AtomicReference<URI> ipv4Origin = new AtomicReference<>();
        AtomicReference<Set<String>> ipv4Addresses = new AtomicReference<>();
        BrokerTarget ipv4 = BrokerTarget.resolve(
                URI.create("https://example.com:8443/private"),
                new NetworkPermission(Set.of("example.com"), Set.of(8443), true),
                (host, timeout, cancellation) -> List.of(PUBLIC, address("10.0.0.8")),
                BrokerDeadline.start(Duration.ofSeconds(1)),
                new CancellationSource(),
                (origin, addresses) -> {
                    ipv4Origin.set(origin);
                    ipv4Addresses.set(addresses);
                });

        AtomicReference<URI> ipv6Origin = new AtomicReference<>();
        BrokerTarget ipv6 = BrokerTarget.resolve(
                URI.create("https://[fd00::8]:9443/private"),
                new NetworkPermission(Set.of("fd00::8"), Set.of(9443), true),
                (host, timeout, cancellation) -> List.of(address("fd00::8")),
                BrokerDeadline.start(Duration.ofSeconds(1)),
                new CancellationSource(),
                (origin, addresses) -> ipv6Origin.set(origin));

        assertEquals(URI.create("https://example.com:8443"), ipv4Origin.get());
        assertEquals(Set.of("8.8.8.8", "10.0.0.8"), ipv4Addresses.get());
        assertEquals(URI.create("https://[fd00::8]:9443"), ipv6Origin.get());
        assertTrue(ipv4.tls());
        assertEquals("[fd00::8]:9443", ipv6.hostHeader());
        assertEquals(
                "/",
                new BrokerTarget(URI.create("http:opaque"), "example.com", 80, false, List.of(PUBLIC)).requestTarget());
    }

    private static BrokerTarget resolve(String uri, NetworkPermission permission, List<InetAddress> addresses)
            throws Exception {
        return BrokerTarget.resolve(
                URI.create(uri),
                permission,
                (host, timeout, cancellation) -> addresses,
                BrokerDeadline.start(Duration.ofSeconds(1)),
                new CancellationSource());
    }

    private static void assertRejected(String uri, NetworkPermission permission, List<InetAddress> addresses) {
        assertThrows(Exception.class, () -> resolve(uri, permission, addresses));
    }

    private static InetAddress address(String value) {
        try {
            return InetAddress.getByName(value);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
