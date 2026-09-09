package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.util.Duration;

import com.javaclaw.desktop.DesktopConfigurationChange;
import com.javaclaw.desktop.DesktopNotificationSubscription;

/**
 * 核心设置页共享的事件重验协调器；成功快照在五分钟内复用，隐藏时只记录失效，激活时按需读取。
 *
 * <p>SDK 成功事件可能先于页面收到写回执。延后到下一次 UI 调度再检查状态；只有确实存在待刷新且 页面仍在执行动作时才短暂等待，不能重入写回调或让跨分区的在途操作丢失刷新。草稿保护交给 Presenter。
 */
final class SettingsPageRefresh implements AutoCloseable {
    private final ManagedSettingsPage page;
    private final Function<Boolean, CompletionStage<Boolean>> refresh;
    private final SettingsCacheFreshness freshness;
    private final BooleanSupplier healthy;
    private final DesktopNotificationSubscription subscription;
    private final PauseTransition retry = new PauseTransition(Duration.millis(100));
    private boolean active;
    private boolean requested;
    private boolean scheduled;
    private boolean closed;
    private boolean reading;
    private long invalidation;

    SettingsPageRefresh(
            CoreSettingsGateway gateway,
            Set<DesktopConfigurationChange.Kind> kinds,
            ManagedSettingsPage page,
            Function<Boolean, CompletionStage<Boolean>> refresh) {
        this(gateway, kinds, page, refresh, new SettingsCacheFreshness(), () -> true);
    }

    SettingsPageRefresh(
            CoreSettingsGateway gateway,
            Set<DesktopConfigurationChange.Kind> kinds,
            ManagedSettingsPage page,
            Function<Boolean, CompletionStage<Boolean>> refresh,
            SettingsCacheFreshness freshness,
            BooleanSupplier healthy) {
        this.page = Objects.requireNonNull(page, "page");
        this.refresh = Objects.requireNonNull(refresh, "refresh");
        this.freshness = Objects.requireNonNull(freshness, "freshness");
        this.healthy = Objects.requireNonNull(healthy, "healthy");
        Set<DesktopConfigurationChange.Kind> observed = Set.copyOf(kinds);
        subscription = gateway.onConfigurationChanged(change -> {
            if (observed.contains(change.kind())) {
                request();
            }
        });
        retry.setOnFinished(ignored -> drain());
    }

    static SettingsPageRefresh provider(
            CoreSettingsGateway gateway,
            ManagedSettingsPage page,
            ProviderSettingsPresenter presenter,
            ProviderEmbeddingBindingPresenter embedding) {
        return new SettingsPageRefresh(
                gateway,
                Set.of(DesktopConfigurationChange.Kind.PROVIDERS),
                page,
                preserve -> presenter
                        .refreshForConfigurationChange(preserve)
                        .thenCombine(embedding.reload(), (catalog, binding) -> catalog && binding),
                new SettingsCacheFreshness(),
                () -> presenter.state().phase() == SettingsLoadState.READY && embedding.ready());
    }

    static SettingsPageRefresh roles(
            CoreSettingsGateway gateway, ManagedSettingsPage page, AgentRoleSettingsPresenter presenter) {
        return new SettingsPageRefresh(
                gateway,
                Set.of(DesktopConfigurationChange.Kind.ROLES, DesktopConfigurationChange.Kind.PROVIDERS),
                page,
                presenter::refreshForConfigurationChange,
                new SettingsCacheFreshness(),
                () -> presenter.state().phase() == SettingsLoadState.READY);
    }

    static SettingsPageRefresh permissions(
            CoreSettingsGateway gateway, ManagedSettingsPage page, PermissionProfileSettingsPresenter presenter) {
        return new SettingsPageRefresh(
                gateway,
                Set.of(DesktopConfigurationChange.Kind.PERMISSIONS),
                page,
                presenter::refreshForConfigurationChange,
                new SettingsCacheFreshness(),
                () -> presenter.state().phase() == SettingsLoadState.READY);
    }

    void activate() {
        active = true;
        if (!reading && (!freshness.fresh() || !page.pending() && !healthy.getAsBoolean())) {
            requested = true;
        }
        drain();
    }

    void invalidate() {
        freshness.invalidate();
        invalidation++;
        requested = true;
        schedule();
    }

    void deactivate() {
        active = false;
        retry.stop();
    }

    private void request() {
        invalidate();
    }

    private void schedule() {
        if (!closed && active && !scheduled) {
            scheduled = true;
            Platform.runLater(() -> {
                scheduled = false;
                drain();
            });
        }
    }

    private void drain() {
        if (closed || !active || !requested || reading) {
            return;
        }
        if (page.pending()) {
            retry.playFromStart();
            return;
        }
        requested = false;
        reading = true;
        freshness.invalidate();
        long request = invalidation;
        refresh.apply(page.dirty()).whenComplete((success, failure) -> {
            reading = false;
            if (!closed && request == invalidation && failure == null && Boolean.TRUE.equals(success)) {
                freshness.markFresh();
            }
            drain();
        });
    }

    @Override
    public void close() {
        closed = true;
        requested = false;
        deactivate();
        subscription.close();
    }
}
