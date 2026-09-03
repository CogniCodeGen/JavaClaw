package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;
import javafx.util.StringConverter;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.PermissionPresetPreview;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.Workspace;
import com.javaclaw.desktop.component.AsyncActionBar;
import com.javaclaw.desktop.component.AsyncActionBar.ActionState;
import com.javaclaw.desktop.component.FormSection;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.desktop.component.PlatformDialogs;

/** 使用现有 FormSection 与不可变 Presenter 完成首次智能体初始化的非阻塞向导。 */
public final class AgentPresetOnboardingDialog extends Dialog<Void> {
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final AgentPresetOnboardingPresenter presenter;
    private final AgentPresetOnboardingIds ids;
    private final Runnable completed;
    private final ComboBox<ProviderRef> defaultProvider = new ComboBox<>();
    private final ComboBox<ProviderRef> workerProvider = new ComboBox<>();
    private final ComboBox<ProviderRef> explorerProvider = new ComboBox<>();
    private final Label reviewSummary = summaryLabel();
    private final Label developerSummary = summaryLabel();
    private final CheckBox permissionConfirmation = new CheckBox("我已核对上述文件、网络、进程和工具边界");
    private final ListView<ToolDescriptor> reviewTools = toolList();
    private final ListView<ToolDescriptor> developerTools = toolList();
    private final Button confirmPermissions;
    private final Button retry;
    private final Button apply;
    private final AsyncActionBar actions;
    private AgentPresetOnboardingState state;
    private boolean rendering;
    private boolean completionNotified;

    /**
     * 创建固定 Workspace 的初始化 Dialog。
     *
     * @param owner 设置与管理中心窗口
     * @param workspace 打开时冻结的 Workspace；主窗口后续选择不会改变它
     * @param gateway Java SDK 异步边界
     * @param completed 初始化完成后的页面刷新动作
     */
    public AgentPresetOnboardingDialog(
            Window owner, Workspace workspace, AgentPresetOnboardingGateway gateway, Runnable completed) {
        Workspace frozen = Objects.requireNonNull(workspace, "workspace");
        presenter = new AgentPresetOnboardingPresenter(frozen, gateway);
        ids = AgentPresetOnboardingIds.forWorkspace(frozen.id());
        this.completed = Objects.requireNonNull(completed, "completed");
        confirmPermissions = action("确认并创建权限方案", ActionStyle.SOFT);
        retry = action("重新检查", ActionStyle.GHOST);
        apply = action("完成初始化", ActionStyle.PRIMARY);
        actions = new AsyncActionBar(retry, apply);
        configureDialog(owner);
        configureModels();
        configureEvents();
        getDialogPane().setContent(content(frozen));
        presenter.subscribe(this::render);
    }

    /** 打开非阻塞 Dialog，并开始读取固定 Workspace 状态。 */
    public void open() {
        if (!isShowing()) {
            show();
        }
        getDialogPane().requestFocus();
        if (!presenter.state().pending()) {
            presenter.reload();
        }
    }

    /** 将已经打开的向导带到前台，不触发新的后台读取。 */
    public void focus() {
        if (!isShowing()) {
            show();
        }
        Window window = getDialogPane().getScene() == null
                ? null
                : getDialogPane().getScene().getWindow();
        if (window != null) {
            window.requestFocus();
        }
    }

    /** @return 是否有尚未完成的模型或工具选择 */
    public boolean dirty() {
        if (!isShowing() || state == null || state.completed()) {
            return false;
        }
        AgentPresetOnboardingSelection selection = state.selection();
        return selection.defaultProvider().isPresent()
                || selection.workerProvider().isPresent()
                || selection.explorerProvider().isPresent()
                || !selection.reviewTools().isEmpty()
                || !selection.developerTools().isEmpty();
    }

    /** @return 是否正在执行不可被 Scope 切换中断的后台步骤 */
    public boolean pending() {
        return state != null && state.pending();
    }

    /** @return 向导固定的 Workspace */
    public Workspace workspace() {
        return presenter.state().workspace();
    }

    private void configureDialog(Window owner) {
        initModality(Modality.NONE);
        setTitle("初始化内置智能体");
        setHeaderText("创建 default、worker、explorer 三个普通智能体方案");
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().getStyleClass().add("jc-dialog-wide");
        getDialogPane().setPrefSize(840, 760);
        setResizable(true);
        PlatformDialogs.style(this, owner);
    }

    private void configureModels() {
        configureModel(defaultProvider, "default");
        configureModel(workerProvider, "worker");
        configureModel(explorerProvider, "explorer");
        reviewTools.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        developerTools.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        permissionConfirmation.setWrapText(true);
        permissionConfirmation.setTooltip(new Tooltip("提示词不能扩大权限；Explorer 的只读边界由权限方案强制执行。"));
        permissionConfirmation.selectedProperty().addListener((ignored, previous, selected) -> {
            if (state != null && !rendering && !state.permissions().confirmed()) {
                confirmPermissions.setDisable(!selected || state.pending());
            }
        });
    }

    private void configureModel(ComboBox<ProviderRef> combo, String presetId) {
        combo.setMaxWidth(Double.MAX_VALUE);
        combo.setPromptText("为 " + presetId + " 选择精确 Chat 模型");
        combo.setConverter(new StringConverter<>() {
            @Override
            public String toString(ProviderRef value) {
                return value == null ? "" : providerLabel(value);
            }

            @Override
            public ProviderRef fromString(String value) {
                throw new UnsupportedOperationException("模型引用只能从目录选择");
            }
        });
        combo.setCellFactory(ignored -> components.detailCell(this::providerLabel, this::providerDetail));
    }

    private Node content(Workspace workspace) {
        VBox sections = new VBox(12, workspaceSection(workspace), modelSection(), permissionSection(), toolSection());
        sections.getStyleClass().add("platform-page");
        ScrollPane scroll = new ScrollPane(sections);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().addAll("settings-scroll", "edge-to-edge");
        BorderPane root = new BorderPane(scroll);
        root.setBottom(actions);
        return root;
    }

    private FormSection workspaceSection(Workspace workspace) {
        FormSection section = new FormSection("固定工作区", "本向导的所有预览、工具目录和写入都固定到此 Workspace。");
        Label name = new Label(workspace.name());
        Label identity = new Label(workspace.id().toString());
        identity.getStyleClass().add("sec-hint");
        section.addField("工作区", new VBox(3, name, identity));
        return section;
    }

    private FormSection modelSection() {
        FormSection section = new FormSection("智能体与模型", "三个预设都会创建为普通可编辑 Profile；每项都固定精确 Provider revision 和模型 ID。");
        section.addRequiredField("默认智能体", defaultProvider, "端到端推进任务，可使用受治理的子任务能力。");
        section.addRequiredField("Worker", workerProvider, "专注执行交办目标，不再创建子任务。");
        section.addRequiredField("Explorer", explorerProvider, "只读探索；实际只读由权限方案强制。");
        return section;
    }

    private FormSection permissionSection() {
        FormSection section = new FormSection("权限预览", "预设先由服务端针对固定 Workspace 生成；确认后才会保存为普通权限方案。");
        section.addField("只读审阅", reviewSummary);
        section.addField("Workspace 开发", developerSummary);
        section.addFullWidth(permissionConfirmation);
        section.addFullWidth(confirmPermissions);
        return section;
    }

    private FormSection toolSection() {
        FormSection section = new FormSection("精确工具", "空查询只列出权限能力上限内的当前候选；只有选中的完整工具名会写入，不接受 *。");
        section.addField("Explorer 工具", reviewTools);
        section.addField("default / worker 工具", developerTools);
        return section;
    }

    private void configureEvents() {
        defaultProvider
                .valueProperty()
                .addListener(
                        (ignored, previous, selected) -> selectProvider(AgentPresetOnboardingPolicy.DEFAULT, selected));
        workerProvider
                .valueProperty()
                .addListener(
                        (ignored, previous, selected) -> selectProvider(AgentPresetOnboardingPolicy.WORKER, selected));
        explorerProvider
                .valueProperty()
                .addListener((ignored, previous, selected) ->
                        selectProvider(AgentPresetOnboardingPolicy.EXPLORER, selected));
        reviewTools.getSelectionModel().getSelectedItems().addListener((javafx.collections.ListChangeListener<
                        ToolDescriptor>)
                change -> selectTools(AgentPresetOnboardingPolicy.REVIEW));
        developerTools.getSelectionModel().getSelectedItems().addListener((javafx.collections.ListChangeListener<
                        ToolDescriptor>)
                change -> selectTools(AgentPresetOnboardingPolicy.DEVELOPER));
        confirmPermissions.setOnAction(event -> presenter.confirmPermissions(permissionConfirmation.isSelected()));
        retry.setOnAction(event -> presenter.reload());
        apply.setOnAction(event -> presenter.apply());
    }

    private void selectProvider(String presetId, ProviderRef provider) {
        if (!rendering && provider != null) {
            presenter.selectProvider(presetId, provider);
        }
    }

    private void selectTools(String presetId) {
        if (rendering) {
            return;
        }
        ListView<ToolDescriptor> list =
                AgentPresetOnboardingPolicy.REVIEW.equals(presetId) ? reviewTools : developerTools;
        Set<String> selected = list.getSelectionModel().getSelectedItems().stream()
                .map(tool -> tool.identity().name())
                .collect(java.util.stream.Collectors.toSet());
        presenter.selectTools(presetId, selected);
    }

    private void render(AgentPresetOnboardingState value) {
        state = Objects.requireNonNull(value, "value");
        rendering = true;
        try {
            renderModels(value);
            renderPermissions(value);
            renderTools(value);
            renderActions(value);
        } finally {
            rendering = false;
        }
        if (value.completed() && !completionNotified) {
            completionNotified = true;
            completed.run();
        }
    }

    private void renderModels(AgentPresetOnboardingState value) {
        List<ProviderRef> providers = presenter.chatProviders();
        defaultProvider.setItems(FXCollections.observableArrayList(providers));
        workerProvider.setItems(FXCollections.observableArrayList(providers));
        explorerProvider.setItems(FXCollections.observableArrayList(providers));
        defaultProvider.setValue(value.selection().defaultProvider().orElse(null));
        workerProvider.setValue(value.selection().workerProvider().orElse(null));
        explorerProvider.setValue(value.selection().explorerProvider().orElse(null));
        defaultProvider.setDisable(value.pending() || hasProfile(value, AgentPresetOnboardingPolicy.DEFAULT));
        workerProvider.setDisable(value.pending() || hasProfile(value, AgentPresetOnboardingPolicy.WORKER));
        explorerProvider.setDisable(value.pending() || hasProfile(value, AgentPresetOnboardingPolicy.EXPLORER));
    }

    private void renderPermissions(AgentPresetOnboardingState value) {
        reviewSummary.setText(
                value.permissions().reviewPreview().map(this::permissionSummary).orElse("正在等待服务端预览…"));
        developerSummary.setText(value.permissions()
                .developerPreview()
                .map(this::permissionSummary)
                .orElse("正在等待服务端预览…"));
        boolean confirmed = value.permissions().confirmed();
        permissionConfirmation.setSelected(confirmed);
        permissionConfirmation.setDisable(
                value.pending() || confirmed || !value.permissions().previewsReady());
        confirmPermissions.setDisable(value.pending() || confirmed || !permissionConfirmation.isSelected());
    }

    private void renderTools(AgentPresetOnboardingState value) {
        renderTools(
                reviewTools,
                value.permissions().reviewCandidates(),
                value.selection().reviewTools());
        renderTools(
                developerTools,
                value.permissions().developerCandidates(),
                value.selection().developerTools());
        AgentPresetOnboardingPolicy policy = new AgentPresetOnboardingPolicy(value.workspace());
        reviewTools.setDisable(value.pending()
                || !value.permissions().toolsLoaded()
                || policy.toolsFrozenByExistingProfile(value.catalog(), AgentPresetOnboardingPolicy.REVIEW));
        developerTools.setDisable(value.pending()
                || !value.permissions().toolsLoaded()
                || policy.toolsFrozenByExistingProfile(value.catalog(), AgentPresetOnboardingPolicy.DEVELOPER));
    }

    private void renderTools(ListView<ToolDescriptor> list, List<ToolDescriptor> candidates, Set<String> selected) {
        list.getItems().setAll(candidates);
        list.getSelectionModel().clearSelection();
        for (int index = 0; index < candidates.size(); index++) {
            if (selected.contains(candidates.get(index).identity().name())) {
                list.getSelectionModel().select(index);
            }
        }
    }

    private void renderActions(AgentPresetOnboardingState value) {
        retry.setDisable(value.pending());
        apply.setDisable(value.pending()
                || value.completed()
                || value.conflict()
                || value.phase() != AgentPresetOnboardingPhase.SELECT_CONFIGURATION
                || !value.selection().modelsComplete());
        ActionState actionState = value.pending()
                ? ActionState.PENDING
                : value.conflict() || value.phase() == AgentPresetOnboardingPhase.ERROR
                        ? ActionState.ERROR
                        : value.completed() ? ActionState.SUCCESS : ActionState.IDLE;
        actions.show(actionState, value.message());
    }

    private String permissionSummary(PermissionPresetPreview preview) {
        var profile = preview.proposedProfile();
        String roots = profile.files().writeRoots().isEmpty() ? "只读" : "Workspace 内读写";
        String process = profile.processes().executables().isEmpty() ? "无进程" : "精确进程白名单";
        String warnings = preview.warnings().isEmpty() ? "" : " · " + String.join("；", preview.warnings());
        return roots + " · 无网络 · " + process + " · 无 PTY · 最高工具风险 "
                + profile.tools().maximumRisk() + warnings;
    }

    private boolean hasProfile(AgentPresetOnboardingState value, String presetId) {
        String id = ids.profileId(presetId);
        return value.catalog().profiles().stream().map(AgentProfile::id).anyMatch(id::equals);
    }

    private String providerLabel(ProviderRef reference) {
        Optional<ProviderEndpoint> endpoint = provider(reference);
        String endpointName = endpoint.map(value -> value.spec().displayName()).orElse(reference.endpointId());
        String modelName = endpoint.flatMap(value -> value.spec().models().stream()
                        .filter(model -> model.modelId().equals(reference.model()))
                        .findFirst())
                .map(model -> model.displayName())
                .orElse(reference.model());
        return endpointName + " · " + modelName;
    }

    private String providerDetail(ProviderRef reference) {
        return reference.endpointId() + " · Provider 版本 " + reference.endpointRevision() + " · " + reference.model();
    }

    private Optional<ProviderEndpoint> provider(ProviderRef reference) {
        if (state == null) {
            return Optional.empty();
        }
        return state.catalog().providers().stream()
                .filter(endpoint -> endpoint.id().equals(reference.endpointId())
                        && endpoint.revision() == reference.endpointRevision())
                .findFirst();
    }

    private Button action(String text, ActionStyle style) {
        return components.action(text, style, ActionSize.NORMAL);
    }

    private static Label summaryLabel() {
        Label label = new Label();
        label.setWrapText(true);
        label.getStyleClass().add("sec-hint");
        return label;
    }

    private static ListView<ToolDescriptor> toolList() {
        ListView<ToolDescriptor> list = new ListView<>();
        list.setPrefHeight(132);
        list.setCellFactory(ignored -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(ToolDescriptor item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.identity().name() + " · " + item.description());
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);
        return list;
    }
}
