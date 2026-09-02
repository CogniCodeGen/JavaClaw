package com.javaclaw.server.security;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.BrokerRequest;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.TurnCancelledException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PinnedHttpNetworkBrokerTest {
    private static final List<InetAddress> PUBLIC = addresses("93.184.216.34");

    @Test
    void sendsPinnedRequestAndFiltersCookieAndHopByHopResponseHeaders() throws Exception {
        FakeTransport transport = new FakeTransport(
                response(
                        "HTTP/1.1 200 OK\r\nContent-Length: 5\r\nSet-Cookie: secret=1\r\nConnection: close\r\nX-Test: yes\r\n\r\nhello"));
        PinnedHttpNetworkBroker broker = broker(transport, Map.of("example.com", PUBLIC));

        var result = broker.exchange(
                request("https://example.com/resource?q=1", "GET", Map.of("x-client", List.of("sdk")), new byte[0], 32),
                permission(Set.of("example.com"), Set.of(443), true),
                new CancellationSource());

        assertEquals(200, result.statusCode());
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), result.body());
        assertFalse(result.truncated());
        assertEquals(List.of("yes"), result.headers().get("x-test"));
        assertFalse(result.headers().containsKey("set-cookie"));
        assertFalse(result.headers().containsKey("connection"));
        String sent = transport.requests().getFirst();
        assertTrue(sent.startsWith("GET /resource?q=1 HTTP/1.1\r\n"));
        assertTrue(sent.contains("Host: example.com\r\n"));
        assertTrue(sent.contains("Accept-Encoding: identity\r\n"));
        assertTrue(sent.contains("x-client: sdk\r\n"));
    }

    @Test
    void followsRedirectRechecksHostAndRemovesCredentialsAcrossOrigins() throws Exception {
        FakeTransport transport = new FakeTransport(
                response(
                        "HTTP/1.1 303 See Other\r\nLocation: https://other.example/final\r\nContent-Length: 0\r\n\r\n"),
                response("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n"));
        PinnedHttpNetworkBroker broker =
                broker(transport, Map.of("example.com", PUBLIC, "other.example", addresses("8.8.8.8")));

        var result = broker.exchange(
                request(
                        "https://example.com/start",
                        "POST",
                        Map.of("authorization", List.of("Bearer secret"), "content-type", List.of("text/plain")),
                        "body".getBytes(StandardCharsets.UTF_8),
                        32),
                permission(Set.of("example.com", "other.example"), Set.of(443), true),
                new CancellationSource());

        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), result.body());
        assertEquals(2, transport.requests().size());
        String redirected = transport.requests().get(1);
        assertTrue(redirected.startsWith("GET /final HTTP/1.1\r\n"));
        assertFalse(redirected.contains("authorization"));
        assertFalse(redirected.contains("content-type"));
        assertTrue(transport.targets().stream().anyMatch(target -> target.host().equals("other.example")));
    }

    @Test
    void browserExchangePreservesCookieButNeverFollowsRedirect() throws Exception {
        FakeTransport transport = new FakeTransport(
                response(
                        "HTTP/1.1 302 Found\r\nLocation: https://other.example/next\r\n"
                                + "Set-Cookie: session=secret; Secure; HttpOnly\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"),
                response("HTTP/1.1 204 No Content\r\n\r\n"));
        PinnedHttpNetworkBroker broker = broker(transport, Map.of("example.com", PUBLIC));

        BrowserBrokerResponse result = broker.exchangeBrowserSingleHop(
                request("https://example.com/start", "GET", Map.of(), new byte[0], 32),
                permission(Set.of("example.com"), Set.of(443), true),
                new CancellationSource(),
                (origin, addresses) -> {
                    throw new AssertionError("public DNS must not require a private grant");
                });

        assertEquals(302, result.statusCode());
        assertEquals(
                List.of("session=secret; Secure; HttpOnly"), result.headers().get("set-cookie"));
        assertEquals(List.of("https://other.example/next"), result.headers().get("location"));
        assertFalse(result.headers().containsKey("connection"));
        assertEquals(1, transport.requests().size());
    }

    @Test
    void browserExchangeRejectsHttpBeforeOpeningTransport() {
        FakeTransport transport = new FakeTransport(response("HTTP/1.1 204 No Content\r\n\r\n"));
        PinnedHttpNetworkBroker broker = broker(transport, Map.of("internal.example", addresses("10.0.0.8")));

        assertThrows(
                SecurityException.class,
                () -> broker.exchangeBrowserSingleHop(
                        request("http://internal.example", "GET", Map.of(), new byte[0], 32),
                        permission(Set.of("internal.example"), Set.of(80), false),
                        new CancellationSource(),
                        (origin, addresses) -> {}));
        assertTrue(transport.requests().isEmpty());
    }

    @Test
    void truncatesKnownLengthAndRejectsAmbiguousResponseFraming() throws Exception {
        FakeTransport truncated = new FakeTransport(response("HTTP/1.1 200 OK\r\nContent-Length: 8\r\n\r\n12345678"));
        PinnedHttpNetworkBroker broker = broker(truncated, Map.of("example.com", PUBLIC));
        var result = broker.exchange(
                request("https://example.com", "GET", Map.of(), new byte[0], 4),
                permission(Set.of("example.com"), Set.of(443), true),
                new CancellationSource());

        assertArrayEquals("1234".getBytes(StandardCharsets.UTF_8), result.body());
        assertTrue(result.truncated());
        assertFalse(result.headers().containsKey("content-length"));

        FakeTransport ambiguous = new FakeTransport(
                response("HTTP/1.1 200 OK\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n"));
        assertThrows(
                IOException.class,
                () -> broker(ambiguous, Map.of("example.com", PUBLIC))
                        .exchange(
                                request("https://example.com", "GET", Map.of(), new byte[0], 4),
                                permission(Set.of("example.com"), Set.of(443), true),
                                new CancellationSource()));
    }

    @Test
    void rejectsWildcardPermissionPrivateDnsWrongPortAndTlsDowngrade() throws Exception {
        FakeTransport transport = new FakeTransport(response("HTTP/1.1 204 No Content\r\n\r\n"));
        PinnedHttpNetworkBroker publicBroker = broker(transport, Map.of("example.com", PUBLIC));
        assertThrows(
                SecurityException.class,
                () -> publicBroker.exchange(
                        request("https://example.com", "GET", Map.of(), new byte[0], 1),
                        permission(Set.of(NetworkPermission.ANY_HOST), Set.of(443), true),
                        new CancellationSource()));
        assertThrows(
                SecurityException.class,
                () -> publicBroker.exchange(
                        request("https://example.com:8443", "GET", Map.of(), new byte[0], 1),
                        permission(Set.of("example.com"), Set.of(443), true),
                        new CancellationSource()));
        assertThrows(
                SecurityException.class,
                () -> publicBroker.exchange(
                        request("http://example.com", "GET", Map.of(), new byte[0], 1),
                        permission(Set.of("example.com"), Set.of(80), true),
                        new CancellationSource()));

        PinnedHttpNetworkBroker privateBroker =
                broker(transport, Map.of("example.com", List.of(InetAddress.getByName("127.0.0.1"))));
        assertThrows(
                SecurityException.class,
                () -> privateBroker.exchange(
                        request("https://example.com", "GET", Map.of(), new byte[0], 1),
                        permission(Set.of("example.com"), Set.of(443), true),
                        new CancellationSource()));
        assertTrue(transport.requests().isEmpty());
    }

    @Test
    void cancellationClosesBlockingConnectionAndReturnsStableCancellation() throws Exception {
        BlockingInput input = new BlockingInput();
        FakeTransport transport = new FakeTransport(input);
        PinnedHttpNetworkBroker broker = broker(transport, Map.of("example.com", PUBLIC));
        CancellationSource cancellation = new CancellationSource();
        FutureTask<Void> task = new FutureTask<>(() -> {
            broker.exchange(
                    request("https://example.com", "GET", Map.of(), new byte[0], 1),
                    permission(Set.of("example.com"), Set.of(443), true),
                    cancellation);
            return null;
        });
        Thread thread = Thread.ofVirtual().start(task);
        assertTrue(input.started.await(1, TimeUnit.SECONDS));

        cancellation.cancel("stop");
        var failure = assertThrows(java.util.concurrent.ExecutionException.class, task::get);
        thread.join(Duration.ofSeconds(1));

        assertTrue(failure.getCause() instanceof TurnCancelledException);
        assertTrue(input.closed.get());
    }

    @Test
    void requestControlledHeadersAndRedirectLimitFailClosed() throws Exception {
        FakeTransport controlled = new FakeTransport(response("HTTP/1.1 204 No Content\r\n\r\n"));
        assertThrows(
                SecurityException.class,
                () -> broker(controlled, Map.of("example.com", PUBLIC))
                        .exchange(
                                request(
                                        "https://example.com",
                                        "GET",
                                        Map.of("host", List.of("attacker.example")),
                                        new byte[0],
                                        1),
                                permission(Set.of("example.com"), Set.of(443), true),
                                new CancellationSource()));

        FakeTransport redirects = new FakeTransport();
        for (int count = 0; count < 6; count++) {
            redirects.add(response("HTTP/1.1 307 Temporary Redirect\r\nLocation: /again\r\nContent-Length: 0\r\n\r\n"));
        }
        assertThrows(
                SecurityException.class,
                () -> broker(redirects, Map.of("example.com", PUBLIC))
                        .exchange(
                                request("https://example.com/start", "GET", Map.of(), new byte[0], 1),
                                permission(Set.of("example.com"), Set.of(443), true),
                                new CancellationSource()));
    }

    @Test
    void rejectsOversizedRequestAndAmbiguousOrInvalidRedirectLocations() {
        FakeTransport unused = new FakeTransport(response("HTTP/1.1 204 No Content\r\n\r\n"));
        assertThrows(
                SecurityException.class,
                () -> broker(unused, Map.of("example.com", PUBLIC))
                        .exchange(
                                request("https://example.com", "POST", Map.of(), new byte[5], 4),
                                permission(Set.of("example.com"), Set.of(443), true, 4),
                                new CancellationSource()));
        assertRedirectRejected("HTTP/1.1 302 Found\r\nContent-Length: 0\r\n\r\n");
        assertRedirectRejected("HTTP/1.1 302 Found\r\nLocation: /one\r\nLocation: /two\r\nContent-Length: 0\r\n\r\n");
        assertRedirectRejected("HTTP/1.1 302 Found\r\nLocation: http://[\r\nContent-Length: 0\r\n\r\n");
        assertTrue(unused.requests().isEmpty());
    }

    @Test
    void preservesMethodAndCredentialsOnlyWhenRedirectSemanticsPermit() throws Exception {
        FakeTransport sameOrigin = redirects(301, "https://example.com:443/final");
        broker(sameOrigin, Map.of("example.com", PUBLIC))
                .exchange(
                        request(
                                "https://example.com/start",
                                "GET",
                                Map.of("authorization", List.of("secret")),
                                new byte[0],
                                1),
                        permission(Set.of("example.com"), Set.of(443), true),
                        new CancellationSource());
        assertTrue(sameOrigin.requests().get(1).startsWith("GET /final HTTP/1.1"));
        assertTrue(sameOrigin.requests().get(1).contains("authorization: secret"));

        FakeTransport head = redirects(303, "/head");
        broker(head, Map.of("example.com", PUBLIC))
                .exchange(
                        request("https://example.com/start", "HEAD", Map.of(), new byte[0], 1),
                        permission(Set.of("example.com"), Set.of(443), true),
                        new CancellationSource());
        assertTrue(head.requests().get(1).startsWith("HEAD /head HTTP/1.1"));
    }

    @Test
    void switchesPostForLegacyRedirectAndTreatsOtherStatusesAsFinal() throws Exception {
        FakeTransport post = redirects(302, "/get");
        broker(post, Map.of("example.com", PUBLIC))
                .exchange(
                        request(
                                "https://example.com/start",
                                "POST",
                                Map.of("content-encoding", List.of("identity")),
                                new byte[] {1},
                                1),
                        permission(Set.of("example.com"), Set.of(443), true),
                        new CancellationSource());
        assertTrue(post.requests().get(1).startsWith("GET /get HTTP/1.1"));
        assertFalse(post.requests().get(1).contains("content-encoding"));

        FakeTransport notRedirect =
                new FakeTransport(response("HTTP/1.1 304 Not Modified\r\nContent-Length: 0\r\n\r\n"));
        var result = broker(notRedirect, Map.of("example.com", PUBLIC))
                .exchange(
                        request("https://example.com", "GET", Map.of(), new byte[0], 1),
                        permission(Set.of("example.com"), Set.of(443), true),
                        new CancellationSource());
        assertEquals(304, result.statusCode());
        assertEquals(1, notRedirect.requests().size());
    }

    @Test
    void stripsCredentialsWhenOnlySchemeOrPortChangesAndFollowsPermanentRedirect() throws Exception {
        FakeTransport scheme = redirects(308, "http://example.com/final");
        broker(scheme, Map.of("example.com", PUBLIC))
                .exchange(
                        request(
                                "https://example.com/start",
                                "GET",
                                Map.of("authorization", List.of("secret")),
                                new byte[0],
                                1),
                        permission(Set.of("example.com"), Set.of(80, 443), false),
                        new CancellationSource());
        assertFalse(scheme.requests().get(1).contains("authorization"));

        FakeTransport port = redirects(307, "https://example.com:8443/final");
        broker(port, Map.of("example.com", PUBLIC))
                .exchange(
                        request(
                                "https://example.com/start",
                                "GET",
                                Map.of("authorization", List.of("secret")),
                                new byte[0],
                                1),
                        permission(Set.of("example.com"), Set.of(443, 8443), true),
                        new CancellationSource());
        assertFalse(port.requests().get(1).contains("authorization"));
    }

    @Test
    void keepsNonLengthHeadersOnTruncatedResponseAndUsesPlatformTimeoutCeiling() throws Exception {
        FakeTransport transport =
                new FakeTransport(response("HTTP/1.1 200 OK\r\nContent-Length: 8\r\nX-Test: kept\r\n\r\n12345678"));
        var result = broker(transport, Map.of("example.com", PUBLIC))
                .exchange(
                        request("https://example.com", "GET", Map.of(), new byte[0], 4, Duration.ofMinutes(5)),
                        permission(Set.of("example.com"), Set.of(443), true, 1024 * 1024, Duration.ofMinutes(4)),
                        new CancellationSource());

        assertTrue(result.truncated());
        assertEquals(List.of("kept"), result.headers().get("x-test"));
        assertFalse(result.headers().containsKey("content-length"));
    }

    @Test
    void preservesCredentialsAcrossSameOriginHttpRedirect() throws Exception {
        FakeTransport transport = redirects(307, "/final");
        broker(transport, Map.of("example.com", PUBLIC))
                .exchange(
                        request(
                                "http://example.com/start",
                                "GET",
                                Map.of("authorization", List.of("secret")),
                                new byte[0],
                                1),
                        permission(Set.of("example.com"), Set.of(80), false),
                        new CancellationSource());

        assertTrue(transport.requests().get(1).contains("authorization: secret"));
    }

    @Test
    void 私网回调只能放行Rfc1918而不能放宽永久拒绝地址() throws Exception {
        FakeTransport privateTransport = new FakeTransport(response("HTTP/1.1 204 No Content\r\n\r\n"));
        PinnedHttpNetworkBroker privateBroker =
                broker(privateTransport, Map.of("internal.example", addresses("10.0.0.8")));
        AtomicReference<Set<String>> authorized = new AtomicReference<>();
        AtomicReference<URI> authorizedOrigin = new AtomicReference<>();

        var response = privateBroker.exchange(
                request("https://internal.example", "GET", Map.of(), new byte[0], 1),
                permission(Set.of("internal.example"), Set.of(443), true),
                new CancellationSource(),
                (origin, addresses) -> {
                    authorizedOrigin.set(origin);
                    authorized.set(addresses);
                });

        assertEquals(204, response.statusCode());
        assertEquals(URI.create("https://internal.example"), authorizedOrigin.get());
        assertEquals(Set.of("10.0.0.8"), authorized.get());

        AtomicBoolean httpGrantCalled = new AtomicBoolean();
        assertThrows(
                SecurityException.class,
                () -> privateBroker.exchange(
                        request("http://internal.example", "GET", Map.of(), new byte[0], 1),
                        permission(Set.of("internal.example"), Set.of(80), false),
                        new CancellationSource(),
                        (origin, addresses) -> httpGrantCalled.set(true)));
        assertFalse(httpGrantCalled.get());

        FakeTransport loopbackTransport = new FakeTransport(response("HTTP/1.1 204 No Content\r\n\r\n"));
        PinnedHttpNetworkBroker loopbackBroker =
                broker(loopbackTransport, Map.of("internal.example", addresses("127.0.0.1")));
        assertThrows(
                SecurityException.class,
                () -> loopbackBroker.exchange(
                        request("https://internal.example", "GET", Map.of(), new byte[0], 1),
                        permission(Set.of("internal.example"), Set.of(443), true),
                        new CancellationSource(),
                        (origin, addresses) -> {}));
        assertTrue(loopbackTransport.requests().isEmpty());
    }

    private static PinnedHttpNetworkBroker broker(FakeTransport transport, Map<String, List<InetAddress>> addresses) {
        return new PinnedHttpNetworkBroker(
                (host, timeout, cancellation) -> addresses.getOrDefault(host, List.of()), transport);
    }

    private static BrokerRequest request(
            String uri, String method, Map<String, List<String>> headers, byte[] body, long maximumBytes) {
        return request(uri, method, headers, body, maximumBytes, Duration.ofSeconds(3));
    }

    private static BrokerRequest request(
            String uri,
            String method,
            Map<String, List<String>> headers,
            byte[] body,
            long maximumBytes,
            Duration timeout) {
        return new BrokerRequest(URI.create(uri), method, headers, body, maximumBytes, timeout);
    }

    private static PermissionProfile permission(Set<String> hosts, Set<Integer> ports, boolean tlsOnly) {
        return permission(hosts, ports, tlsOnly, 1024 * 1024);
    }

    private static PermissionProfile permission(
            Set<String> hosts, Set<Integer> ports, boolean tlsOnly, long outputBytes) {
        return permission(hosts, ports, tlsOnly, outputBytes, Duration.ofSeconds(5));
    }

    private static PermissionProfile permission(
            Set<String> hosts, Set<Integer> ports, boolean tlsOnly, long outputBytes, Duration processTimeout) {
        return new PermissionProfile(
                "test",
                1,
                new FilePermission(List.of(), List.of(), false, false),
                new NetworkPermission(hosts, ports, tlsOnly),
                new ProcessPermission(Set.of(), false, processTimeout),
                new ToolPermission(Set.of(), ToolRisk.READ_ONLY, ApprovalRequirement.NONE),
                new ResourceLimits(64 * 1024 * 1024, outputBytes, 1, 16));
    }

    private static FakeTransport redirects(int status, String location) {
        return new FakeTransport(
                response("HTTP/1.1 " + status + " Redirect\r\nLocation: " + location + "\r\nContent-Length: 0\r\n\r\n"),
                response("HTTP/1.1 204 No Content\r\n\r\n"));
    }

    private static void assertRedirectRejected(String wireResponse) {
        assertThrows(
                SecurityException.class,
                () -> broker(new FakeTransport(response(wireResponse)), Map.of("example.com", PUBLIC))
                        .exchange(
                                request("https://example.com", "GET", Map.of(), new byte[0], 1),
                                permission(Set.of("example.com"), Set.of(443), true),
                                new CancellationSource()));
    }

    private static byte[] response(String value) {
        return value.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static List<InetAddress> addresses(String... values) {
        try {
            ArrayList<InetAddress> result = new ArrayList<>();
            for (String value : values) {
                result.add(InetAddress.getByName(value));
            }
            return List.copyOf(result);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static final class FakeTransport implements HttpWireExchange.Transport {
        private final ArrayDeque<InputStream> responses = new ArrayDeque<>();
        private final List<ByteArrayOutputStream> outputs = new ArrayList<>();
        private final List<BrokerTarget> targets = new ArrayList<>();

        FakeTransport(byte[]... responses) {
            for (byte[] response : responses) {
                add(response);
            }
        }

        FakeTransport(InputStream response) {
            responses.add(response);
        }

        void add(byte[] response) {
            responses.add(new ByteArrayInputStream(response));
        }

        @Override
        public HttpWireExchange.Connection open(
                BrokerTarget target,
                BrokerDeadline deadline,
                com.javaclaw.api.CancellationToken cancellation,
                java.util.function.Consumer<AutoCloseable> pending) {
            InputStream input = responses.removeFirst();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            outputs.add(output);
            targets.add(target);
            FakeConnection connection = new FakeConnection(input, output);
            pending.accept(connection);
            return connection;
        }

        List<String> requests() {
            return outputs.stream()
                    .map(output -> output.toString(StandardCharsets.ISO_8859_1))
                    .toList();
        }

        List<BrokerTarget> targets() {
            return List.copyOf(targets);
        }
    }

    private record FakeConnection(InputStream input, ByteArrayOutputStream output)
            implements HttpWireExchange.Connection {
        @Override
        public void close() throws IOException {
            input.close();
            output.close();
        }
    }

    private static final class BlockingInput extends InputStream {
        private final CountDownLatch started = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public int read() throws IOException {
            started.countDown();
            while (!closed.get()) {
                try {
                    Thread.sleep(Duration.ofMillis(10));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", interrupted);
                }
            }
            throw new IOException("closed");
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }
}
