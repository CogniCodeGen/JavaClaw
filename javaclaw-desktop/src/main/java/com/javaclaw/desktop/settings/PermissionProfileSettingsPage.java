package com.javaclaw.desktop.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.Workspace;
import com.javaclaw.desktop.component.AsyncActionBar;
import com.javaclaw.desktop.component.AsyncActionBar.ActionState;
import com.javaclaw.desktop.component.FormSection;
import com.javaclaw.desktop.component.ListDetailPane;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.desktop.component.RevisionConflictPane;

/** 权限方案版本编辑、内置方案复制、本地差异和有效权限说明页面。 */
public final class PermissionProfileSettingsPage implements ManagedSettingsPage {
    private final SettingsPageRefresh configurationRefresh;
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final PermissionProfileSettingsPresenter presenter;
    private final PermissionPreviewPresenter previewPresenter;
    private final PermissionHistoryPresenter historyPresenter;
    private final VBox content = components.page("权限方案");
    private final ListDetailPane<PermissionProfile> masterDetail = new ListDetailPane<>();
    private final VBox form = new VBox(12);
    private final List<FormSection> editorSections = new ArrayList<>();
    private final TextField id = new TextField();
    private final TextArea readRoots = area(3);
    private final TextArea writeRoots = area(3);
    private final CheckBox allowDelete = new CheckBox("允许删除");
    private final CheckBox followLinks = new CheckBox("允许跟随符号链接");
    private final TextArea hosts = area(3);
    private final TextField ports = new TextField();
    private final CheckBox tlsOnly = new CheckBox("只允许加密连接（TLS）");
    private final TextArea executables = area(3);
    private final CheckBox allowPty = new CheckBox("允许交互终端（PTY）");
    private final TextField processSeconds = new TextField();
    private final PermissionToolCatalogEditor allowedTools;
    private final ComboBox<ToolRisk> maximumRisk = new ComboBox<>();
    private final ComboBox<ApprovalRequirement> approval = new ComboBox<>();
    private final TextField memoryMiB = new TextField();
    private final TextField outputMiB = new TextField();
    private final TextField childProcesses = new TextField();
    private final TextField openFiles = new TextField();
    private final Label diff = new Label();
    private final VBox historyRows = new VBox(6);
    private final Label historyMessage = new Label();
    private final CheckBox includeTurnGrant = new CheckBox("应用");
    private final ComboBox<PermissionProfile> previewTurnGrant = new ComboBox<>();
    private final CheckBox includeToolDeclaration = new CheckBox("应用");
    private final ComboBox<PermissionProfile> previewToolDeclaration = new ComboBox<>();
    private final VBox previewLayers = new VBox(6);
    private final Label previewMessage = new Label();
    private final Button previewButton;
    private final Button save;
    private final Button discard;
    private final AsyncActionBar actions;
    private final RevisionConflictPane conflict;
    private PermissionPreviewState previewState = PermissionPreviewState.initial();
    private Optional<Workspace> scopedWorkspace = Optional.empty();
    private boolean rendering;

    /**
     * 创建权限方案设置页。
     *
     * @param gateway SDK 异步边界
     */
    public PermissionProfileSettingsPage(CoreSettingsGateway gateway) {
        presenter = new PermissionProfileSettingsPresenter(gateway);
        previewPresenter = new PermissionPreviewPresenter(gateway);
        historyPresenter = new PermissionHistoryPresenter(gateway);
        allowedTools = new PermissionToolCatalogEditor(gateway, this::draftChanged);
        save = components.action("保存新版本", ActionStyle.PRIMARY, ActionSize.NORMAL);
        discard = components.action("放弃更改", ActionStyle.GHOST, ActionSize.NORMAL);
        previewButton = components.action("计算有效权限", ActionStyle.SOFT, ActionSize.NORMAL);
        actions = new AsyncActionBar(discard, save);
        conflict = new RevisionConflictPane(presenter::reload, this::showConflictComparison);
        allowedTools.onStateChanged(() -> renderStatus(presenter.state()));
        configureControls();
        buildLayout();
        bindEvents();
        configurationRefresh = SettingsPageRefresh.permissions(gateway, this, presenter);
        presenter.subscribe(this::render);
        previewPresenter.subscribe(this::renderPreview);
        historyPresenter.subscribe(this::renderHistory);
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
        scopedWorkspace.ifPresent(previewPresenter::selectWorkspace);
    }

    @Override
    public void deactivate() {
        configurationRefresh.deactivate();
    }

    @Override
    public void dispose() {
        configurationRefresh.close();
    }

    @Override
    public boolean dirty() {
        return presenter.state().dirty();
    }

    @Override
    public boolean pending() {
        return presenter.state().pending()
                || previewState.phase() == SettingsLoadState.LOADING
                || allowedTools.pending();
    }

    @Override
    public void workspaceChanged(Optional<Workspace> workspace) {
        Optional<Workspace> checked = Objects.requireNonNull(workspace, "workspace");
        if (scopedWorkspace.equals(checked)) {
            return;
        }
        scopedWorkspace = checked;
        checked.ifPresent(previewPresenter::selectWorkspace);
        syncToolCatalog(presenter.state());
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
        id.setPromptText("复制后输入新的稳定标识");
        readRoots.setPromptText("每行一个绝对路径");
        writeRoots.setPromptText("每行一个绝对路径");
        hosts.setPromptText("每行一个小写 DNS 主机名；* 只能作为权限上限");
        ports.setPromptText("例如 443, 8443");
        executables.setPromptText("每行一个可执行文件规范名");
        processSeconds.setPromptText("30");
        maximumRisk.setItems(FXCollections.observableArrayList(ToolRisk.values()));
        maximumRisk.setConverter(SettingsLabels.converter(SettingsLabels::toolRisk));
        approval.setItems(FXCollections.observableArrayList(ApprovalRequirement.values()));
        approval.setConverter(SettingsLabels.converter(SettingsLabels::approvalRequirement));
        diff.setWrapText(true);
        diff.getStyleClass().add("sec-hint");
        historyMessage.setWrapText(true);
        historyMessage.getStyleClass().add("sec-hint");
        configurePermissionChoice(previewTurnGrant);
        configurePermissionChoice(previewToolDeclaration);
        previewMessage.setWrapText(true);
        previewMessage.getStyleClass().add("sec-hint");
    }

    private void buildLayout() {
        Label hint = new Label("每次保存都会生成不可变的新版本。正在运行的任务不会因后续放宽权限而获得更多权限；收紧权限会立即生效。");
        hint.setWrapText(true);
        hint.getStyleClass().add("sec-hint");
        Button clone = components.action("复制为新方案", ActionStyle.PRIMARY, ActionSize.COMPACT);
        clone.setId("permissionCloneButton");
        clone.setOnAction(event -> presenter.cloneSelected());
        Button reload = components.action("刷新", ActionStyle.GHOST, ActionSize.COMPACT);
        reload.setOnAction(event -> presenter.reload());
        masterDetail
                .list()
                .setCellFactory(ignored -> components.detailCell(
                        profile -> SettingsLabels.permissionProfile(profile.id()),
                        profile -> profile.id() + " · 版本 " + profile.version()
                                + (profile.id().equals("standard") ? " · 内置只读" : "")));
        FormSection identity = identitySection();
        FormSection files = fileSection();
        FormSection network = networkSection();
        FormSection process = processSection();
        FormSection tools = toolSection();
        FormSection resources = resourceSection();
        editorSections.addAll(List.of(files, network, process, tools, resources));
        form.getChildren()
                .addAll(
                        identity,
                        files,
                        network,
                        process,
                        tools,
                        resources,
                        diffSection(),
                        historySection(),
                        effectivePreview(),
                        conflict);
        masterDetail.showDetail(form);
        VBox.setVgrow(masterDetail, Priority.ALWAYS);
        content.getChildren().addAll(hint, new HBox(8, clone, reload), masterDetail);
    }

    private FormSection identitySection() {
        FormSection section = new FormSection("版本身份", "“受限对话”是内置只读模板（技术标识 standard）；自定义方案每次保存都会生成新版本。");
        section.addField("方案标识", id);
        return section;
    }

    private FormSection fileSection() {
        FormSection section = new FormSection("文件", "最终文件访问范围还会受平台安全上限、工作区和本次任务授权限制。");
        section.addField("读取根目录", readRoots);
        section.addField("写入根目录", writeRoots);
        section.addField("危险能力", new HBox(12, allowDelete, followLinks));
        return section;
    }

    private FormSection networkSection() {
        FormSection section = new FormSection("网络", "这里设置网络访问上限；访问私网还需要单独创建精确且可撤销的私网授权。");
        section.addField("主机", hosts);
        section.addField("端口", ports);
        section.addField("传输", tlsOnly);
        return section;
    }

    private FormSection processSection() {
        FormSection section = new FormSection("进程与交互终端", "进程始终由系统沙箱启动；此处不能授予完整主机访问权限。");
        section.addField("可执行文件", executables);
        section.addField("最长运行（秒）", processSeconds);
        section.addField("交互终端", allowPty);
        return section;
    }

    private FormSection toolSection() {
        FormSection section = new FormSection("工具与审批", "候选来自当前固定工作区和精确权限版本；只有明确选中的完整工具名会写入白名单。");
        section.addField("允许工具", allowedTools);
        section.addField("最大风险", maximumRisk);
        section.addField("审批强度", approval);
        return section;
    }

    private FormSection resourceSection() {
        FormSection section = new FormSection("资源上限", "内存和输出量以 MiB 为单位；数值必须是有限正数。");
        section.addField("内存上限（MiB）", memoryMiB);
        section.addField("输出上限（MiB）", outputMiB);
        section.addField("子进程数", childProcesses);
        section.addField("打开文件数", openFiles);
        return section;
    }

    private FormSection diffSection() {
        FormSection section = new FormSection("本地差异", "只比较当前草稿与已读取版本，不声称是最终有效权限差异。");
        section.addFullWidth(diff);
        return section;
    }

    private FormSection historySection() {
        FormSection section = new FormSection("版本历史与差异", "历史版本不可修改；差异由服务端比较最近两个版本。");
        section.addFullWidth(historyRows);
        section.addFullWidth(historyMessage);
        return section;
    }

    private FormSection effectivePreview() {
        FormSection section = new FormSection("有效权限预览", "服务端会按平台安全上限、工作区、Agent、本次任务授权和工具自身限制逐层收紧。");
        section.addField("本次任务授权", new HBox(8, includeTurnGrant, previewTurnGrant));
        section.addField("工具声明", new HBox(8, includeToolDeclaration, previewToolDeclaration));
        section.addFullWidth(new HBox(8, previewButton));
        section.addFullWidth(previewLayers);
        section.addFullWidth(previewMessage);
        return section;
    }

    private void bindEvents() {
        masterDetail.list().getSelectionModel().selectedItemProperty().addListener((ignored, previous, selected) -> {
            if (!rendering && selected != null) {
                presenter.select(selected);
            }
        });
        id.textProperty().addListener((ignored, previous, value) -> draftChanged());
        readRoots.textProperty().addListener((ignored, previous, value) -> draftChanged());
        writeRoots.textProperty().addListener((ignored, previous, value) -> draftChanged());
        allowDelete.selectedProperty().addListener((ignored, previous, value) -> draftChanged());
        followLinks.selectedProperty().addListener((ignored, previous, value) -> draftChanged());
        hosts.textProperty().addListener((ignored, previous, value) -> draftChanged());
        ports.textProperty().addListener((ignored, previous, value) -> draftChanged());
        tlsOnly.selectedProperty().addListener((ignored, previous, value) -> draftChanged());
        executables.textProperty().addListener((ignored, previous, value) -> draftChanged());
        allowPty.selectedProperty().addListener((ignored, previous, value) -> draftChanged());
        processSeconds.textProperty().addListener((ignored, previous, value) -> draftChanged());
        maximumRisk.valueProperty().addListener((ignored, previous, value) -> draftChanged());
        approval.valueProperty().addListener((ignored, previous, value) -> draftChanged());
        memoryMiB.textProperty().addListener((ignored, previous, value) -> draftChanged());
        outputMiB.textProperty().addListener((ignored, previous, value) -> draftChanged());
        childProcesses.textProperty().addListener((ignored, previous, value) -> draftChanged());
        openFiles.textProperty().addListener((ignored, previous, value) -> draftChanged());
        includeTurnGrant.selectedProperty().addListener((ignored, previous, selected) -> {
            if (!rendering) {
                selectOptionalLayer(selected, previewTurnGrant, true);
            }
        });
        previewTurnGrant.valueProperty().addListener((ignored, previous, value) -> {
            if (!rendering && includeTurnGrant.isSelected()) {
                previewPresenter.selectTurnGrant(value);
            }
        });
        includeToolDeclaration.selectedProperty().addListener((ignored, previous, selected) -> {
            if (!rendering) {
                selectOptionalLayer(selected, previewToolDeclaration, false);
            }
        });
        previewToolDeclaration.valueProperty().addListener((ignored, previous, value) -> {
            if (!rendering && includeToolDeclaration.isSelected()) {
                previewPresenter.selectToolDeclaration(value);
            }
        });
        previewButton.setOnAction(event -> presenter.state().selected().ifPresent(previewPresenter::preview));
        save.setOnAction(event -> presenter.save());
        discard.setOnAction(event -> presenter.discardDraft());
    }

    private void draftChanged() {
        if (rendering || maximumRisk.getValue() == null || approval.getValue() == null) {
            return;
        }
        presenter.updateDraft(new PermissionProfileDraft(
                id.getText(),
                readRoots.getText(),
                writeRoots.getText(),
                allowDelete.isSelected(),
                followLinks.isSelected(),
                hosts.getText(),
                ports.getText(),
                tlsOnly.isSelected(),
                executables.getText(),
                allowPty.isSelected(),
                PermissionSettingsFormatting.longValue(processSeconds.getText()),
                allowedTools.selectedNames(),
                maximumRisk.getValue(),
                approval.getValue(),
                PermissionSettingsFormatting.longValue(memoryMiB.getText()),
                PermissionSettingsFormatting.longValue(outputMiB.getText()),
                PermissionSettingsFormatting.intValue(childProcesses.getText()),
                PermissionSettingsFormatting.intValue(openFiles.getText())));
    }

    private void render(PermissionProfileSettingsState state) {
        rendering = true;
        try {
            masterDetail.list().getItems().setAll(state.profiles());
            masterDetail.list().getSelectionModel().select(state.selected().orElse(null));
            renderDraft(state.draft(), state.selected().isPresent());
            renderStatus(state);
            syncHistory(state);
        } finally {
            rendering = false;
        }
        syncToolCatalog(state);
    }

    private void renderDraft(PermissionProfileDraft draft, boolean existing) {
        id.setText(draft.id());
        id.setDisable(existing);
        readRoots.setText(draft.readRoots());
        writeRoots.setText(draft.writeRoots());
        allowDelete.setSelected(draft.allowDelete());
        followLinks.setSelected(draft.followSymbolicLinks());
        hosts.setText(draft.networkHosts());
        ports.setText(draft.networkPorts());
        tlsOnly.setSelected(draft.tlsOnly());
        executables.setText(draft.executables());
        allowPty.setSelected(draft.allowPty());
        processSeconds.setText(Long.toString(draft.processSeconds()));
        allowedTools.showSelection(draft.allowedTools());
        maximumRisk.setValue(draft.maximumRisk());
        approval.setValue(draft.approvalRequirement());
        memoryMiB.setText(Long.toString(draft.memoryMiB()));
        outputMiB.setText(Long.toString(draft.outputMiB()));
        childProcesses.setText(Integer.toString(draft.childProcesses()));
        openFiles.setText(Integer.toString(draft.openFiles()));
    }

    private void renderStatus(PermissionProfileSettingsState state) {
        boolean unavailable = scopedWorkspace.isEmpty();
        editorSections.forEach(section -> section.setDisable(editorDisabled(state, unavailable)));
        id.setDisable(identityDisabled(state, unavailable));
        save.setDisable(saveDisabled(state, unavailable));
        discard.setDisable(state.pending() || !state.dirty());
        List<String> changed = state.draft().changedSections(state.baseline());
        diff.setText(changed.isEmpty() ? "没有本地更改" : "已更改：" + String.join("、", changed));
        if (state.revisionConflict()) {
            conflict.showUnknownActual(
                    state.selected().map(PermissionProfile::version).orElse(0L));
        } else {
            conflict.hide();
        }
        renderActionState(state);
        previewButton.setDisable(state.selected().isEmpty() || state.dirty());
    }

    private static boolean editorDisabled(PermissionProfileSettingsState state, boolean unavailable) {
        return unavailable || state.standardReadOnly() || state.cloning() || state.pending();
    }

    private static boolean identityDisabled(PermissionProfileSettingsState state, boolean unavailable) {
        return unavailable || state.selected().isPresent() || state.standardReadOnly() || state.pending();
    }

    private boolean saveDisabled(PermissionProfileSettingsState state, boolean unavailable) {
        return unavailable || state.pending() || state.standardReadOnly() || !state.dirty() || !allowedTools.ready();
    }

    private void renderActionState(PermissionProfileSettingsState state) {
        if (state.pending()) {
            actions.show(ActionState.PENDING, state.message());
        } else if (state.phase() == SettingsLoadState.ERROR) {
            actions.show(ActionState.ERROR, state.message());
        } else if (state.dirty()) {
            actions.show(ActionState.DIRTY, state.message().isBlank() ? "权限方案草稿尚未保存" : state.message());
        } else if (!state.message().isBlank()) {
            actions.show(ActionState.SUCCESS, state.message());
        } else {
            actions.show(ActionState.IDLE, "");
        }
    }

    private void showConflictComparison() {
        actions.show(ActionState.DIRTY, "服务端未返回实际版本；重新读取可查看权威版本，但会丢弃本地草稿。");
    }

    private void renderPreview(PermissionPreviewState state) {
        previewState = Objects.requireNonNull(state, "state");
        rendering = true;
        try {
            previewTurnGrant.getItems().setAll(state.candidates());
            previewTurnGrant.setValue(state.turnGrant().orElse(null));
            includeTurnGrant.setSelected(state.turnGrant().isPresent());
            previewTurnGrant.setDisable(state.turnGrant().isEmpty());
            previewToolDeclaration.getItems().setAll(state.candidates());
            previewToolDeclaration.setValue(state.toolDeclaration().orElse(null));
            includeToolDeclaration.setSelected(state.toolDeclaration().isPresent());
            previewToolDeclaration.setDisable(state.toolDeclaration().isEmpty());
        } finally {
            rendering = false;
        }
        previewButton.setDisable(state.phase() == SettingsLoadState.LOADING
                || scopedWorkspace.isEmpty()
                || state.selectedWorkspace().isEmpty()
                || presenter.state().selected().isEmpty()
                || presenter.state().dirty());
        previewLayers.getChildren().clear();
        state.preview().ifPresent(preview -> PermissionInsightRenderer.renderPreview(previewLayers, preview));
        previewMessage.setText(state.message());
        previewMessage.getStyleClass().remove("platform-action-error");
        if (state.phase() == SettingsLoadState.ERROR) {
            previewMessage.getStyleClass().add("platform-action-error");
        }
    }

    private void syncToolCatalog(PermissionProfileSettingsState state) {
        Optional<PermissionProfileRef> reference = state.selected()
                .map(profile -> new PermissionProfileRef(profile.id(), profile.version()))
                .or(() -> state.cloneSource());
        allowedTools.bind(scopedWorkspace.map(Workspace::id), reference);
    }

    private void syncHistory(PermissionProfileSettingsState state) {
        var desired = state.selected().map(profile -> new PermissionProfileRef(profile.id(), profile.version()));
        if (desired.equals(historyPresenter.state().reference())) {
            return;
        }
        if (state.selected().isPresent()) {
            historyPresenter.load(state.selected().orElseThrow());
        } else {
            historyPresenter.clear();
        }
    }

    private void renderHistory(PermissionHistoryState state) {
        PermissionInsightRenderer.renderHistory(historyRows, historyMessage, state);
    }

    private static TextArea area(int rows) {
        TextArea area = new TextArea();
        area.setPrefRowCount(rows);
        return area;
    }

    private void configurePermissionChoice(ComboBox<PermissionProfile> choice) {
        choice.setPromptText("未提供");
        choice.setMaxWidth(Double.MAX_VALUE);
        choice.setCellFactory(ignored -> components.detailCell(
                profile -> SettingsLabels.permissionProfile(profile.id()),
                profile -> profile.id() + " · 版本 " + profile.version()));
        choice.setButtonCell(components.textCell(profile -> profile == null
                ? ""
                : SettingsLabels.permissionProfile(profile.id()) + " · " + profile.id() + " · 版本 "
                        + profile.version()));
    }

    private void selectOptionalLayer(boolean selected, ComboBox<PermissionProfile> choice, boolean turnGrant) {
        choice.setDisable(!selected);
        PermissionProfile previous = choice.getValue();
        PermissionProfile value = selected ? selectedOrFirst(choice) : null;
        choice.setValue(value);
        if (selected && !Objects.equals(previous, value)) {
            return;
        }
        if (turnGrant) {
            previewPresenter.selectTurnGrant(value);
        } else {
            previewPresenter.selectToolDeclaration(value);
        }
    }

    private static PermissionProfile selectedOrFirst(ComboBox<PermissionProfile> choice) {
        PermissionProfile selected = choice.getValue();
        return selected == null && !choice.getItems().isEmpty()
                ? choice.getItems().getFirst()
                : selected;
    }
}
