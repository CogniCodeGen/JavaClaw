package com.javaclaw.nativehost.transport.windows;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.nativehost.ffm.WindowsNamedPipes;
import com.javaclaw.protocol.JsonRpcCodec;
import com.javaclaw.protocol.LocalMuxFrame;

/** Current-user Named Pipe bridge. App Server and SDK never call FFM directly. */
public final class WindowsTransportHostMain {
    private static final int PIPE_BUFFER_BYTES = 64 * 1024;
    private static final int MAX_CONNECTIONS = 128;
    private static final int MAX_QUEUE_FRAMES = 1_024;
    private static final long MAX_QUEUE_BYTES = 8L * 1024L * 1024L;
    private static final int MAX_MUX_LINE_CHARS = 2 * 1024 * 1024;

    private WindowsTransportHostMain() {}

    /** 启动本机 Named Pipe 的 client bridge 或 server Mux 模式；仅 server 模式持有 App Server 子进程，FFM 保持在此独立 JVM 中。 */
    public static void main(String[] args) throws Exception {
        Arguments parsed = Arguments.parse(args);
        if (parsed.mode.equals("client")) {
            runClient(parsed.pipeName);
        } else {
            runServer(parsed.pipeName, parsed.appServerCommand);
        }
    }

    private static void runClient(String pipeName) throws Exception {
        try (WindowsNamedPipes.Pipe pipe = WindowsNamedPipes.connectClient(pipeName)) {
            CountDownLatch finished = new CountDownLatch(1);
            Thread.ofVirtual().name("named-pipe-client-read").start(() -> {
                byte[] buffer = new byte[PIPE_BUFFER_BYTES];
                try {
                    int count;
                    while ((count = pipe.read(buffer)) >= 0) {
                        System.out.write(buffer, 0, count);
                        System.out.flush();
                    }
                } catch (IOException ignored) {
                } finally {
                    finished.countDown();
                }
            });
            Thread.ofVirtual().name("named-pipe-client-write").start(() -> {
                byte[] buffer = new byte[PIPE_BUFFER_BYTES];
                try {
                    int count;
                    while ((count = System.in.read(buffer)) >= 0) {
                        if (count > 0) {
                            pipe.write(buffer, 0, count);
                        }
                    }
                } catch (IOException ignored) {
                } finally {
                    finished.countDown();
                }
            });
            finished.await();
        }
    }

    private static void runServer(String pipeName, List<String> appServerCommand) throws Exception {
        ArrayList<String> command = new ArrayList<>(appServerCommand);
        if (!command.contains("--windows-mux")) {
            command.add("--windows-mux");
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process appServer = builder.start();
        ObjectMapper json = new JsonRpcCodec().mapper();
        ConcurrentHashMap<String, PipeConnection> connections = new ConcurrentHashMap<>();
        try (MuxSender inbound = new MuxSender(appServer, json)) {
            Thread outbound = Thread.ofVirtual()
                    .name("app-server-mux-output")
                    .start(() -> readAppServerOutput(appServer, json, connections));
            boolean first = true;
            while (appServer.isAlive()) {
                WindowsNamedPipes.ServerPipe pipe = WindowsNamedPipes.createServer(pipeName, PIPE_BUFFER_BYTES, first);
                first = false;
                try {
                    pipe.acceptCurrentUser();
                    if (connections.size() >= MAX_CONNECTIONS) {
                        pipe.close();
                        continue;
                    }
                    String id = "connection_" + UUID.randomUUID().toString().replace("-", "");
                    PipeConnection connection = new PipeConnection(id, pipe, inbound, connections);
                    connections.put(id, connection);
                    connection.start();
                } catch (Throwable failure) {
                    pipe.close();
                    if (!appServer.isAlive()) {
                        break;
                    }
                }
            }
            connections.values().forEach(PipeConnection::close);
            connections.clear();
            outbound.interrupt();
        } finally {
            if (appServer.isAlive()) {
                try {
                    appServer.descendants().forEach(ProcessHandle::destroyForcibly);
                } catch (RuntimeException ignored) {
                }
                appServer.destroyForcibly();
            }
        }
        int exit = appServer.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("App Server exited with " + exit);
        }
    }

    private static void readAppServerOutput(
            Process appServer, ObjectMapper json, ConcurrentHashMap<String, PipeConnection> connections) {
        try (BufferedReader input =
                new BufferedReader(new InputStreamReader(appServer.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = readBoundedLine(input)) != null) {
                if (line.isBlank()) {
                    continue;
                }
                LocalMuxFrame frame = json.readValue(line, LocalMuxFrame.class);
                PipeConnection connection = connections.get(frame.connectionId());
                if (connection == null) {
                    continue;
                }
                if (frame.kind() == LocalMuxFrame.Kind.DATA) {
                    if (!connection.offer(frame.data())) {
                        connection.close();
                    }
                } else if (frame.kind() == LocalMuxFrame.Kind.CLOSE) {
                    connection.close();
                }
            }
        } catch (Exception ignored) {
        } finally {
            connections.values().forEach(PipeConnection::close);
        }
    }

    private static String readBoundedLine(BufferedReader input) throws IOException {
        StringBuilder line = new StringBuilder(4096);
        int value;
        while ((value = input.read()) >= 0) {
            if (value == '\n') {
                break;
            }
            if (value != '\r') {
                line.append((char) value);
            }
            if (line.length() > MAX_MUX_LINE_CHARS) {
                throw new IOException("App Server mux frame exceeds limit");
            }
        }
        return value < 0 && line.isEmpty() ? null : line.toString();
    }

    private static final class PipeConnection {
        private final String id;
        private final WindowsNamedPipes.ServerPipe pipe;
        private final MuxSender inbound;
        private final ConcurrentHashMap<String, PipeConnection> owner;
        private final BoundedBytes outbound = new BoundedBytes();
        private final AtomicBoolean active = new AtomicBoolean(true);

        private PipeConnection(
                String id,
                WindowsNamedPipes.ServerPipe pipe,
                MuxSender inbound,
                ConcurrentHashMap<String, PipeConnection> owner) {
            this.id = id;
            this.pipe = pipe;
            this.inbound = inbound;
            this.owner = owner;
        }

        private void start() throws IOException {
            inbound.send(LocalMuxFrame.open(id));
            Thread.ofVirtual().name("named-pipe-read-" + id).start(this::read);
            Thread.ofVirtual().name("named-pipe-write-" + id).start(this::write);
        }

        private void read() {
            byte[] buffer = new byte[PIPE_BUFFER_BYTES];
            try {
                int count;
                while (active.get() && (count = pipe.read(buffer)) >= 0) {
                    if (count > 0) {
                        inbound.send(LocalMuxFrame.data(id, buffer, 0, count));
                    }
                }
            } catch (IOException ignored) {
            } finally {
                close();
            }
        }

        private void write() {
            try {
                while (active.get()) {
                    byte[] value = outbound.take();
                    if (value == null) {
                        break;
                    }
                    pipe.write(value, 0, value.length);
                }
            } catch (Exception ignored) {
            } finally {
                close();
            }
        }

        private boolean offer(byte[] value) {
            return active.get() && outbound.offer(value);
        }

        private void close() {
            if (!active.getAndSet(false)) {
                return;
            }
            owner.remove(id, this);
            outbound.close();
            pipe.close();
            try {
                inbound.send(LocalMuxFrame.close(id));
            } catch (IOException ignored) {
            }
        }
    }

    private static final class MuxSender implements AutoCloseable {
        private final ObjectMapper json;
        private final BufferedWriter output;
        private final BoundedBytes queue = new BoundedBytes();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Thread writer;

        private MuxSender(Process appServer, ObjectMapper json) {
            this.json = json;
            this.output =
                    new BufferedWriter(new OutputStreamWriter(appServer.getOutputStream(), StandardCharsets.UTF_8));
            writer = Thread.ofVirtual().name("app-server-mux-input").start(this::writeLoop);
        }

        private void send(LocalMuxFrame frame) throws IOException {
            if (closed.get()) {
                throw new IOException("App Server mux input is closed");
            }
            byte[] encoded = (encode(frame) + "\n").getBytes(StandardCharsets.UTF_8);
            if (!queue.offer(encoded)) {
                close();
                throw new IOException("App Server mux input queue exceeded limit");
            }
        }

        private String encode(LocalMuxFrame frame) throws IOException {
            try {
                return json.writeValueAsString(frame);
            } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
                throw new IOException("cannot encode local mux frame", failure);
            }
        }

        private void writeLoop() {
            try {
                byte[] value;
                while ((value = queue.take()) != null) {
                    output.write(new String(value, StandardCharsets.UTF_8));
                    output.flush();
                }
            } catch (Exception ignored) {
            } finally {
                close();
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            queue.close();
            try {
                output.close();
            } catch (IOException ignored) {
            }
            writer.interrupt();
        }
    }

    private static final class BoundedBytes {
        private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(MAX_QUEUE_FRAMES);
        private final AtomicLong bytes = new AtomicLong();
        private final AtomicBoolean closed = new AtomicBoolean();

        private boolean offer(byte[] value) {
            byte[] immutable = Objects.requireNonNull(value, "value").clone();
            if (closed.get() || immutable.length > LocalMuxFrame.MAX_DATA_BYTES * 2L) {
                return false;
            }
            long current = bytes.addAndGet(immutable.length);
            if (current > MAX_QUEUE_BYTES || !queue.offer(immutable)) {
                bytes.addAndGet(-immutable.length);
                return false;
            }
            return true;
        }

        private byte[] take() throws InterruptedException {
            while (true) {
                if (closed.get() && queue.isEmpty()) {
                    return null;
                }
                byte[] value = queue.poll(1, TimeUnit.SECONDS);
                if (value != null) {
                    bytes.addAndGet(-value.length);
                    return value;
                }
            }
        }

        private void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            queue.clear();
            bytes.set(0);
        }
    }

    private record Arguments(String mode, String pipeName, List<String> appServerCommand) {
        private static Arguments parse(String[] args) {
            if (args.length < 3 || (!"server".equals(args[0]) && !"client".equals(args[0]))) {
                throw new IllegalArgumentException(
                        "usage: <server|client> --pipe javaclaw-v4-NAME [-- app-server argv...]");
            }
            if (!"--pipe".equals(args[1])) {
                throw new IllegalArgumentException("--pipe is required");
            }
            String pipe = args[2];
            if ("client".equals(args[0])) {
                if (args.length != 3) {
                    throw new IllegalArgumentException("client mode accepts only --pipe");
                }
                return new Arguments("client", pipe, List.of());
            }
            if (args.length < 5 || !"--".equals(args[3])) {
                throw new IllegalArgumentException("server mode requires -- followed by App Server argv");
            }
            return new Arguments("server", pipe, List.copyOf(Arrays.asList(args).subList(4, args.length)));
        }
    }
}
