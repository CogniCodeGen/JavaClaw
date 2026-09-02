package com.javaclaw.desktop.settings;

import java.util.Objects;

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
import com.javaclaw.api.UnattendedToolGrant;
import com.javaclaw.api.UnattendedToolGrantStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.desktop.component.AsyncActionBar;
import com.javaclaw.desktop.component.AsyncActionBar.ActionState;
import com.javaclaw.desktop.component.ExecutionTimeline;
import com.javaclaw.desktop.component.FormSection;
import com.javaclaw.desktop.component.ListDetailPane;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.desktop.component.PlatformComponentFactory.FeedbackKind;

/** Schedule 专用无人值守 Tool Grant 管理页面。 */
public final class UnattendedToolGrantSettingsPage extends VBox implements ManagedSettingsPage {
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final UnattendedToolGrantSettingsPresenter presenter;
    private final ComboBox<Workspace> workspace = new ComboBox<>();
    private final ListDetailPane<UnattendedToolGrantStatus> grants = new ListDetailPane<>();
    private final TextField scheduleId = field("Schedule 定义 ID");
    private final TextField scheduleRevision = field("Schedule revision");
    private final TextField producerId = field("工具 producer ID");
    private final TextField toolName = field("工具名称");
    private final TextField toolRevision = field("工具 revision");
    private final TextField catalogRevision = field("Catalog revision");
    private final TextField schemaHash = field("输入 Schema SHA-256");
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
    private boolean rendering;

    /** @param gateway 强类型 SDK 设置边界 */
    public UnattendedToolGrantSettingsPage(CoreSettingsGateway gateway) {
        presenter = new UnattendedToolGrantSettingsPresenter(gateway);
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
    public void activate() {
        presenter.reload();
    }

    @Override
    public boolean dirty() {
        return state.dirty();
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
        Label hint = new Label("此授权只允许固定 Schedule revision 在严格模板内调用一个冻结工具；UNKNOWN_OUTCOME 也消耗额度且不自动重试。");
        hint.setWrapText(true);
        hint.getStyleClass().add("sec-hint");
        configureWorkspace();
        configureGrantList();
        getChildren()
                .addAll(title, hint, workspace, definitionSection(), toolSection(), limitSection(), actions, grants);
        getStyleClass().add("platform-page");
    }

    private void configureWorkspace() {
        workspace.setMaxWidth(Double.MAX_VALUE);
        workspace.setAccessibleText("无人值守授权所属 Workspace");
        workspace.setPromptText("选择 Workspace");
        workspace.setCellFactory(ignored -> components.detailCell(
                Workspace::name, value -> value.id().value() + " · revision " + value.revision()));
        workspace.setButtonCell(components.textCell(Workspace::name));
        workspace.valueProperty().addListener((observable, previous, selected) -> selectWorkspace(selected));
    }

    private FormSection definitionSection() {
        FormSection section = new FormSection("Schedule 绑定", "Definition 的新 revision 不会扩大或复用旧授权。");
        bind(scheduleId);
        bind(scheduleRevision);
        section.addField("Schedule ID", scheduleId);
        section.addField("Schedule revision", scheduleRevision);
        return section;
    }

    private FormSection toolSection() {
        FormSection section = new FormSection("冻结工具", "来源、工具 revision、Catalog revision 和 Schema hash 必须全部精确匹配。");
        bind(producerId);
        bind(toolName);
        bind(toolRevision);
        bind(catalogRevision);
        bind(schemaHash);
        fixedArguments.setPrefRowCount(5);
        fixedArguments.setAccessibleText("固定参数 JSON object");
        fixedArguments.textProperty().addListener((observable, previous, value) -> edit());
        variableFields.textProperty().addListener((observable, previous, value) -> edit());
        section.addField("Producer", producerId);
        section.addField("工具名称", toolName);
        section.addField("工具 revision", toolRevision);
        section.addField("Catalog revision", catalogRevision);
        section.addField("Schema SHA-256", schemaHash);
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
        Label forbidden = new Label("收件人、Origin、Secret、命令、Browser、PTY、Worktree 和任意网络参数不得作为可变槽位。");
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
                        value -> value.grant().state() + " · 剩余 " + value.remainingUses() + "/"
                                + value.grant().maximumUses()));
        grants.list()
                .getSelectionModel()
                .selectedItemProperty()
                .addListener((observable, previous, selected) -> selectGrant(selected));
        grants.list()
                .setPlaceholder(components.feedback(FeedbackKind.EMPTY, "暂无无人值守授权", "只有 Schedule 可以消费此处创建的严格限额授权。"));
        grants.showDetail(components.feedback(FeedbackKind.EMPTY, "选择授权", "选择左侧授权查看冻结工具、余额与决策记录。"));
    }

    private Node grantDetail() {
        FormSection snapshot = new FormSection("授权快照", "授权内容不可编辑；如需改变必须撤销并创建新授权。");
        snapshot.addField("授权 ID", grantId);
        snapshot.addField("状态", grantState);
        snapshot.addField("Schedule", grantSchedule);
        snapshot.addField("工具", grantTool);
        snapshot.addField("Catalog revision", grantCatalog);
        snapshot.addField("Schema SHA-256", grantSchema);
        snapshot.addField("使用余额", grantUses);
        snapshot.addField("到期", grantExpiry);
        snapshot.addField("固定参数", grantArguments);
        snapshot.addField("可变字段", grantVariables);
        FormSection audit = new FormSection("权限决策", "逐层决策不包含 Secret 或参数正文。");
        audit.addFullWidth(decisions);
        VBox box = new VBox(12, snapshot, audit, revoke);
        box.getStyleClass().add("platform-page");
        return box;
    }

    private void bind(TextField value) {
        value.textProperty().addListener((observable, previous, current) -> edit());
    }

    private void edit() {
        if (!rendering) {
            presenter.edit(new UnattendedToolGrantForm(
                    scheduleId.getText(),
                    scheduleRevision.getText(),
                    producerId.getText(),
                    toolName.getText(),
                    toolRevision.getText(),
                    catalogRevision.getText(),
                    schemaHash.getText(),
                    fixedArguments.getText(),
                    variableFields.getText(),
                    maximumUses.getText(),
                    validityDays.getText()));
        }
    }

    private void selectWorkspace(Workspace selected) {
        if (rendering || selected == null || selected.equals(state.workspace().orElse(null))) {
            return;
        }
        if (dirty()) {
            warnUnsavedChanges();
            restoreWorkspaceSelection();
            return;
        }
        presenter.selectWorkspace(selected);
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
            workspace.getItems().setAll(snapshot.workspaces());
            workspace.setValue(snapshot.workspace().orElse(null));
            renderDraft(snapshot.draft());
            grants.list().getItems().setAll(snapshot.grants());
            grants.list().getSelectionModel().select(snapshot.selected().orElse(null));
            renderSelected(snapshot.selected().orElse(null));
            renderActions(snapshot);
        } finally {
            rendering = false;
        }
    }

    private void renderDraft(UnattendedToolGrantForm draft) {
        scheduleId.setText(draft.scheduleId());
        scheduleRevision.setText(draft.scheduleRevision());
        producerId.setText(draft.producerId());
        toolName.setText(draft.toolName());
        toolRevision.setText(draft.toolRevision());
        catalogRevision.setText(draft.catalogRevision());
        schemaHash.setText(draft.schemaHash());
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
        grantId.setText(grant.id() + " · revision " + grant.revision());
        grantState.setText(grant.state().name());
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
        create.setDisable(pending || snapshot.workspace().isEmpty() || !snapshot.dirty());
        workspace.setDisable(pending);
        setDraftDisabled(pending);
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
        scheduleId.setDisable(disabled);
        scheduleRevision.setDisable(disabled);
        producerId.setDisable(disabled);
        toolName.setDisable(disabled);
        toolRevision.setDisable(disabled);
        catalogRevision.setDisable(disabled);
        schemaHash.setDisable(disabled);
        fixedArguments.setDisable(disabled);
        variableFields.setDisable(disabled);
        maximumUses.setDisable(disabled);
        validityDays.setDisable(disabled);
    }

    private void confirmCreate() {
        Alert alert = new Alert(
                Alert.AlertType.CONFIRMATION,
                "此授权允许 Schedule 在无人值守时产生工具副作用。请确认固定参数、可变字段、次数与期限均为最小必要范围。",
                ButtonType.CANCEL,
                ButtonType.OK);
        own(alert);
        alert.setTitle("确认无人值守授权");
        alert.setHeaderText("创建严格限额 Tool Grant？");
        if (alert.showAndWait().filter(ButtonType.OK::equals).isPresent()) {
            presenter.create();
        }
    }

    private void confirmRevoke() {
        Alert alert = new Alert(
                Alert.AlertType.CONFIRMATION, "撤销不可恢复，后续 Schedule 工具调用会立即失败。", ButtonType.CANCEL, ButtonType.OK);
        own(alert);
        alert.setTitle("撤销无人值守授权");
        alert.setHeaderText(state.selected().orElseThrow().grant().id());
        if (alert.showAndWait().filter(ButtonType.OK::equals).isPresent()) {
            presenter.revoke();
        }
    }

    private void own(Alert alert) {
        if (getScene() != null && getScene().getWindow() != null) {
            alert.initOwner(getScene().getWindow());
        }
    }

    private void restoreWorkspaceSelection() {
        rendering = true;
        try {
            workspace.setValue(state.workspace().orElse(null));
        } finally {
            rendering = false;
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
