package com.javaclaw.server.mcp;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.McpCatalogKind;
import com.javaclaw.api.McpCatalogPage;
import com.javaclaw.api.McpCatalogRefresh;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpEndpointSpec;
import com.javaclaw.api.McpEndpointState;
import com.javaclaw.api.McpHealth;
import com.javaclaw.api.McpHealthState;
import com.javaclaw.api.McpInvocationResult;
import com.javaclaw.api.McpPromptPage;
import com.javaclaw.api.McpPromptResult;
import com.javaclaw.api.McpProtocol;
import com.javaclaw.api.McpRemoteSession;
import com.javaclaw.api.McpResourcePage;
import com.javaclaw.api.McpResourceReadResult;
import com.javaclaw.api.McpTransport;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.extension.spi.McpCredentialStatusPort;
import com.javaclaw.extension.spi.McpNetworkAuthorizationPort;
import com.javaclaw.extension.spi.McpRemotePort;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.McpRpcContracts;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CommandLocks;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2Transactions;
import com.javaclaw.server.persistence.IdempotencyRepository;
import com.javaclaw.server.persistence.PersistenceException;

/**
 * MCP Endpoint、健康、Catalog 与实时工具校验的应用服务。
 *
 * <p>远端读取发生在数据库事务外；只有完整分页、固定协议和 Schema 校验全部成功后，才原子提交新 Catalog revision。执行调用前会重新检查端点开关、端点 revision、Catalog
 * revision、Schema hash、Vault 引用和 Network 授权。
 */
public final class McpService {
    private final H2Transactions transactions;
    private final McpEndpointRepository endpoints;
    private final McpHealthRepository health;
    private final IdempotencyRepository idempotency = new IdempotencyRepository();
    private final McpRemotePort remote;
    private final McpNetworkAuthorizationPort network;
    private final McpCredentialStatusPort credentials;
    private final SignedBundleMcpSource signedBundles;
    private final CanonicalJson json;
    private final Clock clock;
    private final McpCatalogCoordinator catalogCoordinator;
    private final McpInvocationCoordinator invocationCoordinator;

    /**
     * 创建 MCP Host 应用服务。
     *
     * @param database data-v5 数据库
     * @param dependencies 远端、交互、授权、凭据与签名 Bundle 边界
     * @param json 规范 JSON codec
     * @param clock 平台时钟
     */
    public McpService(H2Database database, McpServiceDependencies dependencies, CanonicalJson json, Clock clock) {
        Objects.requireNonNull(dependencies, "dependencies");
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.json = Objects.requireNonNull(json, "json");
        endpoints = new McpEndpointRepository(json);
        health = new McpHealthRepository(json);
        remote = dependencies.remote();
        network = dependencies.network();
        credentials = dependencies.credentials();
        signedBundles = dependencies.signedBundles();
        this.clock = Objects.requireNonNull(clock, "clock");
        catalogCoordinator = new McpCatalogCoordinator(
                database, dependencies.remote(), dependencies.network(), dependencies.credentials(), json, clock);
        invocationCoordinator = new McpInvocationCoordinator(database, dependencies, json, clock);
    }

    /** @param workspaceId Workspace @return 端点最新版本 */
    public List<McpEndpoint> list(WorkspaceId workspaceId) {
        return execute(connection -> endpoints.listLatest(connection, Objects.requireNonNull(workspaceId)));
    }

    /** @param id 端点标识 @return 最新版本 */
    public McpEndpoint requireLatest(String id) {
        return execute(connection -> endpoints
                .latest(connection, identifier(id), false)
                .orElseThrow(() -> PersistenceException.invalidRequest("MCP Endpoint 不存在")));
    }

    /** @param id 端点标识 @return 不可变历史 */
    public List<McpEndpoint> history(String id) {
        return execute(connection -> endpoints.history(connection, identifier(id)));
    }

    /**
     * 创建用户 HTTPS Endpoint；stdio 永远不能从该入口创建。
     *
     * @param identity 写命令身份
     * @param id 端点标识
     * @param spec HTTPS 配置
     * @return 默认停用的首个版本
     */
    public McpEndpoint createHttps(CommandIdentity identity, String id, McpEndpointSpec spec) {
        requireUserHttps(spec);
        return write(identity, identifier(id), spec, McpEndpointState.DISABLED, Optional.empty());
    }

    /**
     * 由已验证签名 Bundle 注册 stdio Endpoint。
     *
     * @param identity 平台写命令身份
     * @param request 只引用已安装 Bundle 的非敏感配置
     * @return 默认停用的首个版本
     */
    public McpEndpoint registerSignedBundle(
            CommandIdentity identity, McpRpcContracts.SignedBundleRegisterPayload request) {
        McpEndpointSpec spec = McpEndpointSpecFactory.signedBundle(request, signedBundles, json);
        return write(identity, request.endpointId(), spec, McpEndpointState.DISABLED, Optional.empty());
    }

    /**
     * 更新用户 HTTPS Endpoint。
     *
     * @param identity 写命令身份
     * @param id 端点标识
     * @param spec 完整候选配置
     * @return 新版本
     */
    public McpEndpoint updateHttps(CommandIdentity identity, String id, McpEndpointSpec spec) {
        requireUserHttps(spec);
        McpEndpoint current = requireLatest(id);
        requireUserHttps(current.spec());
        requireWorkspace(current, spec.workspaceId());
        return write(identity, current.id(), spec, current.state(), Optional.of(current));
    }

    /**
     * 将 OAuth 交换得到的 Vault 引用绑定到启动时冻结的 Endpoint revision。
     *
     * <p>写入与幂等结果处于同一事务。若调用方在 token 密封后崩溃，重试只能恢复该次绑定，不能把凭据附到已经被用户修改的配置。
     *
     * @param identity 内部幂等命令身份
     * @param id Endpoint 标识
     * @param credential OAuth token 的 Vault 引用
     * @return 带凭据的新 Endpoint revision
     */
    McpEndpoint attachOAuthCredential(CommandIdentity identity, String id, CredentialRef credential) {
        Optional<McpEndpoint> recovered = recoverEndpoint(identity);
        if (recovered.isPresent()) {
            return recovered.orElseThrow();
        }
        McpEndpoint current = requireLatest(id);
        requireRevision(current, identity.expectedRevision());
        if (current.spec().authType() != com.javaclaw.api.McpAuthType.OAUTH_2_1_PKCE) {
            throw PersistenceException.invalidRequest("MCP Endpoint 不再使用 OAuth 2.1 PKCE");
        }
        McpEndpointSpec spec =
                McpEndpointSpecFactory.oauth(current.spec(), Objects.requireNonNull(credential, "credential"));
        return write(identity, current.id(), spec, current.state(), Optional.of(current));
    }

    /**
     * 切换 Endpoint；停用会立即使旧 Turn 的后续调用失败。
     *
     * @param identity 写命令身份
     * @param id 端点标识
     * @param state 目标开关
     * @return 新版本
     */
    public McpEndpoint setState(CommandIdentity identity, String id, McpEndpointState state) {
        McpEndpoint current = requireLatest(id);
        return write(identity, current.id(), current.spec(), Objects.requireNonNull(state), Optional.of(current));
    }

    /**
     * 执行非计费 MCP initialize 健康检查并保存脱敏投影。
     *
     * @param id 端点标识
     * @return 健康状态
     */
    public McpHealth probe(String id) {
        McpEndpoint endpoint = requireLatest(id);
        McpHealth result = inspect(endpoint, new CancellationSource());
        execute(connection -> {
            health.save(connection, result);
            return null;
        });
        return result;
    }

    /** @param id 端点标识 @return 最新健康投影；未检查时为 UNKNOWN */
    public McpHealth health(String id) {
        McpEndpoint endpoint = requireLatest(id);
        return execute(connection -> health.find(connection, endpoint.id()))
                .filter(value -> value.endpointRevision() == endpoint.revision())
                .orElseGet(() -> unknown(endpoint));
    }

    /**
     * 分页发现远端目录并原子提交新 Catalog revision。
     *
     * @param identity 写命令身份
     * @param id 端点标识
     * @return 携带新目录版本的新 Endpoint 版本
     */
    public McpEndpoint refreshCatalog(CommandIdentity identity, String id) {
        return catalogCoordinator.refresh(identity, requireLatest(id));
    }

    /** @param endpointId Endpoint 标识 @return 最近一次 Catalog 刷新进度 */
    public Optional<McpCatalogRefresh> catalogRefresh(String endpointId) {
        return catalogCoordinator.refreshStatus(identifier(endpointId));
    }

    /**
     * 读取已提交 Catalog 的服务端分页。
     *
     * @param endpointId 端点标识
     * @param kind 可选类型
     * @param offset 零基偏移
     * @param limit 本页上限
     * @return 当前目录页
     */
    public McpCatalogPage catalog(String endpointId, Optional<McpCatalogKind> kind, int offset, int limit) {
        return catalogCoordinator.page(requireLatest(endpointId), kind, offset, limit);
    }

    /**
     * 直接读取 HTTPS Endpoint 的一页外部 Resource；结果不进入 Item、Prompt 或 system context。
     *
     * @param endpointId Endpoint 标识
     * @param cursor 可选远端 cursor
     * @return 强类型外部 Resource 页
     */
    public McpResourcePage resources(String endpointId, Optional<String> cursor) {
        McpEndpoint endpoint = requireExternalDataEndpoint(endpointId);
        return externalCall(
                () -> remote.resources(endpoint, Objects.requireNonNull(cursor, "cursor"), new CancellationSource()));
    }

    /**
     * 显式读取 HTTPS MCP Resource；正文只返回给调用方，不自动注入任何 Turn。
     *
     * @param endpointId Endpoint 标识
     * @param uri 远端 Resource URI
     * @return 强类型外部内容
     */
    public McpResourceReadResult readResource(String endpointId, String uri) {
        McpEndpoint endpoint = requireExternalDataEndpoint(endpointId);
        return externalCall(() -> remote.readResource(endpoint, uri, new CancellationSource()));
    }

    /**
     * 直接读取 HTTPS Endpoint 的一页外部 Prompt 模板。
     *
     * @param endpointId Endpoint 标识
     * @param cursor 可选远端 cursor
     * @return 强类型外部 Prompt 页
     */
    public McpPromptPage prompts(String endpointId, Optional<String> cursor) {
        McpEndpoint endpoint = requireExternalDataEndpoint(endpointId);
        return externalCall(
                () -> remote.prompts(endpoint, Objects.requireNonNull(cursor, "cursor"), new CancellationSource()));
    }

    /**
     * 显式展开 HTTPS MCP Prompt；外部消息不会成为系统指令。
     *
     * @param endpointId Endpoint 标识
     * @param name Prompt 名称
     * @param arguments 用户显式提供的参数
     * @return 强类型外部消息
     */
    public McpPromptResult getPrompt(String endpointId, String name, Map<String, String> arguments) {
        McpEndpoint endpoint = requireExternalDataEndpoint(endpointId);
        return externalCall(() -> remote.getPrompt(endpoint, name, arguments, new CancellationSource()));
    }

    /**
     * 生成 Workspace 当前 MCP Tool 描述；同名冲突会拒绝冻结。
     *
     * @param workspaceId Workspace
     * @return 稳定名称排序的描述
     */
    public List<ToolDescriptor> toolDescriptors(WorkspaceId workspaceId) {
        return catalogCoordinator.toolDescriptors(list(workspaceId));
    }

    /**
     * 从 Turn 冻结描述执行 MCP Tool；执行前重新检查所有可撤销状态。
     *
     * @param turnId 当前 Turn
     * @param workspaceId Turn Workspace
     * @param descriptor 冻结 Tool 描述
     * @param arguments 规范参数
     * @param idempotencyKey 去重键
     * @param cancellation 取消信号
     * @return MCP Tool 结果
     * @throws Exception 远端调用失败
     */
    public McpInvocationResult invoke(
            TurnId turnId,
            WorkspaceId workspaceId,
            ToolDescriptor descriptor,
            CanonicalPayload arguments,
            String idempotencyKey,
            CancellationToken cancellation)
            throws Exception {
        return invocationCoordinator.invoke(turnId, workspaceId, descriptor, arguments, idempotencyKey, cancellation);
    }

    private McpEndpoint write(
            CommandIdentity identity,
            String id,
            McpEndpointSpec spec,
            McpEndpointState state,
            Optional<McpEndpoint> expectedCurrent) {
        Objects.requireNonNull(identity, "identity");
        synchronized (CommandLocks.forKey(identity.idempotencyKey())) {
            return execute(connection -> {
                Optional<IdempotencyRepository.StoredCommand> stored =
                        idempotency.find(connection, identity.idempotencyKey());
                if (stored.isPresent()) {
                    return recover(identity, stored.orElseThrow());
                }
                Optional<McpEndpoint> current = endpoints.latest(connection, id, true);
                requireRevision(current, identity.expectedRevision());
                expectedCurrent.ifPresent(value -> requireSameCurrent(value, current.orElseThrow()));
                Instant now = clock.instant();
                McpEndpoint endpoint = new McpEndpoint(
                        id,
                        identity.expectedRevision() + 1,
                        state,
                        current.map(McpEndpoint::catalogRevision).orElse(0L),
                        spec,
                        current.map(McpEndpoint::createdAt).orElse(now),
                        now);
                endpoints.insert(connection, endpoint);
                idempotency.insert(connection, identity, json.encode(endpoint), now);
                return endpoint;
            });
        }
    }

    private McpHealth inspect(McpEndpoint endpoint, CancellationToken cancellation) {
        Instant checkedAt = clock.instant();
        if (endpoint.state() == McpEndpointState.DISABLED) {
            return new McpHealth(
                    endpoint.id(),
                    endpoint.revision(),
                    McpHealthState.UNAVAILABLE,
                    Optional.empty(),
                    Optional.of("Endpoint 已停用"),
                    checkedAt);
        }
        if (!credentialAvailable(endpoint)) {
            return new McpHealth(
                    endpoint.id(),
                    endpoint.revision(),
                    McpHealthState.AUTH_REQUIRED,
                    Optional.empty(),
                    Optional.of("CredentialRef 不可用"),
                    checkedAt);
        }
        try {
            authorize(endpoint);
            McpRemoteSession session = remote.initialize(endpoint, McpProtocol.VERSION, cancellation);
            McpHealthState state = McpProtocol.VERSION.equals(session.protocolVersion())
                    ? McpHealthState.HEALTHY
                    : McpHealthState.PROTOCOL_MISMATCH;
            return new McpHealth(
                    endpoint.id(),
                    endpoint.revision(),
                    state,
                    Optional.of(session.protocolVersion()),
                    state == McpHealthState.HEALTHY ? Optional.empty() : Optional.of("远端协议版本不受支持"),
                    checkedAt);
        } catch (Exception failure) {
            return new McpHealth(
                    endpoint.id(),
                    endpoint.revision(),
                    McpHealthState.UNAVAILABLE,
                    Optional.empty(),
                    Optional.of(failure.getClass().getSimpleName()),
                    checkedAt);
        }
    }

    private void requireCredential(McpEndpoint endpoint) {
        if (!credentialAvailable(endpoint)) {
            throw PersistenceException.invalidRequest("MCP CredentialRef 已清除或 Vault 已锁定");
        }
    }

    private McpEndpoint requireExternalDataEndpoint(String endpointId) {
        McpEndpoint endpoint = requireLatest(endpointId);
        if (endpoint.state() != McpEndpointState.ENABLED) {
            throw PersistenceException.invalidRequest("MCP Endpoint 已停用");
        }
        if (endpoint.spec().transport() != McpTransport.STREAMABLE_HTTPS) {
            throw PersistenceException.invalidRequest("Resource 与 Prompt 管理只允许 HTTPS MCP Endpoint");
        }
        requireCredential(endpoint);
        authorize(endpoint);
        return endpoint;
    }

    private <T> T externalCall(ExternalCall<T> call) {
        try {
            return call.execute();
        } catch (PersistenceException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("MCP 外部数据读取失败", failure);
        }
    }

    private boolean credentialAvailable(McpEndpoint endpoint) {
        if (endpoint.spec().authType() == com.javaclaw.api.McpAuthType.NONE) {
            return true;
        }
        return endpoint.spec().credential().map(credentials::available).orElse(false);
    }

    private void authorize(McpEndpoint endpoint) {
        if (endpoint.spec().transport() != McpTransport.STREAMABLE_HTTPS) {
            return;
        }
        try {
            network.authorize(endpoint);
        } catch (Exception failure) {
            throw new PersistenceException("MCP Network Broker 授权失败", failure);
        }
    }

    private McpEndpoint recover(CommandIdentity identity, IdempotencyRepository.StoredCommand stored) {
        if (!stored.method().equals(identity.method())
                || !stored.requestDigest().equals(identity.requestDigest())) {
            throw PersistenceException.idempotencyConflict("幂等键已被不同 MCP 命令使用");
        }
        return json.decode(stored.response(), McpEndpoint.class);
    }

    private Optional<McpEndpoint> recoverEndpoint(CommandIdentity identity) {
        CommandIdentity checked = Objects.requireNonNull(identity, "identity");
        return execute(connection ->
                idempotency.find(connection, checked.idempotencyKey()).map(stored -> recover(checked, stored)));
    }

    private static void requireUserHttps(McpEndpointSpec spec) {
        if (Objects.requireNonNull(spec, "spec").transport() != McpTransport.STREAMABLE_HTTPS) {
            throw PersistenceException.invalidRequest("用户只能配置 HTTPS MCP Endpoint");
        }
    }

    private static void requireWorkspace(McpEndpoint endpoint, WorkspaceId workspaceId) {
        if (!endpoint.spec().workspaceId().equals(Objects.requireNonNull(workspaceId))) {
            throw PersistenceException.invalidRequest("MCP Endpoint 不属于当前 Workspace");
        }
    }

    private static void requireSameCurrent(McpEndpoint expected, McpEndpoint current) {
        if (!expected.equals(current)) {
            throw PersistenceException.revisionConflict("MCP Endpoint 已变化");
        }
    }

    private static void requireRevision(Optional<McpEndpoint> current, long expectedRevision) {
        if (current.isEmpty() && expectedRevision == 0) {
            return;
        }
        if (current.isEmpty() || current.orElseThrow().revision() != expectedRevision) {
            throw PersistenceException.revisionConflict("MCP Endpoint revision 已变化");
        }
    }

    private static void requireRevision(McpEndpoint current, long expectedRevision) {
        if (current.revision() != expectedRevision) {
            throw PersistenceException.revisionConflict("MCP Endpoint revision 已变化");
        }
    }

    private static String identifier(String value) {
        String id = Objects.requireNonNull(value, "id").strip();
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,239}")) {
            throw new IllegalArgumentException("MCP id contains unsupported characters");
        }
        return id;
    }

    private McpHealth unknown(McpEndpoint endpoint) {
        return new McpHealth(
                endpoint.id(),
                endpoint.revision(),
                McpHealthState.UNKNOWN,
                Optional.empty(),
                Optional.empty(),
                clock.instant());
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (PersistenceException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("MCP 持久化失败", failure);
        }
    }

    @FunctionalInterface
    private interface ExternalCall<T> {
        T execute() throws Exception;
    }
}
