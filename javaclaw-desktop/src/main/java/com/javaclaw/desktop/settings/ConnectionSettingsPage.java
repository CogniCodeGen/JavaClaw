package com.javaclaw.desktop.settings;

import java.util.stream.Collectors;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;

import com.javaclaw.desktop.LauncherSession;
import com.javaclaw.desktop.component.FormSection;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;

/** 当前本地 App Server SDK 会话和 Protocol v2 协商状态页面。 */
public final class ConnectionSettingsPage implements ManagedSettingsPage {
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final ConnectionSettingsPresenter presenter;
    private final LauncherSession launcher = LauncherSession.current();
    private final VBox content = components.page("连接");
    private final Label status = value();
    private final Label server = value();
    private final Label protocol = value();
    private final Label stable = value();
    private final Label experimental = value();
    private final Label error = new Label();
    private final Button refresh;
    private final Button reconnect;

    /**
     * 创建连接状态页。
     *
     * @param gateway SDK 异步边界
     */
    public ConnectionSettingsPage(CoreSettingsGateway gateway) {
        presenter = new ConnectionSettingsPresenter(gateway);
        refresh = components.action("刷新当前会话", ActionStyle.SOFT, ActionSize.NORMAL);
        refresh.setOnAction(event -> presenter.reload());
        reconnect = components.action("重新连接", ActionStyle.SOFT, ActionSize.NORMAL);
        reconnect.setOnAction(event -> presenter.reconnect());
        buildLayout();
        presenter.subscribe(this::render);
    }

    @Override
    public Node content() {
        return content;
    }

    @Override
    public void activate() {
        presenter.reload();
    }

    @Override
    public boolean dirty() {
        return false;
    }

    @Override
    public void warnUnsavedChanges() {}

    @Override
    public void discardDraft() {}

    private void buildLayout() {
        Label hint = new Label("此页只展示 Desktop 当前 Java SDK 会话，不读取 transport 实现或 App Server 内部对象。");
        hint.setWrapText(true);
        hint.getStyleClass().add("sec-hint");
        FormSection session = new FormSection("SDK 会话", "initialize/session 成功后固定 Protocol v2 和协商能力，断线后不会伪造在线状态。");
        session.addField("状态", status);
        session.addField("App Server", server);
        session.addField("Protocol", protocol);
        session.addField("Stable", stable);
        session.addField("Experimental", experimental);
        FormSection recovery = new FormSection("连接恢复", "重连与启动服务需要 Desktop 连接协调器和 launcher supervisor 的显式状态。");
        Button start = components.action(launcher.controlLabel(), ActionStyle.PRIMARY, ActionSize.NORMAL);
        start.setDisable(true);
        start.setTooltip(new Tooltip(launcher.recoveryInstruction()));
        Label unavailable = new Label("“重新连接”会关闭旧 SDK 会话并重新协商 Protocol v2。" + launcher.recoveryInstruction());
        unavailable.setWrapText(true);
        unavailable.getStyleClass().addAll("sec-hint", "platform-action-error");
        recovery.addFullWidth(new javafx.scene.layout.HBox(8, reconnect, start));
        recovery.addFullWidth(unavailable);
        error.setWrapText(true);
        error.getStyleClass().add("platform-action-error");
        content.getChildren().addAll(hint, refresh, session, recovery, error);
    }

    private void render(ConnectionSettingsState snapshot) {
        refresh.setDisable(snapshot.phase() == SettingsLoadState.LOADING);
        reconnect.setDisable(snapshot.phase() == SettingsLoadState.LOADING);
        error.setText(snapshot.phase() == SettingsLoadState.ERROR ? snapshot.message() : "");
        error.setVisible(!error.getText().isBlank());
        error.setManaged(error.isVisible());
        if (snapshot.phase() == SettingsLoadState.LOADING) {
            status.setText("连接检查中");
        } else if (snapshot.phase() == SettingsLoadState.ERROR) {
            status.setText("不可用");
        }
        snapshot.summary().ifPresent(this::renderSummary);
    }

    private void renderSummary(ConnectionSummary summary) {
        status.setText("已连接");
        server.setText(summary.serverName() + " " + summary.serverVersion());
        protocol.setText("App Protocol v" + summary.protocolVersion() + " / JSON-RPC 2.0");
        stable.setText(join(summary.stableCapabilities()));
        experimental.setText(join(summary.experimentalCapabilities()));
    }

    private static String join(java.util.Set<String> capabilities) {
        return capabilities.isEmpty() ? "无" : capabilities.stream().sorted().collect(Collectors.joining("、"));
    }

    private static Label value() {
        Label label = new Label("—");
        label.setWrapText(true);
        label.getStyleClass().add("platform-detail-text");
        return label;
    }
}
