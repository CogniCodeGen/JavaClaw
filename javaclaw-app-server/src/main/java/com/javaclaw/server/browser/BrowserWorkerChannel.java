package com.javaclaw.server.browser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;

import com.javaclaw.protocol.LocalRpcPeer;
import com.javaclaw.sandbox.api.SandboxSession;

/** Browser 专用双向管道；只允许反向 Broker 请求，任何异常终止整个受监督进程，不自动重放操作。 */
final class BrowserWorkerChannel implements AutoCloseable {
    @FunctionalInterface
    interface BrokerHandler {
        JsonNode request(JsonNode params) throws Exception;
    }

    private final SandboxSession session;
    private final LocalRpcPeer peer;
    private final Runnable releaseFiles;
    private Thread brokerReader;
    private final AtomicBoolean closed = new AtomicBoolean();

    BrowserWorkerChannel(SandboxSession session, BrokerHandler handler, Runnable releaseFiles) {
        this.session = session;
        this.releaseFiles = java.util.Objects.requireNonNull(releaseFiles);
        peer = new LocalRpcPeer(
                new InputStreamReader(new SessionInput(), StandardCharsets.UTF_8),
                new OutputStreamWriter(
                        new OutputStream() {
                            @Override
                            public void write(int value) throws IOException {
                                write(new byte[] {(byte) value});
                            }

                            @Override
                            public void write(byte[] bytes, int offset, int length) throws IOException {
                                try {
                                    session.write(java.util.Arrays.copyOfRange(bytes, offset, offset + length));
                                } catch (Exception failure) {
                                    throw new IOException("browser pipe write failed");
                                }
                            }
                        },
                        StandardCharsets.UTF_8));
        brokerReader = Thread.ofVirtual().name("javaclaw-browser-broker").start(() -> {
            try {
                while (!closed.get() && peer.isOpen()) {
                    var request = peer.next(Duration.ofMillis(100));
                    if (request == null) {
                        continue;
                    }
                    if (!"broker/request".equals(request.method())) {
                        peer.reject(request, -32601, "unsupported request");
                        continue;
                    }
                    try {
                        peer.respond(request, handler.request(request.params()));
                    } catch (Exception failure) {
                        peer.reject(request, -32000, "network policy or response limit rejected request");
                    }
                }
            } catch (Exception failure) {
                close();
            }
        });
    }

    JsonNode call(String method, JsonNode params, Duration timeout) throws Exception {
        try {
            return peer.call(method, params, timeout);
        } catch (LocalRpcPeer.RejectedException rejected) {
            throw rejected;
        } catch (Exception failure) {
            close();
            throw failure;
        }
    }

    boolean alive() {
        return !closed.get() && session.isAlive() && peer.isOpen();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        session.terminate();
        peer.close();
        if (brokerReader != null) {
            brokerReader.interrupt();
        }
        if (!session.isAlive()) {
            releaseFiles.run();
        }
    }

    private final class SessionInput extends InputStream {
        private byte[] current = new byte[0];
        private int offset;

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            return read(single, 0, 1) < 0 ? -1 : single[0] & 255;
        }

        @Override
        public int read(byte[] target, int start, int maximum) throws IOException {
            if (maximum == 0) {
                return 0;
            }
            try {
                while (offset == current.length) {
                    if (closed.get()) {
                        return -1;
                    }
                    var frame = session.read(Duration.ofMillis(100));
                    if (frame == null) {
                        if (!session.isAlive()) {
                            return -1;
                        }
                        continue;
                    }
                    switch (frame.kind()) {
                        case STDOUT -> {
                            current = frame.data();
                            offset = 0;
                        }
                        case STDERR -> {
                            /* Launcher 已限流；绝不将原始 stderr 发送到模型或持久化。 */
                        }
                        case EXIT -> {
                            return -1;
                        }
                        case ERROR, READY -> throw new IOException("unexpected browser launcher state");
                    }
                }
                int length = Math.min(maximum, current.length - offset);
                System.arraycopy(current, offset, target, start, length);
                offset += length;
                return length;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return -1;
            } catch (Exception failure) {
                throw new IOException("browser output channel failed");
            }
        }
    }
}
