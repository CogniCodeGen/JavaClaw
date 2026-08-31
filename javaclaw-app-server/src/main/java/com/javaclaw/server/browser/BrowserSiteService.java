package com.javaclaw.server.browser;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.agent.prompt.PromptHashes;
import com.javaclaw.agent.runtime.ToolExecutionContext;
import com.javaclaw.agent.runtime.persistence.AttachmentRepository;
import com.javaclaw.agent.runtime.persistence.ThreadJournal;
import com.javaclaw.agent.runtime.persistence.WorkspaceRepository;
import com.javaclaw.agent.tool.BrowserSessionGateway;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.core.api.WorkspaceId;
import com.javaclaw.sandbox.api.BrokerRequest;
import com.javaclaw.sandbox.api.NetworkPolicy;
import com.javaclaw.server.network.NetworkGrantService;
import com.javaclaw.server.security.SecretStore;

/** 站点管理与受监督浏览器会话；秘密仅经内部管道交付，所有网络重新绑定当前工作区授权。 */
public final class BrowserSiteService implements BrowserSiteUseCases, BrowserSessionGateway, AutoCloseable {
    private static final int BINARY_LIMIT = 4 * 1024 * 1024;
    private final BrowserSiteRepository repository;
    private final WorkspaceRepository workspaces;
    private final AttachmentRepository attachments;
    private final ThreadJournal journal;
    private final SecretStore secrets;
    private final BrowserServiceRuntime runtime;
    private final NetworkGrantService network;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Login> loginRequests = new LinkedHashMap<>();
    private final java.util.concurrent.ScheduledExecutorService expiry;

    /** 装配配置库、秘密库与独立 Browser Service；runtime 为空时保留管理能力但不宣称浏览器可执行。 */
    public BrowserSiteService(
            BrowserSiteRepository repository,
            WorkspaceRepository workspaces,
            AttachmentRepository attachments,
            ThreadJournal journal,
            SecretStore secrets,
            BrowserServiceRuntime runtime,
            NetworkGrantService network) {
        this.repository = java.util.Objects.requireNonNull(repository);
        this.workspaces = java.util.Objects.requireNonNull(workspaces);
        this.attachments = java.util.Objects.requireNonNull(attachments);
        this.journal = java.util.Objects.requireNonNull(journal);
        this.secrets = java.util.Objects.requireNonNull(secrets);
        this.runtime = runtime;
        this.network = java.util.Objects.requireNonNull(network);
        expiry = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("javaclaw-browser-expiry").factory());
        expiry.scheduleWithFixedDelay(this::reap, 1, 1, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Override
    public List<BrowserSite> list(String workspaceId) {
        workspace(workspaceId);
        return repository.list(workspaceId);
    }

    @Override
    public BrowserSite put(BrowserSite site, long revision, boolean confirmed, String key) {
        workspace(site.workspaceId());
        if (!confirmed
                || site.name() == null
                || site.name().isBlank()
                || site.name().length() > 500
                || site.allowedOrigins().size() > 32) {
            throw new IllegalArgumentException("confirm a named site with at most 32 origins");
        }
        URI origin = exactOrigin(site.origin());
        var allowed = new LinkedHashSet<URI>();
        allowed.add(origin);
        site.allowedOrigins().forEach(value -> allowed.add(exactOrigin(value)));
        BrowserSite result = repository.put(
                new BrowserSite(
                        site.id(),
                        site.workspaceId(),
                        site.name().strip(),
                        origin,
                        allowed,
                        site.enabled(),
                        0,
                        Instant.now()),
                revision,
                key);
        sessions.values().stream()
                .filter(session -> session.site != null
                        && session.site.id().equals(result.id())
                        && session.site.revision() != result.revision())
                .forEach(this::stop);
        return result;
    }

    @Override
    public boolean disable(String id, long revision, String key) {
        boolean result = repository.disable(id, revision, key);
        sessions.values().stream()
                .filter(session -> session.site != null && session.site.id().equals(id))
                .forEach(this::stop);
        return result;
    }

    @Override
    public SecretStore.SecretMetadata putSecret(String id, String name, char[] value, String key) {
        BrowserSite site = requireSite(id);
        if (value == null || value.length < 1 || value.length > 16_384) {
            throw new IllegalArgumentException("invalid secret size");
        }
        return secrets.put(namespace(site), slot(name), value, key);
    }

    @Override
    public Optional<SecretStore.SecretMetadata> secret(String id, String name) {
        return secrets.metadata(namespace(requireSite(id)), slot(name));
    }

    @Override
    public boolean clearSecret(String id, String name, long revision, String key) {
        return secrets.remove(namespace(requireSite(id)), slot(name), revision, key);
    }

    @Override
    public Optional<SecretStore.SecretMetadata> savedSession(String id) {
        return secrets.metadata(namespace(requireSite(id)), "state");
    }

    @Override
    public boolean clearSession(String id, long revision, String key) {
        BrowserSite site = requireSite(id);
        boolean removed = secrets.remove(namespace(site), "state", revision, key);
        sessions.values().stream()
                .filter(session -> session.site != null && session.site.id().equals(id))
                .forEach(this::stop);
        return removed;
    }

    @Override
    public synchronized Login startLogin(String id, long revision, boolean confirmed, String key) throws Exception {
        if (!confirmed || key == null || key.isBlank()) {
            throw new IllegalArgumentException("login requires explicit confirmation and an idempotency key");
        }
        String request = PromptHashes.sha256(id + "\n" + revision + "\n" + key);
        Login previous = loginRequests.get(request);
        if (previous != null) {
            return previous;
        }
        BrowserSite site = requireSite(id);
        if (site.revision() != revision) {
            throw new IllegalStateException("site revision conflict");
        }
        if (loginRequests.size() >= 128) {
            throw new IllegalStateException("login request quota exceeded; restart before a new request");
        }
        // 先持久化单次启动意图；进程异常后同一请求不自动重新打开登录页面或重放用户动作。
        if (secrets.metadata("browser-login-requests", request).isPresent()) {
            throw new IllegalStateException(
                    "previous login request ended or was interrupted; start a new explicit request");
        }
        char[] marker = "STARTED".toCharArray();
        try {
            secrets.put("browser-login-requests", request, marker, request);
        } finally {
            Arrays.fill(marker, '\0');
        }
        Session session = create(site.workspaceId(), null, site, site.origin(), true, null);
        Login result = new Login(session.id, id, session.expiresAt);
        loginRequests.put(request, result);
        return result;
    }

    @Override
    public boolean finishLogin(String id, boolean save, String key) throws Exception {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("login completion requires an idempotency key");
        }
        String request = PromptHashes.sha256(key);
        String expected = id + "\n" + save;
        var replay = secrets.resolve("browser-login-results", request);
        if (replay.isPresent()) {
            char[] value = replay.get();
            try {
                if (!expected.equals(new String(value))) {
                    throw new IllegalStateException("login idempotency key conflict");
                }
                return true;
            } finally {
                Arrays.fill(value, '\0');
            }
        }
        Session session = sessions.get(id);
        if (session == null || !session.manual) {
            throw new IllegalArgumentException("manual login session does not exist or expired");
        }
        synchronized (session) {
            verify(session);
            try {
                if (save) {
                    String state = session.channel
                            .call("browser/state", json.createObjectNode(), Duration.ofSeconds(10))
                            .path("state")
                            .asText();
                    validateState(session, state);
                    char[] value = state.toCharArray();
                    try {
                        secrets.put(namespace(session.site), "state", value, "browser-session:" + id);
                    } finally {
                        Arrays.fill(value, '\0');
                    }
                }
                char[] value = expected.toCharArray();
                try {
                    secrets.put("browser-login-results", request, value, request);
                } finally {
                    Arrays.fill(value, '\0');
                }
            } finally {
                stop(session);
            }
        }
        return true;
    }

    @Override
    public List<SiteOption> sites(ToolExecutionContext call) {
        return list(call.thread().workspaceId()).stream()
                .filter(BrowserSite::enabled)
                .map(site -> new SiteOption(site.id(), site.name(), site.origin(), site.revision()))
                .toList();
    }

    @Override
    public Result open(ToolExecutionContext call, String siteId, URI url) throws Exception {
        call.scope().check();
        BrowserSite site = siteId == null || siteId.isBlank() ? null : requireSite(siteId);
        if (site != null && !site.workspaceId().equals(call.thread().workspaceId())) {
            throw new IllegalArgumentException("site belongs to another workspace");
        }
        URI target = url == null && site != null ? site.origin() : BrowserServiceRuntime.validateUri(url);
        Session session =
                create(call.thread().workspaceId(), call.turn().id().value(), site, target, false, call.scope());
        return session.opened;
    }

    @Override
    public Result act(ToolExecutionContext call, String id, Action action) throws Exception {
        Session session = sessions.get(id);
        if (session == null
                || session.manual
                || !call.turn().id().value().equals(session.turnId)
                || !call.thread().workspaceId().equals(session.workspaceId)) {
            throw new IllegalArgumentException("browser session is not owned by this Turn");
        }
        synchronized (session) {
            call.scope().check();
            verify(session);
            if ("close".equals(action.operation())) {
                stop(session);
                return new Result(id, "浏览器会话已关闭。", null, null);
            }
            if (!Set.of(
                            "snapshot",
                            "navigate",
                            "newTab",
                            "closeTab",
                            "click",
                            "fill",
                            "select",
                            "press",
                            "upload",
                            "wait",
                            "screenshot",
                            "pdf",
                            "downloadLink",
                            "download")
                    .contains(action.operation())) {
                throw new IllegalArgumentException("unsupported browser operation");
            }
            boolean mutating = Set.of("navigate", "newTab", "click", "fill", "select", "press", "upload")
                    .contains(action.operation());
            if (mutating && "PLAN".equals(call.config().attributes().get("profileKind"))) {
                throw new IllegalArgumentException("PLAN cannot perform page business actions");
            }
            var params = json.createObjectNode()
                    .put("operation", action.operation())
                    .put("value", action.value() == null ? "" : action.value());
            if (action.tabId() != null && !action.tabId().isBlank()) {
                params.put("tabId", action.tabId());
            }
            if (action.reference() != null) {
                params.put("reference", action.reference());
            }
            if (Set.of("navigate", "newTab").contains(action.operation())) {
                target(session, URI.create(action.value()));
            }
            if (action.secretName() != null && !action.secretName().isBlank()) {
                if (!"fill".equals(action.operation()) || session.site == null) {
                    throw new IllegalArgumentException("SecretRef requires a configured site fill");
                }
                char[] value = secrets.resolve(namespace(session.site), slot(action.secretName()))
                        .orElseThrow(() -> new IllegalStateException("SecretRef is not configured"));
                try {
                    String secret = new String(value);
                    if (session.redactions.size() >= 16 && !session.redactions.contains(secret)) {
                        throw new IllegalStateException("secret fill quota exceeded");
                    }
                    session.redactions.add(secret);
                    params.put("value", secret).put("secret", true);
                } finally {
                    Arrays.fill(value, '\0');
                }
            }
            if ("upload".equals(action.operation())) {
                attach(call, params, action.attachmentSha256());
            }
            session.mutationUntil = mutating ? Instant.now().plusSeconds(30) : Instant.MIN;
            try {
                JsonNode result = session.channel.call("browser/action", params, Duration.ofSeconds(30));
                call.scope().check();
                return result(session, result);
            } finally {
                session.mutationUntil = Instant.MIN;
                params.remove("value");
                params.remove("bodyBase64");
            }
        }
    }

    private synchronized Session create(
            String workspaceId,
            String turnId,
            BrowserSite site,
            URI url,
            boolean manual,
            com.javaclaw.agent.runtime.TurnScope scope)
            throws Exception {
        if (runtime == null) {
            throw new IllegalStateException("browser launcher is not configured");
        }
        workspace(workspaceId);
        reap();
        if (sessions.size() >= 8
                || sessions.values().stream()
                                .filter(value -> java.util.Objects.equals(value.turnId, turnId))
                                .count()
                        >= (manual ? 2 : 4)) {
            throw new IllegalStateException("browser session quota exceeded");
        }
        Set<URI> allowed = site == null ? Set.of(NetworkGrantService.origin(url)) : site.allowedOrigins();
        Session session =
                new Session("browser_" + UUID.randomUUID(), workspaceId, turnId, site, allowed, manual, scope);
        target(session, url);
        session.channel = runtime.openChannel(params -> request(session, params));
        sessions.put(session.id, session);
        try {
            var params = json.createObjectNode().put("url", url.toString()).put("headed", manual);
            params.set(
                    "allowedOrigins",
                    json.valueToTree(
                            allowed.stream().map(URI::toString).sorted().toList()));
            if (site != null) {
                var state = secrets.resolve(namespace(site), "state");
                if (state.isPresent()) {
                    char[] value = state.get();
                    try {
                        String text = new String(value);
                        validateState(session, text);
                        params.put("state", text);
                    } finally {
                        Arrays.fill(value, '\0');
                    }
                }
            }
            session.opened = result(session, session.channel.call("browser/open", params, Duration.ofSeconds(45)));
            return session;
        } catch (Exception failure) {
            stop(session);
            throw failure;
        }
    }

    private JsonNode request(Session session, JsonNode params) throws Exception {
        verify(session);
        if (session.scope != null) {
            session.scope.check();
        }
        URI uri = target(session, URI.create(params.path("url").asText()));
        String method = params.path("method").asText().toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("GET", "HEAD", "OPTIONS").contains(method)
                && !session.manual
                && !session.mutationUntil.isAfter(Instant.now())) {
            throw new IllegalArgumentException("page write request requires an approved interactive action");
        }
        if (session.requests.incrementAndGet() > 1000) {
            throw new IllegalStateException("browser request budget exceeded");
        }
        var headers = new LinkedHashMap<String, String>();
        Set<String> denied = Set.of(
                "host",
                "content-length",
                "connection",
                "transfer-encoding",
                "proxy-authorization",
                "proxy-connection",
                "keep-alive",
                "trailer",
                "upgrade",
                "te",
                "accept-encoding");
        params.path("headers").properties().forEach(entry -> {
            String name = entry.getKey().toLowerCase(java.util.Locale.ROOT);
            if (!denied.contains(name) && !name.startsWith("mcp-")) {
                headers.put(name, entry.getValue().asText());
            }
        });
        byte[] body = decode(params.path("bodyBase64").asText());
        var response = network.broker(session.workspaceId, "BROWSER")
                .execute(
                        new BrokerRequest(
                                method,
                                uri,
                                headers,
                                body,
                                Duration.ofSeconds(15),
                                BINARY_LIMIT,
                                0,
                                "https".equals(uri.getScheme()),
                                false),
                        new NetworkPolicy(
                                NetworkPolicy.Mode.ALLOWLIST, Set.of(BrowserServiceRuntime.allowlistEntry(uri))));
        verify(session);
        if (session.bytes.addAndGet(response.body().length) > 64L * 1024 * 1024) {
            throw new IllegalStateException("browser response budget exceeded");
        }
        var result = json.createObjectNode()
                .put("status", response.statusCode())
                .put("bodyBase64", Base64.getEncoder().encodeToString(response.body()));
        var responseHeaders = result.putObject("headers");
        response.headers().forEach((name, values) -> {
            if (!Set.of("connection", "transfer-encoding", "content-length", "keep-alive", "trailer", "upgrade")
                    .contains(name.toLowerCase(java.util.Locale.ROOT))) {
                var array = responseHeaders.putArray(name);
                values.forEach(array::add);
            }
        });
        return result;
    }

    private Result result(Session session, JsonNode value) throws Exception {
        if (value.has("bodyBase64")) {
            byte[] body = decode(value.path("bodyBase64").asText());
            var artifact = attachments.put(
                    new ByteArrayInputStream(body), value.path("mediaType").asText("application/octet-stream"));
            return new Result(
                    session.id,
                    "已生成附件 " + artifact.sha256(),
                    artifact,
                    safe(session, value.path("name").asText("browser-artifact")));
        }
        String text = json.writeValueAsString(value);
        if (text.length() > 96_000) {
            throw new IllegalArgumentException("browser snapshot exceeds limit");
        }
        return new Result(session.id, safe(session, text), null, null);
    }

    private void attach(ToolExecutionContext call, com.fasterxml.jackson.databind.node.ObjectNode params, String sha)
            throws Exception {
        if (sha == null || !sha.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("attachment SHA-256 required");
        }
        boolean owned = journal.items(call.thread().id()).stream()
                .map(com.javaclaw.core.api.StoredItem::item)
                .anyMatch(item -> item instanceof ThreadItem.UserMessage message
                                && message.attachments().stream()
                                        .anyMatch(ref -> ref.sha256().equals(sha))
                        || item instanceof ThreadItem.ImageView image
                                && image.uri().equals("attachment:sha256:" + sha)
                        || item instanceof ThreadItem.Artifact artifact
                                && artifact.category().equals("browserAttachment")
                                && artifact.content().equals("attachment:sha256:" + sha));
        if (!owned) {
            throw new IllegalArgumentException("attachment is not attached to this Thread");
        }
        var metadata = attachments.findAttachment(sha).orElseThrow();
        if (metadata.sizeBytes() > BINARY_LIMIT) {
            throw new IllegalArgumentException("browser upload exceeds 4 MiB");
        }
        try (var source = attachments.openAttachment(sha)) {
            byte[] bytes = source.readNBytes(BINARY_LIMIT + 1);
            if (bytes.length > BINARY_LIMIT) {
                throw new IllegalArgumentException("browser upload exceeds limit");
            }
            params.put("bodyBase64", Base64.getEncoder().encodeToString(bytes))
                    .put("name", "attachment-" + sha.substring(0, 12))
                    .put("mediaType", metadata.mediaType());
        }
    }

    private void validateState(Session session, String state) throws Exception {
        if (state.length() > 512_000) {
            throw new IllegalArgumentException("browser state exceeds limit");
        }
        JsonNode value = json.readTree(state);
        if (!value.isObject()
                || !value.path("cookies").isArray()
                || !value.path("origins").isArray()) {
            throw new IllegalArgumentException("invalid browser state");
        }
        Set<String> hosts = session.origins.stream().map(URI::getHost).collect(java.util.stream.Collectors.toSet());
        for (var cookie : value.path("cookies")) {
            String domain = cookie.path("domain").asText();
            if (domain.startsWith(".")) {
                domain = domain.substring(1);
            }
            if (!hosts.contains(domain)) {
                throw new IllegalArgumentException("saved cookie is outside exact approved hosts");
            }
        }
        for (var origin : value.path("origins")) {
            target(session, URI.create(origin.path("origin").asText()));
        }
    }

    private void verify(Session session) {
        if (!session.expiresAt.isAfter(Instant.now())
                || session.closed
                || (session.channel != null && !session.channel.alive())) {
            throw new IllegalStateException("browser session expired or closed");
        }
        workspace(session.workspaceId);
        if (session.site != null) {
            BrowserSite site = requireSite(session.site.id());
            if (site.revision() != session.site.revision()) {
                throw new IllegalStateException("site permissions changed; reopen the browser");
            }
        }
    }

    private URI target(Session session, URI uri) {
        URI value = BrowserServiceRuntime.validateUri(uri);
        if (!session.origins.contains(NetworkGrantService.origin(value))) {
            throw new IllegalArgumentException("URL is outside the site's approved exact origins");
        }
        return value;
    }

    private BrowserSite requireSite(String id) {
        var value = repository.find(id).orElseThrow(() -> new IllegalArgumentException("site does not exist"));
        if (!value.enabled()) {
            throw new IllegalStateException("site is disabled");
        }
        workspace(value.workspaceId());
        return value;
    }

    private void workspace(String id) {
        var value = workspaces
                .find(new WorkspaceId(id))
                .orElseThrow(() -> new IllegalArgumentException("workspace does not exist"));
        if (value.locked()) {
            throw new IllegalStateException("workspace is locked");
        }
    }

    private static String namespace(BrowserSite site) {
        return "browser-site:" + site.id() + ":" + site.revision();
    }

    private static String slot(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,80}")) {
            throw new IllegalArgumentException("invalid SecretRef name");
        }
        return "credential:" + name;
    }

    private static URI exactOrigin(URI uri) {
        if (uri == null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || (uri.getPath() != null && !Set.of("", "/").contains(uri.getPath()))) {
            throw new IllegalArgumentException("an exact origin without path is required");
        }
        return NetworkGrantService.origin(uri);
    }

    private static byte[] decode(String value) {
        if (value.length() > (BINARY_LIMIT + 2L) / 3 * 4) {
            throw new IllegalArgumentException("browser body exceeds limit");
        }
        byte[] bytes = Base64.getDecoder().decode(value);
        if (bytes.length > BINARY_LIMIT) {
            throw new IllegalArgumentException("browser body exceeds limit");
        }
        return bytes;
    }

    private static String safe(Session session, String text) {
        String result = text;
        for (String secret : session.redactions) {
            result = result.replace(secret, "[REDACTED]");
        }
        return result.replaceAll(
                "(?i)(password|token|secret|api[_-]?key|authorization)([=:]\\s*)[^\\s&\\\"<>]+", "$1$2[REDACTED]");
    }

    private void stop(Session session) {
        session.closed = true;
        sessions.remove(session.id);
        if (session.channel != null) {
            session.channel.close();
        }
        session.redactions.clear();
    }

    private void reap() {
        for (Session session : sessions.values()) {
            try {
                verify(session);
                if (session.scope != null) {
                    session.scope.check();
                }
            } catch (Exception expired) {
                stop(session);
            }
        }
    }

    @Override
    public void closeTurn(String turnId) {
        sessions.values().stream().filter(value -> turnId.equals(value.turnId)).forEach(this::stop);
    }

    @Override
    public void close() {
        expiry.shutdownNow();
        List.copyOf(sessions.values()).forEach(this::stop);
    }

    private static final class Session {
        private final String id;
        private final String workspaceId;
        private final String turnId;
        private final BrowserSite site;
        private final Set<URI> origins;
        private final boolean manual;
        private final com.javaclaw.agent.runtime.TurnScope scope;
        private final Instant expiresAt;
        private final Set<String> redactions = ConcurrentHashMap.newKeySet();
        private final java.util.concurrent.atomic.AtomicInteger requests =
                new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicLong bytes = new java.util.concurrent.atomic.AtomicLong();
        private volatile Instant mutationUntil = Instant.MIN;
        private volatile BrowserWorkerChannel channel;
        private volatile boolean closed;
        private Result opened;

        private Session(
                String id,
                String workspaceId,
                String turnId,
                BrowserSite site,
                Set<URI> origins,
                boolean manual,
                com.javaclaw.agent.runtime.TurnScope scope) {
            this.id = id;
            this.workspaceId = workspaceId;
            this.turnId = turnId;
            this.site = site;
            this.origins = Set.copyOf(origins);
            this.manual = manual;
            this.scope = scope;
            expiresAt = Instant.now().plus(manual ? Duration.ofMinutes(10) : Duration.ofMinutes(30));
        }
    }
}
