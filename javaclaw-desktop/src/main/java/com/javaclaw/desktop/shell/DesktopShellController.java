package com.javaclaw.desktop.shell;

import java.io.File;
import java.util.Objects;
import java.util.Optional;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import com.javaclaw.api.AgentProfile;
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
import com.javaclaw.desktop.settings.ManagementCenterWindow;
import com.javaclaw.desktop.settings.SdkManagementSettingsGateways;
import com.javaclaw.desktop.state.ConnectionState;
import com.javaclaw.desktop.state.DesktopState;
import com.javaclaw.desktop.view.PresentedItem;
import com.javaclaw.desktop.view.TranscriptPresenter;
import com.javaclaw.protocol.CanonicalJson;

/** JavaFX 壳控制器；只绑定控件和转发用户意图，不保存领域状态。 */
public final class DesktopShellController {
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
    private ComboBox<AgentProfile> profileBox;

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
    private ManagementCenterWindow managementCenter;
    private InputRequestPanel inputRequests;
    private boolean rendering;

    /** 配置单元格与选择事件。 */
    @FXML
    public void initialize() {
        workspaceBox.setCellFactory(ignored -> components.textCell(Workspace::name));
        workspaceBox.setButtonCell(components.textCell(Workspace::name));
        threadList.setCellFactory(ignored -> components.textCell(ConversationThread::title));
        transcriptList.setCellFactory(ignored -> new TranscriptCell(transcriptPresenter));
        profileBox.setCellFactory(ignored -> components.textCell(DesktopShellController::profileName));
        profileBox.setButtonCell(components.textCell(DesktopShellController::profileName));
        approvalList.setCellFactory(ignored -> components.detailCell(
                approval -> approval.request().tool().name() + " · "
                        + approval.request().risk(),
                approval -> approval.request().explanation()));
        workspaceBox.valueProperty().addListener((observable, previous, value) -> selectWorkspace(value));
        threadList
                .getSelectionModel()
                .selectedItemProperty()
                .addListener((observable, previous, value) -> selectThread(value));
        profileBox.valueProperty().addListener((observable, previous, value) -> selectProfile(value));
        approvalList
                .getSelectionModel()
                .selectedItemProperty()
                .addListener((observable, previous, value) -> renderApprovalButtons(value));
        configureLauncherRecovery();
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
     * 绑定 Presenter、管理中心并开始连接。
     *
     * @param value Desktop Presenter
     * @param center 单实例设置与管理中心
     */
    public void attach(DesktopPresenter value, ManagementCenterWindow center) {
        if (presenter != null) {
            throw new IllegalStateException("controller is already attached");
        }
        presenter = Objects.requireNonNull(value, "presenter");
        managementCenter = Objects.requireNonNull(center, "center");
        inputRequests = new InputRequestPanel(
                new CanonicalJson(),
                components,
                (request, response) -> presenter.inputs().resolve(request, response),
                request -> presenter.inputs().cancel(request));
        inputRequestHost.getChildren().setAll(inputRequests);
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
        TextInputDialog nameDialog = new TextInputDialog("新工作区");
        nameDialog.setHeaderText("输入 Workspace 名称");
        Optional<String> name = nameDialog.showAndWait().map(String::strip).filter(value -> !value.isEmpty());
        if (name.isEmpty()) {
            return;
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择 Workspace 根目录");
        File selected = chooser.showDialog(root.getScene().getWindow());
        if (selected != null) {
            presenter.createWorkspace(
                    name.orElseThrow(), selected.toPath().toAbsolutePath().normalize());
        }
    }

    /** 创建当前 Workspace 的 Thread。 */
    @FXML
    public void newThread() {
        TextInputDialog dialog = new TextInputDialog("新对话");
        dialog.setHeaderText("输入 Thread 标题");
        dialog.showAndWait()
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .ifPresent(presenter::createThread);
    }

    /** 发送当前输入。 */
    @FXML
    public void send() {
        try {
            presenter.send(composer.getText());
            composer.clear();
        } catch (RuntimeException failure) {
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

    /** 关闭旧 SDK 会话并重新协商 Protocol v2。 */
    @FXML
    public void retryConnection() {
        presenter.reconnect();
    }

    /** 打开设置中心的脱敏诊断页面。 */
    @FXML
    public void openDiagnostics() {
        managementCenter.show(root.getScene().getWindow(), "diagnostics");
    }

    /** 清除显式 Agent Profile，恢复使用 Workspace 默认配置。 */
    @FXML
    public void useDefaultProfile() {
        presenter.clearProfileSelection();
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
        rendering = true;
        try {
            workspaceBox.getItems().setAll(state.threads().workspaces());
            workspaceBox.setValue(state.threads().selectedWorkspace().orElse(null));
            threadList.getItems().setAll(state.threads().threads());
            threadList
                    .getSelectionModel()
                    .select(state.threads().selectedThread().orElse(null));
            transcriptList.getItems().setAll(state.transcript().items());
            profileBox.getItems().setAll(state.interaction().profiles());
            profileBox.setValue(state.interaction().selectedProfile().orElse(null));
            approvalList.getItems().setAll(state.interaction().pendingApprovals());
            inputRequests.render(state.interaction().inputs());
            renderLabels(state);
            renderActions(state);
        } finally {
            rendering = false;
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
                .orElse("选择工作区和权限配置后开始任务"));
        errorLabel.setText(state.interaction().error().orElse(""));
        visible(errorLabel, state.interaction().error().isPresent());
    }

    private void renderActions(DesktopState state) {
        boolean ready = state.connection().status() == ConnectionState.Status.CONNECTED
                && state.threads().selectedThread().isPresent()
                && !state.interaction().busy();
        sendButton.setDisable(!ready);
        interruptButton.setDisable(state.threads().activeTurn().isEmpty());
        renderApprovalButtons(approvalList.getSelectionModel().getSelectedItem());
        if (state.transcript().following() && !transcriptList.getItems().isEmpty()) {
            transcriptList.scrollTo(transcriptList.getItems().size() - 1);
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

    private void selectProfile(AgentProfile value) {
        if (!rendering && value != null) {
            presenter.selectProfile(value);
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

    private static String profileName(AgentProfile profile) {
        return profile.spec().displayName() + " · " + profile.id() + " · v" + profile.revision();
    }

    private static final class TranscriptCell extends ListCell<ItemEnvelope> {
        private final TranscriptPresenter presenter;

        private TranscriptCell(TranscriptPresenter presenter) {
            this.presenter = presenter;
        }

        @Override
        protected void updateItem(ItemEnvelope item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            PresentedItem presented = presenter.present(item);
            Label title = new Label(presented.title());
            title.getStyleClass().add("message-role");
            Label body = new Label(presented.body());
            body.setWrapText(true);
            VBox box = new VBox(6, title, body);
            box.getStyleClass().add(presented.styleClass());
            setGraphic(box);
        }
    }
}
