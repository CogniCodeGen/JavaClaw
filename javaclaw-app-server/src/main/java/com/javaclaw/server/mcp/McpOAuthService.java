package com.javaclaw.server.mcp;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.McpAuthType;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpEndpointState;
import com.javaclaw.api.McpHashes;
import com.javaclaw.api.McpOAuthAuthorization;
import com.javaclaw.api.McpOAuthState;
import com.javaclaw.api.VaultState;
import com.javaclaw.extension.spi.McpOAuthAuthorizationRequest;
import com.javaclaw.extension.spi.McpOAuthBrokerPort;
import com.javaclaw.extension.spi.McpOAuthExchange;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CommandLocks;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2Transactions;
import com.javaclaw.server.persistence.IdempotencyRepository;
import com.javaclaw.server.persistence.PersistenceException;
import com.javaclaw.server.security.vault.SecretVaultService;

/**
 * OAuth 2.1 Authorization Code + PKCE 的可恢复服务端状态机。
 *
 * <p><strong>安全不变量：</strong>完整授权 URI、PKCE verifier/state 与 token 均不进入公共返回值。授权 URI 只出现在 {@link BrowserLaunch} 和隔离 Worker
 * 私有管道；PKCE 临时材料只保存在 Vault。服务重启会把遗留 PENDING 会话标记为不可恢复，并在 Vault 可用时清理临时材料。
 */
public final class McpOAuthService {
    /** 人工授权硬时限。 */
    public static final Duration LIFETIME = Duration.ofMinutes(10);

    private final H2Transactions transactions;
    private final McpOAuthRepository sessions = new McpOAuthRepository();
    private final IdempotencyRepository idempotency = new IdempotencyRepository();
    private final McpService mcp;
    private final SecretVaultService vault;
    private final McpOAuthBrokerPort broker;
    private final URI redirectUri;
    private final CanonicalJson json;
    private final Clock clock;
    private final McpOAuthSecurity security = new McpOAuthSecurity();

    /**
     * 创建 OAuth PKCE 服务。
     *
     * @param database data-v6 数据库
     * @param mcp MCP Endpoint 服务
     * @param vault Secret Vault
     * @param broker OAuth metadata/token Broker
     * @param redirectUri 平台固定 loopback 拦截 URI
     * @param json 规范 JSON codec
     * @param clock 平台时钟
     */
    public McpOAuthService(
            H2Database database,
            McpService mcp,
            SecretVaultService vault,
            McpOAuthBrokerPort broker,
            URI redirectUri,
            CanonicalJson json,
            Clock clock) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.mcp = Objects.requireNonNull(mcp, "mcp");
        this.vault = Objects.requireNonNull(vault, "vault");
        this.broker = Objects.requireNonNull(broker, "broker");
        this.redirectUri = security.requireLoopback(redirectUri);
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        recoverInterrupted();
        vault.onChange(this::cleanupTerminatedSecrets);
    }

    /**
     * 读取授权流程的脱敏状态；到期状态会在返回前落库并清理 PKCE。
     *
     * @param id 流程标识
     * @return 脱敏状态
     */
    public McpOAuthAuthorization require(String id) {
        return expireIfNeeded(find(security.identifier(id))).projection();
    }

    /**
     * 读取 Endpoint 最近一次授权流程，供 Desktop 重连或 App Server 重启后恢复页面状态。
     *
     * @param endpointId Endpoint 标识
     * @return 最近授权流程
     */
    public Optional<McpOAuthAuthorization> latest(String endpointId) {
        return execute(connection -> sessions.findLatest(connection, security.identifier(endpointId)))
                .map(this::expireIfNeeded)
                .map(McpOAuthSession::projection);
    }

    /**
     * 生成 PKCE、完成 metadata 发现并返回服务端内部浏览器任务。
     *
     * @param identity 写命令身份
     * @param endpointId MCP Endpoint
     * @return 含脱敏投影和私有 Browser 参数的启动结果
     */
    public BrowserLaunch start(CommandIdentity identity, String endpointId) {
        Optional<McpOAuthAuthorization> recovered = recover(identity);
        if (recovered.isPresent()) {
            return launch(find(recovered.orElseThrow().id()));
        }
        McpEndpoint endpoint = requireOAuthEndpoint(endpointId);
        String authorizationId =
                "oauth-" + McpHashes.sha256(identity.idempotencyKey()).substring(0, 32);
        McpOAuthSecurity.PkceMaterial generated = security.generate();
        byte[] secret = generated.encoded();
        try {
            McpOAuthAuthorizationRequest authorization = authorizationRequest(endpoint, generated);
            CredentialMetadata metadata = vault.create(security.vaultCreateIdentity(identity), "oauth", secret);
            McpOAuthSession pending = new McpOAuthSession(
                    authorizationId,
                    endpoint.id(),
                    endpoint.revision(),
                    authorization.authorizationUri(),
                    metadata.reference(),
                    McpOAuthState.PENDING,
                    clock.instant().plus(LIFETIME),
                    Optional.empty(),
                    clock.instant());
            return launch(recordStart(identity, pending));
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    /**
     * 接收隔离 Browser Worker 私有管道截获的固定 loopback callback 并交换 token。
     *
     * <p>该方法不得注册为 RPC handler。幂等身份只保存 callback 的 SHA-256，不保存 code/state 明文。
     *
     * @param authorizationId OAuth 流程标识
     * @param callbackUri Worker 私有回调 URI
     * @return AUTHORIZED 脱敏状态
     */
    public McpOAuthAuthorization completeBrowser(String authorizationId, URI callbackUri) {
        URI callback = security.requireCallback(callbackUri, redirectUri);
        CommandIdentity identity = security.browserCompleteIdentity(security.identifier(authorizationId), callback);
        Optional<McpOAuthAuthorization> recovered = recover(identity);
        if (recovered.isPresent()) {
            return recovered.orElseThrow();
        }
        McpOAuthSession pending = requirePending(authorizationId);
        CommandIdentity rotate = security.vaultRotateIdentity(identity, pending);
        if (vault.recoverCredential(rotate).isEmpty()) {
            McpEndpoint endpoint = requireFrozenEndpoint(pending);
            exchangeAndSeal(endpoint, pending, callback, rotate);
        }
        CredentialMetadata credential = vault.recoverCredential(rotate)
                .orElseThrow(() -> PersistenceException.invalidRequest("OAuth token 未写入 Vault"));
        mcp.attachOAuthCredential(
                security.oauthAttachIdentity(identity, pending.endpointRevision()),
                pending.endpointId(),
                credential.reference());
        McpOAuthSession authorized = pending.transition(McpOAuthState.AUTHORIZED, Optional.empty(), clock.instant());
        McpOAuthAuthorization result = recordTransition(identity, authorized);
        mcp.probe(pending.endpointId());
        return result;
    }

    /**
     * 取消待处理流程并清理 PKCE 临时材料。
     *
     * @param identity 写命令身份
     * @param authorizationId OAuth 流程标识
     * @return CANCELLED 状态
     */
    public McpOAuthAuthorization cancel(CommandIdentity identity, String authorizationId) {
        Optional<McpOAuthAuthorization> recovered = recover(identity);
        if (recovered.isPresent()) {
            return recovered.orElseThrow();
        }
        McpOAuthSession pending = requirePending(authorizationId);
        McpOAuthSession cancelled = pending.transition(McpOAuthState.CANCELLED, Optional.empty(), clock.instant());
        McpOAuthAuthorization result = recordTransition(identity, cancelled);
        tryClearTemporary(cancelled);
        return result;
    }

    /**
     * 把 Worker 关闭、撤权、Vault lock 或启动失败映射为稳定终态，并使后续 callback 无法使用。
     *
     * @param authorizationId OAuth 流程标识
     * @param state CANCELLED、EXPIRED 或 FAILED
     * @param detail FAILED 的稳定错误码；其他状态可为空
     * @return 当前或新终态
     */
    public McpOAuthAuthorization terminate(String authorizationId, McpOAuthState state, Optional<String> detail) {
        requireTerminal(state);
        McpOAuthSession current = find(security.identifier(authorizationId));
        if (current.state() != McpOAuthState.PENDING) {
            return current.projection();
        }
        McpOAuthSession terminal = current.transition(state, detail, clock.instant());
        execute(connection -> {
            McpOAuthSession locked = sessions.find(connection, current.id(), true)
                    .orElseThrow(() -> PersistenceException.invalidRequest("OAuth session 不存在"));
            if (locked.state() == McpOAuthState.PENDING) {
                sessions.update(connection, terminal);
            }
            return null;
        });
        tryClearTemporary(terminal);
        return find(current.id()).projection();
    }

    /**
     * 启动时关闭遗留 PENDING：Worker 进程和 PKCE 回调通道不可跨 App Server 重启恢复。
     *
     * @return 被关闭的会话数
     */
    public int recoverInterrupted() {
        java.util.List<McpOAuthSession> pending = execute(sessions::pending);
        for (McpOAuthSession session : pending) {
            McpOAuthState state =
                    session.expiresAt().isAfter(clock.instant()) ? McpOAuthState.FAILED : McpOAuthState.EXPIRED;
            Optional<String> detail =
                    state == McpOAuthState.FAILED ? Optional.of("APP_SERVER_RESTARTED") : Optional.empty();
            terminate(session.id(), state, detail);
        }
        return pending.size();
    }

    /** Vault 解锁或刷新后再次清理未授权终态遗留的 PKCE 材料。 */
    public void cleanupTerminatedSecrets() {
        if (vault.status().state() != VaultState.READY) {
            return;
        }
        execute(sessions::cleanupCandidates).forEach(this::tryClearTemporary);
    }

    private void exchangeAndSeal(
            McpEndpoint endpoint, McpOAuthSession pending, URI callbackUri, CommandIdentity rotate) {
        McpOAuthSecurity.PkceMaterial material = readMaterial(pending.credential());
        byte[] token = null;
        try {
            token = broker.exchange(new McpOAuthExchange(
                    endpoint,
                    pending.authorizationUri(),
                    callbackUri,
                    material.verifier(),
                    material.state(),
                    redirectUri,
                    new CancellationSource()));
            vault.rotate(rotate, pending.credential(), token);
        } catch (Exception failure) {
            throw new PersistenceException("OAuth token 交换失败", failure);
        } finally {
            if (token != null) {
                Arrays.fill(token, (byte) 0);
            }
        }
    }

    private McpOAuthSession requirePending(String id) {
        McpOAuthSession value = expireIfNeeded(find(security.identifier(id)));
        if (value.state() != McpOAuthState.PENDING) {
            throw PersistenceException.invalidRequest("OAuth session 不再等待浏览器回调");
        }
        return value;
    }

    private McpOAuthSession expireIfNeeded(McpOAuthSession session) {
        if (session.state() != McpOAuthState.PENDING || session.expiresAt().isAfter(clock.instant())) {
            return session;
        }
        terminate(session.id(), McpOAuthState.EXPIRED, Optional.empty());
        return find(session.id());
    }

    private McpEndpoint requireOAuthEndpoint(String endpointId) {
        if (vault.status().state() != VaultState.READY) {
            throw PersistenceException.invalidRequest("Vault 已锁定，不能启动 MCP OAuth");
        }
        McpEndpoint endpoint = mcp.requireLatest(security.identifier(endpointId));
        if (endpoint.state() != McpEndpointState.ENABLED) {
            throw PersistenceException.invalidRequest("MCP Endpoint 已停用");
        }
        if (endpoint.spec().authType() != McpAuthType.OAUTH_2_1_PKCE) {
            throw PersistenceException.invalidRequest("MCP Endpoint 未配置 OAuth 2.1 PKCE");
        }
        return endpoint;
    }

    private McpEndpoint requireFrozenEndpoint(McpOAuthSession authorization) {
        if (vault.status().state() != VaultState.READY) {
            throw PersistenceException.invalidRequest("Vault 已锁定，OAuth callback 已拒绝");
        }
        McpEndpoint endpoint = mcp.requireLatest(authorization.endpointId());
        if (endpoint.revision() != authorization.endpointRevision()) {
            throw PersistenceException.revisionConflict("OAuth 启动后 MCP Endpoint 已变化");
        }
        if (endpoint.state() != McpEndpointState.ENABLED || endpoint.spec().authType() != McpAuthType.OAUTH_2_1_PKCE) {
            throw PersistenceException.invalidRequest("MCP Endpoint 已撤权或认证方式已变化");
        }
        return endpoint;
    }

    private McpOAuthAuthorizationRequest authorizationRequest(
            McpEndpoint endpoint, McpOAuthSecurity.PkceMaterial material) {
        try {
            return broker.authorizationRequest(endpoint, material.challenge(), material.state(), redirectUri);
        } catch (Exception failure) {
            throw new PersistenceException("OAuth authorization metadata 获取失败", failure);
        }
    }

    private McpOAuthSecurity.PkceMaterial readMaterial(CredentialRef reference) {
        byte[] encoded = vault.use(reference, bytes -> bytes.clone());
        try {
            return security.decode(encoded);
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    private BrowserLaunch launch(McpOAuthSession session) {
        return new BrowserLaunch(
                session.projection(),
                session.authorizationUri(),
                Set.of(security.origin(session.authorizationUri())),
                redirectUri);
    }

    private McpOAuthSession recordStart(CommandIdentity identity, McpOAuthSession pending) {
        synchronized (CommandLocks.forKey(identity.idempotencyKey())) {
            return execute(connection -> {
                Optional<IdempotencyRepository.StoredCommand> stored =
                        idempotency.find(connection, identity.idempotencyKey());
                if (stored.isPresent()) {
                    return find(recover(identity, stored.orElseThrow()).id());
                }
                sessions.insert(connection, pending);
                idempotency.insert(connection, identity, json.encode(pending.projection()), clock.instant());
                return pending;
            });
        }
    }

    private McpOAuthAuthorization recordTransition(CommandIdentity identity, McpOAuthSession authorization) {
        synchronized (CommandLocks.forKey(identity.idempotencyKey())) {
            return execute(connection -> {
                Optional<IdempotencyRepository.StoredCommand> stored =
                        idempotency.find(connection, identity.idempotencyKey());
                if (stored.isPresent()) {
                    return recover(identity, stored.orElseThrow());
                }
                McpOAuthSession current = sessions.find(connection, authorization.id(), true)
                        .orElseThrow(() -> PersistenceException.invalidRequest("OAuth session 不存在"));
                if (current.state() != McpOAuthState.PENDING) {
                    throw PersistenceException.revisionConflict("OAuth session 状态已变化");
                }
                sessions.update(connection, authorization);
                McpOAuthAuthorization projection = authorization.projection();
                idempotency.insert(connection, identity, json.encode(projection), clock.instant());
                return projection;
            });
        }
    }

    private Optional<McpOAuthAuthorization> recover(CommandIdentity identity) {
        return execute(connection ->
                idempotency.find(connection, identity.idempotencyKey()).map(stored -> recover(identity, stored)));
    }

    private McpOAuthAuthorization recover(CommandIdentity identity, IdempotencyRepository.StoredCommand stored) {
        if (!stored.method().equals(identity.method())
                || !stored.requestDigest().equals(identity.requestDigest())) {
            throw PersistenceException.idempotencyConflict("幂等键已被不同 OAuth 命令使用");
        }
        return json.decode(stored.response(), McpOAuthAuthorization.class);
    }

    private McpOAuthSession find(String id) {
        return execute(connection -> sessions.find(connection, id, false)
                .orElseThrow(() -> PersistenceException.invalidRequest("OAuth session 不存在")));
    }

    private void tryClearTemporary(McpOAuthSession session) {
        if (session.state() == McpOAuthState.AUTHORIZED) {
            return;
        }
        try {
            vault.metadata(session.credential())
                    .ifPresent(metadata ->
                            vault.clear(security.cleanupIdentity(session, metadata.revision()), session.credential()));
        } catch (RuntimeException unavailable) {
            // Vault lock 时状态已先变为终态；onChange 会在解锁后继续清理，旧 PKCE 不能再被 complete 使用。
        }
    }

    private static void requireTerminal(McpOAuthState state) {
        if (state != McpOAuthState.CANCELLED && state != McpOAuthState.EXPIRED && state != McpOAuthState.FAILED) {
            throw new IllegalArgumentException("OAuth Browser termination requires a terminal failure state");
        }
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (PersistenceException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("OAuth 状态持久化失败", failure);
        }
    }

    /**
     * App Server 私有 Browser 启动参数。
     *
     * @param authorization 脱敏公共投影
     * @param authorizationUri 含 state/challenge 的完整 URI，禁止进入 RPC/日志
     * @param allowedOrigins metadata 允许的精确 HTTPS Origin
     * @param redirectUri Worker 必须本地截获且不得联网的固定 loopback URI
     */
    public record BrowserLaunch(
            McpOAuthAuthorization authorization, URI authorizationUri, Set<URI> allowedOrigins, URI redirectUri) {
        /** 复制启动边界。 */
        public BrowserLaunch {
            Objects.requireNonNull(authorization, "authorization");
            Objects.requireNonNull(authorizationUri, "authorizationUri");
            allowedOrigins = Set.copyOf(allowedOrigins);
            Objects.requireNonNull(redirectUri, "redirectUri");
        }
    }
}
