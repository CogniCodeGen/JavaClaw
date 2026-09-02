package com.javaclaw.server.security;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.javaclaw.api.BrokerRequest;
import com.javaclaw.api.BrokerResponse;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.NetworkBroker;
import com.javaclaw.api.PermissionProfile;

/**
 * 解析、验证并固定每一跳目标地址的宿主 HTTP Broker。
 *
 * <p><strong>安全不变量：</strong>每次重定向都重新执行精确 host/port/TLS 权限与全部 DNS 地址的公网校验；实际 socket 只连接已校验的 {@code InetAddress}，TLS 仍使用原
 * DNS 主机执行 SNI 和证书校验。响应去除 Cookie 与逐跳 header，正文和总时限取请求、权限与平台 ceiling 的最小值。
 */
public final class PinnedHttpNetworkBroker implements NetworkBroker {
    private static final Duration MAXIMUM_TIMEOUT = Duration.ofMinutes(2);
    private static final int MAXIMUM_BODY_BYTES = 16 * 1024 * 1024;
    private static final int MAXIMUM_REDIRECTS = 5;
    private static final Set<String> SENSITIVE_REDIRECT_HEADERS = Set.of("authorization", "cookie");
    private static final Set<String> FILTERED_RESPONSE_HEADERS = Set.of(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "set-cookie",
            "set-cookie2",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade");
    private static final Set<String> BROWSER_FILTERED_RESPONSE_HEADERS = Set.of(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade");

    private final BrokerTarget.HostResolver resolver;
    private final HttpWireExchange wire;

    /** 创建使用系统 DNS、无代理 socket 与默认 JVM TLS 信任库的 Broker。 */
    public PinnedHttpNetworkBroker() {
        this(new BrokerTarget.SystemHostResolver(), new SocketBrokerTransport());
    }

    PinnedHttpNetworkBroker(BrokerTarget.HostResolver resolver, HttpWireExchange.Transport transport) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        wire = new HttpWireExchange(transport);
    }

    /**
     * 执行受控 HTTP 请求。
     *
     * @param request 已验证请求
     * @param permission 当前调用最终有效权限
     * @param cancellation Turn 或扩展调用取消信号
     * @return 有界、已去除逐跳与 Cookie header 的响应
     * @throws Exception DNS、权限、TLS、HTTP framing、取消或时限失败
     */
    @Override
    public BrokerResponse exchange(BrokerRequest request, PermissionProfile permission, CancellationToken cancellation)
            throws Exception {
        return exchange(request, permission, cancellation, AddressAuthorization.rejectPrivate());
    }

    /**
     * 执行可由上层精确授权 RFC1918/ULA 地址的受控请求。
     *
     * <p>回调会在 DNS 全量解析后、socket 建立前调用。Broker 自身仍永久拒绝 loopback、link-local、metadata、multicast 以及其他保留地址，回调无权放宽这些 ceiling。
     *
     * @param request 已验证请求
     * @param permission 当前调用最终有效权限
     * @param cancellation 取消信号
     * @param privateAuthorization 私网 Origin、DNS 集合和授权 revision 检查
     * @return 有界脱敏响应
     * @throws Exception DNS、授权、TLS、HTTP framing、取消或时限失败
     */
    public BrokerResponse exchange(
            BrokerRequest request,
            PermissionProfile permission,
            CancellationToken cancellation,
            AddressAuthorization privateAuthorization)
            throws Exception {
        BrokerRequest checkedRequest = Objects.requireNonNull(request, "request");
        PermissionProfile checkedPermission = Objects.requireNonNull(permission, "permission");
        CancellationToken checkedCancellation = Objects.requireNonNull(cancellation, "cancellation");
        AddressAuthorization checkedAuthorization =
                Objects.requireNonNull(privateAuthorization, "privateAuthorization");
        checkedCancellation.throwIfCancelled();
        int bodyLimit = bodyLimit(checkedRequest, checkedPermission);
        Duration timeout =
                minimum(checkedRequest.timeout(), checkedPermission.processes().maxRunTime(), MAXIMUM_TIMEOUT);
        BrokerDeadline deadline = BrokerDeadline.start(timeout);
        HttpWireExchange.Request current =
                new HttpWireExchange.Request(checkedRequest.method(), checkedRequest.headers(), checkedRequest.body());
        URI uri = checkedRequest.uri();
        for (int redirects = 0; ; redirects++) {
            BrokerTarget target = BrokerTarget.resolve(
                    uri, checkedPermission.network(), resolver, deadline, checkedCancellation, checkedAuthorization);
            HttpWireExchange.WireResponse response =
                    wire.exchange(target, current, bodyLimit, deadline, checkedCancellation);
            if (!redirect(response.statusCode())) {
                return response(response);
            }
            if (redirects == MAXIMUM_REDIRECTS) {
                throw new SecurityException("Network Broker redirect limit exceeded");
            }
            URI next = redirectUri(uri, response.headers());
            current = redirected(current, uri, next, response.statusCode());
            uri = next;
        }
    }

    /**
     * 为隔离 Browser 执行一次不跟随重定向的 HTTPS 交换。
     *
     * <p>每个重定向和子资源必须由 Browser 再次发起，因此每一跳都会重新解析 DNS、复查 Site authority 与实时撤权。 响应可保留 {@code Set-Cookie}，返回值只能写入 Worker
     * 私有管道。
     *
     * @param request 已过滤的单跳 HTTPS 请求
     * @param permission 当前调用最终有效权限
     * @param cancellation 取消信号
     * @param privateAuthorization 精确私网授权复查
     * @return 仅供隔离 Browser 使用的敏感响应
     * @throws Exception DNS、权限、TLS、HTTP framing、取消或时限失败
     */
    public BrowserBrokerResponse exchangeBrowserSingleHop(
            BrokerRequest request,
            PermissionProfile permission,
            CancellationToken cancellation,
            AddressAuthorization privateAuthorization)
            throws Exception {
        return exchangeBrowserSingleHop(request, permission, cancellation, privateAuthorization, () -> {});
    }

    /**
     * 与四参数版本相同，并在 DNS 固定后、打开 socket 前执行一次实时授权复查。
     *
     * @param request 已过滤的单跳 HTTPS 请求
     * @param permission 当前调用最终有效权限
     * @param cancellation 取消信号
     * @param privateAuthorization 精确私网授权复查
     * @param realtimeAuthorization Site authority 或其他实时 kill switch
     * @return 仅供隔离 Browser 使用的敏感响应
     * @throws Exception DNS、权限、实时撤权、TLS、HTTP framing、取消或时限失败
     */
    public BrowserBrokerResponse exchangeBrowserSingleHop(
            BrokerRequest request,
            PermissionProfile permission,
            CancellationToken cancellation,
            AddressAuthorization privateAuthorization,
            RealtimeAuthorization realtimeAuthorization)
            throws Exception {
        BrokerRequest checkedRequest = Objects.requireNonNull(request, "request");
        PermissionProfile checkedPermission = Objects.requireNonNull(permission, "permission");
        CancellationToken checkedCancellation = Objects.requireNonNull(cancellation, "cancellation");
        AddressAuthorization checkedAuthorization =
                Objects.requireNonNull(privateAuthorization, "privateAuthorization");
        RealtimeAuthorization checkedRealtime = Objects.requireNonNull(realtimeAuthorization, "realtimeAuthorization");
        if (!"https".equalsIgnoreCase(checkedRequest.uri().getScheme())) {
            throw new SecurityException("Browser Broker accepts only HTTPS");
        }
        checkedCancellation.throwIfCancelled();
        int bodyLimit = bodyLimit(checkedRequest, checkedPermission);
        Duration timeout =
                minimum(checkedRequest.timeout(), checkedPermission.processes().maxRunTime(), MAXIMUM_TIMEOUT);
        BrokerDeadline deadline = BrokerDeadline.start(timeout);
        BrokerTarget target = BrokerTarget.resolve(
                checkedRequest.uri(),
                checkedPermission.network(),
                resolver,
                deadline,
                checkedCancellation,
                checkedAuthorization);
        checkedCancellation.throwIfCancelled();
        checkedRealtime.authorize();
        HttpWireExchange.Request wireRequest =
                new HttpWireExchange.Request(checkedRequest.method(), checkedRequest.headers(), checkedRequest.body());
        return browserResponse(wire.exchange(target, wireRequest, bodyLimit, deadline, checkedCancellation));
    }

    private static int bodyLimit(BrokerRequest request, PermissionProfile permission) {
        long resourceLimit = permission.resources().outputBytes();
        if (request.body().length > Math.min(resourceLimit, MAXIMUM_BODY_BYTES)) {
            throw new SecurityException("Network Broker request body exceeds the effective resource limit");
        }
        return Math.toIntExact(Math.min(Math.min(request.maximumResponseBytes(), resourceLimit), MAXIMUM_BODY_BYTES));
    }

    private static URI redirectUri(URI current, Map<String, List<String>> headers) {
        List<String> locations = headers.getOrDefault("location", List.of());
        if (locations.isEmpty() || locations.stream().distinct().count() != 1) {
            throw new SecurityException("redirect response requires one unambiguous Location header");
        }
        try {
            return current.resolve(locations.getFirst()).normalize();
        } catch (IllegalArgumentException failure) {
            throw new SecurityException("redirect Location is invalid", failure);
        }
    }

    private static HttpWireExchange.Request redirected(
            HttpWireExchange.Request request, URI current, URI next, int statusCode) {
        LinkedHashMap<String, List<String>> headers = new LinkedHashMap<>(request.headers());
        if (!sameOrigin(current, next)) {
            SENSITIVE_REDIRECT_HEADERS.forEach(headers::remove);
        }
        boolean switchToGet = statusCode == 303 && !"HEAD".equals(request.method())
                || (statusCode == 301 || statusCode == 302) && "POST".equals(request.method());
        if (!switchToGet) {
            return new HttpWireExchange.Request(request.method(), headers, request.body());
        }
        headers.remove("content-type");
        headers.remove("content-encoding");
        return new HttpWireExchange.Request("GET", headers, new byte[0]);
    }

    private static BrokerResponse response(HttpWireExchange.WireResponse response) {
        LinkedHashMap<String, List<String>> headers = new LinkedHashMap<>();
        response.headers().forEach((name, values) -> {
            if (!FILTERED_RESPONSE_HEADERS.contains(name) && !(response.truncated() && "content-length".equals(name))) {
                headers.put(name, values);
            }
        });
        return new BrokerResponse(response.statusCode(), Map.copyOf(headers), response.body(), response.truncated());
    }

    private static BrowserBrokerResponse browserResponse(HttpWireExchange.WireResponse response) {
        LinkedHashMap<String, List<String>> headers = new LinkedHashMap<>();
        response.headers().forEach((name, values) -> {
            if (!BROWSER_FILTERED_RESPONSE_HEADERS.contains(name)
                    && !(response.truncated() && "content-length".equals(name))) {
                headers.put(name, values);
            }
        });
        return new BrowserBrokerResponse(
                response.statusCode(), Map.copyOf(headers), response.body(), response.truncated());
    }

    private static boolean redirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303 || statusCode == 307 || statusCode == 308;
    }

    private static boolean sameOrigin(URI first, URI second) {
        return first.getScheme().equalsIgnoreCase(second.getScheme())
                && first.getHost().equalsIgnoreCase(second.getHost())
                && effectivePort(first) == effectivePort(second);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equals(uri.getScheme().toLowerCase(Locale.ROOT)) ? 443 : 80;
    }

    private static Duration minimum(Duration first, Duration second, Duration third) {
        Duration result = first.compareTo(second) <= 0 ? first : second;
        return result.compareTo(third) <= 0 ? result : third;
    }

    /** DNS 固定后对精确非公网地址集合执行的授权回调。 */
    @FunctionalInterface
    public interface AddressAuthorization {
        /**
         * 校验当前连接的精确 Origin 和全量 DNS 集合。
         *
         * @param origin 当前连接的 HTTPS Origin
         * @param dnsAddresses 规范数字地址集合
         */
        void authorize(URI origin, Set<String> dnsAddresses);

        private static AddressAuthorization rejectPrivate() {
            return (origin, addresses) -> {
                throw new SecurityException("Network Broker requires an explicit private-network grant");
            };
        }
    }

    /** DNS 固定后、socket 建立前执行的实时撤权边界。 */
    @FunctionalInterface
    public interface RealtimeAuthorization {
        /** 拒绝时抛出异常，调用方不得继续建立 socket。 */
        void authorize() throws Exception;
    }
}
