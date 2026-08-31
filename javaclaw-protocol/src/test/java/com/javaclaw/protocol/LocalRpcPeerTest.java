package com.javaclaw.protocol;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalRpcPeerTest {
    private final JsonRpcCodec codec = new JsonRpcCodec();

    @Test
    void reverseRequestDoesNotDeadlockTheForwardResponse() throws Exception {
        var forward = Pipe.open();
        var reverse = Pipe.open();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
                var left = new LocalRpcPeer(reader(reverse), writer(forward));
                var right = new LocalRpcPeer(reader(forward), writer(reverse))) {
            var result = executor.submit(
                    () -> left.call("page/open", codec.mapper().createObjectNode(), Duration.ofSeconds(3)));
            var request = right.next(Duration.ofSeconds(1));
            assertNotNull(request);
            var broker = executor.submit(
                    () -> right.call("broker/request", codec.mapper().createObjectNode(), Duration.ofSeconds(3)));
            var backwards = left.next(Duration.ofSeconds(1));
            assertNotNull(backwards);
            left.respond(backwards, codec.mapper().createObjectNode().put("status", 200));
            assertEquals(200, broker.get(2, TimeUnit.SECONDS).path("status").asInt());
            right.respond(request, codec.mapper().createObjectNode().put("title", "中文页面"));
            assertEquals("中文页面", result.get(2, TimeUnit.SECONDS).path("title").asText());
            assertThrows(
                    IOException.class,
                    () -> right.respond(request, codec.mapper().createObjectNode()));
        } finally {
            forward.source().close();
            forward.sink().close();
            reverse.source().close();
            reverse.sink().close();
        }
    }

    @Test
    void eofPreservesQueuedRequestsAndFlushesResponsesBeforeClose() throws Exception {
        var output = new StringWriter();
        try (var peer = new LocalRpcPeer(new StringReader("""
                {"jsonrpc":"2.0","id":"one","method":"health","params":{}}
                {"jsonrpc":"2.0","id":"two","method":"health","params":{}}
                """), output)) {
            for (int index = 0; index < 2; index++) {
                var request = peer.next(Duration.ofSeconds(1));
                assertNotNull(request);
                peer.respond(request, codec.mapper().createObjectNode().put("ok", true));
            }
        }
        assertEquals(2, output.toString().lines().count());
        assertTrue(output.toString().contains("\"id\":\"two\""));
    }

    @Test
    void fullPipeCannotBypassDeadlineOrBlockClose() throws Exception {
        var input = Pipe.open();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var slow = new Writer() {
            @Override
            public void write(char[] data, int offset, int length) throws IOException {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                    throw new IOException("cancelled");
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        long start = System.nanoTime();
        try (var peer = new LocalRpcPeer(reader(input), slow)) {
            assertThrows(
                    TimeoutException.class,
                    () -> peer.call(
                            "large",
                            codec.mapper().createObjectNode().put("value", "x".repeat(100_000)),
                            Duration.ofMillis(100)));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertFalse(peer.isOpen());
            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(2)) < 0);
        } finally {
            release.countDown();
            input.source().close();
            input.sink().close();
        }
    }

    @Test
    void duplicateIdsAndOverfullRequestQueueFailClosed() throws Exception {
        for (String input : new String[] {
            """
            {"jsonrpc":"2.0","id":"duplicate","method":"health","params":{}}
            {"jsonrpc":"2.0","id":"duplicate","method":"health","params":{}}
            """,
            java.util.stream.IntStream.range(0, 17)
                    .mapToObj(index ->
                            "{\"jsonrpc\":\"2.0\",\"id\":\"" + index + "\",\"method\":\"health\",\"params\":{}}\n")
                    .collect(java.util.stream.Collectors.joining())
        }) {
            try (var peer = new LocalRpcPeer(new StringReader(input), new StringWriter())) {
                long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
                while (peer.isOpen() && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                assertFalse(peer.isOpen());
            }
        }
    }

    private static Reader reader(Pipe pipe) {
        return new InputStreamReader(Channels.newInputStream(pipe.source()), StandardCharsets.UTF_8);
    }

    private static Writer writer(Pipe pipe) {
        return new OutputStreamWriter(Channels.newOutputStream(pipe.sink()), StandardCharsets.UTF_8);
    }
}
