package com.javaclaw.desktop.settings;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import com.javaclaw.api.DiagnosticsSnapshot;
import com.javaclaw.desktop.component.AsyncActionBar;
import com.javaclaw.desktop.component.AsyncActionBar.ActionState;
import com.javaclaw.desktop.component.FormSection;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.desktop.component.PlatformComponentFactory.FeedbackKind;
import com.javaclaw.protocol.CanonicalJson;

/** 展示并导出白名单诊断字段的设置页面。 */
public final class DiagnosticsSettingsPage extends VBox implements ManagedSettingsPage {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss z");

    private final CoreSettingsGateway gateway;
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final CanonicalJson json = new CanonicalJson();
    private final StackPane snapshotContent = new StackPane();
    private final Button copy;
    private final Button export;
    private final AsyncActionBar actions;
    private Optional<DiagnosticsSnapshot> snapshot = Optional.empty();
    private long requestEpoch;

    /**
     * 创建诊断页。
     *
     * @param gateway 强类型 SDK 设置边界
     */
    public DiagnosticsSettingsPage(CoreSettingsGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        copy = components.action("复制摘要", ActionStyle.SOFT, ActionSize.NORMAL);
        copy.setOnAction(event -> copySummary());
        export = components.action("导出 JSON", ActionStyle.SOFT, ActionSize.NORMAL);
        export.setOnAction(event -> exportSnapshot());
        Button refresh = components.action("刷新", ActionStyle.PRIMARY, ActionSize.NORMAL);
        refresh.setOnAction(event -> load());
        actions = new AsyncActionBar(copy, export, refresh);
        configurePage();
        updateActionAvailability();
    }

    @Override
    public Node content() {
        return this;
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

    private void configurePage() {
        Label title = new Label("诊断");
        title.getStyleClass().addAll("sec-title", "platform-page-title");
        Label description = new Label("只展示协议白名单中的构建、数据库和运行状态；不读取环境变量、Secret、用户正文或绝对路径。");
        description.setWrapText(true);
        description.getStyleClass().add("sec-hint");
        snapshotContent
                .getChildren()
                .setAll(components.feedback(FeedbackKind.LOADING, "正在读取诊断", "等待 App Server 返回脱敏快照。"));
        getChildren().addAll(title, description, snapshotContent, actions);
        getStyleClass().add("platform-page");
    }

    private void load() {
        long epoch = ++requestEpoch;
        actions.show(ActionState.PENDING, "正在刷新诊断…");
        if (snapshot.isEmpty()) {
            snapshotContent
                    .getChildren()
                    .setAll(components.feedback(FeedbackKind.LOADING, "正在读取诊断", "等待 App Server 返回脱敏快照。"));
        }
        gateway.diagnostics()
                .whenComplete(
                        (loaded, failure) -> FxStateDispatcher.dispatch(() -> completeLoad(epoch, loaded, failure)));
    }

    private void completeLoad(long epoch, DiagnosticsSnapshot loaded, Throwable failure) {
        if (epoch != requestEpoch) {
            return;
        }
        if (failure != null) {
            applyFailure(failure);
        } else {
            applySnapshot(loaded);
        }
    }

    private void applySnapshot(DiagnosticsSnapshot loaded) {
        DiagnosticsSnapshot checked = Objects.requireNonNull(loaded, "loaded");
        snapshot = Optional.of(checked);
        snapshotContent.getChildren().setAll(render(checked));
        actions.show(ActionState.SUCCESS, "诊断已刷新 · " + format(checked.observedAt()));
        updateActionAvailability();
    }

    private void applyFailure(Throwable failure) {
        String detail = SettingsFailures.message(failure);
        if (snapshot.isEmpty()) {
            snapshotContent.getChildren().setAll(components.feedback(FeedbackKind.ERROR, "诊断读取失败", detail));
        }
        actions.show(ActionState.ERROR, snapshot.isPresent() ? "刷新失败，继续显示上一次快照：" + detail : detail);
        updateActionAvailability();
    }

    private Node render(DiagnosticsSnapshot value) {
        DiagnosticsSnapshot.SubsystemHealth subsystems = value.subsystems();
        return new VBox(
                12,
                buildSection(value),
                runtimeSection(value.health()),
                providerSection(subsystems.providers()),
                extensionSection(subsystems.extensions()),
                integrationSection(subsystems.integrations()),
                backgroundSection(subsystems.jobs(), subsystems.schedule()),
                launcherSection(subsystems.launcher()));
    }

    private FormSection buildSection(DiagnosticsSnapshot value) {
        DiagnosticsSnapshot.BuildIdentity build = value.build();
        FormSection identity = new FormSection("构建与数据", "用于确认客户端与 App Server 的版本边界。");
        identity.addField("JavaClaw", text(build.applicationVersion()));
        identity.addField("Protocol", text("v" + build.protocolVersion()));
        identity.addField("data-v5 Schema", text("V" + build.dataSchemaVersion()));
        identity.addField("启动时间", text(format(value.startedAt())));
        return identity;
    }

    private FormSection runtimeSection(DiagnosticsSnapshot.RuntimeHealth health) {
        FormSection runtime = new FormSection("运行状态", "计数只表示当前本地进程，不包含 Workspace 名称或内容。");
        runtime.addField("数据库", healthStatus(health.databaseHealthy()));
        runtime.addField("Workspace", text(Integer.toString(health.workspaceCount())));
        runtime.addField("扩展", text(Integer.toString(health.extensionCount())));
        runtime.addField("连接客户端", text(Integer.toString(health.connectedClients())));
        runtime.addField("活动 Lease", text(Integer.toString(health.activeLeases())));
        runtime.addField("运行平台", text(health.operatingSystem() + " · Java " + health.javaVersion()));
        return runtime;
    }

    private FormSection providerSection(DiagnosticsSnapshot.ProviderVaultHealth providers) {
        FormSection platform = new FormSection("模型与 Vault", "仅展示配置数量和 Vault 可用性，不读取端点、模型或 Secret 内容。");
        platform.addField(
                "Provider", text(providers.activeProviders() + " 启用 / " + providers.configuredProviders() + " 已配置"));
        platform.addField("Vault", text(providers.vaultState() + " · " + providers.credentialCount() + " 条凭据"));
        return platform;
    }

    private FormSection extensionSection(DiagnosticsSnapshot.ExtensionHealth extensions) {
        FormSection extension = new FormSection("扩展运行状态", "只展示数量与隔离状态，不包含 Bundle 路径或失败正文。");
        extension.addField(
                "Extension",
                text(extensions.enabled()
                        + " 启用 · "
                        + extensions.disabled()
                        + " 禁用 · "
                        + extensions.quarantined()
                        + " 隔离"));
        extension.addField("信任层", text(extensions.thirdParty() + " 第三方 / " + extensions.registered() + " 全部"));
        return extension;
    }

    private FormSection integrationSection(DiagnosticsSnapshot.IntegrationHealth integrations) {
        FormSection external = new FormSection("连接与隔离 Worker", "MCP 异常计数来自最近健康投影；UNKNOWN 不记为异常。");
        external.addField(
                "MCP",
                text(integrations.enabledMcpEndpoints()
                        + " 启用 / "
                        + integrations.configuredMcpEndpoints()
                        + " 已配置 / "
                        + integrations.unhealthyMcpEndpoints()
                        + " 异常"));
        external.addField("Browser Worker", availability(integrations.browserWorkerAvailable()));
        external.addField("Knowledge Worker", availability(integrations.knowledgeWorkerAvailable()));
        external.addField("Skill Java/JShell", availability(integrations.skillExecutionAvailable()));
        return external;
    }

    private FormSection backgroundSection(
            DiagnosticsSnapshot.JobHealth jobs, DiagnosticsSnapshot.ScheduleHealth schedule) {
        FormSection background = new FormSection("后台执行", "Job 和 Schedule 计数不包含 Workspace、Definition 或正文。");
        background.addField(
                "Extension Job",
                text(jobs.queued() + " 排队 · " + jobs.running() + " 运行 · " + jobs.waiting() + " 等待 · " + jobs.paused()
                        + " 暂停"));
        background.addField("Schedule Lease", state(schedule.leaseHeld(), "已持有", "未持有"));
        background.addField(
                "登录启动项",
                text(
                        schedule.loginStartupInstalled()
                                ? "已安装"
                                : schedule.loginStartupRequired() ? "缺失，需要修复" : "未安装（符合权威状态）"));
        return background;
    }

    private FormSection launcherSection(DiagnosticsSnapshot.LauncherHealth launcher) {
        FormSection supervisor = new FormSection("发行生命周期", "托盘只控制 App Server 与主窗口，不承担 Schedule 执行。");
        supervisor.addField("Launcher", state(launcher.launcherConfigured(), "已配置", "未配置"));
        supervisor.addField("SystemTray", state(launcher.trayActive(), "运行中", "未运行"));
        supervisor.addField("Server 控制", availability(launcher.serverControlAvailable()));
        launcher.unavailableReason().ifPresent(reason -> supervisor.addField("不可用原因", text(reason)));
        return supervisor;
    }

    private Label healthStatus(boolean healthy) {
        Label label = text(healthy ? "正常" : "异常");
        label.getStyleClass().add(healthy ? "platform-action-success" : "platform-action-error");
        return label;
    }

    private Label availability(boolean available) {
        return state(available, "可用", "不可用");
    }

    private Label state(boolean positive, String positiveText, String negativeText) {
        Label label = text(positive ? positiveText : negativeText);
        if (positive) {
            label.getStyleClass().add("platform-action-success");
        }
        return label;
    }

    private static Label text(String value) {
        Label label = new Label(Objects.requireNonNull(value, "value"));
        label.setWrapText(true);
        label.getStyleClass().add("platform-body-text");
        return label;
    }

    private void copySummary() {
        DiagnosticsSnapshot value = snapshot.orElseThrow();
        ClipboardContent content = new ClipboardContent();
        content.putString(summary(value));
        Clipboard.getSystemClipboard().setContent(content);
        actions.show(ActionState.SUCCESS, "脱敏摘要已复制");
    }

    private void exportSnapshot() {
        DiagnosticsSnapshot value = snapshot.orElseThrow();
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出 JavaClaw 脱敏诊断");
        chooser.setInitialFileName("javaclaw-diagnostics.json");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
        File selected = chooser.showSaveDialog(owner());
        if (selected == null) {
            return;
        }
        try {
            Files.writeString(
                    selected.toPath(),
                    json.encode(value).json() + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            actions.show(ActionState.SUCCESS, "脱敏诊断已导出");
        } catch (IOException failure) {
            actions.show(ActionState.ERROR, "导出失败：" + SettingsFailures.message(failure));
        }
    }

    private Window owner() {
        return getScene() == null ? null : getScene().getWindow();
    }

    private void updateActionAvailability() {
        boolean unavailable = snapshot.isEmpty();
        copy.setDisable(unavailable);
        export.setDisable(unavailable);
    }

    private static String summary(DiagnosticsSnapshot value) {
        DiagnosticsSnapshot.RuntimeHealth health = value.health();
        return "JavaClaw " + value.build().applicationVersion()
                + " · Protocol v" + value.build().protocolVersion()
                + " · data-v5 V" + value.build().dataSchemaVersion()
                + System.lineSeparator()
                + "DB=" + (health.databaseHealthy() ? "healthy" : "unhealthy")
                + ", workspaces=" + health.workspaceCount()
                + ", extensions=" + health.extensionCount()
                + ", clients=" + health.connectedClients()
                + ", leases=" + health.activeLeases()
                + System.lineSeparator()
                + "providers=" + value.subsystems().providers().activeProviders()
                + "/" + value.subsystems().providers().configuredProviders()
                + ", vault=" + value.subsystems().providers().vaultState()
                + ", mcp=" + value.subsystems().integrations().enabledMcpEndpoints()
                + "/" + value.subsystems().integrations().configuredMcpEndpoints()
                + ", extensionEnabled=" + value.subsystems().extensions().enabled()
                + "/" + value.subsystems().extensions().registered()
                + ", extensionQuarantined=" + value.subsystems().extensions().quarantined()
                + ", jobs=" + value.subsystems().jobs().queued()
                + "/" + value.subsystems().jobs().running()
                + ", launcher=" + value.subsystems().launcher().launcherConfigured()
                + ", tray=" + value.subsystems().launcher().trayActive()
                + System.lineSeparator()
                + "observedAt=" + value.observedAt();
    }

    private static String format(java.time.Instant value) {
        return TIME.format(Objects.requireNonNull(value, "value").atZone(ZoneId.systemDefault()));
    }
}
