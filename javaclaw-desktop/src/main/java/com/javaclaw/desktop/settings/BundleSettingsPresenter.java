package com.javaclaw.desktop.settings;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import com.javaclaw.protocol.BundleRpcContracts;

/** 第三方 Bundle staging、审阅、生命周期与卸载的异步状态机。 */
public final class BundleSettingsPresenter {
    private final BundleSettingsGateway gateway;
    private Consumer<BundleSettingsState> listener = ignored -> {};
    private BundleSettingsState state = BundleSettingsState.initial();

    /** @param gateway 强类型 SDK 设置边界 */
    public BundleSettingsPresenter(BundleSettingsGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    /** @param value 完整状态订阅者；注册后立即收到当前快照 */
    public void subscribe(Consumer<BundleSettingsState> value) {
        listener = Objects.requireNonNull(value, "listener");
        listener.accept(state);
    }

    /** 读取已安装 Bundle；未提交 staging 不会被静默覆盖。 */
    public void reload() {
        if (state.dirty()) {
            warnUnsavedChanges();
            return;
        }
        loadCatalog(Optional.empty(), "正在读取第三方 Bundle…", "");
    }

    /** @param bundle 选择的 Bundle */
    public void select(BundleRpcContracts.Bundle bundle) {
        if (state.dirty()) {
            warnUnsavedChanges();
            return;
        }
        publish(copy(state.phase(), Optional.ofNullable(bundle), state.staging(), "", false, state.epoch()));
    }

    /** @param archive 用户选择的本机 Bundle 文件 */
    public void stage(Path archive) {
        long epoch = begin("正在上传 Attachment、验签并生成权限审阅…");
        gateway.stageBundle(archive).whenComplete((staging, failure) -> completeStage(epoch, staging, failure));
    }

    /** 安装当前 staging。 */
    public void install() {
        BundleRpcContracts.StageResult staging = state.staging().orElseThrow();
        long epoch = begin("正在安装已确认 Bundle…");
        completeBundleWrite(epoch, gateway.installBundle(staging), staging.extensionId(), "Bundle 已安装", true);
    }

    /** 将当前 staging 原子升级到所选 Bundle。 */
    public void upgrade() {
        BundleRpcContracts.StageResult staging = state.staging().orElseThrow();
        BundleRpcContracts.Bundle current = state.selected().orElseThrow();
        long epoch = begin("正在健康检查并原子升级 Bundle…");
        completeBundleWrite(epoch, gateway.upgradeBundle(staging, current), current.id(), "Bundle 已原子升级", true);
    }

    /** 探测所选 Bundle 健康状态。 */
    public void probe() {
        BundleRpcContracts.Bundle current = requireCleanSelection();
        long epoch = begin("正在隔离 Worker 中执行健康检查…");
        completeBundleWrite(epoch, gateway.probeBundle(current), current.id(), "健康状态已刷新", false);
    }

    /** 实时启用或停用所选 Bundle。 */
    public void toggleEnabled() {
        BundleRpcContracts.Bundle current = requireCleanSelection();
        boolean enabled = !"ENABLED".equals(current.state());
        long epoch = begin(enabled ? "正在启用 Bundle…" : "正在停用 Bundle…");
        completeBundleWrite(
                epoch,
                gateway.setBundleEnabled(current, enabled),
                current.id(),
                enabled ? "Bundle 已启用" : "Bundle 已停用",
                false);
    }

    /** 将所选 Bundle 移入可恢复 Trash。 */
    public void uninstall() {
        BundleRpcContracts.Bundle current = requireCleanSelection();
        long epoch = begin("正在将 Bundle 移入 Trash…");
        gateway.uninstallBundle(current).whenComplete((entry, failure) -> completeUninstall(epoch, entry, failure));
    }

    /** 丢弃本地 staging 审阅。 */
    public void discardDraft() {
        publish(copy(SettingsLoadState.READY, state.selected(), Optional.empty(), "staging 已丢弃", false, state.epoch()));
    }

    /** 显示统一离页保护消息。 */
    public void warnUnsavedChanges() {
        publish(copy(state.phase(), state.selected(), state.staging(), "请先安装、升级或丢弃 staging", false, state.epoch()));
    }

    /** @return 当前不可变状态 */
    public BundleSettingsState state() {
        return state;
    }

    private void loadCatalog(Optional<String> selectedId, String progress, String resultMessage) {
        long epoch = nextEpoch();
        publish(copy(SettingsLoadState.LOADING, state.selected(), Optional.empty(), progress, false, epoch));
        gateway.bundles()
                .whenComplete(
                        (bundles, failure) -> completeCatalog(epoch, selectedId, resultMessage, bundles, failure));
    }

    private void completeCatalog(
            long epoch,
            Optional<String> selectedId,
            String resultMessage,
            List<BundleRpcContracts.Bundle> bundles,
            Throwable failure) {
        if (stale(epoch)) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure);
            return;
        }
        List<BundleRpcContracts.Bundle> sorted = bundles.stream()
                .sorted(Comparator.comparing(BundleRpcContracts.Bundle::displayName))
                .toList();
        Optional<BundleRpcContracts.Bundle> selected = selectedId
                .flatMap(id ->
                        sorted.stream().filter(value -> value.id().equals(id)).findFirst())
                .or(() -> sorted.stream().findFirst());
        publish(new BundleSettingsState(
                SettingsLoadState.READY,
                sorted,
                selected,
                Optional.empty(),
                resultMessage.isBlank() && sorted.isEmpty() ? "暂无第三方 Bundle" : resultMessage,
                false,
                epoch));
    }

    private void completeStage(long epoch, BundleRpcContracts.StageResult staging, Throwable failure) {
        if (stale(epoch)) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure);
            return;
        }
        Optional<BundleRpcContracts.Bundle> current = state.bundles().stream()
                .filter(bundle -> bundle.id().equals(staging.extensionId()))
                .findFirst();
        publish(copy(SettingsLoadState.READY, current, Optional.of(staging), "请核对签名、摘要和权限后安装或升级", false, epoch));
    }

    private void completeBundleWrite(
            long epoch,
            CompletionStage<BundleRpcContracts.Bundle> operation,
            String id,
            String success,
            boolean clearsDraft) {
        operation.whenComplete((bundle, failure) -> {
            if (stale(epoch)) {
                return;
            }
            if (failure != null) {
                fail(epoch, failure);
                return;
            }
            if (clearsDraft) {
                publish(copy(SettingsLoadState.READY, Optional.of(bundle), Optional.empty(), success, false, epoch));
            }
            loadCatalog(Optional.of(id), "正在刷新第三方 Bundle…", success);
        });
    }

    private void completeUninstall(long epoch, BundleRpcContracts.TrashEntry entry, Throwable failure) {
        if (stale(epoch)) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure);
            return;
        }
        loadCatalog(Optional.empty(), "正在刷新第三方 Bundle…", "Bundle 已移入 Trash：" + entry.trashId());
    }

    private BundleRpcContracts.Bundle requireCleanSelection() {
        if (state.dirty()) {
            throw new IllegalStateException("staging 未处理，不能执行生命周期动作");
        }
        return state.selected().orElseThrow();
    }

    private long begin(String message) {
        long epoch = nextEpoch();
        publish(copy(SettingsLoadState.SAVING, state.selected(), state.staging(), message, false, epoch));
        return epoch;
    }

    private void fail(long epoch, Throwable failure) {
        publish(copy(
                SettingsLoadState.ERROR,
                state.selected(),
                state.staging(),
                SettingsFailures.message(failure),
                SettingsFailures.revisionConflict(failure),
                epoch));
    }

    private BundleSettingsState copy(
            SettingsLoadState phase,
            Optional<BundleRpcContracts.Bundle> selected,
            Optional<BundleRpcContracts.StageResult> staging,
            String message,
            boolean conflict,
            long epoch) {
        return new BundleSettingsState(phase, state.bundles(), selected, staging, message, conflict, epoch);
    }

    private long nextEpoch() {
        return Math.incrementExact(state.epoch());
    }

    private boolean stale(long epoch) {
        return epoch != state.epoch();
    }

    private void publish(BundleSettingsState value) {
        state = Objects.requireNonNull(value, "value");
        listener.accept(state);
    }
}
