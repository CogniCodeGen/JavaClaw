package com.javaclaw.desktop.shell;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

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
import com.javaclaw.desktop.settings.ChatConfigurationPanel;
import com.javaclaw.desktop.settings.ManagementCenterWindow;
import com.javaclaw.desktop.settings.SdkCoreSettingsGateway;
import com.javaclaw.desktop.settings.SdkManagementSettingsGateways;
import com.javaclaw.desktop.state.ConnectionState;
import com.javaclaw.desktop.state.DesktopState;
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
    private VBox composerCard;

    @FXML
    private Button pendingButton;

    @FXML
    private Label statusDot;

    @FXML
    private Label pendingEmpty;

    @FXML
    private Label approvalTitle;

    @FXML
    private HBox approvalActions;

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
    private ShellSidePanels sidePanels;
    private ShellComposerBehavior composerBehavior;
    private ShellStatusLabels statusLabels;
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
        transcriptList.setCellFactory(ignored -> new ShellTranscriptCell(transcriptPresenter));
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
        sidePanels = new ShellSidePanels(root, sidebar, progressPanel, pendingButton);
        statusLabels = new ShellStatusLabels(
                connectionLabel,
                threadTitle,
                threadMeta,
                errorLabel,
                connectionErrorCard,
                connectionErrorDetail,
                statusDot);
        composerBehavior = new ShellComposerBehavior(composer, composerCard, sendButton, () -> {
            if (!bindingDraft) {
                drafts.edited(composer.getText());
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
                this::newThread);
        executionHost.getChildren().setAll(executionSelection);
        javafx.scene.layout.HBox previousActions = (javafx.scene.layout.HBox) sendButton.getParent();
        previousActions.getChildren().removeAll(interruptButton, sendButton);
        ((VBox) previousActions.getParent()).getChildren().remove(previousActions);
        executionSelection.setActions(interruptButton, sendButton);
        executionSelection.onStateChanged(() -> renderActions(latestState));
        windowFocus = new ShellWindowFocus(root.getScene(), () -> {
            if (latestState.connection().status() == ConnectionState.Status.CONNECTED) {
                presenter.refreshWorkspaceCatalog();
                executionSelection.activate();
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
        surfaces = new ShellWebSurfaces(value, progressPanel, transcriptHost, transcriptList, sidePanels);
        managementCenter.installShortcut(root.getScene());
        presenter.subscribe(this::render);
        presenter.connect();
    }

    /** 切换会话侧栏。 */
    @FXML
    public void toggleSidebar() {
        sidePanels.toggleSidebar();
    }

    /** 切换审批与进度侧栏。 */
    @FXML
    public void toggleProgress() {
        sidePanels.toggleProgress();
    }

    /** 创建工作区；已登记目录可直接打开，不重复提交创建请求。 */
    @FXML
    public void newWorkspace() {
        ShellWorkspaceCreation.show(root, presenter, () -> latestState.threads().workspaces());
    }

    /** 直接创建当前工作区的空对话，首条消息提交后再生成标题。 */
    @FXML
    public void newThread() {
        presenter.createThread("新对话");
        // 仅响应本次用户操作；异步创建结果不改变用户后来选择的焦点。
        composer.requestFocus();
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
            sidePanels.render(state);
            renderPendingRegions(state);
            if (changes.transcript()) {
                renderTranscript(state);
            }
            if (changes.inputs()) {
                inputRequests.render(state.interaction().inputs());
            }
            if (changes.scope()) {
                bindDraft(state);
            }
            if (changes.scope() || changes.connection()) {
                executionSelection.bind(
                        state.threads().selectedWorkspace(),
                        state.threads().selectedThread(),
                        state.connection().connectedAt());
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
                managementCenter.refreshExecutionConfiguration();
            }
        }
    }

    private void renderLabels(DesktopState state) {
        statusLabels.render(state);
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
        visible(approvalActions, selected != null);
    }

    private void renderPendingRegions(DesktopState state) {
        boolean approvals = !state.interaction().pendingApprovals().isEmpty();
        boolean inputs = !state.interaction().inputs().pendingRequests().isEmpty()
                || state.interaction().inputs().error().isPresent();
        visible(approvalTitle, approvals);
        visible(approvalList, approvals);
        visible(inputRequestHost, inputs);
        visible(pendingEmpty, !approvals && !inputs);
        renderApprovalButtons(approvalList.getSelectionModel().getSelectedItem());
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
        sidePanels.close();
        composerBehavior.close();
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
}
