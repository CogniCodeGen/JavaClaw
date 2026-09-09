package com.javaclaw.desktop.shell;

import java.io.File;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import com.javaclaw.api.ApprovalDecision;
import com.javaclaw.api.ApprovalRecord;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.Workspace;
import com.javaclaw.desktop.DesktopPresenter;
import com.javaclaw.desktop.LauncherSession;
import com.javaclaw.desktop.appearance.DesktopAppearanceManager;
import com.javaclaw.desktop.appearance.JavaPreferencesAppearanceStore;
import com.javaclaw.desktop.component.InputRequestPanel;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformDialogs;
import com.javaclaw.desktop.settings.ChatConfigurationPanel;
import com.javaclaw.desktop.settings.ExecutionSelectionPanel;
import com.javaclaw.desktop.settings.ManagementCenterWindow;
import com.javaclaw.desktop.settings.SdkCoreSettingsGateway;
import com.javaclaw.desktop.settings.SdkManagementSettingsGateways;
import com.javaclaw.desktop.state.ConnectionState;
import com.javaclaw.desktop.state.DesktopState;
import com.javaclaw.desktop.view.PresentedItem;
import com.javaclaw.desktop.view.TranscriptPresenter;
import com.javaclaw.protocol.CanonicalJson;

/** JavaFX 壳控制器；只绑定控件和转发用户意图，不保存领域状态。 */
public final class DesktopShellController implements AutoCloseable {
    private final TranscriptPresenter transcriptPresenter = new TranscriptPresenter(new CanonicalJson());
    private final PlatformComponentFactory components = new PlatformComponentFactory();

    @FXML
    private BorderPane root;

    @FXML
    private VBox sidebar;

    @FXML
    private VBox progressPanel;

    @FXML
    private ComboBox<Workspace> workspaceBox;

    @FXML
    private ListView<ConversationThread> threadList;

    @FXML
    private ListView<ItemEnvelope> transcriptList;

    @FXML
    private StackPane transcriptHost;

    @FXML
    private VBox executionHost;

    @FXML
    private TextArea composer;

    @FXML
    private Label connectionLabel;

    @FXML
    private Label threadTitle;

    @FXML
    private Label threadMeta;

    @FXML
    private Label errorLabel;

    @FXML
    private VBox connectionErrorCard;

    @FXML
    private Label connectionErrorDetail;

    @FXML
    private Button connectionStartButton;

    @FXML
    private Button sendButton;

    @FXML
    private Button interruptButton;

    @FXML
    private ListView<ApprovalRecord> approvalList;

    @FXML
    private VBox inputRequestHost;

    @FXML
    private Button approveButton;

    @FXML
    private Button denyButton;

    private DesktopPresenter presenter;
    private ShellCatalogBindings catalogs;
    private ShellWebSurfaces surfaces;
    private java.util.List<ItemEnvelope> displayedItems = java.util.List.of();
    private ManagementCenterWindow managementCenter;
    private InputRequestPanel inputRequests;
    private CodingExecutionPanel codingOutput;
    private boolean rendering;
    private ChatConfigurationPanel executionSelection;
    private ShellWindowFocus windowFocus;
    private final ComposerDrafts drafts = new ComposerDrafts();
    private DesktopState latestState = DesktopState.initial();
    private DesktopState renderedState;
    private boolean localError;
    private boolean bindingDraft;
    private Optional<Instant> executionConnection = Optional.empty();

    /** 配置单元格与选择事件。 */
    @FXML
    public void initialize() {
        workspaceBox.setCellFactory(ignored -> components.textCell(Workspace::name));
        workspaceBox.setButtonCell(components.textCell(Workspace::name));
        threadList.setCellFactory(ignored -> {
            ListCell<ConversationThread> cell = components.textCell(ConversationThread::title);
            // 选中项不变时也转发明确激活，让恢复失败的会话可重试；运行中的同会话由 Presenter 保持幂等。
            cell.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && !cell.isEmpty()) {
                    selectThread(cell.getItem());
                }
            });
            return cell;
        });
        threadList.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                selectThread(threadList.getSelectionModel().getSelectedItem());
                event.consume();
            }
        });
        transcriptList.setCellFactory(ignored -> new TranscriptCell(transcriptPresenter));
        approvalList.setCellFactory(ignored -> components.detailCell(
                approval -> approval.request().tool().name() + " · "
                        + approval.request().risk(),
                approval -> approval.request().explanation()));
        workspaceBox.valueProperty().addListener((observable, previous, value) -> selectWorkspace(value));
        threadList
                .getSelectionModel()
                .selectedItemProperty()
                .addListener((observable, previous, value) -> selectThread(value));
        approvalList
                .getSelectionModel()
                .selectedItemProperty()
                .addListener((observable, previous, value) -> renderApprovalButtons(value));
        configureLauncherRecovery();
        composer.textProperty().addListener((ignored, previous, text) -> {
            if (!bindingDraft) {
                drafts.edited(text);
            }
            if (executionSelection != null) {
                renderActions(latestState);
            }
        });
    }

    /**
     * 绑定 Presenter 并开始连接。
     *
     * @param value Desktop Presenter
     */
    public void attach(DesktopPresenter value) {
        DesktopAppearanceManager appearance = new DesktopAppearanceManager(new JavaPreferencesAppearanceStore());
        appearance.register(root.getScene());
        attach(value, new ManagementCenterWindow(appearance, SdkManagementSettingsGateways.create(value)));
    }

    /**
     * 绑定 Presenter、管理中心并开始连接；根节点必须已有 Scene，Scene 可尚未挂载到窗口。
     *
     * @param value Desktop Presenter
     * @param center 单实例设置与管理中心
     */
    public void attach(DesktopPresenter value, ManagementCenterWindow center) {
        if (presenter != null) {
            throw new IllegalStateException("controller is already attached");
        }
        presenter = Objects.requireNonNull(value, "presenter");
        catalogs = new ShellCatalogBindings(workspaceBox, threadList, approvalList);
        managementCenter = Objects.requireNonNull(center, "center");
        executionSelection = new ChatConfigurationPanel(
                new SdkCoreSettingsGateway(value),
                () -> managementCenter.show(root.getScene().getWindow(), "providers"),
                this::chooseChatWorkspace,
                () -> presenter.createThread("新对话"));
        executionHost.getChildren().setAll(executionSelection);
        javafx.scene.layout.HBox previousActions = (javafx.scene.layout.HBox) sendButton.getParent();
        previousActions.getChildren().removeAll(interruptButton, sendButton);
        ((VBox) previousActions.getParent()).getChildren().remove(previousActions);
        executionSelection.setActions(interruptButton, sendButton);
        executionSelection.onStateChanged(() -> renderActions(latestState));
        windowFocus = new ShellWindowFocus(root.getScene(), () -> {
            if (latestState.connection().status() == ConnectionState.Status.CONNECTED) {
                presenter.refreshWorkspaceCatalog();
                executionSelection.refresh();
            }
        });
        inputRequests = new InputRequestPanel(
                new CanonicalJson(),
                components,
                (request, response) -> presenter.inputs().resolve(request, response),
                request -> presenter.inputs().cancel(request));
        inputRequestHost.getChildren().setAll(inputRequests);
        codingOutput = new CodingExecutionPanel(value);
        progressPanel.getChildren().add(2, codingOutput);
        surfaces = new ShellWebSurfaces(value, progressPanel, transcriptHost, transcriptList);
        managementCenter.installShortcut(root.getScene());
        presenter.subscribe(this::render);
        presenter.connect();
    }

    /** 切换会话侧栏。 */
    @FXML
    public void toggleSidebar() {
        visible(sidebar, !sidebar.isVisible());
    }

    /** 切换审批与进度侧栏。 */
    @FXML
    public void toggleProgress() {
        visible(progressPanel, !progressPanel.isVisible());
    }

    /** 创建 Workspace。 */
    @FXML
    public void newWorkspace() {
        TextInputDialog nameDialog = PlatformDialogs.requiredText(
                root,
                "创建 Workspace",
                "设置 Workspace 名称",
                "该名称用于在 JavaClaw 中识别工作区；下一步将选择对应的本地根目录。",
                "例如：JavaClaw 开发",
                "新工作区",
                "继续");
        Optional<String> name = nameDialog.showAndWait().map(String::strip).filter(value -> !value.isEmpty());
        if (name.isEmpty()) {
            return;
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择 Workspace 根目录");
        File selected = chooser.showDialog(root.getScene().getWindow());
        if (selected != null) {
            chooseWorkspaceExecution(name.orElseThrow(), selected);
        }
    }

    private void chooseWorkspaceExecution(String name, File directory) {
        ExecutionSelectionPanel selection = new ExecutionSelectionPanel(new SdkCoreSettingsGateway(presenter));
        Dialog<com.javaclaw.api.ExecutionOverrides> dialog = new Dialog<>();
        dialog.setTitle("创建 Workspace");
        dialog.setHeaderText("分别选择 Agent、模型与权限");
        dialog.getDialogPane().setContent(selection);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
        PlatformDialogs.style(dialog, root);
        selection.prepareWorkspaceCreation();
        dialog.setResultConverter(button -> button == ButtonType.OK ? selection.execution() : null);
        dialog.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            if (selection.pending() || !selection.ready()) {
                event.consume();
            }
        });
        dialog.showAndWait()
                .ifPresent(execution -> presenter.createWorkspace(
                        name, directory.toPath().toAbsolutePath().normalize(), execution));
        selection.close();
    }

    /** 创建当前 Workspace 的 Thread。 */
    @FXML
    public void newThread() {
        TextInputDialog dialog = PlatformDialogs.requiredText(
                root, "创建对话", "设置对话标题", "标题用于在当前 Workspace 的对话列表中识别本次任务。", "例如：排查模型连接", "新对话", "创建对话");
        dialog.showAndWait()
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .ifPresent(presenter::createThread);
    }

    /** 发送当前输入。 */
    @FXML
    public void send() {
        try {
            if (!executionSelection.ready()) {
                return;
            }
            ComposerDrafts.Submission submitted = drafts.submission(composer.getText());
            var execution = executionSelection.execution();
            presenter
                    .send(submitted.text(), execution, drafts.options(submitted, execution))
                    .thenAccept(started -> {
                        if (submitted
                                        .scope()
                                        .thread()
                                        .filter(started.threadId()::equals)
                                        .isPresent()
                                && drafts.acknowledged(submitted)) {
                            composer.clear();
                        }
                    });
        } catch (RuntimeException failure) {
            localError = true;
            errorLabel.setText(failure.getMessage());
            errorLabel.setVisible(true);
            errorLabel.setManaged(true);
        }
    }

    /** 取消活动 Turn。 */
    @FXML
    public void interrupt() {
        presenter.cancelActiveTurn();
    }

    /** 打开或聚焦设置与管理中心；该动作不依赖 App Server 连接状态。 */
    @FXML
    public void openSettings() {
        managementCenter.show(root.getScene().getWindow());
    }

    /** 关闭旧 SDK 会话并重新协商 Protocol v3。 */
    @FXML
    public void retryConnection() {
        presenter.reconnect();
    }

    /** 打开设置中心的脱敏诊断页面。 */
    @FXML
    public void openDiagnostics() {
        managementCenter.show(root.getScene().getWindow(), "diagnostics");
    }

    /** 清除显式 Agent Role，恢复使用 Workspace 默认配置。 */
    @FXML
    public void useDefaultRole() {
        presenter.clearRoleSelection();
        executionSelection.discard();
    }

    /** 批准选中的调用。 */
    @FXML
    public void approve() {
        resolveSelected(ApprovalDecision.APPROVED, "Desktop 用户批准");
    }

    /** 拒绝选中的调用。 */
    @FXML
    public void deny() {
        resolveSelected(ApprovalDecision.DENIED, "Desktop 用户拒绝");
    }

    private void render(DesktopState state) {
        ShellRenderChanges changes = ShellRenderChanges.between(renderedState, state);
        latestState = state;
        rendering = true;
        try {
            catalogs.render(state);
            if (changes.transcript()) {
                renderTranscript(state);
            }
            if (changes.inputs()) {
                inputRequests.render(state.interaction().inputs());
            }
            if (changes.scope()) {
                bindDraft(state);
                executionSelection.bind(
                        state.threads().selectedWorkspace(), state.threads().selectedThread());
            }
            if (changes.scope() || changes.connection()) {
                codingOutput.bind(state);
                refreshExecutionConnection(state);
            }
            if (changes.labels() || localError) {
                renderLabels(state);
                localError = false;
            }
            if (changes.actions()) {
                renderActions(state);
            }
            renderedState = state;
        } finally {
            rendering = false;
        }
    }

    private void renderTranscript(DesktopState state) {
        if (!displayedItems.equals(state.transcript().items())) {
            displayedItems = state.transcript().items();
            transcriptPresenter.replaceItems(displayedItems);
            transcriptList.getItems().setAll(displayedItems);
        }
        surfaces.render(state);
    }

    private void refreshExecutionConnection(DesktopState state) {
        Optional<Instant> connectedAt = state.connection().connectedAt();
        if (!executionConnection.equals(connectedAt)) {
            executionConnection = connectedAt;
            if (connectedAt.isPresent()) {
                executionSelection.refresh();
                managementCenter.refreshExecutionConfiguration();
            }
        }
    }

    private void renderLabels(DesktopState state) {
        boolean connectionFailed = state.connection().status() == ConnectionState.Status.FAILED;
        connectionLabel.setText(
                connectionFailed ? "App Server 未连接" : state.connection().detail());
        connectionErrorDetail.setText(
                "无法连接本地 App Server。" + LauncherSession.current().recoveryInstruction());
        visible(connectionErrorCard, connectionFailed);
        threadTitle.setText(
                state.threads().selectedThread().map(ConversationThread::title).orElse("新对话"));
        threadMeta.setText(state.threads()
                .activeTurn()
                .map(turn -> "Turn " + turn.status())
                .orElseGet(() ->
                        state.threads().selectedWorkspace().map(Workspace::name).orElse("选择工作区，开始聊天")));
        threadMeta.setTooltip(state.threads()
                .activeTurn()
                .map(turn -> new Tooltip(
                        "Agent " + turn.role().id() + "@" + turn.role().revision()
                                + " · 模型 " + turn.provider().model()
                                + (turn.resolvedConfig().modelLocked() ? "（由 Agent 锁定）" : "")
                                + " · 权限 " + turn.permissionProfile().id() + "@"
                                + turn.permissionProfile().version()
                                + "\n"
                                + turn.resolvedConfig().provenance().stream()
                                        .map(source -> source.field() + " ← " + source.source() + " / "
                                                + source.sourceId() + "@" + source.revision())
                                        .collect(java.util.stream.Collectors.joining("\n"))))
                .orElse(null));
        errorLabel.setText(state.interaction().error().orElse(""));
        visible(errorLabel, state.interaction().error().isPresent());
    }

    private void renderActions(DesktopState state) {
        boolean ready = state.connection().status() == ConnectionState.Status.CONNECTED
                && state.threads().selectedThread().isPresent()
                && state.threads().activeTurn().isEmpty()
                && !state.interaction().busy()
                && executionSelection.ready()
                && !composer.getText().isBlank();
        sendButton.setDisable(!ready);
        interruptButton.setDisable(state.threads().activeTurn().isEmpty());
        visible(interruptButton, state.threads().activeTurn().isPresent());
        visible(sendButton, state.threads().activeTurn().isEmpty());
        renderApprovalButtons(approvalList.getSelectionModel().getSelectedItem());
    }

    private void bindDraft(DesktopState state) {
        var scope = new ComposerDrafts.Scope(
                state.threads().selectedWorkspace().map(Workspace::id),
                state.threads().selectedThread().map(ConversationThread::id));
        String text = drafts.bind(scope, composer.getText());
        bindingDraft = true;
        try {
            if (!composer.getText().equals(text)) {
                composer.setText(text);
            }
        } finally {
            bindingDraft = false;
        }
    }

    private void chooseChatWorkspace() {
        if (workspaceBox.getItems().isEmpty()) {
            newWorkspace();
        } else {
            workspaceBox.show();
        }
    }

    private void selectWorkspace(Workspace value) {
        if (!rendering && value != null) {
            presenter.selectWorkspace(value);
        }
    }

    private void selectThread(ConversationThread value) {
        if (!rendering && value != null) {
            presenter.selectThread(value);
        }
    }

    private void resolveSelected(ApprovalDecision decision, String reason) {
        ApprovalRecord selected = approvalList.getSelectionModel().getSelectedItem();
        if (selected != null) {
            presenter.resolveApproval(selected, decision, reason);
        }
    }

    private void renderApprovalButtons(ApprovalRecord selected) {
        approveButton.setDisable(selected == null);
        denyButton.setDisable(selected == null);
    }

    private void configureLauncherRecovery() {
        LauncherSession launcher = LauncherSession.current();
        connectionStartButton.setText(launcher.controlLabel());
        connectionStartButton.setDisable(true);
        connectionStartButton.setAccessibleText(launcher.recoveryInstruction());
        connectionStartButton.setTooltip(new Tooltip(launcher.recoveryInstruction()));
    }

    private static void visible(javafx.scene.Node node, boolean value) {
        node.setVisible(value);
        node.setManaged(value);
    }

    /** 释放窗口监听、主壳页面、预览租约与后台转换任务。 */
    @Override
    public void close() {
        if (windowFocus != null) {
            windowFocus.close();
        }
        if (executionSelection != null) {
            executionSelection.close();
        }
        if (surfaces != null) {
            surfaces.close();
        }
    }

    private static final class TranscriptCell extends ListCell<ItemEnvelope> {
        private final TranscriptPresenter presenter;
        private final Label title = new Label();
        private final Label body = new Label();
        private final VBox box = new VBox(6, title, body);

        private TranscriptCell(TranscriptPresenter presenter) {
            this.presenter = presenter;
            title.getStyleClass().add("message-role");
            body.setWrapText(true);
        }

        @Override
        protected void updateItem(ItemEnvelope item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            PresentedItem presented = presenter.present(item);
            title.setText(presented.title());
            body.setText(presented.body().length() > 65_536 ? presented.body().substring(0, 65_536) : presented.body());
            box.getStyleClass().setAll(presented.styleClass());
            setGraphic(box);
        }
    }
}
