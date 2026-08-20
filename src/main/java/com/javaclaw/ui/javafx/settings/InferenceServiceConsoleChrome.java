package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.State;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.Objects;

/** Process actions, responsive layout, log collapse, and the service-settings drawer. */
final class InferenceServiceConsoleChrome implements AutoCloseable {
    private final StackPane root;
    private final SplitPane modelSplit;
    private final VBox drawer;
    private final VBox runtimeSettings;
    private final Region scrim;
    private final TextArea logs;
    private final Button logCollapse;
    private final Button processPrimary;
    private final Button processRestart;
    private final Label processLabel;
    private final Label errorLabel;
    private final ChangeListener<Number> widthListener =
            (ignored, previous, width) -> responsive(width.doubleValue());
    private InferencePluginConfigurationFactory.RuntimeControls controls =
            InferencePluginConfigurationFactory.RuntimeControls.none();
    private State state = State.STOPPED;
    private boolean logsExpanded = true;

    InferenceServiceConsoleChrome(
            StackPane root, SplitPane modelSplit, VBox drawer, VBox runtimeSettings,
            Region scrim, TextArea logs, Button logCollapse, Button processPrimary,
            Button processRestart, Label processLabel, Label errorLabel) {
        this.root = Objects.requireNonNull(root, "root");
        this.modelSplit = Objects.requireNonNull(modelSplit, "modelSplit");
        this.drawer = Objects.requireNonNull(drawer, "drawer");
        this.runtimeSettings = Objects.requireNonNull(runtimeSettings, "runtimeSettings");
        this.scrim = Objects.requireNonNull(scrim, "scrim");
        this.logs = Objects.requireNonNull(logs, "logs");
        this.logCollapse = Objects.requireNonNull(logCollapse, "logCollapse");
        this.processPrimary = Objects.requireNonNull(processPrimary, "processPrimary");
        this.processRestart = Objects.requireNonNull(processRestart, "processRestart");
        this.processLabel = Objects.requireNonNull(processLabel, "processLabel");
        this.errorLabel = Objects.requireNonNull(errorLabel, "errorLabel");
        root.widthProperty().addListener(widthListener);
        responsive(root.getWidth());
    }

    void configure(
            InferenceApiSettingsController.PluginPresentation presentation,
            InferencePluginConfigurationFactory.RuntimeControls runtimeControls) {
        state = presentation.state();
        controls = Objects.requireNonNull(runtimeControls, "runtimeControls");
        Node settings = controls.settings();
        runtimeSettings.getChildren().setAll(settings);
        renderProcess(presentation.pid(), presentation.lastError());
    }

    void updateProcess(String processState, long pid, int activeRequests) {
        String suffix = activeRequests > 0 ? " · " + activeRequests + " 个请求" : "";
        processLabel.setText("插件 " + processStateText(processState)
                + (pid > 0 ? " · PID " + pid : "") + suffix);
    }

    void processPrimary() {
        switch (state) {
            case QUARANTINED -> controls.unquarantine().run();
            case HEALTHY, DEGRADED, STARTING -> controls.stop().run();
            default -> controls.start().run();
        }
    }

    void restart() { controls.restart().run(); }

    void openDrawer() {
        show(scrim, true);
        show(drawer, true);
        drawer.toFront();
    }

    void closeDrawer() {
        show(drawer, false);
        show(scrim, false);
    }

    void toggleLogs() {
        logsExpanded = !logsExpanded;
        show(logs, logsExpanded);
        logCollapse.setText(logsExpanded ? "折叠" : "展开");
    }

    private void renderProcess(long pid, String lastError) {
        boolean running = state == State.HEALTHY || state == State.DEGRADED;
        processLabel.setText(stateText(state) + (pid > 0 ? " · PID " + pid : ""));
        processLabel.getStyleClass().setAll("jc-badge",
                running ? "jc-badge-ok" : "jc-badge-stopped");
        processPrimary.setText(switch (state) {
            case QUARANTINED -> "解除隔离";
            case HEALTHY, DEGRADED, STARTING -> "停止";
            default -> "启动";
        });
        show(processRestart, running);
        boolean failed = lastError != null && !lastError.isBlank();
        errorLabel.setText(failed ? "最近错误：" + lastError : "");
        show(errorLabel, failed);
    }

    private void responsive(double width) {
        boolean narrow = width > 0 && width < 900;
        modelSplit.setOrientation(narrow ? Orientation.VERTICAL : Orientation.HORIZONTAL);
        modelSplit.setPrefHeight(narrow ? 520 : 270);
        modelSplit.setMaxHeight(narrow ? 620 : 330);
        for (Node item : modelSplit.getItems()) {
            if (item instanceof Region region) region.setMinWidth(narrow ? 0 : 300);
        }
        drawer.setMaxWidth(narrow ? Double.MAX_VALUE : 460);
        drawer.setPrefWidth(narrow ? Math.max(320, width) : 430);
    }

    private static String stateText(State state) {
        return switch (state) {
            case HEALTHY -> "● 进程运行中";
            case DEGRADED -> "● 进程降级运行";
            case STARTING -> "进程启动中";
            case STOPPING -> "进程停止中";
            case FAILED -> "进程启动失败";
            case QUARANTINED -> "进程已隔离";
            default -> "进程已停止";
        };
    }

    private static String processStateText(String value) {
        if (value == null) return "已停止";
        return switch (value.strip().toUpperCase(java.util.Locale.ROOT)) {
            case "HEALTHY", "RUNNING" -> "运行中";
            case "DEGRADED" -> "降级运行";
            case "STARTING" -> "启动中";
            case "STOPPING" -> "停止中";
            case "FAILED" -> "启动失败";
            case "QUARANTINED" -> "已隔离";
            default -> "已停止";
        };
    }

    private static void show(Node node, boolean value) {
        node.setVisible(value);
        node.setManaged(value);
    }

    @Override public void close() {
        root.widthProperty().removeListener(widthListener);
        runtimeSettings.getChildren().clear();
        closeDrawer();
    }
}
