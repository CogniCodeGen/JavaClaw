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
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** 设置窗口主 Controller；协调导航、分区、页脚和关闭确认。 */
public final class SettingsViewController
        implements SettingsPanelCatalogFactory.Callbacks, AutoCloseable {

    @FXML private StackPane contentArea;
    @FXML private VBox loadingPane;
    @FXML private Label loadingLabel;
    @FXML private Label crumbCurrentLabel;
    @FXML private SettingsNavigationController navigationController;
    @FXML private SettingsFooterController footerController;

    private final SettingsPanelCatalogFactory catalogs;
    private final DialogService dialogs;
    private final SettingsViewModel viewModel = new SettingsViewModel();
    private final SettingsDirtyTracker dirtyTracker = new SettingsDirtyTracker();
    private final FxDispatcher fx;
    private final UiAsyncAction<ConfirmDecision> closeConfirmation;
    private final Map<SettingsCategory, SettingsPanelCatalog.Panel> panels =
            new EnumMap<>(SettingsCategory.class);

    private SettingsPanelCatalog catalog;
    private Runnable closeWindow = () -> { };
    private Runnable onRuntimeConfigurationChanged = () -> { };
    private boolean runtimeCallbackConfigured;
    private boolean activated;
    private boolean closed;
    private long loadGeneration;

    public SettingsViewController(
            SettingsPanelCatalogFactory catalogs,
            DialogService dialogs,
            ManagedTaskExecutor tasks,
            FxDispatcher fx) {
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
        this.dialogs = Objects.requireNonNull(dialogs, "dialogs");
        this.fx = Objects.requireNonNull(fx, "fx");
        closeConfirmation = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        navigationController.configure(this::showPanel);
        footerController.configure(this::saveCurrentPanel,
                this::testCurrentPanel, this::requestClose);
        catalog = catalogs.create(this);
        crumbCurrentLabel.textProperty().bind(viewModel.selectedCategoryProperty()
                .map(SettingsCategory::displayName));
        showLoading(SettingsCategory.MODEL);
        navigationController.select(SettingsCategory.MODEL);
    }

    public SettingsViewModel viewModel() {
        return viewModel;
    }

    int loadedPanelCount() { return catalog == null ? 0 : catalog.loadedCount(); }

    public void configure(Runnable closeAction, Runnable runtimeConfigurationChanged) {
        closeWindow = Objects.requireNonNull(closeAction, "closeAction");
        runtimeCallbackConfigured = runtimeConfigurationChanged != null;
        onRuntimeConfigurationChanged = runtimeConfigurationChanged == null
                ? () -> { } : runtimeConfigurationChanged;
    }

    public void prepare(String categoryName) {
        viewModel.clearDirty();
        navigationController.showDirty(viewModel.dirtyCategories());
        SettingsCategory requested = SettingsCategory.named(categoryName);
        navigationController.select(requested == null ? SettingsCategory.MODEL : requested);
    }

    /** 在 Stage 已显示后的下一次 pulse 创建目标面板，保证设置窗口骨架优先呈现。 */
    public void activate() {
        if (closed || activated) return;
        activated = true;
        SettingsCategory target = viewModel.selectedCategory();
        if (panel(target) == null) loadPanel(target);
    }

    public void saveCurrentPanel() {
        SettingsCategory category = viewModel.selectedCategory();
        SettingsPanelCatalog.Panel current = panel(category);
        if (current == null) return;
        SettingsPanelActions actions = current.actions();
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
        SettingsPanelCatalog.Panel current = panel(category);
        if (current == null) return;
        SettingsPanelActions actions = current.actions();
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
        SettingsPanelCatalog.Panel previousPanel = panel(previous);
        if (category != previous && previousPanel != null) previousPanel.deactivate().run();
        for (SettingsPanelCatalog.Panel panel : panels.values()) {
            visible(panel.root(), panel.category() == category);
        }
        viewModel.select(category);
        SettingsPanelCatalog.Panel selected = panel(category);
        if (selected == null) {
            showLoading(category);
            if (activated) schedulePanelLoad(category);
        } else {
            visible(loadingPane, false);
            visible(selected.root(), true);
            if (category != previous) {
                animate(selected.root());
                if (!viewModel.isDirty(category)) {
                    fx.dispatchLater(() -> reloadPanel(category, selected));
                }
            }
        }
        refreshFooter(true);
    }

    private void schedulePanelLoad(SettingsCategory category) {
        if (closed || panel(category) != null) return;
        long requested = ++loadGeneration;
        showLoading(category);
        fx.dispatchLater(() -> {
            if (closed || requested != loadGeneration
                    || category != viewModel.selectedCategory()) return;
            loadPanel(category);
        });
    }

    private void loadPanel(SettingsCategory category) {
        if (closed) return;
        viewModel.loading(true);
        try {
            SettingsPanelCatalog.Panel loaded = catalog.load(category);
            panels.put(category, loaded);
            contentArea.getChildren().add(loaded.root());
            visible(loaded.root(), category == viewModel.selectedCategory());
            if (loaded.actions().save() != null) {
                dirtyTracker.watch(loaded.root(), () -> markDirty(category));
            }
            if (category == viewModel.selectedCategory()) {
                visible(loadingPane, false);
                animate(loaded.root());
                refreshFooter(true);
            }
            fx.dispatchLater(() -> reloadPanel(category, loaded));
        } catch (Throwable failure) {
            loadingLabel.setText("无法打开“" + category.displayName() + "”："
                    + SettingsFieldSupport.failureMessage(failure));
            footerController.showResult("页面加载失败", false);
        } finally {
            viewModel.loading(false);
        }
    }

    private void reloadPanel(SettingsCategory category, SettingsPanelCatalog.Panel loaded) {
        if (closed || panel(category) != loaded) return;
        try {
            loaded.reload().run();
        } catch (Throwable failure) {
            if (category == viewModel.selectedCategory()) {
                footerController.showResult("读取设置失败: "
                        + SettingsFieldSupport.failureMessage(failure), false);
            }
        }
    }

    private void showLoading(SettingsCategory category) {
        loadingLabel.setText("正在打开“" + category.displayName() + "”…");
        visible(loadingPane, true);
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
        SettingsPanelCatalog.Panel selected = panel(viewModel.selectedCategory());
        if (selected == null) {
            footerController.capabilities(false, false, false, false, "测试连接");
            return;
        }
        SettingsPanelActions actions = selected.actions();
        footerController.capabilities(actions.save() != null,
                viewModel.isDirty(viewModel.selectedCategory()), actions.test() != null,
                viewModel.testing(), actions.testLabel());
    }

    private SettingsPanelCatalog.Panel panel(SettingsCategory category) {
        return panels.get(category);
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
        if (closed) return;
        closed = true;
        loadGeneration++;
        crumbCurrentLabel.textProperty().unbind();
        closeConfirmation.close();
        dirtyTracker.close();
        if (catalog != null) catalog.close();
        panels.clear();
        runtimeCallbackConfigured = false;
        closeWindow = onRuntimeConfigurationChanged = () -> { };
    }
}
