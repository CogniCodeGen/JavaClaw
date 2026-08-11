package com.javaclaw.chat;

import com.javaclaw.agent.AgentRuntime;
import com.javaclaw.agent.ChatService;
import com.javaclaw.agent.PlanModeService;
import com.javaclaw.runtime.ApplicationKernel;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.ui.javafx.loop.LoopStatusViewFactory;
import com.javaclaw.ui.javafx.diagnostics.DiagnosticsViewFactory;
import com.javaclaw.ui.javafx.plugin.PluginCenterViewFactory;
import javafx.fxml.FXML;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;


/** FXML chat page composition controller; feature state lives in dedicated coordinators. */
public class ChatViewController implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ChatViewController.class);

    @FXML private BorderPane outerRoot;
    @FXML private ChatSessionController sessionViewController;
    @FXML private WorkspaceSwitchOverlayController workspaceSwitchOverlayController;
    @FXML private ChatComposerController composerController;
    @FXML private ChatModeController modeBarController;
    @FXML private ChatHeaderController headerController;
    /** 应用组合根：服务重建、工作区切换和回滚的唯一生命周期入口。 */
    private final ApplicationKernel applicationKernel;
    @FXML private SidebarController sidebarController;
    @FXML private StackPane thinkingPanelHost;
    @FXML private ThinkingPanelController thinkingPanelController;
    private ThinkingPanelController thinkingPanel;

    /** 串行落盘，避免阻塞 FX 线程或产生会话文件覆盖竞争。 */
    private final TaskScope persistenceTasks;
    private final TaskScope backgroundTasks;
    private final java.util.concurrent.Executor persistExecutor;
    private final FxDispatcher fx;
    private final AssistantMessageFactory assistantMessages;
    private final ExpandableMarkdownBlockFactory expandableBlocks;
    private final ChatMessageRowFactory messageRows;
    private final LoopDecisionFactory loopDecisions;
    private final ClarificationCardFactory clarificationCards;
    private final LoopStatusViewFactory loopStatusViews;
    private final ChatInlineImageRenderer inlineImages;
    private final ChatShortcutHelpFactory shortcutHelp;
    private final ChatRuntimeCoordinator runtimeCoordinator;
    private final ChatNavigationController navigation;
    private ChatShellController shell;
    private ChatStatusController status;
    private ChatStreamRenderer streamRenderer;
    private ChatTurnController turns;
    private ChatSessionCoordinator sessionCoordinator;

    /** 兼容旧退出链；页面生命周期统一由 {@link #close()} 收口。 */
    public void shutdownPersistence() {
        close();
    }

    /**
     * 停止页面动画和订阅，并取消仍属于本页面的后台任务。关闭是幂等的；迟到的任务结果
     * 会因作用域取消或 {@code closed} 标记而被丢弃，不再触碰已经卸载的 FXML 节点。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        backgroundTasks.close();
        persistenceTasks.close();
        try {
            fx.dispatch(this::stopUiResources);
        } catch (IllegalStateException toolkitStopped) {
            log.debug("JavaFX 已停止，跳过聊天页面动画清理", toolkitStopped);
        }
    }

    private void stopUiResources() {
        if (sessionCoordinator != null) {
            sessionCoordinator.close();
            sessionCoordinator = null;
        }
        if (shell != null) {
            shell.close();
            shell = null;
        }
        if (status != null) {
            status.close();
            status = null;
        }
    }

    private final java.util.concurrent.atomic.AtomicBoolean closed =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Spring constructs the controller before FXMLLoader injects the view graph. */
    @Autowired
    public ChatViewController(
            ApplicationKernel applicationKernel,
            FxDispatcher fx,
            ManagedTaskExecutor taskExecutor,
            AssistantMessageFactory assistantMessages,
            ExpandableMarkdownBlockFactory expandableBlocks,
            ChatMessageRowFactory messageRows,
            LoopDecisionFactory loopDecisions,
            ClarificationCardFactory clarificationCards,
            LoopStatusViewFactory loopStatusViews,
            DiagnosticsViewFactory diagnosticsViews,
            PluginCenterViewFactory pluginCenterViews,
            ChatInlineImageRenderer inlineImages,
            ChatShortcutHelpFactory shortcutHelp) {
        this.applicationKernel = java.util.Objects.requireNonNull(
                applicationKernel, "applicationKernel");
        this.fx = java.util.Objects.requireNonNull(fx, "fx");
        this.assistantMessages = java.util.Objects.requireNonNull(
                assistantMessages, "assistantMessages");
        this.expandableBlocks = java.util.Objects.requireNonNull(
                expandableBlocks, "expandableBlocks");
        this.messageRows = java.util.Objects.requireNonNull(messageRows, "messageRows");
        this.loopDecisions = java.util.Objects.requireNonNull(loopDecisions, "loopDecisions");
        this.clarificationCards = java.util.Objects.requireNonNull(
                clarificationCards, "clarificationCards");
        this.loopStatusViews = java.util.Objects.requireNonNull(loopStatusViews, "loopStatusViews");
        this.inlineImages = java.util.Objects.requireNonNull(inlineImages, "inlineImages");
        this.shortcutHelp = java.util.Objects.requireNonNull(shortcutHelp, "shortcutHelp");
        java.util.Objects.requireNonNull(taskExecutor, "taskExecutor");
        persistenceTasks = taskExecutor.openScope("chat-persistence", 1);
        backgroundTasks = taskExecutor.openScope("chat-ui-background", 2);
        persistExecutor = command -> persistenceTasks.submit(
                TaskSpec.io("chat-persistence"), context -> {
                    command.run();
                    return null;
                });

        runtimeCoordinator = new ChatRuntimeCoordinator(applicationKernel, fx, backgroundTasks);
        navigation = new ChatNavigationController(
                applicationKernel,
                java.util.Objects.requireNonNull(diagnosticsViews, "diagnosticsViews"),
                java.util.Objects.requireNonNull(pluginCenterViews, "pluginCenterViews"),
                this::ownerStage,
                runtimeCoordinator::modeRegistry,
                runtimeCoordinator::rejectIfTransitioning,
                runtimeCoordinator::isTransitioning,
                runtimeCoordinator::rebuild,
                () -> headerController.refreshKnowledgeMenu());
    }

    @FXML
    private void initialize() {
        log.info("开始构建聊天界面 FXML");
        configureShell();
        configureTopBar();
        configureModeBar();
        configureThinkingPanel();
        configureComposer();
        configureSidebar();

        sessionCoordinator.load();
        log.info("聊天界面 FXML 构建完成");
    }

    private void configureSidebar() {
        sidebarController.setOnNewChat(sessionCoordinator::newSession);
        sidebarController.setOnSwitchSession(sessionCoordinator::switchSession);
        sidebarController.setOnDeleteSession(sessionCoordinator::deleteSession);
        sidebarController.setOnBatchDeleteSessions(sessionCoordinator::deleteSessions);
        sidebarController.setOnOpenSettings(() -> navigation.openSettings(null));
        sidebarController.setOnOpenSkillCenter(navigation::openSkills);
        sidebarController.setOnOpenMemoryCenter(navigation::openMemory);
        sidebarController.setOnOpenScheduler(navigation::openSchedules);
        sidebarController.setOnOpenKnowledgeBase(navigation::openKnowledge);
        sidebarController.setOnOpenTaskManager(navigation::openTasks);
        sidebarController.setOnOpenWorkflowCenter(navigation::openWorkflows);
        sidebarController.setOnOpenMcp(navigation::openMcp);
        sidebarController.setOnOpenPluginCenter(navigation::openPlugins);
        sidebarController.setOnSwitchWorkspace(runtimeCoordinator::switchWorkspace);
    }

    private void configureTopBar() {
        headerController.configure(
                shell::toggleSidebar,
                navigation::openTasks,
                this::openSettingsRequested,
                () -> sessionCoordinator.clearCurrentHistory(),
                runtimeCoordinator::knowledgeMenuSnapshot,
                runtimeCoordinator::applyKnowledgeSelection,
                navigation::openKnowledge);
        headerController.setShortcutHints(shell.shortcutHint());
        status = new ChatStatusController(
                applicationKernel,
                fx,
                headerController,
                modeBarController,
                sidebarController,
                this::currentSession);
        status.start(applicationKernel.current());
    }

    private void configureModeBar() {
        modeBarController.setOnLoopTemplateSelected(template -> {
            composerController.replaceInput(template);
            composerController.focusInput();
        });
        modeBarController.setOnOpenWorkflowCenter(navigation::openWorkflows);
        modeBarController.setOnOpenTaskManager(navigation::openTasks);
        modeBarController.setOnResetTokens(status::resetSession);
    }

    private void configureComposer() {
        composerController.setOnSend(turns::sendFromComposer);
        composerController.setOnStop(turns::stop);
        composerController.setRecallPrevious(sessionCoordinator::lastUserMessage);

    }

    private void configureThinkingPanel() {
        thinkingPanel = thinkingPanelController;
        streamRenderer = new ChatStreamRenderer(
                sessionViewController,
                composerController,
                thinkingPanel,
                assistantMessages,
                expandableBlocks,
                loopStatusViews,
                inlineImages,
                this::currentModelDisplayName);
        sessionCoordinator = new ChatSessionCoordinator(
                persistExecutor,
                sessionViewController,
                composerController,
                thinkingPanel,
                sidebarController,
                messageRows,
                inlineImages,
                clarificationCards,
                streamRenderer,
                status,
                runtimeCoordinator::chatService,
                runtimeCoordinator::planModeService,
                this::currentModelDisplayName,
                this::ownerStage,
                runtimeCoordinator::rejectIfTransitioning);
        turns = new ChatTurnController(
                fx,
                backgroundTasks,
                composerController,
                modeBarController,
                thinkingPanel,
                status,
                streamRenderer,
                navigation,
                runtimeCoordinator::runtime,
                runtimeCoordinator::modeRegistry,
                runtimeCoordinator::isTransitioning,
                sessionCoordinator);
        sessionCoordinator.bindTurns(turns);
        runtimeCoordinator.bind(
                turns,
                sessionCoordinator,
                headerController,
                modeBarController,
                sidebarController,
                workspaceSwitchOverlayController,
                status,
                this::showLoopInteractionBubble);
    }

    private void configureShell() {
        shell = new ChatShellController(
                outerRoot,
                sidebarController.getRoot(),
                headerController,
                composerController,
                shortcutHelp,
                this::isStreaming,
                this::stopActiveStream,
                () -> sessionCoordinator.newSession(),
                this::openSettings,
                () -> sessionCoordinator.clearCurrentHistory(),
                navigation::openMcp);
        shell.install();
    }

    /**
     * 交互式循环检测气泡 — 让用户决定是继续还是终止
     *
     * <p>该方法在 Reactor 线程上被调用，必须切回 JavaFX 线程更新 UI。
     * 用户点击"继续"或"终止"后回调 {@code decision}，超时由 Hook 层处理。</p>
     */
    private void showLoopInteractionBubble(String toolName, int repeats,
                                           java.util.function.Consumer<Boolean> decision) {
        fx.dispatch(() -> {
            LoopDecisionView view = loopDecisions.create(toolName, repeats, decision);
            sessionViewController.addMessage(view.root());
        });
    }

    /** 获取主页根节点。 */
    public BorderPane getOuterRoot() {
        return outerRoot;
    }

    private Stage ownerStage() {
        return outerRoot == null || outerRoot.getScene() == null
                ? null : (Stage) outerRoot.getScene().getWindow();
    }

    private ChatSession currentSession() {
        return sessionCoordinator == null ? null : sessionCoordinator.currentSession();
    }

    private boolean isStreaming() {
        return turns != null && turns.isStreaming();
    }

    private void openSettingsRequested() {
        openSettings();
    }

    /**
     * 工作流中心发布成功后的即时 UI 更新。取消在途旧查询，避免旧快照覆盖刚发布的定义。
     */
    public void onWorkflowPublished(String workflowId, String workflowName) {
        fx.dispatch(() -> modeBarController.workflowPublished(workflowId, workflowName));
    }

    /**
     * 取当前模型的精简显示名（用于消息气泡头部徽章）。
     */
    private String currentModelDisplayName() {
        try {
            String m = status.modelName();
            if (m == null || m.isBlank()) return "模型";
            // 截取常见前缀后更紧凑的名字
            String[] parts = m.split("[-/]");
            if (parts.length >= 2) {
                return parts[parts.length - 2] + " " + parts[parts.length - 1];
            }
            return m;
        } catch (Throwable t) {
            return "模型";
        }
    }


    private void stopActiveStream() {
        turns.stop();
    }


    /**
     * 打开设置对话框（供顶栏按钮与系统托盘菜单复用）
     */
    public void openSettings() {
        navigation.openSettings(null);
    }

    /**
     * 打开设置对话框并可直达指定分类（如「嵌入模型」）；category 为 null 时打开默认分类。
     */
    public void openSettings(String category) {
        navigation.openSettings(category);
    }


    /** 获取共享基础设施容器（供外部使用） */
    public AgentRuntime getRuntime() {
        return runtimeCoordinator.runtime();
    }

    /** 获取普通模式服务 */
    public ChatService getChatService() {
        return runtimeCoordinator.chatService();
    }

    /** 获取规划模式服务 */
    public PlanModeService getPlanModeService() {
        return runtimeCoordinator.planModeService();
    }

}
