package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;

import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import com.javaclaw.api.SecurityGrantState;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.UnattendedToolGrant;
import com.javaclaw.api.UnattendedToolGrantStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.builtin.contracts.ScheduleContracts;
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

/** 定时任务专用的无人值守工具授权管理页面。 */
public final class UnattendedToolGrantSettingsPage extends VBox implements ManagedSettingsPage {
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final UnattendedToolGrantSettingsPresenter presenter;
    private final ListDetailPane<UnattendedToolGrantStatus> grants = new ListDetailPane<>();
    private final ComboBox<ScheduleContracts.Definition> schedule = new ComboBox<>();
    private final Label scheduleReference = value();
    private final Label agentProfileReference = value();
    private final ComboBox<ToolDescriptor> tool = new ComboBox<>();
    private final Label producerId = value();
    private final Label toolRevision = value();
    private final Label catalogRevision = value();
    private final Label schemaHash = value();
    private final TextArea fixedArguments = new TextArea("{}");
    private final TextField variableFields = field("允许变化的顶层字符串字段");
    private final TextField maximumUses = new TextField("1");
    private final TextField validityDays = new TextField("1");
    private final Label grantId = value();
    private final Label grantState = value();
    private final Label grantSchedule = value();
    private final Label grantTool = value();
    private final Label grantCatalog = value();
    private final Label grantSchema = value();
    private final Label grantUses = value();
    private final Label grantExpiry = value();
    private final Label grantArguments = value();
    private final Label grantVariables = value();
    private final ExecutionTimeline decisions = new ExecutionTimeline();
    private final Button create;
    private final Button revoke;
    private final AsyncActionBar actions;
    private UnattendedToolGrantSettingsState state = UnattendedToolGrantSettingsState.initial();
    private Node detail;
    private Optional<Workspace> scopedWorkspace = Optional.empty();
    private boolean rendering;

    /**
     * 创建无人值守授权页面。
     *
     * @param gateway 强类型 SDK 设置边界
     * @param schedules Schedule 强类型目录边界
     */
    public UnattendedToolGrantSettingsPage(CoreSettingsGateway gateway, ScheduleCatalogGateway schedules) {
        presenter = new UnattendedToolGrantSettingsPresenter(gateway, schedules);
        create = components.action("审核并创建", ActionStyle.PRIMARY, ActionSize.NORMAL);
        create.setOnAction(event -> confirmCreate());
        revoke = components.action("撤销授权", ActionStyle.DANGER, ActionSize.NORMAL);
        revoke.setOnAction(event -> confirmRevoke());
        actions = new AsyncActionBar(create);
        configurePage();
        presenter.subscribe(this::render);
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
        scopedWorkspace.ifPresent(presenter::selectWorkspace);
    }

    @Override
    public boolean dirty() {
        return state.dirty();
    }

    @Override
    public boolean pending() {
        return state.phase() == SettingsLoadState.LOADING || state.phase() == SettingsLoadState.SAVING;
    }

    @Override
    public void workspaceChanged(Optional<Workspace> workspace) {
        Optional<Workspace> checked = Objects.requireNonNull(workspace, "workspace");
        if (scopedWorkspace.equals(checked)) {
            return;
        }
        scopedWorkspace = checked;
        if (checked.isPresent()) {
            presenter.selectWorkspace(checked.orElseThrow());
        } else {
            presenter.scopeUnavailable();
        }
        renderActions(state);
    }

    @Override
    public void warnUnsavedChanges() {
        actions.show(ActionState.DIRTY, "请先创建或丢弃无人值守授权草稿");
    }

    @Override
    public void discardDraft() {
        presenter.discardDraft();
    }

    private void configurePage() {
        Label title = new Label("无人值守授权");
        title.getStyleClass().addAll("sec-title", "platform-page-title");
        Label hint = new Label("此授权只允许固定版本的定时任务按严格模板调用一个固定工具。结果未知的调用也会消耗额度，且不会自动重试。");
        hint.setWrapText(true);
        hint.getStyleClass().add("sec-hint");
        configureGrantList();
        getChildren().addAll(title, hint, definitionSection(), toolSection(), limitSection(), grants);
        getStyleClass().add("platform-page");
    }

    private FormSection definitionSection() {
        FormSection section = new FormSection("定时任务绑定", "定时任务更新版本后，不会自动扩大或复用旧授权。");
        configureScheduleChoice();
        scheduleReference.setId("unattendedScheduleReference");
        agentProfileReference.setId("unattendedAgentProfileReference");
        section.addField("定时任务", schedule);
        section.addField("精确版本", scheduleReference);
        section.addField("智能体方案", agentProfileReference);
        return section;
    }

    private FormSection toolSection() {
        FormSection section = new FormSection("固定工具", "工具提供方、工具版本、目录版本和输入结构指纹必须全部精确匹配。");
        configureToolChoice();
        producerId.setId("unattendedToolProducer");
        toolRevision.setId("unattendedToolRevision");
        catalogRevision.setId("unattendedCatalogRevision");
        schemaHash.setId("unattendedToolSchemaHash");
        fixedArguments.setPrefRowCount(5);
        fixedArguments.setAccessibleText("固定参数 JSON 对象");
        fixedArguments.textProperty().addListener((observable, previous, value) -> edit());
        variableFields.textProperty().addListener((observable, previous, value) -> edit());
        section.addField("工具目录", tool);
        section.addField("工具提供方", producerId);
        section.addField("工具版本", toolRevision);
        section.addField("目录版本", catalogRevision);
        section.addField("输入结构指纹（SHA-256）", schemaHash);
        section.addField("固定参数", fixedArguments);
        section.addField("可变字符串字段", variableFields);
        return section;
    }

    private FormSection limitSection() {
        FormSection section = new FormSection("额度与期限", "最多 100 次、最长 30 天；敏感和高风险字段不能成为可变槽位。");
        maximumUses.setAccessibleText("无人值守授权最大次数");
        validityDays.setAccessibleText("无人值守授权有效天数");
        maximumUses.textProperty().addListener((observable, previous, value) -> edit());
        validityDays.textProperty().addListener((observable, previous, value) -> edit());
        section.addField("最大次数", maximumUses);
        section.addField("有效天数", validityDays);
        Label forbidden = new Label("收件人、来源地址、密钥、命令、浏览器、交互终端、隔离工作区和任意网络参数都不能作为可变槽位。");
        forbidden.setWrapText(true);
        forbidden.getStyleClass().add("sec-hint");
        section.addFullWidth(forbidden);
        return section;
    }

    private void configureGrantList() {
        grants.list()
                .setCellFactory(ignored -> components.detailCell(
                        value -> value.grant().scheduleId() + " · "
                                + value.grant().tool().name(),
                        value -> SettingsLabels.securityGrantState(value.grant().state()) + " · 剩余 "
                                + value.remainingUses() + "/"
                                + value.grant().maximumUses()));
        grants.list()
                .getSelectionModel()
                .selectedItemProperty()
                .addListener((observable, previous, selected) -> selectGrant(selected));
        grants.list().setPlaceholder(components.feedback(FeedbackKind.EMPTY, "暂无无人值守授权", "只有定时任务可以消费此处创建的严格限额授权。"));
        grants.showDetail(components.feedback(FeedbackKind.EMPTY, "选择授权", "选择左侧授权查看冻结工具、余额与决策记录。"));
    }

    private Node grantDetail() {
        FormSection snapshot = new FormSection("授权快照", "授权内容不可编辑；如需改变必须撤销并创建新授权。");
        snapshot.addField("授权标识", grantId);
        snapshot.addField("状态", grantState);
        snapshot.addField("定时任务", grantSchedule);
        snapshot.addField("工具", grantTool);
        snapshot.addField("目录版本", grantCatalog);
        snapshot.addField("输入结构指纹（SHA-256）", grantSchema);
        snapshot.addField("使用余额", grantUses);
        snapshot.addField("到期", grantExpiry);
        snapshot.addField("固定参数", grantArguments);
        snapshot.addField("可变字段", grantVariables);
        FormSection audit = new FormSection("权限决策", "逐层决策不包含密钥或参数正文。");
        audit.addFullWidth(decisions);
        VBox box = new VBox(12, snapshot, audit, revoke);
        box.getStyleClass().add("platform-page");
        return box;
    }

    private void edit() {
        if (!rendering) {
            presenter.editArguments(
                    fixedArguments.getText(), variableFields.getText(), maximumUses.getText(), validityDays.getText());
        }
    }

    private void selectGrant(UnattendedToolGrantStatus selected) {
        if (!rendering) {
            presenter.selectGrant(selected);
        }
    }

    private void render(UnattendedToolGrantSettingsState snapshot) {
        state = Objects.requireNonNull(snapshot, "snapshot");
        rendering = true;
        try {
            renderDraft(snapshot.draft());
            renderBinding(snapshot.binding());
            grants.list().getItems().setAll(snapshot.grants());
            grants.list().getSelectionModel().select(snapshot.selected().orElse(null));
            renderSelected(snapshot.selected().orElse(null));
            renderActions(snapshot);
        } finally {
            rendering = false;
        }
    }

    private void renderDraft(UnattendedToolGrantForm draft) {
        fixedArguments.setText(draft.fixedArguments());
        variableFields.setText(draft.variableFields());
        maximumUses.setText(draft.maximumUses());
        validityDays.setText(draft.validityDays());
    }

    private void renderSelected(UnattendedToolGrantStatus selected) {
        if (selected == null) {
            grants.showDetail(components.feedback(FeedbackKind.EMPTY, "选择授权", "选择左侧授权查看冻结工具、余额与决策记录。"));
            return;
        }
        if (detail == null) {
            detail = grantDetail();
        }
        grants.showDetail(detail);
        UnattendedToolGrant grant = selected.grant();
        grantId.setText(grant.id() + " · 版本 " + grant.revision());
        grantState.setText(SettingsLabels.securityGrantState(grant.state()));
        grantSchedule.setText(grant.scheduleId() + " @ " + grant.scheduleRevision());
        grantTool.setText(grant.tool().producerId() + ":" + grant.tool().name() + " @ "
                + grant.tool().revision());
        grantCatalog.setText(Long.toString(grant.catalogRevision()));
        grantSchema.setText(grant.schemaHash());
        grantUses.setText(selected.consumedUses() + " 已用 / " + selected.remainingUses() + " 剩余");
        grantExpiry.setText(grant.expiresAt().toString());
        grantArguments.setText(grant.fixedArguments().json());
        grantVariables.setText(String.join(", ", grant.variableStringFields()));
        decisions.setEntries(state.decisions().stream()
                .filter(trace -> trace.grantId().equals(grant.id()))
                .map(trace -> new ExecutionTimeline.Entry(
                        trace.decidedAt(),
                        trace.allowed() ? "允许" : "拒绝",
                        trace.operation() + " · " + trace.resource()
                                + trace.denialReason()
                                        .map(reason -> " · " + reason)
                                        .orElse("")))
                .toList());
        revoke.setDisable(state.phase() == SettingsLoadState.LOADING || grant.state() != SecurityGrantState.ACTIVE);
    }

    private void renderActions(UnattendedToolGrantSettingsState snapshot) {
        boolean pending = snapshot.phase() == SettingsLoadState.LOADING || snapshot.phase() == SettingsLoadState.SAVING;
        create.setDisable(
                pending || scopedWorkspace.isEmpty() || snapshot.workspace().isEmpty() || !snapshot.dirty());
        setDraftDisabled(pending || scopedWorkspace.isEmpty());
        if (pending) {
            actions.show(ActionState.PENDING, snapshot.message());
        } else if (snapshot.phase() == SettingsLoadState.ERROR) {
            actions.show(ActionState.ERROR, snapshot.message());
        } else if (snapshot.dirty()) {
            actions.show(ActionState.DIRTY, snapshot.message());
        } else {
            actions.show(snapshot.message().isBlank() ? ActionState.IDLE : ActionState.SUCCESS, snapshot.message());
        }
    }

    private void setDraftDisabled(boolean disabled) {
        schedule.setDisable(disabled || state.binding().schedules().isEmpty());
        tool.setDisable(disabled || state.binding().catalog().isEmpty());
        fixedArguments.setDisable(disabled);
        variableFields.setDisable(disabled);
        maximumUses.setDisable(disabled);
        validityDays.setDisable(disabled);
    }

    private void configureScheduleChoice() {
        schedule.setId("scheduleCatalogChoice");
        schedule.setMaxWidth(Double.MAX_VALUE);
        schedule.setPromptText("选择精确定时任务");
        schedule.setCellFactory(ignored -> components.detailCell(
                ScheduleContracts.Definition::name, value -> value.id() + " · 版本 " + value.revision()));
        schedule.setButtonCell(components.textCell(
                value -> value == null ? "" : value.name() + " · " + value.id() + " @ " + value.revision()));
        schedule.valueProperty().addListener((ignored, previous, selected) -> {
            if (!rendering && selected != null) {
                presenter.selectSchedule(selected);
            }
        });
    }

    private void configureToolChoice() {
        tool.setId("unattendedToolCatalogChoice");
        tool.setMaxWidth(Double.MAX_VALUE);
        tool.setPromptText("选择实际可执行工具");
        tool.setCellFactory(ignored -> components.detailCell(
                value -> value.identity().name(),
                value -> value.identity().producerId() + " · 版本 "
                        + value.identity().revision()));
        tool.setButtonCell(components.textCell(value -> value == null
                ? ""
                : value.identity().name() + " · " + value.identity().producerId()));
        tool.valueProperty().addListener((ignored, previous, selected) -> {
            if (!rendering && selected != null) {
                presenter.selectTool(selected);
            }
        });
    }

    private void renderBinding(UnattendedGrantBindingState binding) {
        schedule.getItems().setAll(binding.schedules());
        schedule.setValue(binding.schedule().orElse(null));
        scheduleReference.setText(binding.schedule()
                .map(value -> value.id() + " @ " + value.revision())
                .orElse("—"));
        agentProfileReference.setText(binding.agentProfile()
                .map(value -> value.id() + " @ " + value.revision())
                .orElse("—"));
        tool.getItems().setAll(binding.catalog().map(value -> value.tools()).orElse(java.util.List.of()));
        tool.setValue(binding.tool().orElse(null));
        producerId.setText(
                binding.tool().map(value -> value.identity().producerId()).orElse("—"));
        toolRevision.setText(binding.tool()
                .map(value -> Long.toString(value.identity().revision()))
                .orElse("—"));
        catalogRevision.setText(binding.catalog()
                .map(value -> Long.toString(value.catalogRevision()))
                .orElse("—"));
        schemaHash.setText(
                binding.tool().map(value -> value.inputSchema().sha256()).orElse("—"));
    }

    private void confirmCreate() {
        Alert alert = new Alert(
                Alert.AlertType.CONFIRMATION,
                "此授权允许定时任务在无人值守时产生工具副作用。请确认固定参数、可变字段、次数与期限均为最小必要范围。",
                ButtonType.CANCEL,
                ButtonType.OK);
        alert.setTitle("确认无人值守授权");
        alert.setHeaderText("创建严格限额工具授权？");
        PlatformDialogs.style(alert, this);
        if (alert.showAndWait().filter(ButtonType.OK::equals).isPresent()) {
            presenter.create();
        }
    }

    private void confirmRevoke() {
        Alert alert =
                new Alert(Alert.AlertType.CONFIRMATION, "撤销不可恢复，后续定时任务工具调用会立即失败。", ButtonType.CANCEL, ButtonType.OK);
        alert.setTitle("撤销无人值守授权");
        alert.setHeaderText(state.selected().orElseThrow().grant().id());
        PlatformDialogs.style(alert, this);
        if (alert.showAndWait().filter(ButtonType.OK::equals).isPresent()) {
            presenter.revoke();
        }
    }

    private static TextField field(String accessibleText) {
        TextField field = new TextField();
        field.setAccessibleText(accessibleText);
        return field;
    }

    private static Label value() {
        Label label = new Label("—");
        label.setWrapText(true);
        label.getStyleClass().add("platform-detail-text");
        return label;
    }
}
