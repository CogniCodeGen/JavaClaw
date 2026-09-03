package com.javaclaw.desktop.settings;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileDiff;
import com.javaclaw.api.PermissionProfileRef;

/** PermissionProfile 历史和服务端 diff 的异步状态机。 */
public final class PermissionHistoryPresenter {
    private final CoreSettingsGateway gateway;
    private Consumer<PermissionHistoryState> listener = ignored -> {};
    private PermissionHistoryState state = PermissionHistoryState.initial();

    /**
     * 创建 Presenter。
     *
     * @param gateway SDK 异步边界
     */
    public PermissionHistoryPresenter(CoreSettingsGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    /**
     * 订阅状态并立即收到当前快照。
     *
     * @param value 页面渲染回调
     */
    public void subscribe(Consumer<PermissionHistoryState> value) {
        listener = Objects.requireNonNull(value, "listener");
        listener.accept(state);
    }

    /**
     * 异步读取配置历史，并比较最新两个版本。
     *
     * @param profile 当前权威配置
     */
    public void load(PermissionProfile profile) {
        PermissionProfile checked = Objects.requireNonNull(profile, "profile");
        PermissionProfileRef reference = new PermissionProfileRef(checked.id(), checked.version());
        long epoch = state.epoch() + 1;
        publish(new PermissionHistoryState(
                SettingsLoadState.LOADING,
                Optional.of(reference),
                state.history(),
                Optional.empty(),
                "正在读取版本历史…",
                epoch));
        gateway.permissionProfileHistory(checked.id())
                .whenComplete((history, failure) -> completeHistory(epoch, reference, history, failure));
    }

    /** 清空已经离开详情页的历史状态。 */
    public void clear() {
        publish(new PermissionHistoryState(
                SettingsLoadState.INITIAL,
                Optional.empty(),
                List.of(),
                Optional.empty(),
                "请先选择已保存的权限方案",
                state.epoch() + 1));
    }

    /** @return 当前不可变状态 */
    public PermissionHistoryState state() {
        return state;
    }

    private void completeHistory(
            long epoch, PermissionProfileRef reference, List<PermissionProfile> history, Throwable failure) {
        if (epoch != state.epoch()) {
            return;
        }
        if (failure != null) {
            publishFailure(epoch, reference, failure);
            return;
        }
        List<PermissionProfile> ordered = history.stream()
                .sorted(Comparator.comparingLong(PermissionProfile::version))
                .toList();
        if (ordered.size() < 2) {
            publish(new PermissionHistoryState(
                    SettingsLoadState.READY,
                    Optional.of(reference),
                    ordered,
                    Optional.empty(),
                    ordered.isEmpty() ? "服务端没有返回版本历史" : "当前只有一个版本，暂无可比较差异",
                    epoch));
            return;
        }
        PermissionProfile before = ordered.get(ordered.size() - 2);
        PermissionProfile after = ordered.getLast();
        gateway.permissionProfileDiff(reference.id(), before.version(), after.version())
                .whenComplete((diff, diffFailure) -> completeDiff(epoch, reference, ordered, diff, diffFailure));
    }

    private void completeDiff(
            long epoch,
            PermissionProfileRef reference,
            List<PermissionProfile> history,
            PermissionProfileDiff diff,
            Throwable failure) {
        if (epoch != state.epoch()) {
            return;
        }
        if (failure != null) {
            publishFailure(epoch, reference, failure);
            return;
        }
        publish(new PermissionHistoryState(
                SettingsLoadState.READY,
                Optional.of(reference),
                history,
                Optional.of(diff),
                "服务端版本历史与 diff 已刷新",
                epoch));
    }

    private void publishFailure(long epoch, PermissionProfileRef reference, Throwable failure) {
        publish(new PermissionHistoryState(
                SettingsLoadState.ERROR,
                Optional.of(reference),
                state.history(),
                Optional.empty(),
                SettingsFailures.message(failure),
                epoch));
    }

    private void publish(PermissionHistoryState next) {
        state = next;
        listener.accept(next);
    }
}
