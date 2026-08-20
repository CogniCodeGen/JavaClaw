package com.javaclaw.ui.javafx.plugin;

import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.ServicePluginInfo;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import javafx.beans.value.ObservableBooleanValue;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.geometry.Pos;

import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Owns the lazily rendered service-plugin tab and its asynchronous mutations. */
final class ServicePluginPanel implements AutoCloseable {
    private final ServicePluginManagementApplicationService services;
    private final VBox view;
    private final VBox list;
    private final Label count;
    private final Consumer<String> status;
    private final BiConsumer<String, Throwable> failures;
    private final Consumer<String> openConfiguration;
    private final UiAsyncAction<List<ServicePluginInfo>> action;

    ServicePluginPanel(
            ServicePluginManagementApplicationService services,
            ManagedTaskExecutor tasks,
            FxDispatcher fx,
            VBox view,
            VBox list,
            Label count,
            Consumer<String> status,
            BiConsumer<String, Throwable> failures,
            Consumer<String> openConfiguration) {
        this.services = Objects.requireNonNull(services, "services");
        this.view = Objects.requireNonNull(view, "view");
        this.list = Objects.requireNonNull(list, "list");
        this.count = Objects.requireNonNull(count, "count");
        this.status = Objects.requireNonNull(status, "status");
        this.failures = Objects.requireNonNull(failures, "failures");
        this.openConfiguration = Objects.requireNonNull(openConfiguration, "openConfiguration");
        action = new UiAsyncAction<>(tasks, fx);
    }

    ObservableBooleanValue busyProperty() {
        return action.busyProperty();
    }

    void show() {
        view.setVisible(true);
        view.setManaged(true);
        refresh();
    }

    void hide() {
        view.setVisible(false);
        view.setManaged(false);
    }

    void refresh() {
        action.execute(
                TaskSpec.io("service-plugin-refresh"),
                context -> services.list(),
                this::render,
                failure -> failures.accept("刷新服务插件失败", failure));
    }

    private void mutate(String taskName, String success, Runnable operation) {
        action.execute(
                TaskSpec.io(taskName),
                context -> {
                    operation.run();
                    return services.list();
                },
                values -> {
                    render(values);
                    status.accept(success);
                },
                failure -> failures.accept("服务插件操作失败", failure));
    }

    private void render(List<ServicePluginInfo> values) {
        list.getChildren().clear();
        List<ServicePluginInfo> plugins = values == null ? List.of() : values;
        count.setText(plugins.size() + " 个");
        if (plugins.isEmpty()) {
            list.setAlignment(Pos.CENTER);
            list.getChildren().add(emptyState());
            return;
        }
        list.setAlignment(Pos.TOP_LEFT);
        plugins.forEach(plugin -> list.getChildren().add(row(plugin)));
    }

    private VBox emptyState() {
        Label icon = new Label("◇");
        icon.getStyleClass().add("service-plugin-empty-icon");
        Label title = new Label("尚未发现服务插件");
        title.getStyleClass().add("service-plugin-empty-title");
        Label explanation = new Label(
                "尚未在插件目录发现服务插件。可从签名 JAR 安装；"
                        + "源码开发包需要先构建，再通过“从文件安装”加入插件目录。");
        explanation.setWrapText(true);
        explanation.setMaxWidth(440);
        explanation.getStyleClass().add("settings-hint");
        Button retry = button("重新检测", this::refresh, false);
        retry.setId("servicePluginRetryButton");
        VBox empty = new VBox(10, icon, title, explanation, retry);
        empty.setAlignment(Pos.CENTER);
        empty.setMaxWidth(520);
        empty.getStyleClass().add("service-plugin-empty");
        return empty;
    }

    private VBox row(ServicePluginInfo plugin) {
        VBox card = new VBox(8);
        card.getStyleClass().addAll("jc-card", "service-plugin-card");
        card.setMaxWidth(Double.MAX_VALUE);
        HBox header = new HBox(10);
        header.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        Label name = new Label(plugin.name() + "  " + plugin.version());
        name.setWrapText(true);
        name.getStyleClass().add("service-plugin-name");
        Label state = new Label(stateText(plugin));
        state.getStyleClass().addAll("jc-badge", stateClass(plugin));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        header.getChildren().addAll(name, state, spacer);

        String process = plugin.pid() > 0
                ? "PID " + plugin.pid() + " · 运行于 " + plugin.processStartedAt()
                : "进程未运行";
        Label metadata = hint("发布者 " + plugin.publisher() + " · "
                + (plugin.signatureVerified() ? "签名已验证" : "未签名开发模式") + " · "
                + (plugin.builtIn() ? "内置服务插件" : "第三方服务插件") + " · " + process
                + (plugin.artifactPath() == null ? "" : "\n插件 JAR：" + plugin.artifactPath()));
        metadata.setId("servicePluginMetadata");
        Label description = hint(plugin.description().isBlank()
                ? "此插件未提供说明" : plugin.description());
        description.setId("servicePluginDescription");
        description.getStyleClass().add("service-plugin-description");
        Label resources = hint("资源预留：" + plugin.reservedMemoryMiB() + " MiB · "
                + plugin.reservedComputeThreads() + " 计算线程 · 活动/排队 "
                + plugin.activeRequests() + "/" + plugin.queuedRequests()
                + actualResourceSummary(plugin));
        Label endpoints = hint(plugin.endpoints().isEmpty() ? "无对外端点"
                : plugin.endpoints().stream().map(endpoint -> endpoint.protocol() + "://"
                        + endpoint.bindAddress() + ":" + endpoint.port())
                        .collect(java.util.stream.Collectors.joining("  ")));
        endpoints.setWrapText(true);

        HBox actions = new HBox(8);
        actions.setId("servicePluginActions");
        actions.getStyleClass().add("service-plugin-actions");
        actions.getChildren().add(button(primaryActionLabel(plugin), () -> primaryAction(plugin), true));
        if (isRunning(plugin)) {
            actions.getChildren().add(button("重启", () -> mutate(
                    "service-plugin-restart-" + plugin.id(), "服务插件已重启",
                    () -> services.restart(plugin.id())), false));
        }
        if (plugin.signatureVerified()) {
            actions.getChildren().add(button(
                    plugin.startupPolicy() == ServicePluginManagementApplicationService.StartupPolicy.AUTO_START
                            ? "改为手动启动" : "设为自动启动",
                    () -> mutate("service-plugin-policy-" + plugin.id(), "启动策略已保存",
                            () -> services.setStartupPolicy(plugin.id(),
                                    plugin.startupPolicy()
                                            == ServicePluginManagementApplicationService.StartupPolicy.AUTO_START
                                            ? ServicePluginManagementApplicationService.StartupPolicy.MANUAL
                                            : ServicePluginManagementApplicationService.StartupPolicy.AUTO_START)), false));
        }

        actions.getChildren().add(button("配置与日志",
                () -> openConfiguration.accept(plugin.id()), false));

        card.getChildren().addAll(header, description, metadata, resources, endpoints, actions);
        if (!plugin.lastError().isBlank()) {
            Label error = new Label("最近错误：" + plugin.lastError());
            error.setWrapText(true);
            error.getStyleClass().add("service-plugin-error");
            card.getChildren().add(error);
        }
        return card;
    }

    private void primaryAction(ServicePluginInfo plugin) {
        if (plugin.state() == ServicePluginManagementApplicationService.State.QUARANTINED) {
            mutate("service-plugin-unquarantine-" + plugin.id(), "服务插件已解除隔离",
                    () -> services.unquarantine(plugin.id()));
        } else if (isRunning(plugin)
                || plugin.state() == ServicePluginManagementApplicationService.State.STARTING) {
            mutate("service-plugin-stop-" + plugin.id(), "服务插件已停止",
                    () -> services.stop(plugin.id()));
        } else {
            mutate("service-plugin-start-" + plugin.id(), "服务插件已启动",
                    () -> services.start(plugin.id()));
        }
    }

    private static boolean isRunning(ServicePluginInfo plugin) {
        return plugin.state() == ServicePluginManagementApplicationService.State.HEALTHY
                || plugin.state() == ServicePluginManagementApplicationService.State.DEGRADED;
    }

    private static String primaryActionLabel(ServicePluginInfo plugin) {
        return switch (plugin.state()) {
            case QUARANTINED -> "解除隔离";
            case HEALTHY, DEGRADED, STARTING -> "停止";
            default -> "启动";
        };
    }

    private static String stateText(ServicePluginInfo plugin) {
        return switch (plugin.state()) {
            case INSTALLED -> "已安装";
            case STOPPED -> "已停止";
            case STARTING -> "启动中";
            case HEALTHY -> "健康";
            case DEGRADED -> "降级";
            case STOPPING -> "停止中";
            case FAILED -> "失败";
            case QUARANTINED -> "已隔离";
        };
    }

    private static String stateClass(ServicePluginInfo plugin) {
        return switch (plugin.state()) {
            case HEALTHY -> "jc-badge-ok";
            case STARTING, STOPPING, DEGRADED -> "jc-badge-amber";
            case FAILED, QUARANTINED -> "jc-badge-fail";
            default -> "jc-badge-stopped";
        };
    }

    private static String actualResourceSummary(ServicePluginInfo plugin) {
        Object heap = plugin.health().get("heapUsedMiB");
        Object threads = plugin.health().get("threadCount");
        if (!(heap instanceof Number used) || !(threads instanceof Number count)) return "";
        return " · 实际堆 " + used.longValue() + " MiB · " + count.intValue() + " 线程";
    }

    private static Button button(String text, Runnable action, boolean primary) {
        Button button = new Button(text);
        button.getStyleClass().addAll("jc-btn", primary ? "jc-btn-primary" : "jc-btn-soft", "jc-btn-sm");
        button.setOnAction(event -> action.run());
        return button;
    }

    private static Label hint(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("settings-hint");
        return label;
    }

    @Override
    public void close() {
        action.close();
    }
}
