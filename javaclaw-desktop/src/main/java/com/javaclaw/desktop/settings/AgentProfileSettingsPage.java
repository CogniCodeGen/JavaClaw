package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;

import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProfileLifecycle;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.desktop.component.AsyncActionBar;
import com.javaclaw.desktop.component.AsyncActionBar.ActionState;
import com.javaclaw.desktop.component.DangerZone;
import com.javaclaw.desktop.component.FormSection;
import com.javaclaw.desktop.component.ListDetailPane;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.desktop.component.RevisionConflictPane;

/** Agent Profile 目录、模型/权限引用、工具可见范围和 TurnBudget 编辑页面。 */
public final class AgentProfileSettingsPage implements ManagedSettingsPage {
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final AgentProfileSettingsPresenter presenter;
    private final VBox content = components.page("Agent Profile");
    private final ListDetailPane<AgentProfile> masterDetail = new ListDetailPane<>();
    private final VBox form = new VBox(12);
    private final TextField id = new TextField();
    private final TextField displayName = new TextField();
    private final TextArea systemInstruction = new TextArea();
    private final ComboBox<ProviderEndpoint> provider = new ComboBox<>();
    private final ComboBox<String> model = new ComboBox<>();
    private final ComboBox<PermissionProfile> permission = new ComboBox<>();
    private final TextArea visibleTools = new TextArea();
    private final TextField inputTokens = new TextField();
    private final TextField outputTokens = new TextField();
    private final TextField toolCalls = new TextField();
    private final TextField childThreads = new TextField();
    private final TextField wallTime = new TextField();
    private final ComboBox<ProfileLifecycle> lifecycle = new ComboBox<>();
    private final Button save;
    private final Button discard;
    private final AsyncActionBar actions;
    private final RevisionConflictPane conflict;
    private final DangerZone dangerZone;
    private final PromptPreviewPanel promptPreview;
    private final PromptOptimizationPanel promptOptimization;
    private boolean rendering;

    /**
     * 创建 Agent Profile 设置页。
     *
     * @param gateway SDK 异步边界
     * @param promptGateway Prompt provenance 异步边界
     * @param optimizationGateway Prompt 优化异步边界
     */
    public AgentProfileSettingsPage(
            CoreSettingsGateway gateway,
            PromptPreviewSettingsGateway promptGateway,
            PromptOptimizationSettingsGateway optimizationGateway) {
        presenter = new AgentProfileSettingsPresenter(gateway);
        save = components.action("保存 Profile", ActionStyle.PRIMARY, ActionSize.NORMAL);
        discard = components.action("放弃更改", ActionStyle.GHOST, ActionSize.NORMAL);
        actions = new AsyncActionBar(discard, save);
        conflict = new RevisionConflictPane(presenter::reload, this::showConflictComparison);
        dangerZone = new DangerZone(
                "归档 Agent Profile", "归档后不能用于新 Turn；活动 Turn 继续使用已冻结的快照。", "归档当前 Profile", presenter::archive);
        promptPreview = new PromptPreviewPanel(promptGateway);
        promptOptimization = new PromptOptimizationPanel(optimizationGateway, presenter::reload);
        configureControls();
        buildLayout();
        bindEvents();
        presenter.subscribe(this::render);
    }

    @Override
    public Node content() {
        return content;
    }

    @Override
    public void activate() {
        presenter.reload();
        promptPreview.activate();
        promptOptimization.activate();
    }

    @Override
    public boolean dirty() {
        return presenter.state().dirty();
    }

    @Override
    public void warnUnsavedChanges() {
        presenter.warnUnsavedChanges();
    }

    @Override
    public void discardDraft() {
        presenter.discardDraft();
    }

    private void configureControls() {
        id.setPromptText("例如 coding-default");
        displayName.setPromptText("用户可见名称");
        systemInstruction.setPromptText("只描述角色与工作偏好，不授予工具权限");
        systemInstruction.setPrefRowCount(6);
        provider.setCellFactory(ignored -> components.detailCell(
                endpoint -> endpoint.spec().displayName(), endpoint -> endpoint.id() + " · v" + endpoint.revision()));
        provider.setButtonCell(components.textCell(
                endpoint -> endpoint == null ? "" : endpoint.spec().displayName() + " · v" + endpoint.revision()));
        model.setPromptText("选择 Provider 原生模型");
        permission.setCellFactory(
                ignored -> components.detailCell(PermissionProfile::id, profile -> "v" + profile.version()));
        permission.setButtonCell(
                components.textCell(profile -> profile == null ? "" : profile.id() + " · v" + profile.version()));
        visibleTools.setPromptText("每行一个完整工具名；空集合表示不向模型公开工具");
        visibleTools.setPrefRowCount(4);
        lifecycle.setItems(FXCollections.observableArrayList(ProfileLifecycle.ACTIVE, ProfileLifecycle.DISABLED));
    }

    private void buildLayout() {
        Label hint = new Label(
                "Profile 只保存 Prompt、Provider/model、PermissionProfile、工具可见范围和 TurnBudget。每个 Turn 会冻结精确 revision。");
        hint.setWrapText(true);
        hint.getStyleClass().add("sec-hint");
        Button create = components.action("新建", ActionStyle.PRIMARY, ActionSize.COMPACT);
        create.setId("profileCreateButton");
        create.setOnAction(event -> presenter.createDraft());
        Button reload = components.action("刷新", ActionStyle.GHOST, ActionSize.COMPACT);
        reload.setOnAction(event -> presenter.reload());
        masterDetail
                .list()
                .setCellFactory(ignored -> components.detailCell(
                        profile -> profile.spec().displayName(),
                        profile -> profile.id() + " · v" + profile.revision() + " · " + profile.lifecycle()));
        form.getChildren()
                .addAll(
                        identitySection(),
                        routingSection(),
                        budgetSection(),
                        promptPreview.content(),
                        promptOptimization.content(),
                        conflict,
                        actions,
                        dangerZone);
        masterDetail.showDetail(form);
        VBox.setVgrow(masterDetail, Priority.ALWAYS);
        content.getChildren().addAll(hint, new HBox(8, create, reload), masterDetail);
    }

    private FormSection identitySection() {
        FormSection section = new FormSection("身份与 Prompt", "ID 创建后不可修改；system prompt 不能改变 PermissionProfile 或工具目录上限。");
        section.addField("Profile ID", id);
        section.addField("显示名称", displayName);
        section.addField("System prompt", systemInstruction);
        section.addField("状态", lifecycle);
        return section;
    }

    private FormSection routingSection() {
        FormSection section =
                new FormSection("模型、权限与工具", "Provider/model 和 PermissionProfile 都保存精确 revision；过期引用必须重新选择。");
        section.addField("Provider", provider);
        section.addField("模型", model);
        section.addField("PermissionProfile", permission);
        section.addField("可见工具上限", visibleTools);
        return section;
    }

    private FormSection budgetSection() {
        FormSection section = new FormSection("TurnBudget", "这些是单 Turn 默认上限；执行级预算可以进一步收窄，不能扩大。");
        section.addField("输入 token", inputTokens);
        section.addField("输出 token", outputTokens);
        section.addField("工具调用次数", toolCalls);
        section.addField("直接子 Thread", childThreads);
        section.addField("墙钟时间（秒）", wallTime);
        return section;
    }

    private void bindEvents() {
        masterDetail.list().getSelectionModel().selectedItemProperty().addListener((ignored, previous, value) -> {
            if (!rendering && value != null) {
                presenter.select(value);
            }
        });
        provider.valueProperty().addListener((ignored, previous, selected) -> {
            if (!rendering) {
                model.getItems()
                        .setAll(
                                selected == null
                                        ? java.util.List.of()
                                        : selected.spec().models());
                model.setValue(model.getItems().stream().findFirst().orElse(null));
                draftChanged();
            }
        });
        id.textProperty().addListener((ignored, previous, value) -> draftChanged());
        displayName.textProperty().addListener((ignored, previous, value) -> draftChanged());
        systemInstruction.textProperty().addListener((ignored, previous, value) -> draftChanged());
        model.valueProperty().addListener((ignored, previous, value) -> draftChanged());
        permission.valueProperty().addListener((ignored, previous, value) -> draftChanged());
        visibleTools.textProperty().addListener((ignored, previous, value) -> draftChanged());
        inputTokens.textProperty().addListener((ignored, previous, value) -> draftChanged());
        outputTokens.textProperty().addListener((ignored, previous, value) -> draftChanged());
        toolCalls.textProperty().addListener((ignored, previous, value) -> draftChanged());
        childThreads.textProperty().addListener((ignored, previous, value) -> draftChanged());
        wallTime.textProperty().addListener((ignored, previous, value) -> draftChanged());
        lifecycle.valueProperty().addListener((ignored, previous, value) -> draftChanged());
        save.setOnAction(event -> presenter.save());
        discard.setOnAction(event -> presenter.discardDraft());
    }

    private void draftChanged() {
        if (rendering || lifecycle.getValue() == null) {
            return;
        }
        ProviderEndpoint selectedProvider = provider.getValue();
        Optional<ProviderRef> providerRef = selectedProvider == null || model.getValue() == null
                ? Optional.empty()
                : Optional.of(new ProviderRef(selectedProvider.id(), selectedProvider.revision(), model.getValue()));
        PermissionProfile selectedPermission = permission.getValue();
        Optional<PermissionProfileRef> permissionRef = selectedPermission == null
                ? Optional.empty()
                : Optional.of(new PermissionProfileRef(selectedPermission.id(), selectedPermission.version()));
        presenter.updateDraft(new AgentProfileDraft(
                id.getText(),
                displayName.getText(),
                systemInstruction.getText(),
                providerRef,
                permissionRef,
                visibleTools.getText(),
                number(inputTokens.getText()),
                number(outputTokens.getText()),
                integer(toolCalls.getText()),
                integer(childThreads.getText()),
                number(wallTime.getText()),
                lifecycle.getValue()));
    }

    private void render(AgentProfileSettingsState state) {
        rendering = true;
        try {
            masterDetail.list().getItems().setAll(state.profiles());
            masterDetail.list().getSelectionModel().select(state.selected().orElse(null));
            provider.getItems()
                    .setAll(state.providers().stream()
                            .filter(endpoint -> endpoint.lifecycle() == ProviderLifecycle.ACTIVE)
                            .toList());
            permission.getItems().setAll(state.permissions());
            renderDraft(state.draft(), state.selected().isPresent());
            promptPreview.selectProfile(state.selected());
            promptOptimization.selectProfile(state.selected().filter(profile -> !state.dirty()));
            renderStatus(state);
        } finally {
            rendering = false;
        }
    }

    private void renderDraft(AgentProfileDraft draft, boolean existing) {
        id.setText(draft.id());
        id.setDisable(existing);
        displayName.setText(draft.displayName());
        systemInstruction.setText(draft.systemInstruction());
        ProviderEndpoint endpoint = draft.provider()
                .flatMap(reference -> provider.getItems().stream()
                        .filter(candidate -> candidate.id().equals(reference.endpointId())
                                && candidate.revision() == reference.endpointRevision())
                        .findFirst())
                .orElse(null);
        provider.setValue(endpoint);
        model.getItems()
                .setAll(endpoint == null ? java.util.List.of() : endpoint.spec().models());
        model.setValue(draft.provider().map(ProviderRef::model).orElse(null));
        permission.setValue(draft.permissionProfile()
                .flatMap(reference -> permission.getItems().stream()
                        .filter(candidate ->
                                candidate.id().equals(reference.id()) && candidate.version() == reference.version())
                        .findFirst())
                .orElse(null));
        visibleTools.setText(draft.visibleTools());
        inputTokens.setText(Long.toString(draft.inputTokens()));
        outputTokens.setText(Long.toString(draft.outputTokens()));
        toolCalls.setText(Integer.toString(draft.toolCalls()));
        childThreads.setText(Integer.toString(draft.childThreads()));
        wallTime.setText(Long.toString(draft.wallTimeSeconds()));
        lifecycle.setValue(
                draft.lifecycle() == ProfileLifecycle.ARCHIVED ? ProfileLifecycle.DISABLED : draft.lifecycle());
    }

    private void renderStatus(AgentProfileSettingsState state) {
        boolean archived = state.selected()
                .map(profile -> profile.lifecycle() == ProfileLifecycle.ARCHIVED)
                .orElse(false);
        boolean pending = state.pending();
        save.setDisable(pending || archived || !state.dirty());
        discard.setDisable(pending || !state.dirty());
        dangerZone.setActionDisabled(pending || state.selected().isEmpty() || archived || state.dirty());
        renderConflict(state);
        renderActionState(state);
    }

    private void renderConflict(AgentProfileSettingsState state) {
        if (state.revisionConflict()) {
            conflict.showUnknownActual(
                    state.selected().map(AgentProfile::revision).orElse(0L));
        } else {
            conflict.hide();
        }
    }

    private void renderActionState(AgentProfileSettingsState state) {
        boolean pending = state.pending();
        if (pending) {
            actions.show(ActionState.PENDING, state.message());
        } else if (state.phase() == SettingsLoadState.ERROR) {
            actions.show(ActionState.ERROR, state.message());
        } else if (state.dirty()) {
            actions.show(ActionState.DIRTY, state.message().isBlank() ? "Agent Profile 草稿尚未保存" : state.message());
        } else if (!state.message().isBlank()) {
            actions.show(ActionState.SUCCESS, state.message());
        } else {
            actions.show(ActionState.IDLE, "");
        }
    }

    private void showConflictComparison() {
        actions.show(ActionState.DIRTY, "当前 RPC 未返回服务端实际 revision；重新读取后可逐字段比较，但会丢弃本地草稿。");
    }

    private static long number(String value) {
        try {
            return Long.parseLong(Objects.requireNonNullElse(value, "").strip());
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    private static int integer(String value) {
        long parsed = number(value);
        return parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE ? -1 : (int) parsed;
    }
}
