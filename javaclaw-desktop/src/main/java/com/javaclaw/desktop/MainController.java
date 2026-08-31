package com.javaclaw.desktop;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.geometry.Dimension2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import com.javaclaw.sdk.AttachmentClient;
import com.javaclaw.sdk.model.ApprovalItemContent;
import com.javaclaw.sdk.model.ProfileInfo;
import com.javaclaw.sdk.model.ThreadInfo;
import com.javaclaw.sdk.model.UserInputItemContent;
import com.javaclaw.sdk.model.WorkspaceInfo;

/** 原桌面视觉体系的 SDK 控制器；只负责交互与展示，权限及持久化由服务端处理。 */
public final class MainController {
    private static final PseudoClass COMPOSER_FOCUS_WITHIN = PseudoClass.getPseudoClass("focus-within");

    private final DesktopViewModel model;
    private final ConversationDraftStore drafts = new ConversationDraftStore();
    private final javafx.collections.ObservableList<Path> pendingAttachments = FXCollections.observableArrayList();
    private final javafx.collections.ObservableList<TranscriptBlock> visibleTranscript =
            FXCollections.observableArrayList();
    private final Set<String> selectedThreadIds = new LinkedHashSet<>();
    private final Map<ConversationDraftStore.Key, Integer> transcriptPositions = new HashMap<>();
    private ConversationDraftStore.Key activeDraftKey = new ConversationDraftStore.Key("", "");
    private boolean restoringDraft;
    private boolean managingThreads;
    private final TranscriptFollowState transcriptFollow = new TranscriptFollowState();
    private Node focusBeforeDrawer;
    private Stage managementWindow;
    private ManagementController managementController;
    private final Map<String, Dimension2D> managementSizes = new HashMap<>();
    private final javafx.collections.ObservableList<DesktopProgressSnapshot.Entry> progressEntries =
            FXCollections.observableArrayList();
    private String currentManagementSection;
    private ShellMode shellMode;
    private boolean sidebarOpen = true;
    private boolean progressOpen = true;
    private WindowToast toast;

    @FXML
    private BorderPane root;

    @FXML
    private VBox sidebar;

    @FXML
    private StackPane shellStack;

    @FXML
    private BorderPane chatPane;

    @FXML
    private VBox progressPanel;

    @FXML
    private ComboBox<WorkspaceInfo> workspaceBox;

    @FXML
    private ListView<ThreadInfo> threadList;

    @FXML
    private ComboBox<ProfileInfo> profileBox;

    @FXML
    private ListView<TranscriptBlock> transcriptList;

    @FXML
    private TextArea composer;

    @FXML
    private TextField searchField;

    @FXML
    private Label connectionLabel;

    @FXML
    private Label errorLabel;

    @FXML
    private Label windowToast;

    @FXML
    private Label threadTitle;

    @FXML
    private Label threadMeta;

    @FXML
    private VBox threadHeaderText;

    @FXML
    private VBox composerCard;

    @FXML
    private Label progressPhase;

    @FXML
    private Label progressStatus;

    @FXML
    private Label inputTokens;

    @FXML
    private Label outputTokens;

    @FXML
    private Label reasoningTokens;

    @FXML
    private VBox attachmentPreview;

    @FXML
    private FlowPane attachmentChips;

    @FXML
    private Label composerStatus;

    @FXML
    private Button cancelUploadButton;

    @FXML
    private Button attachButton;

    @FXML
    private Button removeAttachmentButton;

    @FXML
    private Button newMessagesButton;

    @FXML
    private Region drawerScrim;

    @FXML
    private ComboBox<DesktopViewModel.ThreadScope> threadScopeBox;

    @FXML
    private Label threadResultCount;

    @FXML
    private HBox threadBatchBar;

    @FXML
    private Label threadBatchCount;

    @FXML
    private Button threadManageButton;

    @FXML
    private MenuButton workspaceActionMenu;

    @FXML
    private MenuButton themeMenu;

    @FXML
    private MenuButton threadActionMenu;

    @FXML
    private ListView<DesktopProgressSnapshot.Entry> progressList;

    @FXML
    private Button sendButton;

    @FXML
    private Button interruptButton;

    MainController(DesktopViewModel model) {
        this.model = model;
    }

    @FXML
    private void initialize() {
        root.setMinSize(0, 0);
        shellStack.setMinSize(0, 0);
        chatPane.setMinSize(0, 0);
        sidebar.setMinHeight(0);
        progressPanel.setMinHeight(0);
        workspaceBox.setItems(model.workspaces());
        threadList.setItems(model.threads());
        profileBox.setItems(model.profiles());
        transcriptList.setItems(visibleTranscript);
        progressList.setItems(progressEntries);
        connectionLabel.textProperty().bind(model.connectionProperty());
        errorLabel.textProperty().bind(model.errorProperty());
        hideWhenEmpty(errorLabel);
        toast = new WindowToast(windowToast);
        model.errorProperty().addListener((ignored, old, value) -> {
            if (value != null && !value.isBlank()) {
                UiMotion.error(errorLabel);
                toast.error(value);
            }
        });
        threadScopeBox.setItems(FXCollections.observableArrayList(DesktopViewModel.ThreadScope.values()));
        threadScopeBox.setValue(DesktopViewModel.ThreadScope.ACTIVE);
        attachmentPreview.setVisible(false);
        attachmentPreview.managedProperty().bind(attachmentPreview.visibleProperty());
        threadBatchBar.setVisible(false);
        threadBatchBar.managedProperty().bind(threadBatchBar.visibleProperty());
        newMessagesButton.setVisible(false);
        newMessagesButton.managedProperty().bind(newMessagesButton.visibleProperty());

        workspaceBox.setCellFactory(ignored -> workspaceCell());
        workspaceBox.setButtonCell(workspaceCell());
        profileBox.setCellFactory(ignored -> profileCell());
        profileBox.setButtonCell(profileCell());
        configureComposerCardFocus();
        threadList.setCellFactory(ignored -> threadCell());
        transcriptList.setCellFactory(ignored -> transcriptCell());
        progressList.setCellFactory(ignored -> progressCell());
        workspaceBox.valueProperty().addListener((ignored, old, value) -> {
            if (value != null) {
                model.selectWorkspace(value);
                switchDraftContext();
            }
            workspaceActionMenu.setDisable(value == null);
        });
        threadList.getSelectionModel().selectedItemProperty().addListener((ignored, old, value) -> {
            // 列表刷新时会短暂清空选择，此时不能取消另一个已接受的异步请求。
            if (value != null) {
                model.selectThread(value);
            }
        });
        model.selectedThreadProperty().addListener((ignored, old, value) -> {
            rememberTranscriptPosition();
            saveDraft();
            threadTitle.setText(value == null ? "新对话" : value.title());
            threadMeta.setText(value == null ? "选择工作区，开始你的任务" : "会话记录由本机 App Server 保存");
            threadList.getSelectionModel().select(value);
            threadActionMenu.setDisable(value == null);
            activeDraftKey = draftKey();
            restoreDraft();
            updateNewMessagesButton(transcriptFollow.reset(
                    model.transcript().size(), !model.streamProperty().get().isBlank()));
            rebuildTranscript(false);
            restoreTranscriptPosition();
        });
        threadActionMenu.setDisable(model.selectedThread() == null);
        model.progressProperty().addListener((ignored, old, value) -> renderProgress(value));
        renderProgress(model.progressProperty().get());
        searchField.textProperty().addListener((ignored, old, value) -> model.searchThreads(value));
        threadScopeBox.valueProperty().addListener((ignored, old, value) -> model.setThreadScope(value));
        model.threads().addListener((javafx.collections.ListChangeListener<ThreadInfo>) change -> {
            selectedThreadIds.retainAll(
                    model.threads().stream().map(ThreadInfo::id).toList());
            threadResultCount.setText(model.threads().size() + " 条");
            updateBatchCount();
            threadList.refresh();
        });
        model.workspaces().addListener((javafx.collections.ListChangeListener<WorkspaceInfo>) change -> {
            if (workspaceBox.getValue() == null && !model.workspaces().isEmpty()) {
                workspaceBox.setValue(model.workspaces().getFirst());
            }
            workspaceActionMenu.setDisable(workspaceBox.getValue() == null);
        });
        model.profiles().addListener((javafx.collections.ListChangeListener<ProfileInfo>) change -> {
            restoreProfile(drafts.read(activeDraftKey).profileId());
        });
        composer.textProperty().addListener((ignored, old, value) -> {
            saveDraft();
            updateComposerState(model.composerActivityProperty().get());
        });
        profileBox.valueProperty().addListener((ignored, old, value) -> {
            // Profile 列表刷新会短暂清空 ComboBox 值；不能用这个过渡状态覆盖当前 Thread 的选择。
            if (value != null) {
                saveDraft();
            }
        });
        pendingAttachments.addListener((javafx.collections.ListChangeListener<Path>) change -> {
            renderAttachments();
            saveDraft();
            updateComposerState(model.composerActivityProperty().get());
        });
        model.composerActivityProperty().addListener((ignored, old, value) -> updateComposerState(value));
        model.transcript().addListener((javafx.collections.ListChangeListener<TranscriptBlock>)
                change -> rebuildTranscript(true));
        model.streamProperty().addListener((ignored, old, value) -> rebuildTranscript(true));
        model.executionSummaries().addListener((javafx.collections.MapChangeListener<
                        String, com.javaclaw.sdk.model.TurnExecutionSummaryInfo>)
                change -> transcriptList.refresh());
        composer.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER && !event.isShiftDown()) {
                event.consume();
                if (!sendButton.isDisabled()) {
                    send();
                }
            } else if (event.getCode() == KeyCode.UP && composer.getText().isEmpty()) {
                String previous = drafts.lastSubmitted(activeDraftKey);
                if (!previous.isEmpty()) {
                    event.consume();
                    composer.setText(previous);
                    composer.positionCaret(previous.length());
                }
            } else if (event.getCode() == KeyCode.ESCAPE) {
                event.consume();
                escape();
            }
        });
        composer.setOnDragOver(this::attachmentDragOver);
        composer.setOnDragDropped(this::attachmentDropped);
        transcriptList.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (event.getDeltaY() > 0) {
                updateNewMessagesButton(transcriptFollow.userScrolledUp());
            }
            Platform.runLater(this::rememberTranscriptPosition);
        });
        root.addEventFilter(KeyEvent.KEY_PRESSED, this::shortcut);
        root.widthProperty().addListener((ignored, old, value) -> updateResponsiveLayout(value.doubleValue()));
        Platform.runLater(() -> {
            updateResponsiveLayout(root.getWidth());
            installTranscriptScrollTracking();
        });
        updateComposerState(model.composerActivityProperty().get());
        rebuildTranscript(false);
        updateBatchCount();
        configureThemes();
        model.initialize();
        model.loadAppearance();
    }

    private void configureThemes() {
        ToggleGroup group = new ToggleGroup();
        for (DesktopTheme.Choice theme : DesktopTheme.choices()) {
            RadioMenuItem item = new RadioMenuItem(theme.name());
            item.setToggleGroup(group);
            item.setUserData(theme.id());
            item.setSelected(theme.id().equals(model.themeProperty().get()));
            item.setOnAction(ignored -> model.setTheme(theme.id()));
            themeMenu.getItems().add(item);
        }
        DesktopTheme.apply(root, model.themeProperty().get());
        model.themeProperty().addListener((ignored, old, value) -> {
            DesktopTheme.apply(root, value);
            group.getToggles().forEach(toggle -> toggle.setSelected(value.equals(toggle.getUserData())));
            if (managementWindow != null) {
                DesktopTheme.apply(managementWindow.getScene().getRoot(), value);
            }
        });
    }

    @FXML
    private void toggleSidebar() {
        sidebarOpen = !sidebarOpen;
        if (sidebarOpen && shellMode == ShellMode.COMPACT) {
            progressOpen = false;
        }
        updateResponsiveLayout(root.getWidth());
    }

    @FXML
    private void toggleProgress() {
        progressOpen = !progressOpen;
        if (progressOpen && shellMode == ShellMode.COMPACT) {
            sidebarOpen = false;
        }
        updateResponsiveLayout(root.getWidth());
    }

    @FXML
    private void closeDrawers() {
        boolean changed = false;
        if (shellMode == ShellMode.COMPACT && sidebarOpen) {
            sidebarOpen = false;
            changed = true;
        }
        if (shellMode != ShellMode.WIDE && progressOpen) {
            progressOpen = false;
            changed = true;
        }
        if (changed) {
            updateResponsiveLayout(root.getWidth());
            restoreDrawerFocus();
        }
    }

    @FXML
    private void showShortcutHelp() {
        ManagementForms.showText(
                root,
                "快捷键",
                "新建对话\n  ⌘/Ctrl + N\n\n"
                        + "打开设置\n  ⌘/Ctrl + ,\n\n"
                        + "显示或隐藏会话侧栏\n  ⌘/Ctrl + \\\\\n\n"
                        + "聚焦消息输入框\n  ⌘/Ctrl + K\n\n"
                        + "打开 MCP 连接\n  ⌘/Ctrl + M\n\n"
                        + "快捷键帮助\n  ⌘/Ctrl + /\n\n"
                        + "发送 / 换行 / 停止\n  Enter / Shift+Enter / Esc");
    }

    @FXML
    private void send() {
        String text = composer.getText();
        ConversationDraftStore.Key submittedKey = activeDraftKey;
        ConversationDraftStore.Draft submitted = currentDraft();
        ComposerActivity activity = model.composerActivityProperty().get();
        if (activity.acceptsSteering()) {
            model.steer(text, accepted -> {
                if (accepted && drafts.acceptedText(submittedKey, text) && activeDraftKey.equals(submittedKey)) {
                    restoreDraft();
                    toast.success("追加指令已提交");
                }
            });
            return;
        }
        if (activity.phase() != ComposerActivity.Phase.IDLE) {
            return;
        }
        model.submitMessage(text, submitted.attachments(), profileBox.getValue(), ignored -> {
            if (drafts.accepted(submittedKey, submitted) && activeDraftKey.equals(submittedKey)) {
                restoreDraft();
                toast.success("任务已接受");
            }
        });
    }

    @FXML
    private void interrupt() {
        model.interrupt();
    }

    @FXML
    private void newThread() {
        model.createThread("新对话");
        composer.requestFocus();
    }

    @FXML
    private void compactThread() {
        if (model.selectedThreadProperty().get() == null) {
            return;
        }
        if (model.management()
                .dialogs()
                .confirm(
                        root,
                        "压缩会话上下文",
                        "沿用最近一次会话的模型与预算",
                        "使用当前模型压缩会话，会产生模型调用费用。原始消息和执行记录不会删除，压缩过程中不执行工具；可随时取消。",
                        "开始压缩")) {
            model.compactThread();
        }
    }

    @FXML
    private void newWorkspace() {
        model.management()
                .dialogs()
                .chooseDirectory(root, "选择工作区目录")
                .ifPresent(directory -> model.management()
                        .dialogs()
                        .promptText(
                                root,
                                "新建工作区",
                                directory.toString(),
                                directory.getFileName().toString())
                        .filter(value -> !value.isBlank())
                        .ifPresent(value -> model.createWorkspace(value, directory)));
    }

    @FXML
    private void renameCurrentWorkspace() {
        WorkspaceInfo workspace = workspaceBox.getValue();
        if (workspace == null) {
            return;
        }
        model.management()
                .dialogs()
                .promptText(root, "重命名工作区", "只修改 JavaClaw 中的展示名称", workspace.name())
                .filter(value -> !value.isBlank())
                .ifPresent(value -> model.renameWorkspace(workspace, value));
    }

    @FXML
    private void deleteCurrentWorkspace() {
        WorkspaceInfo workspace = workspaceBox.getValue();
        if (workspace == null) {
            return;
        }
        if (model.management()
                .dialogs()
                .confirm(root, "删除工作区登记", "项目目录和文件不会删除", "仅删除 JavaClaw 中的工作区登记。存在关联 Thread 时服务端会拒绝，并说明原因。", "删除登记")) {
            model.deleteWorkspace(workspace);
        }
    }

    @FXML
    private void toggleThreadManagement() {
        managingThreads = !managingThreads;
        if (!managingThreads) {
            selectedThreadIds.clear();
        }
        threadManageButton.setText(managingThreads ? "完成" : "选择管理");
        threadBatchBar.setVisible(managingThreads);
        updateBatchCount();
        threadList.refresh();
    }

    @FXML
    private void selectAllThreads() {
        if (selectedThreadIds.size() == model.threads().size()) {
            selectedThreadIds.clear();
        } else {
            selectedThreadIds.addAll(
                    model.threads().stream().map(ThreadInfo::id).toList());
        }
        updateBatchCount();
        threadList.refresh();
    }

    @FXML
    private void batchArchiveThreads() {
        runBatchArchive(true);
    }

    @FXML
    private void batchRestoreThreads() {
        runBatchArchive(false);
    }

    @FXML
    private void batchDeleteThreads() {
        List<ThreadInfo> targets = selectedThreads();
        if (targets.isEmpty()
                || !model.management()
                        .dialogs()
                        .confirm(
                                root,
                                "批量删除对话",
                                "项目文件不会删除",
                                "将逐项删除 " + targets.size() + " 个 Thread。成功项移除，失败项保留选择并显示原因。",
                                "删除所选对话")) {
            return;
        }
        model.batchDeleteThreads(targets, this::applyBatchResult);
    }

    private void runBatchArchive(boolean archived) {
        List<ThreadInfo> targets = selectedThreads();
        if (!targets.isEmpty()) {
            model.batchSetArchived(targets, archived, this::applyBatchResult);
        }
    }

    private List<ThreadInfo> selectedThreads() {
        return model.threads().stream()
                .filter(value -> selectedThreadIds.contains(value.id()))
                .toList();
    }

    private void applyBatchResult(DesktopViewModel.BatchOperationResult result) {
        result.succeeded().forEach(value -> selectedThreadIds.remove(value.id()));
        selectedThreadIds.retainAll(
                result.failed().keySet().stream().map(ThreadInfo::id).toList());
        updateBatchCount();
        threadList.refresh();
        if (!result.failed().isEmpty()) {
            String detail = result.failed().entrySet().stream()
                    .map(value -> value.getKey().title() + "：" + value.getValue())
                    .collect(java.util.stream.Collectors.joining("\n"));
            ManagementForms.showText(root, "部分操作未完成", detail);
            toast.error("部分操作未完成；失败项已保留选择");
        } else if (!result.succeeded().isEmpty()) {
            toast.success("已处理 " + result.succeeded().size() + " 个对话");
        }
    }

    @FXML
    private void attach() {
        List<Path> selected = model.management()
                .dialogs()
                .chooseOpenFiles(
                        root,
                        "添加附件",
                        List.of(new DesktopDialogGateway.FileType(
                                "支持的附件",
                                List.of(
                                        "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp", "*.pdf", "*.txt", "*.md",
                                        "*.csv", "*.json", "*.xml", "*.html", "*.docx"))));
        addAttachments(selected);
    }

    @FXML
    private void cancelUpload() {
        model.cancelSubmission();
    }

    @FXML
    private void removeAllAttachments() {
        pendingAttachments.clear();
        composer.requestFocus();
    }

    @FXML
    private void openProviders() {
        openManagement("Settings");
    }

    @FXML
    private void openProfiles() {
        openManagement("Profiles");
    }

    @FXML
    private void openMemory() {
        openManagement("Memory");
    }

    @FXML
    private void openKnowledge() {
        openManagement("Knowledge");
    }

    @FXML
    private void openSkills() {
        openManagement("Skills");
    }

    @FXML
    private void openAutomation() {
        openManagement("Automation");
    }

    @FXML
    private void openSchedules() {
        openManagement("Schedules");
    }

    @FXML
    private void openPlugins() {
        openManagement("Plugins");
    }

    @FXML
    private void openMcp() {
        openManagement("MCP");
    }

    @FXML
    private void openSites() {
        openManagement("Sites");
    }

    @FXML
    private void openInstructions() {
        openManagement("Instructions");
    }

    @FXML
    private void openWorktrees() {
        openManagement("Worktrees");
    }

    @FXML
    private void renameCurrentThread() {
        ThreadInfo thread = model.selectedThread();
        if (thread == null) {
            return;
        }
        model.management()
                .dialogs()
                .promptText(root, "重命名对话", "输入新的会话标题", thread.title())
                .filter(value -> !value.isBlank())
                .ifPresent(value -> model.renameThread(thread, value));
    }

    @FXML
    private void forkCurrentThread() {
        model.forkThread(model.selectedThread());
    }

    @FXML
    private void archiveCurrentThread() {
        ThreadInfo thread = model.selectedThread();
        if (thread == null) {
            return;
        }
        boolean archived = "ARCHIVED".equalsIgnoreCase(thread.status());
        model.setThreadArchived(thread, !archived);
    }

    @FXML
    private void deleteCurrentThread() {
        ThreadInfo thread = model.selectedThread();
        if (thread == null) {
            return;
        }
        if (model.management()
                .dialogs()
                .confirm(root, "删除对话", "此操作不会删除工作区文件", "删除当前 Thread 及其持久记录。正在运行的 Turn 会先被中断。", "删除对话")) {
            model.deleteThread(thread);
        }
    }

    private void openManagement(String section) {
        try {
            if (managementWindow == null) {
                var loader = new javafx.fxml.FXMLLoader(MainController.class.getResource("/fxml/management.fxml"));
                loader.setControllerFactory(type -> {
                    if (type == ManagementController.class) {
                        return new ManagementController(model);
                    }
                    throw new IllegalArgumentException("unsupported management controller");
                });
                javafx.scene.Parent page = loader.load();
                managementController = loader.getController();
                DesktopTheme.apply(page, model.themeProperty().get());
                managementWindow = new Stage();
                managementWindow.initOwner(root.getScene().getWindow());
                managementWindow.setScene(new Scene(page));
                managementWindow.setOnHiding(ignored -> rememberManagementSize());
                managementWindow.setOnCloseRequest(event -> {
                    event.consume();
                    managementController.requestClose();
                });
                managementWindow.getScene().addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
                    if (event.getCode() == javafx.scene.input.KeyCode.S && event.isShortcutDown()) {
                        event.consume();
                        managementController.requestSave();
                    }
                });
            }
            rememberManagementSize();
            managementController.show(section, spec -> {
                currentManagementSection = section;
                managementWindow.setTitle("JavaClaw · " + spec.title());
                managementWindow.setMinWidth(spec.minimumWidth());
                managementWindow.setMinHeight(spec.minimumHeight());
                Dimension2D size = managementSizes.getOrDefault(
                        section, new Dimension2D(spec.preferredWidth(), spec.preferredHeight()));
                managementWindow.setWidth(Math.max(spec.minimumWidth(), size.getWidth()));
                managementWindow.setHeight(Math.max(spec.minimumHeight(), size.getHeight()));
                managementWindow.show();
                managementWindow.toFront();
            });
        } catch (java.io.IOException failure) {
            Alert dialog = new Alert(Alert.AlertType.ERROR, "页面加载失败：" + failure.getMessage());
            styleDialog(dialog);
            dialog.showAndWait();
        }
    }

    private void rememberManagementSize() {
        if (managementWindow == null || currentManagementSection == null) {
            return;
        }
        if (managementWindow.getWidth() > 0 && managementWindow.getHeight() > 0) {
            managementSizes.put(
                    currentManagementSection,
                    new Dimension2D(managementWindow.getWidth(), managementWindow.getHeight()));
        }
    }

    private void styleDialog(Dialog<?> dialog) {
        dialog.initOwner(root.getScene().getWindow());
        DesktopTheme.apply(dialog.getDialogPane(), model.themeProperty().get());
    }

    private static void hideWhenEmpty(Label label) {
        label.visibleProperty().bind(label.textProperty().isNotEmpty());
        label.managedProperty().bind(label.visibleProperty());
    }

    private ConversationDraftStore.Key draftKey() {
        WorkspaceInfo workspace = model.selectedWorkspace();
        ThreadInfo thread = model.selectedThread();
        return new ConversationDraftStore.Key(
                workspace == null ? "" : workspace.id(), thread == null ? "" : thread.id());
    }

    private ConversationDraftStore.Draft currentDraft() {
        ProfileInfo profile = profileBox.getValue();
        return new ConversationDraftStore.Draft(
                composer.getText(), List.copyOf(pendingAttachments), profile == null ? "" : profile.id());
    }

    private void saveDraft() {
        if (!restoringDraft && !activeDraftKey.workspaceId().isBlank()) {
            drafts.save(activeDraftKey, currentDraft());
        }
    }

    private void switchDraftContext() {
        rememberTranscriptPosition();
        saveDraft();
        activeDraftKey = draftKey();
        restoreDraft();
        restoreTranscriptPosition();
    }

    private void restoreDraft() {
        ConversationDraftStore.Draft draft = drafts.read(activeDraftKey);
        restoringDraft = true;
        try {
            composer.setText(draft.text());
            pendingAttachments.setAll(draft.attachments());
            restoreProfile(draft.profileId());
        } finally {
            restoringDraft = false;
        }
        renderAttachments();
        updateComposerState(model.composerActivityProperty().get());
    }

    private void restoreProfile(String profileId) {
        if (model.profiles().isEmpty()) {
            return;
        }
        ProfileInfo selected = model.profiles().stream()
                .filter(value -> !profileId.isBlank() && profileId.equals(value.id()))
                .findFirst()
                .orElseGet(() -> model.profiles().stream()
                        .filter(value -> "profile_chat".equals(value.id()))
                        .findFirst()
                        .orElse(model.profiles().getFirst()));
        boolean previous = restoringDraft;
        restoringDraft = true;
        try {
            profileBox.setValue(selected);
        } finally {
            restoringDraft = previous;
        }
    }

    private void addAttachments(List<Path> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        ArrayList<String> rejected = new ArrayList<>();
        for (Path file : files) {
            String problem = attachmentProblem(file);
            if (problem != null) {
                rejected.add(problem);
                continue;
            }
            Path normalized = file.toAbsolutePath().normalize();
            if (!pendingAttachments.contains(normalized)) {
                pendingAttachments.add(normalized);
            }
        }
        composerStatus.setText(rejected.isEmpty() ? "" : String.join("；", rejected));
        attachmentPreview.setVisible(
                !pendingAttachments.isEmpty() || !composerStatus.getText().isBlank());
        composer.requestFocus();
    }

    private static String attachmentProblem(Path file) {
        if (file == null || !java.nio.file.Files.isRegularFile(file)) {
            return "无法读取附件";
        }
        String name = file.getFileName().toString();
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        boolean supported = List.of(
                        ".png", ".jpg", ".jpeg", ".gif", ".webp", ".pdf", ".txt", ".md", ".csv", ".json", ".xml",
                        ".html", ".docx")
                .stream()
                .anyMatch(lower::endsWith);
        if (!supported) {
            return name + " 类型不受支持";
        }
        try {
            if (java.nio.file.Files.size(file) > AttachmentClient.MAX_BYTES) {
                return name + " 超过 256 MiB";
            }
        } catch (java.io.IOException failure) {
            return name + " 无法读取";
        }
        return null;
    }

    private void renderAttachments() {
        attachmentChips.getChildren().clear();
        for (Path file : pendingAttachments) {
            Label icon = new Label("文件");
            icon.getStyleClass().add("attachment-icon");
            Label name = new Label(file.getFileName().toString());
            name.getStyleClass().add("attachment-name");
            Tooltip.install(name, new Tooltip(file.toString()));
            Button remove = new Button("×");
            remove.getStyleClass().add("attachment-remove-btn");
            remove.setAccessibleText("移除附件 " + file.getFileName());
            remove.setOnAction(ignored -> pendingAttachments.remove(file));
            remove.setDisable(!model.composerActivityProperty().get().allowsAttachments());
            VBox details = new VBox(3, icon, name);
            HBox chip = new HBox(6, details, remove);
            chip.setAlignment(Pos.TOP_RIGHT);
            chip.getStyleClass().add("attachment-item");
            attachmentChips.getChildren().add(chip);
        }
    }

    private void attachmentDragOver(DragEvent event) {
        if (model.composerActivityProperty().get().allowsAttachments()
                && event.getDragboard().hasFiles()
                && event.getGestureSource() != composer) {
            event.acceptTransferModes(TransferMode.COPY);
        }
        event.consume();
    }

    private void attachmentDropped(DragEvent event) {
        boolean accepted = model.composerActivityProperty().get().allowsAttachments()
                && event.getDragboard().hasFiles();
        if (accepted) {
            addAttachments(event.getDragboard().getFiles().stream()
                    .map(java.io.File::toPath)
                    .toList());
        }
        event.setDropCompleted(accepted);
        event.consume();
    }

    private void updateComposerState(ComposerActivity activity) {
        ComposerActivity state = activity == null ? ComposerActivity.idle() : activity;
        boolean hasText = !composer.getText().isBlank();
        boolean hasDraft = hasText || !pendingAttachments.isEmpty();
        switch (state.phase()) {
            case IDLE -> {
                sendButton.setText("发送 ↑");
                sendButton.setAccessibleText("发送消息");
                sendButton.setDisable(!hasDraft);
                attachButton.setDisable(false);
                attachButton.setTooltip(null);
                cancelUploadButton.setVisible(false);
                interruptButton.setVisible(false);
                composerStatus.setText("");
            }
            case SUBMITTING -> {
                sendButton.setText("上传并提交中…");
                sendButton.setAccessibleText("消息正在上传并提交");
                sendButton.setDisable(true);
                attachButton.setDisable(true);
                cancelUploadButton.setVisible(!pendingAttachments.isEmpty());
                cancelUploadButton.setDisable(false);
                interruptButton.setVisible(false);
                composerStatus.setText("正在上传并提交 " + pendingAttachments.size() + " 个附件；服务端接受前草稿不会清除。");
            }
            case CANCELLING_SUBMISSION -> {
                sendButton.setText("正在取消…");
                sendButton.setDisable(true);
                attachButton.setDisable(true);
                cancelUploadButton.setVisible(true);
                cancelUploadButton.setDisable(true);
                interruptButton.setVisible(false);
                composerStatus.setText("正在结束上传并释放未引用附件…");
            }
            case ACTIVE -> {
                sendButton.setText("追加指令 ↑");
                sendButton.setAccessibleText("向活动任务追加文本指令");
                sendButton.setDisable(!hasText);
                attachButton.setDisable(true);
                attachButton.setTooltip(new Tooltip("活动 Turn 只接受文本追加；附件已保留在草稿中。"));
                cancelUploadButton.setVisible(false);
                interruptButton.setVisible(true);
                interruptButton.setDisable(false);
                composerStatus.setText(pendingAttachments.isEmpty() ? "" : "附件已保留；当前 Turn 结束后才能发送。 ");
            }
            case STEERING -> {
                sendButton.setText("正在追加…");
                sendButton.setDisable(true);
                attachButton.setDisable(true);
                cancelUploadButton.setVisible(false);
                interruptButton.setVisible(true);
                interruptButton.setDisable(false);
                composerStatus.setText("正在向活动 Turn 追加指令…");
            }
            case WAITING_INTERACTION -> {
                sendButton.setText("请先完成上方请求");
                sendButton.setAccessibleText("请在对话中的审批或输入卡片完成操作");
                sendButton.setDisable(true);
                attachButton.setDisable(true);
                cancelUploadButton.setVisible(false);
                interruptButton.setVisible(true);
                interruptButton.setDisable(false);
                composerStatus.setText("草稿已保存；请通过 transcript 内联卡片回答当前请求。 ");
            }
            case INTERRUPTING -> {
                sendButton.setText("正在停止…");
                sendButton.setDisable(true);
                attachButton.setDisable(true);
                cancelUploadButton.setVisible(false);
                interruptButton.setVisible(true);
                interruptButton.setDisable(true);
                composerStatus.setText("等待服务端确认中断；未发送草稿不会清除。 ");
            }
        }
        cancelUploadButton.setManaged(cancelUploadButton.isVisible());
        interruptButton.setManaged(interruptButton.isVisible());
        attachmentPreview.setVisible(
                !pendingAttachments.isEmpty() || !composerStatus.getText().isBlank());
        boolean attachmentsEditable = state.allowsAttachments();
        removeAttachmentButton.setDisable(!attachmentsEditable || pendingAttachments.isEmpty());
        attachmentChips.getChildren().stream()
                .filter(HBox.class::isInstance)
                .map(HBox.class::cast)
                .flatMap(chip -> chip.getChildren().stream())
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .forEach(button -> button.setDisable(!attachmentsEditable));
    }

    private void rebuildTranscript(boolean incoming) {
        int persistentSize = model.transcript().size();
        String stream = model.streamProperty().get();
        visibleTranscript.setAll(model.transcript());
        if (!stream.isBlank()) {
            ComposerActivity activity = model.composerActivityProperty().get();
            visibleTranscript.add(new TranscriptBlock(
                    "streaming:" + activeDraftKey.threadId(),
                    TranscriptBlock.Category.AGENT_MESSAGE,
                    "agentMessageDelta",
                    "JavaClaw · 正在回复",
                    stream,
                    List.of(),
                    activity.hasActiveTurn() ? activity.turnId() : "",
                    "IN_PROGRESS",
                    null,
                    null));
        }
        TranscriptFollowState.Update follow =
                transcriptFollow.contentChanged(persistentSize, !stream.isBlank(), incoming);
        updateNewMessagesButton(follow);
        if (follow.following() && !visibleTranscript.isEmpty()) {
            Platform.runLater(() -> transcriptList.scrollTo(visibleTranscript.size() - 1));
        }
    }

    @FXML
    private void followNewMessages() {
        updateNewMessagesButton(transcriptFollow.followLatest());
        if (!visibleTranscript.isEmpty()) {
            transcriptList.scrollTo(visibleTranscript.size() - 1);
        }
    }

    private void updateNewMessagesButton(TranscriptFollowState.Update state) {
        newMessagesButton.setText(state.unread() > 0 ? "↓ " + state.unread() + " 条新消息" : "↓ 最新消息");
        newMessagesButton.setVisible(!state.following() && state.unread() > 0);
    }

    private void installTranscriptScrollTracking() {
        Node value = transcriptList.lookup(".scroll-bar:vertical");
        if (value instanceof ScrollBar bar) {
            bar.valueProperty().addListener((ignored, old, current) -> {
                if (current.doubleValue() >= bar.getMax() - 0.001) {
                    updateNewMessagesButton(
                            transcriptFollow.viewportChanged(old.doubleValue(), current.doubleValue(), bar.getMax()));
                } else if (current.doubleValue() < old.doubleValue()) {
                    updateNewMessagesButton(
                            transcriptFollow.viewportChanged(old.doubleValue(), current.doubleValue(), bar.getMax()));
                }
                rememberTranscriptPosition();
            });
        }
    }

    private void rememberTranscriptPosition() {
        if (activeDraftKey.threadId().isBlank()) {
            return;
        }
        int first = transcriptList.lookupAll(".list-cell").stream()
                .filter(ListCell.class::isInstance)
                .map(ListCell.class::cast)
                .filter(value -> !value.isEmpty() && value.isVisible())
                .mapToInt(ListCell::getIndex)
                .min()
                .orElse(Math.max(0, visibleTranscript.size() - 1));
        transcriptPositions.put(activeDraftKey, first);
    }

    private void restoreTranscriptPosition() {
        Integer position = transcriptPositions.get(activeDraftKey);
        if (position != null) {
            Platform.runLater(() ->
                    transcriptList.scrollTo(Math.min(position, Math.max(0, visibleTranscript.size() - 1))));
        } else if (!visibleTranscript.isEmpty()) {
            Platform.runLater(() -> transcriptList.scrollTo(visibleTranscript.size() - 1));
        }
    }

    private void updateBatchCount() {
        threadBatchCount.setText(selectedThreadIds.size() + " 项");
    }

    private static String threadDateGroup(ThreadInfo thread) {
        if (thread == null || thread.updatedAt() == null) {
            return "更早";
        }
        LocalDate date = thread.updatedAt().atZone(ZoneId.systemDefault()).toLocalDate();
        LocalDate today = LocalDate.now();
        if (!date.isBefore(today)) {
            return "今天";
        }
        if (!date.isBefore(today.minusDays(7))) {
            return "本周";
        }
        return "更早";
    }

    private void renderProgress(DesktopProgressSnapshot snapshot) {
        DesktopProgressSnapshot value = snapshot == null ? DesktopProgressSnapshot.empty() : snapshot;
        progressPhase.setText(value.phase());
        progressStatus.setText(value.status());
        progressPanel.setAccessibleText("处理进度：" + value.phase() + "，" + value.status());
        progressEntries.setAll(value.entries());
        DesktopProgressSnapshot.TokenUsage usage = value.usage();
        inputTokens.setText(token(usage.available(), usage.inputTokens()));
        outputTokens.setText(token(usage.available(), usage.outputTokens()));
        reasoningTokens.setText(token(usage.available(), usage.reasoningTokens()));
    }

    private static String token(boolean available, long value) {
        return available ? String.format(java.util.Locale.ROOT, "%,d", value) : "—";
    }

    private ListCell<DesktopProgressSnapshot.Entry> progressCell() {
        return new ListCell<>() {
            private final Label title = new Label();
            private final Label state = new Label();
            private final Label detail = new Label();
            private final Region spacer = new Region();
            private final HBox heading = new HBox(6, title, spacer, state);
            private final VBox card = new VBox(3, heading, detail);

            {
                heading.setAlignment(Pos.CENTER_LEFT);
                HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
                title.getStyleClass().add("progress-entry-title");
                state.getStyleClass().add("progress-entry-state");
                detail.getStyleClass().add("progress-entry-detail");
                detail.setWrapText(true);
                card.getStyleClass().add("progress-entry");
                card.prefWidthProperty().bind(widthProperty().subtract(24));
                detail.maxWidthProperty().bind(card.widthProperty().subtract(20));
            }

            @Override
            protected void updateItem(DesktopProgressSnapshot.Entry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setAccessibleText(null);
                    return;
                }
                title.setText(icon(item.kind()) + "  " + item.title());
                state.setText(item.state());
                detail.setText(item.detail());
                detail.setVisible(!item.detail().isBlank());
                detail.setManaged(detail.isVisible());
                setAccessibleText(item.title() + "，" + item.state() + "，" + item.detail());
                setGraphic(card);
            }
        };
    }

    private static String icon(DesktopProgressSnapshot.Kind kind) {
        return switch (kind) {
            case TOOL -> "⌘";
            case FILE -> "▤";
            case MCP -> "⌁";
            case SUBTASK -> "◇";
            case PLAN -> "☷";
            case ARTIFACT -> "▣";
            case INTERACTION -> "?";
            case ERROR -> "!";
            case OTHER -> "•";
        };
    }

    private void shortcut(KeyEvent event) {
        if (event.getCode() == KeyCode.ESCAPE) {
            escape();
            event.consume();
            return;
        }
        if (event.getCode() == KeyCode.TAB && drawerScrim.isVisible()) {
            trapDrawerFocus(event);
            return;
        }
        if (!event.isShortcutDown()) {
            return;
        }
        switch (shortcutAction(event.getCode())) {
            case NEW_THREAD -> newThread();
            case OPEN_SETTINGS -> openProviders();
            case TOGGLE_SIDEBAR -> toggleSidebar();
            case FOCUS_COMPOSER -> composer.requestFocus();
            case OPEN_MCP -> openMcp();
            case SHOW_HELP -> showShortcutHelp();
            case NONE -> {
                return;
            }
        }
        event.consume();
    }

    private void escape() {
        if (drawerScrim.isVisible()) {
            closeDrawers();
        } else if (model.composerActivityProperty().get().canInterrupt()) {
            interrupt();
        }
    }

    static ShortcutAction shortcutAction(KeyCode code) {
        return switch (code) {
            case N -> ShortcutAction.NEW_THREAD;
            case COMMA -> ShortcutAction.OPEN_SETTINGS;
            case BACK_SLASH -> ShortcutAction.TOGGLE_SIDEBAR;
            case K -> ShortcutAction.FOCUS_COMPOSER;
            case M -> ShortcutAction.OPEN_MCP;
            case SLASH -> ShortcutAction.SHOW_HELP;
            default -> ShortcutAction.NONE;
        };
    }

    private void updateResponsiveLayout(double width) {
        boolean drawerWasOpen = drawerScrim.isVisible();
        ShellMode next = responsiveMode(width);
        if (next != shellMode) {
            shellMode = next;
            sidebarOpen = next != ShellMode.COMPACT;
            progressOpen = next == ShellMode.WIDE;
        }
        placeSidebar(next == ShellMode.COMPACT);
        placeProgress(next != ShellMode.WIDE);
        configureCompactHeader(next == ShellMode.COMPACT);
        boolean sidebarDrawer = next == ShellMode.COMPACT && sidebarOpen;
        boolean progressDrawer = next != ShellMode.WIDE && progressOpen;
        boolean drawerOpen = sidebarDrawer || progressDrawer;
        drawerScrim.setVisible(drawerOpen);
        drawerScrim.setManaged(drawerOpen);
        if (drawerOpen) {
            drawerScrim.toFront();
            if (sidebarDrawer) {
                sidebar.toFront();
            }
            if (progressDrawer) {
                progressPanel.toFront();
            }
        }
        if (!drawerWasOpen && drawerOpen) {
            focusBeforeDrawer = root.getScene() == null ? null : root.getScene().getFocusOwner();
            Platform.runLater(() ->
                    firstFocusable(sidebarDrawer ? sidebar : progressPanel).requestFocus());
        } else if (drawerWasOpen && !drawerOpen) {
            restoreDrawerFocus();
        }
    }

    private void configureCompactHeader(boolean compact) {
        threadMeta.setVisible(!compact);
        threadMeta.setManaged(!compact);
        threadHeaderText.setMinWidth(compact ? 80 : Region.USE_COMPUTED_SIZE);
        threadHeaderText.setMaxWidth(compact ? 160 : Double.MAX_VALUE);
        themeMenu.setText(compact ? "◐" : "◐  主题");
    }

    private void configureComposerCardFocus() {
        for (Node control : List.of(composer, attachButton, profileBox, interruptButton, sendButton)) {
            control.focusedProperty().addListener((ignored, old, value) -> updateComposerCardFocus());
        }
        profileBox.showingProperty().addListener((ignored, old, value) -> updateComposerCardFocus());
        updateComposerCardFocus();
    }

    private void updateComposerCardFocus() {
        boolean focusWithin = composer.isFocused()
                || attachButton.isFocused()
                || profileBox.isFocused()
                || profileBox.isShowing()
                || interruptButton.isFocused()
                || sendButton.isFocused();
        composerCard.pseudoClassStateChanged(COMPOSER_FOCUS_WITHIN, focusWithin);
    }

    static ShellMode responsiveMode(double width) {
        if (width >= 1180) {
            return ShellMode.WIDE;
        }
        if (width >= 960) {
            return ShellMode.STANDARD;
        }
        return ShellMode.COMPACT;
    }

    private void placeSidebar(boolean overlay) {
        detach(sidebar);
        sidebar.setVisible(sidebarOpen);
        sidebar.setManaged(sidebarOpen);
        sidebar.getStyleClass().remove("desktop-drawer");
        if (!sidebarOpen) {
            return;
        }
        if (overlay) {
            sidebar.getStyleClass().add("desktop-drawer");
            shellStack.getChildren().add(sidebar);
            StackPane.setAlignment(sidebar, Pos.CENTER_LEFT);
            sidebar.toFront();
        } else {
            root.setLeft(sidebar);
        }
    }

    private void placeProgress(boolean overlay) {
        detach(progressPanel);
        progressPanel.setVisible(progressOpen);
        progressPanel.setManaged(progressOpen);
        progressPanel.getStyleClass().remove("desktop-drawer");
        if (!progressOpen) {
            return;
        }
        if (overlay) {
            progressPanel.getStyleClass().add("desktop-drawer");
            shellStack.getChildren().add(progressPanel);
            StackPane.setAlignment(progressPanel, Pos.CENTER_RIGHT);
            progressPanel.toFront();
        } else {
            root.setRight(progressPanel);
        }
    }

    private void detach(javafx.scene.Node node) {
        if (root.getLeft() == node) {
            root.setLeft(null);
        }
        if (root.getRight() == node) {
            root.setRight(null);
        }
        shellStack.getChildren().remove(node);
    }

    private void trapDrawerFocus(KeyEvent event) {
        Parent drawer = shellMode == ShellMode.COMPACT && sidebarOpen ? sidebar : progressPanel;
        List<Node> focusable = focusableNodes(drawer);
        if (focusable.isEmpty()) {
            event.consume();
            return;
        }
        Node owner = root.getScene().getFocusOwner();
        int current = focusable.indexOf(owner);
        int next = event.isShiftDown()
                ? (current <= 0 ? focusable.size() - 1 : current - 1)
                : (current < 0 || current == focusable.size() - 1 ? 0 : current + 1);
        focusable.get(next).requestFocus();
        event.consume();
    }

    private static Node firstFocusable(Parent root) {
        return focusableNodes(root).stream().findFirst().orElse(root);
    }

    private static List<Node> focusableNodes(Parent root) {
        ArrayList<Node> result = new ArrayList<>();
        collectFocusable(root, result);
        return result;
    }

    private static void collectFocusable(Parent parent, List<Node> result) {
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child.isVisible() && child.isManaged() && !child.isDisabled() && child.isFocusTraversable()) {
                result.add(child);
            }
            if (child instanceof Parent nested && child.isVisible() && child.isManaged()) {
                collectFocusable(nested, result);
            }
        }
    }

    private void restoreDrawerFocus() {
        Node target = focusBeforeDrawer;
        focusBeforeDrawer = null;
        if (target != null && target.getScene() != null && target.isVisible() && !target.isDisabled()) {
            Platform.runLater(target::requestFocus);
        } else {
            Platform.runLater(composer::requestFocus);
        }
    }

    private ListCell<TranscriptBlock> transcriptCell() {
        return new ListCell<>() {
            private final Label role = new Label();
            private final Label metadata = new Label();
            private final Region headerSpacer = new Region();
            private final HBox header = new HBox(8, role, headerSpacer, metadata);
            private final MarkdownView content = new MarkdownView();
            private final Button copy = new Button("复制");
            private final Button quote = new Button("引用");
            private final Button export = new Button("导出");
            private final Button branch = new Button("从此处建分支");
            private final Button related = new Button("关联执行");
            private final Button adopt = new Button("采用并执行此计划…");
            private final Button inspect = new Button("查看内容与附件…");
            private final HBox actions = new HBox(6, copy, quote, export, branch, related, adopt, inspect);
            private final VBox interaction = new VBox(8);
            private final VBox bubble = new VBox(6, header, content, interaction, actions);
            private String renderedId = "";

            {
                role.getStyleClass().add("message-role");
                metadata.getStyleClass().add("message-metadata");
                HBox.setHgrow(headerSpacer, javafx.scene.layout.Priority.ALWAYS);
                header.setAlignment(Pos.CENTER_LEFT);
                content.setMaxWidth(Double.MAX_VALUE);
                bubble.setMaxWidth(920);
                bubble.prefWidthProperty().bind(widthProperty().subtract(36));
                interaction.getStyleClass().add("inline-interaction-actions");
                actions.getStyleClass().add("message-actions");
                List.of(copy, quote, export, branch, related).forEach(UiActionKind.GHOST::apply);
                UiActionKind.SECONDARY.apply(adopt);
                UiActionKind.GHOST.apply(inspect);
                copy.setAccessibleText("复制消息正文");
                quote.setAccessibleText("引用消息到输入框");
                export.setAccessibleText("导出消息正文");
                branch.setAccessibleText("从此消息所属 Turn 创建新分支");
                related.setAccessibleText("查看此消息关联的执行记录");
                adopt.setId("transcript-adopt-plan");
                inspect.setId("transcript-inspect-item");
                adopt.setAccessibleText("采用计划并创建执行任务");
                inspect.setAccessibleText("查看内容、附件或执行详情");
                setMinHeight(Region.USE_PREF_SIZE);
                setPrefHeight(Region.USE_COMPUTED_SIZE);
                setMaxHeight(Region.USE_PREF_SIZE);
            }

            @Override
            protected void updateItem(TranscriptBlock item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    content.clear();
                    interaction.getChildren().clear();
                    copy.setOnAction(null);
                    quote.setOnAction(null);
                    export.setOnAction(null);
                    branch.setOnAction(null);
                    related.setOnAction(null);
                    adopt.setOnAction(null);
                    inspect.setOnAction(null);
                    setGraphic(null);
                    return;
                }
                role.setText(item.title());
                metadata.setText(messageMetadata(item));
                content.show(
                        transcriptPreview(item),
                        item.category() == TranscriptBlock.Category.AGENT_MESSAGE
                                || item.category() == TranscriptBlock.Category.ARTIFACT,
                        uri -> ArtifactViewer.openLink(root, model.management(), uri));
                boolean message = item.category() == TranscriptBlock.Category.USER_MESSAGE
                        || item.category() == TranscriptBlock.Category.AGENT_MESSAGE;
                configureAction(copy, message, ignored -> copyText(item.text()));
                configureAction(quote, message, ignored -> quoteToComposer(item.text()));
                configureAction(
                        export,
                        item.category() == TranscriptBlock.Category.AGENT_MESSAGE
                                || item.category() == TranscriptBlock.Category.ARTIFACT
                                || item.category() == TranscriptBlock.Category.PLAN,
                        ignored -> exportTranscript(item));
                configureAction(
                        branch,
                        message && !item.turnId().isBlank() && model.selectedThread() != null,
                        ignored -> retryFromMessage(item));
                branch.setText(item.category() == TranscriptBlock.Category.USER_MESSAGE ? "编辑后重试…" : "重新生成到新分支");
                configureAction(
                        related,
                        item.category() == TranscriptBlock.Category.AGENT_MESSAGE
                                && !item.turnId().isBlank(),
                        ignored -> model.readTurnItems(
                                item.turnId(), value -> ManagementForms.showText(root, "关联执行", value)));
                boolean inspectable = item.category() == TranscriptBlock.Category.EXECUTION
                        || item.category() == TranscriptBlock.Category.ARTIFACT
                        || item.category() == TranscriptBlock.Category.ERROR;
                inspect.setVisible(inspectable);
                inspect.setManaged(inspectable);
                inspect.setOnAction(ignored -> {
                    if (item.itemIds().size() == 1) {
                        model.readItem(
                                item.itemIds().getFirst(),
                                value -> ArtifactViewer.show(root, model.management(), value));
                    } else {
                        model.readItems(item.itemIds(), value -> ManagementForms.showText(root, "执行过程", value));
                    }
                });
                adopt.setVisible(item.category() == TranscriptBlock.Category.PLAN);
                adopt.setManaged(adopt.isVisible());
                adopt.setOnAction(ignored -> model.readPlan(item.id(), plan -> {
                    var options = model.profiles().stream()
                            .filter(profile -> "CHAT".equals(profile.kind()))
                            .toList();
                    var profile = ManagementForms.choices(
                            options, ProfileInfo::name, options.isEmpty() ? null : options.getFirst());
                    var decisions = ManagementForms.area("", 5);
                    var form = ManagementForms.form(
                            ManagementForms.hint("采用将创建新的执行 Turn，旧 PLAN 仍保持只读。不是因为自然语言“继续”而自动执行。"),
                            ManagementForms.hint("目标：" + plan.goal() + "\n风险：" + String.join("、", plan.risks())),
                            ManagementForms.field("执行 Profile", profile),
                            ManagementForms.field("待决策回答：" + String.join("；", plan.openQuestions()), decisions));
                    ManagementForms.edit(root, "显式采用计划", form, () -> decisions.getText(), value -> {
                        if (profile.getValue() != null
                                && ManagementForms.confirm(
                                        root, model.management(), "确认开始执行", "授权在该 Profile 范围内执行所选计划；工具仍需正常审批。")) {
                            model.adoptPlan(item.id(), profile.getValue(), value);
                        }
                    });
                }));
                renderInlineInteraction(item, interaction);
                actions.setVisible(actions.getChildren().stream().anyMatch(Node::isVisible));
                actions.setManaged(actions.isVisible());
                bubble.getStyleClass().setAll("message-bubble", bubbleStyle(item.category()));
                bubble.setMaxWidth(item.category() == TranscriptBlock.Category.USER_MESSAGE ? 720 : 820);
                setAlignment(
                        item.category() == TranscriptBlock.Category.USER_MESSAGE ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
                setGraphic(bubble);
                bubble.requestLayout();
                if (!renderedId.equals(item.id())) {
                    renderedId = item.id();
                    UiMotion.fadeIn(bubble);
                }
            }
        };
    }

    private static void configureAction(
            Button button, boolean visible, javafx.event.EventHandler<javafx.event.ActionEvent> action) {
        button.setVisible(visible);
        button.setManaged(visible);
        button.setOnAction(visible ? action : null);
    }

    private void renderInlineInteraction(TranscriptBlock item, VBox target) {
        target.getChildren().clear();
        target.setVisible(item.category() == TranscriptBlock.Category.INTERACTION);
        target.setManaged(target.isVisible());
        if (!target.isVisible()) {
            return;
        }
        if (!item.pendingInteraction()) {
            Label resolved = new Label("此请求已处理；服务端持久状态是恢复后的权威结果。");
            resolved.getStyleClass().add("chat-top-meta");
            target.getChildren().add(resolved);
            return;
        }
        if (item.source() instanceof ApprovalItemContent approval) {
            renderApproval(approval, target);
        } else if (item.source() instanceof UserInputItemContent input) {
            renderUserInput(input, target);
        }
    }

    private void renderApproval(ApprovalItemContent approval, VBox target) {
        Label risk = new Label("风险级别：" + DesktopPresentationMapper.status(approval.risk()));
        risk.getStyleClass().add("inline-interaction-risk");
        Label status = new Label();
        status.getStyleClass().add("chat-top-meta");
        Button approve = new Button("允许此次操作");
        Button reject = new Button("拒绝");
        UiActionKind.PRIMARY.apply(approve);
        UiActionKind.DANGER.apply(reject);
        approve.setAccessibleText("批准当前权限请求一次");
        reject.setAccessibleText("拒绝当前权限请求");
        HBox buttons = new HBox(8, approve, reject, status);
        buttons.setAlignment(Pos.CENTER_LEFT);
        boolean submitting = model.interactionSubmitting(approval.approvalId());
        setInteractionSubmitting(buttons, status, submitting);
        approve.setOnAction(ignored -> submitApproval(approval.approvalId(), true, buttons, status));
        reject.setOnAction(ignored -> submitApproval(approval.approvalId(), false, buttons, status));
        target.getChildren().addAll(risk, buttons);
    }

    private void submitApproval(String id, boolean approved, HBox controls, Label status) {
        setInteractionSubmitting(controls, status, true);
        model.respondToApproval(id, approved, accepted -> {
            if (accepted) {
                status.setText("已提交，等待服务端确认…");
            } else {
                setInteractionSubmitting(controls, status, false);
            }
        });
    }

    private void renderUserInput(UserInputItemContent request, VBox target) {
        Label status = new Label();
        status.getStyleClass().add("chat-top-meta");
        Button submit = new Button("提交回答");
        Button cancel = new Button("取消请求");
        UiActionKind.PRIMARY.apply(submit);
        UiActionKind.GHOST.apply(cancel);
        Node answer;
        java.util.function.Supplier<String> value;
        if (request.choices().isEmpty()) {
            TextField field = new TextField();
            field.setPromptText("输入回答");
            field.setAccessibleText("回答：" + request.prompt());
            field.setMaxWidth(560);
            answer = field;
            value = field::getText;
            submit.setDisable(true);
            field.textProperty().addListener((ignored, old, current) -> submit.setDisable(current.isEmpty()));
        } else {
            ComboBox<String> choices = new ComboBox<>(FXCollections.observableArrayList(request.choices()));
            choices.setPromptText("选择一个回答");
            choices.setAccessibleText("选择回答：" + request.prompt());
            choices.setMaxWidth(560);
            answer = choices;
            value = choices::getValue;
            submit.setDisable(true);
            choices.valueProperty().addListener((ignored, old, current) -> submit.setDisable(current == null));
        }
        HBox buttons = new HBox(8, submit, cancel, status);
        buttons.setAlignment(Pos.CENTER_LEFT);
        boolean submitting = model.interactionSubmitting(request.requestId());
        answer.setDisable(submitting);
        cancel.setDisable(submitting);
        if (submitting) {
            status.setText("正在提交…");
        }
        submit.setOnAction(
                ignored -> submitUserInput(request.requestId(), value.get(), false, answer, buttons, status));
        cancel.setOnAction(ignored -> submitUserInput(request.requestId(), "", true, answer, buttons, status));
        target.getChildren().addAll(answer, buttons);
    }

    private void submitUserInput(String id, String value, boolean cancelled, Node answer, HBox controls, Label status) {
        answer.setDisable(true);
        setInteractionSubmitting(controls, status, true);
        model.respondToUserInput(id, value, cancelled, accepted -> {
            if (accepted) {
                status.setText("已提交，等待服务端确认…");
            } else {
                answer.setDisable(false);
                setInteractionSubmitting(controls, status, false);
            }
        });
    }

    private static void setInteractionSubmitting(HBox controls, Label status, boolean submitting) {
        controls.getChildren().stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .forEach(button -> button.setDisable(submitting));
        status.setText(submitting ? "正在提交…" : "");
    }

    private String messageMetadata(TranscriptBlock item) {
        String time = DesktopPresentationMapper.instant(item.createdAt(), "");
        String state = DesktopPresentationMapper.status(item.state());
        ArrayList<String> parts = new ArrayList<>();
        if (!time.isBlank()) {
            parts.add(time);
        }
        if (!state.isBlank()) {
            parts.add(state);
        }
        var execution = model.executionSummary(item.turnId());
        if (execution != null) {
            if (!execution.profileId().isBlank()) {
                String profile = model.profiles().stream()
                        .filter(value -> execution.profileId().equals(value.id()))
                        .map(ProfileInfo::name)
                        .findFirst()
                        .orElse(execution.profileId());
                parts.add(profile);
            }
            if (!execution.provider().isBlank() || !execution.model().isBlank()) {
                parts.add(execution.provider() + " / " + execution.model());
            }
        }
        return String.join(" · ", parts);
    }

    private void retryFromMessage(TranscriptBlock item) {
        ProfileInfo profile = profileBox.getValue();
        if (profile == null) {
            return;
        }
        if (item.category() == TranscriptBlock.Category.USER_MESSAGE) {
            String initial = item.source() instanceof com.javaclaw.sdk.model.UserMessageItemContent message
                    ? message.text()
                    : item.text();
            model.management()
                    .dialogs()
                    .promptText(root, "编辑后在新分支重试", "源 Thread 不会修改；原附件引用、Profile、审批和沙箱会重新校验。", initial)
                    .ifPresent(value -> model.retryInNewBranch(item.turnId(), value, profile));
            return;
        }
        if (model.management()
                .dialogs()
                .confirm(
                        root,
                        "重新生成到新分支",
                        "源 Thread 和现有回复保持不变",
                        "将复用该 Turn 的输入并按当前 Profile 重新校验权限、附件和模型配置。此操作会启动一次新的模型调用。",
                        "创建分支并重试")) {
            model.retryInNewBranch(item.turnId(), null, profile);
        }
    }

    private void copyText(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text == null ? "" : text);
        Clipboard.getSystemClipboard().setContent(content);
        toast.info("已复制到剪贴板");
    }

    private void quoteToComposer(String text) {
        String quoted = Objects.toString(text, "")
                .lines()
                .map(value -> "> " + value)
                .collect(java.util.stream.Collectors.joining("\n"));
        String prefix = composer.getText().isBlank() ? "" : composer.getText() + "\n\n";
        composer.setText(prefix + quoted + "\n\n");
        composer.positionCaret(composer.getText().length());
        composer.requestFocus();
    }

    private void exportTranscript(TranscriptBlock item) {
        String suggested =
                item.category() == TranscriptBlock.Category.ARTIFACT ? "javaclaw-result.md" : "javaclaw-message.md";
        model.management()
                .dialogs()
                .chooseSaveFile(
                        root,
                        "导出正文",
                        suggested,
                        List.of(
                                new DesktopDialogGateway.FileType("Markdown", List.of("*.md")),
                                new DesktopDialogGateway.FileType("文本", List.of("*.txt"))))
                .ifPresent(path -> model.exportText(path, item.text(), () -> {
                    composerStatus.setText("已导出到 " + path.getFileName());
                    toast.success("正文已导出");
                }));
    }

    private ListCell<ThreadInfo> threadCell() {
        return new ListCell<>() {
            private final CheckBox selected = new CheckBox();
            private final Region indicator = new Region();
            private final Label title = new Label();
            private final Label time = new Label();
            private final Label group = new Label();
            private final Label groupCount = new Label();
            private final Region groupSpacer = new Region();
            private final HBox row = new HBox(8, selected, indicator, title, time);
            private final HBox groupRow = new HBox(6, group, groupSpacer, groupCount);
            private final VBox container = new VBox(groupRow, row);
            private final MenuItem rename = new MenuItem("重命名…");
            private final MenuItem fork = new MenuItem("创建分支");
            private final MenuItem archive = new MenuItem();
            private final MenuItem delete = new MenuItem("删除对话");
            private final ContextMenu menu = new ContextMenu(rename, fork, archive, delete);

            {
                row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                row.setMaxWidth(Double.MAX_VALUE);
                row.getStyleClass().add("sidebar-conv-row");
                indicator.getStyleClass().add("sidebar-conv-indicator");
                title.getStyleClass().add("sidebar-conv-title");
                time.getStyleClass().add("sidebar-conv-time");
                groupRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                groupRow.setMaxWidth(Double.MAX_VALUE);
                groupRow.getStyleClass().add("sidebar-group-header");
                group.getStyleClass().add("sidebar-group-header-text");
                groupCount.getStyleClass().add("sidebar-group-count");
                container.setMaxWidth(Double.MAX_VALUE);
                title.setMinWidth(0);
                title.setMaxWidth(Double.MAX_VALUE);
                title.setWrapText(false);
                title.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
                time.setMinWidth(Region.USE_PREF_SIZE);
                HBox.setHgrow(title, javafx.scene.layout.Priority.ALWAYS);
                HBox.setHgrow(groupSpacer, javafx.scene.layout.Priority.ALWAYS);
                setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
                delete.getStyleClass().add("menu-item-danger");
                selected.setAccessibleText("选择此对话用于批量操作");
                selectedProperty().addListener((ignored, old, value) -> updateSelectedStyle());
                selected.setOnAction(ignored -> {
                    ThreadInfo value = getItem();
                    if (value == null) {
                        return;
                    }
                    if (selected.isSelected()) {
                        selectedThreadIds.add(value.id());
                    } else {
                        selectedThreadIds.remove(value.id());
                    }
                    updateBatchCount();
                });
                setOnKeyPressed(event -> {
                    ThreadInfo value = getItem();
                    if (value == null) {
                        return;
                    }
                    if (event.getCode() == KeyCode.ENTER) {
                        model.selectThread(value);
                        event.consume();
                    } else if (event.getCode() == KeyCode.CONTEXT_MENU
                            || event.getCode() == KeyCode.F10 && event.isShiftDown()) {
                        menu.show(this, javafx.geometry.Side.BOTTOM, 0, 0);
                        event.consume();
                    }
                });
            }

            @Override
            protected void updateItem(ThreadInfo item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    row.getStyleClass().remove("sidebar-conv-selected");
                    setGraphic(null);
                    setContextMenu(null);
                    setAccessibleText(null);
                    return;
                }
                String displayTitle = DesktopPresentationMapper.sidebarTitle(item.title());
                title.setText(displayTitle);
                time.setText(DesktopPresentationMapper.sidebarTime(item.updatedAt()));
                selected.setVisible(managingThreads);
                selected.setManaged(managingThreads);
                selected.setSelected(selectedThreadIds.contains(item.id()));
                String section = threadDateGroup(item);
                boolean showGroup = getIndex() == 0
                        || getIndex() > 0
                                && !section.equals(
                                        threadDateGroup(threadList.getItems().get(getIndex() - 1)));
                group.setText(section);
                groupCount.setText(Integer.toString(threadGroupCount(section)));
                groupRow.setVisible(showGroup);
                groupRow.setManaged(showGroup);
                Tooltip.install(title, new Tooltip(displayTitle));
                boolean archived = "ARCHIVED".equalsIgnoreCase(item.status());
                archive.setText(archived ? "恢复对话" : "归档对话");
                rename.setOnAction(ignored -> model.management()
                        .dialogs()
                        .promptText(root, "重命名对话", "输入新的会话标题", displayTitle)
                        .filter(value -> !value.isBlank())
                        .ifPresent(value -> model.renameThread(item, value)));
                fork.setOnAction(ignored -> model.forkThread(item));
                archive.setOnAction(ignored -> model.setThreadArchived(item, !archived));
                delete.setOnAction(ignored -> {
                    if (model.management()
                            .dialogs()
                            .confirm(root, "删除对话", "此操作不会删除工作区文件", "删除当前 Thread 及其持久记录。正在运行的 Turn 会先被中断。", "删除对话")) {
                        model.deleteThread(item);
                    }
                });
                setGraphic(container);
                setContextMenu(menu);
                setAccessibleText(displayTitle + (archived ? "，已归档" : ""));
                updateSelectedStyle();
            }

            private void updateSelectedStyle() {
                row.getStyleClass().remove("sidebar-conv-selected");
                if (!isEmpty() && getItem() != null && isSelected() && !managingThreads) {
                    row.getStyleClass().add("sidebar-conv-selected");
                }
            }
        };
    }

    private int threadGroupCount(String section) {
        return (int) threadList.getItems().stream()
                .filter(value -> section.equals(threadDateGroup(value)))
                .count();
    }

    private static String bubbleStyle(TranscriptBlock.Category category) {
        return switch (category) {
            case USER_MESSAGE -> "message-user";
            case AGENT_MESSAGE, ARTIFACT, PLAN -> "message-assistant";
            case EXECUTION -> "transcript-execution-block";
            case INTERACTION -> "transcript-interaction-block";
            case ERROR -> "transcript-error-block";
        };
    }

    private static String transcriptPreview(TranscriptBlock item) {
        String text = item.text();
        if (item.category() != TranscriptBlock.Category.EXECUTION || text.length() <= 560) {
            return text;
        }
        return text.substring(0, 559).stripTrailing() + "…\n\n使用“查看内容与附件”审查完整执行详情。";
    }

    private static ListCell<WorkspaceInfo> workspaceCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(WorkspaceInfo item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name());
            }
        };
    }

    private static ListCell<ProfileInfo> profileCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(ProfileInfo item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : profileModeLabel(item));
            }
        };
    }

    /** 输入区只展示可选运行模式；Provider 与模型仍由对应 Profile 在提交 Turn 时决定。 */
    static String profileModeLabel(ProfileInfo profile) {
        return DesktopPresentationMapper.text(profile.name(), DesktopPresentationMapper.profileKind(profile.kind()));
    }

    enum ShellMode {
        WIDE,
        STANDARD,
        COMPACT
    }

    enum ShortcutAction {
        NEW_THREAD,
        OPEN_SETTINGS,
        TOGGLE_SIDEBAR,
        FOCUS_COMPOSER,
        OPEN_MCP,
        SHOW_HELP,
        NONE
    }
}
