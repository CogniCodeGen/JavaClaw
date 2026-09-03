package com.javaclaw.model;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

/** 模型目录测试使用的顺序本地 HTTP 服务，不依赖真实厂商或付费接口。 */
final class LocalDiscoveryServer implements AutoCloseable {
    private final ServerSocket server;
    private final List<Response> responses;
    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> targets = new CopyOnWriteArrayList<>();
    private final List<List<String>> headers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean peerClosed = new AtomicBoolean();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicReference<Socket> activeConnection = new AtomicReference<>();
    private final CountDownLatch requestReceived = new CountDownLatch(1);
    private final boolean blocking;
    private final Thread worker;

    private LocalDiscoveryServer(List<Response> responses) throws IOException {
        this(responses, false);
    }

    private LocalDiscoveryServer(List<Response> responses, boolean blocking) throws IOException {
        this.responses = List.copyOf(responses);
        this.blocking = blocking;
        server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        worker = Thread.ofVirtual().start(this::serve);
    }

    static LocalDiscoveryServer json(String json) throws IOException {
        return sequence(List.of(json));
    }

    static LocalDiscoveryServer keepAliveJson(String json) throws IOException {
        return new LocalDiscoveryServer(List.of(Response.json(json, true)));
    }

    static LocalDiscoveryServer sequence(List<String> jsonPages) throws IOException {
        return new LocalDiscoveryServer(
                jsonPages.stream().map(page -> Response.json(page, false)).toList());
    }

    static LocalDiscoveryServer blocking() throws IOException {
        return new LocalDiscoveryServer(List.of(Response.json("{}", true)), true);
    }

    static LocalDiscoveryServer redirect(URI target) throws IOException {
        return new LocalDiscoveryServer(List.of(
                new Response("302 Found", List.of("Location: " + target, "Content-Length: 0"), new byte[0], false)));
    }

    static LocalDiscoveryServer advertisedOversize() throws IOException {
        return new LocalDiscoveryServer(List.of(new Response(
                "200 OK",
                List.of(
                        "Content-Type: application/json",
                        "Content-Length: " + (DiscoveryHttpClients.MAXIMUM_RESPONSE_BYTES + 1)),
                new byte[0],
                false)));
    }

    static LocalDiscoveryServer compressedOversize() throws IOException {
        byte[] decoded = new byte[Math.toIntExact(DiscoveryHttpClients.MAXIMUM_RESPONSE_BYTES + 1)];
        java.util.Arrays.fill(decoded, (byte) 'x');
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(decoded);
        }
        return new LocalDiscoveryServer(List.of(new Response(
                "200 OK",
                List.of(
                        "Content-Type: application/json",
                        "Content-Encoding: gzip",
                        "Content-Length: " + compressed.size()),
                compressed.toByteArray(),
                false)));
    }

    URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getLocalPort() + "/v1");
    }

    int requests() {
        return requests.get();
    }

    String target(int index) {
        return targets.get(index);
    }

    boolean authorizationPresent(int index) {
        return headers.get(index).stream()
                .anyMatch(line -> line.toLowerCase(Locale.ROOT).startsWith("authorization:"));
    }

    boolean awaitPeerClosed() throws InterruptedException {
        worker.join(TimeUnit.SECONDS.toMillis(2));
        return peerClosed.get();
    }

    boolean awaitRequest() throws InterruptedException {
        return requestReceived.await(2, TimeUnit.SECONDS);
    }

    @Override
    public void close() throws Exception {
        server.close();
        Socket connection = activeConnection.get();
        if (connection != null) {
            connection.close();
        }
        worker.join(TimeUnit.SECONDS.toMillis(2));
        Throwable problem = failure.get();
        if (problem != null) {
            throw new AssertionError("本地模型目录服务执行失败", problem);
        }
    }

    private void serve() {
        try {
            for (Response response : responses) {
                serve(response);
            }
        } catch (SocketTimeoutException timeout) {
            failure.compareAndSet(null, new AssertionError("客户端没有关闭模型目录连接", timeout));
        } catch (IOException exception) {
            if (!server.isClosed()) {
                failure.compareAndSet(null, exception);
            }
        }
    }

    private void serve(Response response) throws IOException {
        try (Socket connection = server.accept()) {
            activeConnection.set(connection);
            BufferedReader input =
                    new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
            String requestLine = input.readLine();
            targets.add(requestTarget(requestLine));
            headers.add(readHeaders(input));
            requests.incrementAndGet();
            requestReceived.countDown();
            if (blocking) {
                connection.setSoTimeout(Math.toIntExact(TimeUnit.SECONDS.toMillis(2)));
                peerClosed.set(input.read() < 0);
                return;
            }
            writeResponse(connection.getOutputStream(), response);
            if (response.keepAlive()) {
                connection.setSoTimeout(Math.toIntExact(TimeUnit.SECONDS.toMillis(2)));
                peerClosed.set(input.read() < 0);
            }
        } finally {
            activeConnection.set(null);
        }
    }

    private static List<String> readHeaders(BufferedReader input) throws IOException {
        List<String> values = new ArrayList<>();
        String line;
        while ((line = input.readLine()) != null && !line.isEmpty()) {
            values.add(line);
        }
        return List.copyOf(values);
    }

    private static void writeResponse(OutputStream output, Response response) throws IOException {
        output.write(("HTTP/1.1 " + response.status() + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (String header : response.headers()) {
            output.write((header + "\r\n").getBytes(StandardCharsets.US_ASCII));
        }
        String connection = response.keepAlive() ? "keep-alive" : "close";
        output.write(("Connection: " + connection + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(response.body());
        output.flush();
    }

    private static String requestTarget(String requestLine) {
        if (requestLine == null) {
            throw new IllegalStateException("请求行缺失");
        }
        String[] parts = requestLine.split(" ", 3);
        if (parts.length != 3) {
            throw new IllegalStateException("请求行格式不合法");
        }
        return parts[1];
    }

    private record Response(String status, List<String> headers, byte[] body, boolean keepAlive) {
        private Response {
            headers = List.copyOf(headers);
            body = body.clone();
        }

        private static Response json(String json, boolean keepAlive) {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            return new Response(
                    "200 OK",
                    List.of("Content-Type: application/json", "Content-Length: " + body.length),
                    body,
                    keepAlive);
        }
    }
}
