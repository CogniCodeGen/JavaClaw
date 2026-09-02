package com.javaclaw.browser.protocol;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.CanonicalPayload;

/**
 * Browser Worker 私有双向 framing 契约。
 *
 * <p>结构化元数据使用规范 JSON 帧；HTTP body 与 Browser storage state 紧随元数据使用独立原始字节帧，禁止 Base64 扩张和 Secret 混入可记录
 * JSON。每次命令只允许一个串行反向网络请求，宿主必须在回复前完成权限、DNS 与实时撤权复查。
 */
public final class BrowserWorkerProtocol {
    /** 当前私有协议版本；不协商、不降级。 */
    public static final int VERSION = 2;

    /** 快照操作名。 */
    public static final String SNAPSHOT = "snapshot";

    /** 人工登录操作名。 */
    public static final String LOGIN = "login";

    /** MCP OAuth 隔离授权操作名。 */
    public static final String OAUTH = "oauth";

    /** 单个敏感状态帧上限。 */
    public static final int MAXIMUM_STATE_BYTES = 2 * 1024 * 1024;

    /** 单个网络 body 帧上限。 */
    public static final int MAXIMUM_NETWORK_BYTES = 8 * 1024 * 1024;

    private static final Set<String> METHODS = Set.of("DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT");

    private BrowserWorkerProtocol() {}

    /** Worker 发往宿主的消息类型。 */
    public enum WorkerMessageKind {
        /** 请求宿主执行一次受治理单跳 HTTPS 交换。 */
        NETWORK_REQUEST,
        /** 隔离 Chromium 窗口已完成初始导航。 */
        SESSION_READY,
        /** 命令最终成功或失败。 */
        RESULT
    }

    /**
     * 宿主发起的 Browser 命令。
     *
     * @param version 固定为 2
     * @param id 进程内正请求序号
     * @param operation 操作名
     * @param payload 非敏感任务元数据
     * @param sensitiveStateBytes 紧随其后的原始 storage state 字节数；0 表示无状态
     */
    public record Command(int version, long id, String operation, CanonicalPayload payload, int sensitiveStateBytes) {
        /** 校验命令和敏感帧上限。 */
        public Command {
            requireVersion(version);
            positive(id, "id");
            operation = text(operation, "operation");
            if (!Set.of(SNAPSHOT, LOGIN, OAUTH).contains(operation)) {
                throw new IllegalArgumentException("unsupported Browser Worker operation");
            }
            Objects.requireNonNull(payload, "payload");
            bytes(sensitiveStateBytes, MAXIMUM_STATE_BYTES, "sensitiveStateBytes");
            if (OAUTH.equals(operation) && sensitiveStateBytes != 0) {
                throw new IllegalArgumentException("OAuth Browser command cannot contain sensitive state bytes");
            }
        }
    }

    /**
     * Worker 发往宿主的反向请求或最终结果。
     *
     * @param version 固定为 2
     * @param commandId 所属命令
     * @param sequence 单调网络序号；RESULT 时为 0
     * @param kind 消息类型
     * @param payload 请求元数据或成功结果
     * @param error 脱敏稳定错误码
     * @param binaryBytes 紧随其后的原始 HTTP body 字节数
     */
    public record WorkerMessage(
            int version,
            long commandId,
            long sequence,
            WorkerMessageKind kind,
            Optional<CanonicalPayload> payload,
            Optional<String> error,
            int binaryBytes) {
        /** 校验方向、结果互斥和 body 上限。 */
        public WorkerMessage {
            requireVersion(version);
            positive(commandId, "commandId");
            Objects.requireNonNull(kind, "kind");
            payload = Objects.requireNonNull(payload, "payload");
            error = Objects.requireNonNull(error, "error").map(value -> errorCode(value, "error"));
            switch (kind) {
                case NETWORK_REQUEST -> validateNetwork(sequence, payload, error, binaryBytes);
                case SESSION_READY -> validateReady(sequence, payload, error, binaryBytes);
                case RESULT -> validateResult(sequence, payload, error, binaryBytes);
            }
        }

        /** 创建反向网络请求。 */
        public static WorkerMessage network(long commandId, long sequence, CanonicalPayload payload, int bodyBytes) {
            return new WorkerMessage(
                    VERSION,
                    commandId,
                    sequence,
                    WorkerMessageKind.NETWORK_REQUEST,
                    Optional.of(payload),
                    Optional.empty(),
                    bodyBytes);
        }

        /** 创建成功结果。 */
        public static WorkerMessage success(long commandId, CanonicalPayload result) {
            return new WorkerMessage(
                    VERSION, commandId, 0, WorkerMessageKind.RESULT, Optional.of(result), Optional.empty(), 0);
        }

        /** 创建带私有 storage state 帧的登录成功结果。 */
        public static WorkerMessage sensitiveSuccess(long commandId, CanonicalPayload result, int sensitiveBytes) {
            return new WorkerMessage(
                    VERSION,
                    commandId,
                    0,
                    WorkerMessageKind.RESULT,
                    Optional.of(result),
                    Optional.empty(),
                    sensitiveBytes);
        }

        /** 创建登录窗口已经打开的状态事件。 */
        public static WorkerMessage ready(long commandId, CanonicalPayload result) {
            return new WorkerMessage(
                    VERSION, commandId, 0, WorkerMessageKind.SESSION_READY, Optional.of(result), Optional.empty(), 0);
        }

        /** 创建脱敏失败结果。 */
        public static WorkerMessage failure(long commandId, String error) {
            return new WorkerMessage(
                    VERSION, commandId, 0, WorkerMessageKind.RESULT, Optional.empty(), Optional.of(error), 0);
        }

        private static void validateNetwork(
                long sequence, Optional<CanonicalPayload> payload, Optional<String> error, int binaryBytes) {
            positive(sequence, "sequence");
            requireOutcome(payload, error, true);
            bytes(binaryBytes, MAXIMUM_NETWORK_BYTES, "binaryBytes");
        }

        private static void validateReady(
                long sequence, Optional<CanonicalPayload> payload, Optional<String> error, int binaryBytes) {
            if (sequence != 0 || binaryBytes != 0 || payload.isEmpty() || error.isPresent()) {
                throw new IllegalArgumentException("SESSION_READY must contain one metadata payload");
            }
        }

        private static void validateResult(
                long sequence, Optional<CanonicalPayload> payload, Optional<String> error, int binaryBytes) {
            if (sequence != 0) {
                throw new IllegalArgumentException("RESULT cannot contain a network sequence");
            }
            requireOutcome(payload, error, false);
            bytes(binaryBytes, MAXIMUM_STATE_BYTES, "binaryBytes");
            if (error.isPresent() && binaryBytes != 0) {
                throw new IllegalArgumentException("failed RESULT cannot contain sensitive data");
            }
        }
    }

    /**
     * 宿主对一个反向网络请求的答复。
     *
     * @param version 固定为 2
     * @param commandId 所属命令
     * @param sequence 精确匹配 Worker 请求
     * @param payload 成功响应元数据
     * @param error 脱敏错误码
     * @param binaryBytes 紧随其后的响应 body 字节数
     */
    public record HostMessage(
            int version,
            long commandId,
            long sequence,
            Optional<CanonicalPayload> payload,
            Optional<String> error,
            int binaryBytes) {
        /** 校验网络响应。 */
        public HostMessage {
            requireVersion(version);
            positive(commandId, "commandId");
            positive(sequence, "sequence");
            payload = Objects.requireNonNull(payload, "payload");
            error = Objects.requireNonNull(error, "error").map(value -> errorCode(value, "error"));
            requireOutcome(payload, error, false);
            bytes(binaryBytes, MAXIMUM_NETWORK_BYTES, "binaryBytes");
            if (error.isPresent() && binaryBytes != 0) {
                throw new IllegalArgumentException("failed network response cannot contain a body");
            }
        }

        /** 创建成功网络响应。 */
        public static HostMessage success(long commandId, long sequence, CanonicalPayload response, int bodyBytes) {
            return new HostMessage(VERSION, commandId, sequence, Optional.of(response), Optional.empty(), bodyBytes);
        }

        /** 创建失败网络响应。 */
        public static HostMessage failure(long commandId, long sequence, String error) {
            return new HostMessage(VERSION, commandId, sequence, Optional.empty(), Optional.of(error), 0);
        }
    }

    /**
     * 只含非敏感权限边界的 Worker 快照任务。
     *
     * @param uri 初始 URI
     * @param allowedOrigins 精确 HTTPS Origin 集合
     * @param maxCharacters 可见正文上限
     * @param timeout 导航时限
     */
    public record SnapshotTask(URI uri, Set<URI> allowedOrigins, int maxCharacters, Duration timeout) {
        /** 校验 Worker 不能扩大 Origin 与时限。 */
        public SnapshotTask {
            uri = https(uri, false);
            allowedOrigins = Objects.requireNonNull(allowedOrigins, "allowedOrigins").stream()
                    .map(value -> https(value, true))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!allowedOrigins.contains(origin(uri))) {
                throw new IllegalArgumentException("allowedOrigins must contain the requested URI origin");
            }
            if (maxCharacters < 1 || maxCharacters > 200_000) {
                throw new IllegalArgumentException("maxCharacters must be between 1 and 200000");
            }
            timeout = Objects.requireNonNull(timeout, "timeout");
            if (timeout.compareTo(Duration.ofSeconds(1)) < 0 || timeout.compareTo(Duration.ofSeconds(60)) > 0) {
                throw new IllegalArgumentException("timeout must be between 1 and 60 seconds");
            }
        }
    }

    /**
     * 只含非敏感权限边界的 Worker 登录任务。
     *
     * @param sessionId 会话 UUID，同时用于受限控制文件名
     * @param uri 初始登录 URI
     * @param allowedOrigins 精确 HTTPS Origin 集合
     * @param timeout 登录硬时限，最多十分钟
     */
    public record LoginTask(String sessionId, URI uri, Set<URI> allowedOrigins, Duration timeout) {
        /** 校验会话 ID、Origin 与时限。 */
        public LoginTask {
            sessionId = uuid(sessionId, "sessionId");
            uri = https(uri, false);
            allowedOrigins = Objects.requireNonNull(allowedOrigins, "allowedOrigins").stream()
                    .map(value -> https(value, true))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!allowedOrigins.contains(origin(uri))) {
                throw new IllegalArgumentException("allowedOrigins must contain the login URI origin");
            }
            timeout = Objects.requireNonNull(timeout, "timeout");
            if (timeout.compareTo(Duration.ofSeconds(1)) < 0 || timeout.compareTo(Duration.ofMinutes(10)) > 0) {
                throw new IllegalArgumentException("login timeout must be between 1 second and 10 minutes");
            }
        }
    }

    /**
     * Worker 登录状态事件；不包含页面 URL 或内容。
     *
     * @param sessionId 会话 UUID
     */
    public record LoginReady(String sessionId) {
        /** 校验会话标识。 */
        public LoginReady {
            sessionId = uuid(sessionId, "sessionId");
        }
    }

    /**
     * Worker 完成登录保存的非敏感元数据。
     *
     * @param sessionId 会话 UUID
     */
    public record LoginSaved(String sessionId) {
        /** 校验会话标识。 */
        public LoginSaved {
            sessionId = uuid(sessionId, "sessionId");
        }
    }

    /**
     * MCP OAuth 私有浏览器任务。
     *
     * @param sessionId Worker 控制 UUID
     * @param authorizationId 服务端流程标识
     * @param endpointId 冻结 Endpoint 标识
     * @param endpointRevision 冻结 revision
     * @param authorizationUri 含 state/challenge 的完整 HTTPS URI
     * @param allowedOrigins metadata 允许的精确 HTTPS Origin
     * @param redirectUri 只允许本地截获的精确 loopback HTTP URI
     * @param timeout 会话硬时限
     */
    public record OAuthTask(
            String sessionId,
            String authorizationId,
            String endpointId,
            long endpointRevision,
            URI authorizationUri,
            Set<URI> allowedOrigins,
            URI redirectUri,
            Duration timeout) {
        /** 校验 OAuth 浏览器边界。 */
        public OAuthTask {
            sessionId = uuid(sessionId, "sessionId");
            authorizationId = identifier(authorizationId, "authorizationId");
            endpointId = identifier(endpointId, "endpointId");
            positive(endpointRevision, "endpointRevision");
            authorizationUri = https(authorizationUri, false);
            allowedOrigins = Objects.requireNonNull(allowedOrigins, "allowedOrigins").stream()
                    .map(value -> https(value, true))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!allowedOrigins.contains(origin(authorizationUri))) {
                throw new IllegalArgumentException("allowedOrigins must contain the authorization URI origin");
            }
            redirectUri = loopback(redirectUri, false);
            timeout = Objects.requireNonNull(timeout, "timeout");
            if (timeout.compareTo(Duration.ofSeconds(1)) < 0 || timeout.compareTo(Duration.ofMinutes(10)) > 0) {
                throw new IllegalArgumentException("OAuth timeout must be between 1 second and 10 minutes");
            }
        }
    }

    /** @param sessionId Worker 控制 UUID */
    public record OAuthReady(String sessionId) {
        /** 校验会话标识。 */
        public OAuthReady {
            sessionId = uuid(sessionId, "sessionId");
        }
    }

    /**
     * Worker 私有结果；该 payload 含 code/state，不得进入日志或 JSON-RPC。
     *
     * @param sessionId Worker 控制 UUID
     * @param callbackUri 精确 loopback callback URI
     */
    public record OAuthCallback(String sessionId, URI callbackUri) {
        /** 校验 callback 只能是带 query 的 loopback HTTP URI。 */
        public OAuthCallback {
            sessionId = uuid(sessionId, "sessionId");
            callbackUri = loopback(callbackUri, true);
        }
    }

    /**
     * Worker 提交给宿主 Broker 的单跳 HTTPS 请求元数据。
     *
     * @param uri 精确 HTTPS URI
     * @param method HTTP 方法
     * @param headers Browser 生成的请求头；宿主仍会重新过滤
     */
    public record NetworkRequest(URI uri, String method, Map<String, List<String>> headers) {
        /** 校验 URI、方法和 header 集合。 */
        public NetworkRequest {
            uri = https(uri, false);
            method = text(method, "method").toUpperCase(Locale.ROOT);
            if (!METHODS.contains(method)) {
                throw new IllegalArgumentException("unsupported HTTP method");
            }
            headers = copyHeaders(headers);
        }
    }

    /**
     * 宿主 Broker 返回给 Worker 的单跳响应元数据。
     *
     * @param statusCode HTTP 状态码
     * @param headers 仅供隔离 Browser 使用的响应头；可能含 Cookie，禁止记录或返回 RPC
     * @param truncated 是否达到响应上限
     */
    public record NetworkResponse(int statusCode, Map<String, List<String>> headers, boolean truncated) {
        /** 校验状态码并复制 header。 */
        public NetworkResponse {
            if (statusCode < 100 || statusCode > 599) {
                throw new IllegalArgumentException("invalid HTTP status code");
            }
            headers = copyHeaders(headers);
        }
    }

    private static URI https(URI value, boolean originOnly) {
        URI uri = Objects.requireNonNull(value, "uri").normalize();
        requireHttpsAuthority(uri);
        if (originOnly) {
            requireOriginShape(uri);
            return origin(uri);
        }
        return uri;
    }

    private static URI loopback(URI value, boolean queryRequired) {
        URI uri = Objects.requireNonNull(value, "redirectUri").normalize();
        String host = uri.getHost();
        boolean valid = uri.isAbsolute()
                && "http".equalsIgnoreCase(uri.getScheme())
                && ("127.0.0.1".equals(host) || "::1".equals(host) || "[::1]".equals(host))
                && uri.getPort() > 0
                && uri.getUserInfo() == null
                && uri.getFragment() == null
                && (queryRequired ? uri.getQuery() != null && !uri.getQuery().isBlank() : uri.getQuery() == null);
        if (!valid) {
            throw new IllegalArgumentException("OAuth redirect must be an exact loopback HTTP URI");
        }
        return uri;
    }

    private static void requireHttpsAuthority(URI uri) {
        if (!uri.isAbsolute()
                || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || uri.getPort() == 0
                || uri.getPort() < -1
                || uri.getPort() > 65_535) {
            throw new IllegalArgumentException("Browser URI must be an exact HTTPS target");
        }
    }

    private static void requireOriginShape(URI uri) {
        String path = uri.getPath();
        if ((path != null && !path.isEmpty() && !"/".equals(path)) || uri.getQuery() != null) {
            throw new IllegalArgumentException("Browser Origin must not contain a path or query");
        }
    }

    private static URI origin(URI uri) {
        try {
            URI value = new URI("https", null, uri.getHost(), uri.getPort(), null, null, null);
            return com.javaclaw.api.PrivateNetworkGrant.normalizeOrigin(value);
        } catch (URISyntaxException failure) {
            throw new IllegalArgumentException("Browser origin is invalid", failure);
        }
    }

    private static Map<String, List<String>> copyHeaders(Map<String, List<String>> source) {
        return Objects.requireNonNull(source, "headers").entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        entry -> text(entry.getKey(), "header name").toLowerCase(Locale.ROOT),
                        entry -> List.copyOf(entry.getValue())));
    }

    private static void requireOutcome(
            Optional<CanonicalPayload> payload, Optional<String> error, boolean payloadRequired) {
        if (payload.isPresent() == error.isPresent() || payloadRequired && payload.isEmpty()) {
            throw new IllegalArgumentException("message must contain exactly one result or error");
        }
    }

    private static void requireVersion(int version) {
        if (version != VERSION) {
            throw new IllegalArgumentException("unsupported Browser Worker protocol version");
        }
    }

    private static long positive(long value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static int bytes(int value, int maximum, String name) {
        if (value < 0 || value > maximum) {
            throw new IllegalArgumentException(name + " is outside the frame limit");
        }
        return value;
    }

    private static String text(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String errorCode(String value, String name) {
        String normalized = text(value, name);
        if (!normalized.matches("[A-Z][A-Z0-9_]{0,79}")) {
            throw new IllegalArgumentException(name + " must be a stable error code");
        }
        return normalized;
    }

    private static String uuid(String value, String name) {
        String normalized = text(value, name);
        try {
            return java.util.UUID.fromString(normalized).toString();
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(name + " must be a UUID", failure);
        }
    }

    private static String identifier(String value, String name) {
        String normalized = text(value, name);
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,239}")) {
            throw new IllegalArgumentException(name + " contains unsupported characters");
        }
        return normalized;
    }
}
