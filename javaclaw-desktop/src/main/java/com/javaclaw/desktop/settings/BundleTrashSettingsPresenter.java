package com.javaclaw.desktop.settings;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import com.javaclaw.protocol.BundleRpcContracts;

/** Bundle Trash 列表、恢复和精确确认永久清除的异步状态机。 */
public final class BundleTrashSettingsPresenter {
    private final BundleSettingsGateway gateway;
    private Consumer<BundleTrashSettingsState> listener = ignored -> {};
    private BundleTrashSettingsState state = BundleTrashSettingsState.initial();

    /** @param gateway 强类型 SDK 设置边界 */
    public BundleTrashSettingsPresenter(BundleSettingsGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    /** @param value 完整状态订阅者；注册后立即收到当前快照 */
    public void subscribe(Consumer<BundleTrashSettingsState> value) {
        listener = Objects.requireNonNull(value, "listener");
        listener.accept(state);
    }

    /** 读取全部 Trash tombstone。 */
    public void reload() {
        load(Optional.empty(), "正在读取扩展包回收站…", "");
    }

    /** @param entry 选择的 Trash 条目 */
    public void select(BundleRpcContracts.TrashEntry entry) {
        publish(copy(state.phase(), Optional.ofNullable(entry), "", false, state.epoch()));
    }

    /** 恢复所选 TRASHED Bundle 为新的 DISABLED revision。 */
    public void restore() {
        BundleRpcContracts.TrashEntry entry = requireTrashed();
        long epoch = begin("正在重新验签并恢复扩展包…");
        gateway.restoreBundle(entry).whenComplete((bundle, failure) -> {
            if (completeFailure(epoch, failure)) {
                return;
            }
            load(Optional.of(entry.trashId()), "正在刷新扩展包回收站…", "扩展包已恢复为版本 " + bundle.revision());
        });
    }

    /** @param confirmation 必须精确等于 {@code PURGE <trashId>} */
    public void purge(String confirmation) {
        BundleRpcContracts.TrashEntry entry = requireTrashed();
        String expected = "PURGE " + entry.trashId();
        if (!expected.equals(confirmation)) {
            publish(copy(SettingsLoadState.ERROR, state.selected(), "危险确认不匹配", false, state.epoch()));
            return;
        }
        long epoch = begin("正在永久清除回收站文件…");
        gateway.purgeBundle(entry, confirmation).whenComplete((purged, failure) -> {
            if (completeFailure(epoch, failure)) {
                return;
            }
            load(Optional.of(purged.trashId()), "正在刷新扩展包回收站…", "回收站文件已永久清除");
        });
    }

    /** @return 当前不可变状态 */
    public BundleTrashSettingsState state() {
        return state;
    }

    private void load(Optional<String> selectedId, String progress, String resultMessage) {
        long epoch = nextEpoch();
        publish(copy(SettingsLoadState.LOADING, state.selected(), progress, false, epoch));
        gateway.bundleTrash()
                .whenComplete((entries, failure) -> completeLoad(epoch, selectedId, resultMessage, entries, failure));
    }

    private void completeLoad(
            long epoch,
            Optional<String> selectedId,
            String resultMessage,
            List<BundleRpcContracts.TrashEntry> entries,
            Throwable failure) {
        if (stale(epoch)) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure);
            return;
        }
        List<BundleRpcContracts.TrashEntry> sorted = entries.stream()
                .sorted(Comparator.comparing(BundleRpcContracts.TrashEntry::removedAt)
                        .reversed())
                .toList();
        Optional<BundleRpcContracts.TrashEntry> selected = selectedId
                .flatMap(id -> sorted.stream()
                        .filter(value -> value.trashId().equals(id))
                        .findFirst())
                .or(() -> sorted.stream().findFirst());
        publish(new BundleTrashSettingsState(
                SettingsLoadState.READY,
                sorted,
                selected,
                resultMessage.isBlank() && sorted.isEmpty() ? "回收站为空" : resultMessage,
                false,
                epoch));
    }

    private BundleRpcContracts.TrashEntry requireTrashed() {
        BundleRpcContracts.TrashEntry entry = state.selected().orElseThrow();
        if (entry.state() != BundleRpcContracts.TrashState.TRASHED) {
            throw new IllegalStateException("只有 TRASHED 条目可执行此动作");
        }
        return entry;
    }

    private long begin(String message) {
        long epoch = nextEpoch();
        publish(copy(SettingsLoadState.SAVING, state.selected(), message, false, epoch));
        return epoch;
    }

    private boolean completeFailure(long epoch, Throwable failure) {
        if (stale(epoch)) {
            return true;
        }
        if (failure == null) {
            return false;
        }
        fail(epoch, failure);
        return true;
    }

    private void fail(long epoch, Throwable failure) {
        publish(copy(
                SettingsLoadState.ERROR,
                state.selected(),
                SettingsFailures.message(failure),
                SettingsFailures.revisionConflict(failure),
                epoch));
    }

    private BundleTrashSettingsState copy(
            SettingsLoadState phase,
            Optional<BundleRpcContracts.TrashEntry> selected,
            String message,
            boolean conflict,
            long epoch) {
        return new BundleTrashSettingsState(phase, state.entries(), selected, message, conflict, epoch);
    }

    private long nextEpoch() {
        return Math.incrementExact(state.epoch());
    }

    private boolean stale(long epoch) {
        return epoch != state.epoch();
    }

    private void publish(BundleTrashSettingsState value) {
        state = Objects.requireNonNull(value, "value");
        listener.accept(state);
    }
}
