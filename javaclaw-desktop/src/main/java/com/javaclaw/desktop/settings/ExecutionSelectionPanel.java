package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;

import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ExecutionConfiguration;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceLifecycle;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.desktop.component.PlatformDialogs;
import com.javaclaw.desktop.component.RevisionConflictPane;
import com.javaclaw.protocol.CanonicalJson;

/**
 * SDK 驱动的独立执行选择和配置来源面板。
 *
 * <p>相同作用域的渲染不触发读取；主动刷新完整成功后才替换基线。epoch 隔离跨作用域的旧响应， 保存冲突始终保留草稿及其原 revision，只有用户明确丢弃后才能采用服务端新版本。
 */
public final class ExecutionSelectionPanel extends VBox {
    private final CoreSettingsGateway gateway;
    private final ExecutionSelectionLoader loader;
    private final ExecutionSelectionControl choices = new ExecutionSelectionControl();
    private final Label status = new Label();
    private final Button refresh;
    private final RevisionConflictPane conflict;
    private ExecutionSelectionLoader.Scope scope =
            new ExecutionSelectionLoader.Scope(Optional.empty(), Optional.empty(), false);
    private ExecutionOverrides baseline = ExecutionOverrides.empty();
    private Runnable listener = () -> {};
    private boolean pending;
    private boolean loaded;
    private boolean conflicted;
    private boolean creating;
    private long revision;
    private long epoch;

    /** @param gateway 所有目录与配置只通过 SDK 读取，异步完成由网关调度到 JavaFX 线程 */
    public ExecutionSelectionPanel(CoreSettingsGateway gateway) {
        super(4);
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        loader = new ExecutionSelectionLoader(gateway);
        status.setWrapText(true);
        status.getStyleClass().add("sec-hint");
        refresh = new PlatformComponentFactory().action("刷新配置", ActionStyle.GHOST, ActionSize.COMPACT);
        refresh.setId("executionRefresh");
        refresh.setOnAction(event -> refresh());
        conflict = new RevisionConflictPane(this::confirmReload, this::compare);
        conflict.setId("executionConflict");
        HBox footer = new HBox(8, status, refresh);
        HBox.setHgrow(status, Priority.ALWAYS);
        getChildren().addAll(choices, footer, conflict);
        choices.onChanged(ignored -> changed());
        changed();
    }

    /**
     * 绑定固定编辑作用域；作用域相同时只更新登记信息，作用域改变时废弃旧请求和选择。
     *
     * @param workspace 固定 Workspace
     * @param thread 可选 Thread
     * @param defaults 是否编辑 Workspace 直接默认配置
     */
    public void bind(Optional<Workspace> workspace, Optional<ConversationThread> thread, boolean defaults) {
        ExecutionSelectionLoader.Scope next = new ExecutionSelectionLoader.Scope(workspace, thread, defaults);
        boolean same = scope.sameBinding(next) && epoch > 0;
        scope = next;
        if (same) {
            return;
        }
        creating = false;
        resetBinding();
        reload();
    }

    /** 读取创建向导目录，首次成功加载时选择通用 default。 */
    public void prepareWorkspaceCreation() {
        creating = true;
        resetBinding();
        reload();
    }

    /** 主动刷新当前作用域；默认配置的未保存草稿保持原样，输入区的临时选择跨刷新保留。 */
    public void refresh() {
        if (pending) {
            return;
        }
        if (dirty()) {
            status.setText("请先保存或放弃执行配置草稿，再刷新配置。");
            return;
        }
        reload();
    }

    /** @param callback 加载、保存或选择变化时的通知；注册后立即通知当前状态 */
    public void onStateChanged(Runnable callback) {
        listener = Objects.requireNonNull(callback, "callback");
        listener.run();
    }

    /** @return 新 Turn 或 Workspace 显式执行选择 */
    public ExecutionOverrides execution() {
        return choices.value();
    }

    /** @return 是否有尚未保存的 Workspace 默认草稿 */
    public boolean dirty() {
        return scope.defaults() && !baseline.equals(choices.value());
    }

    /** @return 是否存在读取、比较或保存请求 */
    public boolean pending() {
        return pending;
    }

    /** @return 当前作用域是否已有完整加载的展示数据；初次失败为 false */
    public boolean ready() {
        return loaded;
    }

    /** @return 已加载且无冲突、无在途请求的活动 Workspace 草稿是否可以保存 */
    public boolean canSave() {
        return loaded
                && !pending
                && !conflicted
                && dirty()
                && scope.workspace()
                        .filter(value -> value.lifecycle() == WorkspaceLifecycle.ACTIVE)
                        .isPresent();
    }

    /** 无在途请求时丢弃选择，恢复最近成功读取的基线；冲突仍需重新读取权威版本。 */
    public void discard() {
        if (!pending) {
            choices.setValue(baseline);
            changed();
        }
    }

    /** 保存当前 Workspace 直接默认配置，使用草稿所属 revision，保留未编辑的隐藏限制。 */
    public void save() {
        if (!canSave()) {
            return;
        }
        Workspace selected = scope.workspace().orElseThrow();
        ExecutionOverrides submitted = choices.value();
        long request = begin("正在保存独立执行配置…");
        gateway.updateExecutionDefaults(Optional.of(selected.id()), submitted, CommandOptions.create(revision))
                .whenComplete((saved, failure) -> completeSave(request, saved, failure));
    }

    private void completeSave(long request, ExecutionConfiguration saved, Throwable failure) {
        if (request != epoch) {
            return;
        }
        pending = false;
        if (failure != null) {
            status.setText(SettingsFailures.message(failure));
            if (SettingsFailures.revisionConflict(failure)) {
                conflicted = true;
                conflict.showUnknownActual(revision);
            }
        } else {
            revision = saved.revision();
            baseline = saved.overrides();
            choices.setValue(baseline);
            status.setText("已保存版本 " + revision + "；只影响新任务");
            conflict.hide();
        }
        changed();
    }

    private void resetBinding() {
        loaded = false;
        conflicted = false;
        baseline = ExecutionOverrides.empty();
        revision = 0;
        choices.setValue(baseline);
        choices.showInheritedRole(Optional.empty());
        conflict.hide();
    }

    private void reload() {
        ExecutionSelectionLoader.Scope requestedScope = scope;
        ExecutionOverrides retained = choices.value();
        boolean chooseDefault = creating && !loaded;
        long request = begin("正在读取 Agent、模型、权限及继承来源…");
        loader.load(requestedScope).whenComplete((snapshot, failure) -> {
            if (request != epoch) {
                return;
            }
            pending = false;
            if (failure != null) {
                status.setText(SettingsFailures.message(failure));
            } else {
                apply(snapshot, retained, chooseDefault);
            }
            changed();
        });
    }

    private void apply(ExecutionSelectionLoader.Snapshot snapshot, ExecutionOverrides retained, boolean chooseDefault) {
        var catalog = snapshot.catalog();
        Optional<ExecutionConfiguration> current = snapshot.sources().workspace();
        baseline = scope.defaults()
                ? current.map(ExecutionConfiguration::overrides).orElseGet(ExecutionOverrides::empty)
                : ExecutionOverrides.empty();
        revision = current.map(ExecutionConfiguration::revision).orElse(0L);
        choices.setCatalog(catalog.roles(), catalog.providers(), catalog.permissions());
        choices.setValue(scope.defaults() ? baseline : retained);
        if (chooseDefault) {
            choices.selectDefaultRole();
        }
        choices.showSource(snapshot.sources().description());
        choices.showInheritedRole(snapshot.inheritedRole());
        loaded = true;
        conflicted = false;
        conflict.hide();
        status.setText("Agent 固定模型优先；有效权限和预算由服务端在新任务开始时计算。");
    }

    private void confirmReload() {
        if (pending) {
            return;
        }
        long request = epoch;
        Alert dialog = new Alert(
                Alert.AlertType.CONFIRMATION, "重新读取会放弃当前执行配置草稿，并采用服务端最新版本。", ButtonType.CANCEL, ButtonType.OK);
        dialog.setTitle("重新读取执行配置");
        dialog.setHeaderText("确认放弃本地草稿");
        PlatformDialogs.style(dialog, this);
        if (dialog.showAndWait().filter(ButtonType.OK::equals).isPresent() && request == epoch && !pending) {
            reload();
        }
    }

    private void compare() {
        if (pending || scope.workspace().isEmpty()) {
            return;
        }
        ExecutionOverrides local = choices.value();
        long localRevision = revision;
        long request = begin("正在读取服务端版本以比较…");
        gateway.executionDefaults(scope.workspace().map(Workspace::id)).whenComplete((remote, failure) -> {
            if (request != epoch) {
                return;
            }
            pending = false;
            if (failure != null) {
                status.setText(SettingsFailures.message(failure));
            } else {
                showComparison(local, localRevision, remote);
            }
            changed();
        });
    }

    private void showComparison(ExecutionOverrides local, long localRevision, Optional<ExecutionConfiguration> remote) {
        CanonicalJson json = new CanonicalJson();
        TextArea content = new TextArea("本地草稿 · r" + localRevision + "\n"
                + json.encode(local).json()
                + "\n\n服务端 · r" + remote.map(ExecutionConfiguration::revision).orElse(0L) + "\n"
                + json.encode(remote.map(ExecutionConfiguration::overrides).orElseGet(ExecutionOverrides::empty))
                        .json());
        content.setEditable(false);
        content.setWrapText(true);
        Alert dialog = new Alert(Alert.AlertType.INFORMATION, "", ButtonType.CLOSE);
        dialog.setTitle("比较执行配置");
        dialog.setHeaderText("只读比较，不改变草稿及其版本");
        dialog.getDialogPane().setContent(content);
        PlatformDialogs.style(dialog, this);
        dialog.showAndWait();
    }

    private long begin(String message) {
        long request = ++epoch;
        pending = true;
        status.setText(message);
        changed();
        return request;
    }

    private void changed() {
        choices.setDisable(pending || !loaded);
        refresh.setDisable(pending);
        conflict.setDisable(pending);
        listener.run();
    }
}
