package com.javaclaw.browser.worker;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.microsoft.playwright.Playwright;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.browser.protocol.BrowserFrameIo;
import com.javaclaw.browser.protocol.BrowserWorkerProtocol;
import com.javaclaw.browser.protocol.InteractiveBrowserProtocol;
import com.javaclaw.browser.protocol.InteractiveBrowserProtocol.Frame;
import com.javaclaw.browser.protocol.InteractiveBrowserProtocol.Kind;
import com.javaclaw.browser.protocol.InteractiveBrowserProtocol.Packet;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 真实私有帧和独立 actor 线程组成的双向夹具；外部进程由 fake Playwright 替换。 */
final class InteractiveWorkerLoopFixture implements AutoCloseable {
    static final URI ORIGIN = URI.create("https://docs.example.com");
    final CanonicalJson json = new CanonicalJson();
    final InteractivePlaywrightFixture browser = new InteractivePlaywrightFixture();
    final BrowserContracts.AccessLease original = lease(BrowserContracts.ControlMode.ASSISTANT, 1);
    final PipedInputStream workerInput = new PipedInputStream(65_536);
    final PipedInputStream hostInput = new PipedInputStream(65_536);
    final PipedOutputStream hostOutput;
    private final PipedOutputStream workerOutput;
    private final CompletableFuture<Void> ended = new CompletableFuture<>();
    private final ArrayBlockingQueue<Packet> responses = new ArrayBlockingQueue<>(16);
    private volatile Exception readFailure;
    private long sequence = 1;

    InteractiveWorkerLoopFixture() throws IOException {
        hostOutput = new PipedOutputStream(workerInput);
        workerOutput = new PipedOutputStream(hostInput);
        Thread.ofVirtual().name("interactive-loop-host-read").start(this::readReplies);
    }

    void start() {
        start(() -> browser.playwright, new byte[0]);
    }

    void start(Supplier<Playwright> factory, byte[] state) {
        var task = new BrowserContracts.OpenTask(
                UUID.randomUUID().toString(),
                new BrowserContracts.Owner(WorkspaceId.random(), ThreadId.random(), Optional.empty()),
                URI.create("https://docs.example.com/"),
                original);
        var initial = new BrowserWorkerProtocol.Command(
                BrowserWorkerProtocol.VERSION, 1, InteractiveBrowserProtocol.OPEN, json.encode(task), state.length);
        Thread.ofVirtual().name("interactive-loop-fixture").start(() -> {
            try (workerOutput) {
                InteractiveWorkerLoop.run(initial, state, workerInput, workerOutput, json, factory);
                ended.complete(null);
            } catch (Exception failure) {
                ended.completeExceptionally(failure);
            }
        });
    }

    BrowserContracts.Observation opened() throws Exception {
        try (Packet packet = receive()) {
            assertEquals(1, packet.frame().id());
            assertEquals(Optional.empty(), packet.frame().error());
            return json.decode(packet.frame().payload(), BrowserContracts.Observation.class);
        }
    }

    Packet request(String operation, Object payload) throws Exception {
        return request(operation, payload, new byte[0]);
    }

    Packet request(String operation, Object payload, byte[] bytes) throws Exception {
        Frame frame = frame(Kind.COMMAND, operation, payload, bytes.length);
        BrowserFrameIo.writeJson(hostOutput, json, frame);
        BrowserFrameIo.writeBinary(hostOutput, bytes, BrowserWorkerProtocol.MAXIMUM_NETWORK_BYTES);
        Packet reply = receive();
        assertEquals(Kind.REPLY, reply.frame().kind());
        assertEquals(frame.id(), reply.frame().id());
        return reply;
    }

    Frame frame(Kind kind, String operation, Object payload, int bytes) {
        return new Frame(kind, ++sequence, operation, json.encode(payload), bytes, Optional.empty());
    }

    Packet receive() throws Exception {
        Packet packet = responses.poll(5, TimeUnit.SECONDS);
        if (packet == null) {
            throw new IOException("私有循环未在时限内回复", readFailure);
        }
        return packet;
    }

    private void readReplies() {
        try {
            while (true) {
                Frame frame = BrowserFrameIo.readJson(hostInput, json, Frame.class);
                byte[] bytes = BrowserFrameIo.readBinary(
                        hostInput, frame.binaryBytes(), BrowserWorkerProtocol.MAXIMUM_NETWORK_BYTES);
                try {
                    responses.put(new Packet(frame, bytes));
                } finally {
                    Arrays.fill(bytes, (byte) 0);
                }
            }
        } catch (Exception failure) {
            readFailure = failure;
        }
    }

    void awaitStopped() throws Exception {
        ended.get(5, TimeUnit.SECONDS);
    }

    static CanonicalPayload empty() {
        return new CanonicalPayload("{}");
    }

    static BrowserContracts.AccessLease lease(BrowserContracts.ControlMode mode, long generation) {
        return new BrowserContracts.AccessLease(
                mode, UUID.randomUUID().toString(), generation, Instant.now().plusSeconds(60), Set.of(ORIGIN));
    }

    @Override
    public void close() throws IOException {
        hostOutput.close();
        workerInput.close();
        workerOutput.close();
        hostInput.close();
        responses.forEach(Packet::close);
        responses.clear();
    }
}
