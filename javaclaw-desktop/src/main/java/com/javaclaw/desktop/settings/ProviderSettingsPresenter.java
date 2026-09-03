package com.javaclaw.desktop.settings;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.ProviderCredentialBinding;
import com.javaclaw.api.ProviderCredentialClearResult;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ProviderStatus;
import com.javaclaw.client.CommandOptions;

/** Provider 设置页的异步状态机；不持有 JavaFX 控件。 */
public final class ProviderSettingsPresenter {
    private final CoreSettingsGateway gateway;
    private final Supplier<String> providerIds;
    private Consumer<ProviderSettingsState> listener = ignored -> {};
    private ProviderSettingsState state = ProviderSettingsState.initial();

    /**
     * 创建 Presenter。
     *
     * @param gateway SDK 异步边界
     */
    public ProviderSettingsPresenter(CoreSettingsGateway gateway) {
        this(gateway, () -> "provider-" + UUID.randomUUID());
    }

    ProviderSettingsPresenter(CoreSettingsGateway gateway, Supplier<String> providerIds) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.providerIds = Objects.requireNonNull(providerIds, "providerIds");
    }

    /**
     * 订阅完整状态并立即收到当前快照。
     *
     * @param value 页面渲染回调
     */
    public void subscribe(Consumer<ProviderSettingsState> value) {
        listener = Objects.requireNonNull(value, "listener");
        listener.accept(state);
    }

    /** 异步重新读取 Provider 目录；旧响应会按 epoch 丢弃。 */
    public void reload() {
        if (state.dirty() && state.phase() != SettingsLoadState.ERROR) {
            warnUnsavedChanges();
            return;
        }
        long epoch = state.epoch() + 1;
        publish(new ProviderSettingsState(
                SettingsLoadState.LOADING,
                state.providers(),
                state.selected(),
                state.baseline(),
                state.draft(),
                state.credential(),
                state.providerStatus(),
                "正在读取模型服务…",
                false,
                epoch));
        gateway.providers().whenComplete((providers, failure) -> completeReload(epoch, providers, failure));
    }

    /**
     * 选择权威 Provider；脏草稿存在时拒绝切换。
     *
     * @param endpoint 目录项
     */
    public void select(ProviderEndpoint endpoint) {
        ProviderEndpoint checked = Objects.requireNonNull(endpoint, "endpoint");
        if (state.dirty()) {
            warnUnsavedChanges();
            return;
        }
        ProviderDraft draft = ProviderDraft.from(checked);
        long epoch = state.epoch() + 1;
        publish(new ProviderSettingsState(
                SettingsLoadState.READY,
                state.providers(),
                Optional.of(checked),
                draft,
                draft,
                Optional.empty(),
                Optional.empty(),
                "",
                false,
                epoch));
        checked.spec()
                .credential()
                .ifPresentOrElse(
                        reference -> loadCredential(epoch, reference),
                        () -> publishCredential(epoch, Optional.empty(), ""));
    }

    /** 开始填写一个新 Provider。 */
    public void createDraft() {
        if (state.dirty()) {
            warnUnsavedChanges();
            return;
        }
        ProviderDraft empty = ProviderDraft.forNew(requireId(providerIds.get()));
        publish(new ProviderSettingsState(
                SettingsLoadState.READY,
                state.providers(),
                Optional.empty(),
                empty,
                empty,
                Optional.empty(),
                Optional.empty(),
                "填写新模型服务配置",
                false,
                state.epoch() + 1));
    }

    /**
     * 替换当前表单草稿。
     *
     * @param draft 页面控件投影
     */
    public void updateDraft(ProviderDraft draft) {
        publish(new ProviderSettingsState(
                SettingsLoadState.READY,
                state.providers(),
                state.selected(),
                state.baseline(),
                draft,
                state.credential(),
                state.providerStatus(),
                state.message(),
                false,
                state.epoch()));
    }

    /** 保存新建或更新 Provider。 */
    public void save() {
        ProviderEndpointSpec requested;
        try {
            requested = state.draft().toSpec();
            requireId(state.draft().id());
        } catch (RuntimeException invalid) {
            failLocal(invalid);
            return;
        }
        long expected = state.selected().map(ProviderEndpoint::revision).orElse(0L);
        publishSaving("正在保存模型服务…");
        if (state.selected().isEmpty()) {
            ProviderEndpointSpec shell = ProviderSetupCommands.connectionShell(requested);
            gateway.createProvider(
                            state.draft().id(),
                            shell,
                            ProviderLifecycle.DISABLED,
                            ProviderSetupCommands.createShell(state.draft().id(), shell))
                    .whenComplete(this::completeWrite);
            return;
        }
        CommandOptions options =
                switch (state.setupPhase()) {
                    case MODELS, ENABLE ->
                        ProviderSetupCommands.finish(
                                state.draft().id(),
                                expected,
                                requested,
                                state.draft().lifecycle());
                    default -> CommandOptions.create(expected);
                };
        gateway.updateProvider(state.draft().id(), requested, state.draft().lifecycle(), options)
                .whenComplete(this::completeWrite);
    }

    /** 归档当前 Provider；归档后不再供新 Profile 使用。 */
    public void archive() {
        ProviderEndpoint selected = requireSelected();
        if (state.dirty()) {
            warnUnsavedChanges();
            return;
        }
        publishSaving("正在归档模型服务…");
        gateway.archiveProvider(selected.id(), CommandOptions.create(selected.revision()))
                .whenComplete(this::completeWrite);
    }

    /** 执行本地配置探测；不会发起模型 round-trip。 */
    public void probe() {
        ProviderEndpoint selected = requireSelected();
        String model = selected.spec().models().stream()
                .map(ProviderModelSpec::modelId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("请先保存至少一个模型"));
        publishSaving("正在执行非计费本地检查…");
        gateway.probeProvider(new ProviderRef(selected.id(), selected.revision(), model))
                .whenComplete(this::completeProbe);
    }

    /**
     * 写入或轮换当前 Provider 的 Secret。
     *
     * @param secret PasswordField 临时字符；无论提交与否，本方法返回前都会清零调用方数组
     */
    public void replaceSecret(char[] secret) {
        try {
            ProviderEndpoint selected = requireSelected();
            if (state.dirty()) {
                warnUnsavedChanges();
                return;
            }
            if (secret == null || secret.length == 0) {
                failLocal(new IllegalArgumentException("密钥不能为空"));
                return;
            }
            long credentialRevision = selected.spec().credential().isPresent()
                    ? state.credential()
                            .orElseThrow(() -> new IllegalStateException("密钥元数据尚未读取"))
                            .revision()
                    : 0;
            publishSaving(credentialRevision == 0 ? "正在创建并绑定密钥…" : "正在原子轮换密钥…");
            CommandOptions options = state.setupPhase() == ProviderSetupPhase.CREDENTIAL
                    ? ProviderSetupCommands.configureCredential(selected.id(), selected.revision(), credentialRevision)
                    : CommandOptions.create(selected.revision());
            gateway.setProviderCredential(selected, credentialRevision, secret, options)
                    .whenComplete(this::completeCredentialBinding);
        } finally {
            if (secret != null) {
                Arrays.fill(secret, '\0');
            }
        }
    }

    /** 清除当前 Provider 的 Secret 引用和值。 */
    public void clearSecret() {
        ProviderEndpoint selected = requireSelected();
        selected.spec().credential().orElseThrow(() -> new IllegalStateException("模型服务尚未配置密钥"));
        CredentialMetadata metadata = state.credential().orElseThrow(() -> new IllegalStateException("密钥元数据尚未读取"));
        if (state.dirty()) {
            warnUnsavedChanges();
            return;
        }
        publishSaving("正在解除模型服务引用并清除密钥…");
        gateway.clearProviderCredential(selected, metadata, CommandOptions.create(selected.revision()))
                .whenComplete(this::completeCredentialClear);
    }

    /** 丢弃表单草稿。 */
    public void discardDraft() {
        publish(new ProviderSettingsState(
                SettingsLoadState.READY,
                state.providers(),
                state.selected(),
                state.baseline(),
                state.baseline(),
                state.credential(),
                state.providerStatus(),
                "本地草稿已丢弃",
                false,
                state.epoch()));
    }

    /** 显示统一离页保护说明。 */
    public void warnUnsavedChanges() {
        publish(new ProviderSettingsState(
                state.phase(),
                state.providers(),
                state.selected(),
                state.baseline(),
                state.draft(),
                state.credential(),
                state.providerStatus(),
                "请先保存或放弃模型服务草稿",
                false,
                state.epoch()));
    }

    /** @return 当前不可变状态 */
    public ProviderSettingsState state() {
        return state;
    }

    private void completeReload(long epoch, List<ProviderEndpoint> providers, Throwable failure) {
        if (epoch != state.epoch()) {
            return;
        }
        if (failure != null) {
            publish(new ProviderSettingsState(
                    SettingsLoadState.ERROR,
                    state.providers(),
                    state.selected(),
                    state.baseline(),
                    state.draft(),
                    state.credential(),
                    state.providerStatus(),
                    SettingsFailures.message(failure),
                    false,
                    epoch));
            return;
        }
        List<ProviderEndpoint> catalog = List.copyOf(providers);
        Optional<ProviderEndpoint> selected = retainedSelection(catalog);
        if (hasIdentityCollision(selected)) {
            ProviderEndpoint existing = selected.orElseThrow();
            publish(new ProviderSettingsState(
                    SettingsLoadState.READY,
                    catalog,
                    Optional.of(existing),
                    ProviderDraft.from(existing),
                    state.draft(),
                    Optional.empty(),
                    Optional.empty(),
                    "模型服务标识已存在且配置不同；未覆盖权威版本",
                    true,
                    epoch));
            return;
        }
        ProviderDraft draft = selected.map(ProviderDraft::from).orElseGet(this::emptyCatalogDraft);
        publish(new ProviderSettingsState(
                SettingsLoadState.READY,
                catalog,
                selected,
                draft,
                draft,
                Optional.empty(),
                Optional.empty(),
                reloadMessage(catalog, selected),
                false,
                epoch));
        selected.flatMap(endpoint -> endpoint.spec().credential())
                .ifPresent(reference -> loadCredential(epoch, reference));
    }

    private Optional<ProviderEndpoint> retainedSelection(List<ProviderEndpoint> catalog) {
        String target = state.selected()
                .map(ProviderEndpoint::id)
                .orElseGet(() -> state.draft().id().strip());
        if (!target.isEmpty()) {
            Optional<ProviderEndpoint> retained = catalog.stream()
                    .filter(candidate -> candidate.id().equals(target))
                    .findFirst();
            if (retained.isPresent()) {
                return retained;
            }
        }
        return catalog.stream().findFirst();
    }

    private ProviderDraft emptyCatalogDraft() {
        String currentId = state.draft().id().strip();
        String stableId = currentId.isEmpty() ? requireId(providerIds.get()) : requireId(currentId);
        return ProviderDraft.forNew(stableId);
    }

    private boolean hasIdentityCollision(Optional<ProviderEndpoint> selected) {
        if (state.selected().isPresent() || state.draft().id().isBlank() || selected.isEmpty()) {
            return false;
        }
        ProviderEndpoint existing = selected.orElseThrow();
        if (!existing.id().equals(state.draft().id())) {
            return false;
        }
        try {
            ProviderEndpointSpec shell =
                    ProviderSetupCommands.connectionShell(state.draft().toSpec());
            return existing.lifecycle() != ProviderLifecycle.DISABLED
                    || !existing.spec().equals(shell);
        } catch (RuntimeException invalidDraft) {
            return true;
        }
    }

    private String reloadMessage(List<ProviderEndpoint> catalog, Optional<ProviderEndpoint> selected) {
        if (catalog.isEmpty()) {
            return "尚未配置模型服务";
        }
        if (state.selected().isEmpty()
                && !state.draft().id().isBlank()
                && selected.map(ProviderEndpoint::id)
                        .filter(state.draft().id()::equals)
                        .isPresent()) {
            return "已从服务端恢复首次配置进度";
        }
        return "";
    }

    private void loadCredential(long epoch, CredentialRef reference) {
        gateway.credential(reference).whenComplete((metadata, failure) -> {
            if (failure != null) {
                publishCredential(epoch, Optional.empty(), "密钥元数据读取失败：" + SettingsFailures.message(failure));
            } else {
                publishCredential(epoch, metadata, metadata.isEmpty() ? "CredentialRef 已失效" : "");
            }
        });
    }

    private void publishCredential(long epoch, Optional<CredentialMetadata> metadata, String message) {
        if (epoch != state.epoch()) {
            return;
        }
        publish(new ProviderSettingsState(
                state.phase(),
                state.providers(),
                state.selected(),
                state.baseline(),
                state.draft(),
                metadata,
                state.providerStatus(),
                message,
                false,
                epoch));
    }

    private void completeWrite(ProviderEndpoint endpoint, Throwable failure) {
        if (failure != null) {
            publishFailure(failure);
            return;
        }
        List<ProviderEndpoint> catalog = replace(state.providers(), endpoint);
        ProviderDraft draft = ProviderDraft.from(endpoint);
        publish(new ProviderSettingsState(
                SettingsLoadState.READY,
                catalog,
                Optional.of(endpoint),
                draft,
                draft,
                state.credential(),
                Optional.empty(),
                endpoint.spec().models().isEmpty() ? "连接壳已保存；请继续配置凭据和模型目录" : "模型服务已保存为版本 " + endpoint.revision(),
                false,
                state.epoch()));
        endpoint.spec().credential().ifPresent(reference -> loadCredential(state.epoch(), reference));
    }

    private void completeProbe(ProviderStatus status, Throwable failure) {
        if (failure != null) {
            publishFailure(failure);
            return;
        }
        publish(new ProviderSettingsState(
                SettingsLoadState.READY,
                state.providers(),
                state.selected(),
                state.baseline(),
                state.draft(),
                state.credential(),
                Optional.of(status),
                status.detail().orElse(status.readiness().name()),
                false,
                state.epoch()));
    }

    private void completeCredentialBinding(ProviderCredentialBinding binding, Throwable failure) {
        if (failure != null) {
            publishFailure(failure);
            return;
        }
        List<ProviderEndpoint> catalog = replace(state.providers(), binding.provider());
        ProviderDraft draft = ProviderDraft.from(binding.provider());
        publish(new ProviderSettingsState(
                SettingsLoadState.READY,
                catalog,
                Optional.of(binding.provider()),
                draft,
                draft,
                Optional.of(binding.credential()),
                Optional.empty(),
                "模型服务与密钥已原子提交",
                false,
                state.epoch()));
    }

    private void completeCredentialClear(ProviderCredentialClearResult result, Throwable failure) {
        if (failure != null) {
            publishFailure(failure);
            return;
        }
        List<ProviderEndpoint> catalog = replace(state.providers(), result.provider());
        ProviderDraft draft = ProviderDraft.from(result.provider());
        publish(new ProviderSettingsState(
                SettingsLoadState.READY,
                catalog,
                Optional.of(result.provider()),
                draft,
                draft,
                Optional.empty(),
                Optional.empty(),
                "模型服务引用与密钥已原子清除",
                false,
                state.epoch()));
    }

    private void publishSaving(String message) {
        publish(new ProviderSettingsState(
                SettingsLoadState.SAVING,
                state.providers(),
                state.selected(),
                state.baseline(),
                state.draft(),
                state.credential(),
                state.providerStatus(),
                message,
                false,
                state.epoch()));
    }

    private void failLocal(RuntimeException failure) {
        publish(new ProviderSettingsState(
                SettingsLoadState.ERROR,
                state.providers(),
                state.selected(),
                state.baseline(),
                state.draft(),
                state.credential(),
                state.providerStatus(),
                SettingsFailures.message(failure),
                false,
                state.epoch()));
    }

    private void publishFailure(Throwable failure) {
        publish(new ProviderSettingsState(
                SettingsLoadState.ERROR,
                state.providers(),
                state.selected(),
                state.baseline(),
                state.draft(),
                state.credential(),
                state.providerStatus(),
                SettingsFailures.message(failure),
                SettingsFailures.revisionConflict(failure),
                state.epoch()));
    }

    private ProviderEndpoint requireSelected() {
        return state.selected().orElseThrow(() -> new IllegalStateException("请先选择模型服务"));
    }

    private static String requireId(String value) {
        String normalized = Objects.requireNonNullElse(value, "").strip();
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("模型服务标识只能包含字母、数字、点、下划线和连字符");
        }
        return normalized;
    }

    private static List<ProviderEndpoint> replace(List<ProviderEndpoint> current, ProviderEndpoint updated) {
        java.util.ArrayList<ProviderEndpoint> result = new java.util.ArrayList<>(current);
        result.removeIf(provider -> provider.id().equals(updated.id()));
        result.add(updated);
        result.sort(java.util.Comparator.comparing(ProviderEndpoint::id));
        return List.copyOf(result);
    }

    private void publish(ProviderSettingsState next) {
        state = next;
        listener.accept(next);
    }
}
