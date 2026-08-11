package com.javaclaw.ui.javafx.settings;

import com.javaclaw.api.interaction.ConfirmDecision;
import com.javaclaw.api.interaction.ConfirmKind;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import javafx.animation.TranslateTransition;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** 设置窗口主 Controller；协调导航、分区、页脚和关闭确认。 */
public final class SettingsViewController
        implements SettingsPanelCatalogFactory.Callbacks, AutoCloseable {

    @FXML private StackPane contentArea;
    @FXML private Label crumbCurrentLabel;
    @FXML private SettingsNavigationController navigationController;
    @FXML private SettingsFooterController footerController;

    private final SettingsPanelCatalogFactory catalogs;
    private final DialogService dialogs;
    private final SettingsViewModel viewModel = new SettingsViewModel();
    private final SettingsDirtyTracker dirtyTracker = new SettingsDirtyTracker();
    private final UiAsyncAction<ConfirmDecision> closeConfirmation;
    private final Map<SettingsCategory, SettingsPanelCatalog.Panel> panels =
            new EnumMap<>(SettingsCategory.class);

    private SettingsPanelCatalog catalog;
    private Runnable closeWindow = () -> { };
    private Runnable onRuntimeConfigurationChanged = () -> { };
    private boolean runtimeCallbackConfigured;

    public SettingsViewController(
            SettingsPanelCatalogFactory catalogs,
            DialogService dialogs,
            ManagedTaskExecutor tasks,
            FxDispatcher fx) {
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
        this.dialogs = Objects.requireNonNull(dialogs, "dialogs");
        closeConfirmation = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        navigationController.configure(this::showPanel);
        footerController.configure(this::saveCurrentPanel,
                this::testCurrentPanel, this::requestClose);
        catalog = catalogs.create(this);
        for (SettingsPanelCatalog.Panel panel : catalog.orderedPanels()) {
            panels.put(panel.category(), panel);
            contentArea.getChildren().add(panel.root());
            visible(panel.root(), panel.category() == SettingsCategory.MODEL);
            if (panel.actions().save() != null) {
                dirtyTracker.watch(panel.root(), () -> markDirty(panel.category()));
            }
        }
        crumbCurrentLabel.textProperty().bind(viewModel.selectedCategoryProperty()
                .map(SettingsCategory::displayName));
        navigationController.select(SettingsCategory.MODEL);
    }

    public SettingsViewModel viewModel() {
        return viewModel;
    }

    public void configure(Runnable closeAction, Runnable runtimeConfigurationChanged) {
        closeWindow = Objects.requireNonNull(closeAction, "closeAction");
        runtimeCallbackConfigured = runtimeConfigurationChanged != null;
        onRuntimeConfigurationChanged = runtimeConfigurationChanged == null
                ? () -> { } : runtimeConfigurationChanged;
    }

    public void prepare(String categoryName) {
        viewModel.loading(true);
        try {
            catalog.reloadAll();
        } finally {
            viewModel.loading(false);
        }
        viewModel.clearDirty();
        navigationController.showDirty(viewModel.dirtyCategories());
        SettingsCategory requested = SettingsCategory.named(categoryName);
        navigationController.select(requested == null ? SettingsCategory.MODEL : requested);
    }

    public void saveCurrentPanel() {
        SettingsCategory category = viewModel.selectedCategory();
        SettingsPanelActions actions = panel(category).actions();
        if (actions.save() == null || !viewModel.isDirty(category)) return;
        footerController.showInfo("正在保存…");
        refreshCapabilities();
        try {
            actions.save().run(() -> saved(category, actions),
                    failure -> saveFailed(category, failure));
        } catch (Throwable failure) {
            saveFailed(category, failure);
        }
    }

    public void testCurrentPanel() {
        SettingsCategory category = viewModel.selectedCategory();
        SettingsPanelActions actions = panel(category).actions();
        if (actions.test() == null || viewModel.testing()) return;
        viewModel.testing(true);
        footerController.clearStatus();
        refreshCapabilities();
        try {
            actions.test().run();
        } catch (Throwable failure) {
            testFinished(category, "测试失败: "
                    + SettingsFieldSupport.failureMessage(failure), false);
        }
    }

    public void requestClose() {
        if (viewModel.dirtyCount() == 0) {
            closeWindow.run();
            return;
        }
        int dirtyCount = viewModel.dirtyCount();
        footerController.showInfo("等待确认…");
        closeConfirmation.execute(TaskSpec.io("settings-close-confirm"), context ->
                        dialogs.confirm(new ConfirmRequest(
                                "关闭设置", "未保存更改",
                                "有 " + dirtyCount + " 个分区存在未保存的更改，确定关闭？",
                                ConfirmKind.CONFIRM, 60, "", false)),
                decision -> {
                    if (decision.isAllow()) closeWindow.run();
                    else refreshFooter(true);
                }, failure -> footerController.showResult(
                        "关闭确认失败: " + SettingsFieldSupport.failureMessage(failure), false));
    }

    private void showPanel(SettingsCategory category) {
        SettingsCategory previous = viewModel.selectedCategory();
        for (SettingsPanelCatalog.Panel panel : panels.values()) {
            visible(panel.root(), panel.category() == category);
        }
        viewModel.select(category);
        if (category != previous) animate(panel(category).root());
        refreshFooter(true);
    }

    private void markDirty(SettingsCategory category) {
        if (viewModel.loading()) return;
        viewModel.markDirty(category);
        navigationController.showDirty(viewModel.dirtyCategories());
        if (category == viewModel.selectedCategory()) {
            refreshCapabilities();
            footerController.showUnsaved();
        }
    }

    private void saved(SettingsCategory category, SettingsPanelActions actions) {
        viewModel.clearDirty(category);
        navigationController.showDirty(viewModel.dirtyCategories());
        if (category == viewModel.selectedCategory()) {
            refreshCapabilities();
            footerController.showSaved(actions.savedTip().get());
        }
    }

    private void saveFailed(SettingsCategory category, Throwable failure) {
        if (category != viewModel.selectedCategory()) return;
        refreshCapabilities();
        footerController.showResult("保存失败: "
                + SettingsFieldSupport.failureMessage(failure), false);
    }

    private void refreshFooter(boolean resetStatus) {
        if (resetStatus) footerController.clearStatus();
        refreshCapabilities();
        if (resetStatus && viewModel.isDirty(viewModel.selectedCategory())) {
            footerController.showUnsaved();
        }
        navigationController.showDirty(viewModel.dirtyCategories());
    }

    private void refreshCapabilities() {
        SettingsPanelActions actions = panel(viewModel.selectedCategory()).actions();
        footerController.capabilities(actions.save() != null,
                viewModel.isDirty(viewModel.selectedCategory()), actions.test() != null,
                viewModel.testing(), actions.testLabel());
    }

    private SettingsPanelCatalog.Panel panel(SettingsCategory category) {
        return Objects.requireNonNull(panels.get(category), "缺少设置分区: " + category);
    }

    private static void animate(Node target) {
        if (target instanceof ScrollPane scrollPane) scrollPane.setVvalue(0);
        target.setTranslateY(5);
        TranslateTransition transition = new TranslateTransition(Duration.millis(180), target);
        transition.setFromY(5);
        transition.setToY(0);
        transition.play();
    }

    private static void visible(Node node, boolean value) {
        node.setVisible(value);
        node.setManaged(value);
    }

    @Override
    public void applied(SettingsCategory category, String message,
                        boolean runtimeRefreshRequired) {
        viewModel.clearDirty(category);
        navigationController.showDirty(viewModel.dirtyCategories());
        if (category == viewModel.selectedCategory() && message != null && !message.isBlank()) {
            refreshCapabilities();
            footerController.showResult(message, true);
        }
        if (runtimeRefreshRequired) onRuntimeConfigurationChanged.run();
    }

    @Override
    public void testFinished(SettingsCategory category, String message, boolean succeeded) {
        viewModel.testing(false);
        if (category == viewModel.selectedCategory()) {
            refreshCapabilities();
            footerController.showResult(message, succeeded);
        } else {
            refreshFooter(true);
        }
    }

    @Override
    public void runtimeConfigurationChanged() {
        onRuntimeConfigurationChanged.run();
    }

    @Override
    public String modelSavedTip() {
        return runtimeCallbackConfigured
                ? "✓ 已保存并生效，下一轮对话重建智能体服务"
                : "✓ 已保存（重启后生效）";
    }

    @Override
    public void close() {
        crumbCurrentLabel.textProperty().unbind();
        closeConfirmation.close();
        dirtyTracker.close();
        if (catalog != null) catalog.close();
        panels.clear();
        runtimeCallbackConfigured = false;
        closeWindow = onRuntimeConfigurationChanged = () -> { };
    }
}
