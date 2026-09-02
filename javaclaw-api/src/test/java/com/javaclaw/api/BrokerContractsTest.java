package com.javaclaw.api;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrokerContractsTest {
    @Test
    void requestNormalizesMethodHeadersAndDefensivelyCopiesBody() {
        byte[] body = {1, 2};
        BrokerRequest request = new BrokerRequest(
                URI.create("https://example.com/a/../resource?q=1"),
                " post ",
                Map.of("X-Request", List.of("one")),
                body,
                1024,
                Duration.ofSeconds(3));
        body[0] = 9;

        assertEquals("POST", request.method());
        assertEquals(URI.create("https://example.com/resource?q=1"), request.uri());
        assertEquals(List.of("one"), request.headers().get("x-request"));
        assertArrayEquals(new byte[] {1, 2}, request.body());
        byte[] returned = request.body();
        returned[0] = 8;
        assertArrayEquals(new byte[] {1, 2}, request.body());
    }

    @Test
    void requestRejectsAmbiguousUrisMethodsHeadersAndBounds() {
        assertInvalid("ftp://example.com", "GET", Map.of());
        assertInvalid("https://user@example.com", "GET", Map.of());
        assertInvalid("https://example.com/path#fragment", "GET", Map.of());
        assertInvalid("https:///path", "GET", Map.of());
        assertInvalid("https://example.com", "TRACE", Map.of());
        assertInvalid("https://example.com", "GET", Map.of("bad name", List.of("x")));
        assertInvalid("https://example.com", "GET", Map.of("x", List.of("line\r\nbreak")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrokerRequest(
                        URI.create("https://example.com"), "GET", Map.of(), new byte[0], 0, Duration.ofSeconds(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrokerRequest(
                        URI.create("https://example.com"), "GET", Map.of(), new byte[0], 1, Duration.ZERO));
    }

    @Test
    void responseValidatesStatusAndDefensivelyCopiesBody() {
        byte[] body = {3};
        BrokerResponse response = new BrokerResponse(200, Map.of("content-type", List.of("text/plain")), body, false);
        body[0] = 4;

        assertArrayEquals(new byte[] {3}, response.body());
        assertThrows(IllegalArgumentException.class, () -> new BrokerResponse(99, Map.of(), new byte[0], false));
        assertThrows(NullPointerException.class, () -> new BrokerResponse(200, null, new byte[0], false));
    }

    private static void assertInvalid(String uri, String method, Map<String, List<String>> headers) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrokerRequest(URI.create(uri), method, headers, new byte[0], 1024, Duration.ofSeconds(1)));
    }
}
