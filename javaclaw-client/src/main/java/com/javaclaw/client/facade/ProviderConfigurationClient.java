package com.javaclaw.client.facade;

import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.ProviderConfiguration;
import com.javaclaw.api.ProviderConfigurationResult;
import com.javaclaw.api.ProviderCredentialChange;
import com.javaclaw.api.ProviderModelDiscoveryOperationState;
import com.javaclaw.api.ProviderModelPreviewOperation;
import com.javaclaw.api.ProviderModelPreviewRequest;
import com.javaclaw.api.ProviderModelPreviewResult;
import com.javaclaw.api.TurnCancelledException;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.protocol.ProviderConfigurationRpcContracts;
import com.javaclaw.protocol.ProviderModelDiscoveryRpcContracts;
import com.javaclaw.protocol.SealedSecret;
import com.javaclaw.protocol.SessionKeyInfo;
import com.javaclaw.protocol.SessionSecretSealer;

/** Provider 草稿预览及完整配置提交；不修改通用传输或重试策略。 */
public final class ProviderConfigurationClient {
    private final RpcClientConnection connection;
    private final SessionKeyInfo sessionKey;

    /**
     * 绑定当前 SDK 会话。
     *
     * @param connection 已初始化连接
     * @param sessionKey 当前会话密封公钥
     */
    public ProviderConfigurationClient(RpcClientConnection connection, SessionKeyInfo sessionKey) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.sessionKey = Objects.requireNonNull(sessionKey, "sessionKey");
    }

    /**
     * 观察当前草稿所依赖的 SDK 会话失败；不更改连接或重试策略。
     *
     * @param listener 快速清理回调；调用方负责切回其 UI 调度器
     * @return 释放观察者的句柄
     */
    public AutoCloseable onSessionFailure(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        return connection.onFailure(ignored -> listener.run());
    }

    /**
     * 密封并冻结最终请求，不发起写入；无论成功失败都会清零输入秘密。
     *
     * @param configuration 完整非敏感配置
     * @param secret REPLACE 时的临时秘密，其它意图必须为空数组
     * @param options 一次逻辑保存身份
     * @return 必须用于提交及未知结果查询的同一冻结对象
     */
    public PreparedProviderConfiguration prepare(
            ProviderConfiguration configuration, char[] secret, CommandOptions options) {
        Objects.requireNonNull(secret, "secret");
        try {
            Objects.requireNonNull(configuration, "configuration");
            return new PreparedProviderConfiguration(
                    new ProviderConfigurationRpcContracts.SavePayload(
                            configuration,
                            seal(
                                    configuration.credentialChange(),
                                    secret,
                                    ProviderConfigurationRpcContracts.SAVE_PURPOSE)),
                    options);
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    /**
     * 只发送已冻结请求，不重新密封或自动重试。
     *
     * @param prepared 原逻辑保存
     * @return 权威已提交事实
     */
    public ProviderConfigurationResult save(PreparedProviderConfiguration prepared) {
        return connection.query(
                ProviderConfigurationRpcContracts.SAVE_METHOD,
                Objects.requireNonNull(prepared, "prepared").command(),
                ProviderConfigurationResult.class);
    }

    /**
     * 查询原提交回执；可在重新连接后查询原冻结对象。
     *
     * @param prepared 原逻辑保存
     * @return 已提交事实；为空表示仍未知，不能解释为保存失败
     */
    public Optional<ProviderConfigurationResult> result(PreparedProviderConfiguration prepared) {
        Objects.requireNonNull(prepared, "prepared");
        return connection
                .query(
                        ProviderConfigurationRpcContracts.RESULT_METHOD,
                        new ProviderConfigurationRpcContracts.ResultPayload(
                                prepared.options().idempotencyKey(),
                                prepared.options().expectedRevision(),
                                prepared.requestDigest()),
                        ProviderConfigurationRpcContracts.ResultResponse.class)
                .result();
    }

    /**
     * 读取草稿模型目录，不创建 Provider 或写入 Vault；输入秘密始终被清零。
     *
     * @param request 当前连接草稿代次
     * @param secret REPLACE 时的临时秘密，其它意图必须为空数组
     * @param cancellation 页面生命周期取消令牌
     * @return 绑定原草稿代次的有界目录
     */
    public ProviderModelPreviewResult preview(
            ProviderModelPreviewRequest request, char[] secret, CancellationToken cancellation) {
        Objects.requireNonNull(secret, "secret");
        ProviderConfigurationRpcContracts.PreviewPayload payload;
        try {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(cancellation, "cancellation").throwIfCancelled();
            payload = new ProviderConfigurationRpcContracts.PreviewPayload(
                    request,
                    seal(request.credentialChange(), secret, ProviderConfigurationRpcContracts.PREVIEW_PURPOSE));
        } finally {
            Arrays.fill(secret, '\0');
        }
        ProviderModelPreviewOperation operation = connection.command(
                ProviderConfigurationRpcContracts.PREVIEW_START_METHOD,
                payload,
                CommandOptions.create(request.generation()),
                ProviderModelPreviewOperation.class);
        return awaitPreview(operation, cancellation);
    }

    private ProviderModelPreviewResult awaitPreview(
            ProviderModelPreviewOperation initial, CancellationToken cancellation) {
        ProviderModelPreviewOperation operation = initial;
        while (!operation.terminal()) {
            if (cancellation.isCancelled()) {
                cancel(operation, ProviderModelDiscoveryRpcContracts.CLIENT_CANCELLED);
                cancellation.throwIfCancelled();
            }
            LockSupport.parkNanos(Duration.ofMillis(100).toNanos());
            if (Thread.interrupted()) {
                cancel(operation, ProviderModelDiscoveryRpcContracts.THREAD_INTERRUPTED);
                Thread.currentThread().interrupt();
                throw new TurnCancelledException("Provider preview interrupted");
            }
            if (!cancellation.isCancelled()) {
                operation = connection.query(
                        ProviderConfigurationRpcContracts.PREVIEW_READ_METHOD,
                        new ProviderModelDiscoveryRpcContracts.ReadPayload(operation.operationId()),
                        ProviderModelPreviewOperation.class);
            }
        }
        cancellation.throwIfCancelled();
        if (operation.state() == ProviderModelDiscoveryOperationState.SUCCEEDED) {
            return operation.result().orElseThrow();
        }
        if (operation.state() == ProviderModelDiscoveryOperationState.CANCELLED) {
            throw new TurnCancelledException("Provider preview cancelled");
        }
        throw new IllegalStateException(operation.failureCode().orElse("PREVIEW_FAILED"));
    }

    private void cancel(ProviderModelPreviewOperation operation, String reason) {
        try {
            connection.command(
                    ProviderConfigurationRpcContracts.PREVIEW_CANCEL_METHOD,
                    new ProviderModelDiscoveryRpcContracts.CancelPayload(operation.operationId(), reason),
                    CommandOptions.create(operation.revision()),
                    ProviderModelPreviewOperation.class);
        } catch (RuntimeException ignored) {
            // 连接失效时由服务端会话关闭回调释放预览、网络调用和临时秘密。
        }
    }

    private Optional<SealedSecret> seal(ProviderCredentialChange change, char[] secret, String purpose) {
        if (change == ProviderCredentialChange.REPLACE) {
            if (secret.length == 0) {
                throw new IllegalArgumentException("replacement secret must not be empty");
            }
            return Optional.of(SessionSecretSealer.seal(sessionKey, purpose, secret));
        }
        if (secret.length != 0) {
            throw new IllegalArgumentException("secret requires REPLACE credential intent");
        }
        return Optional.empty();
    }
}
