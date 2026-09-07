package com.javaclaw.desktop.settings;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javafx.animation.PauseTransition;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainKind;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainRef;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.component.AsyncActionBar;
import com.javaclaw.desktop.component.AsyncActionBar.ActionState;
import com.javaclaw.desktop.component.FormSection;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.protocol.InputJobRpcContracts;

/** 托管编程环境设置；沿用平台表单，任何项目依赖安装均由当前 Turn 的工具与审批执行。 */
public final class CodingSettingsPage implements ManagedSettingsPage {
    private final CodingSettingsGateway gateway;
    private final CodingPreparationStatus preparationStatus;
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final VBox content = components.page("编程环境");
    private final TextField name = new TextField();
    private final TextArea repositories = new TextArea();
    private final CheckBox lifecycleScripts = new CheckBox("允许原生依赖安装脚本");
    private final Map<ToolchainKind, ComboBox<ToolchainRef>> selectors = new EnumMap<>(ToolchainKind.class);
    private final ComboBox<CodingEnvironmentContracts.ToolchainArtifact> artifact = new ComboBox<>();
    private final Label installed = new Label();
    private final Label progress = new Label();
    private final Button save;
    private final Button refresh;
    private final Button install;
    private final Button cancel;
    private final AsyncActionBar actions;
    private final PauseTransition polling = new PauseTransition(Duration.seconds(2));
    private Optional<WorkspaceId> workspaceId = Optional.empty();
    private Optional<CodingSettingsGateway.Snapshot> snapshot = Optional.empty();
    private Optional<InputJobRpcContracts.JobReadResult> job = Optional.empty();
    private String jobId;
    private long epoch;
    private boolean loading;
    private boolean writing;
    private boolean dirty;
    private boolean rendering;
    private boolean active;

    /**
     * 创建 Coding 环境页。
     *
     * @param gateway 返回结果由 UI 调度器完成的 SDK 边界
     */
    public CodingSettingsPage(CodingSettingsGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        preparationStatus = new CodingPreparationStatus(gateway);
        save = action("保存环境", ActionStyle.PRIMARY, this::save);
        refresh = action("刷新", ActionStyle.GHOST, this::reload);
        install = action("安装选中工具链", ActionStyle.SOFT, this::install);
        cancel = action("取消工具链安装", ActionStyle.DANGER, this::cancel);
        save.setId("codingSave");
        install.setId("codingInstall");
        cancel.setId("codingCancelInstall");
        actions = new AsyncActionBar(refresh, save);
        buildLayout();
        polling.setOnFinished(event -> pollJob());
        updateButtons();
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
        active = true;
        preparationStatus.bind(workspaceId, true);
        if (!dirty) {
            reload();
        }
        pollJob();
    }

    @Override
    public void deactivate() {
        active = false;
        preparationStatus.bind(workspaceId, false);
        polling.stop();
    }

    @Override
    public boolean dirty() {
        return dirty;
    }

    @Override
    public boolean pending() {
        return writing;
    }

    @Override
    public void workspaceChanged(Optional<Workspace> workspace) {
        Optional<WorkspaceId> next =
                Objects.requireNonNull(workspace, "workspace").map(Workspace::id);
        if (next.equals(workspaceId)) {
            return;
        }
        workspaceId = next;
        preparationStatus.bind(next, active);
        epoch++;
        snapshot = Optional.empty();
        job = Optional.empty();
        jobId = null;
        dirty = false;
        loading = false;
        writing = false;
        progress.setText("");
        polling.stop();
        clear();
        reload();
    }

    @Override
    public void warnUnsavedChanges() {
        actions.show(ActionState.DIRTY, "编程环境尚未保存，请保存或丢弃草稿后离开。");
    }

    @Override
    public void discardDraft() {
        dirty = false;
        snapshot.ifPresent(this::render);
        actions.show(ActionState.IDLE, "草稿已丢弃");
    }

    @Override
    public void dispose() {
        deactivate();
        epoch++;
    }

    private void buildLayout() {
        FormSection environment = new FormSection("项目环境", "聊天与编程共用同一会话；所选工具链在后续操作中使用。");
        name.setId("codingEnvironmentName");
        name.textProperty().addListener((observable, previous, value) -> changed());
        environment.addField("环境名称", name);
        for (ToolchainKind kind : ToolchainKind.values()) {
            ComboBox<ToolchainRef> selector = new ComboBox<>();
            selector.setId("codingToolchain" + kind.name());
            selector.setPromptText("未选择");
            selector.setCellFactory(ignored -> components.textCell(CodingSettingsPage::toolchainLabel));
            selector.setButtonCell(components.textCell(CodingSettingsPage::toolchainLabel));
            selector.valueProperty().addListener((observable, previous, value) -> changed());
            selectors.put(kind, selector);
            Button clear = action("清除", ActionStyle.GHOST, () -> selector.setValue(null));
            clear.disableProperty().bind(selector.disableProperty());
            environment.addField(kind.name(), new HBox(8, selector, clear));
        }
        FormSection dependencies =
                new FormSection("依赖准备", "准备阶段可访问选中的公共 HTTPS 仓库；构建和测试默认断网。请在会话中请求安装依赖，审批与取消继续作用于当前任务。");
        repositories.setId("codingRepositoryHosts");
        repositories.setPromptText("每行一个仓库主机名");
        repositories.setPrefRowCount(4);
        repositories.textProperty().addListener((observable, previous, value) -> changed());
        lifecycleScripts.selectedProperty().addListener((observable, previous, value) -> changed());
        dependencies.addField("公共仓库", repositories);
        dependencies.addFullWidth(lifecycleScripts);
        Label scripts = new Label("关闭后，仅支持能约束安装脚本的包管理器；Maven / Gradle 无法禁用时会明确拒绝准备。");
        scripts.setWrapText(true);
        scripts.getStyleClass().add("sec-hint");
        dependencies.addFullWidth(scripts);
        content.getChildren().addAll(environment, dependencies, preparationStatus.section(), installationSection());
    }

    private FormSection installationSection() {
        FormSection section = new FormSection("托管工具链", "下载、校验和解包由应用管理；安装任务不执行项目脚本。");
        artifact.setId("codingInstallArtifact");
        artifact.setCellFactory(ignored -> components.textCell(value -> toolchainLabel(value.reference())));
        artifact.setButtonCell(components.textCell(value -> toolchainLabel(value.reference())));
        artifact.valueProperty().addListener((observable, previous, value) -> updateButtons());
        installed.setWrapText(true);
        progress.setWrapText(true);
        progress.setId("codingInstallProgress");
        section.addField("发行目录", artifact);
        section.addFullWidth(new HBox(8, install, cancel));
        section.addField("安装状态", installed);
        section.addField("后台任务", progress);
        return section;
    }

    private void reload() {
        if (workspaceId.isEmpty() || loading || writing || dirty) {
            updateButtons();
            return;
        }
        long requestEpoch = ++epoch;
        loading = true;
        actions.show(ActionState.PENDING, "正在读取编程环境…");
        updateButtons();
        gateway.load(workspaceId.orElseThrow()).whenComplete((result, failure) -> {
            if (requestEpoch != epoch) {
                return;
            }
            loading = false;
            if (failure == null) {
                snapshot = Optional.of(result);
                render(result);
                actions.show(ActionState.IDLE, "");
            } else {
                actions.show(ActionState.ERROR, SettingsFailures.message(failure));
            }
            updateButtons();
        });
    }

    private void render(CodingSettingsGateway.Snapshot value) {
        rendering = true;
        try {
            name.setText(value.environment().spec().name());
            repositories.setText(value.environment().spec().repositoryHosts().stream()
                    .sorted()
                    .collect(Collectors.joining("\n")));
            lifecycleScripts.setSelected(value.environment().spec().allowLifecycleScripts());
            selectors.forEach((kind, selector) -> {
                List<ToolchainRef> options = new ArrayList<>(value.catalog().artifacts().stream()
                        .map(CodingEnvironmentContracts.ToolchainArtifact::reference)
                        .filter(ref -> ref.kind() == kind)
                        .toList());
                Optional<ToolchainRef> selected = value.environment().spec().toolchains().stream()
                        .filter(ref -> ref.kind() == kind)
                        .findFirst();
                selected.filter(ref -> !options.contains(ref)).ifPresent(options::add);
                selector.getItems().setAll(options);
                selector.setValue(selected.orElse(null));
            });
            artifact.getItems().setAll(value.catalog().artifacts());
            installed.setText(value.installed().toolchains().stream()
                    .map(tool -> toolchainLabel(tool.reference()) + " · " + tool.state())
                    .collect(Collectors.joining("\n")));
            dirty = false;
        } finally {
            rendering = false;
        }
        updateButtons();
    }

    private void save() {
        if (workspaceId.isEmpty() || snapshot.isEmpty() || writing || loading) {
            return;
        }
        CodingEnvironmentContracts.EnvironmentSpec spec;
        try {
            Set<String> hosts = repositories
                    .getText()
                    .lines()
                    .map(String::strip)
                    .filter(value -> !value.isEmpty())
                    .collect(Collectors.toSet());
            spec = new CodingEnvironmentContracts.EnvironmentSpec(
                    name.getText(),
                    selectors.values().stream()
                            .map(ComboBox::getValue)
                            .filter(Objects::nonNull)
                            .toList(),
                    hosts,
                    lifecycleScripts.isSelected());
        } catch (IllegalArgumentException failure) {
            actions.show(ActionState.ERROR, SettingsFailures.message(failure));
            return;
        }
        writing = true;
        long requestEpoch = epoch;
        actions.show(ActionState.PENDING, "正在保存环境…");
        updateButtons();
        gateway.save(
                        workspaceId.orElseThrow(),
                        spec,
                        CommandOptions.create(
                                snapshot.orElseThrow().environment().revision()))
                .whenComplete((result, failure) -> {
                    if (requestEpoch != epoch) {
                        return;
                    }
                    writing = false;
                    if (failure == null) {
                        var previous = snapshot.orElseThrow();
                        snapshot = Optional.of(
                                new CodingSettingsGateway.Snapshot(result, previous.catalog(), previous.installed()));
                        render(snapshot.orElseThrow());
                        actions.show(ActionState.SUCCESS, "环境已保存，后续任务使用新配置");
                    } else {
                        actions.show(ActionState.ERROR, SettingsFailures.message(failure));
                    }
                    updateButtons();
                });
    }

    private void install() {
        if (workspaceId.isEmpty() || artifact.getValue() == null || writing) {
            return;
        }
        writing = true;
        long requestEpoch = epoch;
        updateButtons();
        gateway.install(workspaceId.orElseThrow(), artifact.getValue().reference(), CommandOptions.create(0))
                .whenComplete((result, failure) -> {
                    if (requestEpoch != epoch) {
                        return;
                    }
                    writing = false;
                    if (failure == null) {
                        jobId = result.jobId();
                        progress.setText("工具链安装已排队 · " + jobId);
                        pollJob();
                    } else {
                        actions.show(ActionState.ERROR, SettingsFailures.message(failure));
                    }
                    updateButtons();
                });
    }

    private void pollJob() {
        if (!active || jobId == null) {
            return;
        }
        long requestEpoch = epoch;
        String requestedJob = jobId;
        gateway.job(requestedJob).whenComplete((result, failure) -> {
            if (requestEpoch != epoch || !requestedJob.equals(jobId)) {
                return;
            }
            if (failure != null) {
                progress.setText(SettingsFailures.message(failure));
                return;
            }
            job = Optional.of(result);
            progress.setText(SettingsLabels.executionState(result.job().state()) + " · "
                    + result.job().id() + "\n"
                    + result.units().stream()
                            .map(unit -> unit.unitId() + " · " + unit.state())
                            .collect(Collectors.joining("\n"))
                    + result.job().errorCode().map(code -> "\n" + code).orElse(""));
            updateButtons();
            if (active && !result.job().state().terminal()) {
                polling.playFromStart();
            } else if (result.job().state().terminal() && !dirty) {
                reload();
            }
        });
    }

    private void cancel() {
        if (job.isEmpty() || writing) {
            return;
        }
        writing = true;
        long requestEpoch = epoch;
        var selected = job.orElseThrow().job();
        updateButtons();
        gateway.cancel(selected.id(), CommandOptions.create(selected.revision()))
                .whenComplete((ignored, failure) -> {
                    if (requestEpoch != epoch) {
                        return;
                    }
                    writing = false;
                    if (failure != null) {
                        actions.show(ActionState.ERROR, SettingsFailures.message(failure));
                    }
                    pollJob();
                    updateButtons();
                });
    }

    private void clear() {
        rendering = true;
        name.clear();
        repositories.clear();
        selectors.values().forEach(selector -> selector.getItems().clear());
        artifact.getItems().clear();
        installed.setText("");
        rendering = false;
        updateButtons();
    }

    private void changed() {
        if (!rendering) {
            dirty = true;
            actions.show(ActionState.DIRTY, "环境有未保存修改");
            updateButtons();
        }
    }

    private void updateButtons() {
        boolean unavailable = workspaceId.isEmpty() || loading || writing;
        save.setDisable(unavailable || !dirty || snapshot.isEmpty());
        refresh.setDisable(unavailable || dirty);
        install.setDisable(unavailable
                || artifact.getValue() == null
                || job.filter(value -> !value.job().state().terminal()).isPresent());
        cancel.setDisable(unavailable
                || job.filter(value -> !value.job().state().terminal()).isEmpty());
        boolean draftUnavailable = unavailable || snapshot.isEmpty();
        name.setDisable(draftUnavailable);
        repositories.setDisable(draftUnavailable);
        lifecycleScripts.setDisable(draftUnavailable);
        selectors.values().forEach(selector -> selector.setDisable(draftUnavailable));
    }

    private Button action(String title, ActionStyle style, Runnable operation) {
        Button button = components.action(title, style, ActionSize.NORMAL);
        button.setOnAction(event -> operation.run());
        return button;
    }

    private static String toolchainLabel(ToolchainRef value) {
        return value == null
                ? "未选择"
                : value.kind() + " " + value.version() + " · "
                        + value.artifactSha256().substring(0, 12);
    }
}
