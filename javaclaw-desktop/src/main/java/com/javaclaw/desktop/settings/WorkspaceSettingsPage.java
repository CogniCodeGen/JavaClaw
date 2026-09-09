package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;

import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceLifecycle;
import com.javaclaw.desktop.DesktopConfigurationChange;
import com.javaclaw.desktop.DesktopNotificationSubscription;
import com.javaclaw.desktop.component.AsyncActionBar;
import com.javaclaw.desktop.component.AsyncActionBar.ActionState;
import com.javaclaw.desktop.component.FormSection;
import com.javaclaw.desktop.component.ListDetailPane;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.desktop.component.PlatformComponentFactory.FeedbackKind;
import com.javaclaw.desktop.component.PlatformDialogs;

/** 工作区重命名、归档和独立执行默认配置管理页面。 */
public final class WorkspaceSettingsPage extends VBox implements ManagedSettingsPage {
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final WorkspaceSettingsPresenter presenter;
    private final ListDetailPane<Workspace> masterDetail = new ListDetailPane<>();
    private final TextField name = new TextField();
    private final ExecutionSelectionPanel execution;
    private final Label rootPath = value();
    private final Label lifecycle = value();
    private final Label revision = value();
    private final Button saveName;
    private final Button saveExecution;
    private final Button archive;
    private final AsyncActionBar actions;
    private final DesktopNotificationSubscription configurationSubscription;
    private WorkspaceSettingsState state = WorkspaceSettingsState.initial();
    private Node editor;
    private boolean rendering;
    private boolean active;

    /**
     * 创建工作区设置页。
     *
     * @param gateway 强类型 SDK 设置边界
     */
    public WorkspaceSettingsPage(CoreSettingsGateway gateway) {
        presenter = new WorkspaceSettingsPresenter(gateway);
        execution = new ExecutionSelectionPanel(gateway);
        saveName = components.action("保存名称", ActionStyle.SOFT, ActionSize.NORMAL);
        saveName.setOnAction(event -> presenter.saveName());
        saveExecution = components.action("保存执行默认配置", ActionStyle.PRIMARY, ActionSize.NORMAL);
        saveExecution.setOnAction(event -> execution.save());
        archive = components.action("归档登记", ActionStyle.DANGER, ActionSize.NORMAL);
        archive.setOnAction(event -> archive());
        actions = new AsyncActionBar(saveName, saveExecution, archive);
        configurePage();
        execution.onStateChanged(this::updateActions);
        presenter.subscribe(this::render);
        configurationSubscription = gateway.onConfigurationChanged(this::configurationChanged);
    }

    @Override
    public Node content() {
        return this;
    }

    @Override
    public Optional<Node> actionContent() {
        return Optional.of(actions);
    }

    @Override
    public void activate() {
        active = true;
        execution.setRefreshActive(true);
        presenter.activate();
    }

    @Override
    public void deactivate() {
        active = false;
        presenter.deactivate();
        execution.setRefreshActive(false);
    }

    @Override
    public void invalidateCache() {
        presenter.invalidateCache();
        execution.invalidateCache();
    }

    @Override
    public void dispose() {
        active = false;
        configurationSubscription.close();
        execution.close();
    }

    /** 重验登记与执行目录；各编辑基线及草稿由对应状态机保护。 */
    void refreshExecutionConfiguration() {
        presenter.refresh();
        if (state.selected().isPresent()) {
            execution.refreshAutomatically();
        }
    }

    private void configurationChanged(DesktopConfigurationChange change) {
        if (change.kind() == DesktopConfigurationChange.Kind.WORKSPACES) {
            presenter.invalidateCache();
            if (active) {
                presenter.activate();
            }
        }
    }

    @Override
    public boolean dirty() {
        return state.dirty() || execution.dirty();
    }

    @Override
    public boolean pending() {
        return state.phase() == SettingsLoadState.LOADING || execution.pending();
    }

    @Override
    public void workspaceChanged(Optional<Workspace> workspace) {
        presenter.bindWorkspace(Objects.requireNonNull(workspace, "workspace"));
    }

    @Override
    public void warnUnsavedChanges() {
        actions.show(ActionState.DIRTY, "请先保存或丢弃工作区草稿，再离开此页");
    }

    @Override
    public void discardDraft() {
        presenter.discardDraft();
        execution.discard();
    }

    private void configurePage() {
        Label title = new Label("工作区");
        title.getStyleClass().addAll("sec-title", "platform-page-title");
        Label hint = new Label("工作区根目录在创建后不可修改。归档只移除 JavaClaw 登记，永远不会删除用户目录。");
        hint.setWrapText(true);
        hint.getStyleClass().add("sec-hint");
        configureCatalog();
        getChildren().addAll(title, hint, masterDetail);
        getStyleClass().add("platform-page");
    }

    private void configureCatalog() {
        masterDetail
                .list()
                .setCellFactory(ignored -> components.detailCell(
                        Workspace::name,
                        workspace -> SettingsLabels.workspaceLifecycle(workspace.lifecycle()) + " · 版本 "
                                + workspace.revision()));
        masterDetail
                .list()
                .getSelectionModel()
                .selectedItemProperty()
                .addListener((observable, previous, selected) -> select(selected));
        masterDetail.list().setPlaceholder(components.feedback(FeedbackKind.EMPTY, "暂无工作区", "请先在主窗口创建工作区。"));
        masterDetail.showDetail(components.feedback(FeedbackKind.EMPTY, "选择工作区", "选择左侧项目后可管理名称与执行默认配置。"));
    }

    private Node detail() {
        FormSection identity = new FormSection("登记信息", "根目录只读；名称与生命周期使用各自的权威版本。");
        name.setAccessibleText("工作区名称");
        name.setPromptText("工作区名称");
        name.getStyleClass().add("settings-field");
        name.textProperty().addListener((observable, previous, value) -> editName(value));
        identity.addField("名称", name);
        identity.addField("根目录", rootPath);
        identity.addField("状态", lifecycle);
        identity.addField("版本", revision);

        FormSection defaults = new FormSection("任务默认配置", "Agent、模型与权限分别固定到精确版本；变更只影响新任务。");
        defaults.addFullWidth(execution);

        VBox detail = new VBox(12, identity, defaults);
        detail.getStyleClass().add("platform-page");
        return detail;
    }

    private void select(Workspace selected) {
        if (rendering || selected == null || selected.equals(state.selected().orElse(null))) {
            return;
        }
        if (dirty()) {
            warnUnsavedChanges();
            restoreSelection();
            return;
        }
        presenter.select(selected);
    }

    private void editName(String value) {
        if (!rendering) {
            presenter.editName(value);
        }
    }

    private void render(WorkspaceSettingsState snapshot) {
        state = Objects.requireNonNull(snapshot, "snapshot");
        rendering = true;
        try {
            masterDetail.list().getItems().setAll(snapshot.workspaces());
            masterDetail
                    .list()
                    .getSelectionModel()
                    .select(snapshot.selected()
                            .flatMap(selected -> snapshot.workspaces().stream()
                                    .filter(candidate -> candidate.id().equals(selected.id()))
                                    .findFirst())
                            .orElse(null));
            if (snapshot.selected().isPresent()) {
                renderSelected(snapshot);
            } else {
                masterDetail.showDetail(components.feedback(FeedbackKind.EMPTY, "暂无工作区", "请先在主窗口创建工作区。"));
            }
            updateActions();
        } finally {
            rendering = false;
        }
    }

    private void renderSelected(WorkspaceSettingsState snapshot) {
        Workspace selected = snapshot.selected().orElseThrow();
        if (editor == null) {
            editor = detail();
        }
        masterDetail.showDetail(editor);
        name.setText(snapshot.draftName());
        rootPath.setText(selected.root().toString());
        lifecycle.setText(SettingsLabels.workspaceLifecycle(selected.lifecycle()));
        revision.setText(Long.toString(selected.revision()));
        execution.bind(snapshot.selected(), Optional.empty(), true);
        updateActions();
    }

    private void updateActions() {
        boolean pending = pending();
        boolean active = state.selected()
                .filter(value -> value.lifecycle() == WorkspaceLifecycle.ACTIVE)
                .isPresent();
        saveName.setDisable(pending || !active || !state.nameDirty());
        saveExecution.setDisable(pending || !active || !execution.canSave());
        archive.setDisable(pending || !active);
        showStatus(state);
    }

    private void showStatus(WorkspaceSettingsState snapshot) {
        if (snapshot.phase() == SettingsLoadState.LOADING) {
            actions.show(ActionState.PENDING, snapshot.message());
        } else if (snapshot.phase() == SettingsLoadState.ERROR) {
            actions.show(ActionState.ERROR, snapshot.message());
        } else if (snapshot.dirty() || execution.dirty()) {
            actions.show(ActionState.DIRTY, snapshot.message().isBlank() ? "工作区草稿尚未保存" : snapshot.message());
        } else {
            actions.show(snapshot.message().isBlank() ? ActionState.IDLE : ActionState.SUCCESS, snapshot.message());
        }
    }

    private void restoreSelection() {
        rendering = true;
        try {
            masterDetail.list().getSelectionModel().select(state.selected().orElse(null));
        } finally {
            rendering = false;
        }
    }

    private void archive() {
        Workspace selected = state.selected().orElseThrow();
        Alert alert = new Alert(
                Alert.AlertType.CONFIRMATION,
                "只归档 JavaClaw 中的工作区登记，不会删除目录：" + selected.root(),
                ButtonType.CANCEL,
                ButtonType.OK);
        alert.setTitle("归档工作区登记");
        alert.setHeaderText("确认归档 “" + selected.name() + "”？");
        PlatformDialogs.style(alert, this);
        if (alert.showAndWait().filter(ButtonType.OK::equals).isPresent()) {
            presenter.archive();
        }
    }

    private static Label value() {
        Label label = new Label("—");
        label.setWrapText(true);
        label.getStyleClass().add("platform-detail-text");
        return label;
    }
}
