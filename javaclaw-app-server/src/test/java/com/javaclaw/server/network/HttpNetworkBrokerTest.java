package com.javaclaw.server.network;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.sandbox.api.BrokerRequest;
import com.javaclaw.sandbox.api.NetworkPolicy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpNetworkBrokerTest {
    @Test
    void rejectsPrivateLinkLocalLoopbackAndCgnatTargets() throws Exception {
        for (String address : Set.of(
                "127.0.0.1",
                "10.0.0.1",
                "172.16.1.1",
                "192.168.1.1",
                "169.254.169.254",
                "100.64.0.1",
                "::1",
                "fc00::1")) {
            assertTrue(HttpNetworkBroker.blocked(InetAddress.getByName(address)), address);
        }
    }

    @Test
    void validatesAllowlistBeforeOpeningAnySocket() {
        BrokerRequest request = new BrokerRequest(
                "GET", URI.create("http://127.0.0.1:9/"), Map.of(), new byte[0], Duration.ofSeconds(1), 1024, 0);
        assertThrows(
                IOException.class,
                () -> new HttpNetworkBroker()
                        .execute(request, new NetworkPolicy(NetworkPolicy.Mode.ALLOWLIST, Set.of("127.0.0.1:9"))));
        assertThrows(
                IOException.class,
                () -> new HttpNetworkBroker()
                        .execute(request, new NetworkPolicy(NetworkPolicy.Mode.ALLOWLIST, Set.of("example.com"))));
    }

    @Test
    void rejectsAmbiguousResponseFraming() {
        assertThrows(
                IOException.class,
                () -> HttpNetworkBroker.parseContentLength(Map.of("content-length", List.of("1", "2"))));
        assertThrows(
                IOException.class,
                () -> HttpNetworkBroker.parseContentLength(Map.of("content-length", List.of("1, 2"))));
        assertThrows(
                IOException.class,
                () -> HttpNetworkBroker.transferEncoding(Map.of("transfer-encoding", List.of("gzip, chunked"))));
        assertThrows(
                IOException.class,
                () -> HttpNetworkBroker.transferEncoding(Map.of("transfer-encoding", List.of("chunked", "chunked"))));
    }

    @Test
    void enforcesTlsAndRefusesCredentialRedirectsAcrossOrigins() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrokerRequest(
                        "GET",
                        URI.create("http://example.com"),
                        Map.of(),
                        new byte[0],
                        Duration.ofSeconds(1),
                        1024,
                        1,
                        true));
        assertTrue(HttpNetworkBroker.sameOrigin(
                URI.create("https://example.com/a"), URI.create("https://example.com:443/b")));
        assertFalse(HttpNetworkBroker.sameOrigin(
                URI.create("https://example.com/a"), URI.create("https://auth.example.com/b")));
        assertTrue(HttpNetworkBroker.hasSensitiveHeaders(Map.of("Authorization", "Bearer redacted")));
        assertFalse(HttpNetworkBroker.hasSensitiveHeaders(Map.of("Accept", "application/json")));
    }
}
