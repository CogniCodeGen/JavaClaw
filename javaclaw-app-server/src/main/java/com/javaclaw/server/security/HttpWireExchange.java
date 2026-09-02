package com.javaclaw.server.security;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.javaclaw.api.CancellationToken;

/** 写入一条无连接复用的 HTTP/1.1 请求，并在可关闭虚拟线程中读取有界响应。 */
final class HttpWireExchange {
    private static final Set<String> FORBIDDEN_REQUEST_HEADERS = Set.of(
            "accept-encoding",
            "connection",
            "content-length",
            "expect",
            "host",
            "keep-alive",
            "proxy-connection",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade");
    private static final int MAXIMUM_REQUEST_HEADERS = 64;
    private static final int MAXIMUM_REQUEST_HEADER_BYTES = 32 * 1024;

    private final Transport transport;

    HttpWireExchange(Transport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    WireResponse exchange(
            BrokerTarget target,
            Request request,
            int maximumResponseBytes,
            BrokerDeadline deadline,
            CancellationToken cancellation)
            throws Exception {
        AtomicReference<AutoCloseable> pending = new AtomicReference<>();
        FutureTask<WireResponse> task = new FutureTask<>(
                () -> blocking(target, request, maximumResponseBytes, deadline, cancellation, pending));
        Thread worker = Thread.ofVirtual().name("javaclaw-network-broker").start(task);
        try {
            while (true) {
                int wait = deadline.timeoutMillis(cancellation, 100);
                try {
                    return task.get(wait, TimeUnit.MILLISECONDS);
                } catch (TimeoutException stillRunning) {
                    // 周期性检查取消和总时限；finally 会关闭当前 socket。
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            InterruptedIOException failure = new InterruptedIOException("Network Broker was interrupted");
            failure.initCause(interrupted);
            throw failure;
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IOException("Network Broker exchange failed", cause);
        } finally {
            if (!task.isDone()) {
                close(pending.get());
                task.cancel(true);
                worker.interrupt();
            }
        }
    }

    private WireResponse blocking(
            BrokerTarget target,
            Request request,
            int maximumResponseBytes,
            BrokerDeadline deadline,
            CancellationToken cancellation,
            AtomicReference<AutoCloseable> pending)
            throws IOException {
        try (Connection connection = transport.open(target, deadline, cancellation, pending::set)) {
            pending.set(connection);
            write(connection.output(), target, request);
            return new HttpResponseReader(connection.input()).read(request.method(), maximumResponseBytes);
        } finally {
            pending.set(null);
        }
    }

    private static void write(OutputStream output, BrokerTarget target, Request request) throws IOException {
        StringBuilder head = new StringBuilder(512);
        byte[] body = request.body();
        head.append(request.method()).append(' ').append(target.requestTarget()).append(" HTTP/1.1\r\n");
        head.append("Host: ").append(target.hostHeader()).append("\r\n");
        head.append("Connection: close\r\nAccept-Encoding: identity\r\n");
        if (request.headers().size() > MAXIMUM_REQUEST_HEADERS) {
            throw new SecurityException("HTTP request header count exceeds the Broker limit");
        }
        request.headers().forEach((name, values) -> appendHeader(head, name, values));
        head.append("Content-Length: ").append(body.length).append("\r\n\r\n");
        byte[] encoded = head.toString().getBytes(StandardCharsets.ISO_8859_1);
        if (encoded.length > MAXIMUM_REQUEST_HEADER_BYTES) {
            throw new SecurityException("HTTP request headers exceed the Broker byte limit");
        }
        output.write(encoded);
        output.write(body);
        output.flush();
    }

    private static void appendHeader(StringBuilder target, String name, List<String> values) {
        if (FORBIDDEN_REQUEST_HEADERS.contains(name) || values.isEmpty()) {
            throw new SecurityException("HTTP request header is controlled by the Broker: " + name);
        }
        values.forEach(value -> target.append(name).append(": ").append(value).append("\r\n"));
    }

    private static void close(AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception ignored) {
        }
    }

    record Request(String method, Map<String, List<String>> headers, byte[] body) {
        Request {
            Objects.requireNonNull(method, "method");
            headers = Map.copyOf(headers);
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    record WireResponse(int statusCode, Map<String, List<String>> headers, byte[] body, boolean truncated) {}

    @FunctionalInterface
    interface Transport {
        Connection open(
                BrokerTarget target,
                BrokerDeadline deadline,
                CancellationToken cancellation,
                Consumer<AutoCloseable> pending)
                throws IOException;
    }

    interface Connection extends AutoCloseable {
        InputStream input() throws IOException;

        OutputStream output() throws IOException;

        @Override
        void close() throws IOException;
    }
}
