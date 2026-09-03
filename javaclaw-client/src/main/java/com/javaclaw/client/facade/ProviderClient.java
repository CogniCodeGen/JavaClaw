package com.javaclaw.client.facade;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.EmbeddingBinding;
import com.javaclaw.api.ProviderCredentialBinding;
import com.javaclaw.api.ProviderCredentialClearResult;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelDiscoveryOperation;
import com.javaclaw.api.ProviderModelDiscoveryOperationState;
import com.javaclaw.api.ProviderModelDiscoveryRequest;
import com.javaclaw.api.ProviderModelDiscoveryResult;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ProviderStatus;
import com.javaclaw.api.ProviderVerificationResult;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.protocol.ProviderCredentialRpcContracts;
import com.javaclaw.protocol.ProviderModelDiscoveryRpcContracts;
import com.javaclaw.protocol.ProviderProfileRpcContracts;
import com.javaclaw.protocol.ProviderVerificationRpcContracts;
import com.javaclaw.protocol.SealedSecret;
import com.javaclaw.protocol.SessionKeyInfo;
import com.javaclaw.protocol.SessionSecretSealer;

/** Provider 强类型查询、版本写入、归档与本地探测 facade。 */
public final class ProviderClient {
    private final RpcClientConnection connection;
    private final SessionKeyInfo sessionKey;

    /**
     * 创建 facade。
     *
     * @param connection 已初始化连接
     * @param sessionKey initialize 返回的会话公钥
     */
    public ProviderClient(RpcClientConnection connection, SessionKeyInfo sessionKey) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.sessionKey = Objects.requireNonNull(sessionKey, "sessionKey");
    }

    /** @return 每个 Provider 的最新版本 */
    public List<ProviderEndpoint> list() {
        return connection
                .query("provider/list", Map.of(), ProviderProfileRpcContracts.ProviderListResult.class)
                .providers();
    }

    /**
     * 读取精确版本。
     *
     * @param id Provider 标识
     * @param revision 版本
     * @return Provider
     */
    public ProviderEndpoint read(String id, long revision) {
        return connection.query(
                "provider/read",
                new ProviderProfileRpcContracts.ProviderReadPayload(id, revision),
                ProviderEndpoint.class);
    }

    /**
     * 创建 Provider。
     *
     * @param id 稳定标识
     * @param spec 完整配置
     * @param lifecycle ACTIVE 或 DISABLED；禁用连接允许暂时没有模型
     * @param options expected revision 必须为 0
     * @return 首个版本
     */
    public ProviderEndpoint create(
            String id, ProviderEndpointSpec spec, ProviderLifecycle lifecycle, CommandOptions options) {
        return connection.command(
                "provider/create",
                new ProviderProfileRpcContracts.ProviderCreatePayload(id, spec, lifecycle),
                options,
                ProviderEndpoint.class);
    }

    /**
     * 更新 Provider。
     *
     * @param id 稳定标识
     * @param spec 完整配置
     * @param lifecycle ACTIVE 或 DISABLED
     * @param options expected revision 必须匹配当前版本
     * @return 新版本
     */
    public ProviderEndpoint update(
            String id, ProviderEndpointSpec spec, ProviderLifecycle lifecycle, CommandOptions options) {
        return connection.command(
                "provider/update",
                new ProviderProfileRpcContracts.ProviderUpdatePayload(id, spec, lifecycle),
                options,
                ProviderEndpoint.class);
    }

    /**
     * 归档 Provider。
     *
     * @param id Provider 标识
     * @param options expected revision 必须匹配当前版本
     * @return 归档版本
     */
    public ProviderEndpoint archive(String id, CommandOptions options) {
        return connection.command(
                "provider/archive",
                new ProviderProfileRpcContracts.ProviderArchivePayload(id),
                options,
                ProviderEndpoint.class);
    }

    /**
     * 读取已保存精确版本的远端模型目录，不执行模型推理。
     *
     * @param id Provider 标识
     * @param revision 精确 Provider 版本
     * @param cancellation 页面、作用域或上层会话的取消信号
     * @return 有界模型候选和截断状态
     */
    public ProviderModelDiscoveryResult discoverModels(String id, long revision, CancellationToken cancellation) {
        CancellationToken checkedCancellation = Objects.requireNonNull(cancellation, "cancellation");
        checkedCancellation.throwIfCancelled();
        ProviderModelDiscoveryOperation operation = connection.command(
                ProviderModelDiscoveryRpcContracts.START_METHOD,
                new ProviderModelDiscoveryRequest(id, revision),
                CommandOptions.create(revision),
                ProviderModelDiscoveryOperation.class);
        while (!operation.terminal()) {
            if (checkedCancellation.isCancelled()) {
                cancelDiscovery(operation, ProviderModelDiscoveryRpcContracts.CLIENT_CANCELLED);
                checkedCancellation.throwIfCancelled();
            }
            java.util.concurrent.locks.LockSupport.parkNanos(
                    java.time.Duration.ofMillis(100).toNanos());
            if (Thread.interrupted()) {
                cancelDiscovery(operation, ProviderModelDiscoveryRpcContracts.THREAD_INTERRUPTED);
                Thread.currentThread().interrupt();
                throw new com.javaclaw.api.TurnCancelledException("Provider model discovery was interrupted");
            }
            if (checkedCancellation.isCancelled()) {
                continue;
            }
            operation = connection.query(
                    ProviderModelDiscoveryRpcContracts.READ_METHOD,
                    new ProviderModelDiscoveryRpcContracts.ReadPayload(operation.operationId()),
                    ProviderModelDiscoveryOperation.class);
        }
        checkedCancellation.throwIfCancelled();
        return discoveryResult(operation);
    }

    private void cancelDiscovery(ProviderModelDiscoveryOperation operation, String reason) {
        try {
            connection.command(
                    ProviderModelDiscoveryRpcContracts.CANCEL_METHOD,
                    new ProviderModelDiscoveryRpcContracts.CancelPayload(operation.operationId(), reason),
                    CommandOptions.create(operation.revision()),
                    ProviderModelDiscoveryOperation.class);
        } catch (RuntimeException ignored) {
            // 本地连接可能已关闭；服务端 session close 回调仍会取消其拥有的网络调用。
        }
    }

    private static ProviderModelDiscoveryResult discoveryResult(ProviderModelDiscoveryOperation operation) {
        if (operation.state() == ProviderModelDiscoveryOperationState.SUCCEEDED) {
            return operation.result().orElseThrow();
        }
        if (operation.state() == ProviderModelDiscoveryOperationState.CANCELLED) {
            throw new com.javaclaw.api.TurnCancelledException("Provider model discovery was cancelled");
        }
        throw new IllegalStateException(
                "Provider model discovery failed: " + operation.failureCode().orElse("DISCOVERY_FAILED"));
    }

    /**
     * 读取本地安装默认 Embedding 绑定。
     *
     * @return 未配置时为空
     */
    public Optional<EmbeddingBinding> embeddingBinding() {
        return connection
                .query(
                        "provider/embeddingBinding/read",
                        new ProviderProfileRpcContracts.EmbeddingBindingReadPayload(),
                        ProviderProfileRpcContracts.EmbeddingBindingReadResult.class)
                .binding();
    }

    /**
     * 创建或替换本地安装默认 Embedding 绑定。
     *
     * @param provider 精确 Provider 与 Embedding 模型
     * @param options 绑定自身的 expected revision
     * @return 已提交绑定
     */
    public EmbeddingBinding bindEmbedding(ProviderRef provider, CommandOptions options) {
        return connection.command(
                "provider/embeddingBinding/update",
                new ProviderProfileRpcContracts.EmbeddingBindingUpdatePayload(provider),
                options,
                EmbeddingBinding.class);
    }

    /**
     * 原子创建并绑定或轮换 Provider Secret。
     *
     * @param id Provider 标识
     * @param providerRevision Provider 当前版本
     * @param credentialRevision 未绑定时为 0，轮换时为当前凭据版本
     * @param secret 敏感字符；调用方必须在返回后清零
     * @param options 幂等键，expected revision 必须等于 Provider 当前版本
     * @return Provider 新版本与脱敏凭据元数据
     */
    public ProviderCredentialBinding setCredential(
            String id, long providerRevision, long credentialRevision, char[] secret, CommandOptions options) {
        requireProviderRevision(providerRevision, options);
        SealedSecret sealed = SessionSecretSealer.seal(
                sessionKey, ProviderCredentialRpcContracts.SET_PURPOSE, Objects.requireNonNull(secret, "secret"));
        return connection.command(
                "provider/credential/set",
                new ProviderCredentialRpcContracts.SetPayload(id, providerRevision, credentialRevision, sealed),
                options,
                ProviderCredentialBinding.class);
    }

    /**
     * 原子解除 Provider 引用并清除 Vault Secret。
     *
     * @param id Provider 标识
     * @param providerRevision Provider 当前版本
     * @param credential 当前 Provider 凭据引用
     * @param credentialRevision 凭据当前版本
     * @param options 幂等键，expected revision 必须等于 Provider 当前版本
     * @return Provider 新版本与清除回执
     */
    public ProviderCredentialClearResult clearCredential(
            String id,
            long providerRevision,
            com.javaclaw.api.CredentialRef credential,
            long credentialRevision,
            CommandOptions options) {
        requireProviderRevision(providerRevision, options);
        return connection.command(
                "provider/credential/clear",
                new ProviderCredentialRpcContracts.ClearPayload(id, providerRevision, credential, credentialRevision),
                options,
                ProviderCredentialClearResult.class);
    }

    /**
     * 读取非计费本地状态。
     *
     * @param provider 精确 Provider 与模型
     * @return 状态
     */
    public ProviderStatus status(ProviderRef provider) {
        return connection.query(
                "provider/status",
                new ProviderProfileRpcContracts.ProviderProbePayload(provider),
                ProviderStatus.class);
    }

    /**
     * 重新执行非计费本地配置检查；不会向模型发送请求。
     *
     * @param provider 精确 Provider 与模型
     * @return 检查状态
     */
    public ProviderStatus probe(ProviderRef provider) {
        return connection.query(
                "provider/probe", new ProviderProfileRpcContracts.ProviderProbePayload(provider), ProviderStatus.class);
    }

    /**
     * 执行一次可能计费的最小模型 round-trip。
     *
     * @param provider 已保存的精确 Provider 与模型
     * @param purpose 本次要验证的模型用途
     * @param billingConfirmed 调用方显式确认标记，必须为 true
     * @param confirmation 固定危险确认文本
     * @param options 幂等键，expected revision 必须匹配 Provider
     * @return 不含 Prompt 或模型正文的脱敏结果
     */
    public ProviderVerificationResult verifyRoundTrip(
            ProviderRef provider,
            ProviderModelPurpose purpose,
            boolean billingConfirmed,
            String confirmation,
            CommandOptions options) {
        requireProviderRevision(provider.endpointRevision(), options);
        return connection.command(
                ProviderVerificationRpcContracts.METHOD,
                new ProviderVerificationRpcContracts.VerifyPayload(provider, purpose, billingConfirmed, confirmation),
                options,
                ProviderVerificationResult.class);
    }

    private static void requireProviderRevision(long providerRevision, CommandOptions options) {
        CommandOptions checked = Objects.requireNonNull(options, "options");
        if (providerRevision < 1 || checked.expectedRevision() != providerRevision) {
            throw new IllegalArgumentException("Provider expected revision does not match command options");
        }
    }
}
