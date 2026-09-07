package com.javaclaw.server.security;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainArtifact;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainKind;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PinnedArtifactDownloaderTest {
    @Test
    void repinsEveryRedirectAndStreamsWithoutCredentialsOrClosingTarget() throws Exception {
        ArrayList<String> resolutions = new ArrayList<>();
        ArrayList<FakeConnection> opened = new ArrayList<>();
        var downloader = new PinnedArtifactDownloader(
                (host, deadline, cancellation) -> {
                    resolutions.add(host);
                    return List.of(InetAddress.getByName("93.184.216.34"));
                },
                (target, deadline, cancellation, pending) -> {
                    String response = opened.isEmpty()
                            ? "HTTP/1.1 302 Found\r\nLocation: https://cdn.example.org/file\r\n\r\n"
                            : "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello";
                    var connection =
                            new FakeConnection(new ByteArrayInputStream(response.getBytes(StandardCharsets.US_ASCII)));
                    opened.add(connection);
                    pending.accept(connection);
                    return connection;
                },
                Duration.ofSeconds(2));
        var target = new TrackedTarget();
        downloader.download(artifact(10), target, new CancellationSource());
        assertEquals("hello", target.toString(StandardCharsets.UTF_8));
        assertEquals(List.of("downloads.example.org", "cdn.example.org"), resolutions);
        assertFalse(target.closed);
        assertTrue(opened.stream().allMatch(connection -> connection.closed.get()));
        assertTrue(opened.getLast()
                .request
                .toString(StandardCharsets.US_ASCII)
                .startsWith("GET /file HTTP/1.1\r\nHost: cdn.example.org\r\n"));
        assertFalse(opened.getLast().request.toString(StandardCharsets.US_ASCII).contains("Authorization"));
    }

    @Test
    void rejectsAnyPrivateDnsAnswerAndInsecureRedirectBeforeConnecting() throws Exception {
        AtomicBoolean opened = new AtomicBoolean();
        var downloader = new PinnedArtifactDownloader(
                (host, deadline, cancellation) ->
                        List.of(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("127.0.0.1")),
                (target, deadline, cancellation, pending) -> {
                    opened.set(true);
                    throw new AssertionError("must not connect");
                },
                Duration.ofSeconds(2));
        assertThrows(
                SecurityException.class,
                () -> downloader.download(artifact(10), new ByteArrayOutputStream(), new CancellationSource()));
        assertFalse(opened.get());
        for (String location : List.of(
                "http://example.org/file", "https://example.org:444/file", "https://user:pass@example.org/file")) {
            var redirect = downloader("HTTP/1.1 302 Found\r\nLocation: " + location + "\r\n\r\n");
            assertThrows(
                    SecurityException.class,
                    () -> redirect.download(artifact(10), new ByteArrayOutputStream(), new CancellationSource()));
        }
    }

    @Test
    void downloadLimitRejectsBodyBeforeAnyOversizedFixedWrite() {
        var target = new ByteArrayOutputStream();
        assertThrows(
                IOException.class,
                () -> downloader("HTTP/1.1 200 OK\r\nContent-Length: 20\r\n\r\n")
                        .download(artifact(10), target, new CancellationSource()));
        assertEquals(0, target.size());
    }

    @Test
    void cancellationClosesPendingTransportAndPreventsLateTargetWrites() throws Exception {
        var blocked = new BlockingInput();
        var connection = new FakeConnection(blocked);
        var downloader = new PinnedArtifactDownloader(
                (host, deadline, cancellation) -> List.of(InetAddress.getByName("93.184.216.34")),
                (target, deadline, cancellation, pending) -> {
                    pending.accept(connection);
                    return connection;
                },
                Duration.ofSeconds(10));
        CancellationSource cancellation = new CancellationSource();
        var target = new ByteArrayOutputStream();
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = tasks.submit(() -> {
                downloader.download(artifact(10), target, cancellation);
                return null;
            });
            assertTrue(blocked.entered.await(2, TimeUnit.SECONDS));
            cancellation.cancel("test");
            assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertTrue(connection.closed.get());
            assertEquals(0, target.size());
        }
    }

    private static PinnedArtifactDownloader downloader(String response) {
        return new PinnedArtifactDownloader(
                (host, deadline, cancellation) -> List.of(InetAddress.getByName("93.184.216.34")),
                (target, deadline, cancellation, pending) ->
                        new FakeConnection(new ByteArrayInputStream(response.getBytes(StandardCharsets.US_ASCII))),
                Duration.ofSeconds(2));
    }

    private static ToolchainArtifact artifact(long limit) {
        return new ToolchainArtifact(
                new ToolchainRef(ToolchainKind.NODE, "22.0.0", "a".repeat(64)),
                "macos",
                "aarch64",
                URI.create("https://downloads.example.org/archive"),
                "tar.gz",
                Map.of("node", "bin/node"),
                limit,
                "MIT");
    }

    private static final class TrackedTarget extends ByteArrayOutputStream {
        private boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class FakeConnection implements HttpWireExchange.Connection {
        private final InputStream input;
        private final ByteArrayOutputStream request = new ByteArrayOutputStream();
        private final AtomicBoolean closed = new AtomicBoolean();

        private FakeConnection(InputStream input) {
            this.input = input;
        }

        @Override
        public InputStream input() {
            return input;
        }

        @Override
        public OutputStream output() {
            return request;
        }

        @Override
        public void close() throws IOException {
            closed.set(true);
            input.close();
        }
    }

    private static final class BlockingInput extends InputStream {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public int read() throws IOException {
            entered.countDown();
            try {
                if (!closed.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("test timeout");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("closed");
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }
}
