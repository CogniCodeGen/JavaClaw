package com.javaclaw.desktop.settings;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.ProviderCredentialBinding;
import com.javaclaw.api.ProviderCredentialClearResult;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ProviderStatus;
import com.javaclaw.client.CommandOptions;

/** Provider 设置页的异步状态机；不持有 JavaFX 控件。 */
public final class ProviderSettingsPresenter {
    private final CoreSettingsGateway gateway;
    private Consumer<ProviderSettingsState> listener = ignored -> {};
    private ProviderSettingsState state = ProviderSettingsState.initial();

    /**
     * 创建 Presenter。
     *
     * @param gateway SDK 异步边界
     */
    public ProviderSettingsPresenter(CoreSettingsGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
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
        long epoch = state.epoch() + 1;
        publish(new ProviderSettingsState(
                SettingsLoadState.LOADING,
                state.providers(),
                state.selected(),
                state.baseline(),
                state.draft(),
                state.credential(),
                state.providerStatus(),
                "正在读取 Provider…",
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
        ProviderDraft empty = ProviderDraft.empty();
        publish(new ProviderSettingsState(
                SettingsLoadState.READY,
                state.providers(),
                Optional.empty(),
                empty,
                empty,
                Optional.empty(),
                Optional.empty(),
                "填写新 Provider 配置",
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
        ProviderEndpointSpec spec;
        try {
            spec = state.draft().toSpec();
            requireId(state.draft().id());
        } catch (RuntimeException invalid) {
            failLocal(invalid);
            return;
        }
        long expected = state.selected().map(ProviderEndpoint::revision).orElse(0L);
        publishSaving("正在保存 Provider…");
        if (state.selected().isEmpty()) {
            gateway.createProvider(state.draft().id(), spec, CommandOptions.create(0))
                    .whenComplete(this::completeWrite);
            return;
        }
        gateway.updateProvider(state.draft().id(), spec, state.draft().lifecycle(), CommandOptions.create(expected))
                .whenComplete(this::completeWrite);
    }

    /** 归档当前 Provider；归档后不再供新 Profile 使用。 */
    public void archive() {
        ProviderEndpoint selected = requireSelected();
        if (state.dirty()) {
            warnUnsavedChanges();
            return;
        }
        publishSaving("正在归档 Provider…");
        gateway.archiveProvider(selected.id(), CommandOptions.create(selected.revision()))
                .whenComplete(this::completeWrite);
    }

    /** 执行本地配置探测；不会发起模型 round-trip。 */
    public void probe() {
        ProviderEndpoint selected = requireSelected();
        String model = selected.spec().models().getFirst();
        publishSaving("正在执行非计费本地检查…");
        gateway.probeProvider(new ProviderRef(selected.id(), selected.revision(), model))
                .whenComplete(this::completeProbe);
    }

    /**
     * 写入或轮换当前 Provider 的 Secret。
     *
     * @param secret PasswordField 临时字符；网关会立即复制并负责清零副本
     */
    public void replaceSecret(char[] secret) {
        ProviderEndpoint selected = requireSelected();
        if (state.dirty()) {
            warnUnsavedChanges();
            return;
        }
        if (secret == null || secret.length == 0) {
            failLocal(new IllegalArgumentException("Secret 不能为空"));
            return;
        }
        long credentialRevision = selected.spec().credential().isPresent()
                ? state.credential()
                        .orElseThrow(() -> new IllegalStateException("Secret 元数据尚未读取"))
                        .revision()
                : 0;
        publishSaving(credentialRevision == 0 ? "正在创建并绑定 Secret…" : "正在原子轮换 Secret…");
        try {
            gateway.setProviderCredential(
                            selected, credentialRevision, secret, CommandOptions.create(selected.revision()))
                    .whenComplete(this::completeCredentialBinding);
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    /** 清除当前 Provider 的 Secret 引用和值。 */
    public void clearSecret() {
        ProviderEndpoint selected = requireSelected();
        selected.spec().credential().orElseThrow(() -> new IllegalStateException("Provider 尚未配置 Secret"));
        CredentialMetadata metadata = state.credential().orElseThrow(() -> new IllegalStateException("Secret 元数据尚未读取"));
        if (state.dirty()) {
            warnUnsavedChanges();
            return;
        }
        publishSaving("正在解除 Provider 引用并清除 Secret…");
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
                "请先保存或放弃 Provider 草稿",
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
        Optional<ProviderEndpoint> selected = catalog.stream().findFirst();
        ProviderDraft draft = selected.map(ProviderDraft::from).orElseGet(ProviderDraft::empty);
        publish(new ProviderSettingsState(
                SettingsLoadState.READY,
                catalog,
                selected,
                draft,
                draft,
                Optional.empty(),
                Optional.empty(),
                catalog.isEmpty() ? "尚未配置 Provider" : "",
                false,
                epoch));
        selected.flatMap(endpoint -> endpoint.spec().credential())
                .ifPresent(reference -> loadCredential(epoch, reference));
    }

    private void loadCredential(long epoch, CredentialRef reference) {
        gateway.credential(reference).whenComplete((metadata, failure) -> {
            if (failure != null) {
                publishCredential(epoch, Optional.empty(), "Secret 元数据读取失败：" + SettingsFailures.message(failure));
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
                "Provider 已保存为 v" + endpoint.revision(),
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
                "Provider 与 Secret 已原子提交",
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
                "Provider 引用与 Secret 已原子清除",
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
        return state.selected().orElseThrow(() -> new IllegalStateException("请先选择 Provider"));
    }

    private static String requireId(String value) {
        String normalized = Objects.requireNonNullElse(value, "").strip();
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("Provider ID 只能包含字母、数字、点、下划线和连字符");
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
