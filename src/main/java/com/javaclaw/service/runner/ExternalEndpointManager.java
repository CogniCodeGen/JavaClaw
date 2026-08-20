package com.javaclaw.service.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.service.api.CancellationToken;
import com.javaclaw.service.api.ExternalEndpointDescriptor;
import com.javaclaw.service.api.ExternalEndpointRegistry;
import com.javaclaw.service.api.ExternalInvocation;
import com.javaclaw.service.api.ExternalRequestHandler;
import com.javaclaw.service.api.ExternalResponse;
import com.javaclaw.service.api.PluginLogger;
import com.javaclaw.service.api.Registration;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Runner-owned external listeners. Plugins supply handlers but never own sockets or pools. */
final class ExternalEndpointManager implements ExternalEndpointRegistry, AutoCloseable {
    private volatile Map<String, ServicePluginWire.Endpoint> configurations;
    private final Map<String, Listener> listeners = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final RunnerExecutor timers;
    private final boolean ownsTimers;
    private final ObjectMapper json;
    private final PluginLogger log;
    private final AtomicBoolean accepting = new AtomicBoolean(true);

    ExternalEndpointManager(List<ServicePluginWire.Endpoint> configurations,
                            ExecutorService executor, RunnerExecutor timers,
                            ObjectMapper json, PluginLogger log) {
        this(configurations, executor, timers, false, json, log);
    }

    /** Compatibility constructor used by isolated listener tests and embedders. */
    ExternalEndpointManager(List<ServicePluginWire.Endpoint> configurations,
                            ExecutorService executor, ObjectMapper json, PluginLogger log) {
        this(configurations, executor, new RunnerExecutor(), true, json, log);
    }

    private ExternalEndpointManager(List<ServicePluginWire.Endpoint> configurations,
                                    ExecutorService executor, RunnerExecutor timers,
                                    boolean ownsTimers, ObjectMapper json, PluginLogger log) {
        this.configurations = checkedConfigurations(configurations);
        this.executor = executor;
        this.timers = timers;
        this.ownsTimers = ownsTimers;
        this.json = json;
        this.log = log;
    }

    @Override
    public synchronized Registration register(
            ExternalEndpointDescriptor descriptor, ExternalRequestHandler handler) {
        ServicePluginWire.Endpoint config = configurations.get(descriptor.id());
        if (config == null) {
            throw new IllegalStateException("Desktop did not authorize endpoint: " + descriptor.id());
        }
        if (!descriptor.protocol().name().equalsIgnoreCase(config.protocol())) {
            throw new IllegalArgumentException("endpoint protocol differs from Desktop configuration");
        }
        Listener listener;
        try {
            listener = switch (descriptor.protocol()) {
                case HTTP, HTTPS, SSE -> http(descriptor, config, handler);
                case TCP -> tcp(descriptor, config, handler);
                case WEBSOCKET -> throw new UnsupportedOperationException(
                        "WebSocket is a future service-plugin capability");
            };
        } catch (Exception failure) {
            throw new IllegalStateException("cannot start external endpoint " + descriptor.id(), failure);
        }
        if (listeners.putIfAbsent(descriptor.id(), listener) != null) {
            listener.close();
            throw new IllegalStateException("endpoint already registered: " + descriptor.id());
        }
        return () -> {
            synchronized (ExternalEndpointManager.this) {
                if (listeners.remove(descriptor.id(), listener)) listener.close();
            }
        };
    }

    /** Replaces Desktop-authorized endpoint settings while no listener is active. */
    synchronized void reconfigure(List<ServicePluginWire.Endpoint> values) {
        if (!listeners.isEmpty()) {
            throw new IllegalStateException("external listeners must be stopped before hot configuration");
        }
        configurations = checkedConfigurations(values);
    }

    List<ServicePluginWire.Endpoint> configuredEndpoints() {
        return List.copyOf(configurations.values());
    }

    private static Map<String, ServicePluginWire.Endpoint> checkedConfigurations(
            List<ServicePluginWire.Endpoint> values) {
        Map<String, ServicePluginWire.Endpoint> byId = new LinkedHashMap<>();
        for (ServicePluginWire.Endpoint endpoint : values == null ? List.<ServicePluginWire.Endpoint>of() : values) {
            if (endpoint == null || endpoint.id() == null || endpoint.id().isBlank()) {
                throw new IllegalArgumentException("endpoint configuration id is required");
            }
            if (byId.putIfAbsent(endpoint.id(), endpoint) != null) {
                throw new IllegalArgumentException("duplicate endpoint configuration: " + endpoint.id());
            }
        }
        return Map.copyOf(byId);
    }

    List<Map<String, Object>> snapshot() {
        return listeners.entrySet().stream().map(entry -> Map.<String, Object>of(
                "id", entry.getKey(),
                "address", entry.getValue().address().getHostString(),
                "port", entry.getValue().address().getPort(),
                "connections", entry.getValue().connections(),
                "accepting", accepting.get())).toList();
    }

    void stopAccepting() {
        if (!accepting.compareAndSet(true, false)) return;
        listeners.values().forEach(Listener::close);
    }

    @Override
    public void close() {
        accepting.set(false);
        listeners.values().forEach(Listener::close);
        listeners.clear();
        if (ownsTimers) timers.close();
    }

    private Listener http(ExternalEndpointDescriptor descriptor,
                          ServicePluginWire.Endpoint config,
                          ExternalRequestHandler handler) throws Exception {
        InetAddress address = validateAddress(config);
        if (config.apiKey() == null || config.apiKey().length() < 24) {
            throw new SecurityException("external endpoint requires a high-entropy API key");
        }
        boolean https = descriptor.protocol() == ExternalEndpointDescriptor.Protocol.HTTPS
                || config.tlsEnabled();
        if (!address.isLoopbackAddress() && !https && !config.allowInsecureLan()) {
            throw new SecurityException("insecure LAN endpoint was not explicitly approved");
        }
        HttpServer server;
        if (https) {
            SSLContext context = sslContext(config);
            HttpsServer secure = HttpsServer.create(new InetSocketAddress(address, config.port()), 32);
            secure.setHttpsConfigurator(new HttpsConfigurator(context));
            server = secure;
        } else {
            server = HttpServer.create(new InetSocketAddress(address, config.port()), 32);
        }
        EndpointGate gate = new EndpointGate(config);
        server.setExecutor(executor);
        server.createContext(descriptor.pathPrefix(), exchange ->
                handle(exchange, config, handler, gate));
        server.start();
        log.info("external endpoint " + descriptor.id() + " listening on "
                + server.getAddress().getHostString() + ":" + server.getAddress().getPort());
        return new HttpListener(server, gate);
    }

    /**
     * Framed TCP contract: the first length-prefixed UTF-8 frame is the API key; every following
     * frame is one request body. Responses contain status, content-type length/value and body
     * length/value. A streaming response uses body length -1 followed by length-prefixed chunks and
     * a final -1 chunk. The Runner owns every socket and closes all connections during drain.
     */
    private Listener tcp(ExternalEndpointDescriptor descriptor,
                         ServicePluginWire.Endpoint config,
                         ExternalRequestHandler handler) throws Exception {
        InetAddress address = validateAddress(config);
        if (config.apiKey() == null || config.apiKey().length() < 24) {
            throw new SecurityException("external endpoint requires a high-entropy API key");
        }
        if (!address.isLoopbackAddress() && !config.tlsEnabled() && !config.allowInsecureLan()) {
            throw new SecurityException("insecure LAN endpoint was not explicitly approved");
        }
        ServerSocket server = config.tlsEnabled()
                ? sslContext(config).getServerSocketFactory().createServerSocket()
                : new ServerSocket();
        server.bind(new InetSocketAddress(address, config.port()), 32);
        TcpListener listener = new TcpListener(server, Math.max(1, config.maxConnections()));
        EndpointGate gate = new EndpointGate(config);
        executor.submit(() -> acceptTcp(listener, descriptor, config, handler, gate));
        log.info("external TCP endpoint " + descriptor.id() + " listening on "
                + server.getLocalSocketAddress());
        return listener;
    }

    private void acceptTcp(TcpListener listener, ExternalEndpointDescriptor descriptor,
                           ServicePluginWire.Endpoint config, ExternalRequestHandler handler,
                           EndpointGate gate) {
        while (accepting.get() && !listener.closed.get()) {
            try {
                Socket socket = listener.server.accept();
                if (!listener.permits.tryAcquire()) {
                    socket.close();
                    continue;
                }
                listener.sockets.add(socket);
                executor.submit(() -> handleTcp(listener, socket, descriptor, config, handler, gate));
            } catch (IOException failure) {
                if (!listener.closed.get()) log.warn("external TCP accept failed: " + failure.getMessage());
                return;
            }
        }
    }

    private void handleTcp(TcpListener listener, Socket socket,
                           ExternalEndpointDescriptor descriptor,
                           ServicePluginWire.Endpoint config, ExternalRequestHandler handler,
                           EndpointGate gate) {
        AtomicBoolean cancelled = new AtomicBoolean();
        try (socket;
             DataInputStream input = new DataInputStream(socket.getInputStream());
             DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {
            socket.setKeepAlive(true);
            String apiKey = new String(tcpFrame(input, 4_096), StandardCharsets.UTF_8).strip();
            String credentialId = authenticatedCredential(apiKey, config.apiKey());
            if (credentialId.isEmpty()) {
                new TcpResponse(output).send(401, "application/json", Map.of(),
                        json.writeValueAsBytes(Map.of("error", "unauthorized")));
                return;
            }
            while (accepting.get() && !socket.isClosed()) {
                byte[] body;
                try {
                    body = tcpFrame(input, Math.min(config.maxRequestBytes(),
                            ServicePluginWire.MAX_REQUEST_BYTES));
                } catch (EOFException disconnected) {
                    return;
                }
                TcpResponse response = new TcpResponse(output, cancelled);
                if (!gate.tryAcquire()) {
                    response.send(429, "application/json", Map.of(),
                            json.writeValueAsBytes(Map.of("error", "rate_limited")));
                    continue;
                }
                RequestLease lease = new RequestLease(gate);
                try {
                    String requestId = UUID.randomUUID().toString();
                    RequestDeadline deadline = new RequestDeadline(
                            config, response, cancelled, Thread.currentThread(), lease);
                    try {
                        handler.handle(new ExternalInvocation() {
                        @Override public String requestId() { return requestId; }
                        @Override public String credentialId() { return credentialId; }
                        @Override public String method() { return "TCP"; }
                        @Override public String path() { return descriptor.pathPrefix(); }
                        @Override public Map<String, List<String>> headers() { return Map.of(); }
                        @Override public byte[] body() { return body.clone(); }
                        @Override public InetSocketAddress remoteAddress() {
                            return (InetSocketAddress) socket.getRemoteSocketAddress();
                        }
                        @Override public Instant deadline() { return deadline.deadline(); }
                        @Override public CancellationToken cancellation() { return cancelled::get; }
                        @Override public ExternalResponse response() { return response; }
                        });
                        if (!response.committed() && !deadline.timedOut()) {
                            response.send(500, "application/json", Map.of(),
                                    json.writeValueAsBytes(Map.of("error", "missing_response")));
                        }
                    } finally {
                        deadline.close();
                    }
                } catch (Throwable failure) {
                    log.error("external TCP request failed", failure);
                    if (!response.committed()) response.send(500, "application/json", Map.of(),
                            json.writeValueAsBytes(Map.of("error", "internal_error")));
                } finally {
                    response.closeStream();
                    lease.close();
                }
                if (cancelled.get()) return;
            }
        } catch (IOException ignored) {
            cancelled.set(true);
        } finally {
            cancelled.set(true);
            listener.sockets.remove(socket);
            listener.permits.release();
        }
    }

    private static byte[] tcpFrame(DataInputStream input, long maximum) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maximum) throw new IOException("invalid TCP frame length");
        byte[] value = input.readNBytes(length);
        if (value.length != length) throw new EOFException("truncated TCP frame");
        return value;
    }

    private void handle(HttpExchange exchange, ServicePluginWire.Endpoint config,
                        ExternalRequestHandler handler, EndpointGate gate) throws IOException {
        if (!accepting.get()) { error(exchange, 503, "service_draining"); return; }
        String credentialId = authenticatedCredential(exchange, config.apiKey());
        if (credentialId.isEmpty()) { error(exchange, 401, "unauthorized"); return; }
        if (!gate.tryAcquire()) { error(exchange, 429, "rate_limited"); return; }
        AtomicBoolean cancelled = new AtomicBoolean();
        ExchangeResponse response = new ExchangeResponse(exchange, cancelled);
        RequestLease lease = new RequestLease(gate);
        RequestDeadline deadline = new RequestDeadline(
                config, response, cancelled, Thread.currentThread(), lease);
        try {
            byte[] body = readBounded(exchange.getRequestBody(), Math.min(
                    Math.max(1, config.maxRequestBytes()), ServicePluginWire.MAX_REQUEST_BYTES));
            handler.handle(new ExternalInvocation() {
                @Override public String requestId() { return UUID.randomUUID().toString(); }
                @Override public String credentialId() { return credentialId; }
                @Override public String method() { return exchange.getRequestMethod(); }
                @Override public String path() { return exchange.getRequestURI().getPath(); }
                @Override public Map<String, List<String>> headers() {
                    return Map.copyOf(exchange.getRequestHeaders());
                }
                @Override public byte[] body() { return body.clone(); }
                @Override public InetSocketAddress remoteAddress() { return exchange.getRemoteAddress(); }
                @Override public Instant deadline() { return deadline.deadline(); }
                @Override public CancellationToken cancellation() { return cancelled::get; }
                @Override public ExternalResponse response() { return response; }
            });
            if (!response.committed() && !deadline.timedOut()) {
                error(exchange, 500, "missing_response");
            }
        } catch (IllegalArgumentException badRequest) {
            if (!response.committed()) error(exchange, 400, "invalid_request");
        } catch (Throwable failure) {
            log.error("external endpoint request failed", failure);
            if (!response.committed()) error(exchange, 500, "internal_error");
        } finally {
            deadline.close();
            cancelled.set(true);
            response.closeQuietly();
            lease.close();
        }
    }

    private final class RequestDeadline implements AutoCloseable {
        private static final int ACTIVE = 0;
        private static final int CLOSED = 1;
        private static final int TIMING_OUT = 2;
        private static final int TIMED_OUT = 3;

        private final Instant deadline;
        private final AtomicInteger phase = new AtomicInteger(ACTIVE);
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<Registration> forcedInterrupt = new AtomicReference<>();
        private final CountDownLatch timeoutFinished = new CountDownLatch(1);
        private final Registration registration;

        private RequestDeadline(ServicePluginWire.Endpoint config, ExternalResponse response,
                                AtomicBoolean cancelled, Thread carrier, RequestLease lease) {
            Duration timeout = Duration.ofSeconds(config.requestTimeoutSeconds());
            deadline = Instant.now().plus(timeout);
            registration = timers.schedule(timeout, () -> {
                if (!phase.compareAndSet(ACTIVE, TIMING_OUT)) return;
                try {
                    cancelled.set(true);
                    boolean committed = response instanceof ExchangeResponse http
                            ? http.committed() : response instanceof TcpResponse tcp && tcp.committed();
                    if (!committed) {
                        try {
                            response.send(504, "application/json; charset=utf-8", Map.of(),
                                    json.writeValueAsBytes(Map.of("error", Map.of(
                                            "message", "request timed out",
                                            "type", "service_plugin_error",
                                            "code", "request_timeout"))));
                        } catch (RuntimeException | IOException failure) {
                            log.debug("external timeout response failed: " + failure.getMessage());
                        }
                    }
                    lease.close();
                    // Give every handler a brief opportunity to observe cooperative cancellation. In
                    // particular, interrupting an HttpServer carrier immediately after writing a 504
                    // can make the server close the exchange before its header bytes are flushed.
                    Registration interrupt = timers.schedule(
                            Duration.ofMillis(250), carrier::interrupt);
                    if (closed.get() || !forcedInterrupt.compareAndSet(null, interrupt)) {
                        interrupt.close();
                    }
                } finally {
                    phase.compareAndSet(TIMING_OUT, TIMED_OUT);
                    timeoutFinished.countDown();
                }
            });
        }

        private Instant deadline() { return deadline; }
        private boolean timedOut() { return phase.get() >= TIMING_OUT; }
        @Override public void close() {
            closed.set(true);
            if (phase.compareAndSet(ACTIVE, CLOSED)) registration.close();
            else if (phase.get() >= TIMING_OUT) awaitTimeoutCompletion();
            Registration interrupt = forcedInterrupt.getAndSet(null);
            if (interrupt != null) interrupt.close();
        }

        private void awaitTimeoutCompletion() {
            long remaining = Duration.ofSeconds(1).toNanos();
            long started = System.nanoTime();
            boolean interrupted = false;
            while (timeoutFinished.getCount() > 0 && remaining > 0) {
                try {
                    timeoutFinished.await(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException failure) {
                    interrupted = true;
                }
                remaining = Duration.ofSeconds(1).toNanos() - (System.nanoTime() - started);
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** A timeout and a returning handler may race; quota resources are released exactly once. */
    private static final class RequestLease implements AutoCloseable {
        private final EndpointGate gate;
        private final AtomicBoolean closed = new AtomicBoolean();

        private RequestLease(EndpointGate gate) { this.gate = gate; }

        @Override public void close() {
            if (closed.compareAndSet(false, true)) gate.release();
        }
    }

    private static InetAddress validateAddress(ServicePluginWire.Endpoint config) throws Exception {
        InetAddress address = InetAddress.getByName(config.bindAddress());
        if (address.isAnyLocalAddress()) throw new SecurityException("wildcard bind is forbidden");
        if (!address.isLoopbackAddress() && !isPrivate(address)) {
            throw new SecurityException("only loopback or private LAN addresses are allowed");
        }
        return address;
    }

    private static boolean isPrivate(InetAddress address) {
        if (address instanceof Inet4Address) {
            byte[] value = address.getAddress();
            int a = value[0] & 0xff;
            int b = value[1] & 0xff;
            return a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168);
        }
        return address instanceof Inet6Address && address.isSiteLocalAddress();
    }

    private static SSLContext sslContext(ServicePluginWire.Endpoint config) throws Exception {
        if (config.keyStorePath() == null || config.keyStorePath().isBlank()) {
            throw new SecurityException("HTTPS endpoint requires a PKCS#12 key store");
        }
        Path path = Path.of(config.keyStorePath()).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new SecurityException("TLS key store is not a regular file");
        }
        char[] password = config.keyStorePassword() == null
                ? new char[0] : config.keyStorePassword().toCharArray();
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(path)) { store.load(input, password); }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, password);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(keys.getKeyManagers(), null, null);
        java.util.Arrays.fill(password, '\0');
        return ssl;
    }

    private static String authenticatedCredential(HttpExchange exchange, String key) {
        String actual = exchange.getRequestHeaders().getFirst("Authorization");
        if (actual == null || !actual.regionMatches(true, 0, "Bearer ", 0, 7)) return "";
        return authenticatedCredential(actual.substring(7).strip(), key);
    }

    private static String authenticatedCredential(String raw, String key) {
        if (raw == null || key == null) return "";
        if (key.startsWith("pbkdf2$")) {
            for (String verifier : key.split(";")) {
                String[] fields = verifier.split("\\$", -1);
                if (fields.length != 4 || !"pbkdf2".equals(fields[0])
                        || !raw.startsWith(fields[1])) continue;
                try {
                    byte[] salt = Base64.getUrlDecoder().decode(fields[2]);
                    PBEKeySpec spec = new PBEKeySpec(raw.toCharArray(), salt, 120_000, 256);
                    byte[] derived;
                    try {
                        derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                                .generateSecret(spec).getEncoded();
                    } finally { spec.clearPassword(); }
                    if (MessageDigest.isEqual(derived,
                            Base64.getUrlDecoder().decode(fields[3]))) return fields[1];
                } catch (Exception invalid) {
                    // Ignore one malformed verifier without disabling the remaining keys.
                }
            }
            return "";
        }
        byte[] expected = key.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, raw.getBytes(StandardCharsets.UTF_8))
                ? raw.substring(0, Math.min(12, raw.length())) : "";
    }

    private static byte[] readBounded(InputStream input, long max) throws IOException {
        if (max > Integer.MAX_VALUE - 1) max = Integer.MAX_VALUE - 1L;
        byte[] value = input.readNBytes((int) max + 1);
        if (value.length > max) throw new IllegalArgumentException("request body is too large");
        return value;
    }

    private void error(HttpExchange exchange, int status, String code) throws IOException {
        byte[] body = json.writeValueAsBytes(Map.of("error", Map.of(
                "message", code, "type", "service_plugin_error", "code", code)));
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(body); }
    }

    private interface Listener extends AutoCloseable {
        InetSocketAddress address();
        int connections();
        @Override void close();
    }

    private record HttpListener(HttpServer server, EndpointGate gate) implements Listener {
        @Override public InetSocketAddress address() { return server.getAddress(); }
        @Override public int connections() { return gate.active(); }
        @Override public void close() { server.stop(0); }
    }

    private final class TcpListener implements Listener {
        private final ServerSocket server;
        private final Semaphore permits;
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean closed = new AtomicBoolean();

        private TcpListener(ServerSocket server, int maxConnections) {
            this.server = server;
            permits = new Semaphore(maxConnections, true);
        }

        @Override public InetSocketAddress address() {
            return (InetSocketAddress) server.getLocalSocketAddress();
        }
        @Override public int connections() { return sockets.size(); }
        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            try { server.close(); }
            catch (IOException failure) { log.debug("TCP listener close failed: " + failure.getMessage()); }
            sockets.forEach(socket -> {
                try { socket.close(); }
                catch (IOException failure) { log.debug("TCP connection close failed: " + failure.getMessage()); }
            });
            sockets.clear();
        }
    }

    private static final class EndpointGate {
        private final Semaphore concurrent;
        private final int requestsPerMinute;
        private final AtomicLong minute = new AtomicLong(-1);
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicInteger active = new AtomicInteger();

        private EndpointGate(ServicePluginWire.Endpoint config) {
            concurrent = new Semaphore(Math.max(1,
                    Math.min(config.maxConcurrent(), config.maxConnections())), true);
            requestsPerMinute = Math.max(1, config.requestsPerMinute());
        }

        boolean tryAcquire() {
            long current = Instant.now().getEpochSecond() / 60;
            long previous = minute.get();
            if (previous != current && minute.compareAndSet(previous, current)) requests.set(0);
            if (requests.incrementAndGet() > requestsPerMinute) return false;
            if (!concurrent.tryAcquire()) return false;
            active.incrementAndGet();
            return true;
        }

        void release() {
            active.decrementAndGet();
            concurrent.release();
        }

        int active() { return Math.max(0, active.get()); }
    }

    private final class ExchangeResponse implements ExternalResponse {
        private final HttpExchange exchange;
        private final AtomicBoolean cancelled;
        private final AtomicBoolean committed = new AtomicBoolean();
        private OutputStream stream;

        private ExchangeResponse(HttpExchange exchange, AtomicBoolean cancelled) {
            this.exchange = exchange;
            this.cancelled = cancelled;
        }
        @Override public boolean committed() { return committed.get(); }

        @Override
        public synchronized void send(int status, String contentType,
                                      Map<String, String> headers, byte[] body) {
            if (!committed.compareAndSet(false, true)) throw new IllegalStateException("response committed");
            byte[] value = body == null ? new byte[0] : body;
            try {
                apply(contentType, headers);
                exchange.sendResponseHeaders(status, value.length);
                try (OutputStream output = exchange.getResponseBody()) { output.write(value); }
            } catch (IOException failure) {
                cancelled.set(true);
                throw new IllegalStateException(failure);
            }
        }

        @Override
        public synchronized void startStream(int status, String contentType, Map<String, String> headers) {
            if (!committed.compareAndSet(false, true)) throw new IllegalStateException("response committed");
            try {
                apply(contentType, headers);
                exchange.sendResponseHeaders(status, 0);
                stream = exchange.getResponseBody();
            } catch (IOException failure) {
                cancelled.set(true);
                throw new IllegalStateException(failure);
            }
        }

        @Override
        public synchronized void stream(byte[] chunk) {
            if (stream == null) throw new IllegalStateException("stream was not started");
            try { stream.write(chunk); stream.flush(); }
            catch (IOException failure) {
                cancelled.set(true);
                throw new IllegalStateException(failure);
            }
        }

        @Override public synchronized void closeStream() { closeQuietly(); }

        private void apply(String contentType, Map<String, String> headers) {
            exchange.getResponseHeaders().set("Content-Type",
                    contentType == null || contentType.isBlank()
                            ? "application/octet-stream" : contentType);
            if (headers != null) headers.forEach((name, value) -> {
                String lower = name.toLowerCase(Locale.ROOT);
                if (!lower.equals("content-length") && !lower.equals("connection")
                        && !lower.equals("transfer-encoding")) {
                    exchange.getResponseHeaders().set(name, value);
                }
            });
        }

        synchronized void closeQuietly() {
            if (stream != null) {
                try { stream.close(); }
                catch (IOException failure) { log.debug("HTTP response close failed: " + failure.getMessage()); }
                stream = null;
            }
            exchange.close();
        }
    }

    private final class TcpResponse implements ExternalResponse {
        private final DataOutputStream output;
        private final AtomicBoolean cancelled;
        private final AtomicBoolean committed = new AtomicBoolean();
        private boolean streaming;

        private TcpResponse(DataOutputStream output) {
            this(output, new AtomicBoolean());
        }
        private TcpResponse(DataOutputStream output, AtomicBoolean cancelled) {
            this.output = output;
            this.cancelled = cancelled;
        }
        @Override public boolean committed() { return committed.get(); }

        @Override public synchronized void send(int status, String contentType,
                                                Map<String, String> headers, byte[] body) {
            if (!committed.compareAndSet(false, true)) throw new IllegalStateException("response committed");
            byte[] value = body == null ? new byte[0] : body;
            try {
                header(status, contentType, value.length);
                output.write(value);
                output.flush();
            } catch (IOException failure) {
                cancelled.set(true);
                throw new IllegalStateException(failure);
            }
        }

        @Override public synchronized void startStream(int status, String contentType,
                                                       Map<String, String> headers) {
            if (!committed.compareAndSet(false, true)) throw new IllegalStateException("response committed");
            try {
                header(status, contentType, -1);
                output.flush();
                streaming = true;
            } catch (IOException failure) {
                cancelled.set(true);
                throw new IllegalStateException(failure);
            }
        }

        @Override public synchronized void stream(byte[] chunk) {
            if (!streaming) throw new IllegalStateException("stream was not started");
            byte[] value = chunk == null ? new byte[0] : chunk;
            if (value.length > ServicePluginWire.MAX_REQUEST_BYTES) {
                throw new IllegalArgumentException("TCP stream chunk is too large");
            }
            try { output.writeInt(value.length); output.write(value); output.flush(); }
            catch (IOException failure) {
                cancelled.set(true);
                throw new IllegalStateException(failure);
            }
        }

        @Override public synchronized void closeStream() {
            if (!streaming) return;
            try { output.writeInt(-1); output.flush(); }
            catch (IOException failure) { log.debug("TCP stream close failed: " + failure.getMessage()); }
            streaming = false;
        }

        private void header(int status, String contentType, int bodyLength) throws IOException {
            byte[] type = (contentType == null ? "application/octet-stream" : contentType)
                    .getBytes(StandardCharsets.UTF_8);
            if (type.length > 1_024) throw new IOException("content type is too long");
            output.writeInt(status);
            output.writeInt(type.length);
            output.write(type);
            output.writeInt(bodyLength);
        }
    }
}
