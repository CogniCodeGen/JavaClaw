package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Optional;

import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.PermissionConstraint;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ReasoningPreference;
import com.javaclaw.api.RoleLifecycle;
import com.javaclaw.api.Workspace;
import com.javaclaw.desktop.component.AlertDangerConfirmationPolicy;
import com.javaclaw.desktop.component.AsyncActionBar;
import com.javaclaw.desktop.component.AsyncActionBar.ActionState;
import com.javaclaw.desktop.component.DangerZone;
import com.javaclaw.desktop.component.FormSection;
import com.javaclaw.desktop.component.ListDetailPane;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.desktop.component.PlatformDialogs;
import com.javaclaw.desktop.component.RevisionConflictPane;

/** 沿用管理中心控件和布局的 Agent Studio；角色只编辑行为、可选模型偏好与能力收窄。 */
public final class AgentRoleSettingsPage implements ManagedSettingsPage {
    private final SettingsPageRefresh configurationRefresh;
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final AgentRoleSettingsPresenter presenter;
    private final VBox content = components.page("Agent Studio");
    private final ListDetailPane<AgentRole> masterDetail = new ListDetailPane<>();
    private final TextField id = new TextField();
    private final TextField name = new TextField();
    private final TextField description = new TextField();
    private final TextArea instructions = new TextArea();
    private final ComboBox<ProviderEndpoint> provider = new ComboBox<>();
    private final ComboBox<String> model = new ComboBox<>();
    private final ComboBox<ReasoningPreference> reasoning = new ComboBox<>();
    private final ComboBox<PermissionConstraint> constraint = new ComboBox<>();
    private final TextArea capabilities = new TextArea();
    private final TextArea skills = new TextArea();
    private final ComboBox<RoleLifecycle> lifecycle = new ComboBox<>();
    private final VBox editor = new VBox(12);
    private final Label readOnly = new Label();
    private final Label modelReference = new Label();
    private final Button create;
    private final Button reload;
    private final Button save;
    private final Button discard;
    private final Button clone;
    private final AsyncActionBar actions;
    private final RevisionConflictPane conflict;
    private final DangerZone dangerZone;
    private final PromptPreviewPanel promptPreview;
    private final PromptOptimizationPanel promptOptimization;
    private final AgentRoleFilePanel files;
    private boolean rendering;

    /**
     * 创建 Agent Studio 页面。
     *
     * @param gateway 角色和 Provider SDK 边界
     * @param promptGateway 提示词来源 SDK 边界
     * @param optimizationGateway 提示词优化 SDK 边界
     */
    public AgentRoleSettingsPage(
            CoreSettingsGateway gateway,
            PromptPreviewSettingsGateway promptGateway,
            PromptOptimizationSettingsGateway optimizationGateway) {
        presenter = new AgentRoleSettingsPresenter(gateway);
        create = components.action("新建", ActionStyle.PRIMARY, ActionSize.COMPACT);
        reload = components.action("刷新", ActionStyle.GHOST, ActionSize.COMPACT);
        save = components.action("保存 Agent", ActionStyle.PRIMARY, ActionSize.NORMAL);
        discard = components.action("放弃更改", ActionStyle.GHOST, ActionSize.NORMAL);
        clone = components.action("复制为自定义 Agent", ActionStyle.SOFT, ActionSize.COMPACT);
        actions = new AsyncActionBar(discard, save);
        conflict = new RevisionConflictPane(this::reloadAfterDiscard, this::compare);
        dangerZone = new DangerZone(
                "归档 Agent",
                "归档后不能用于新任务；活动任务继续使用已冻结的快照。",
                "归档当前 Agent",
                new AlertDangerConfirmationPolicy(content),
                presenter::archive);
        promptPreview = new PromptPreviewPanel(promptGateway);
        promptOptimization = new PromptOptimizationPanel(optimizationGateway, this::requestReload);
        files = new AgentRoleFilePanel(gateway, presenter::acceptImported);
        configureControls();
        buildLayout();
        bindEvents();
        configurationRefresh = SettingsPageRefresh.roles(gateway, this, presenter);
        files.onPendingChanged(this::updateInteractionState);
        promptOptimization.onPendingChanged(this::updateInteractionState);
        presenter.subscribe(this::render);
    }

    @Override
    public Node content() {
        return content;
    }

    @Override
    public Optional<Node> actionContent() {
        return Optional.of(actions);
    }

    @Override
    public void activate() {
        configurationRefresh.activate();
        promptPreview.activate();
        promptOptimization.activate();
    }

    @Override
    public boolean dirty() {
        return presenter.state().dirty();
    }

    @Override
    public boolean pending() {
        return presenter.state().pending() || promptOptimization.pending() || files.pending();
    }

    @Override
    public void workspaceChanged(Optional<Workspace> workspace) {
        promptPreview.workspaceChanged(workspace);
        promptOptimization.workspaceChanged(workspace);
    }

    @Override
    public void warnUnsavedChanges() {
        presenter.warnUnsavedChanges();
    }

    @Override
    public void discardDraft() {
        presenter.discardDraft();
    }

    @Override
    public void dispose() {
        configurationRefresh.close();
        presenter.dispose();
        files.dispose();
        promptOptimization.onPendingChanged(() -> {});
    }

    @Override
    public void deactivate() {
        configurationRefresh.deactivate();
    }

    private void configureControls() {
        id.setPromptText("例如 research-helper");
        name.setPromptText("Agent 名称");
        description.setPromptText("适用任务与职责边界");
        instructions.setPromptText("描述角色行为和工作偏好");
        instructions.setPrefRowCount(6);
        provider.setPromptText("继承执行配置");
        provider.setId("roleProvider");
        provider.setCellFactory(
                ignored -> components.textCell(endpoint -> endpoint.spec().displayName()));
        provider.setButtonCell(components.textCell(endpoint -> endpoint.spec().displayName()));
        model.setPromptText("选择固定模型");
        model.setId("roleModel");
        reasoning.setItems(FXCollections.observableArrayList(ReasoningPreference.values()));
        reasoning.setPromptText("继承推理设置");
        capabilities.setPromptText("* 表示继承；每行一个能力名；空白表示禁用全部");
        capabilities.setPrefRowCount(3);
        skills.setPromptText("* 表示继承；每行一个 Skill；空白表示禁用全部");
        skills.setPrefRowCount(3);
        constraint.setItems(FXCollections.observableArrayList(PermissionConstraint.values()));
        lifecycle.setItems(FXCollections.observableArrayList(RoleLifecycle.ACTIVE, RoleLifecycle.DISABLED));
        lifecycle.setConverter(SettingsLabels.converter(SettingsLabels::roleLifecycle));
        readOnly.setWrapText(true);
        readOnly.getStyleClass().add("sec-hint");
        modelReference.setWrapText(true);
        modelReference.getStyleClass().add("sec-hint");
    }

    private void buildLayout() {
        Label hint = new Label("Agent 定义职责和行为。模型、权限、审批与预算在执行配置中独立选择，修改只影响新任务。");
        hint.setWrapText(true);
        hint.getStyleClass().add("sec-hint");
        create.setId("roleCreateButton");
        create.setOnAction(event -> presenter.createDraft());
        reload.setOnAction(event -> requestReload());
        masterDetail
                .list()
                .setCellFactory(ignored -> components.detailCell(
                        role -> role.spec().name(),
                        role -> role.id() + " · 版本 " + role.revision() + (role.builtin() ? " · 内置只读" : "")));
        editor.getChildren().addAll(identitySection(), narrowingSection());
        VBox form = new VBox(
                12,
                readOnly,
                editor,
                files.content(),
                promptPreview.content(),
                promptOptimization.content(),
                conflict,
                dangerZone);
        masterDetail.showDetail(form);
        VBox.setVgrow(masterDetail, Priority.ALWAYS);
        content.getChildren().addAll(hint, new HBox(8, create, clone, reload), masterDetail);
    }

    private FormSection identitySection() {
        FormSection section = new FormSection("名称与角色指令", "内置角色随发行版本冻结，可复制为自定义角色后编辑。");
        section.addField("Agent 标识", id);
        section.addField("名称", name);
        section.addField("用途", description);
        section.addField("开发者指令", instructions);
        section.addField("状态", lifecycle);
        return section;
    }

    private FormSection narrowingSection() {
        FormSection section = new FormSection("偏好与能力收窄", "固定模型会覆盖临时选择。角色不能授予权限、恢复禁用能力或增加预算。");
        Button inherit = components.action("继承模型与推理", ActionStyle.GHOST, ActionSize.COMPACT);
        inherit.setOnAction(event -> inheritModel());
        section.addField("固定模型服务", provider);
        section.addField("固定模型", model);
        section.addFullWidth(modelReference);
        section.addField("推理偏好", reasoning);
        section.addFullWidth(inherit);
        section.addField("能力上限", capabilities);
        section.addField("Skill 上限", skills);
        section.addField("只读约束", constraint);
        return section;
    }

    private void bindEvents() {
        masterDetail.list().getSelectionModel().selectedItemProperty().addListener((ignored, previous, value) -> {
            if (!rendering && value != null) {
                presenter.select(value);
            }
        });
        provider.valueProperty().addListener((ignored, previous, selected) -> providerChanged(selected));
        model.valueProperty().addListener((ignored, previous, selected) -> modelChanged());
        for (TextField field : List.of(id, name, description)) {
            field.textProperty().addListener((ignored, previous, value) -> draftChanged());
        }
        for (TextArea area : List.of(instructions, capabilities, skills)) {
            area.textProperty().addListener((ignored, previous, value) -> draftChanged());
        }
        for (ComboBox<?> box : List.of(reasoning, constraint, lifecycle)) {
            box.valueProperty().addListener((ignored, previous, value) -> draftChanged());
        }
        save.setOnAction(event -> presenter.save());
        discard.setOnAction(event -> presenter.discardDraft());
        clone.setOnAction(event -> cloneRole());
    }

    private void draftChanged() {
        draftChanged(presenter.state().draft().provider());
    }

    private void draftChanged(Optional<ProviderRef> reference) {
        if (rendering || pending() || lifecycle.getValue() == null || constraint.getValue() == null) {
            return;
        }
        presenter.updateDraft(new AgentRoleDraft(
                id.getText(),
                name.getText(),
                description.getText(),
                instructions.getText(),
                reference,
                Optional.ofNullable(reasoning.getValue()),
                capabilities.getText(),
                skills.getText(),
                constraint.getValue(),
                lifecycle.getValue(),
                presenter.state().draft().extensions()));
    }

    private void providerChanged(ProviderEndpoint selected) {
        if (rendering || pending()) {
            return;
        }
        rendering = true;
        try {
            model.getItems().setAll(selected == null ? List.of() : chatModels(selected));
            model.setValue(model.getItems().stream().findFirst().orElse(null));
        } finally {
            rendering = false;
        }
        modelChanged();
    }

    private void modelChanged() {
        if (rendering || pending()) {
            return;
        }
        ProviderEndpoint selected = provider.getValue();
        Optional<ProviderRef> reference = selected == null || model.getValue() == null
                ? Optional.empty()
                : Optional.of(new ProviderRef(selected.id(), selected.revision(), model.getValue()));
        draftChanged(reference);
    }

    private void inheritModel() {
        if (pending()) {
            return;
        }
        rendering = true;
        try {
            provider.setValue(null);
            model.setValue(null);
            reasoning.setValue(null);
        } finally {
            rendering = false;
        }
        draftChanged(Optional.empty());
    }

    private void render(AgentRoleSettingsState state) {
        rendering = true;
        try {
            masterDetail.list().getItems().setAll(state.roles());
            masterDetail.list().getSelectionModel().select(state.selected().orElse(null));
            provider.getItems()
                    .setAll(state.providers().stream()
                            .filter(endpoint -> endpoint.lifecycle() == ProviderLifecycle.ACTIVE)
                            .toList());
            renderDraft(state.draft(), state.selected().isPresent());
            readOnly.setText(state.readOnly() ? "此版本只读。使用“复制为自定义 Agent”创建可编辑角色。" : "");
            promptPreview.selectRole(state.selected().filter(role -> !state.dirty()));
            promptOptimization.selectRole(state.selected().filter(role -> !role.builtin() && !state.dirty()));
            files.bind(state.selected(), state.providers());
            updateInteractionState();
        } finally {
            rendering = false;
        }
    }

    private void renderDraft(AgentRoleDraft draft, boolean existing) {
        id.setText(draft.id());
        id.setDisable(existing);
        name.setText(draft.name());
        description.setText(draft.description());
        instructions.setText(draft.developerInstructions());
        ProviderEndpoint endpoint = draft.provider()
                .flatMap(reference -> provider.getItems().stream()
                        .filter(candidate -> candidate.id().equals(reference.endpointId())
                                && candidate.revision() == reference.endpointRevision())
                        .findFirst())
                .orElse(null);
        provider.setValue(endpoint);
        renderModelReference(draft, endpoint);
        model.getItems().setAll(endpoint == null ? List.of() : chatModels(endpoint));
        model.setValue(draft.provider().map(ProviderRef::model).orElse(null));
        reasoning.setValue(draft.reasoning().orElse(null));
        capabilities.setText(draft.capabilities());
        skills.setText(draft.skills());
        constraint.setValue(draft.constraint());
        lifecycle.setValue(draft.lifecycle() == RoleLifecycle.ARCHIVED ? RoleLifecycle.DISABLED : draft.lifecycle());
    }

    private void renderModelReference(AgentRoleDraft draft, ProviderEndpoint endpoint) {
        String reference = draft.provider()
                .map(value -> value.endpointId() + "@" + value.endpointRevision() + " / " + value.model())
                .orElse("");
        boolean fixed = draft.provider().isPresent();
        provider.setPromptText(fixed && endpoint == null ? "已保留 " + reference : "继承执行配置");
        provider.setTooltip(new Tooltip(fixed ? "精确模型引用：" + reference : "未固定模型时继承执行配置"));
        modelReference.setText(
                fixed ? "已固定 " + reference + (endpoint == null ? "；最新目录未展示此版本，其他字段的编辑会保留原引用。" : "") : "");
        modelReference.setVisible(fixed);
        modelReference.setManaged(fixed);
    }

    private void updateInteractionState() {
        AgentRoleSettingsState state = presenter.state();
        presenter.externalPending(files.pending() || promptOptimization.pending());
        editor.setDisable(state.readOnly() || pending());
        masterDetail.list().setDisable(pending());
        files.blockImport(state.dirty() || state.pending() || promptOptimization.pending());
        promptOptimization.content().setDisable(state.dirty() || state.pending() || files.pending());
        promptPreview.content().setDisable(state.pending() || files.pending());
        renderStatus(state);
    }

    private void renderStatus(AgentRoleSettingsState state) {
        create.setDisable(pending());
        reload.setDisable(pending());
        save.setDisable(pending() || state.readOnly() || !state.dirty());
        discard.setDisable(pending() || !state.dirty());
        clone.setDisable(pending() || state.dirty() || state.selected().isEmpty());
        dangerZone.setActionDisabled(
                pending() || state.readOnly() || state.selected().isEmpty() || state.dirty());
        conflict.setDisable(pending());
        if (state.revisionConflict()) {
            conflict.showUnknownActual(state.selected().map(AgentRole::revision).orElse(0L));
        } else {
            conflict.hide();
        }
        actions.show(actionState(state), state.message());
    }

    private ActionState actionState(AgentRoleSettingsState state) {
        if (pending()) {
            return ActionState.PENDING;
        }
        if (state.phase() == SettingsLoadState.ERROR) {
            return ActionState.ERROR;
        }
        return state.dirty() ? ActionState.DIRTY : ActionState.IDLE;
    }

    private void cloneRole() {
        if (pending()) {
            return;
        }
        AgentRole role = presenter.state().selected().orElseThrow();
        PlatformDialogs.requiredText(
                        content, "复制 Agent", "创建自定义 Agent", "新角色将保留所选版本的指令和能力收窄。", "新角色标识", role.id() + "-custom", "复制")
                .showAndWait()
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .filter(ignored -> !pending() && presenter.state().selected().equals(Optional.of(role)))
                .ifPresent(value -> presenter.cloneSelected(value, role.spec().name() + "（自定义）"));
    }

    private void requestReload() {
        if (!pending()) {
            presenter.reload();
        }
    }

    private void reloadAfterDiscard() {
        if (pending()) {
            return;
        }
        AgentRoleSettingsState before = presenter.state();
        if (before.dirty()) {
            Alert dialog = new Alert(
                    Alert.AlertType.CONFIRMATION,
                    "重新读取会丢弃当前 Agent 的未保存草稿。可以先比较差异并保留需要的内容。",
                    ButtonType.CANCEL,
                    ButtonType.OK);
            dialog.setTitle("重新读取 Agent");
            dialog.setHeaderText("确认丢弃草稿并重新读取");
            PlatformDialogs.style(dialog, content);
            if (dialog.showAndWait().filter(ButtonType.OK::equals).isEmpty()
                    || pending()
                    || !presenter.state().equals(before)) {
                return;
            }
            presenter.discardDraft();
        }
        presenter.reload();
    }

    private void compare() {
        if (pending()) {
            return;
        }
        presenter.compare(comparison -> {
            TextArea text = new TextArea(comparison.text());
            text.setEditable(false);
            text.setWrapText(true);
            text.setPrefRowCount(18);
            Alert dialog = new Alert(Alert.AlertType.INFORMATION, "", ButtonType.CLOSE);
            dialog.setTitle("比较 Agent 差异");
            dialog.setHeaderText("只读比较；草稿和保存版本保持不变");
            dialog.getDialogPane().setContent(text);
            PlatformDialogs.style(dialog, content);
            dialog.showAndWait();
        });
    }

    private static List<String> chatModels(ProviderEndpoint endpoint) {
        return endpoint.spec().models().stream()
                .filter(model -> model.supports(ProviderModelPurpose.CHAT))
                .map(com.javaclaw.api.ProviderModelSpec::modelId)
                .toList();
    }
}
