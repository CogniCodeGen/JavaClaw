package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import com.javaclaw.api.DiagnosticsSnapshot;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.component.AsyncActionBar;
import com.javaclaw.desktop.component.AsyncActionBar.ActionState;
import com.javaclaw.desktop.component.FormSection;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.protocol.DiagnosticsRpcContracts;

/** 展示 JavaClaw 服务固定退出策略、活动运行锁和登录启动约束的管理页面。 */
public final class LifecycleSettingsPage extends VBox implements ManagedSettingsPage {
    private final CoreSettingsGateway gateway;
    private final Label clients = value();
    private final Label leases = value();
    private final Label startup = value();
    private final Label startupDetail = value();
    private final Label launcher = value();
    private final Label tray = value();
    private final Label serverControl = value();
    private final Label launcherDetail = value();
    private final Button repair;
    private final AsyncActionBar actions;
    private Optional<PageState> state = Optional.empty();
    private long requestEpoch;

    /**
     * 创建生命周期页面。
     *
     * @param gateway 强类型 SDK 设置边界
     */
    public LifecycleSettingsPage(CoreSettingsGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        PlatformComponentFactory components = new PlatformComponentFactory();
        repair = components.action("修复启动项", ActionStyle.SOFT, ActionSize.NORMAL);
        repair.setDisable(true);
        repair.setOnAction(event -> repair());
        Button refresh = components.action("刷新", ActionStyle.PRIMARY, ActionSize.NORMAL);
        refresh.setOnAction(event -> load());
        actions = new AsyncActionBar(repair, refresh);
        configurePage(repair);
    }

    @Override
    public Node content() {
        return this;
    }

    @Override
    public Optional<Node> actionContent() {
        return Optional.of(actions);
    }

    @Override
    public void activate() {
        load();
    }

    @Override
    public boolean dirty() {
        return false;
    }

    @Override
    public void warnUnsavedChanges() {}

    @Override
    public void discardDraft() {}

    @Override
    public void dispose() {
        requestEpoch++;
    }

    private void configurePage(Button repair) {
        Label title = new Label("生命周期");
        title.getStyleClass().addAll("sec-title", "platform-page-title");
        Label description = new Label("JavaClaw 服务只由客户端连接和持久活动运行锁保活；托盘不执行定时任务。");
        description.setWrapText(true);
        description.getStyleClass().add("sec-hint");

        FormSection process = new FormSection("后台退出", "退出等待是产品安全边界，不提供用户可修改值。");
        process.addField("无客户端、无运行锁", value("60 秒后退出（固定）"));
        process.addField("当前客户端", clients);
        process.addField("活动运行锁", leases);

        FormSection login = new FormSection("登录启动项", "首个启用定时任务注册，最后一个停用时注销；页面只允许修复权威投影。");
        login.addField("状态", startup);
        login.addFullWidth(new HBox(8, repair));
        login.addFullWidth(startupDetail);

        FormSection supervisor = new FormSection("发行托盘", "托盘只控制 JavaClaw 服务和打开主窗口，不执行定时任务。");
        supervisor.addField("启动器", launcher);
        supervisor.addField("系统托盘", tray);
        supervisor.addField("服务控制", serverControl);
        supervisor.addFullWidth(launcherDetail);

        getChildren().addAll(title, description, process, login, supervisor);
        getStyleClass().add("platform-page");
    }

    private void load() {
        long epoch = ++requestEpoch;
        actions.show(ActionState.PENDING, "正在读取生命周期状态…");
        gateway.diagnostics()
                .thenCombine(gateway.launcherStatus(), PageState::new)
                .whenComplete(
                        (loaded, failure) -> FxStateDispatcher.dispatch(() -> completeLoad(epoch, loaded, failure)));
    }

    private void completeLoad(long epoch, PageState loaded, Throwable failure) {
        if (epoch != requestEpoch) {
            return;
        }
        if (failure != null) {
            actions.show(
                    ActionState.ERROR,
                    state.isPresent()
                            ? "读取失败，保留上一次状态：" + SettingsFailures.message(failure)
                            : SettingsFailures.message(failure));
            return;
        }
        state = Optional.of(Objects.requireNonNull(loaded, "loaded"));
        render(loaded);
    }

    private void render(PageState loaded) {
        DiagnosticsSnapshot diagnostics = loaded.diagnostics();
        clients.setText(Integer.toString(diagnostics.health().connectedClients()));
        leases.setText(Integer.toString(diagnostics.health().activeLeases()));
        DiagnosticsSnapshot.ScheduleHealth schedule = diagnostics.subsystems().schedule();
        boolean projectionMatches = schedule.loginStartupRequired() == schedule.loginStartupInstalled();
        startup.setText(projectionMatches ? "已同步" : "需要修复");
        repair.setDisable(!schedule.repairAvailable());
        String detail = schedule.unavailableReason()
                .orElseGet(
                        () -> schedule.loginStartupRequired() ? "存在启用定时任务；登录启动项应处于已安装状态。" : "没有启用定时任务；登录启动项应处于未安装状态。");
        startupDetail.setText(detail);
        repair.setTooltip(schedule.unavailableReason().map(Tooltip::new).orElse(null));
        renderSupervisor(loaded.launcher());
        actions.show(ActionState.SUCCESS, "生命周期状态已刷新");
    }

    private void renderSupervisor(DiagnosticsRpcContracts.LauncherStatus status) {
        launcher.setText(status.launcherConfigured() ? "已配置" : "未配置");
        tray.setText(status.trayActive() ? "运行中" : "不可用");
        serverControl.setText(status.serverControlAvailable() ? "可用" : "不可用");
        launcherDetail.setText(status.unavailableReason().orElse("发行托盘可以启动、停止、重启 JavaClaw 服务并打开主窗口。"));
    }

    private void repair() {
        long epoch = ++requestEpoch;
        repair.setDisable(true);
        actions.show(ActionState.PENDING, "正在修复登录启动项…");
        gateway.repairLoginStartup(CommandOptions.create(0))
                .whenComplete(
                        (loaded, failure) -> FxStateDispatcher.dispatch(() -> completeRepair(epoch, loaded, failure)));
    }

    private void completeRepair(long epoch, DiagnosticsSnapshot loaded, Throwable failure) {
        if (epoch != requestEpoch) {
            return;
        }
        if (failure != null) {
            state.ifPresent(this::render);
            actions.show(ActionState.ERROR, SettingsFailures.message(failure));
            return;
        }
        PageState refreshed = new PageState(
                Objects.requireNonNull(loaded, "loaded"),
                state.map(PageState::launcher)
                        .orElseGet(() -> new DiagnosticsRpcContracts.LauncherStatus(
                                false, false, false, Optional.of("launcher 状态尚未读取"))));
        state = Optional.of(refreshed);
        render(refreshed);
        actions.show(ActionState.SUCCESS, "登录启动项已按定时任务权威状态修复");
    }

    private static Label value() {
        return value("—");
    }

    private static Label value(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("platform-detail-text");
        return label;
    }

    private record PageState(DiagnosticsSnapshot diagnostics, DiagnosticsRpcContracts.LauncherStatus launcher) {
        private PageState {
            Objects.requireNonNull(diagnostics, "diagnostics");
            Objects.requireNonNull(launcher, "launcher");
        }
    }
}
