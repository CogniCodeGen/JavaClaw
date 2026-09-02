package com.javaclaw.server.mcp;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.BrokerRequest;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.McpAuthType;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpEndpointState;
import com.javaclaw.api.McpOAuthAuthorization;
import com.javaclaw.api.McpOAuthState;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PrivateNetworkGrant;
import com.javaclaw.api.PrivateNetworkGrantRef;
import com.javaclaw.api.PrivateNetworkPurpose;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.VaultState;
import com.javaclaw.browser.client.BrowserNetworkResult;
import com.javaclaw.browser.client.BrowserWorkerPort;
import com.javaclaw.browser.client.McpOAuthBrowserSession;
import com.javaclaw.browser.client.McpOAuthBrowserState;
import com.javaclaw.browser.client.McpOAuthBrowserTask;
import com.javaclaw.browser.protocol.BrowserWorkerProtocol;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.server.lifecycle.LifecycleCoordinator;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.ExtensionCatalogRepository;
import com.javaclaw.server.security.BrowserBrokerResponse;
import com.javaclaw.server.security.PinnedHttpNetworkBroker;
import com.javaclaw.server.security.grant.PrivateNetworkGrantService;
import com.javaclaw.server.security.vault.SecretVaultService;

/**
 * MCP OAuth 持久状态、隔离 Browser Worker、Network Broker 与 lifecycle lease 的组合服务。
 *
 * <p><strong>安全不变量：</strong>Desktop 只能调用 start/read/cancel 并看到脱敏投影。完整 authorization/callback URI 只在当前对象、Worker 私有管道和
 * OAuth 服务调用栈中存在。每个 Browser HTTPS 请求和 callback 都重新检查扩展开关、Endpoint revision/state/auth 与 Vault 状态。
 */
public final class McpOAuthCoordinator {
    private static final ExtensionId MCP = new ExtensionId(BuiltinExtensionIds.MCP);
    private static final int MAXIMUM_RESPONSE_BYTES = BrowserWorkerProtocol.MAXIMUM_NETWORK_BYTES;
    private static final Set<String> CONTROLLED_HEADERS = Set.of(
            "accept-encoding",
            "authorization",
            "connection",
            "content-length",
            "cookie",
            "expect",
            "host",
            "keep-alive",
            "proxy-authorization",
            "proxy-connection",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade");

    private final McpOAuthService service;
    private final McpService mcp;
    private final SecretVaultService vault;
    private final PrivateNetworkGrantService grants;
    private final ExtensionCatalogRepository extensions;
    private final LifecycleCoordinator lifecycle;
    private final Optional<BrowserWorkerPort> browser;
    private final PinnedHttpNetworkBroker network;
    private final ConcurrentHashMap<String, Active> active = new ConcurrentHashMap<>();

    /**
     * 创建 OAuth Browser 组合服务。
     *
     * @param service OAuth 持久状态机
     * @param mcp Endpoint 权威服务
     * @param vault Secret Vault
     * @param grants 私网授权权威服务
     * @param extensions MCP 可选内置扩展目录
     * @param lifecycle App Server lifecycle
     * @param browser 签名发行镜像中的隔离 Browser Worker
     */
    public McpOAuthCoordinator(
            McpOAuthService service,
            McpService mcp,
            SecretVaultService vault,
            PrivateNetworkGrantService grants,
            ExtensionCatalogRepository extensions,
            LifecycleCoordinator lifecycle,
            Optional<BrowserWorkerPort> browser) {
        this.service = Objects.requireNonNull(service, "service");
        this.mcp = Objects.requireNonNull(mcp, "mcp");
        this.vault = Objects.requireNonNull(vault, "vault");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.extensions = Objects.requireNonNull(extensions, "extensions");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.browser = Objects.requireNonNull(browser, "browser").filter(BrowserWorkerPort::oauthAvailable);
        network = new PinnedHttpNetworkBroker();
        vault.onChange(this::revokeInvalidSessions);
    }

    /** @param authorizationId 流程标识 @return 同步后的脱敏状态 */
    public McpOAuthAuthorization require(String authorizationId) {
        McpOAuthAuthorization authorization = service.require(authorizationId);
        synchronize(authorization.id());
        return service.require(authorizationId);
    }

    /** @param endpointId Endpoint 标识 @return 最近一次脱敏状态 */
    public Optional<McpOAuthAuthorization> latest(String endpointId) {
        Optional<McpOAuthAuthorization> authorization = service.latest(endpointId);
        authorization.ifPresent(value -> synchronize(value.id()));
        return service.latest(endpointId);
    }

    /**
     * 启动持久 OAuth 会话和隔离浏览器；缺少签名 Worker 时在写入 PKCE 前 fail closed。
     *
     * @param identity 写命令身份
     * @param endpointId Endpoint 标识
     * @return PENDING 脱敏状态
     */
    public McpOAuthAuthorization start(CommandIdentity identity, String endpointId) {
        extensions.requireEnabled(MCP);
        BrowserWorkerPort worker = requireBrowser();
        McpOAuthService.BrowserLaunch launch = service.start(identity, endpointId);
        if (launch.authorization().state() != McpOAuthState.PENDING) {
            return launch.authorization();
        }
        Active existing = active.get(launch.authorization().id());
        if (existing != null) {
            return launch.authorization();
        }
        String workerSessionId = workerSessionId(launch.authorization().id());
        LifecycleCoordinator.Lease lease = lifecycle.acquireActivity(
                "mcp-oauth:" + launch.authorization().id(), "MCP_OAUTH", McpOAuthService.LIFETIME);
        Active candidate = new Active(workerSessionId, lease);
        Active prior = active.putIfAbsent(launch.authorization().id(), candidate);
        if (prior != null) {
            lease.close();
            return launch.authorization();
        }
        try {
            begin(worker, launch, candidate);
            watch(launch.authorization().id(), candidate);
            return launch.authorization();
        } catch (RuntimeException failure) {
            finish(launch.authorization().id(), candidate);
            service.terminate(
                    launch.authorization().id(), McpOAuthState.FAILED, Optional.of("OAUTH_BROWSER_START_FAILED"));
            throw failure;
        }
    }

    /**
     * 取消浏览器和持久状态；callback 到达后取消只返回已提交终态。
     *
     * @param identity 写命令身份
     * @param authorizationId OAuth 流程标识
     * @return 取消或既有终态
     */
    public McpOAuthAuthorization cancel(CommandIdentity identity, String authorizationId) {
        extensions.requireEnabled(MCP);
        Active current = active.get(authorizationId);
        if (current == null || !current.beginTermination()) {
            return service.cancel(identity, authorizationId);
        }
        try {
            browser.ifPresent(worker -> safelyCancel(worker, current.workerSessionId()));
            return service.cancel(identity, authorizationId);
        } finally {
            finish(authorizationId, current);
        }
    }

    private void begin(BrowserWorkerPort worker, McpOAuthService.BrowserLaunch launch, Active candidate) {
        McpOAuthAuthorization authorization = launch.authorization();
        McpOAuthBrowserTask task = new McpOAuthBrowserTask(
                candidate.workerSessionId(),
                authorization.id(),
                authorization.endpointId(),
                authorization.endpointRevision(),
                launch.authorizationUri(),
                launch.allowedOrigins(),
                launch.redirectUri(),
                McpOAuthService.LIFETIME);
        worker.beginOAuth(
                task,
                (request, body, cancellation) -> exchange(launch, request, body, cancellation),
                callback -> complete(authorization.id(), candidate, callback),
                new CancellationSource());
    }

    private void complete(String authorizationId, Active expected, URI callback) {
        try {
            requireRealtime(service.require(authorizationId));
            service.completeBrowser(authorizationId, callback);
        } catch (RuntimeException failure) {
            service.terminate(authorizationId, McpOAuthState.FAILED, Optional.of("OAUTH_CALLBACK_REJECTED"));
            throw failure;
        } finally {
            finish(authorizationId, expected);
        }
    }

    private BrowserNetworkResult exchange(
            McpOAuthService.BrowserLaunch launch,
            BrowserWorkerProtocol.NetworkRequest request,
            byte[] body,
            com.javaclaw.api.CancellationToken cancellation)
            throws Exception {
        McpOAuthAuthorization authorization =
                service.require(launch.authorization().id());
        McpEndpoint endpoint = requireRealtime(authorization);
        URI origin = PrivateNetworkGrant.normalizeOrigin(request.uri());
        if (!launch.allowedOrigins().contains(origin)) {
            throw new SecurityException("OAuth Browser request Origin is outside discovered metadata");
        }
        BrokerRequest brokerRequest = new BrokerRequest(
                request.uri(),
                request.method(),
                filteredHeaders(request.headers()),
                body,
                MAXIMUM_RESPONSE_BYTES,
                endpoint.spec().requestTimeout());
        BrowserBrokerResponse response = network.exchangeBrowserSingleHop(
                brokerRequest,
                permission(endpoint, request.uri()),
                cancellation,
                (privateOrigin, addresses) -> authorizePrivate(endpoint, privateOrigin, addresses),
                () -> requireRealtime(authorization));
        byte[] responseBody = response.body();
        try {
            return new BrowserNetworkResult(
                    new BrowserWorkerProtocol.NetworkResponse(
                            response.statusCode(), response.headers(), response.truncated()),
                    responseBody);
        } finally {
            Arrays.fill(responseBody, (byte) 0);
        }
    }

    private McpEndpoint requireRealtime(McpOAuthAuthorization authorization) {
        extensions.requireEnabled(MCP);
        if (vault.status().state() != VaultState.READY) {
            throw new SecurityException("Vault is locked");
        }
        McpEndpoint endpoint = mcp.requireLatest(authorization.endpointId());
        if (authorization.state() != McpOAuthState.PENDING
                || endpoint.revision() != authorization.endpointRevision()
                || endpoint.state() != McpEndpointState.ENABLED
                || endpoint.spec().authType() != McpAuthType.OAUTH_2_1_PKCE) {
            throw new SecurityException("OAuth Endpoint authority changed or was revoked");
        }
        return endpoint;
    }

    private void watch(String authorizationId, Active expected) {
        Thread.ofVirtual().name("javaclaw-mcp-oauth-watch").start(() -> {
            while (active.get(authorizationId) == expected) {
                try {
                    requireRealtime(service.require(authorizationId));
                    McpOAuthBrowserSession status = requireBrowser().oauthStatus(expected.workerSessionId());
                    if (terminal(status.state()) && expected.beginTermination()) {
                        try {
                            applyTerminal(authorizationId, status);
                        } finally {
                            finish(authorizationId, expected);
                        }
                        return;
                    }
                    Thread.sleep(Duration.ofMillis(200));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    revoke(authorizationId, expected, "OAUTH_BROWSER_INTERRUPTED");
                    return;
                } catch (RuntimeException revoked) {
                    revoke(authorizationId, expected, "OAUTH_AUTHORITY_REVOKED");
                    return;
                }
            }
        });
    }

    private void revokeInvalidSessions() {
        active.forEach((authorizationId, entry) -> {
            try {
                requireRealtime(service.require(authorizationId));
            } catch (RuntimeException revoked) {
                revoke(authorizationId, entry, "OAUTH_AUTHORITY_REVOKED");
            }
        });
    }

    private void revoke(String authorizationId, Active expected, String code) {
        if (!expected.beginTermination()) {
            return;
        }
        try {
            browser.ifPresent(worker -> safelyCancel(worker, expected.workerSessionId()));
            service.terminate(authorizationId, McpOAuthState.FAILED, Optional.of(code));
        } finally {
            finish(authorizationId, expected);
        }
    }

    private void synchronize(String authorizationId) {
        Active current = active.get(authorizationId);
        if (current == null || browser.isEmpty()) {
            return;
        }
        McpOAuthBrowserSession status = browser.orElseThrow().oauthStatus(current.workerSessionId());
        if (terminal(status.state()) && current.beginTermination()) {
            try {
                applyTerminal(authorizationId, status);
            } finally {
                finish(authorizationId, current);
            }
        }
    }

    private void applyTerminal(String authorizationId, McpOAuthBrowserSession status) {
        switch (status.state()) {
            case COMPLETED -> {
                // 私有 callback 在标记 COMPLETED 前已完成持久 token 交换。
            }
            case CANCELLED -> service.terminate(authorizationId, McpOAuthState.CANCELLED, Optional.empty());
            case EXPIRED -> service.terminate(authorizationId, McpOAuthState.EXPIRED, Optional.empty());
            case FAILED ->
                service.terminate(
                        authorizationId,
                        McpOAuthState.FAILED,
                        Optional.of(status.failureCode().orElse("OAUTH_BROWSER_FAILED")));
            case STARTING, PENDING -> throw new IllegalArgumentException("OAuth Browser state is not terminal");
        }
    }

    private void finish(String authorizationId, Active expected) {
        if (active.remove(authorizationId, expected)) {
            expected.lease().close();
        }
    }

    private BrowserWorkerPort requireBrowser() {
        return browser.orElseThrow(
                () -> new IllegalStateException("OAuth Browser requires a verified packaged Native Sandbox runtime"));
    }

    private void authorizePrivate(McpEndpoint endpoint, URI origin, Set<String> addresses) {
        PrivateNetworkGrantRef reference = endpoint.spec()
                .privateNetworkGrant()
                .orElseThrow(() -> new SecurityException("private OAuth Origin requires an explicit grant"));
        grants.requireAuthorized(
                reference.id(),
                reference.revision(),
                endpoint.spec().workspaceId(),
                PrivateNetworkPurpose.MCP,
                origin,
                addresses);
    }

    private static PermissionProfile permission(McpEndpoint endpoint, URI target) {
        int port = target.getPort() < 0 ? 443 : target.getPort();
        return new PermissionProfile(
                "mcp-oauth-browser-" + endpoint.id(),
                endpoint.revision(),
                new FilePermission(List.of(), List.of(), false, false),
                new NetworkPermission(Set.of(target.getHost()), Set.of(port), true),
                new ProcessPermission(Set.of(), false, endpoint.spec().requestTimeout()),
                new ToolPermission(Set.of(), ToolRisk.NETWORK, ApprovalRequirement.NONE),
                new ResourceLimits(32L * 1024 * 1024, MAXIMUM_RESPONSE_BYTES, 1, 32));
    }

    private static LinkedHashMap<String, List<String>> filteredHeaders(Map<String, List<String>> source) {
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        source.forEach((name, values) -> {
            String normalized = name.toLowerCase(java.util.Locale.ROOT);
            if (!CONTROLLED_HEADERS.contains(normalized)) {
                result.put(normalized, List.copyOf(values));
            }
        });
        return result;
    }

    private static boolean terminal(McpOAuthBrowserState state) {
        return state == McpOAuthBrowserState.COMPLETED
                || state == McpOAuthBrowserState.CANCELLED
                || state == McpOAuthBrowserState.EXPIRED
                || state == McpOAuthBrowserState.FAILED;
    }

    private static String workerSessionId(String authorizationId) {
        return UUID.nameUUIDFromBytes(
                        ("mcp-oauth:" + authorizationId).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
    }

    private static void safelyCancel(BrowserWorkerPort worker, String sessionId) {
        try {
            worker.cancelOAuth(sessionId);
        } catch (RuntimeException ignored) {
            // 持久 OAuth 状态先于 Worker 清理成为终态；进程仍由 Browser Worker 总生命周期关闭。
        }
    }

    private record Active(String workerSessionId, LifecycleCoordinator.Lease lease, AtomicBoolean terminating) {
        private Active(String workerSessionId, LifecycleCoordinator.Lease lease) {
            this(workerSessionId, lease, new AtomicBoolean());
        }

        private Active {
            Objects.requireNonNull(workerSessionId, "workerSessionId");
            Objects.requireNonNull(lease, "lease");
            Objects.requireNonNull(terminating, "terminating");
        }

        private boolean beginTermination() {
            return terminating.compareAndSet(false, true);
        }
    }
}
