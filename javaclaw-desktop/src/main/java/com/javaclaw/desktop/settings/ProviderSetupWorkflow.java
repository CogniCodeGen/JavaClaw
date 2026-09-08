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
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelDiscoveryResult;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.api.ProviderRef;

/**
 * 两步配置的可恢复 SDK 工作流。每个完成阶段立即保存权威版本，重试不会重复绑定密钥或升级已保存模型。
 *
 * <p>调用和完成回调均在 JavaFX 调度器；只取消目录读取，已发出的写操作必须完成，关闭窗口不得中断其状态确认。
 */
final class ProviderSetupWorkflow {
    private final CoreSettingsGateway gateway;
    private final String providerId;
    private final CancellationSource cancellation = new CancellationSource();
    private Consumer<String> progress = ignored -> {};
    private ProviderEndpoint endpoint;
    private boolean pending;
    private String phase = "";

    ProviderSetupWorkflow(CoreSettingsGateway gateway) {
        this(gateway, "provider-" + UUID.randomUUID());
    }

    ProviderSetupWorkflow(CoreSettingsGateway gateway, String providerId) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.providerId = Objects.requireNonNull(providerId, "providerId");
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

    CompletionStage<Void> connect(ProviderDraft draft, char[] secret) {
        char[] temporary = Objects.requireNonNull(secret, "secret").clone();
        Arrays.fill(secret, '\0');
        if (pending) {
            Arrays.fill(temporary, '\0');
            return CompletableFuture.failedFuture(new IllegalStateException("请等待当前配置步骤完成"));
        }
        try {
            ProviderEndpointSpec requested = draft.toSpec();
            requireSecret(requested, temporary);
            pending = true;
            return ensureConnection(requested)
                    .thenCompose(ignored -> ensureCredential(temporary))
                    .whenComplete((ignored, failure) -> {
                        Arrays.fill(temporary, '\0');
                        pending = false;
                    });
        } catch (RuntimeException invalid) {
            Arrays.fill(temporary, '\0');
            pending = false;
            return CompletableFuture.failedFuture(invalid);
        }
    }

    CompletionStage<ProviderModelDiscoveryResult> discover() {
        if (pending || endpoint == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("请先完成连接服务"));
        }
        pending = true;
        report("正在获取模型列表…");
        return gateway.discoverProviderModels(endpoint.id(), endpoint.revision(), cancellation)
                .whenComplete((result, failure) -> pending = false);
    }

    CompletionStage<ProviderRef> save(
            List<ProviderModelSpec> models, String currentModel, boolean use, ProviderSetupTarget target) {
        if (pending || endpoint == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("请先完成连接服务"));
        }
        if (models.isEmpty()
                || models.stream().noneMatch(model -> model.modelId().equals(currentModel))) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("请选择至少一个模型，并指定当前使用的模型"));
        }
        pending = true;
        return saveModels(models)
                .thenCompose(saved -> {
                    ProviderRef reference = new ProviderRef(saved.id(), saved.revision(), currentModel);
                    return use ? apply(reference, target) : CompletableFuture.completedFuture(reference);
                })
                .whenComplete((result, failure) -> pending = false);
    }

    void close() {
        cancellation.cancel("模型配置窗口已关闭");
    }

    private CompletionStage<ProviderEndpoint> ensureConnection(ProviderEndpointSpec requested) {
        if (endpoint != null) {
            return CompletableFuture.completedFuture(endpoint);
        }
        report("正在保存连接…");
        ProviderEndpointSpec shell = ProviderSetupCommands.connectionShell(requested);
        return gateway.createProvider(
                        providerId,
                        shell,
                        ProviderLifecycle.DISABLED,
                        ProviderSetupCommands.createShell(providerId, shell))
                .thenApply(saved -> {
                    endpoint = saved;
                    return saved;
                });
    }

    private CompletionStage<Void> ensureCredential(char[] secret) {
        if (endpoint.spec().authentication() == ProviderAuthentication.NONE
                || endpoint.spec().credential().isPresent()) {
            return CompletableFuture.completedFuture(null);
        }
        report("正在保存 API Key…");
        return gateway.setProviderCredential(
                        endpoint,
                        0,
                        secret,
                        ProviderSetupCommands.configureCredential(endpoint.id(), endpoint.revision(), 0))
                .thenAccept(binding -> endpoint = binding.provider());
    }

    private CompletionStage<ProviderEndpoint> saveModels(List<ProviderModelSpec> models) {
        if (endpoint.lifecycle() == ProviderLifecycle.ACTIVE
                && endpoint.spec().models().equals(models)) {
            return CompletableFuture.completedFuture(endpoint);
        }
        report("正在保存并启用模型…");
        ProviderEndpointSpec spec =
                ProviderDraft.from(endpoint).withModels(models).toSpec();
        return gateway.updateProvider(
                        endpoint.id(),
                        spec,
                        ProviderLifecycle.ACTIVE,
                        ProviderSetupCommands.finish(
                                endpoint.id(), endpoint.revision(), spec, ProviderLifecycle.ACTIVE))
                .thenApply(saved -> {
                    endpoint = saved;
                    return saved;
                });
    }

    private CompletionStage<ProviderRef> apply(ProviderRef reference, ProviderSetupTarget target) {
        report("模型已保存，正在应用到聊天…");
        if (target.workspaceId().isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("模型已保存，请选择或创建工作区后继续使用"));
        }
        return gateway.useModel(target.workspaceId(), target.threadId(), reference)
                .thenApply(ignored -> reference);
    }

    private void requireSecret(ProviderEndpointSpec spec, char[] secret) {
        boolean bound = endpoint != null && endpoint.spec().credential().isPresent();
        if (spec.authentication() == ProviderAuthentication.API_KEY && !bound && secret.length == 0) {
            throw new IllegalArgumentException("请输入 API Key；本地无鉴权服务可在高级设置中选择无鉴权");
        }
    }

    private void report(String value) {
        phase = value;
        progress.accept(value);
    }
}
