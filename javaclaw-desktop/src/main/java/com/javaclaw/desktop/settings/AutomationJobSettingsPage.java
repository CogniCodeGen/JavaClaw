package com.javaclaw.desktop.settings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.desktop.component.AsyncActionBar;
import com.javaclaw.desktop.component.AsyncActionBar.ActionState;
import com.javaclaw.desktop.component.ExecutionTimeline;
import com.javaclaw.desktop.component.FormSection;
import com.javaclaw.desktop.component.ListDetailPane;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.desktop.component.PlatformComponentFactory.FeedbackKind;
import com.javaclaw.desktop.component.PlatformDialogs;
import com.javaclaw.desktop.component.RevisionConflictPane;
import com.javaclaw.extension.spi.ExtensionExecutionReceipt;
import com.javaclaw.extension.spi.ExtensionJobUnitState;
import com.javaclaw.protocol.InputJobRpcContracts;

/** 全局可恢复后台任务的过滤、分页、检查点和生命周期管理页。 */
public final class AutomationJobSettingsPage extends VBox implements ManagedSettingsPage {
    private static final List<StateChoice> STATE_CHOICES = stateChoices();

    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final AutomationJobSettingsPresenter presenter;
    private final TextField extension = new TextField();
    private final ComboBox<StateChoice> status = new ComboBox<>();
    private final ListDetailPane<ExtensionExecutionReceipt> jobs = new ListDetailPane<>();
    private final Label pageNumber = new Label("第 1 页");
    private final Label jobId = value();
    private final Label owner = value();
    private final Label workspaceId = value();
    private final Label jobType = value();
    private final Label definition = value();
    private final Label executionState = value();
    private final Label revision = value();
    private final Label createdAt = value();
    private final Label updatedAt = value();
    private final Label checkpoint = value();
    private final Label error = value();
    private final ExecutionTimeline timeline = new ExecutionTimeline();
    private final Button previous;
    private final Button next;
    private final Button pause;
    private final Button resume;
    private final Button cancel;
    private final AsyncActionBar actions;
    private final RevisionConflictPane conflict;
    private AutomationJobSettingsState state = AutomationJobSettingsState.initial();
    private Node detail;
    private Optional<Workspace> scopedWorkspace = Optional.empty();
    private boolean rendering;

    /**
     * 创建后台任务管理页。
     *
     * @param gateway 只通过 Java SDK 的异步边界
     */
    public AutomationJobSettingsPage(AutomationJobSettingsGateway gateway) {
        presenter = new AutomationJobSettingsPresenter(gateway);
        previous = action("上一页", ActionStyle.GHOST, presenter::previousPage);
        next = action("下一页", ActionStyle.GHOST, presenter::nextPage);
        pause = action("暂停", ActionStyle.SOFT, presenter::pause);
        resume = action("恢复", ActionStyle.PRIMARY, presenter::resume);
        cancel = action("取消后台任务", ActionStyle.DANGER, this::confirmCancel);
        previous.setId("automationJobPreviousButton");
        next.setId("automationJobNextButton");
        pause.setId("automationJobPauseButton");
        resume.setId("automationJobResumeButton");
        cancel.setId("automationJobCancelButton");
        actions = new AsyncActionBar(pause, resume, cancel);
        conflict = new RevisionConflictPane(presenter::refreshSelected, this::describeConflict);
        configurePage();
        presenter.subscribe(this::render);
        ViewPageReconciler.install(this, () -> scopedWorkspace.isPresent() && !pending(), presenter::reload);
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
        if (scopedWorkspace.isPresent()) {
            presenter.reload();
        }
    }

    @Override
    public void deactivate() {
        presenter.deactivate();
    }

    @Override
    public boolean dirty() {
        return false;
    }

    @Override
    public boolean pending() {
        return state.feedback().phase() == SettingsLoadState.LOADING
                || state.feedback().phase() == SettingsLoadState.SAVING;
    }

    @Override
    public void workspaceChanged(Optional<Workspace> workspace) {
        Optional<Workspace> checked = Objects.requireNonNull(workspace, "workspace");
        if (scopedWorkspace.equals(checked)) {
            return;
        }
        scopedWorkspace = checked;
        if (checked.isPresent()) {
            applyFilter();
        } else {
            presenter.deactivate();
        }
    }

    @Override
    public void warnUnsavedChanges() {}

    @Override
    public void discardDraft() {}

    private void configurePage() {
        Label title = new Label("后台任务");
        title.getStyleClass().addAll("sec-title", "platform-page-title");
        Label hint = new Label("统一查看计划、循环任务、工作流、规格驱动开发（SDD）、定时任务和索引任务。详情只显示脱敏工作单元和检查点提交状态，不显示恢复数据正文。");
        hint.setWrapText(true);
        hint.getStyleClass().add("sec-hint");
        configureFilters();
        configureList();
        VBox.setVgrow(jobs, Priority.ALWAYS);
        getChildren().addAll(title, hint, filters(), pagination(), jobs);
        getStyleClass().add("platform-page");
    }

    private void configureFilters() {
        extension.setPromptText("精确扩展标识，可留空");
        extension.setId("automationJobExtensionFilter");
        extension.setAccessibleText("按扩展标识过滤后台任务");
        extension.setOnAction(event -> applyFilter());
        status.setItems(FXCollections.observableArrayList(STATE_CHOICES));
        status.setId("automationJobStateFilter");
        status.setValue(STATE_CHOICES.getFirst());
        status.setAccessibleText("按执行状态过滤后台任务");
        status.setCellFactory(ignored -> components.textCell(StateChoice::title));
        status.setButtonCell(components.textCell(StateChoice::title));
        status.valueProperty().addListener((observable, previousValue, selected) -> applyFilter());
    }

    private void configureList() {
        jobs.list().setId("automationJobList");
        jobs.list()
                .setCellFactory(ignored -> components.detailCell(
                        job -> job.definitionId() + " · " + SettingsLabels.executionState(job.state()),
                        job -> job.extensionId().value() + " · "
                                + SettingsLabels.automationJobType(job.jobType()) + " · 版本 "
                                + job.revision()));
        jobs.list().getSelectionModel().selectedItemProperty().addListener((observable, previousValue, selected) -> {
            if (!rendering && selected != null) {
                presenter.select(selected);
            }
        });
        jobs.showDetail(components.feedback(FeedbackKind.EMPTY, "选择后台任务", "选择左侧任务查看工作单元、检查点和恢复操作。"));
    }

    private HBox filters() {
        Button apply = action("应用过滤", ActionStyle.SOFT, this::applyFilter);
        Button reload = action("刷新", ActionStyle.GHOST, () -> {
            if (scopedWorkspace.isPresent()) {
                presenter.reload();
            }
        });
        extension.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(extension, Priority.ALWAYS);
        HBox row = new HBox(8, extension, status, apply, reload);
        row.getStyleClass().add("platform-action-bar");
        return row;
    }

    private HBox pagination() {
        pageNumber.getStyleClass().add("sec-hint");
        HBox row = new HBox(8, previous, pageNumber, next);
        row.getStyleClass().add("platform-action-bar");
        return row;
    }

    private Node detail() {
        FormSection identity = new FormSection("执行快照", "任务定义版本和后台任务版本都来自服务端权威摘要。");
        identity.addField("后台任务标识", jobId);
        identity.addField("所属扩展", owner);
        identity.addField("工作区", workspaceId);
        identity.addField("后台任务类型", jobType);
        identity.addField("任务定义", definition);
        identity.addField("状态", executionState);
        identity.addField("版本", revision);
        identity.addField("创建时间", createdAt);
        identity.addField("更新时间", updatedAt);
        FormSection recovery = new FormSection("恢复与错误", "已完成的工作单元表示结果和检查点已安全提交；页面不读取检查点正文。");
        recovery.addField("恢复检查点", checkpoint);
        recovery.addField("错误摘要", error);
        FormSection units = new FormSection("工作单元", "时间线只显示状态、关联任务、副作用凭据键和脱敏错误码。");
        units.addFullWidth(timeline);
        VBox box = new VBox(12, identity, recovery, units, conflict);
        box.getStyleClass().add("platform-page");
        return box;
    }

    private void applyFilter() {
        if (rendering || scopedWorkspace.isEmpty()) {
            return;
        }
        Optional<WorkspaceId> selectedWorkspace = scopedWorkspace.map(Workspace::id);
        Optional<String> extensionId = Optional.of(extension.getText());
        Set<ExecutionState> states = Optional.ofNullable(status.getValue())
                .flatMap(StateChoice::state)
                .map(Set::of)
                .orElseGet(Set::of);
        presenter.filter(selectedWorkspace, extensionId, states);
    }

    private void render(AutomationJobSettingsState snapshot) {
        state = Objects.requireNonNull(snapshot, "snapshot");
        rendering = true;
        try {
            renderFilters(snapshot);
            jobs.list().getItems().setAll(snapshot.page().jobs());
            jobs.list().getSelectionModel().select(snapshot.page().selected().orElse(null));
            pageNumber.setText("第 " + (snapshot.page().index() + 1) + " 页");
            renderPlaceholder(snapshot);
            renderDetail(snapshot);
            renderActions(snapshot);
        } finally {
            rendering = false;
        }
    }

    private void renderFilters(AutomationJobSettingsState snapshot) {
        extension.setText(snapshot.filter().extensionId().orElse(""));
        status.setValue(STATE_CHOICES.stream()
                .filter(choice -> choice.matches(snapshot.filter().states()))
                .findFirst()
                .orElse(STATE_CHOICES.getFirst()));
    }

    private void renderPlaceholder(AutomationJobSettingsState snapshot) {
        Node placeholder;
        if (snapshot.feedback().phase() == SettingsLoadState.LOADING) {
            placeholder = components.feedback(
                    FeedbackKind.LOADING, "正在读取后台任务", snapshot.feedback().message());
        } else if (snapshot.feedback().phase() == SettingsLoadState.ERROR) {
            placeholder = components.feedback(
                    FeedbackKind.ERROR, "后台任务读取失败", snapshot.feedback().message());
        } else {
            placeholder = components.feedback(FeedbackKind.EMPTY, "暂无后台任务", "调整工作区、扩展标识或状态筛选后重试。");
        }
        jobs.list().setPlaceholder(placeholder);
    }

    private void renderDetail(AutomationJobSettingsState snapshot) {
        if (snapshot.page().selected().isEmpty()) {
            jobs.showDetail(components.feedback(FeedbackKind.EMPTY, "选择后台任务", "选择左侧任务查看工作单元、检查点和恢复操作。"));
            return;
        }
        if (detail == null) {
            detail = detail();
        }
        jobs.showDetail(detail);
        ExtensionExecutionReceipt receipt = snapshot.detail()
                .value()
                .map(InputJobRpcContracts.JobReadResult::job)
                .orElseGet(() -> snapshot.page().selected().orElseThrow());
        renderReceipt(receipt);
        snapshot.detail().value().ifPresentOrElse(this::renderUnits, () -> {
            checkpoint.setText("正在读取…");
            error.setText(receipt.errorCode().orElse("—"));
            timeline.setEntries(List.of());
        });
    }

    private void renderReceipt(ExtensionExecutionReceipt receipt) {
        jobId.setText(receipt.id());
        owner.setText(receipt.extensionId().value());
        workspaceId.setText(receipt.workspaceId().toString());
        jobType.setText(SettingsLabels.automationJobType(receipt.jobType()));
        definition.setText(receipt.definitionId() + " · 版本 " + receipt.definitionRevision());
        executionState.setText(SettingsLabels.executionState(receipt.state()));
        revision.setText(Long.toString(receipt.revision()));
        createdAt.setText(receipt.createdAt().toString());
        updatedAt.setText(receipt.updatedAt().toString());
    }

    private void renderUnits(InputJobRpcContracts.JobReadResult result) {
        checkpoint.setText(checkpoint(result.units()));
        error.setText(errorSummary(result));
        List<ExecutionTimeline.Entry> entries = new ArrayList<>();
        entries.add(new ExecutionTimeline.Entry(
                result.job().createdAt(),
                "后台任务已创建",
                result.job().extensionId().value() + " · "
                        + SettingsLabels.automationJobType(result.job().jobType())));
        result.units().stream()
                .sorted(Comparator.comparingLong(InputJobRpcContracts.JobUnitSummary::sequence))
                .map(AutomationJobSettingsPage::timelineEntry)
                .forEach(entries::add);
        entries.add(new ExecutionTimeline.Entry(
                result.job().updatedAt(),
                "当前状态 " + SettingsLabels.executionState(result.job().state()),
                "后台任务版本 " + result.job().revision()));
        timeline.setEntries(entries);
    }

    private void renderActions(AutomationJobSettingsState snapshot) {
        boolean pending = snapshot.feedback().phase() == SettingsLoadState.LOADING
                || snapshot.feedback().phase() == SettingsLoadState.SAVING
                || scopedWorkspace.isEmpty();
        ExtensionExecutionReceipt selected = snapshot.detail()
                .value()
                .map(InputJobRpcContracts.JobReadResult::job)
                .or(() -> snapshot.page().selected())
                .orElse(null);
        previous.setDisable(pending || snapshot.page().index() == 0);
        next.setDisable(pending || snapshot.page().nextCursor().isEmpty());
        extension.setDisable(pending);
        status.setDisable(pending);
        pause.setDisable(pending || selected == null || !pausable(selected.state()));
        resume.setDisable(pending || selected == null || !resumable(selected.state()));
        cancel.setDisable(pending || selected == null || selected.state().terminal());
        showFeedback(snapshot, pending);
        if (snapshot.detail().revisionConflict()) {
            conflict.showMessage("提交所依据的后台任务版本已变化；没有覆盖服务端数据。请刷新详情后重新决定。");
        } else {
            conflict.hide();
        }
    }

    private void showFeedback(AutomationJobSettingsState snapshot, boolean pending) {
        if (pending) {
            actions.show(ActionState.PENDING, snapshot.feedback().message());
        } else if (snapshot.feedback().phase() == SettingsLoadState.ERROR) {
            actions.show(ActionState.ERROR, snapshot.feedback().message());
        } else {
            ActionState kind = snapshot.feedback().message().isBlank() ? ActionState.IDLE : ActionState.SUCCESS;
            actions.show(kind, snapshot.feedback().message());
        }
    }

    private void confirmCancel() {
        ExtensionExecutionReceipt selected = state.page().selected().orElseThrow();
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
        dialog.setTitle("取消可恢复后台任务");
        dialog.setHeaderText("取消后不会启动新的工作单元");
        dialog.setContentText(selected.id() + " · 当前版本 " + selected.revision());
        PlatformDialogs.style(dialog, this);
        dialog.showAndWait().filter(ButtonType.OK::equals).ifPresent(ignored -> presenter.cancel());
    }

    private void describeConflict() {
        actions.show(ActionState.ERROR, "当前列表摘要与服务端版本不一致；刷新详情不会重复执行已完成的工作单元。");
    }

    private Button action(String text, ActionStyle style, Runnable action) {
        Button button = components.action(text, style, ActionSize.COMPACT);
        button.setOnAction(event -> action.run());
        return button;
    }

    private static ExecutionTimeline.Entry timelineEntry(InputJobRpcContracts.JobUnitSummary unit) {
        StringBuilder detail = new StringBuilder(
                unit.state() == ExtensionJobUnitState.COMPLETED
                        ? "结果与检查点已安全提交"
                        : unit.state() == ExtensionJobUnitState.INTENT_RECORDED ? "确定性意图已持久化，等待结果提交" : "工作单元失败");
        unit.turnId().ifPresent(value -> detail.append(" · 任务 ").append(value));
        unit.effectReceiptKey().ifPresent(value -> detail.append(" · 副作用凭据 ").append(value));
        unit.errorCode().ifPresent(value -> detail.append(" · 错误 ").append(value));
        return new ExecutionTimeline.Entry(
                unit.completedAt().orElse(unit.createdAt()),
                "#" + unit.sequence() + " " + unit.unitId() + " · "
                        + SettingsLabels.extensionJobUnitState(unit.state()),
                detail.toString());
    }

    private static String checkpoint(List<InputJobRpcContracts.JobUnitSummary> units) {
        Optional<InputJobRpcContracts.JobUnitSummary> committed = units.stream()
                .filter(unit -> unit.state() == ExtensionJobUnitState.COMPLETED)
                .max(Comparator.comparingLong(InputJobRpcContracts.JobUnitSummary::sequence));
        Optional<InputJobRpcContracts.JobUnitSummary> active = units.stream()
                .filter(unit -> unit.state() == ExtensionJobUnitState.INTENT_RECORDED)
                .max(Comparator.comparingLong(InputJobRpcContracts.JobUnitSummary::sequence));
        String stable = committed
                .map(unit -> "已提交至 #" + unit.sequence() + " " + unit.unitId())
                .orElse("尚无已提交的检查点");
        return active.map(unit -> stable + "；活动单元 #" + unit.sequence() + " 意图已持久化")
                .orElse(stable);
    }

    private static String errorSummary(InputJobRpcContracts.JobReadResult result) {
        return result.job()
                .errorCode()
                .or(() -> result.units().stream()
                        .flatMap(unit -> unit.errorCode().stream())
                        .reduce((first, second) -> second))
                .orElse("—");
    }

    private static boolean pausable(ExecutionState state) {
        return state == ExecutionState.QUEUED
                || state == ExecutionState.RUNNING
                || state == ExecutionState.WAITING_APPROVAL
                || state == ExecutionState.WAITING_INPUT;
    }

    private static boolean resumable(ExecutionState state) {
        return state == ExecutionState.PAUSED
                || state == ExecutionState.WAITING_APPROVAL
                || state == ExecutionState.WAITING_INPUT;
    }

    private static Label value() {
        Label label = new Label("—");
        label.setWrapText(true);
        label.getStyleClass().add("platform-detail-text");
        return label;
    }

    private static List<StateChoice> stateChoices() {
        List<StateChoice> choices = new ArrayList<>();
        choices.add(new StateChoice(Optional.empty(), "全部状态"));
        for (ExecutionState state : ExecutionState.values()) {
            choices.add(new StateChoice(Optional.of(state), SettingsLabels.executionState(state)));
        }
        return List.copyOf(choices);
    }

    private record StateChoice(Optional<ExecutionState> state, String title) {
        private StateChoice {
            state = Objects.requireNonNull(state, "state");
            title = Objects.requireNonNull(title, "title");
        }

        private boolean matches(Set<ExecutionState> states) {
            return state.map(value -> states.size() == 1 && states.contains(value))
                    .orElse(states.isEmpty());
        }
    }
}
