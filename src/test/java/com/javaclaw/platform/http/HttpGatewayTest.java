package com.javaclaw.platform.http;

import com.javaclaw.platform.execution.ManagedTaskExecutor;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpGatewayTest {

    @Test
    void retriesRetryableIdempotentResponse() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        try (ManagedTaskExecutor executor = new ManagedTaskExecutor()) {
            HttpGateway gateway = new HttpGateway(executor, request -> {
                int attempt = attempts.incrementAndGet();
                return response(request.uri(), attempt < 3 ? 503 : 200, "attempt-" + attempt);
            });
            HttpRetryPolicy policy = new HttpRetryPolicy(
                    3, Duration.ZERO, Duration.ZERO, Set.of(503));

            HttpResult result = gateway.sendAndWait("retry-get",
                    () -> HttpRequest.newBuilder(URI.create("https://example.test/data"))
                            .timeout(Duration.ofSeconds(1)).GET().build(), policy);

            assertEquals(3, attempts.get());
            assertEquals(200, result.statusCode());
            assertEquals("attempt-3", result.bodyText());
            assertTrue(result.isSuccessful());
        }
    }

    @Test
    void retriesIoFailureForGet() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        try (ManagedTaskExecutor executor = new ManagedTaskExecutor()) {
            HttpGateway gateway = new HttpGateway(executor, request -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new IOException("temporary");
                }
                return response(request.uri(), 204, "");
            });

            HttpResult result = gateway.sendAndWait("io-retry",
                    () -> HttpRequest.newBuilder(URI.create("https://example.test/data"))
                            .GET().build(),
                    new HttpRetryPolicy(2, Duration.ZERO, Duration.ZERO, Set.of()));

            assertEquals(2, attempts.get());
            assertEquals(204, result.statusCode());
        }
    }

    @Test
    void rejectsAutomaticRetryForPostBeforeSending() {
        AtomicInteger sends = new AtomicInteger();
        try (ManagedTaskExecutor executor = new ManagedTaskExecutor()) {
            HttpGateway gateway = new HttpGateway(executor, request -> {
                sends.incrementAndGet();
                return response(request.uri(), 200, "ok");
            });

            assertThrows(IllegalArgumentException.class, () -> gateway.send("unsafe-retry",
                    () -> HttpRequest.newBuilder(URI.create("https://example.test/write"))
                            .POST(HttpRequest.BodyPublishers.ofString("value")).build(),
                    new HttpRetryPolicy(2, Duration.ZERO, Duration.ZERO, Set.of(503))));
            assertEquals(0, sends.get());
        }
    }

    private static HttpResult response(URI uri, int status, String body) {
        return new HttpResult(uri, status,
                HttpHeaders.of(Map.of(), (name, value) -> true),
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
