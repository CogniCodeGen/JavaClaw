package com.javaclaw.desktop;

import java.util.Objects;
import java.util.function.Consumer;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** 领域窗口的 FXML 控制器；具体页面使用 typed SDK ViewModel，不展示对象 toString 或内部 JSON 检查器。 */
public final class ManagementController {
    private final DesktopViewModel desktop;
    private final ManagementViewModel model;
    private ManagementPageLifecycle currentPage;
    private String currentSection;
    private WindowToast toast;

    @FXML
    private Label heading;

    @FXML
    private Label workspace;

    @FXML
    private Label status;

    @FXML
    private ProgressIndicator busy;

    @FXML
    private Label dirty;

    @FXML
    private Label windowToast;

    @FXML
    private BorderPane content;

    ManagementController(DesktopViewModel desktop) {
        this.desktop = desktop;
        model = desktop.management();
    }

    @FXML
    private void initialize() {
        toast = new WindowToast(windowToast);
        status.textProperty().bind(model.statusProperty());
        status.getStyleClass().add("management-feedback");
        model.feedbackProperty().addListener((ignored, old, value) -> applyFeedback(value));
        applyFeedback(model.feedbackProperty().get());
        busy.visibleProperty().bind(model.busyProperty());
        busy.managedProperty().bind(busy.visibleProperty());
    }

    void show(String section, Consumer<ManagementPageSpec> shown) {
        Objects.requireNonNull(shown, "shown");
        if (section != null && section.equals(currentSection) && currentPage != null) {
            shown.accept(ManagementPageSpec.forSection(section));
            return;
        }
        leaveCurrent(() -> shown.accept(showNow(section)));
    }

    private ManagementPageSpec showNow(String section) {
        ManagementPageSpec spec = ManagementPageSpec.forSection(section);
        disposeCurrent();
        model.newPage();
        workspace.setText(model.workspaceName());
        heading.setText(spec.title());
        if (desktop.selectedWorkspace() == null
                && java.util.List.of(
                                "Memory", "Knowledge", "Automation", "Schedules", "Sites", "Instructions", "Worktrees")
                        .contains(section)) {
            var page = ManagementForms.form(ManagementForms.hint("请先在主窗口创建或选择工作区。"));
            spec.configure(page);
            content.setCenter(page);
            currentSection = section;
            return spec;
        }
        javafx.scene.Parent page =
                switch (section) {
                    case "Settings" -> new SettingsPane(model, desktop);
                    case "Profiles" -> new ProfilePane(model, desktop);
                    case "Memory" -> new MemoryPane(model);
                    case "Knowledge" -> new KnowledgePane(model);
                    case "Skills" -> new SkillPane(model);
                    case "Automation" -> new AutomationPane(model);
                    case "Schedules" -> new SchedulePane(model);
                    case "Plugins" -> new PluginPane(model);
                    case "MCP" -> new McpPane(model);
                    case "Sites" -> new SitePane(model);
                    case "Instructions" -> new AgentsInstructionsPane(model);
                    case "Worktrees" -> new WorktreePane(model);
                    default -> new ProviderPane(model);
                };
        spec.configure(page);
        DesktopTheme.applyPage(page, spec);
        currentPage = page instanceof ManagementPageLifecycle lifecycle ? lifecycle : null;
        if (currentPage == null) {
            content.setCenter(page);
        } else {
            var loading = new ProgressIndicator();
            loading.setMaxSize(30, 30);
            var loadingText = new Label("正在加载页面内容…");
            loadingText.getStyleClass().add("sec-hint");
            var overlay = new VBox(10, loading, loadingText);
            overlay.setAlignment(javafx.geometry.Pos.CENTER);
            overlay.getStyleClass().add("management-loading-overlay");
            overlay.visibleProperty()
                    .bind(currentPage.loadStateProperty().isEqualTo(ManagementPageLifecycle.LoadState.INITIAL_LOADING));
            overlay.managedProperty().bind(overlay.visibleProperty());
            content.setCenter(new StackPane(page, overlay));
            dirty.textProperty()
                    .bind(javafx.beans.binding.Bindings.when(currentPage.dirtyProperty())
                            .then("有未保存修改 · ⌘/Ctrl+S 保存")
                            .otherwise(""));
            dirty.visibleProperty().bind(currentPage.dirtyProperty());
            dirty.managedProperty().bind(dirty.visibleProperty());
        }
        currentSection = section;
        return spec;
    }

    void requestSave() {
        if (currentPage != null && currentPage.canSaveProperty().get()) {
            currentPage.requestSave(ignored -> {});
        }
    }

    void requestClose() {
        leaveCurrent(() -> {
            disposeCurrent();
            content.getScene().getWindow().hide();
        });
    }

    static String title(String section) {
        return ManagementPageSpec.forSection(section).title();
    }

    private void applyFeedback(ManagementFeedback value) {
        status.getStyleClass()
                .removeIf(name -> name.startsWith("management-feedback-") && !name.equals("management-feedback"));
        status.getStyleClass().add(value.styleClass());
        status.setAccessibleText(value.message().isBlank() ? "没有待处理状态" : value.message());
        switch (value.severity()) {
            case SUCCESS -> {
                UiMotion.success(status);
                if (toastableSuccess(value.message())) {
                    toast.success(value.message());
                }
            }
            case VALIDATION_ERROR -> UiMotion.error(status);
            case SERVER_ERROR -> {
                UiMotion.error(status);
                toast.error(value.message());
            }
            case IDLE, RUNNING -> {
                // 运行状态由页脚和目标按钮表达，不用瞬时 Toast 打断连续编辑。
            }
        }
    }

    private static boolean toastableSuccess(String message) {
        return java.util.stream.Stream.of(
                        "保存", "删除", "安装", "卸载", "运行", "启动", "恢复", "启用", "禁用", "重建", "导入", "导出", "授权", "触发", "接受", "拒绝",
                        "更新", "创建")
                .anyMatch(message::contains);
    }

    @FXML
    private void close() {
        requestClose();
    }

    private void leaveCurrent(Runnable continuation) {
        if (currentPage == null || !currentPage.dirtyProperty().get()) {
            continuation.run();
            return;
        }
        switch (model.dialogs().resolveUnsavedChanges(content, currentPage.currentResource())) {
            case SAVE ->
                currentPage.requestSave(success -> {
                    if (success) {
                        continuation.run();
                    }
                });
            case DISCARD -> {
                currentPage.discard();
                continuation.run();
            }
            case CANCEL -> {
                // 保留管理窗口和当前资源。
            }
        }
    }

    private void disposeCurrent() {
        dirty.textProperty().unbind();
        dirty.visibleProperty().unbind();
        dirty.managedProperty().unbind();
        dirty.setText("");
        dirty.setVisible(false);
        dirty.setManaged(false);
        if (currentPage != null) {
            currentPage.cancelReads();
            currentPage.dispose();
            currentPage = null;
        }
    }
}
