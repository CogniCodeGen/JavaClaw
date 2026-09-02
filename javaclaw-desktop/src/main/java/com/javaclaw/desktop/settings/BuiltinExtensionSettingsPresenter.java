package com.javaclaw.desktop.settings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import com.javaclaw.extension.spi.ExtensionAvailability;
import com.javaclaw.extension.spi.ExtensionState;
import com.javaclaw.protocol.BuiltinExtensionRpcContracts;

/** 内置能力页的异步状态机；不持有 JavaFX 控件。 */
public final class BuiltinExtensionSettingsPresenter {
    private final BuiltinExtensionSettingsGateway gateway;
    private Consumer<BuiltinExtensionSettingsState> listener = ignored -> {};
    private BuiltinExtensionSettingsState state = BuiltinExtensionSettingsState.initial();

    /** @param gateway 强类型 SDK 异步边界 */
    public BuiltinExtensionSettingsPresenter(BuiltinExtensionSettingsGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    /** @param consumer 完整状态订阅者；会立即收到当前快照 */
    public void subscribe(Consumer<BuiltinExtensionSettingsState> consumer) {
        listener = Objects.requireNonNull(consumer, "consumer");
        listener.accept(state);
    }

    /** 异步读取实时目录。 */
    public void reload() {
        long epoch = Math.incrementExact(state.epoch());
        publish(new BuiltinExtensionSettingsState(
                SettingsLoadState.LOADING, state.extensions(), state.selected(), "正在读取内置扩展状态…", false, epoch));
        gateway.builtinExtensions().whenComplete((extensions, failure) -> completeReload(epoch, extensions, failure));
    }

    /** @param selected 用户选择的权威状态 */
    public void select(BuiltinExtensionRpcContracts.Status selected) {
        if (state.pending()) {
            return;
        }
        publish(new BuiltinExtensionSettingsState(
                SettingsLoadState.READY,
                state.extensions(),
                Optional.of(Objects.requireNonNull(selected, "selected")),
                "",
                false,
                state.epoch()));
    }

    /** @param enabled 是否启用当前可选能力 */
    public void setEnabled(boolean enabled) {
        BuiltinExtensionRpcContracts.Status current = state.selected().orElseThrow();
        if (current.availability() != ExtensionAvailability.OPTIONAL) {
            fail(new IllegalStateException("必需内置能力只能查看，不能停用"));
            return;
        }
        if (!eligible(current.state())) {
            fail(new IllegalStateException("当前生命周期状态不允许启停"));
            return;
        }
        long epoch = Math.incrementExact(state.epoch());
        publish(new BuiltinExtensionSettingsState(
                SettingsLoadState.SAVING,
                state.extensions(),
                state.selected(),
                enabled ? "正在启用内置能力…" : "正在停用内置能力…",
                false,
                epoch));
        gateway.setBuiltinExtensionEnabled(current, enabled)
                .whenComplete((updated, failure) -> completeTransition(epoch, updated, failure));
    }

    /** @return 当前不可变状态 */
    public BuiltinExtensionSettingsState state() {
        return state;
    }

    private void completeReload(long epoch, List<BuiltinExtensionRpcContracts.Status> extensions, Throwable failure) {
        if (stale(epoch)) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure);
            return;
        }
        List<BuiltinExtensionRpcContracts.Status> catalog = extensions.stream()
                .sorted(Comparator.comparing(BuiltinExtensionRpcContracts.Status::id))
                .toList();
        String selectedId =
                state.selected().map(BuiltinExtensionRpcContracts.Status::id).orElse("");
        Optional<BuiltinExtensionRpcContracts.Status> selected = catalog.stream()
                .filter(item -> item.id().equals(selectedId))
                .findFirst()
                .or(() -> catalog.stream().findFirst());
        String message = catalog.isEmpty() ? "服务端未返回任何内置能力" : "";
        publish(new BuiltinExtensionSettingsState(SettingsLoadState.READY, catalog, selected, message, false, epoch));
    }

    private void completeTransition(long epoch, BuiltinExtensionRpcContracts.Status updated, Throwable failure) {
        if (stale(epoch)) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure);
            return;
        }
        List<BuiltinExtensionRpcContracts.Status> extensions = replace(state.extensions(), updated);
        String message = updated.state() == ExtensionState.ENABLED ? "内置能力已启用" : "内置能力已停用";
        publish(new BuiltinExtensionSettingsState(
                SettingsLoadState.READY, extensions, Optional.of(updated), message, false, epoch));
    }

    private void fail(Throwable failure) {
        fail(state.epoch(), failure);
    }

    private void fail(long epoch, Throwable failure) {
        publish(new BuiltinExtensionSettingsState(
                SettingsLoadState.ERROR,
                state.extensions(),
                state.selected(),
                SettingsFailures.message(failure),
                SettingsFailures.revisionConflict(failure),
                epoch));
    }

    private boolean stale(long epoch) {
        return epoch != state.epoch();
    }

    private void publish(BuiltinExtensionSettingsState next) {
        state = Objects.requireNonNull(next, "next");
        listener.accept(state);
    }

    private static boolean eligible(ExtensionState state) {
        return state == ExtensionState.ENABLED || state == ExtensionState.DISABLED;
    }

    private static List<BuiltinExtensionRpcContracts.Status> replace(
            List<BuiltinExtensionRpcContracts.Status> current, BuiltinExtensionRpcContracts.Status updated) {
        ArrayList<BuiltinExtensionRpcContracts.Status> result = new ArrayList<>(current);
        result.removeIf(item -> item.id().equals(updated.id()));
        result.add(updated);
        result.sort(Comparator.comparing(BuiltinExtensionRpcContracts.Status::id));
        return List.copyOf(result);
    }
}
