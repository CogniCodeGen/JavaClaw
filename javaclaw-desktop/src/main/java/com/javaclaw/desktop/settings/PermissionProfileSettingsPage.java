package com.javaclaw.desktop.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
import com.javaclaw.api.EffectivePermissionPreview;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.PermissionSection;
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

/** PermissionProfile 版本编辑、standard clone、本地 diff 和有效权限说明页面。 */
public final class PermissionProfileSettingsPage implements ManagedSettingsPage {
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final PermissionProfileSettingsPresenter presenter;
    private final PermissionPreviewPresenter previewPresenter;
    private final PermissionHistoryPresenter historyPresenter;
    private final VBox content = components.page("PermissionProfile");
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
    private final CheckBox tlsOnly = new CheckBox("只允许 TLS");
    private final TextArea executables = area(3);
    private final CheckBox allowPty = new CheckBox("允许 PTY");
    private final TextField processSeconds = new TextField();
    private final TextArea allowedTools = area(4);
    private final ComboBox<ToolRisk> maximumRisk = new ComboBox<>();
    private final ComboBox<ApprovalRequirement> approval = new ComboBox<>();
    private final TextField memoryMiB = new TextField();
    private final TextField outputMiB = new TextField();
    private final TextField childProcesses = new TextField();
    private final TextField openFiles = new TextField();
    private final Label diff = new Label();
    private final VBox historyRows = new VBox(6);
    private final Label historyMessage = new Label();
    private final ComboBox<Workspace> previewWorkspace = new ComboBox<>();
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
    private boolean rendering;

    /**
     * 创建 PermissionProfile 设置页。
     *
     * @param gateway SDK 异步边界
     */
    public PermissionProfileSettingsPage(CoreSettingsGateway gateway) {
        presenter = new PermissionProfileSettingsPresenter(gateway);
        previewPresenter = new PermissionPreviewPresenter(gateway);
        historyPresenter = new PermissionHistoryPresenter(gateway);
        save = components.action("保存新版本", ActionStyle.PRIMARY, ActionSize.NORMAL);
        discard = components.action("放弃更改", ActionStyle.GHOST, ActionSize.NORMAL);
        previewButton = components.action("计算有效权限", ActionStyle.SOFT, ActionSize.NORMAL);
        actions = new AsyncActionBar(discard, save);
        conflict = new RevisionConflictPane(presenter::reload, this::showConflictComparison);
        configureControls();
        buildLayout();
        bindEvents();
        presenter.subscribe(this::render);
        previewPresenter.subscribe(this::renderPreview);
        historyPresenter.subscribe(this::renderHistory);
    }

    @Override
    public Node content() {
        return content;
    }

    @Override
    public void activate() {
        presenter.reload();
        previewPresenter.reloadWorkspaces();
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
        id.setPromptText("clone 后输入新的稳定 ID");
        readRoots.setPromptText("每行一个绝对路径");
        writeRoots.setPromptText("每行一个绝对路径");
        hosts.setPromptText("每行一个小写 DNS 主机名；* 只能作为权限上限");
        ports.setPromptText("例如 443, 8443");
        executables.setPromptText("每行一个可执行文件规范名");
        processSeconds.setPromptText("30");
        allowedTools.setPromptText("每行一个完整工具名");
        maximumRisk.setItems(FXCollections.observableArrayList(ToolRisk.values()));
        approval.setItems(FXCollections.observableArrayList(ApprovalRequirement.values()));
        diff.setWrapText(true);
        diff.getStyleClass().add("sec-hint");
        historyMessage.setWrapText(true);
        historyMessage.getStyleClass().add("sec-hint");
        previewWorkspace.setCellFactory(ignored -> components.detailCell(
                Workspace::name, workspace -> workspace.id().value().toString()));
        previewWorkspace.setButtonCell(components.textCell(workspace -> workspace == null ? "" : workspace.name()));
        configurePermissionChoice(previewTurnGrant);
        configurePermissionChoice(previewToolDeclaration);
        previewMessage.setWrapText(true);
        previewMessage.getStyleClass().add("sec-hint");
    }

    private void buildLayout() {
        Label hint = new Label("权限版本不可变。活动 Turn 使用冻结版本与当前最新版本求交，因此后续扩权不会扩大活动 Turn，撤权会实时收窄。");
        hint.setWrapText(true);
        hint.getStyleClass().add("sec-hint");
        Button clone = components.action("Clone 为新配置", ActionStyle.PRIMARY, ActionSize.COMPACT);
        clone.setId("permissionCloneButton");
        clone.setOnAction(event -> presenter.cloneSelected());
        Button reload = components.action("刷新", ActionStyle.GHOST, ActionSize.COMPACT);
        reload.setOnAction(event -> presenter.reload());
        masterDetail
                .list()
                .setCellFactory(ignored -> components.detailCell(
                        PermissionProfile::id,
                        profile -> "v" + profile.version() + (profile.id().equals("standard") ? " · 内置只读" : "")));
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
                        conflict,
                        actions);
        masterDetail.showDetail(form);
        VBox.setVgrow(masterDetail, Priority.ALWAYS);
        content.getChildren().addAll(hint, new HBox(8, clone, reload), masterDetail);
    }

    private FormSection identitySection() {
        FormSection section = new FormSection("版本身份", "standard 是平台只读模板；自定义配置每次保存都会生成新版本。");
        section.addField("配置 ID", id);
        return section;
    }

    private FormSection fileSection() {
        FormSection section = new FormSection("文件", "最终访问根目录仍会与 system ceiling、Workspace 和 Turn grant 求交。");
        section.addField("读取根目录", readRoots);
        section.addField("写入根目录", writeRoots);
        section.addField("危险能力", new HBox(12, allowDelete, followLinks));
        return section;
    }

    private FormSection networkSection() {
        FormSection section = new FormSection("网络", "这里定义 Broker 上限；私网仍需要精确且可撤销的 PrivateNetworkGrant。");
        section.addField("主机", hosts);
        section.addField("端口", ports);
        section.addField("传输", tlsOnly);
        return section;
    }

    private FormSection processSection() {
        FormSection section = new FormSection("进程与 PTY", "进程始终由 Native Sandbox 启动；此处不能配置 HOST_FULL_ACCESS。");
        section.addField("可执行文件", executables);
        section.addField("最长运行（秒）", processSeconds);
        section.addField("交互终端", allowPty);
        return section;
    }

    private FormSection toolSection() {
        FormSection section = new FormSection("工具与审批", "工具声明风险也会参与求交；此处只能设置允许上限。");
        section.addField("允许工具", allowedTools);
        section.addField("最大风险", maximumRisk);
        section.addField("审批强度", approval);
        return section;
    }

    private FormSection resourceSection() {
        FormSection section = new FormSection("资源上限", "内存和输出使用 MiB；数值必须为有限正数。");
        section.addField("内存 MiB", memoryMiB);
        section.addField("输出 MiB", outputMiB);
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
        FormSection section = new FormSection("版本历史与服务端 diff", "历史版本不可变；diff 由服务端比较最近两个版本。");
        section.addFullWidth(historyRows);
        section.addFullWidth(historyMessage);
        return section;
    }

    private FormSection effectivePreview() {
        FormSection section =
                new FormSection("有效权限预览", "权威预览必须由服务端按 system ceiling、Workspace、Profile、Turn grant 和工具声明逐层求交。");
        section.addField("Workspace", previewWorkspace);
        section.addField("Turn grant", new HBox(8, includeTurnGrant, previewTurnGrant));
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
        allowedTools.textProperty().addListener((ignored, previous, value) -> draftChanged());
        maximumRisk.valueProperty().addListener((ignored, previous, value) -> draftChanged());
        approval.valueProperty().addListener((ignored, previous, value) -> draftChanged());
        memoryMiB.textProperty().addListener((ignored, previous, value) -> draftChanged());
        outputMiB.textProperty().addListener((ignored, previous, value) -> draftChanged());
        childProcesses.textProperty().addListener((ignored, previous, value) -> draftChanged());
        openFiles.textProperty().addListener((ignored, previous, value) -> draftChanged());
        previewWorkspace.valueProperty().addListener((ignored, previous, value) -> {
            if (!rendering) {
                previewPresenter.selectWorkspace(value);
            }
        });
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
                number(processSeconds.getText()),
                allowedTools.getText(),
                maximumRisk.getValue(),
                approval.getValue(),
                number(memoryMiB.getText()),
                number(outputMiB.getText()),
                integer(childProcesses.getText()),
                integer(openFiles.getText())));
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
        allowedTools.setText(draft.allowedTools());
        maximumRisk.setValue(draft.maximumRisk());
        approval.setValue(draft.approvalRequirement());
        memoryMiB.setText(Long.toString(draft.memoryMiB()));
        outputMiB.setText(Long.toString(draft.outputMiB()));
        childProcesses.setText(Integer.toString(draft.childProcesses()));
        openFiles.setText(Integer.toString(draft.openFiles()));
    }

    private void renderStatus(PermissionProfileSettingsState state) {
        editorSections.forEach(
                section -> section.setDisable(state.standardReadOnly() || state.cloning() || state.pending()));
        id.setDisable(state.selected().isPresent() || state.standardReadOnly() || state.pending());
        save.setDisable(state.pending() || state.standardReadOnly() || !state.dirty());
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

    private void renderActionState(PermissionProfileSettingsState state) {
        if (state.pending()) {
            actions.show(ActionState.PENDING, state.message());
        } else if (state.phase() == SettingsLoadState.ERROR) {
            actions.show(ActionState.ERROR, state.message());
        } else if (state.dirty()) {
            actions.show(ActionState.DIRTY, state.message().isBlank() ? "PermissionProfile 草稿尚未保存" : state.message());
        } else if (!state.message().isBlank()) {
            actions.show(ActionState.SUCCESS, state.message());
        } else {
            actions.show(ActionState.IDLE, "");
        }
    }

    private void showConflictComparison() {
        actions.show(ActionState.DIRTY, "服务端未返回实际 revision；重新读取可查看权威版本，但会丢弃本地草稿。");
    }

    private void renderPreview(PermissionPreviewState state) {
        rendering = true;
        try {
            previewWorkspace.getItems().setAll(state.workspaces());
            previewWorkspace.setValue(state.selectedWorkspace().orElse(null));
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
                || state.selectedWorkspace().isEmpty()
                || presenter.state().selected().isEmpty()
                || presenter.state().dirty());
        previewLayers.getChildren().clear();
        state.preview().ifPresent(this::renderPreviewLayers);
        previewMessage.setText(state.message());
        previewMessage.getStyleClass().remove("platform-action-error");
        if (state.phase() == SettingsLoadState.ERROR) {
            previewMessage.getStyleClass().add("platform-action-error");
        }
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
        historyRows.getChildren().clear();
        for (PermissionProfile profile : state.history()) {
            Label version = new Label("v" + profile.version()
                    + (state.reference()
                                    .map(reference -> reference.version() == profile.version())
                                    .orElse(false)
                            ? " · 当前"
                            : ""));
            version.getStyleClass().add("platform-detail-text");
            historyRows.getChildren().add(version);
        }
        state.diff().ifPresent(serverDiff -> {
            String sections = serverDiff.changedSections().isEmpty()
                    ? "没有分区变化"
                    : String.join(
                            "、",
                            serverDiff.changedSections().stream()
                                    .map(PermissionProfileSettingsPage::sectionName)
                                    .sorted()
                                    .toList());
            Label comparison = new Label("v" + serverDiff.before().version() + " → v"
                    + serverDiff.after().version() + "：" + sections);
            comparison.setWrapText(true);
            comparison.getStyleClass().add("platform-detail-text");
            historyRows.getChildren().add(comparison);
        });
        historyMessage.setText(state.message());
        historyMessage.getStyleClass().remove("platform-action-error");
        if (state.phase() == SettingsLoadState.ERROR) {
            historyMessage.getStyleClass().add("platform-action-error");
        }
    }

    private void renderPreviewLayers(EffectivePermissionPreview preview) {
        for (var layer : preview.layers()) {
            String source = layer.source()
                    .map(reference -> reference.id() + " v" + reference.version())
                    .orElse("平台即时约束");
            String result = layer.denialReasons().isEmpty() ? "未进一步收窄" : String.join("；", layer.denialReasons());
            Label line = new Label(layer.layer() + " · " + (layer.applied() ? source : "未提供") + " · " + result);
            line.setWrapText(true);
            line.getStyleClass().add("platform-detail-text");
            previewLayers.getChildren().add(line);
        }
        if (!preview.denialReasons().isEmpty()) {
            Label denials = new Label("最终拒绝原因：" + String.join("；", preview.denialReasons()));
            denials.setWrapText(true);
            denials.getStyleClass().add("platform-action-error");
            previewLayers.getChildren().add(denials);
        }
    }

    private static TextArea area(int rows) {
        TextArea area = new TextArea();
        area.setPrefRowCount(rows);
        return area;
    }

    private void configurePermissionChoice(ComboBox<PermissionProfile> choice) {
        choice.setPromptText("未提供");
        choice.setMaxWidth(Double.MAX_VALUE);
        choice.setCellFactory(
                ignored -> components.detailCell(PermissionProfile::id, profile -> "v" + profile.version()));
        choice.setButtonCell(
                components.textCell(profile -> profile == null ? "" : profile.id() + " · v" + profile.version()));
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

    private static String sectionName(PermissionSection section) {
        return switch (section) {
            case FILE -> "文件";
            case NETWORK -> "网络";
            case PROCESS -> "进程与 PTY";
            case TOOL -> "工具与审批";
            case RESOURCE -> "资源上限";
        };
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
