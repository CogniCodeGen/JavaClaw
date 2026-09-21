package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.javaclaw.api.ProviderConfigurationResult;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.desktop.component.AsyncActionBar;
import com.javaclaw.desktop.component.AsyncActionBar.ActionState;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;

/**
 * 设置页内的单页配置编辑器；控件仅持有非敏感草稿，秘密与原子提交身份由工作流拥有。
 *
 * <p>目录反馈不能覆盖保存失败；提交未知时只允许查询原回执。销毁释放订阅及临时输入，不声称撤销已发送的写入。
 */
final class ProviderConfigurationEditor extends VBox implements AutoCloseable {
    private final ProviderSetupWorkflow workflow;
    private final ProviderSetupConnectionForm connection = new ProviderSetupConnectionForm();
    private final ProviderSetupModelForm models;
    private final TitledPane connectionSection;
    private final TitledPane modelSection;
    private final ScrollPane connectionScroll;
    private final CheckBox enabled = new CheckBox("保存后启用");
    private final Label diagnostic = new Label();
    private final TitledPane diagnostics;
    private final Button save;
    private final Button discard;
    private final Button reconnect;
    private final Button reload;
    private final AsyncActionBar actions;
    private final Consumer<ProviderConfigurationResult> completed;
    private Runnable changed = () -> {};
    private ActionState actionState = ActionState.IDLE;
    private String message = "";
    private boolean dirty;
    private boolean rendering;
    private boolean closed;
    private boolean failed;
    private boolean conflict;
    private boolean reconnecting;

    ProviderConfigurationEditor(
            CoreSettingsGateway gateway,
            Optional<ProviderEndpoint> source,
            long credentialRevision,
            Consumer<ProviderConfigurationResult> completed,
            Runnable discarded,
            Runnable reloaded) {
        super(8);
        setId("providerConfigurationEditor");
        setMinSize(0, 0);
        this.completed = completed;
        workflow = source.map(value -> new ProviderSetupWorkflow(gateway, value, credentialRevision))
                .orElseGet(() -> new ProviderSetupWorkflow(gateway));
        PlatformComponentFactory components = new PlatformComponentFactory();
        models = new ProviderSetupModelForm(components, this::discover);
        source.ifPresent(value -> {
            connection.seed(ProviderDraft.from(value));
            models.seed(value.spec().models());
        });
        enabled.setSelected(source.map(value -> value.lifecycle() == ProviderLifecycle.ACTIVE)
                .orElse(true));
        enabled.setId("providerWizardEnable");
        connectionScroll = connectionViewport();
        connectionSection = new TitledPane("连接设置", connectionScroll);
        connectionSection.setId("providerConnectionSection");
        connectionSection.setAnimated(false);
        connectionSection.setMaxHeight(Double.MAX_VALUE);
        connectionSection.setExpanded(source.isEmpty());
        connectionSection.setGraphic(enabled);
        connectionSection.setContentDisplay(ContentDisplay.RIGHT);
        diagnostic.setWrapText(true);
        modelSection = new TitledPane("模型管理", models);
        modelSection.setId("providerModelsSection");
        modelSection.setGraphic(models.selectionSummary());
        modelSection.setContentDisplay(ContentDisplay.RIGHT);
        modelSection.setAnimated(false);
        modelSection.setMaxHeight(Double.MAX_VALUE);
        modelSection.setExpanded(source.isPresent());
        modelSection.setMinHeight(0);
        diagnostics = new TitledPane("诊断分类", diagnostic);
        diagnostics.setExpanded(false);
        diagnostics.setAnimated(false);
        save = action(components, "保存配置", "providerConfigurationSave", ActionStyle.PRIMARY, this::save);
        discard = action(components, "放弃修改", "providerConfigurationDiscard", ActionStyle.GHOST, discarded);
        reconnect = action(components, "重新连接", "providerConfigurationReconnect", ActionStyle.SOFT, this::reconnect);
        reload = action(components, "重新读取", "providerConfigurationReload", ActionStyle.SOFT, reloaded);
        actions = new AsyncActionBar(reconnect, reload, discard, save);
        connection.getChildren().add(diagnostics);
        getChildren().addAll(connectionSection, modelSection);
        VBox.setVgrow(modelSection, Priority.ALWAYS);
        models.setMinHeight(0);
        bind();
        dirty = source.isEmpty();
        message = workflow.phase();
        render();
    }

    private ScrollPane connectionViewport() {
        ScrollPane scroll = new ScrollPane(connection);
        scroll.setId("providerConnectionScroll");
        scroll.setFitToWidth(true);
        scroll.setMinSize(0, 0);
        // 连接表单与模型列表互为兄弟视口，绝不把虚拟列表放进表单滚动容器。
        scroll.prefViewportHeightProperty().bind(heightProperty().multiply(0.42).add(20));
        scroll.setMaxHeight(Double.MAX_VALUE);
        return scroll;
    }

    private void bind() {
        bindSections();
        bindChanges();
        workflow.onProgress(this::progress);
    }

    private void bindSections() {
        connectionSection.expandedProperty().addListener((ignored, before, expanded) -> {
            if (expanded && getHeight() < 620) {
                modelSection.setExpanded(false);
            }
            VBox.setVgrow(connectionSection, expanded ? Priority.ALWAYS : Priority.NEVER);
            VBox.setVgrow(modelSection, modelSection.isExpanded() ? Priority.ALWAYS : Priority.NEVER);
        });
        modelSection.expandedProperty().addListener((ignored, before, expanded) -> {
            if (expanded && getHeight() < 620) {
                connectionSection.setExpanded(false);
            }
            VBox.setVgrow(modelSection, expanded ? Priority.ALWAYS : Priority.NEVER);
        });
        VBox.setVgrow(connectionSection, connectionSection.isExpanded() ? Priority.ALWAYS : Priority.NEVER);
        VBox.setVgrow(modelSection, modelSection.isExpanded() ? Priority.ALWAYS : Priority.NEVER);
        heightProperty().addListener((ignored, before, height) -> compactSections(height.doubleValue()));
    }

    private void compactSections(double height) {
        if (height < 620 && connectionSection.isExpanded() && modelSection.isExpanded()) {
            if (connectionHasFocus()) {
                modelSection.setExpanded(false);
            } else {
                connectionSection.setExpanded(false);
            }
        }
    }

    private boolean connectionHasFocus() {
        Node focus = getScene() == null ? null : getScene().getFocusOwner();
        while (focus != null) {
            if (focus == connectionSection) {
                return true;
            }
            focus = focus.getParent();
        }
        return false;
    }

    private void bindChanges() {
        connection.onChanged(() -> {
            if (!rendering) {
                workflow.cancelPreview();
                edited();
            }
        });
        connection.onDestinationChanged(() -> {
            workflow.connectionDestinationChanged();
            models.connectionChanged();
        });
        connection.onReplacementCancelled(workflow::connectionDestinationChanged);
        models.onChanged(this::edited);
        enabled.selectedProperty().addListener((ignored, before, value) -> edited());
    }

    private void progress(String value) {
        if (closed) {
            return;
        }
        if (!workflow.supported() && workflow.capabilityKnown()) {
            clearInput();
        }
        if (!failed || workflow.pending() || workflow.unknown() || !workflow.supported()) {
            message = value;
        }
        render();
    }

    Node actionContent() {
        return actions;
    }

    void onChanged(Runnable listener) {
        changed = listener;
    }

    boolean dirty() {
        return !closed && dirty;
    }

    boolean pending() {
        return !closed && (workflow.pending() || workflow.unknown());
    }

    void cancelPreview() {
        workflow.cancelPreview();
    }

    void warnUnsavedChanges() {
        if (!pending()) {
            message = "请先保存或放弃模型服务草稿。";
            actionState = ActionState.DIRTY;
            render();
        }
    }

    private void edited() {
        if (rendering || closed) {
            return;
        }
        dirty = true;
        failed = false;
        diagnostic.setText("");
        message = "尚未保存；连接和模型将一次保存。";
        actionState = ActionState.DIRTY;
        render();
    }

    private CompletionStage<Void> prepareConnection() {
        rendering = true;
        try {
            var draft = connection.draft();
            return workflow.connect(
                            draft,
                            connection.takeSecret(),
                            connection.replacementRequested(),
                            connection.clearingConfirmed())
                    .thenRun(connection::prepared);
        } finally {
            rendering = false;
        }
    }

    private boolean validateConnection() {
        if (!connection.validate(workflow.hasPreparedSecret())) {
            revealConnection();
            connection.validate(workflow.hasPreparedSecret());
            scrollToFocus();
            if (!failed) {
                message = "请检查连接设置中标出的字段。";
                actionState = ActionState.ERROR;
            }
            render();
            return false;
        }
        return true;
    }

    private void discover() {
        if (pending() || closed || !validateConnection()) {
            return;
        }
        try {
            prepareConnection().thenCompose(ignored -> workflow.discover()).whenComplete((result, failure) -> {
                if (closed || pending()) {
                    return;
                }
                if (failure == null) {
                    models.candidates(result.candidates());
                    connectionSection.setExpanded(false);
                    modelSection.setExpanded(true);
                    if (!failed) {
                        message = result.truncated() ? "已获取目录，结果已截断；可手动补充。" : "已获取目录，请选择要保存的模型；尚未进行调用验证。";
                        actionState = ActionState.IDLE;
                    }
                } else if (!(SettingsFailures.unwrap(failure) instanceof java.util.concurrent.CancellationException)
                        && !failed) {
                    message = ProviderPreviewMessages.describe(failure);
                    actionState = ActionState.ERROR;
                }
                render();
            });
        } catch (RuntimeException failure) {
            fail(failure);
        }
    }

    private void save() {
        if (closed || workflow.pending()) {
            return;
        }
        if (workflow.unknown()) {
            checkResult();
            return;
        }
        if (!validateConnection()) {
            return;
        }
        List<ProviderModelSpec> selected;
        try {
            selected = models.selectedModels();
            if (selected.isEmpty()) {
                throw new IllegalArgumentException("empty selection");
            }
        } catch (IllegalArgumentException invalid) {
            modelSection.setExpanded(true);
            applyCss();
            layout();
            models.focusFirstInvalid();
            message = "请至少选择一个模型并指定用途；向量维度须为正整数。";
            actionState = ActionState.ERROR;
            render();
            return;
        }
        failed = false;
        diagnostic.setText("");
        try {
            prepareConnection()
                    .thenCompose(ignored -> workflow.save(selected, enabled.isSelected()))
                    .whenComplete((result, failure) -> {
                        if (closed) {
                            return;
                        }
                        if (failure == null) {
                            finish(result);
                        } else {
                            fail(failure);
                            if (workflow.unknown()) {
                                checkResult();
                            }
                        }
                    });
        } catch (RuntimeException failure) {
            fail(failure);
        }
    }

    private void checkResult() {
        workflow.checkResult().whenComplete((result, failure) -> {
            if (closed) {
                return;
            }
            if (failure == null && result.isPresent()) {
                finish(result.orElseThrow());
            } else {
                message = "保存结果仍待确认，请查询原回执。请勿重复提交。";
                render();
            }
        });
    }

    private void finish(ProviderConfigurationResult result) {
        dirty = false;
        clearInput();
        completed.accept(result);
    }

    private void fail(Throwable failure) {
        failed = true;
        conflict = SettingsFailures.revisionConflict(failure);
        diagnostic.setText(ProviderConfigurationFailures.diagnostic(failure));
        message = workflow.unknown() ? workflow.phase() : ProviderConfigurationFailures.save(failure);
        actionState = ActionState.ERROR;
        if (workflow.needsSecretInput()) {
            clearInput();
            message += " 临时密钥已清除，请重新输入。";
            revealConnection();
            rendering = true;
            try {
                connection.focusSecret();
            } finally {
                rendering = false;
            }
            scrollToFocus();
        }
        render();
    }

    private void reconnect() {
        reconnecting = true;
        render();
        workflow.reconnect().whenComplete((ignored, failure) -> {
            reconnecting = false;
            if (closed) {
                return;
            }
            if (workflow.supported()) {
                connectionSection.setExpanded(true);
            }
            render();
        });
    }

    private void render() {
        boolean locked = pending();
        connection.setDisable(locked);
        models.setDisable(locked);
        enabled.setDisable(locked);
        save.setText(workflow.unknown() ? "查询保存结果" : "保存配置");
        save.setDisable(workflow.pending() || !workflow.unknown() && (!dirty || !workflow.supported()));
        discard.setDisable(locked);
        reconnect.setDisable(workflow.pending() || reconnecting);
        visible(
                reconnect,
                workflow.capability() == ProviderConfigurationCapability.DISCONNECTED
                        || workflow.capability() == ProviderConfigurationCapability.FAILED);
        visible(reload, conflict);
        reload.setDisable(locked);
        visible(diagnostics, !diagnostic.getText().isEmpty());
        String displayed = !workflow.supported() && !workflow.unknown()
                ? workflow.capability().message()
                : message;
        actions.show(
                workflow.pending() || workflow.previewing() || reconnecting ? ActionState.PENDING : actionState,
                displayed);
        changed.run();
    }

    private void revealConnection() {
        connectionSection.setExpanded(true);
        applyCss();
        layout();
    }

    private void scrollToFocus() {
        if (getScene() == null || getScene().getFocusOwner() == null) {
            return;
        }
        Node focused = getScene().getFocusOwner();
        var bounds = connection.sceneToLocal(focused.localToScene(focused.getBoundsInLocal()));
        double available =
                connection.getHeight() - connectionScroll.getViewportBounds().getHeight();
        if (available > 0) {
            connectionScroll.setVvalue(Math.max(0, Math.min(1, bounds.getMinY() / available)));
        }
    }

    private void clearInput() {
        rendering = true;
        try {
            connection.clearSecret();
        } finally {
            rendering = false;
        }
    }

    @Override
    public void close() {
        closed = true;
        clearInput();
        workflow.close();
    }

    private static Button action(
            PlatformComponentFactory components, String text, String id, ActionStyle style, Runnable action) {
        Button button = components.action(text, style, ActionSize.COMPACT);
        button.setId(id);
        button.setOnAction(ignored -> action.run());
        return button;
    }

    private static void visible(Node node, boolean value) {
        node.setVisible(value);
        node.setManaged(value);
    }
}
