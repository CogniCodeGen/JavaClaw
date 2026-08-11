package com.javaclaw.chat;

import com.javaclaw.app.UIHelper;
import com.javaclaw.agent.AgentRuntime;
import com.javaclaw.agent.ChatService;
import com.javaclaw.agent.PlanModeService;
import com.javaclaw.api.conversation.CancellationReason;
import com.javaclaw.api.conversation.ModeRegistry;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.runtime.ApplicationKernel;
import com.javaclaw.runtime.WorkspaceRuntime;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.ui.javafx.loop.LoopStatusViewFactory;
import com.javaclaw.ui.javafx.diagnostics.DiagnosticsViewFactory;
import com.javaclaw.ui.javafx.plugin.PluginCenterViewFactory;
import com.javaclaw.ui.javafx.knowledge.KnowledgeMenuSnapshot;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 聊天界面控制器（支持流式输出 + 思考模式 + 规划 + 多智能体 + 多媒体输入）
 *
 * <p>在原有流式聊天基础上，新增以下功能：
 * <ul>
 *   <li>规划展示：显示编排智能体创建的执行规划和子任务状态</li>
 *   <li>工具调用展示：显示子智能体的执行结果</li>
 *   <li>状态指示：显示当前是在思考、规划、委派还是回复</li>
 *   <li>多媒体输入：支持添加图片和文档附件</li>
 *   <li>RichTextFX：输入和气泡使用 InlineCssTextArea，原生支持文本选中复制</li>
 * </ul>
 * </p>
 *
 * @author JavaClaw
 */
public class ChatViewController implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ChatViewController.class);

    @FXML private BorderPane outerRoot;
    @FXML private ChatSessionController sessionViewController;
    @FXML private WorkspaceSwitchOverlayController workspaceSwitchOverlayController;
    @FXML private ChatComposerController composerController;
    @FXML private ChatModeController modeBarController;
    @FXML private ChatHeaderController headerController;
    /** 共享基础设施容器（模型工厂 / 记忆 / 知识 / token 追踪等） */
    private AgentRuntime runtime;
    /** 普通聊天模式服务入口 */
    private ChatService chatService;
    /** 规划模式服务入口 */
    private PlanModeService planModeService;
    /** 模式注册表（对话模式 / 动作模式 统一管理，支持运行期扩展） */
    private ModeRegistry modeRegistry;
    /** 应用组合根：服务重建、工作区切换和回滚的唯一生命周期入口。 */
    private final ApplicationKernel applicationKernel;
    @FXML private SidebarController sidebarController;
    @FXML private StackPane thinkingPanelHost;
    @FXML private ThinkingPanelController thinkingPanelController;
    private ThinkingPanelController thinkingPanel;

    /**
     * 聊天历史 JSON 持久化的串行执行器（单线程 + 守护线程）。
     * Why: 之前 saveCurrentSession 在 JavaFX Application Thread 同步落盘 2 个 JSON 文件，
     * 阻塞用户消息气泡的首帧渲染（用户感受到「回车后卡顿一秒才显示」）。
     * 单线程足以保证写入顺序、避免文件覆盖竞争。
     */
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

    // 浏览器已改为独立窗口，不再使用 browserVisible 标志

    // ==================== 流式输出的活动 UI 引用 ====================

    private final java.util.concurrent.atomic.AtomicBoolean closed =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 重建进行中再次触发重建时置位：收尾后补一轮，保证「后到的配置」不被静默丢弃。 */
    private final java.util.concurrent.atomic.AtomicBoolean rebuildQueued =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 服务重建进行中标志：重建改为后台线程异步执行后，UI 事件可能落在
     * shutdown→重建→采用新 WorkspaceRuntime 的窗口内。两重职责：
     * ① stopActiveStream 在置位期间直接忽略——否则会对正在关闭的服务调 cancelStream
     * 并提前 setInputEnabled(true) 解锁输入，用户此刻发消息会打到半重建的服务上；
     * ② rebuildAgentService 入口 CAS 抢占——setInputEnabled 锁不住设置对话框的保存按钮，
     * 连续两次保存会起两个并发重建线程互相 shutdown 对方刚建好的服务。
     */
    private final java.util.concurrent.atomic.AtomicBoolean rebuildInProgress =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Spring constructs the controller before FXMLLoader injects the static view graph.
     */
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

        WorkspaceRuntime initialRuntime = applicationKernel.current();
        runtime = initialRuntime.agentRuntime();
        chatService = initialRuntime.chatService();
        planModeService = initialRuntime.planModeService();
        modeRegistry = initialRuntime.modeRegistry();
        navigation = new ChatNavigationController(
                applicationKernel,
                java.util.Objects.requireNonNull(diagnosticsViews, "diagnosticsViews"),
                java.util.Objects.requireNonNull(pluginCenterViews, "pluginCenterViews"),
                this::ownerStage,
                () -> modeRegistry,
                this::rejectIfRebuilding,
                rebuildInProgress::get,
                this::rebuildAgentService,
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
        showFirstUseGuidanceIfNeeded();
        chatService.setLoopInteractiveHandler(this::showLoopInteractionBubble);
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
        sidebarController.setOnSwitchWorkspace(this::onSwitchWorkspace);
    }

    private void configureTopBar() {
        headerController.configure(
                shell::toggleSidebar,
                navigation::openTasks,
                this::openSettingsRequested,
                () -> sessionCoordinator.clearCurrentHistory(),
                this::knowledgeMenuSnapshot,
                this::applyKnowledgeSelection,
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
                () -> chatService,
                () -> planModeService,
                this::currentModelDisplayName,
                this::ownerStage,
                this::rejectIfRebuilding);
        turns = new ChatTurnController(
                fx,
                backgroundTasks,
                composerController,
                modeBarController,
                thinkingPanel,
                status,
                streamRenderer,
                navigation,
                () -> runtime,
                () -> modeRegistry,
                rebuildInProgress::get,
                sessionCoordinator);
        sessionCoordinator.bindTurns(turns);
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

    /**
     * 获取最外层根节点（用于 Scene 构建）
     */
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

    private ChatSession streamingSession() {
        return turns == null ? null : turns.streamingSession();
    }

    private void openSettingsRequested() {
        openSettings();
    }

    // ==================== 消息发送 ====================

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

    /** 构建顶栏知识库菜单所需的稳定快照，避免 Controller 解析展示文案。 */
    private KnowledgeMenuSnapshot knowledgeMenuSnapshot() {
        var expert = runtime.getKnowledgeExpert();
        if (!expert.isRagEnabled()) {
            return KnowledgeMenuSnapshot.ragDisabled();
        }
        var globalDocs = expert.getDocumentNames(com.javaclaw.agent.expert.KnowledgeExpert.Scope.GLOBAL);
        var workspaceDocs = expert.getDocumentNames(com.javaclaw.agent.expert.KnowledgeExpert.Scope.WORKSPACE);
        List<KnowledgeMenuSnapshot.Document> global = globalDocs.stream()
                .map(name -> new KnowledgeMenuSnapshot.Document(
                        name, expert.getDocumentChunkCount(name)))
                .toList();
        List<KnowledgeMenuSnapshot.Document> workspace = workspaceDocs.stream()
                .map(name -> new KnowledgeMenuSnapshot.Document(
                        name, expert.getDocumentChunkCount(name)))
                .toList();
        return new KnowledgeMenuSnapshot(
                true, global, workspace, expert.getEnabledDocs());
    }

    /** 将明确的文档 ID 集合持久化到当前工作区知识库。 */
    private void applyKnowledgeSelection(Set<String> selectedNames) {
        var expert = runtime.getKnowledgeExpert();
        List<String> documentNames = new ArrayList<>(expert.getDocumentNames(
                com.javaclaw.agent.expert.KnowledgeExpert.Scope.GLOBAL));
        documentNames.addAll(expert.getDocumentNames(
                com.javaclaw.agent.expert.KnowledgeExpert.Scope.WORKSPACE));
        for (String documentName : documentNames) {
            expert.setDocEnabled(documentName, selectedNames.contains(documentName));
        }
    }

    private void stopActiveStream() {
        turns.stop();
    }

    private void stopActiveStream(CancellationReason reason, boolean showCancelFeedback,
                                  ChatTurnController.StopPolicy policy) {
        turns.stop(reason, showCancelFeedback, policy);
    }

    /**
     * 查找当前会话中最近一条用户消息内容（用于 ↑ 键回填编辑）
     */

    private void setInputEnabled(boolean enabled) {
        turns.setInputEnabled(enabled);
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

    /**
     * 重建三条路径服务（模型配置 / 知识库配置变更后立即生效）。
     *
     * <p>流程：关闭旧的 ChatService + PlanModeService + AgentRuntime，
     * 再按顺序新建 runtime → chatService → planModeService；并把新的
     * ChatService 交给 ScheduleManager，把新 runtime 的模型工厂和能力工具
     * 交给 TaskManager。重建期间任何异常都会尝试再执行一次新建链路以恢复服务。</p>
     *
     * <p>重建在后台线程执行（与切工作区同范式）：停循环的 cancelAndAwait 最长等 5 秒、
     * runtime 重建本身也是重活，放 FX 线程会整段冻结界面。期间禁用输入防止消息发到
     * 半重建的服务上，收尾回 FX 线程恢复。</p>
     */
    private void rebuildAgentService() {
        // 0. 先停活跃流/循环并使旧回调失效（stopActiveStream 内部递增代次）：必须在抢占
        // 重建旗标之前——stopActiveStream 对重建期间的停止请求会忽略。不先停流的话，
        // 后台线程 shutdown 掐断的流/循环终态回调仍持有效代次，会在重建窗口内
        // setInputEnabled(true) 提前解锁输入、并对已关闭的 chatService 保存/加载会话
        if (isStreaming()) {
            stopActiveStream(CancellationReason.RUNTIME_REBUILD, false,
                    ChatTurnController.StopPolicy.DISCARD_AND_INVALIDATE);
        }
        // CAS 抢占：重建已在进行时忽略本次触发（如设置对话框连续两次保存），
        // 两个重建线程并发跑 shutdown→重建会互相关掉对方刚建好的服务
        if (!rebuildInProgress.compareAndSet(false, true)) {
            // 在飞重建可能已越过构造点（配置在构造时读取），刚保存的新配置不会被它拾取——
            // 排队补一轮而非静默丢弃，否则新 API Key 已持久化却永不生效且无任何提示
            rebuildQueued.set(true);
            log.info("服务重建已在进行中，本次触发已排队（收尾后自动补一轮以拾取最新配置）");
            return;
        }
        log.info("配置变更，重建三条路径服务");
        setInputEnabled(false);
        java.util.concurrent.atomic.AtomicBoolean runtimeReady =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        backgroundTasks.submit(TaskSpec.io("agent-service-rebuild"), context -> {
            try {
                adoptRuntime(applicationKernel.rebuildCurrent());
                runtimeReady.set(true);
                log.info("三条路径服务重建完成");
            } catch (Exception e) {
                log.error("重建服务失败", e);
                // Kernel 会自行重试；若恢复成功但后续订阅方激活抛错，仍采用其当前快照。
                try {
                    adoptRuntime(applicationKernel.current());
                    runtimeReady.set(true);
                } catch (IllegalStateException unavailable) {
                    log.error("服务恢复失败，当前没有可用运行时", unavailable);
                }
            } finally {
                // UI 收尾放 finally：Error（如 OOM / 类初始化失败）逃逸上方 catch 时旗标也必须
                // 复位，否则 rejectIfRebuilding 永久拦截会话操作、输入框永久禁用，应用只能重启
                fx.dispatch(() -> {
                    try {
                        // 知识库配置可能变化：直接按新 runtime 重建菜单（清空选中态后重建，
                        // 而非只清空——关知识库中心的 onHidden 重建在异步重建期间被跳过，
                        // 此处是它的唯一补偿点）
                        headerController.resetKnowledgeMenu();
                        headerController.refreshKnowledgeMenu();
                        status.bind(applicationKernel.current());
                        modeBarController.refreshModes(modeBarController.selectedModeId());
                        modeBarController.refreshWorkflows();
                    } finally {
                        rebuildInProgress.set(false);
                        setInputEnabled(runtimeReady.get());
                        if (!runtimeReady.get()) {
                            var port = com.javaclaw.agent.ToolConfirmationManager.getPort();
                            if (port != null) {
                                port.notify(new com.javaclaw.api.interaction.ToastRequest(
                                        "系统", "运行时恢复失败，请修正设置后再次保存或重启应用"));
                            }
                        }
                        // 重建期间又有配置保存被排队：补一轮拾取最新配置
                        if (rebuildQueued.getAndSet(false)) {
                            rebuildAgentService();
                        }
                    }
                });
            }
            return null;
        });
    }

    /**
     * 原子采用一个完整的工作区运行时。Factory 每次会连同 ShellMode 一起新建，避免命令模式
     * 在模型重建后继续持有已经关闭的 ChatService。
     */
    private void adoptRuntime(WorkspaceRuntime workspaceRuntime) {
        runtime = workspaceRuntime.agentRuntime();
        chatService = workspaceRuntime.chatService();
        planModeService = workspaceRuntime.planModeService();
        modeRegistry = workspaceRuntime.modeRegistry();
        chatService.setLoopInteractiveHandler(this::showLoopInteractionBubble);
    }

    // ==================== 顶栏 / 输入区控件 ====================

    // ==================== 工作区切换 ====================

    /**
     * 切换到指定工作区
     *
     * <p>工作区切换时依次执行：
     * <ol>
     *   <li>保存当前会话和 Cookie</li>
     *   <li>切换 WorkspaceManager 的当前工作区</li>
     *   <li>重新加载所有配置</li>
     *   <li>重建智能体服务</li>
     *   <li>重新加载会话和定时任务</li>
     * </ol>
     * </p>
     */
    private void onSwitchWorkspace(String targetWorkspaceId) {
        com.javaclaw.config.WorkspaceManager wsMgr = com.javaclaw.config.WorkspaceManager.getInstance();
        String fromId = wsMgr.getCurrentWorkspaceId();
        if (targetWorkspaceId.equals(fromId)) {
            return;
        }
        log.info("切换工作区: {} -> {}", fromId, targetWorkspaceId);

        // 0. 工作区切换会重建全部服务，后台流式无法跨工作区延续：先停流并清理挂起节点
        //（必须在抢占重建旗标之前：stopActiveStream 对重建期间的停止请求会忽略）
        if (isStreaming()) {
            stopActiveStream(CancellationReason.RUNTIME_REBUILD, false,
                    ChatTurnController.StopPolicy.DISCARD_AND_INVALIDATE);
        }

        // 0.5 与设置保存触发的服务重建互斥：共用 rebuildInProgress 旗标，两条线程并发跑
        // shutdown→重建会互相关掉对方刚建好的服务；持旗期间会话操作被 rejectIfRebuilding 挡住
        if (!rebuildInProgress.compareAndSet(false, true)) {
            log.warn("服务重建/工作区切换已在进行中，忽略本次切换: {}", targetWorkspaceId);
            // 下拉框选中项在回调触发前已变成目标工作区：必须回滚显示，否则 UI 声称在 B
            // 而实际仍在 A，后续聊天/记忆/知识库全落错工作区且用户无从察觉
            sidebarController.refreshWorkspaceCombo();
            var busyPort = com.javaclaw.agent.ToolConfirmationManager.getPort();
            if (busyPort != null) {
                busyPort.notify(new com.javaclaw.api.interaction.ToastRequest(
                        "系统", "服务重建中，工作区切换未执行，请稍候重试"));
            }
            return;
        }

        // 1. 保存当前状态（在旧工作区路径下，UI 线程操作）
        sessionCoordinator.saveChatHistory();

        workspaceSwitchOverlayController.show("正在切换工作区...");

        // 在后台线程执行非 UI 操作（步骤 2-7）
        backgroundTasks.submit(TaskSpec.io("workspace-switch"), context -> {
            try {
                // 生命周期、配置重载、浏览器重绑定和失败回滚统一由应用内核完成。
                adoptRuntime(applicationKernel.switchWorkspace(targetWorkspaceId));

                // UI 更新回到 JavaFX 线程（步骤 8-12）
                fx.dispatch(() -> {
                    try {
                        // TokenTracker 和会话均必须在新工作区运行时上重新绑定。
                        status.bind(applicationKernel.current());
                        sessionCoordinator.reloadWorkspace();

                        // 重置知识库菜单（清除旧工作区的文档列表和选中状态）
                        headerController.resetKnowledgeMenu();

                        // 11. 重置会话模式回对话（规划/循环同等对待：切工作区后残留循环
                        // chip 会把用户随手一问路由成最多几十轮的自动循环）
                        modeBarController.refreshModes("chat");
                        modeBarController.refreshWorkflows();

                        // 12. 更新侧边栏工作区下拉
                        sidebarController.refreshWorkspaceCombo();

                        // 12.5. 重新加载新工作区记忆的界面风格
                        headerController.reloadTheme();

                        // 12.6. 重新加载新工作区记忆的字体（族 / 等宽 / 密度）
                        com.javaclaw.ui.javafx.theme.FontManager.reload();

                        // 12.7. 刷新新工作区的工具审核模式
                        modeBarController.refreshReviewMode();

                        log.info("工作区切换完成: {} ({})",
                                wsMgr.getCurrentWorkspace().getName(), targetWorkspaceId);
                    } finally {
                        workspaceSwitchOverlayController.hide();
                    }
                });
            } catch (Exception e) {
                log.error("工作区切换异常", e);
                // Kernel 已尝试回滚；采用恢复后的运行时，并把侧边栏选择恢复为真实工作区。
                try {
                    adoptRuntime(applicationKernel.current());
                } catch (IllegalStateException unavailable) {
                    log.error("工作区切换后无可用运行时", unavailable);
                }
                fx.dispatch(() -> {
                    try {
                        status.bind(applicationKernel.current());
                    } catch (IllegalStateException unavailable) {
                        log.error("无法恢复工作区状态绑定", unavailable);
                    }
                    sidebarController.refreshWorkspaceCombo();
                    workspaceSwitchOverlayController.hide();
                    var port = com.javaclaw.agent.ToolConfirmationManager.getPort();
                    if (port != null) {
                        port.notify(new com.javaclaw.api.interaction.ToastRequest(
                                "系统", "工作区切换失败，已恢复原工作区"));
                    }
                });
            } finally {
                // 旗标复位收敛到 finally 单一出口：Error 逃逸上方 catch 时也必须复位，否则
                // 会话操作被永久拦截、停流被永久忽略。此 runLater 在成功路径的 UI 重载
                // runLater 之后入队（FIFO），不会提前放行用户操作；重复置 false 无害
                fx.dispatch(() -> {
                    rebuildInProgress.set(false);
                    // 切换期间保存过设置：补一轮重建拾取（按新工作区的配置重建，无害且必要）
                    if (rebuildQueued.getAndSet(false)) {
                        rebuildAgentService();
                    }
                });
            }
            return null;
        });
    }


    /**
     * 服务重建/工作区切换期间拒绝会话操作。
     *
     * <p>重建在后台线程跑 shutdown→重建，窗口期内 chatService/planModeService 已关闭
     * （MemoryService/EclipseStore 已 close），此时保存/加载/删除会话会静默失败或抛异常：
     * 离开会话的检查点丢失、已删会话的记忆检查点在重建后复活、侧边栏与索引不一致。
     * 旧实现在 FX 线程同步重建天然不可交错，改异步后必须显式挡住这些入口。</p>
     *
     * @param action 操作名（用于日志与提示）
     * @return true=正在重建，调用方应立即返回
     */
    private boolean rejectIfRebuilding(String action) {
        if (!rebuildInProgress.get()) {
            return false;
        }
        log.warn("服务重建进行中，忽略操作：{}", action);
        var port = com.javaclaw.agent.ToolConfirmationManager.getPort();
        if (port != null) {
            port.notify(new com.javaclaw.api.interaction.ToastRequest(
                    "系统", "服务重建中，请稍候再" + action));
        }
        return true;
    }

    /** 获取共享基础设施容器（供外部使用） */
    public AgentRuntime getRuntime() {
        return runtime;
    }

    /** 获取普通模式服务 */
    public ChatService getChatService() {
        return chatService;
    }

    /** 获取规划模式服务 */
    public PlanModeService getPlanModeService() {
        return planModeService;
    }

    /**
     * 首次使用时显示功能引导
     */
    private void showFirstUseGuidanceIfNeeded() {
        AgentConfig config = applicationKernel.current().agentConfig();
        if (config.isFirstUseGuidanceDone()) return;

        fx.dispatch(() -> {
            Alert guide = new Alert(Alert.AlertType.INFORMATION,
                    "💬 / 📋  对话 / 研讨模式\n" +
                    "顶部切换对话或研讨模式，研讨模式启用多智能体协作讨论\n\n" +
                    "📚  知识库\n" +
                    "顶部「知识库」按钮可选择导入的文档作为回答参考\n\n" +
                    "📁  会话管理\n" +
                    "左侧「管理」按钮可批量选择和删除会话\n\n" +
                    "⚙️  更多功能\n" +
                    "技能中心、定时任务、工作区隔离等功能在左侧栏中",
                    ButtonType.OK);
            guide.setTitle("欢迎使用 JavaClaw");
            guide.setHeaderText("快速了解核心功能");
            UIHelper.styleAlert(guide);
            guide.getDialogPane().setMinWidth(420);

            guide.showAndWait();

            config.setFirstUseGuidanceDone(true);
            config.save();
        });
    }

}
