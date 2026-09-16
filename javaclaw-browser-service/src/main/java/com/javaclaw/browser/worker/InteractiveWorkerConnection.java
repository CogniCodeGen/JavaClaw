package com.javaclaw.browser.worker;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.browser.protocol.BrowserFrameIo;
import com.javaclaw.browser.protocol.BrowserWorkerProtocol;
import com.javaclaw.browser.protocol.InteractiveBrowserProtocol;
import com.javaclaw.browser.protocol.InteractiveBrowserProtocol.Frame;
import com.javaclaw.browser.protocol.InteractiveBrowserProtocol.Kind;
import com.javaclaw.browser.protocol.InteractiveBrowserProtocol.Packet;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.protocol.CanonicalJson;

/** 唯一 stdin 读线程只分发私有帧；所有 Playwright 调用仍在主 actor 线程。 */
final class InteractiveWorkerConnection implements AutoCloseable {
    private final InputStream input;
    private final OutputStream output;
    private final CanonicalJson json;
    private final ArrayBlockingQueue<Packet> commands = new ArrayBlockingQueue<>(16);
    private final ConcurrentHashMap<Long, CompletableFuture<Packet>> replies = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private volatile boolean closed;
    private final Thread reader;

    InteractiveWorkerConnection(InputStream input, OutputStream output, CanonicalJson json) {
        this.input = input;
        this.output = output;
        this.json = json;
        reader = Thread.ofVirtual().name("browser-interactive-input").start(this::read);
    }

    boolean active() {
        return !closed;
    }

    Packet poll() {
        return commands.poll();
    }

    void reply(long id, Object payload, byte[] bytes) throws IOException {
        send(new Frame(Kind.REPLY, id, "", json.encode(payload), bytes.length, Optional.empty()), bytes);
    }

    void failure(long id, String code) throws IOException {
        send(new Frame(Kind.REPLY, id, "", new CanonicalPayload("{}"), 0, Optional.of(code)), new byte[0]);
    }

    void deniedOrigin(java.net.URI origin, long generation) {
        try {
            send(
                    new Frame(
                            Kind.DENIED_ORIGIN,
                            sequence.incrementAndGet(),
                            Long.toString(generation),
                            json.encode(new InteractiveBrowserProtocol.OriginNotice(origin)),
                            0,
                            Optional.empty()),
                    new byte[0]);
        } catch (IOException failure) {
            throw new IllegalStateException("Browser origin notification channel ended");
        }
    }

    BrowserNetworkChannel.NetworkResult exchange(
            BrowserContracts.NetworkRequest request, byte[] body, long generation) {
        if (closed) {
            throw new IllegalStateException("Browser host disconnected");
        }
        long id = sequence.incrementAndGet();
        CompletableFuture<Packet> future = new CompletableFuture<>();
        replies.put(id, future);
        try {
            send(
                    new Frame(
                            Kind.NETWORK,
                            id,
                            Long.toString(generation),
                            json.encode(request),
                            body.length,
                            Optional.empty()),
                    body);
            try (Packet response = future.get(30, TimeUnit.SECONDS)) {
                if (response.frame().error().isPresent()) {
                    throw new IllegalStateException("Browser network request denied");
                }
                BrowserWorkerProtocol.NetworkResponse metadata =
                        json.decode(response.frame().payload(), BrowserWorkerProtocol.NetworkResponse.class);
                byte[] bytes = response.bytes();
                try {
                    return new BrowserNetworkChannel.NetworkResult(
                            metadata.statusCode(), metadata.headers(), bytes, metadata.truncated());
                } finally {
                    Arrays.fill(bytes, (byte) 0);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Browser network wait interrupted");
        } catch (Exception failure) {
            throw new IllegalStateException("Browser network exchange failed");
        } finally {
            replies.remove(id);
        }
    }

    private void read() {
        try {
            while (!closed) {
                Frame frame = BrowserFrameIo.readJson(input, json, Frame.class);
                byte[] bytes = BrowserFrameIo.readBinary(
                        input, frame.binaryBytes(), BrowserWorkerProtocol.MAXIMUM_NETWORK_BYTES);
                try {
                    dispatch(new Packet(frame, bytes));
                } finally {
                    Arrays.fill(bytes, (byte) 0);
                }
            }
        } catch (IOException | RuntimeException ended) {
            close();
        }
    }

    private void dispatch(Packet packet) {
        if (packet.frame().kind() == Kind.NETWORK_REPLY) {
            CompletableFuture<Packet> reply = replies.get(packet.frame().id());
            if (reply == null || !reply.complete(packet)) {
                packet.close();
            }
        } else if (packet.frame().kind() == Kind.COMMAND && commands.offer(packet)) {
            // 命令等待 actor；读线程绝不触碰 Page 或 Context。
        } else {
            packet.close();
            throw new IllegalArgumentException("unexpected Browser host frame");
        }
    }

    private synchronized void send(Frame frame, byte[] bytes) throws IOException {
        if (closed) {
            throw new IOException("Browser host disconnected");
        }
        BrowserFrameIo.writeJson(output, json, frame);
        BrowserFrameIo.writeBinary(output, bytes, BrowserWorkerProtocol.MAXIMUM_NETWORK_BYTES);
    }

    @Override
    public void close() {
        closed = true;
        replies.values().forEach(value -> value.completeExceptionally(new IOException("Browser host disconnected")));
        Packet packet;
        while ((packet = commands.poll()) != null) {
            packet.close();
        }
        if (reader != null) {
            reader.interrupt();
        }
    }
}
