package com.javaclaw.plugins.deliverance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.plugin.sdk.PluginContext;
import com.javaclaw.plugins.deliverance.internal.*;
import com.sun.net.httpserver.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.net.ssl.*;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** 独立进程中的受鉴权 HTTP 网关，连接和配额都由插件自身管理。 */
final class DeliveranceHttpGateway implements ExternalEndpointRegistry, AutoCloseable {
    private final PluginContext sdk;
    private final ObjectMapper json;
    private final Supplier<JsonNode> configuration;
    private final Supplier<String> policies;
    private final Semaphore requests = new Semaphore(32);
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("deliverance-http-deadlines").daemon(true).factory());
    private HttpServer server;
    private HttpServer pendingStop;
    private boolean accepting, draining;

    DeliveranceHttpGateway(PluginContext sdk, ObjectMapper json,
                           Supplier<JsonNode> configuration, Supplier<String> policies) {
        this.sdk = sdk;
        this.json = json;
        this.configuration = configuration;
        this.policies = policies;
    }

    @Override public synchronized Registration register(ExternalEndpointDescriptor descriptor,
                                                        ExternalRequestHandler handler) {
        if (server != null) throw new IllegalStateException("HTTP 网关已启动");
        if (draining) throw new IllegalStateException("HTTP 网关正在排空");
        try {
            JsonNode settings = configuration.get().deepCopy();
            JsonNode keyPolicies = json.readTree(policies.get());
            if (!keyPolicies.isArray() || keyPolicies.isEmpty()) throw new SecurityException("HTTP 网关缺少 API Key");
            InetAddress address = InetAddress.getByName(settings.path("bindAddress").asText("127.0.0.1"));
            if (address.isAnyLocalAddress()) throw new SecurityException("禁止绑定通配地址");
            boolean tls = settings.path("tlsEnabled").asBoolean(false);
            if (!address.isLoopbackAddress() && !tls
                    && !settings.path("allowInsecureLanWithoutTls").asBoolean(false)) {
                throw new SecurityException("局域网明文监听需要显式授权");
            }
            int port = settings.path("port").asInt(18080);
            if (port < 1 || port > 65535) throw new IllegalArgumentException("HTTP 端口无效");
            long bytes = settings.path("maxRequestBytes").asLong(4 * 1024 * 1024);
            if (bytes < 1024 || bytes > 64 * 1024 * 1024) throw new IllegalArgumentException("HTTP 请求体预算无效");
            int seconds = settings.path("requestTimeoutSeconds").asInt(120);
            if (seconds < 1 || seconds > 3600) throw new IllegalArgumentException("HTTP 请求超时无效");
            InetSocketAddress bind = new InetSocketAddress(address, port);
            HttpServer created;
            if (tls) {
                HttpsServer secure = HttpsServer.create(bind, 32);
                try { secure.setHttpsConfigurator(new HttpsConfigurator(sslContext(settings))); }
                catch (Exception failure) { secure.stop(0); throw failure; }
                created = secure;
            } else created = HttpServer.create(bind, 32);
            created.setExecutor(sdk.executor());
            created.createContext("/", exchange -> handle(exchange, handler, keyPolicies, bytes, seconds));
            created.start();
            server = created;
            accepting = true;
            return () -> stop(created);
        } catch (Exception failure) { throw new IllegalStateException("HTTP 网关启动失败", failure); }
    }

    private void handle(HttpExchange exchange, ExternalRequestHandler handler,
                        JsonNode keys, long maxBytes, int timeoutSeconds) {
        if (!enter()) { reject(exchange, 503); return; }
        AtomicBoolean cancelled = new AtomicBoolean();
        Thread thread = Thread.currentThread();
        ScheduledFuture<?> timeout = deadlines.schedule(() -> {
            cancelled.set(true); exchange.close(); thread.interrupt();
        }, timeoutSeconds, TimeUnit.SECONDS);
        try {
            String credential = authenticate(exchange.getRequestHeaders().getFirst("Authorization"), keys);
            if (credential.isEmpty()) { reject(exchange, 401); return; }
            byte[] body = exchange.getRequestBody().readNBytes((int) maxBytes + 1);
            if (body.length > maxBytes) { reject(exchange, 413); return; }
            Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
            String requestId = UUID.randomUUID().toString();
            HttpResponse response = new HttpResponse(exchange);
            handler.handle(new ExternalInvocation() {
                public String requestId() { return requestId; }
                public String credentialId() { return credential; }
                public String method() { return exchange.getRequestMethod(); }
                public String path() { return exchange.getRequestURI().getPath(); }
                public Map<String, List<String>> headers() { return Map.copyOf(exchange.getRequestHeaders()); }
                public byte[] body() { return body.clone(); }
                public InetSocketAddress remoteAddress() { return exchange.getRemoteAddress(); }
                public Instant deadline() { return deadline; }
                public CancellationToken cancellation() { return () -> cancelled.get() || sdk.cancellation().isCancelled()
                        || Thread.currentThread().isInterrupted() || Instant.now().isAfter(deadline); }
                public ExternalResponse response() { return response; }
            });
            if (!response.committed()) reject(exchange, 500);
        } catch (Exception failure) { reject(exchange, 500); }
        finally {
            timeout.cancel(false); exchange.close(); requests.release(); Thread.interrupted();
            finishPendingStop();
        }
    }

    private synchronized boolean enter() { return accepting && requests.tryAcquire(); }
    synchronized void beginDrain() { draining = true; accepting = false; }

    static String authenticate(String authorization, JsonNode policies) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) return "";
        String raw = authorization.substring(7).strip();
        if (raw.isEmpty() || raw.length() > 4096) return "";
        for (JsonNode policy : policies) {
            String prefix = policy.path("prefix").asText();
            if (prefix.isBlank() || !raw.startsWith(prefix) || policy.path("revoked").asBoolean(false)) continue;
            try {
                byte[] salt = Base64.getUrlDecoder().decode(policy.path("salt").asText());
                byte[] expected = Base64.getUrlDecoder().decode(policy.path("digest").asText());
                if (salt.length < 16 || expected.length != 32) continue;
                PBEKeySpec spec = new PBEKeySpec(raw.toCharArray(), salt, 120_000, 256);
                byte[] actual;
                try { actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
                finally { spec.clearPassword(); }
                if (MessageDigest.isEqual(expected, actual)) return prefix;
            } catch (Exception invalid) { /* 单条损坏策略不应绕过其他密钥的鉴权。 */ }
        }
        return "";
    }

    private static SSLContext sslContext(JsonNode settings) throws Exception {
        if (!settings.path("encryptedKeyStorePassword").asText().isBlank()
                && !settings.has("keyStorePassword")) throw new SecurityException("TLS 密码必须经受限宿主调用解密");
        Path path = Path.of(settings.path("keyStorePath").asText()).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new SecurityException("TLS 密钥库无效");
        }
        char[] password = settings.path("keyStorePassword").asText("").toCharArray();
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (InputStream input = Files.newInputStream(path)) { store.load(input, password); }
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(store, password);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), null, null);
            return context;
        } finally { Arrays.fill(password, '\0'); }
    }
    private synchronized void stop(HttpServer expected) {
        if (server != expected) return;
        accepting = false;
        pendingStop = expected;
        finishPendingStop();
    }
    private synchronized void finishPendingStop() {
        if (pendingStop != null && requests.availablePermits() == 32) {
            pendingStop.stop(0); server = null; pendingStop = null;
        }
    }
    @Override public synchronized void close() {
        accepting = false;
        if (server != null) { server.stop(0); server = null; }
        pendingStop = null;
        deadlines.shutdownNow();
    }
    private static void reject(HttpExchange exchange, int status) {
        try { exchange.sendResponseHeaders(status, -1); } catch (Exception ignored) { }
        exchange.close();
    }
    private static final class HttpResponse implements ExternalResponse {
        private final HttpExchange exchange;
        private boolean committed;
        private HttpResponse(HttpExchange exchange) { this.exchange = exchange; }
        public boolean committed() { return committed; }
        public void send(int status, String type, Map<String, String> headers, byte[] body) {
            begin(status, type, headers, body.length);
            write(body); closeStream();
        }
        public void startStream(int status, String type, Map<String, String> headers) { begin(status, type, headers, 0); }
        private void begin(int status, String type, Map<String, String> headers, long length) {
            if (committed) throw new IllegalStateException("HTTP 响应已提交");
            try {
                exchange.getResponseHeaders().set("Content-Type", type);
                headers.forEach((key, value) -> exchange.getResponseHeaders().set(key, value));
                exchange.sendResponseHeaders(status, length); committed = true;
            } catch (Exception failure) { throw new CancellationException("HTTP 连接已关闭"); }
        }
        public void stream(byte[] chunk) { write(chunk); }
        private void write(byte[] value) {
            try { exchange.getResponseBody().write(value); exchange.getResponseBody().flush(); }
            catch (Exception disconnected) { throw new CancellationException("HTTP 连接已关闭"); }
        }
        public void closeStream() { exchange.close(); }
    }
}
