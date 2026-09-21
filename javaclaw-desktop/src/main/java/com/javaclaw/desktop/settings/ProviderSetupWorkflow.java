package com.javaclaw.desktop.settings;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderConfiguration;
import com.javaclaw.api.ProviderConfigurationResult;
import com.javaclaw.api.ProviderConfigurationSource;
import com.javaclaw.api.ProviderConnectionSpec;
import com.javaclaw.api.ProviderCredentialChange;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPreviewRequest;
import com.javaclaw.api.ProviderModelPreviewResult;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RemoteRpcException;
import com.javaclaw.client.facade.PreparedProviderConfiguration;
import com.javaclaw.desktop.DesktopNotificationSubscription;
import com.javaclaw.protocol.ProtocolErrorCode;

/**
 * 统一配置的临时草稿与一次原子保存；目录读取仅预览，不创建连接壳或轮换凭据。
 *
 * <p>调用与回调沿 CoreSettingsGateway 的 JavaFX 单线程约定串行执行。只有此工作流保留可清零的临时密钥。读取可取消且按代次拒绝迟到结果；写入结果不明时只查询同一密封请求，禁止再次密封和自动重放。
 */
final class ProviderSetupWorkflow implements AutoCloseable {
    private final CoreSettingsGateway gateway;
    private final String providerId;
    private final String draftId = UUID.randomUUID().toString();
    private final Optional<ProviderEndpoint> source;
    private final long credentialRevision;
    private DesktopNotificationSubscription sessionSubscription;
    private long sessionGeneration;
    private boolean sessionInvalidated;
    private Consumer<String> progress = ignored -> {};
    private ProviderConnectionSpec connection;
    private ProviderCredentialChange credentialChange;
    private ProviderEndpoint endpoint;
    private char[] secret = new char[0];
    private char[] pendingSecret = new char[0];
    private CancellationSource previewCancellation = new CancellationSource();
    private PreparedProviderConfiguration prepared;
    private boolean pending;
    private boolean previewing;
    private boolean unknown;
    private boolean closed;
    private ProviderConfigurationCapability capability = ProviderConfigurationCapability.CHECKING;
    private long generation;
    private String phase = "";

    ProviderSetupWorkflow(CoreSettingsGateway gateway) {
        this(gateway, "provider-" + UUID.randomUUID());
    }

    ProviderSetupWorkflow(CoreSettingsGateway gateway, String providerId) {
        this(gateway, providerId, Optional.empty(), 0);
    }

    ProviderSetupWorkflow(CoreSettingsGateway gateway, ProviderEndpoint endpoint, long credentialRevision) {
        this(gateway, endpoint.id(), Optional.of(endpoint), credentialRevision);
    }

    private ProviderSetupWorkflow(
            CoreSettingsGateway gateway,
            String providerId,
            Optional<ProviderEndpoint> source,
            long credentialRevision) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.source = source;
        this.credentialRevision = credentialRevision;
        source.ifPresent(value -> connection = ProviderConnectionSpec.from(value.spec()));
        observeSession();
    }

    private void observeSession() {
        long current = ++sessionGeneration;
        sessionSubscription = gateway.onProviderConfigurationSessionInvalidated(this::invalidateSession);
        gateway.providerConfigurationSupported().whenComplete((supported, failure) -> {
            if (!closed && current == sessionGeneration) {
                capability = sessionInvalidated
                        ? ProviderConfigurationCapability.DISCONNECTED
                        : failure != null
                                ? ProviderConfigurationCapability.FAILED
                                : Boolean.TRUE.equals(supported)
                                        ? ProviderConfigurationCapability.AVAILABLE
                                        : ProviderConfigurationCapability.UNSUPPORTED;
                report(supported() ? unknown ? "已重新连接，请查询原保存结果。" : "填写连接配置并选择模型，最后一次保存。" : capability.message());
            }
        });
    }

    ProviderConfigurationCapability capability() {
        return capability;
    }

    boolean hasPreparedSecret() {
        return secret.length > 0;
    }

    void connectionDestinationChanged() {
        cancelPreview();
        clearSecret();
    }

    CompletionStage<Void> reconnect() {
        if (pending || closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("当前操作尚未结束"));
        }
        capability = ProviderConfigurationCapability.CHECKING;
        report("正在重新连接 App Server…");
        return gateway.reconnect().handle((result, failure) -> {
            if (closed) {
                return null;
            }
            if (failure != null) {
                capability = ProviderConfigurationCapability.DISCONNECTED;
                report(capability.message());
            } else {
                // 重建观察者与能力状态；原密封请求保持不变，未知结果仍只查询原回执。
                sessionSubscription.close();
                sessionInvalidated = false;
                observeSession();
            }
            return null;
        });
    }

    void onProgress(Consumer<String> listener) {
        progress = Objects.requireNonNull(listener, "listener");
    }

    Optional<ProviderEndpoint> endpoint() {
        return Optional.ofNullable(endpoint);
    }

    String phase() {
        return phase;
    }

    boolean pending() {
        return pending;
    }

    boolean previewing() {
        return previewing;
    }

    boolean unknown() {
        return unknown;
    }

    boolean needsSecretInput() {
        return credentialChange == ProviderCredentialChange.REPLACE
                && secret.length == 0
                && !pending
                && !unknown
                && endpoint == null;
    }

    boolean supported() {
        return capability == ProviderConfigurationCapability.AVAILABLE;
    }

    boolean capabilityKnown() {
        return capability != ProviderConfigurationCapability.CHECKING;
    }

    CompletionStage<Void> connect(ProviderDraft draft, char[] entered) {
        return connect(draft, entered, false, false);
    }

    CompletionStage<Void> connect(ProviderDraft draft, char[] entered, boolean replace, boolean clearConfirmed) {
        Objects.requireNonNull(entered, "entered");
        try {
            requireEditable();
            ProviderConnectionSpec requested = ProviderConnectionSpec.from(
                    draft.withCredential(Optional.empty()).toSpec());
            if (connection != null && !sameCredentialDestination(connection, requested)) {
                clearSecret();
            }
            if (entered.length > 0) {
                clearSecret();
                secret = entered.clone();
            }
            credentialChange = credentialChange(requested, replace, clearConfirmed);
            connection = requested;
            cancelPreview();
            report("连接草稿已准备，尚未保存。");
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        } finally {
            Arrays.fill(entered, '\0');
        }
    }

    CompletionStage<ProviderModelPreviewResult> discover() {
        try {
            requireEditable();
            if (connection == null || credentialChange == null) {
                throw new IllegalStateException("请先填写连接配置");
            }
            cancelPreview();
            CancellationSource cancellation = previewCancellation;
            long current = generation;
            var request = new ProviderModelPreviewRequest(
                    draftId,
                    current,
                    connection,
                    source.map(
                            value -> new ProviderConfigurationSource(value.id(), value.revision(), credentialRevision)),
                    credentialChange);
            previewing = true;
            report("正在读取模型目录；仍可手动配置模型。");
            char[] copy = secret.clone();
            return preview(request, copy, cancellation).handle((result, failure) -> {
                Arrays.fill(copy, '\0');
                if (closed || generation != current || cancellation.isCancelled()) {
                    throw new java.util.concurrent.CancellationException("已丢弃旧目录预览");
                }
                previewing = false;
                if (failure != null) {
                    throw new java.util.concurrent.CompletionException(failure);
                }
                if (!draftId.equals(result.draftId()) || result.generation() != current) {
                    throw new IllegalStateException("模型目录预览不属于当前草稿");
                }
                return result;
            });
        } catch (RuntimeException failure) {
            previewing = false;
            return CompletableFuture.failedFuture(failure);
        }
    }

    CompletionStage<ProviderConfigurationResult> save(List<ProviderModelSpec> models, boolean enabled) {
        try {
            requireEditable();
            if (connection == null || credentialChange == null || models.isEmpty()) {
                throw new IllegalArgumentException("请完成连接配置并选择至少一个模型");
            }
            requireReplacementSecret();
            var configuration = new ProviderConfiguration(
                    providerId,
                    source.map(ProviderEndpoint::revision).orElse(0L),
                    connection,
                    List.copyOf(models),
                    enabled ? ProviderLifecycle.ACTIVE : ProviderLifecycle.DISABLED,
                    credentialChange,
                    credentialRevision);
            cancelPreview();
            pending = true;
            report("正在一次保存完整配置…");
            char[] submitted = secret.clone();
            pendingSecret = submitted;
            clearSecret();
            var ready = vaultReady(submitted);
            return ready.thenCompose(ignored -> {
                        if (closed || sessionInvalidated) {
                            throw new IllegalStateException("连接会话已失效，未提交配置");
                        }
                        return gateway.prepareProviderConfiguration(
                                configuration, submitted, CommandOptions.create(configuration.expectedRevision()));
                    })
                    .whenComplete((value, failure) -> {
                        Arrays.fill(submitted, '\0');
                        pendingSecret = new char[0];
                    })
                    .thenCompose(value -> {
                        if (closed || sessionInvalidated) {
                            throw new IllegalStateException("连接会话已失效，配置尚未提交");
                        }
                        prepared = value;
                        return gateway.saveProviderConfiguration(value);
                    })
                    .handle((result, failure) -> saved(result, failure));
        } catch (RuntimeException failure) {
            clearSecret();
            pending = false;
            return CompletableFuture.failedFuture(failure);
        }
    }

    private CompletionStage<ProviderModelPreviewResult> preview(
            ProviderModelPreviewRequest request, char[] copy, CancellationSource cancellation) {
        try {
            return gateway.previewProviderModels(request, copy, cancellation);
        } catch (RuntimeException failure) {
            Arrays.fill(copy, '\0');
            throw failure;
        }
    }

    private CompletionStage<Void> vaultReady(char[] submitted) {
        try {
            return credentialChange == ProviderCredentialChange.REPLACE
                    ? ProviderVaultReadiness.ensureReady(gateway)
                    : CompletableFuture.completedFuture(null);
        } catch (RuntimeException failure) {
            Arrays.fill(submitted, '\0');
            throw failure;
        }
    }

    CompletionStage<Optional<ProviderConfigurationResult>> checkResult() {
        if (!unknown || prepared == null || pending || closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("没有待确认的配置保存"));
        }
        pending = true;
        report("正在查询原保存回执；不会再次提交。");
        return queryReceipt().handle((result, failure) -> {
            pending = false;
            if (closed) {
                return Optional.<ProviderConfigurationResult>empty();
            }
            if (failure != null || result.isEmpty()) {
                report("保存结果尚未确认，请继续查询原回执；草稿已锁定。");
                return Optional.<ProviderConfigurationResult>empty();
            }
            accept(result.orElseThrow());
            return result;
        });
    }

    private CompletionStage<Optional<ProviderConfigurationResult>> queryReceipt() {
        try {
            return gateway.providerConfigurationResult(prepared);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private void invalidateSession() {
        sessionInvalidated = true;
        capability = ProviderConfigurationCapability.DISCONNECTED;
        Arrays.fill(pendingSecret, '\0');
        cancelPreview();
        clearSecret();
        report("连接会话已失效，临时密钥已清除。填写内容已保留；已提交请求只能查询原回执。");
    }

    void cancelPreview() {
        previewCancellation.cancel("模型连接草稿已改变或读取已取消");
        previewCancellation = new CancellationSource();
        generation++;
        previewing = false;
    }

    private ProviderConfigurationResult saved(ProviderConfigurationResult result, Throwable failure) {
        pending = false;
        if (failure == null) {
            accept(result);
            return result;
        }
        unknown = prepared != null && !knownRejection(failure);
        if (!unknown) {
            prepared = null;
        }
        report(unknown ? "保存结果尚未确认，只能查询原回执。" : "保存未完成，配置草稿仍保留；替换密钥需重新输入。");
        throw new java.util.concurrent.CompletionException(failure);
    }

    private void accept(ProviderConfigurationResult result) {
        endpoint = result.provider();
        unknown = false;
        report("完整配置已保存。");
    }

    private ProviderCredentialChange credentialChange(
            ProviderConnectionSpec requested, boolean replace, boolean clearConfirmed) {
        boolean bound = source.flatMap(value -> value.spec().credential()).isPresent();
        if (requested.authentication() == ProviderAuthentication.NONE) {
            if (bound && !clearConfirmed) {
                throw new IllegalArgumentException("切换无鉴权将清除已有密钥，请先明确确认");
            }
            clearSecret();
            return bound ? ProviderCredentialChange.CLEAR : ProviderCredentialChange.KEEP;
        }
        boolean changed = source.map(
                        value -> !sameCredentialDestination(ProviderConnectionSpec.from(value.spec()), requested))
                .orElse(false);
        if (secret.length > 0) {
            return ProviderCredentialChange.REPLACE;
        }
        if (bound && !replace && !changed) {
            if (credentialRevision < 1) {
                throw new IllegalStateException("已有密钥元数据尚未读取，请刷新后重试");
            }
            return ProviderCredentialChange.KEEP;
        }
        throw new IllegalArgumentException(changed ? "更改地址或协议后必须输入替换密钥" : "请输入 API Key，或明确选择无鉴权");
    }

    private void requireEditable() {
        if (closed || pending || unknown) {
            throw new IllegalStateException("保存期间或结果未确认时不能更改配置");
        }
        if (!supported()) {
            throw new IllegalStateException(capability.message());
        }
    }

    private void requireReplacementSecret() {
        if (credentialChange == ProviderCredentialChange.REPLACE && secret.length == 0) {
            throw new IllegalArgumentException("请重新输入 API Key");
        }
    }

    private static boolean sameCredentialDestination(ProviderConnectionSpec first, ProviderConnectionSpec second) {
        return first.adapter() == second.adapter()
                && first.baseUri().equals(second.baseUri())
                && first.authentication() == second.authentication();
    }

    private static boolean knownRejection(Throwable failure) {
        Throwable cause = SettingsFailures.unwrap(failure);
        return cause instanceof RemoteRpcException remote && remote.code() != ProtocolErrorCode.INTERNAL_ERROR;
    }

    private void report(String value) {
        phase = value;
        progress.accept(value);
    }

    private void clearSecret() {
        Arrays.fill(secret, '\0');
        secret = new char[0];
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        // 页面销毁只释放临时输入和读取，不撤销已发出的写请求，也不清除原密文提交身份。
        closed = true;
        sessionSubscription.close();
        Arrays.fill(pendingSecret, '\0');
        cancelPreview();
        clearSecret();
    }
}
