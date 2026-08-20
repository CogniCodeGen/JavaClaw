package com.javaclaw.ui.javafx.plugin;

import com.javaclaw.api.interaction.ConfirmDecision;
import com.javaclaw.api.interaction.ConfirmKind;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.application.plugin.PluginManagementApplicationService;
import com.javaclaw.application.plugin.PluginManagementApplicationService.Catalog;
import com.javaclaw.application.plugin.PluginManagementApplicationService.Details;
import com.javaclaw.application.plugin.PluginManagementApplicationService.NamedItem;
import com.javaclaw.application.plugin.PluginManagementApplicationService.Plugin;
import com.javaclaw.platform.desktop.ExternalDirectoryOpener;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import com.javaclaw.ui.javafx.control.ToggleSwitch;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 插件详情 FXML Controller：协调详情查询、配置、启停和卸载。 */
public final class PluginDetailController implements AutoCloseable {

    @FXML private ScrollPane root;
    @FXML private VBox detailContent;
    @FXML private Label glyphLabel;
    @FXML private Label nameLabel;
    @FXML private Label stateBadge;
    @FXML private Label metadataLabel;
    @FXML private Label stateTextLabel;
    @FXML private Button approvalButton;
    @FXML private ToggleSwitch enabledToggle;
    @FXML private Label descriptionLabel;
    @FXML private VBox permissionsBox;
    @FXML private Label permissionEmptyLabel;
    @FXML private VBox skillsSection;
    @FXML private Label skillsTitle;
    @FXML private VBox skillsItems;
    @FXML private VBox toolsSection;
    @FXML private Label toolsTitle;
    @FXML private VBox toolsItems;
    @FXML private Label exposureHint;
    @FXML private VBox configSection;
    @FXML private VBox configFields;
    @FXML private Button saveConfigButton;
    @FXML private Label configSavedLabel;
    @FXML private Label failureLabel;
    @FXML private Label statusLabel;
    @FXML private StackPane loadingOverlay;

    private final PluginManagementApplicationService useCases;
    private final DialogService dialogs;
    private final ExternalDirectoryOpener directoryOpener;
    private final PluginComponentFactory components;
    private final UiAsyncAction<Details> detailsAction;
    private final UiAsyncAction<Catalog> mutationAction;
    private final UiAsyncAction<Void> configAction;
    private final List<AutoCloseable> dynamicViews = new ArrayList<>();
    private final List<PluginConfigFieldView> configViews = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private Runnable onBack = () -> {};
    private Consumer<Catalog> onCatalogChanged = ignored -> {};
    private Consumer<String> onApproval = ignored -> {};
    private String pluginId;
    private Plugin plugin;
    private boolean configuringToggle;

    public PluginDetailController(
            PluginManagementApplicationService useCases,
            DialogService dialogs,
            ExternalDirectoryOpener directoryOpener,
            PluginComponentFactory components,
            ManagedTaskExecutor tasks,
            FxDispatcher fx) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.dialogs = Objects.requireNonNull(dialogs, "dialogs");
        this.directoryOpener = Objects.requireNonNull(directoryOpener, "directoryOpener");
        this.components = Objects.requireNonNull(components, "components");
        detailsAction = new UiAsyncAction<>(tasks, fx);
        mutationAction = new UiAsyncAction<>(tasks, fx);
        configAction = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        enabledToggle.selectedProperty().addListener((ignored, oldValue, selected) -> {
            if (!configuringToggle && plugin != null) setEnabled(selected);
        });
        loadingOverlay.visibleProperty().bind(detailsAction.busyProperty());
        loadingOverlay.managedProperty().bind(loadingOverlay.visibleProperty());
        enabledToggle.disableProperty().bind(mutationAction.busyProperty());
        saveConfigButton.disableProperty().bind(configAction.busyProperty());
    }

    void configure(
            Runnable back, Consumer<Catalog> catalogChanged, Consumer<String> approval) {
        onBack = Objects.requireNonNull(back, "back");
        onCatalogChanged = Objects.requireNonNull(catalogChanged, "catalogChanged");
        onApproval = Objects.requireNonNull(approval, "approval");
    }

    void showPlugin(String id) {
        if (closed.get()) return;
        pluginId = Objects.requireNonNull(id, "id");
        root.setVisible(true);
        root.setManaged(true);
        requestDetails(id);
    }

    void hide() {
        pluginId = null;
        plugin = null;
        detailsAction.cancel();
        root.setVisible(false);
        root.setManaged(false);
        clearDynamicViews();
    }

    boolean isShowing() { return root.isVisible(); }

    @FXML
    private void backRequested() { onBack.run(); }

    @FXML
    private void approveRequested() {
        if (pluginId != null) onApproval.accept(pluginId);
    }

    @FXML
    private void saveConfigRequested() {
        if (pluginId == null) return;
        Map<String, String> values = new LinkedHashMap<>();
        configViews.forEach(view -> values.put(view.key(), view.value()));
        String target = pluginId;
        configAction.execute(
                TaskSpec.io("plugin-config-save-" + target),
                context -> {
                    useCases.saveConfig(target, values);
                    return null;
                },
                ignored -> {
                    configSavedLabel.setText("已保存（已启用的插件需停用后重新启用方生效）");
                    showStatus("插件配置已保存", false);
                },
                failure -> showFailure("保存配置失败", failure));
    }

    @FXML
    private void openDirectoryRequested() {
        directoryOpener.open(useCases.pluginsDirectory());
    }

    @FXML
    private void uninstallRequested() {
        if (plugin == null) return;
        Plugin targetPlugin = plugin;
        mutationAction.execute(
                TaskSpec.io("plugin-uninstall-" + targetPlugin.id()),
                context -> confirmAndUninstall(targetPlugin),
                catalog -> {
                    if (catalog == null) return;
                    onCatalogChanged.accept(catalog);
                    showStatus("插件已卸载", false);
                    onBack.run();
                },
                failure -> showFailure("卸载失败", failure));
    }

    private Catalog confirmAndUninstall(Plugin target) {
        ConfirmDecision decision = dialogs.confirm(new ConfirmRequest(
                "卸载插件「" + target.name() + "」",
                "不可逆",
                "确定要卸载插件「" + target.name()
                        + "」吗？将停用并删除其在 plugins/ 下的目录。",
                ConfirmKind.CONFIRM, 60, "", false));
        return decision.isAllow() ? useCases.uninstall(target.id()) : null;
    }

    private void requestDetails(String id) {
        detailsAction.execute(
                TaskSpec.io("plugin-details-" + id),
                context -> useCases.details(id),
                details -> {
                    if (id.equals(pluginId)) applyDetails(details);
                },
                failure -> showFailure("加载插件详情失败", failure));
    }

    private void setEnabled(boolean enabled) {
        String target = pluginId;
        mutationAction.execute(
                TaskSpec.io("plugin-toggle-" + target),
                context -> useCases.setEnabled(target, enabled),
                catalog -> {
                    onCatalogChanged.accept(catalog);
                    showStatus(enabled ? "插件已启用" : "插件已停用", false);
                },
                failure -> {
                    configureToggle(plugin != null && plugin.active());
                    showFailure("插件状态更新失败", failure);
                });
    }

    private void applyDetails(Details details) {
        plugin = details.plugin();
        glyphLabel.setText(PluginUiText.glyph(plugin));
        nameLabel.setText(plugin.name());
        metadataLabel.setText("id：" + plugin.id() + " · " + PluginUiText.metadata(plugin));
        stateTextLabel.setText(plugin.active() ? "已启用" : PluginUiText.state(plugin.state()));
        descriptionLabel.setText(plugin.description().isBlank() ? "（无描述）" : plugin.description());
        configureStateBadge();
        configureToggle(plugin.active());
        boolean pending = plugin.state()
                == PluginManagementApplicationService.State.PENDING_APPROVAL;
        enabledToggle.setVisible(!pending);
        enabledToggle.setManaged(!pending);
        approvalButton.setVisible(pending);
        approvalButton.setManaged(pending);
        failureLabel.setText("失败原因：" + plugin.error());
        boolean failed = plugin.state()
                == PluginManagementApplicationService.State.FAILED && !plugin.error().isBlank();
        failureLabel.setVisible(failed);
        failureLabel.setManaged(failed);
        configSavedLabel.setText("");
        showStatus("", false);
        renderDynamicDetails(details);
    }

    private void configureStateBadge() {
        stateBadge.setText(PluginUiText.state(plugin.state()));
        stateBadge.getStyleClass().removeAll(
                "jc-badge-running", "jc-badge-failed", "jc-badge-stopped");
        stateBadge.getStyleClass().add(PluginUiText.stateStyle(plugin.state()));
    }

    private void configureToggle(boolean enabled) {
        configuringToggle = true;
        enabledToggle.setSelected(enabled);
        configuringToggle = false;
    }

    private void renderDynamicDetails(Details details) {
        clearDynamicViews();
        permissionEmptyLabel.setVisible(plugin.permissions().isEmpty());
        permissionEmptyLabel.setManaged(plugin.permissions().isEmpty());
        plugin.permissions().forEach(permission -> {
            PluginChildView<HBox> view = components.permission(permission);
            dynamicViews.add(view);
            permissionsBox.getChildren().add(view.root());
        });
        renderNamedItems(plugin.skills(), skillsSection, skillsTitle, skillsItems, "提供的技能");
        renderNamedItems(plugin.tools(), toolsSection, toolsTitle, toolsItems, "提供的工具");
        boolean pending = plugin.state()
                == PluginManagementApplicationService.State.PENDING_APPROVAL;
        boolean noExposed = plugin.skills().isEmpty() && plugin.tools().isEmpty();
        exposureHint.setVisible(pending || noExposed);
        exposureHint.setManaged(exposureHint.isVisible());
        exposureHint.setText(pending
                ? "该服务插件尚未注册。批准后将移入“服务插件”页，并保持手动停止。"
                : plugin.active() ? "该插件未对外暴露技能或工具。"
                : "启用后此处显示插件对外暴露的技能与工具。");
        renderConfig(details.configValues());
    }

    private void renderNamedItems(
            List<NamedItem> items,
            VBox section,
            Label title,
            VBox container,
            String caption) {
        section.setVisible(!items.isEmpty());
        section.setManaged(!items.isEmpty());
        title.setText(caption + "（" + items.size() + "）");
        for (NamedItem item : items) {
            PluginChildView<VBox> view = components.namedItem(item);
            dynamicViews.add(view);
            container.getChildren().add(view.root());
        }
    }

    private void renderConfig(Map<String, String> values) {
        configSection.setVisible(!plugin.config().isEmpty());
        configSection.setManaged(!plugin.config().isEmpty());
        plugin.config().forEach(field -> {
            PluginConfigFieldView view = components.configField(
                    field, values.getOrDefault(field.key(), ""));
            dynamicViews.add(view);
            configViews.add(view);
            configFields.getChildren().add(view.root());
        });
    }

    private void clearDynamicViews() {
        RuntimeException failure = null;
        for (int index = dynamicViews.size() - 1; index >= 0; index--) {
            try {
                dynamicViews.get(index).close();
            } catch (Exception closeFailure) {
                RuntimeException wrapped = closeFailure instanceof RuntimeException runtime
                        ? runtime : new IllegalStateException(closeFailure);
                if (failure == null) failure = wrapped;
                else failure.addSuppressed(wrapped);
            }
        }
        dynamicViews.clear();
        configViews.clear();
        if (permissionsBox != null) permissionsBox.getChildren().clear();
        if (skillsItems != null) skillsItems.getChildren().clear();
        if (toolsItems != null) toolsItems.getChildren().clear();
        if (configFields != null) configFields.getChildren().clear();
        if (failure != null) throw failure;
    }

    private void showFailure(String prefix, Throwable failure) {
        String detail = failure == null || failure.getMessage() == null
                || failure.getMessage().isBlank() ? "未知错误" : failure.getMessage();
        showStatus(prefix + "：" + detail, true);
    }

    private void showStatus(String text, boolean error) {
        statusLabel.setText(text == null ? "" : text);
        statusLabel.getStyleClass().removeAll("status-success", "status-error");
        if (!statusLabel.getText().isBlank()) {
            statusLabel.getStyleClass().add(error ? "status-error" : "status-success");
        }
        statusLabel.setVisible(!statusLabel.getText().isBlank());
        statusLabel.setManaged(statusLabel.isVisible());
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        RuntimeException failure = null;
        failure = closeStep(failure, detailsAction::close);
        failure = closeStep(failure, mutationAction::close);
        failure = closeStep(failure, configAction::close);
        failure = closeStep(failure, this::clearDynamicViews);
        failure = closeStep(failure, this::unbindViewState);
        if (failure != null) throw failure;
    }

    private void unbindViewState() {
        if (loadingOverlay == null) return;
        loadingOverlay.visibleProperty().unbind();
        loadingOverlay.managedProperty().unbind();
        enabledToggle.disableProperty().unbind();
        saveConfigButton.disableProperty().unbind();
    }

    private static RuntimeException closeStep(RuntimeException current, Runnable step) {
        try {
            step.run();
            return current;
        } catch (RuntimeException failure) {
            if (current == null) return failure;
            current.addSuppressed(failure);
            return current;
        }
    }

    boolean isClosed() { return closed.get(); }
}
