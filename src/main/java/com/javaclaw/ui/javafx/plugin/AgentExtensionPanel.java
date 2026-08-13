package com.javaclaw.ui.javafx.plugin;

import com.javaclaw.api.interaction.ConfirmKind;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.application.plugin.AgentExtensionManagementApplicationService;
import com.javaclaw.application.plugin.AgentExtensionManagementApplicationService.AgentExtension;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Owns the Agent-extension tab's asynchronous lifecycle and rendering. */
final class AgentExtensionPanel implements AutoCloseable {
    private final AgentExtensionManagementApplicationService extensions;
    private final DialogService dialogs;
    private final UiAsyncAction<List<AgentExtension>> action;
    private final VBox root;
    private final VBox list;
    private final Label count;
    private final Button installButton;
    private final Consumer<String> showStatus;
    private final BiConsumer<String, Throwable> showFailure;
    private final AtomicBoolean closed = new AtomicBoolean();
    private List<AgentExtension> records = List.of();

    AgentExtensionPanel(
            AgentExtensionManagementApplicationService extensions,
            DialogService dialogs,
            ManagedTaskExecutor tasks,
            FxDispatcher fx,
            VBox root,
            VBox list,
            Label count,
            Button installButton,
            Consumer<String> showStatus,
            BiConsumer<String, Throwable> showFailure) {
        this.extensions = Objects.requireNonNull(extensions, "extensions");
        this.dialogs = Objects.requireNonNull(dialogs, "dialogs");
        this.root = Objects.requireNonNull(root, "root");
        this.list = Objects.requireNonNull(list, "list");
        this.count = Objects.requireNonNull(count, "count");
        this.installButton = Objects.requireNonNull(installButton, "installButton");
        this.showStatus = Objects.requireNonNull(showStatus, "showStatus");
        this.showFailure = Objects.requireNonNull(showFailure, "showFailure");
        action = new UiAsyncAction<>(tasks, fx);
        installButton.disableProperty().bind(action.busyProperty());
    }

    ReadOnlyBooleanProperty busyProperty() {
        return action.busyProperty();
    }

    void show() {
        root.setVisible(true);
        root.setManaged(true);
        refresh();
    }

    void hide() {
        root.setVisible(false);
        root.setManaged(false);
    }

    void refresh() {
        if (closed.get()) return;
        action.execute(
                TaskSpec.io("agent-extension-list"),
                context -> extensions.installed(),
                this::apply,
                failure -> showFailure.accept("刷新 Agent 扩展失败", failure));
    }

    void install(Path jar) {
        if (closed.get()) return;
        action.execute(
                TaskSpec.io("agent-extension-install"),
                context -> installAfterConfirmation(jar),
                installed -> {
                    if (installed == null) {
                        showStatus.accept("已取消 Agent 扩展安装");
                        return;
                    }
                    apply(installed);
                    showStatus.accept("Agent 扩展已安装并启用");
                },
                failure -> {
                    showFailure.accept("Agent 扩展安装失败", failure);
                    dialogs.notify(new ToastRequest(
                            "Agent 扩展安装失败", failureMessage(failure)));
                });
    }

    private List<AgentExtension> installAfterConfirmation(Path jar) {
        var preview = extensions.preview(jar);
        boolean confirmed = dialogs.confirm(new ConfirmRequest(
                "agent_extension_install", "宿主代码风险",
                "即将安装可在 JavaClaw 进程内执行的 Agent 扩展 JAR。\n"
                        + "文件: " + preview.path() + "\n"
                        + "大小: " + preview.sizeBytes() + " bytes\n"
                        + "SHA-256: " + preview.sha256() + "\n\n"
                        + "确认后才会加载并执行该 JAR 的 ServiceLoader 入口。",
                ConfirmKind.CONFIRM, 0, "", false)).isAllow();
        return confirmed ? extensions.install(preview) : null;
    }

    private void toggle(String extensionId, boolean enabled) {
        action.execute(
                TaskSpec.io("agent-extension-toggle-" + extensionId),
                context -> extensions.setEnabled(extensionId, enabled),
                installed -> {
                    apply(installed);
                    showStatus.accept(enabled
                            ? "Agent 扩展已启用（仅影响新 Run）"
                            : "Agent 扩展已停用（已锁定 Run 不受影响）");
                },
                failure -> {
                    showFailure.accept("Agent 扩展状态更新失败", failure);
                    refresh();
                });
    }

    private void apply(List<AgentExtension> installed) {
        records = List.copyOf(installed);
        render();
    }

    private void render() {
        list.getChildren().clear();
        count.setText(records.size() + " 个 Agent 扩展");
        if (records.isEmpty()) {
            Label empty = new Label("尚未安装外部 Agent 扩展");
            empty.getStyleClass().add("sec-hint");
            list.getChildren().add(empty);
            return;
        }
        records.forEach(extension -> list.getChildren().add(row(extension)));
    }

    private HBox row(AgentExtension extension) {
        Label identity = new Label(extension.id() + "  " + extension.versions());
        identity.getStyleClass().add("sec-title");
        Label detail = new Label("SHA-256  " + extension.artifactHashes());
        detail.getStyleClass().add("sec-hint");
        VBox labels = new VBox(4, identity, detail);
        HBox.setHgrow(labels, Priority.ALWAYS);
        boolean enabled = extension.enabledForNewRuns();
        Button toggle = new Button(enabled ? "停用" : "启用");
        toggle.getStyleClass().addAll("button", "jc-btn", "jc-btn-soft", "jc-btn-sm");
        toggle.setOnAction(event -> toggle(extension.id(), !enabled));
        HBox row = new HBox(12, labels, toggle);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("plugin-card");
        return row;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        installButton.disableProperty().unbind();
        action.close();
        records = List.of();
        list.getChildren().clear();
    }

    private static String failureMessage(Throwable failure) {
        return failure == null || failure.getMessage() == null
                ? "未知错误" : failure.getMessage();
    }
}
