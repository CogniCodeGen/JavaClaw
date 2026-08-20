package com.javaclaw.ui.javafx.plugin;

import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.ResourceConfiguration;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.ServicePluginInfo;
import com.javaclaw.application.serviceplugin.DeliveranceResourceRecommendations;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Objects;
import java.util.function.Consumer;

/** Host-owned process controls rendered either as the compatibility page or an embedded section. */
final class ServicePluginConfigurationPane implements AutoCloseable {
    enum RuntimeAction { START, STOP, RESTART, UNQUARANTINE }
    enum Mode { FULL_PAGE, EMBEDDED_SERVICE, SETTINGS_DRAWER }

    private final ServicePluginInfo plugin;
    private final Consumer<ResourceConfiguration> save;
    private final Consumer<RuntimeAction> runtime;
    private final Mode mode;
    private final VBox root = new VBox(12);
    private final TextField heap;
    private final TextField nativeMemory;
    private final TextField computeThreads;
    private final TextField ioConcurrency;
    private final TextField fileDescriptors;
    private final Label validation = new Label();
    private TextArea logs;

    ServicePluginConfigurationPane(
            ServicePluginInfo plugin,
            Consumer<ResourceConfiguration> save,
            Consumer<RuntimeAction> runtime) {
        this(plugin, save, runtime, Mode.FULL_PAGE);
    }

    ServicePluginConfigurationPane(
            ServicePluginInfo plugin,
            Consumer<ResourceConfiguration> save,
            Consumer<RuntimeAction> runtime,
            Mode mode) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.save = Objects.requireNonNull(save, "save");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.mode = Objects.requireNonNull(mode, "mode");
        ResourceConfiguration value = plugin.resources();
        heap = field(Integer.toString(value.heapMiB()), "serviceHeapMiBField");
        nativeMemory = field(Integer.toString(value.nativeMemoryMiB()), "serviceNativeMemoryMiBField");
        computeThreads = field(Integer.toString(value.computeThreads()), "serviceComputeThreadsField");
        ioConcurrency = field(Integer.toString(value.ioConcurrency()), "serviceIoConcurrencyField");
        fileDescriptors = field(Integer.toString(value.fileDescriptors()), "serviceFileDescriptorsField");
        build();
    }

    VBox root() { return root; }

    private void build() {
        root.setId(mode == Mode.FULL_PAGE
                ? "servicePluginRuntimePage" : "servicePluginRuntimeSection");
        root.getStyleClass().add("service-plugin-runtime");
        validation.setWrapText(true);
        validation.getStyleClass().add("service-plugin-error");
        validation.setVisible(false);
        validation.setManaged(false);
        Node footer = resourceFooter();
        if (mode == Mode.SETTINGS_DRAWER) {
            root.getChildren().addAll(section("进程与资源"),
                    hint("保存后，运行中的插件将安全重启；停止状态只保存配置。"),
                    resourceFields(), footer);
            return;
        }
        if (mode == Mode.EMBEDDED_SERVICE) {
            VBox content = new VBox(10, resourceFields(), footer);
            TitledPane disclosure = new TitledPane("进程与资源", content);
            disclosure.setId("servicePluginRuntimeDisclosure");
            disclosure.setExpanded(false);
            disclosure.setAnimated(false);
            disclosure.getStyleClass().add("service-plugin-disclosure");
            root.getChildren().addAll(runtimeSummary(), runtimeActions(), diagnosticSummary(),
                    disclosure);
            return;
        }
        root.getChildren().addAll(section("运行状态"), runtimeSummary(), runtimeActions(),
                section("进程资源"), resourceFields(), footer,
                section("错误与日志"), diagnostics());
    }

    private Node resourceFooter() {
        Button saveButton = button(isRunning(plugin) ? "保存并重启" : "保存资源", true,
                this::saveResources);
        saveButton.setId("servicePluginSaveResources");
        HBox footer = new HBox(8, validation, spacer());
        if ("builtin-deliverance".equals(plugin.id())) {
            Button recommended = button("恢复本机推荐值", false, this::restoreRecommendedResources);
            recommended.setId("servicePluginRestoreRecommendedResources");
            footer.getChildren().add(recommended);
        }
        footer.getChildren().add(saveButton);
        footer.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(validation, Priority.ALWAYS);
        return footer;
    }

    private Node runtimeSummary() {
        String process = plugin.pid() > 0
                ? "PID " + plugin.pid() + " · 启动于 " + plugin.processStartedAt()
                : "当前没有子进程";
        Label label = hint(stateText(plugin) + " · " + process + " · 活动/排队请求 "
                + plugin.activeRequests() + "/" + plugin.queuedRequests());
        label.setId("servicePluginRuntimeSummary");
        return label;
    }

    private Node runtimeActions() {
        HBox actions = new HBox(8);
        actions.getStyleClass().add("service-plugin-actions");
        if (plugin.state() == com.javaclaw.application.serviceplugin
                .ServicePluginManagementApplicationService.State.QUARANTINED) {
            actions.getChildren().add(runtimeButton("解除隔离", "servicePluginRuntimeUnquarantine",
                    true, RuntimeAction.UNQUARANTINE));
        } else if (isRunning(plugin)
                || plugin.state() == com.javaclaw.application.serviceplugin
                .ServicePluginManagementApplicationService.State.STARTING) {
            actions.getChildren().add(runtimeButton("停止", "servicePluginRuntimeStop",
                    true, RuntimeAction.STOP));
            if (isRunning(plugin)) actions.getChildren().add(runtimeButton(
                    "重启", "servicePluginRuntimeRestart", false, RuntimeAction.RESTART));
        } else {
            actions.getChildren().add(runtimeButton("启动", "servicePluginRuntimeStart",
                    true, RuntimeAction.START));
        }
        return actions;
    }

    private Button runtimeButton(
            String text, String id, boolean primary, RuntimeAction action) {
        Button button = button(text, primary, () -> runtime.accept(action));
        button.setId(id);
        return button;
    }

    private Node resourceFields() {
        FlowPane fields = new FlowPane(10, 8,
                fieldBox("最大堆（MiB）", heap), fieldBox("Native 内存（MiB）", nativeMemory),
                fieldBox("计算线程", computeThreads), fieldBox("I/O 并发", ioConcurrency),
                fieldBox("文件描述符", fileDescriptors));
        fields.setPrefWrapLength(720);
        return fields;
    }

    private Node diagnostics() {
        VBox box = new VBox(8);
        box.getChildren().add(diagnosticSummary());
        if (!plugin.recentCrashes().isEmpty()) {
            box.getChildren().add(hint("最近崩溃：" + plugin.recentCrashes().stream()
                    .map(java.time.Instant::toString).collect(
                            java.util.stream.Collectors.joining(" · "))));
        }
        if (plugin.recentLogs().isEmpty()) {
            box.getChildren().add(hint("暂无运行日志"));
        } else {
            logs = new TextArea(String.join(System.lineSeparator(), plugin.recentLogs()));
            logs.setEditable(false);
            logs.setWrapText(false);
            logs.setPrefRowCount(Math.min(18, Math.max(6, plugin.recentLogs().size())));
            logs.getStyleClass().addAll("settings-field", "service-plugin-log");
            box.getChildren().add(logs);
        }
        return box;
    }

    private Node diagnosticSummary() {
        String error = plugin.lastError().isBlank()
                ? "异常重启 " + plugin.restartCount() + " 次"
                : "最近错误：" + plugin.lastError();
        Label label = new Label(error);
        label.setWrapText(true);
        label.getStyleClass().add(plugin.lastError().isBlank()
                ? "settings-hint" : "service-plugin-error");
        return label;
    }

    private void saveResources() {
        try {
            ResourceConfiguration resources = new ResourceConfiguration(
                    positiveInt(heap, "最大堆", 256),
                    positiveInt(nativeMemory, "Native 内存", 0),
                    positiveInt(computeThreads, "计算线程", 1),
                    positiveInt(ioConcurrency, "I/O 并发", 1),
                    positiveInt(fileDescriptors, "文件描述符", 64));
            validation.setVisible(false);
            validation.setManaged(false);
            save.accept(resources);
        } catch (RuntimeException invalid) {
            validation.setText(invalid.getMessage() == null ? "配置无效" : invalid.getMessage());
            validation.setVisible(true);
            validation.setManaged(true);
        }
    }

    private void restoreRecommendedResources() {
        ResourceConfiguration value = DeliveranceResourceRecommendations.balanced();
        heap.setText(Integer.toString(value.heapMiB()));
        nativeMemory.setText(Integer.toString(value.nativeMemoryMiB()));
        computeThreads.setText(Integer.toString(value.computeThreads()));
        ioConcurrency.setText(Integer.toString(value.ioConcurrency()));
        fileDescriptors.setText(Integer.toString(value.fileDescriptors()));
        validation.setVisible(false);
        validation.setManaged(false);
    }

    private static TextField field(String value, String id) {
        TextField field = new TextField(value);
        field.setId(id);
        field.getStyleClass().add("settings-field");
        return field;
    }

    private static VBox fieldBox(String label, Node control) {
        Label title = hint(label);
        VBox box = new VBox(4, title, control);
        box.getStyleClass().add("service-plugin-field");
        return box;
    }

    private static Label section(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("settings-group-title");
        return label;
    }

    private static Label hint(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("settings-hint");
        return label;
    }

    private static Button button(String text, boolean primary, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().addAll("jc-btn", primary ? "jc-btn-primary" : "jc-btn-soft", "jc-btn-sm");
        button.setOnAction(ignored -> action.run());
        return button;
    }

    private static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    private static int positiveInt(TextField field, String label, int minimum) {
        try {
            long value = Long.parseLong(field.getText().strip());
            if (value < minimum || value > Integer.MAX_VALUE) throw new NumberFormatException();
            return (int) value;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(label + "必须是大于等于 " + minimum + " 的整数");
        }
    }

    private static boolean isRunning(ServicePluginInfo plugin) {
        return plugin.state() == com.javaclaw.application.serviceplugin
                .ServicePluginManagementApplicationService.State.HEALTHY
                || plugin.state() == com.javaclaw.application.serviceplugin
                .ServicePluginManagementApplicationService.State.DEGRADED;
    }

    private static String stateText(ServicePluginInfo plugin) {
        return switch (plugin.state()) {
            case INSTALLED -> "已安装";
            case STOPPED -> "已停止";
            case STARTING -> "启动中";
            case HEALTHY -> "运行正常";
            case DEGRADED -> "降级运行";
            case STOPPING -> "停止中";
            case FAILED -> "启动失败";
            case QUARANTINED -> "已隔离";
        };
    }

    @Override
    public void close() {
        if (logs != null) logs.clear();
        validation.setText("");
    }
}
