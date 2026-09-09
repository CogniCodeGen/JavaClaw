package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.util.Duration;

import com.javaclaw.desktop.DesktopConfigurationChange;
import com.javaclaw.desktop.DesktopNotificationSubscription;

/**
 * 核心设置页共享的事件重验协调器；隐藏时只记录失效，页面重新激活后再读取。
 *
 * <p>SDK 成功事件可能先于页面收到写回执。延后到下一次 UI 调度再检查状态；只有确实存在待刷新且 页面仍在执行动作时才短暂等待，不能重入写回调或让跨分区的在途操作丢失刷新。草稿保护交给 Presenter。
 */
final class SettingsPageRefresh implements AutoCloseable {
    private final ManagedSettingsPage page;
    private final Consumer<Boolean> refresh;
    private final DesktopNotificationSubscription subscription;
    private final PauseTransition retry = new PauseTransition(Duration.millis(100));
    private boolean active;
    private boolean requested;
    private boolean scheduled;
    private boolean closed;

    SettingsPageRefresh(
            CoreSettingsGateway gateway,
            Set<DesktopConfigurationChange.Kind> kinds,
            ManagedSettingsPage page,
            Consumer<Boolean> refresh) {
        this.page = Objects.requireNonNull(page, "page");
        this.refresh = Objects.requireNonNull(refresh, "refresh");
        Set<DesktopConfigurationChange.Kind> observed = Set.copyOf(kinds);
        subscription = gateway.onConfigurationChanged(change -> {
            if (observed.contains(change.kind())) {
                request();
            }
        });
        retry.setOnFinished(ignored -> drain());
    }

    static SettingsPageRefresh provider(
            CoreSettingsGateway gateway, ManagedSettingsPage page, ProviderSettingsPresenter presenter) {
        return new SettingsPageRefresh(
                gateway,
                Set.of(DesktopConfigurationChange.Kind.PROVIDERS),
                page,
                presenter::refreshForConfigurationChange);
    }

    static SettingsPageRefresh roles(
            CoreSettingsGateway gateway, ManagedSettingsPage page, AgentRoleSettingsPresenter presenter) {
        return new SettingsPageRefresh(
                gateway,
                Set.of(DesktopConfigurationChange.Kind.ROLES, DesktopConfigurationChange.Kind.PROVIDERS),
                page,
                presenter::refreshForConfigurationChange);
    }

    static SettingsPageRefresh permissions(
            CoreSettingsGateway gateway, ManagedSettingsPage page, PermissionProfileSettingsPresenter presenter) {
        return new SettingsPageRefresh(
                gateway,
                Set.of(DesktopConfigurationChange.Kind.PERMISSIONS),
                page,
                presenter::refreshForConfigurationChange);
    }

    void activate() {
        active = true;
        requested = true;
        drain();
    }

    void deactivate() {
        active = false;
        retry.stop();
    }

    private void request() {
        requested = true;
        if (!closed && active && !scheduled) {
            scheduled = true;
            Platform.runLater(() -> {
                scheduled = false;
                drain();
            });
        }
    }

    private void drain() {
        if (closed || !active || !requested) {
            return;
        }
        if (page.pending()) {
            retry.playFromStart();
            return;
        }
        requested = false;
        refresh.accept(page.dirty());
    }

    @Override
    public void close() {
        closed = true;
        requested = false;
        deactivate();
        subscription.close();
    }
}
