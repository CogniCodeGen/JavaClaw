package com.javaclaw.server.transport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.protocol.LocalMuxFrame;

/** Bounded connection multiplexer behind the Windows Native Transport Host. */
public final class MultiplexedAppServer implements AutoCloseable {
    private static final int MAX_CONNECTIONS = 128;
    private static final int MAX_MUX_FRAME_CHARS = 2 * 1024 * 1024;
    private static final int MAX_PENDING_FRAMES = 1_024;
    private static final long MAX_PENDING_BYTES = 8L * 1024L * 1024L;

    private final StdioAppServer sessions;
    private final ObjectMapper json;
    private final ConcurrentHashMap<String, Connection> connections = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object outputLock = new Object();
    private Writer output;

    /** 装配 Windows Host 后的连接复用器；sessions 负责每条逻辑连接，json 仅编解码本地 Mux 帧，两者均非空。 */
    public MultiplexedAppServer(StdioAppServer sessions, ObjectMapper json) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 阻塞读取继承管道中的 Mux 帧，按 connectionId 隔离会话和有界输入队列；管道结束时关闭全部逻辑连接。输入、输出由调用方提供。
     *
     * @throws IOException 管道损坏、帧超限或 Mux 编解码失败
     */
    public void serve(Reader input, Writer output) throws IOException {
        Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
        try {
            String line;
            while (!closed.get() && (line = readBoundedLine(input)) != null) {
                if (line.isBlank()) {
                    continue;
                }
                LocalMuxFrame frame = json.readValue(line, LocalMuxFrame.class);
                switch (frame.kind()) {
                    case OPEN -> open(frame.connectionId());
                    case DATA -> data(frame.connectionId(), frame.data());
                    case CLOSE -> close(frame.connectionId());
                }
            }
        } finally {
            close();
        }
    }

    private void open(String id) throws IOException {
        if (closed.get()) {
            return;
        }
        if (connections.size() >= MAX_CONNECTIONS) {
            send(LocalMuxFrame.close(id));
            return;
        }
        Connection connection = new Connection(id);
        if (connections.putIfAbsent(id, connection) != null) {
            send(LocalMuxFrame.close(id));
            return;
        }
        connection.start();
    }

    private void data(String id, byte[] value) {
        Connection connection = connections.get(id);
        if (connection == null || !connection.enqueue(value)) {
            close(id);
        }
    }

    private void close(String id) {
        Connection connection = connections.remove(id);
        if (connection != null) {
            connection.remoteClose();
        }
    }

    private void send(LocalMuxFrame frame) throws IOException {
        synchronized (outputLock) {
            if (closed.get() || output == null) {
                throw new IOException("mux transport is closed");
            }
            output.write(json.writeValueAsString(frame));
            output.write('\n');
            output.flush();
        }
    }

    private static String readBoundedLine(Reader input) throws IOException {
        StringBuilder value = new StringBuilder(4096);
        int next;
        while ((next = input.read()) >= 0) {
            if (next == '\n') {
                break;
            }
            if (next != '\r') {
                value.append((char) next);
            }
            if (value.length() > MAX_MUX_FRAME_CHARS) {
                throw new IOException("local mux frame exceeds limit");
            }
        }
        return next < 0 && value.isEmpty() ? null : value.toString();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        connections.values().forEach(Connection::remoteClose);
        connections.clear();
    }

    private final class Connection {
        private final String id;
        private final PipedInputStream sessionInput;
        private final PipedOutputStream ingress;
        private final Object queueLock = new Object();
        private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
        private final AtomicBoolean active = new AtomicBoolean(true);
        private long queuedBytes;
        private Thread feeder;
        private Thread session;

        private Connection(String id) throws IOException {
            this.id = id;
            this.sessionInput = new PipedInputStream(LocalMuxFrame.MAX_DATA_BYTES);
            this.ingress = new PipedOutputStream(sessionInput);
        }

        private void start() {
            feeder = Thread.ofVirtual().name("javaclaw-mux-ingress-" + id).start(this::feed);
            session = Thread.ofVirtual().name("javaclaw-mux-session-" + id).start(() -> {
                try (Reader reader = new InputStreamReader(sessionInput, StandardCharsets.UTF_8);
                        OutputStream stream = new MuxOutput(id);
                        Writer writer = new java.io.OutputStreamWriter(stream, StandardCharsets.UTF_8)) {
                    sessions.serve(reader, writer);
                } catch (IOException ignored) {
                    // A single local connection can disappear without affecting other sessions.
                } finally {
                    connections.remove(id, this);
                    remoteClose();
                    try {
                        send(LocalMuxFrame.close(id));
                    } catch (IOException ignored) {
                    }
                }
            });
        }

        private boolean enqueue(byte[] value) {
            byte[] immutable = value.clone();
            synchronized (queueLock) {
                if (!active.get()
                        || queue.size() >= MAX_PENDING_FRAMES
                        || queuedBytes + immutable.length > MAX_PENDING_BYTES) {
                    return false;
                }
                queue.addLast(immutable);
                queuedBytes += immutable.length;
                queueLock.notifyAll();
                return true;
            }
        }

        private void feed() {
            try {
                while (active.get()) {
                    byte[] value;
                    synchronized (queueLock) {
                        while (queue.isEmpty() && active.get()) {
                            queueLock.wait();
                        }
                        if (queue.isEmpty()) {
                            break;
                        }
                        value = queue.removeFirst();
                        queuedBytes -= value.length;
                    }
                    ingress.write(value);
                    ingress.flush();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
            } finally {
                try {
                    ingress.close();
                } catch (IOException ignored) {
                }
            }
        }

        private void remoteClose() {
            if (!active.getAndSet(false)) {
                return;
            }
            synchronized (queueLock) {
                queue.clear();
                queuedBytes = 0;
                queueLock.notifyAll();
            }
            try {
                ingress.close();
            } catch (IOException ignored) {
            }
            Thread currentFeeder = feeder;
            if (currentFeeder != null) {
                currentFeeder.interrupt();
            }
        }
    }

    private final class MuxOutput extends OutputStream {
        private static final int CHUNK_BYTES = 64 * 1024;
        private final String id;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream(CHUNK_BYTES);
        private boolean streamClosed;

        private MuxOutput(String id) {
            this.id = id;
        }

        @Override
        public synchronized void write(int value) throws IOException {
            requireOpen();
            pending.write(value);
            if (pending.size() >= CHUNK_BYTES) {
                flush();
            }
        }

        @Override
        public synchronized void write(byte[] value, int offset, int length) throws IOException {
            requireOpen();
            Objects.checkFromIndexSize(offset, length, value.length);
            int consumed = 0;
            while (consumed < length) {
                int accepted = Math.min(length - consumed, CHUNK_BYTES - pending.size());
                pending.write(value, offset + consumed, accepted);
                consumed += accepted;
                if (pending.size() >= CHUNK_BYTES) {
                    flush();
                }
            }
        }

        @Override
        public synchronized void flush() throws IOException {
            requireOpen();
            if (pending.size() == 0) {
                return;
            }
            byte[] value = pending.toByteArray();
            pending.reset();
            send(LocalMuxFrame.data(id, value, 0, value.length));
        }

        @Override
        public synchronized void close() throws IOException {
            if (streamClosed) {
                return;
            }
            flush();
            streamClosed = true;
        }

        private void requireOpen() throws IOException {
            if (streamClosed) {
                throw new IOException("mux connection output is closed");
            }
        }
    }
}
