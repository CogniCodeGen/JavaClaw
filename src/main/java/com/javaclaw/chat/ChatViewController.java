package com.javaclaw.chat;

import com.javaclaw.app.UIHelper;
import com.javaclaw.agent.AgentRuntime;
import com.javaclaw.agent.ChatService;
import com.javaclaw.agent.PlanModeService;
import com.javaclaw.agent.PricingTable;
import com.javaclaw.agent.TokenTracker;
import com.javaclaw.api.conversation.ActionMode;
import com.javaclaw.api.conversation.CancellationReason;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.api.conversation.ConversationHandle;
import com.javaclaw.api.conversation.ConversationMode;
import com.javaclaw.api.conversation.ConversationOutcome;
import com.javaclaw.api.conversation.ConversationRequest;
import com.javaclaw.api.conversation.Mode;
import com.javaclaw.api.conversation.ModeRegistry;
import com.javaclaw.browser.PlaywrightBrowserManager;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.config.SettingsView;
import com.javaclaw.runtime.ApplicationKernel;
import com.javaclaw.runtime.WorkspaceRuntime;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.ui.javafx.loop.LoopStatusView;
import com.javaclaw.ui.javafx.loop.LoopStatusViewFactory;
import com.javaclaw.ui.javafx.diagnostics.DiagnosticsViewFactory;
import com.javaclaw.ui.javafx.plugin.PluginCenterViewFactory;
import com.javaclaw.ui.javafx.image.ImageViewerFactory;
import com.javaclaw.ui.javafx.control.WindowToastFactory;
import com.javaclaw.ui.javafx.knowledge.KnowledgeMenuController;
import com.javaclaw.ui.javafx.knowledge.KnowledgeMenuSnapshot;
import com.javaclaw.ui.javafx.schedule.ScheduleView;
import com.javaclaw.ui.javafx.skill.SkillCenterView;
import com.javaclaw.ui.javafx.task.SddTaskView;
import com.javaclaw.ui.javafx.theme.ThemeMenuController;
import com.javaclaw.util.ProjectAccessPolicy;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
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

    /** 支持内联显示的图片扩展名 */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "bmp", "webp");

    @FXML private BorderPane outerRoot;
    @FXML private ChatSessionController sessionViewController;
    @FXML private WorkspaceSwitchOverlayController workspaceSwitchOverlayController;
    @FXML private ChatComposerController composerController;
    @FXML private ChatModeController modeBarController;
    @FXML private Label topTitleLabel;
    @FXML private Label topTitleStatusDot;
    @FXML private Label topTitleMetaLabel;
    @FXML private Label localModeBadge;
    @FXML private Label embeddingHealthBadge;
    private AutoCloseable embeddingHealthSubscription;
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
    private final PlaywrightBrowserManager browserManager;
    private ChatHistoryManager chatHistoryManager;
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
    private final DiagnosticsViewFactory diagnosticsViews;
    private final PluginCenterViewFactory pluginCenterViews;
    private final ImageViewerFactory imageViewer;
    private final WindowToastFactory windowToasts;

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
        AutoCloseable healthSubscription = embeddingHealthSubscription;
        embeddingHealthSubscription = null;
        if (healthSubscription != null) {
            try {
                healthSubscription.close();
            } catch (Exception failure) {
                log.debug("关闭嵌入健康订阅失败", failure);
            }
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
        stopTimeline(statusBarClock);
        statusBarClock = null;
    }

    private static void stopTimeline(Timeline timeline) {
        if (timeline != null) {
            timeline.stop();
        }
    }

    // ==================== 多会话管理 ====================

    /** 所有会话列表（内存索引） */
    private final List<ChatSession> sessions = new ArrayList<>();

    /** 当前活动会话 */
    private ChatSession currentSession;

    // 浏览器已改为独立窗口，不再使用 browserVisible 标志

    /** 是否处于流式生成中（用于 Esc 取消逻辑） */
    private boolean streamingActive = false;

    /** 顶栏汉堡菜单按钮（仅当侧栏隐藏时显示） */
    @FXML private Button sidebarToggleBtn;

    /** 触发响应式自动收缩的窗口宽度阈值 */
    private static final double RESPONSIVE_BREAKPOINT_PX = 960.0;

    /** 当前侧栏自动隐藏状态（避免响应式监听重复触发） */
    private boolean sidebarAutoHidden = false;

    // ==================== 流式输出的活动 UI 引用 ====================

    /** 当前流式助手消息；聚合其 FXML、嵌套 Markdown、动画和操作状态。 */
    private AssistantMessageView activeAssistantMessage;

    /** 当前正在追加结果的工具名称（用于合并同一智能体的多次结果） */
    private String activeToolName;

    /** 当前正在追加的子智能体结果 FXML 控件。 */
    private ExpandableMarkdownBlockView activeToolResultBlock;

    /** 规划模式下当前发言智能体的 FXML 控件。 */
    private ExpandableMarkdownBlockView activePlanAgentBlock;

    /** 规划模式下当前发言智能体名称（用于在切换或流结束时为右侧面板生成摘要） */
    private String currentPlanAgentName;

    /** 规划模式下当前发言智能体的回复累积文本（用于摘要截断） */
    private final StringBuilder currentPlanAgentBuffer = new StringBuilder();

    @FXML private KnowledgeMenuController knowledgeMenuController;
    @FXML private ThemeMenuController themeMenuController;
    @FXML private Button settingsButton;
    private Timeline statusBarClock;
    private final java.util.concurrent.atomic.AtomicBoolean closed =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 当前流式输出中已显示的图片路径（防止重复显示） */
    private final Set<String> displayedImagePaths = new HashSet<>();

    /** 流式输出代次计数器（会话切换/删除时递增，使旧流回调失效） */
    private volatile int streamGeneration = 0;
    /** 当前一轮的完整运行上下文；终态、取消与计量均只操作这一聚合。 */
    private volatile ActiveTurn activeTurn;

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

    /** 正在流式生成回复的会话（可能不是当前展示的会话；null = 无活跃流） */
    private ChatSession streamingSession;

    /** 流式会话被切走时挂起的消息区节点（仍被流式回调实时更新），切回时原样恢复 */
    private final List<javafx.scene.Node> suspendedStreamingNodes = new ArrayList<>();

    /**
     * 一轮会话运行的单一真相：固定 generation/session/mode，持有回复草稿、
     * 独立句柄、消息级 Token 与耗时。UI 节点仍由控制器维护，但不再用散落字段判断终态归属。
     */
    private static final class ActiveTurn {
        final int generation;
        final ChatSession session;
        final String modeId;
        final StringBuilder messageDraft = new StringBuilder();
        String finalPlanDraft;
        final long startedAtNanos = System.nanoTime();
        long inputTokens;
        long outputTokens;
        DeliveryState deliveryState = DeliveryState.COMPLETE;
        volatile ConversationHandle handle;

        ActiveTurn(int generation, ChatSession session, String modeId) {
            this.generation = generation;
            this.session = session;
            this.modeId = modeId;
        }

        TurnMetrics metrics() {
            long durationMs = Math.max(0,
                    (System.nanoTime() - startedAtNanos) / 1_000_000L);
            return new TurnMetrics(inputTokens, outputTokens, durationMs);
        }
    }

    /** 当前运行的停止语义：用户停止保留部分回复，破坏性操作立即丢弃并隔离旧回调。 */
    private enum StopPolicy {
        PRESERVE_PARTIAL,
        DISCARD_AND_INVALIDATE
    }

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
            ImageViewerFactory imageViewer,
            WindowToastFactory windowToasts) {
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
        this.diagnosticsViews = java.util.Objects.requireNonNull(
                diagnosticsViews, "diagnosticsViews");
        this.pluginCenterViews = java.util.Objects.requireNonNull(
                pluginCenterViews, "pluginCenterViews");
        this.imageViewer = java.util.Objects.requireNonNull(imageViewer, "imageViewer");
        this.windowToasts = java.util.Objects.requireNonNull(windowToasts, "windowToasts");
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
        browserManager = applicationKernel.browserManager();
        chatHistoryManager = new ChatHistoryManager();
    }

    @FXML
    private void initialize() {
        log.info("开始构建聊天界面 FXML");
        configureSidebar();
        configureTopBar();
        configureModeBar();
        configureComposer();
        configureThinkingPanel();

        loadSessions();
        installGlobalShortcuts();
        installResponsiveLayout();
        showFirstUseGuidanceIfNeeded();
        chatService.setLoopInteractiveHandler(this::showLoopInteractionBubble);
        refreshLocalModeBadge();
        log.info("聊天界面 FXML 构建完成");
    }

    private void configureSidebar() {
        sidebarController.setOnNewChat(this::onNewSession);
        sidebarController.setOnSwitchSession(this::onSwitchSession);
        sidebarController.setOnDeleteSession(this::onDeleteSession);
        sidebarController.setOnBatchDeleteSessions(this::onBatchDeleteSessions);
        sidebarController.setOnOpenSettings(this::openSettings);
        sidebarController.setOnOpenSkillCenter(this::openSkillCenter);
        sidebarController.setOnOpenMemoryCenter(this::openMemoryCenter);
        sidebarController.setOnOpenScheduler(this::openScheduler);
        sidebarController.setOnOpenKnowledgeBase(this::openKnowledgeBase);
        sidebarController.setOnOpenTaskManager(this::openTaskManager);
        sidebarController.setOnOpenWorkflowCenter(this::openWorkflowCenter);
        sidebarController.setOnOpenMcp(this::openMcpServers);
        sidebarController.setOnOpenPluginCenter(this::openPluginCenter);
        sidebarController.setOnSwitchWorkspace(this::onSwitchWorkspace);
    }

    private void configureTopBar() {
        sidebarToggleBtn.setTooltip(new Tooltip(
                "显示侧栏 (" + shortcutHint() + " + \\)"));
        settingsButton.setTooltip(new Tooltip(
                "设置（" + shortcutHint() + " + ,）"));
        wireEmbeddingHealth();

        knowledgeMenuController.configure(
                this::knowledgeMenuSnapshot,
                this::applyKnowledgeSelection,
                this::openKnowledgeBase);
        wireTokenTracker();
    }

    private void configureModeBar() {
        modeBarController.setOnLoopTemplateSelected(template -> {
            composerController.replaceInput(template);
            composerController.focusInput();
        });
        modeBarController.setOnOpenWorkflowCenter(this::openWorkflowCenter);
        modeBarController.setOnOpenTaskManager(this::openTaskManager);
        modeBarController.setOnResetTokens(() -> {
            runtime.getTokenTracker().resetSession();
            refreshStatusBar();
        });
    }

    private void configureComposer() {
        composerController.setOnSend(this::onSendMessage);
        composerController.setOnStop(this::stopActiveStream);
        composerController.setRecallPrevious(this::findLastUserMessage);

        statusBarClock = new Timeline(
                new KeyFrame(Duration.seconds(10), event -> refreshStatusBar()));
        statusBarClock.setCycleCount(Animation.INDEFINITE);
        statusBarClock.play();
    }

    private void configureThinkingPanel() {
        thinkingPanel = thinkingPanelController;
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

    @FXML
    private void openSettingsRequested() {
        openSettings();
    }

    // ==================== 消息发送 ====================

    /**
     * 发送消息事件处理（多智能体流式模式，支持附件）
     */
    private void onSendMessage() {
        String userText = composerController.trimmedInput();
        if (userText.isEmpty() && !composerController.hasAttachments()) {
            return;
        }

        // /任务 命令：打开任务创建对话框
        if (userText.startsWith("/任务")) {
            String taskDesc = userText.substring(3).trim();
            composerController.clearInput();
            openTaskCreation(taskDesc);
            return;
        }

        // /demo 命令：播放离线演示，无需 API Key
        if (userText.equals("/demo") || userText.equals("/演示")) {
            composerController.clearInput();
            addUserBubbleWithAttachments(userText, List.of());
            setInputEnabled(false);
            streamingSession = currentSession;
            showThinkingIndicator(true);
            activeTurn = new ActiveTurn(streamGeneration, streamingSession, "chat");
            thinkingPanel.startNewStream();
            createStreamingBubble();
            runDemoPlayback(streamGeneration);
            return;
        }

        // /诊断 斜杠命令：打开诊断面板
        if (userText.equals("/诊断") || userText.equals("/diagnostics")) {
            composerController.clearInput();
            Stage owner = (Stage) outerRoot.getScene().getWindow();
            diagnosticsViews.open(owner);
            return;
        }

        // /plan、/规划 或 /研讨 斜杠命令：单条消息强制走研讨模式（不切换全局状态）
        boolean forcePlanMode = false;
        if (userText.startsWith("/plan ") || userText.startsWith("/规划 ") || userText.startsWith("/研讨 ")) {
            int prefixLen = userText.startsWith("/plan ") ? 6 : 4;
            userText = userText.substring(prefixLen).trim();
            forcePlanMode = true;
            if (userText.isEmpty()) {
                addStaticBubble(ChatMessage.Role.SYSTEM, "用法：/研讨 <问题> — 触发研讨模式多智能体讨论");
                return;
            }
        }

        log.info("用户发送消息: {}，附件数: {}", userText,
                composerController.attachmentCount());

        // 复制附件列表用于发送（发送后清空预览）
        List<File> attachmentsToSend = composerController.attachmentSnapshot();

        // 附件能力闸门：目标模式声明不支持附件（如循环模式）时诚实拒发——
        // 否则附件在用户气泡里看似已送达，模式却静默丢弃，模型对着看不见的内容空转
        if (!attachmentsToSend.isEmpty()) {
            String attachTargetId = conversationTargetId(forcePlanMode);
            boolean supportsAttachments = modeRegistry.getById(attachTargetId)
                    .map(m -> m.capabilities().supportsAttachments())
                    .orElse(true);
            if (!supportsAttachments) {
                addStaticBubble(ChatMessage.Role.SYSTEM,
                        "当前模式不支持附件：请移除附件后再发送，或切回对话模式处理附件内容");
                composerController.showInputError();
                return;
            }
        }

        // 立即同步执行的轻量 UI 反馈：用户气泡入场景图、清输入框、禁用输入、思考指示器。
        // 这些 setter 不会自己触发渲染，仍要等当前事件处理器返回后下一次脉冲才能上屏。
        addUserBubbleWithAttachments(userText, attachmentsToSend);
        composerController.clearInput();
        composerController.clearAttachments();
        setInputEnabled(false);
        streamingSession = currentSession;  // 记录流所属会话（支持切走后后台继续）
        showThinkingIndicator(true);
        final int gen = streamGeneration;
        final String targetModeId = conversationTargetId(forcePlanMode);
        ActiveTurn turn = new ActiveTurn(gen, streamingSession, targetModeId);
        activeTurn = turn;
        thinkingPanel.startNewStream();

        // 重活推迟到下一帧 —— saveChatHistory（智能体状态落盘）+ createStreamingBubble
        // 若同步执行会让用户气泡的首帧推迟。
        // FxDispatcher 让上面的 UI 改动先完成布局/重绘，然后下一脉冲再做这些重活。
        // 捕获当前代次，所有回调中检查代次是否匹配，会话切换/删除后旧回调自动失效。
        final String requestText = userText;
        // 会话切换在后台流式期间保持可用；请求归属必须在排队前固定为发起流的会话，
        // 不能在 runLater 中读取可能已经切换的 currentSession。
        final String requestSessionId = streamingSession == null ? null : streamingSession.getId();
        fx.dispatch(() -> {
            if (streamGeneration != gen) return; // 间隙内会话被切换/取消

            saveChatHistory();
            createStreamingBubble();

            // 按当前选中模式（或 /plan 强制）从注册表取出对应的 ConversationMode。
            String targetId = targetModeId;
            Mode mode = modeRegistry.getById(targetId).orElse(null);
            if (!(mode instanceof ConversationMode convMode)) {
                log.error("模式 [{}] 未注册或不是对话模式", targetId);
                onStreamError(new IllegalStateException("模式未注册: " + targetId));
                return;
            }
            var profile = "plan".equals(targetId)
                    ? modeBarController.planProfile()
                    : com.javaclaw.api.conversation.PlanProfile.AUTO;
            try {
                ConversationHandle handle = convMode.start(
                        new ConversationRequest(requestText, attachmentsToSend, requestSessionId,
                                new com.javaclaw.api.conversation.ConversationOptions(profile)),
                        buildConversationCallbacks(gen));
                if (!handle.isTerminal() && streamGeneration == gen && activeTurn == turn) {
                    turn.handle = handle;
                }
            } catch (Throwable startFailure) {
                onStreamError(startFailure);
            }
        });
    }

    /**
     * 当前消息应路由到的会话模式 id。
     *
     * <p>附件能力闸门与发送路由必须共用同一判定——此前两处各写一份三元判断，
     * 新增模式漏改一处就会出现「闸门按 A 模式校验、消息却发给 B 模式」的错位。</p>
     *
     * @param forcePlan 本条消息是否被 /研讨 斜杠命令强制走研讨模式
     */
    private String conversationTargetId(boolean forcePlan) {
        if (forcePlan) return "plan";
        return modeBarController.selectedModeId();
    }

    private boolean isPlanStream() {
        ActiveTurn turn = activeTurn;
        return turn != null && "plan".equals(turn.modeId);
    }

    /**
     * 工作流中心发布成功后的即时 UI 更新。取消在途旧查询，避免旧快照覆盖刚发布的定义。
     */
    public void onWorkflowPublished(String workflowId, String workflowName) {
        fx.dispatch(() -> modeBarController.workflowPublished(workflowId, workflowName));
    }

    /**
     * 构建 {@link ConversationCallbacks}：把 {@link ConversationEvent} 分发到现有 UI 方法。
     *
     * <p>所有事件消费都包裹在 {@link Platform#runLater}，并通过 {@code streamGeneration}
     * 校验确保会话切换/删除后旧流的事件被丢弃。</p>
     */
    private ConversationCallbacks buildConversationCallbacks(int gen) {
        return new ConversationCallbacks() {
            @Override
            public void onEvent(ConversationEvent event) {
                fx.dispatch(() -> {
                    if (streamGeneration != gen) return;
                    switch (event) {
                        case ConversationEvent.Thinking t -> appendThinkingChunk(t.chunk());
                        case ConversationEvent.Reply r -> appendReplyChunk(r.chunk());
                        case ConversationEvent.ToolResult tr ->
                                appendSubAgentChunk(tr.toolName(), tr.result(), SubAgentChunkKind.RESULT);
                        case ConversationEvent.SubAgentThinking st ->
                                appendSubAgentChunk(st.agentName(), st.chunk(), SubAgentChunkKind.THINKING);
                        case ConversationEvent.SubAgentReply sr ->
                                appendSubAgentChunk(sr.agentName(), sr.chunk(), SubAgentChunkKind.REPLY);
                        case ConversationEvent.Hint h -> appendPlanHint(h.text());
                        case ConversationEvent.AgentStart as -> appendPlanAgentStart(as.agentName());
                        case ConversationEvent.AgentReply ar -> appendPlanAgentReplyChunk(ar.chunk());
                        case ConversationEvent.Evaluation ev -> appendPlanHint(ev.result().formatForDisplay());
                        case ConversationEvent.LoopDetected ld -> onLoopDetected(ld.warning());
                        case ConversationEvent.Usage u -> updateThinkingPanelMetrics(u);
                        case ConversationEvent.Progress p -> thinkingPanel.recordPipelineProgress(
                                p.stageId(), p.stageLabel(),
                                p.status() == null ? "running" : p.status().name(),
                                p.detail());
                        case ConversationEvent.Custom c -> {
                            if ("plan_final".equals(c.kind())
                                    && c.payload() instanceof String finalDraft) {
                                ActiveTurn turn = activeTurn;
                                if (turn != null) turn.finalPlanDraft = finalDraft;
                            } else if ("clarify_request".equals(c.kind())
                                    && c.payload() instanceof com.javaclaw.agent.clarify.ClarifyPayload cp) {
                                // 先渲染琥珀色卡片，再强制收尾流（不抖动 — 已有显眼卡片）。
                                // 工具层已 dispose 订阅，这里再做一次 UI 状态重置，
                                // 确保模型若仍残留事件也被新的 streamGeneration 拦掉。
                                appendClarifyCard(cp.reason(), cp.question());
                                stopActiveStream(CancellationReason.MODE_SWITCH, false,
                                        StopPolicy.DISCARD_AND_INVALIDATE);
                            } else if (com.javaclaw.loop.LoopConstants.EVENT_STATUS_KIND.equals(c.kind())
                                    && c.payload() instanceof com.javaclaw.loop.model.LoopStatus ls) {
                                updateLoopStatus(ls);
                            } else {
                                log.debug("收到自定义事件 [{}] {}", c.kind(), c.payload());
                            }
                        }
                    }
                });
            }

            @Override
            public void onTerminal(ConversationOutcome outcome) {
                fx.dispatch(() -> {
                    if (streamGeneration != gen) return;
                    ActiveTurn turn = activeTurn;
                    if (turn != null && turn.generation == gen) turn.handle = null;
                    switch (outcome) {
                        case ConversationOutcome.Completed ignored -> onStreamComplete();
                        case ConversationOutcome.Cancelled cancelled ->
                                onStreamCancelled(cancelled);
                        case ConversationOutcome.Failed failed -> onStreamError(failed.error());
                    }
                });
            }
        };
    }

    // ==================== RichTextFX 气泡工厂 ====================

    /**
     * 添加带附件的用户消息（设计稿风格：头像 · 姓名+时间头部 · 内容，左对齐）
     */
    private void addUserBubbleWithAttachments(String text, List<File> attachments) {
        ChatMessage message = new ChatMessage(ChatMessage.Role.USER, text, attachments);
        currentSession.getMessages().add(message);
        ChatMessageRowView row = messageRows.create(
                ChatMessageRowFactory.Variant.USER,
                message,
                AgentConfig.AGENT_NAME,
                currentModelDisplayName(),
                "—",
                List.of(),
                this::enableImageZoom);
        sessionViewController.addMessage(row.root());
        log.debug("已添加用户消息（含 {} 个附件）", attachments.size());
    }

    // ==================== 流式输出气泡 ====================

    /** 创建并装配一条由独立 FXML 管理的流式助手消息。 */
    private void createStreamingBubble() {
        activeLoopStatusView = null;
        ChatMessage timeHolder = new ChatMessage(ChatMessage.Role.ASSISTANT, "");
        AssistantMessageView message = assistantMessages.create(
                AgentConfig.AGENT_NAME,
                currentModelDisplayName(),
                timeHolder.getFormattedTime());
        activeAssistantMessage = message;
        message.setRegenerateAction(() -> {
            String last = findLastUserMessage();
            if (last != null) {
                composerController.replaceInput(last);
                onSendMessage();
            }
        });
        message.setQuoteAction(current -> {
            String quoted = "> " + current.replace("\n", "\n> ") + "\n\n";
            composerController.insertInputAtStart(quoted);
            composerController.focusInput();
        });
        message.setSaveAction(this::saveAssistantReply);
        message.setDeleteAction(() -> deleteAssistantMessage(message));
        sessionViewController.addMessage(message.root());
        log.debug("已通过 FXML 创建流式助手消息");
    }

    private void saveAssistantReply(String text) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("保存回复到文件");
        chooser.setInitialFileName("reply.md");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Markdown", "*.md"),
                new FileChooser.ExtensionFilter("文本文件", "*.txt"),
                new FileChooser.ExtensionFilter("所有文件", "*.*"));
        File file = chooser.showSaveDialog(outerRoot.getScene().getWindow());
        if (file == null) return;
        backgroundTasks.submit(TaskSpec.io("export-assistant-reply"), context -> {
            java.nio.file.Files.writeString(file.toPath(), text);
            return null;
        }).completion().whenComplete((ignored, failure) -> {
            if (failure != null) log.error("保存回复到文件失败", failure);
        });
    }

    private void deleteAssistantMessage(AssistantMessageView message) {
        if (!sessionViewController.removeContaining(message.root())) return;
        if (!currentSession.getMessages().isEmpty()) {
            int lastIndex = currentSession.getMessages().size() - 1;
            if (currentSession.getMessages().get(lastIndex).getRole()
                    == ChatMessage.Role.ASSISTANT) {
                currentSession.getMessages().remove(lastIndex);
                saveChatHistory();
            }
        }
        if (message != activeAssistantMessage) message.close();
    }

    /**
     * 取当前模型的精简显示名（用于消息气泡头部徽章）。
     */
    private String currentModelDisplayName() {
        try {
            String m = AgentConfig.getInstance().getModelName();
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

    /**
     * 追加思考过程的文本片段（路由到右侧思考面板）
     */
    private void appendThinkingChunk(String chunk) {
        thinkingPanel.appendThinking(chunk);
    }

    /**
     * 移除「生成中」占位，恢复气泡显示。幂等：可在首 chunk 与各终态重复调用。
     */
    private void dismissGenPlaceholder() {
        AssistantMessageView message = activeAssistantMessage;
        if (message != null) message.revealReply();
    }

    /**
     * 追加回复内容的文本片段
     */
    private void appendReplyChunk(String chunk) {
        AssistantMessageView message = activeAssistantMessage;
        if (message == null) return;
        MarkdownBubble reply = message.reply();

        if (reply.getLength() == 0) {
            dismissGenPlaceholder();
            composerController.setThinkingText("助手正在回复...");
            thinkingPanel.setReplying();
            log.debug("开始接收回复内容");
        }

        reply.appendText(chunk);
        ActiveTurn turn = activeTurn;
        if (turn != null) turn.messageDraft.append(chunk);
    }

    /**
     * 子智能体事件种类：思考 / 回复 / 普通工具结果。
     *
     * <p>从 {@link ConversationEvent} 的三个子类型映射而来，用于在同一个
     * {@link #appendSubAgentChunk(String, String, SubAgentChunkKind)} 方法里统一处理气泡渲染。</p>
     */
    private enum SubAgentChunkKind {
        THINKING, REPLY, RESULT
    }

    /**
     * 追加一段子智能体 / 工具调用的增量内容。
     *
     * <p>三种来源事件统一在这里渲染：
     * <ul>
     *   <li>{@link SubAgentChunkKind#THINKING}：累加到思考区域</li>
     *   <li>{@link SubAgentChunkKind#REPLY}：追加到回复区域</li>
     *   <li>{@link SubAgentChunkKind#RESULT}：普通工具结果，追加到回复区域并尝试内联图片</li>
     * </ul>
     * 同一个智能体的连续调用合并到同一展示块；动态任务智能体每次 RESULT 都强制新块。</p>
     */
    private void appendSubAgentChunk(String toolName, String content, SubAgentChunkKind kind) {
        AssistantMessageView message = activeAssistantMessage;
        if (message == null) return;
        message.showTools();

        String displayName = mapToolDisplayName(toolName);

        // 切换智能体时新建结果块；动态任务智能体每次 RESULT 强制新块
        boolean isDynamicTask = "execute_task_agent".equals(toolName);
        if (toolName == null || !toolName.equals(activeToolName)
                || (isDynamicTask && kind == SubAgentChunkKind.RESULT)) {
            createSubAgentResultBlock(toolName, displayName);
        }

        if (kind == SubAgentChunkKind.THINKING) {
            appendSubThinking(displayName, content);
        } else {
            appendSubReply(displayName, content, content.length());
            if (activeToolResultBlock != null) {
                tryDisplayInlineImages(content, activeToolResultBlock.contentHost());
            }
        }
    }

    /** 创建新的子智能体结果 FXML 控件；思考内容仍路由到右侧面板。 */
    private void createSubAgentResultBlock(String toolName, String displayName) {
        if (activeToolResultBlock != null) activeToolResultBlock.bubble().finish();
        activeToolName = toolName;
        activeToolResultBlock = expandableBlocks.create(
                ExpandableMarkdownBlockFactory.Variant.SUB_AGENT,
                displayName,
                false);
        activeAssistantMessage.toolsHost().getChildren().add(activeToolResultBlock.root());
        log.debug("已创建子智能体结果块 [{}]（初始隐藏，等待内容）", toolName);
    }

    /**
     * 追加子智能体思考内容（路由到右侧面板，中间区域不显示）
     */
    private void appendSubThinking(String displayName, String thinking) {
        // 更新状态提示
        composerController.setThinkingText(displayName + " 正在思考...");

        // 路由到右侧思考面板
        thinkingPanel.appendSubAgentThinking(displayName, thinking);
    }

    /**
     * 追加子智能体回复内容
     */
    private void appendSubReply(String displayName, String displayText, int rawLength) {
        ExpandableMarkdownBlockView block = activeToolResultBlock;
        if (block == null) return;

        // 跳过空白内容，避免生成空气泡
        if (displayText == null || displayText.isBlank()) {
            log.debug("跳过空白子智能体回复 [{}]", activeToolName);
            return;
        }

        block.revealContent();

        // 更新状态提示
        composerController.setThinkingText(displayName + " 已返回结果...");

        // 通知右侧面板标记完成
        thinkingPanel.markSubAgentResult(displayName,
                displayText.length() > 80 ? displayText.substring(0, 77) + "..." : displayText);

        if (block.bubble().getLength() == 0) {
            block.bubble().appendText(displayText);
            log.debug("已添加子智能体回复 [{}]，内容长度: {} 字符", activeToolName, rawLength);
        } else {
            block.bubble().appendText(displayText);
            log.debug("已合并子智能体回复 [{}]，追加内容长度: {} 字符", activeToolName, rawLength);
        }
    }

    /**
     * 检测文本中的图片文件路径，并在指定容器中内联显示图片
     *
     * <p>通过扫描文本中以 "/" 开头的绝对路径片段，验证文件存在后在气泡中添加
     * ImageView 预览。已显示的路径不会重复添加。同时将发现的图片路径收集到
     * {@link #displayedImagePaths} 中，供保存消息时写入 ChatMessage。</p>
     *
     * @param text      待检测的文本内容
     * @param container 图片要添加到的子智能体结果区或当前助手回复内容区
     */
    private void tryDisplayInlineImages(String text, VBox container) {
        if (container == null || text == null) return;

        for (String path : extractImagePaths(text)) {
            if (displayedImagePaths.contains(path)) continue;

            File file = new File(path);
            if (!ProjectAccessPolicy.isProjectFilePath(file.toPath())
                    || !file.exists() || !file.isFile()) continue;

            try {
                Image image = new Image(file.toURI().toString(), 400, 0, true, true);
                ImageView imageView = new ImageView(image);
                imageView.setFitWidth(400);
                imageView.setPreserveRatio(true);
                imageView.setSmooth(true);
                imageView.getStyleClass().add("screenshot-image");
                enableImageZoom(imageView, file);

                container.getChildren().add(imageView);
                displayedImagePaths.add(path);
                log.info("已内联显示图片: {}", path);
            } catch (Exception e) {
                log.warn("内联显示图片失败: {}", path, e);
            }
        }
    }

    /**
     * 为对话中的图片视图启用双击放大查看。
     *
     * <p>双击后弹出 FXML 图片查看器，支持滚轮缩放与拖拽平移；
     * 鼠标悬停显示手型并提示可点击。</p>
     *
     * @param imageView 图片视图
     * @param file      对应的图片文件（弹窗加载原图）
     */
    private void enableImageZoom(ImageView imageView, File file) {
        if (imageView == null || file == null) return;
        imageView.setCursor(javafx.scene.Cursor.HAND);
        javafx.scene.control.Tooltip.install(imageView,
                new javafx.scene.control.Tooltip("双击查看大图（可缩放/拖拽）"));
        imageView.setOnMouseClicked(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY && e.getClickCount() == 2) {
                javafx.stage.Window owner = imageView.getScene() != null
                        ? imageView.getScene().getWindow() : null;
                imageViewer.open(owner, file.toPath());
                e.consume();
            }
        });
    }

    /**
     * 从文本中提取绝对图片文件路径
     *
     * <p>扫描以 "/" 开头、仅包含合法路径字符、以图片扩展名结尾的路径片段。
     * 使用白名单定义合法路径字符，避免中文标点等非路径字符被误纳入。</p>
     */
    private List<String> extractImagePaths(String text) {
        List<String> paths = new ArrayList<>();
        int len = text.length();
        int i = 0;
        while (i < len) {
            if (text.charAt(i) == '/') {
                int start = i;
                i++;
                while (i < len && isPathChar(text.charAt(i))) {
                    i++;
                }
                String candidate = text.substring(start, i);
                if (isImagePath(candidate)) {
                    paths.add(candidate);
                }
            } else {
                i++;
            }
        }
        return paths;
    }

    /**
     * 判断字符是否为合法的文件路径字符
     */
    private boolean isPathChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '/' || c == '.' || c == '_' || c == '-' || c == '~' || c == '+';
    }

    private boolean isImagePath(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) return false;
        String ext = path.substring(dot + 1).toLowerCase();
        return IMAGE_EXTENSIONS.contains(ext);
    }

    /**
     * 将工具名称映射为可读的中文显示名
     */
    private String mapToolDisplayName(String toolName) {
        if (toolName == null) return "专家回复";
        return switch (toolName) {
            case "coding_expert" -> "编程专家";
            case "knowledge_expert" -> "知识专家";
            case "web_expert" -> "Web浏览专家";
            case "email_expert" -> "邮件专家";
            case "system_expert" -> "系统操作专家";
            case "notification_expert" -> "通知专家";
            case "task_evaluator" -> "任务评估专家";
            case "execute_task_agent" -> "任务智能体";
            default -> toolName.contains("expert") ? toolName : "专家回复 [" + toolName + "]";
        };
    }

    /**
     * 追加规划提示信息（路由到右侧思考面板）
     */
    private void appendPlanHint(String hint) {
        composerController.setThinkingText("正在执行规划...");
        thinkingPanel.updatePlan(hint);
        log.debug("规划提示已更新: {} 字符", hint.length());
    }

    // ==================== 规划模式 ====================

    /**
     * 切换规划模式开关
     */
    private void togglePlanMode() {
        boolean enable = !"plan".equals(modeBarController.selectedModeId());
        modeBarController.selectMode(enable ? "plan" : "chat");
        log.info("规划模式已{}", enable ? "开启" : "关闭");
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

    /**
     * 追加规划模式中智能体的发言（每位智能体一个独立展示块）
     *
     * @param agentName 智能体名称（如"规划协调者"、"编程专家"等）
     * @param response  发言内容
     */
    /**
     * 规划模式：智能体开始发言 — 创建气泡容器和空文本区域
     *
     * @param agentName 智能体名称
     */
    private void appendPlanAgentStart(String agentName) {
        AssistantMessageView message = activeAssistantMessage;
        if (message == null) return;

        // 切换到新智能体前，先把上一位标记为"已完成"，并用累积文本生成摘要
        if (currentPlanAgentName != null && !currentPlanAgentName.equals(agentName)) {
            finalizeCurrentPlanAgent();
        }

        if (activePlanAgentBlock != null) activePlanAgentBlock.bubble().finish();

        message.showTools();

        // 更新状态提示
        composerController.setThinkingText(agentName + " 正在发言...");

        // 在右侧面板建立智能体卡片（初始状态"思考中..."，内容区待 reply chunk 追加）
        currentPlanAgentName = agentName;
        currentPlanAgentBuffer.setLength(0);
        thinkingPanel.appendSubAgentThinking(agentName, "");

        activePlanAgentBlock = expandableBlocks.create(
                ExpandableMarkdownBlockFactory.Variant.PLAN_AGENT,
                agentName,
                true);
        message.toolsHost().getChildren().add(activePlanAgentBlock.root());

        log.debug("规划模式 [{}] 开始发言", agentName);
    }

    /**
     * 规划模式：为当前发言智能体生成摘要并在右侧面板标记"已完成"。
     *
     * <p>在新智能体开始发言前、或整体流结束时调用。</p>
     */
    private void finalizeCurrentPlanAgent() {
        if (currentPlanAgentName == null) return;
        if (activePlanAgentBlock != null) activePlanAgentBlock.bubble().finish();
        String buffered = currentPlanAgentBuffer.toString().trim();
        String summary;
        if (buffered.isEmpty()) {
            summary = "（无回复内容）";
        } else {
            String singleLine = buffered.replaceAll("\\s+", " ");
            summary = singleLine.length() > 80
                    ? singleLine.substring(0, 77) + "..."
                    : singleLine;
        }
        thinkingPanel.markSubAgentResult(currentPlanAgentName, summary);
        currentPlanAgentName = null;
        currentPlanAgentBuffer.setLength(0);
    }

    /**
     * 规划模式：追加智能体回复的流式文本片段
     *
     * @param chunk 文本增量片段
     */
    private void appendPlanAgentReplyChunk(String chunk) {
        ExpandableMarkdownBlockView block = activePlanAgentBlock;
        if (block == null) return;

        // 去除 PLAN_COMPLETE 标记
        String displayChunk = chunk;
        if (displayChunk.contains("[PLAN_COMPLETE]")) {
            displayChunk = displayChunk.replace("[PLAN_COMPLETE]", "");
        }
        if (!displayChunk.isEmpty()) {
            block.bubble().appendText(displayChunk);
            ActiveTurn turn = activeTurn;
            if (turn != null) turn.messageDraft.append(displayChunk);
            // 同步追加到右侧面板对应智能体的思考区，展开后可见发言内容
            if (currentPlanAgentName != null) {
                currentPlanAgentBuffer.append(displayChunk);
                thinkingPanel.appendSubAgentThinking(currentPlanAgentName, displayChunk);
            }
        }
    }

    // ==================== 流式输出完成/错误处理 ====================

    /**
     * 流式输出完成时的处理
     */
    private void onStreamComplete() {
        AssistantMessageView message = activeAssistantMessage;
        MarkdownBubble reply = message == null ? null : message.reply();
        log.info("流式输出已完成 — 回复: {} 字符",
                reply != null ? reply.getLength() : 0);

        // 规划模式：流结束时为最后一位发言智能体生成摘要并标记完成
        if (isPlanStream()) {
            finalizeCurrentPlanAgent();
        }

        // 移除「生成中」占位，恢复气泡显示（无论是否有回复内容）
        dismissGenPlaceholder();

        try {
            if (isPlanStream()) {
                // 规划模式的内容位于各智能体结果块，隐藏未使用的主回复卡片。
                if (reply != null && reply.getLength() == 0 && message != null) {
                    message.hideReplyCard();
                }
            } else if (reply != null && reply.getLength() == 0) {
                reply.finishWith("[模型未返回有效回复]");
            }

            // 检测主回复中的图片路径并内联显示
            if (reply != null && message != null) {
                tryDisplayInlineImages(reply.getText(), message.replyContentHost());
            }

            // 将助手回复添加到消息列表并保存（携带流式过程中收集的图片路径）
            String replyText = currentReplyText();
            // 后台流式：回复必须落到发起流的会话（streamingSession），而非当前展示会话
            ChatSession target = streamingSession != null ? streamingSession : currentSession;
            if (replyText != null && target != null) {
                ChatMessage assistantMsg = new ChatMessage(ChatMessage.Role.ASSISTANT, replyText);
                assistantMsg.setDeliveryState(DeliveryState.COMPLETE);
                assistantMsg.setMetrics(currentTurnMetrics());
                for (String imgPath : displayedImagePaths) {
                    assistantMsg.addImagePath(imgPath);
                }
                target.getMessages().add(assistantMsg);
                if (message != null) {
                    message.enableAdoption(() -> adoptAssistantMessage(assistantMsg));
                }
                // 首条用户消息发送后自动更新会话标题
                target.autoTitle();
                if (target == currentSession) {
                    updateTopTitle();
                }
                sidebarController.updateSessionTitle(target.getId(), target.getTitle());
                saveSessionMessages(target);
                chatService.saveSession(target.getId());
            }
        } catch (Exception e) {
            log.error("保存回复时发生错误", e);
        } finally {
            // 无论是否出错，都必须恢复 UI 状态
            clearActiveReferences();
            thinkingPanel.endStream();
            showThinkingIndicator(false);
            composerController.setThinkingText("助手正在思考中...");
            setInputEnabled(true);
            finishBackgroundStreamIfAway();
            activeTurn = null;
            composerController.focusInput();
        }
    }

    private void adoptAssistantMessage(ChatMessage message) {
        message.setAdopted(true);
        String text = message.getContent();
        if (text != null && !text.isEmpty()) {
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(
                    java.util.Map.of(javafx.scene.input.DataFormat.PLAIN_TEXT, text));
        }
        saveChatHistory();
        log.info("用户采纳消息（{} 字符）", text == null ? 0 : text.length());
    }

    /**
     * 流式输出错误时的处理
     */
    private void onStreamError(Throwable error) {
        log.error("流式输出发生错误", error);

        if (isPlanStream()) {
            finalizeCurrentPlanAgent();
        }
        ActiveTurn failedTurn = activeTurn;
        if (failedTurn != null) failedTurn.deliveryState = DeliveryState.FAILED;
        thinkingPanel.endStreamFailed();

        try {
            String errorMsg = "调用失败: " + runtime.extractErrorMessage(error);
            ChatSession target = streamingSession != null ? streamingSession : currentSession;
            String partial = currentReplyText();
            if (partial != null && !partial.isBlank() && target != null) {
                String failedText = partial + "\n\n> ⚠ 失败：" + runtime.extractErrorMessage(error);
                MarkdownBubble reply = activeReply();
                if (!isPlanStream() && reply != null) {
                    reply.finishWith(failedText);
                } else if (isPlanStream() && activePlanAgentBlock != null) {
                    activePlanAgentBlock.bubble().finishWith(failedText);
                }
                ChatMessage failedMessage = new ChatMessage(ChatMessage.Role.ASSISTANT, failedText);
                failedMessage.setDeliveryState(DeliveryState.FAILED);
                failedMessage.setMetrics(currentTurnMetrics());
                target.getMessages().add(failedMessage);
                saveSessionMessages(target);
            } else if (target != null && target != currentSession) {
                target.getMessages().add(new ChatMessage(ChatMessage.Role.SYSTEM, errorMsg));
                saveSessionMessages(target);
            } else {
                if (activeAssistantMessage != null) activeAssistantMessage.hideReplyCard();
                addStaticBubble(ChatMessage.Role.SYSTEM, errorMsg);
                if (currentSession != null) saveSessionMessages(currentSession);
            }
        } catch (Exception e) {
            log.error("显示错误信息时发生异常", e);
        } finally {
            clearActiveReferences();
            showThinkingIndicator(false);
            composerController.setThinkingText("助手正在思考中...");
            setInputEnabled(true);
            composerController.focusInput();
            finishBackgroundStreamIfAway();
            activeTurn = null;
        }
    }

    private void onStreamCancelled(ConversationOutcome.Cancelled cancelled) {
        log.info("流式输出已取消 — reason={}, userInitiated={}",
                cancelled.reason(), cancelled.userInitiated());
        ActiveTurn cancelledTurn = activeTurn;
        if (cancelledTurn != null) cancelledTurn.deliveryState = DeliveryState.CANCELLED;
        if (isPlanStream()) finalizeCurrentPlanAgent();
        dismissGenPlaceholder();
        try {
            String replyText = currentReplyText();
            ChatSession target = streamingSession != null ? streamingSession : currentSession;
            if (replyText != null && !replyText.isBlank() && target != null) {
                String stoppedText = replyText + "\n\n> ⏹ 已停止";
                MarkdownBubble reply = activeReply();
                if (!isPlanStream() && reply != null) {
                    reply.finishWith(stoppedText);
                } else if (isPlanStream() && activePlanAgentBlock != null) {
                    activePlanAgentBlock.bubble().finishWith(stoppedText);
                }
                ChatMessage cancelledMessage =
                        new ChatMessage(ChatMessage.Role.ASSISTANT, stoppedText);
                cancelledMessage.setDeliveryState(DeliveryState.CANCELLED);
                cancelledMessage.setMetrics(currentTurnMetrics());
                target.getMessages().add(cancelledMessage);
                saveSessionMessages(target);
            } else if (activeAssistantMessage != null) {
                activeAssistantMessage.hideReplyCard();
            }
            if (activeLoopStatusView != null) activeLoopStatusView.markCancelled();
        } finally {
            clearActiveReferences();
            thinkingPanel.endStreamCancelled();
            showThinkingIndicator(false);
            composerController.setThinkingText("助手正在思考中...");
            setInputEnabled(true);
            finishBackgroundStreamIfAway();
            activeTurn = null;
            composerController.focusInput();
        }
    }

    /**
     * 循环检测触发时的处理
     *
     * <p>当 LoopGuard 检测到连续重复的工具调用时，
     * 流会被自动取消，此方法将警告信息展示给用户并恢复输入状态。</p>
     */
    // ==================== Demo 模式回放 ====================

    /**
     * 单条演示事件
     */
    private record DemoEvent(long delayMs, String type, String payload, String toolName) {}

    /**
     * 播放内置演示脚本 — 完全离线，不调用真实模型
     *
     * <p>展示一次"总结网页 + 生成摘要"的端到端过程：思考 → 工具调用 → 回复 → 完成。
     * 每个事件按 delayMs 延迟后在 JavaFX 线程上执行，自动处理代次失效。</p>
     */
    private void runDemoPlayback(final int gen) {
        List<DemoEvent> script = List.of(
                new DemoEvent(200, "thinking", "收到用户请求，准备调用网页专家获取并总结内容。", null),
                new DemoEvent(1000, "thinking", "\n\n打开 example.com 并读取页面快照...", null),
                new DemoEvent(1800, "tool_result",
                        "[browser_navigate][成功] 已加载 https://example.com\n页面标题：Example Domain",
                        "browser_navigate"),
                new DemoEvent(2600, "tool_result",
                        "[browser_snapshot][成功] 捕获快照（3 个文本节点、1 个链接）",
                        "browser_snapshot"),
                new DemoEvent(3400, "thinking",
                        "\n\n网页内容已采集，开始撰写摘要...", null),
                new DemoEvent(4200, "reply", "这是一个演示回放 — ", null),
                new DemoEvent(4600, "reply", "所有事件均为本地回放，不消耗 API 额度。\n\n", null),
                new DemoEvent(5000, "reply", "**页面摘要**：Example Domain 是 IANA 用于文档示例的占位域名，内容仅为示范性说明。\n\n", null),
                new DemoEvent(5600, "reply", "想体验真实效果？请在「设置」中配置你的 API Key。", null),
                new DemoEvent(6200, "complete", "", null)
        );

        long cumulative = 0;
        for (DemoEvent ev : script) {
            cumulative += ev.delayMs();
            final DemoEvent e = ev;
            javafx.animation.PauseTransition pt = new javafx.animation.PauseTransition(Duration.millis(cumulative));
            pt.setOnFinished(fin -> {
                if (streamGeneration != gen) return;  // 会话已切换，放弃回放
                switch (e.type()) {
                    case "thinking" -> appendThinkingChunk(e.payload());
                    case "reply" -> appendReplyChunk(e.payload());
                    case "tool_result" -> appendSubAgentChunk(e.toolName(), e.payload(), SubAgentChunkKind.RESULT);
                    case "complete" -> onStreamComplete();
                }
            });
            pt.play();
        }
    }

    private void onLoopDetected(String warning) {
        log.warn("循环检测触发: {}", warning);

        try {
            MarkdownBubble reply = activeReply();
            if (reply != null) {
                if (reply.getLength() == 0) {
                    reply.finishWith("[循环中断] " + warning);
                } else {
                    reply.appendText("\n\n[循环中断] " + warning);
                }
            } else {
                addStaticBubble(ChatMessage.Role.SYSTEM, warning);
            }
        } catch (Exception e) {
            log.error("显示循环检测警告时发生异常", e);
        } finally {
            clearActiveReferences();
            thinkingPanel.endStream();
            showThinkingIndicator(false);
            composerController.setThinkingText("助手正在思考中...");
            setInputEnabled(true);
            composerController.focusInput();
        }
    }

    // ==================== 静态气泡 ====================

    /**
     * 渲染模型主动发起的澄清请求卡片（琥珀色，醒目）。
     *
     * <p>同时把澄清内容以 markdown 形式写入当前会话历史，确保重新打开会话时仍可见。
     * 模型在调用 {@code ask_user_clarification} 工具后应立即结束本轮，
     * 用户的下一条输入会自然成为下一轮对话。</p>
     */
    private void appendClarifyCard(String reason, String question) {
        ChatMessage timestamp = new ChatMessage(ChatMessage.Role.ASSISTANT, "");
        ClarificationCardView card = clarificationCards.create(
                AgentConfig.AGENT_NAME,
                currentModelDisplayName(),
                timestamp.getFormattedTime(),
                reason,
                question);
        sessionViewController.addMessage(card.root());

        // 用 Markdown 引用保存语义结构，历史会话无需依赖专用卡片也能完整呈现。
        StringBuilder md = new StringBuilder();
        md.append("> 🤔 **需要您的澄清**\n>\n");
        if (reason != null && !reason.isBlank()) {
            md.append("> **原因**：").append(reason.replace("\n", "\n> ")).append("\n>\n");
        }
        if (question != null && !question.isBlank()) {
            md.append("> **问题**：").append(question.replace("\n", "\n> ")).append("\n");
        }
        if (currentSession != null) {
            currentSession.getMessages().add(
                    new ChatMessage(ChatMessage.Role.ASSISTANT, md.toString()));
            saveSessionMessages(currentSession);
        }

        // 焦点回到输入框，让用户可以直接回答澄清问题。
        composerController.focusInput();
        log.info("已渲染澄清卡片: reason='{}' question='{}'", reason, question);
    }

    /** 当前循环运行的状态面板；每次新发送重置，一次循环内跨轮复用并原地刷新。 */
    private LoopStatusView activeLoopStatusView;

    /**
     * 处理循环状态事件（{@code loop_status}）：首个事件建面板行，后续原地刷新。
     */
    private void updateLoopStatus(com.javaclaw.loop.model.LoopStatus status) {
        if (activeLoopStatusView == null) {
            activeLoopStatusView = loopStatusViews.create(status);
            HBox row = activeLoopStatusView.root();
            // 首个 loop_status 在第 1 轮结束才到（可能数分钟），期间用户可能已切走会话——
            // 流所属会话的场景图此时挂起在 suspendedStreamingNodes，新建的状态卡必须
            // 归入挂起集（切回时随场景图一并恢复），否则会被塞进当前展示的无关会话
            if (streamingSession != null && streamingSession != currentSession) {
                suspendedStreamingNodes.add(row);
            } else {
                sessionViewController.addMessage(row);
            }
            return;
        }
        activeLoopStatusView.update(status);
    }

    /**
     * 添加一条静态消息气泡（设计稿：头像 + 姓名/时间头部 + 纯文本正文）
     */
    private void addStaticBubble(ChatMessage.Role role, String content) {
        ChatMessage message = new ChatMessage(role, content);
        currentSession.getMessages().add(message);
        sessionViewController.addMessage(createStaticMessageRow(message, List.of()).root());
        log.debug("已添加 {} 静态消息", role.getDisplayName());
    }

    /**
     * 从历史记录添加静态消息气泡（不重复添加到当前会话消息列表）
     */
    private void addStaticBubbleFromHistory(ChatMessage message) {
        List<ImageView> historyImages = new java.util.ArrayList<>();
        for (String path : message.getImagePaths()) {
            File file = new File(path);
            if (!ProjectAccessPolicy.isProjectFilePath(file.toPath())
                    || !file.exists() || !file.isFile()) continue;
            try {
                Image image = new Image(file.toURI().toString(), 400, 0, true, true);
                ImageView imageView = new ImageView(image);
                imageView.setFitWidth(400);
                imageView.setPreserveRatio(true);
                imageView.setSmooth(true);
                imageView.getStyleClass().add("screenshot-image");
                enableImageZoom(imageView, file);
                historyImages.add(imageView);
            } catch (Exception e) {
                log.warn("历史图片加载失败: {}", path, e);
            }
        }
        sessionViewController.addMessage(createStaticMessageRow(message, historyImages).root());
    }

    /** 创建历史或系统消息，并由 FXML Controller 持有嵌套 Markdown 生命周期。 */
    private ChatMessageRowView createStaticMessageRow(
            ChatMessage message, List<? extends javafx.scene.Node> extraImages) {
        ChatMessageRowFactory.Variant variant = switch (message.getRole()) {
            case USER -> ChatMessageRowFactory.Variant.USER;
            case ASSISTANT -> ChatMessageRowFactory.Variant.ASSISTANT;
            case SYSTEM -> ChatMessageRowFactory.Variant.SYSTEM;
        };
        return messageRows.create(
                variant,
                message,
                AgentConfig.AGENT_NAME,
                currentModelDisplayName(),
                formatMessageMeta(message),
                extraImages,
                this::enableImageZoom);
    }

    // ==================== 多会话管理 ====================

    /**
     * 加载所有会话，恢复侧边栏列表和当前会话
     */
    private void loadSessions() {
        sessionViewController.enterAtTail();
        List<ChatSession> loaded = chatHistoryManager.loadSessionIndex();
        sessions.clear();

        // 过滤掉没有持久化消息的空会话（避免重复的"新的对话"条目）
        List<ChatSession> validSessions = loaded.stream()
                .filter(s -> chatHistoryManager.hasSessionMessages(s.getId()))
                .collect(java.util.stream.Collectors.toList());

        if (validSessions.isEmpty()) {
            // 没有有效历史会话，创建默认会话
            ChatSession defaultSession = new ChatSession("新的对话");
            sessions.add(defaultSession);
            chatHistoryManager.saveSessionIndex(sessions);
            currentSession = defaultSession;
            sidebarController.addSession(defaultSession, true);
            addWelcomeBubble();
        } else {
            sessions.addAll(validSessions);
            // 索引与磁盘同步（清除已过滤掉的空会话记录）
            if (validSessions.size() < loaded.size()) {
                chatHistoryManager.saveSessionIndex(sessions);
            }
            // 在侧边栏中添加所有会话，默认选中第一个
            for (int i = 0; i < sessions.size(); i++) {
                sidebarController.addSession(sessions.get(i), i == 0);
            }
            // 加载第一个会话的消息
            currentSession = sessions.getFirst();
            List<ChatMessage> messages = chatHistoryManager.loadSessionMessages(currentSession.getId());
            currentSession.getMessages().addAll(messages);
            for (ChatMessage msg : messages) {
                addStaticBubbleFromHistory(msg);
            }
            // 恢复智能体状态（Memory + PlanNotebook）
            chatService.loadSession(currentSession.getId());
            log.info("已恢复会话 [{}] 的 {} 条消息", currentSession.getTitle(), messages.size());
        }

        updateTopTitle();
    }

    /**
     * 新建会话
     */
    private void onNewSession() {
        if (rejectIfRebuilding("新建会话")) {
            return;
        }
        log.info("用户请求新建会话");
        sessionViewController.enterAtTail();

        final boolean streamRunning = streamingActive && streamingSession != null;

        // 保存当前会话消息
        saveCurrentSession();
        if (streamRunning && currentSession == streamingSession) {
            // 流式进行中：挂起场景图让流在后台继续，不杀流、不动智能体上下文
            suspendedStreamingNodes.clear();
            suspendedStreamingNodes.addAll(sessionViewController.detachMessages());
            composerController.setThinkingText("其他会话正在后台生成回复…");
        } else if (!streamRunning) {
            if (streamingActive) {
                stopActiveStream(CancellationReason.SESSION_SWITCH, false,
                        StopPolicy.DISCARD_AND_INVALIDATE);  // 防御：流活跃但未记录所属会话
            }
            // 保存智能体状态并清空上下文（完整切换）
            if (currentSession != null) {
                chatService.saveSession(currentSession.getId());
            }
            clearAllHistory();
        }

        // 如果当前会话为空（未发送任何消息），移除它，避免堆积空会话
        if (currentSession != null && currentSession.getMessages().isEmpty()) {
            sessions.remove(currentSession);
            sidebarController.removeSession(currentSession.getId());
        }

        // 创建新会话
        ChatSession newSession = new ChatSession("新的对话");
        sessions.addFirst(newSession);
        currentSession = newSession;

        // 更新侧边栏
        sidebarController.insertSessionAtTop(newSession, true);

        // 清空聊天区域并显示欢迎消息（后台流式期间不重置进度面板，保持可观察）
        disposeMessageList();
        composerController.clearAttachments();
        if (!(streamingActive && streamingSession != null)) {
            thinkingPanel.reset();
        }
        addWelcomeBubble();
        updateTopTitle();

        // 持久化索引
        chatHistoryManager.saveSessionIndex(sessions);
        log.info("新会话已创建: {} [{}]", newSession.getId(), newSession.getTitle());
    }

    /**
     * 切换到指定会话
     */
    private void onSwitchSession(String targetSessionId) {
        if (currentSession != null && currentSession.getId().equals(targetSessionId)) {
            return;
        }
        if (rejectIfRebuilding("切换会话")) {
            return;
        }

        // 先解析目标会话（解析失败不动任何状态）
        ChatSession target = null;
        for (ChatSession s : sessions) {
            if (s.getId().equals(targetSessionId)) {
                target = s;
                break;
            }
        }
        if (target == null) {
            log.warn("未找到目标会话: {}", targetSessionId);
            return;
        }

        // 防御：流活跃但未记录所属会话（不应发生），按旧语义停掉
        if (streamingActive && streamingSession == null) {
            stopActiveStream(CancellationReason.SESSION_SWITCH, false,
                    StopPolicy.DISCARD_AND_INVALIDATE);
        }
        final boolean streamRunning = streamingActive && streamingSession != null;

        log.info("切换会话: {} -> {}{}",
                currentSession != null ? currentSession.getId() : "null",
                targetSessionId, streamRunning ? "（后台流式继续）" : "");

        // ==== 离开当前会话 ====
        saveCurrentSession();
        if (streamRunning && currentSession == streamingSession) {
            // 流式进行中切走：挂起场景图（节点仍被流式回调实时更新），
            // 不杀流、不动智能体上下文、不清 active 引用
            suspendedStreamingNodes.clear();
            suspendedStreamingNodes.addAll(sessionViewController.detachMessages());
            composerController.setThinkingText("其他会话正在后台生成回复…");
        } else if (streamRunning) {
            // 当前是只读视图（流在别的会话跑）：仅释放本视图的静态气泡
            disposeMessageList();
        } else {
            // 无活跃流：完整切换（保存智能体状态 + 清上下文 + 全量重置 UI 引用）
            if (currentSession != null) {
                chatService.saveSession(currentSession.getId());
            }
            clearAllHistory();
            disposeMessageList();
            activeAssistantMessage = null;
            activeToolResultBlock = null;
            activeToolName = null;
            activePlanAgentBlock = null;
            thinkingPanel.reset();
        }
        composerController.clearAttachments();

        // 切换当前会话
        currentSession = target;
        sessionViewController.enterAtTail();

        // ==== 进入目标会话 ====
        if (streamRunning && target == streamingSession) {
            // 切回流式中的会话：恢复挂起的场景图，输出与进度无缝继续
            sessionViewController.setMessages(suspendedStreamingNodes);
            suspendedStreamingNodes.clear();
            composerController.setThinkingText("助手正在思考中...");
        } else {
            // 智能体上下文仅在无活跃流时切换；后台流式期间目标会话为只读视图，
            // 流结束后由 finishBackgroundStreamIfAway 把上下文对齐到当前展示会话
            if (!streamRunning) {
                chatService.loadSession(targetSessionId);
            }
            // 加载目标会话的消息（如果内存中没有）
            if (currentSession.getMessages().isEmpty()) {
                List<ChatMessage> messages = chatHistoryManager.loadSessionMessages(targetSessionId);
                currentSession.getMessages().addAll(messages);
            }
            if (currentSession.getMessages().isEmpty()) {
                addWelcomeBubble();
            } else {
                for (ChatMessage msg : currentSession.getMessages()) {
                    addStaticBubbleFromHistory(msg);
                }
            }
        }

        updateTopTitle();
        log.info("已切换到会话: {} [{}]，{} 条消息",
                currentSession.getId(), currentSession.getTitle(), currentSession.getMessages().size());
    }

    /**
     * 后台流式结束（完成或出错）后的收尾：若用户已切到其他会话，
     * 把智能体上下文从流所属会话对齐到当前展示会话，并释放挂起的旧场景图
     * （切回时按完整历史重建，包含刚完成的回复）。
     */
    private void finishBackgroundStreamIfAway() {
        ChatSession finished = streamingSession;
        streamingSession = null;
        if (finished == null || currentSession == null || finished == currentSession) {
            disposeSuspendedStreamingNodes();  // 前台正常结束不应有挂起节点，防御清理
            return;
        }
        // 智能体上下文此刻仍属于刚结束的会话：保存后切到正在展示的会话
        chatService.saveSession(finished.getId());
        clearAllHistory();
        chatService.loadSession(currentSession.getId());
        disposeSuspendedStreamingNodes();
        log.info("后台会话 [{}] 流式结束，智能体上下文已对齐到当前会话 [{}]",
                finished.getTitle(), currentSession.getTitle());
    }

    /** 释放挂起的流式场景图节点（含其中的 MarkdownBubble） */
    private void disposeSuspendedStreamingNodes() {
        if (suspendedStreamingNodes.isEmpty()) return;
        for (javafx.scene.Node n : suspendedStreamingNodes) {
            collectAndDisposeBubbles(n);
        }
        suspendedStreamingNodes.clear();
    }

    /**
     * 删除指定会话
     */
    private void onDeleteSession(String sessionId) {
        if (rejectIfRebuilding("删除会话")) {
            return;
        }
        log.info("用户请求删除会话: {}", sessionId);

        // 删除的是正在后台流式的会话：先停流并清理挂起节点
        if (streamingSession != null && streamingSession.getId().equals(sessionId)) {
            stopActiveStream(CancellationReason.SESSION_SWITCH, false,
                    StopPolicy.DISCARD_AND_INVALIDATE);
        }

        // 从列表移除
        sessions.removeIf(s -> s.getId().equals(sessionId));
        sidebarController.removeSession(sessionId);
        chatHistoryManager.deleteSession(sessionId);
        chatService.deleteSession(sessionId);

        // 如果删除的是当前会话，切换到其他会话。注意：属于被删会话的流/循环已在
        // 方法开头停掉；其它会话仍在后台跑的流/循环（如另一会话里盯 CI 的 @loop）
        // 不受当前会话删除影响，这里只兜底「流活跃但无归属会话」的异常状态
        if (currentSession != null && currentSession.getId().equals(sessionId)) {
            stopAndClearForCurrentSessionDeleted();

            if (sessions.isEmpty()) {
                // 没有剩余会话，创建新会话
                onNewSession();
            } else {
                // 切换到第一个会话
                currentSession = null;
                String firstId = sessions.getFirst().getId();
                sidebarController.selectSession(firstId);
                onSwitchSession(firstId);
            }
        }

        // 更新索引
        chatHistoryManager.saveSessionIndex(sessions);
    }

    /**
     * 批量删除会话
     */
    private void onBatchDeleteSessions(java.util.List<String> sessionIds) {
        if (rejectIfRebuilding("删除会话")) {
            return;
        }
        log.info("用户请求批量删除 {} 个会话", sessionIds.size());

        // 批量删除包含正在后台流式的会话：先停流并清理挂起节点
        if (streamingSession != null && sessionIds.contains(streamingSession.getId())) {
            stopActiveStream(CancellationReason.SESSION_SWITCH, false,
                    StopPolicy.DISCARD_AND_INVALIDATE);
        }

        boolean currentDeleted = currentSession != null && sessionIds.contains(currentSession.getId());

        for (String id : sessionIds) {
            sessions.removeIf(s -> s.getId().equals(id));
            sidebarController.removeSession(id);
            chatHistoryManager.deleteSession(id);
            chatService.deleteSession(id);
        }

        if (currentDeleted) {
            stopAndClearForCurrentSessionDeleted();

            if (sessions.isEmpty()) {
                onNewSession();
            } else {
                currentSession = null;
                String firstId = sessions.getFirst().getId();
                sidebarController.selectSession(firstId);
                onSwitchSession(firstId);
            }
        }

        chatHistoryManager.saveSessionIndex(sessions);
    }

    /**
     * 保存当前会话的消息到磁盘
     */
    private void saveCurrentSession() {
        saveSessionMessages(currentSession);
    }

    /**
     * 保存指定会话的消息到磁盘（后台流式完成时目标可能不是当前展示会话）。
     * 在 UI 线程做快照，后台线程序列化与落盘，避免阻塞 JavaFX Application Thread；
     * sessions / messages 后续仍会在 UI 线程被修改，必须 snapshot 防止 ConcurrentModificationException。
     */
    private void saveSessionMessages(ChatSession session) {
        if (session == null || session.getMessages().isEmpty()) {
            return;
        }
        final String sid = session.getId();
        final List<ChatMessage> messagesSnap = new ArrayList<>(session.getMessages());
        final List<ChatSession> sessionsSnap = new ArrayList<>(sessions);
        persistExecutor.execute(() -> {
            chatHistoryManager.saveSessionMessages(sid, messagesSnap);
            chatHistoryManager.saveSessionIndex(sessionsSnap);
        });
    }

    /**
     * 更新顶部标题为当前会话名称 + 副 meta（消息数 · 创建时间 · ctx）
     */
    private void updateTopTitle() {
        if (currentSession != null) {
            topTitleLabel.setText(currentSession.getTitle());
            if (topTitleMetaLabel != null) {
                int msgCount = currentSession.getMessages().size();
                String createdAt = currentSession.getCreatedAt() != null
                        ? currentSession.getCreatedAt()
                                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                        : "—";
                long ctxTokens = runtime != null && runtime.getTokenTracker() != null
                        ? runtime.getTokenTracker().getSessionTokens()
                        : 0;
                String ctxDisplay = ctxTokens >= 1000
                        ? String.format("%.1fk", ctxTokens / 1000.0)
                        : Long.toString(ctxTokens);
                // 设计稿：副 meta 含当前模型名（· N 条消息 · 模型名）
                String modelName = com.javaclaw.config.AgentConfig.getInstance().getModelName();
                topTitleMetaLabel.setText(msgCount + " 条消息 · 创建于 " + createdAt
                        + " · ctx " + ctxDisplay + " / 200k"
                        + (modelName == null || modelName.isBlank() ? "" : " · " + modelName));
            }
        }
    }

    /**
     * 添加欢迎消息气泡（不纳入消息列表持久化）
     */
    private void addWelcomeBubble() {
        String welcomeText = "你好！我是 JavaClaw 智能助手，拥有多智能体协作、任务规划和 Web 浏览能力。\n"
                + "复杂问题我会自动分解任务并委派给专家处理。\n"
                + "Web 智能体会自动管理 Playwright 浏览器。\n"
                + "点击输入框左侧「+」按钮可以添加图片或文档附件。\n"
                + "请问有什么可以帮助你的？";
        ChatMessage welcome = new ChatMessage(ChatMessage.Role.ASSISTANT, welcomeText);
        ChatMessageRowView row = messageRows.create(
                ChatMessageRowFactory.Variant.WELCOME,
                welcome,
                AgentConfig.AGENT_NAME,
                currentModelDisplayName(),
                "—",
                List.of(),
                this::enableImageZoom);
        sessionViewController.addMessage(row.root());
    }

    /**
     * 保存聊天记录到文件
     */
    private void saveChatHistory() {
        saveCurrentSession();
        // 同步保存智能体状态（Memory + PlanNotebook）
        if (currentSession != null) {
            chatService.saveSession(currentSession.getId());
        }
    }

    /**
     * 清除所有活动 UI 引用
     */
    /**
     * 终止当前活跃的流式调用（普通模式 + 规划模式 + 循环模式），递增代次使旧回调失效，重置 UI 状态。
     * 用户主动按"停止"或 Esc 时调用：附带输入框抖动作为"已取消"的视觉反馈。
     */
    private void stopActiveStream() {
        stopActiveStream(CancellationReason.USER_REQUEST, true, StopPolicy.PRESERVE_PARTIAL);
    }

    /**
     * 停止当前运行。
     *
     * <p>{@link StopPolicy#PRESERVE_PARTIAL} 等待唯一终态按事件队列顺序保存部分回复；
     * {@link StopPolicy#DISCARD_AND_INVALIDATE} 用于删除/清空/重建，先让旧代次失效并同步
     * 摘除 UI 状态，再取消底层运行。这样稍后到达的旧事件与终态不会重新保存已删除的数据。</p>
     *
     * @param reason 取消原因
     * @param showCancelFeedback true=显示输入框抖动反馈（用户主动取消）；
     *                           false=静默停止
     * @param policy 是否保留已经产生的部分回复
     */
    private void stopActiveStream(CancellationReason reason, boolean showCancelFeedback,
                                  StopPolicy policy) {
        if (rebuildInProgress.get() && policy == StopPolicy.PRESERVE_PARTIAL) {
            log.info("服务重建进行中，忽略停止请求（重建收尾会自行恢复输入）");
            return;
        }
        ActiveTurn turn = activeTurn;
        ConversationHandle handle = turn == null ? null : turn.handle;
        if (policy == StopPolicy.DISCARD_AND_INVALIDATE) {
            discardAndInvalidateActiveStream(reason, handle, showCancelFeedback);
            return;
        }
        if (handle != null) {
            if (handle.cancel(reason)) {
                if (showCancelFeedback) composerController.showInputError();
                return;
            }
            // 服务终态可能已经产生，只是 JavaFX 终态回调仍排在事件队列中。
            // 此时失效当前代次会把合法的完成结果与历史保存一起丢掉。
            if (handle.isTerminal()) {
                return;
            }
        }
        // 防御性复位：启动尚未返回句柄或底层拒绝取消时，仍不能把输入永久锁住。
        discardAndInvalidateActiveStream(reason, null, showCancelFeedback);
    }

    private void discardAndInvalidateActiveStream(CancellationReason reason,
                                                   ConversationHandle handle,
                                                   boolean showCancelFeedback) {
        invalidateBeforeCancel(() -> {
            streamGeneration++;
            activeTurn = null;
            streamingSession = null;
            AssistantMessageView abandoned = activeAssistantMessage;
            clearActiveReferences();
            disposeSuspendedStreamingNodes();
            if (abandoned != null) {
                abandoned.root().setVisible(false);
                abandoned.root().setManaged(false);
                abandoned.close();
            }
            showThinkingIndicator(false);
            composerController.setThinkingText("助手正在思考中...");
            thinkingPanel.endStreamCancelled();
            setInputEnabled(true);
        }, handle, reason);
        if (showCancelFeedback) {
            composerController.showInputError();
        }
    }

    /** 包级测试入口：破坏性停止必须先隔离旧代次，再触发会同步发送终态的句柄。 */
    static void invalidateBeforeCancel(Runnable invalidation,
                                       ConversationHandle handle,
                                       CancellationReason reason) {
        invalidation.run();
        if (handle != null) {
            try {
                handle.cancel(reason);
            } catch (RuntimeException cancelFailure) {
                log.warn("取消已失效的对话运行失败（旧回调已隔离）: {}",
                        cancelFailure.getMessage());
            }
        }
    }

    /**
     * 注册全局快捷键 — 等待 Scene 就绪后挂载到 Scene 的 accelerators 表
     * <p>使用 {@code SHORTCUT_DOWN}：macOS 上对应 Cmd，其他平台对应 Ctrl。
     */
    private void installGlobalShortcuts() {
        outerRoot.sceneProperty().addListener((obs, oldScene, scene) -> {
            if (scene == null) return;
            scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
                if (event.getCode() != KeyCode.ESCAPE) return;
                if (composerController.inputLength() > 0) {
                    composerController.clearInput();
                    event.consume();
                } else if (streamingActive) {
                    stopActiveStream();
                    event.consume();
                }
            });
            javafx.collections.ObservableMap<javafx.scene.input.KeyCombination, Runnable> acc = scene.getAccelerators();
            // Ctrl/Cmd + N → 新建会话
            acc.put(new javafx.scene.input.KeyCodeCombination(KeyCode.N,
                    javafx.scene.input.KeyCombination.SHORTCUT_DOWN), this::onNewSession);
            // Ctrl/Cmd + , → 打开设置
            acc.put(new javafx.scene.input.KeyCodeCombination(KeyCode.COMMA,
                    javafx.scene.input.KeyCombination.SHORTCUT_DOWN), this::openSettings);
            // Ctrl/Cmd + L → 清空对话
            acc.put(new javafx.scene.input.KeyCodeCombination(KeyCode.L,
                    javafx.scene.input.KeyCombination.SHORTCUT_DOWN), this::onClearHistory);
            // Ctrl/Cmd + \ → 切换侧栏可见性
            acc.put(new javafx.scene.input.KeyCodeCombination(KeyCode.BACK_SLASH,
                    javafx.scene.input.KeyCombination.SHORTCUT_DOWN), this::toggleSidebar);
            // Ctrl/Cmd + K → 聚焦输入框（命令面板的轻量替代）
            acc.put(new javafx.scene.input.KeyCodeCombination(KeyCode.K,
                    javafx.scene.input.KeyCombination.SHORTCUT_DOWN),
                    composerController::focusInput);
            // Ctrl/Cmd + M → 打开 MCP 服务器窗口
            acc.put(new javafx.scene.input.KeyCodeCombination(KeyCode.M,
                    javafx.scene.input.KeyCombination.SHORTCUT_DOWN), this::openMcpServers);
            // Ctrl/Cmd + / → 弹出快捷键帮助面板
            acc.put(new javafx.scene.input.KeyCodeCombination(KeyCode.SLASH,
                    javafx.scene.input.KeyCombination.SHORTCUT_DOWN), this::showShortcutsHelp);
            // Ctrl/Cmd + ? → 同上（Shift+/）
            acc.put(new javafx.scene.input.KeyCodeCombination(KeyCode.SLASH,
                    javafx.scene.input.KeyCombination.SHORTCUT_DOWN,
                    javafx.scene.input.KeyCombination.SHIFT_DOWN), this::showShortcutsHelp);
        });
    }

    /**
     * 切换侧栏可见性（Ctrl/Cmd + \ 或 汉堡按钮）
     */
    @FXML
    private void toggleSidebar() {
        javafx.scene.Node sidebar = sidebarController.getRoot();
        boolean visible = sidebar.isVisible();
        sidebar.setVisible(!visible);
        sidebar.setManaged(!visible);
        // 同步汉堡按钮可见性：侧栏隐藏时显示
        if (sidebarToggleBtn != null) {
            sidebarToggleBtn.setVisible(visible);
            sidebarToggleBtn.setManaged(visible);
        }
    }

    /**
     * 平台相关的快捷键修饰符提示文字（macOS 显示 ⌘，其他平台显示 Ctrl）
     */
    private String shortcutHint() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac") ? "⌘" : "Ctrl";
    }

    /**
     * 响应式：根据窗口宽度自动收缩/展开侧栏
     * <p>窗口 &lt; {@link #RESPONSIVE_BREAKPOINT_PX} 时自动隐藏；变宽后自动恢复
     * （仅当之前是自动隐藏的，不覆盖用户手动操作）。
     */
    private void installResponsiveLayout() {
        outerRoot.sceneProperty().addListener((obs, oldScene, scene) -> {
            if (scene == null) return;
            scene.widthProperty().addListener((wObs, oldW, newW) -> applyResponsiveSidebar(newW.doubleValue()));
            // 初始触发一次
            fx.dispatch(() -> applyResponsiveSidebar(scene.getWidth()));
        });
    }

    private void applyResponsiveSidebar(double width) {
        javafx.scene.Node sidebar = sidebarController.getRoot();
        if (width < RESPONSIVE_BREAKPOINT_PX && sidebar.isVisible()) {
            // 自动隐藏
            sidebar.setVisible(false);
            sidebar.setManaged(false);
            if (sidebarToggleBtn != null) {
                sidebarToggleBtn.setVisible(true);
                sidebarToggleBtn.setManaged(true);
            }
            sidebarAutoHidden = true;
        } else if (width >= RESPONSIVE_BREAKPOINT_PX && sidebarAutoHidden && !sidebar.isVisible()) {
            // 自动恢复（仅当之前是自动隐藏触发）
            sidebar.setVisible(true);
            sidebar.setManaged(true);
            if (sidebarToggleBtn != null) {
                sidebarToggleBtn.setVisible(false);
                sidebarToggleBtn.setManaged(false);
            }
            sidebarAutoHidden = false;
        }
    }

    /**
     * 弹出快捷键帮助面板（Ctrl/Cmd + / 或 ?）
     */
    private void showShortcutsHelp() {
        Alert dialog = new Alert(Alert.AlertType.INFORMATION);
        dialog.setTitle("键盘快捷键");
        dialog.setHeaderText("JavaClaw 快捷键");
        String shortcutKey = shortcutHint();
        StringBuilder sb = new StringBuilder();
        sb.append("【输入框】\n");
        sb.append("  Enter             发送消息\n");
        sb.append("  Shift + Enter     换行\n");
        sb.append("  ").append(shortcutKey).append(" + Enter     换行\n");
        sb.append("  ↑ (输入为空)       回填上一条消息\n");
        sb.append("  Esc               清空输入 / 取消生成\n\n");
        sb.append("【全局】\n");
        sb.append("  ").append(shortcutKey).append(" + N         新建会话\n");
        sb.append("  ").append(shortcutKey).append(" + L         清空当前对话\n");
        sb.append("  ").append(shortcutKey).append(" + K         聚焦输入框\n");
        sb.append("  ").append(shortcutKey).append(" + ,         打开设置\n");
        sb.append("  ").append(shortcutKey).append(" + \\         切换侧栏\n");
        sb.append("  ").append(shortcutKey).append(" + / 或 ?    显示本帮助\n");
        TextArea content = new TextArea(sb.toString());
        content.setEditable(false);
        content.setWrapText(false);
        content.setPrefRowCount(15);
        content.setPrefColumnCount(40);
        content.setStyle("-fx-font-family: " + com.javaclaw.ui.javafx.theme.FontManager.MONO_FONT_STACK + "; -fx-font-size: 12.5px;");
        dialog.getDialogPane().setContent(content);
        dialog.initOwner(outerRoot.getScene().getWindow());
        UIHelper.styleAlert(dialog);
        dialog.showAndWait();
    }

    /**
     * 查找当前会话中最近一条用户消息内容（用于 ↑ 键回填编辑）
     */
    private String findLastUserMessage() {
        if (currentSession == null) return null;
        List<ChatMessage> msgs = currentSession.getMessages();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            ChatMessage m = msgs.get(i);
            if (m.getRole() == ChatMessage.Role.USER && m.getContent() != null && !m.getContent().isBlank()) {
                return m.getContent();
            }
        }
        return null;
    }

    /**
     * 清空消息列表并释放每个 FXML 视图及其嵌套 Markdown 资源。
     *
     * <p>动态消息根节点携带自身的生命周期句柄。命中外层句柄后立即停止向下遍历，
     * 由该句柄按所有权关系关闭嵌套视图，避免重复销毁和全局监听器泄漏。</p>
     */
    private void disposeMessageList() {
        for (javafx.scene.Node node : sessionViewController.detachMessages()) {
            collectAndDisposeBubbles(node);
        }
    }

    private void collectAndDisposeBubbles(javafx.scene.Node node) {
        if (node.hasProperties()
                && node.getProperties().get("loopStatusView")
                instanceof LoopStatusView loopStatus) {
            loopStatus.close();
            return;
        }
        if (node.hasProperties()
                && node.getProperties().get("loopDecisionView")
                instanceof LoopDecisionView loopDecision) {
            loopDecision.close();
            return;
        }
        if (node.hasProperties()
                && node.getProperties().get("clarificationCardView")
                instanceof ClarificationCardView clarificationCard) {
            clarificationCard.close();
            return;
        }
        if (node.hasProperties()
                && node.getProperties().get("chatMessageRowView")
                instanceof ChatMessageRowView messageRow) {
            messageRow.close();
            return;
        }
        if (node.hasProperties()
                && node.getProperties().get("assistantMessageView")
                instanceof AssistantMessageView message) {
            message.close();
            return;
        }
        if (node.hasProperties()
                && node.getProperties().get("markdownBubble") instanceof MarkdownBubble bubble) {
            bubble.dispose();
            return;
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                collectAndDisposeBubbles(child);
            }
        }
    }

    private void clearActiveReferences() {
        AssistantMessageView message = activeAssistantMessage;
        MarkdownBubble reply = message == null ? null : message.reply();
        // 兜底移除「生成中」占位（正常完成/出错/循环中断等各路径终态）
        dismissGenPlaceholder();
        // 终态只负责提交后台排版，不等待解析或动画完成即可继续保存消息和恢复输入。
        if (reply != null) reply.finish();
        if (activeToolResultBlock != null) activeToolResultBlock.bubble().finish();
        if (activePlanAgentBlock != null) activePlanAgentBlock.bubble().finish();
        // 流式结束前，写入最终的"耗时 · Tokens"到消息头部
        if (message != null) {
            ActiveTurn turn = activeTurn;
            message.setMetadata(formatTurnMeta(currentTurnMetrics(),
                    turn == null ? DeliveryState.COMPLETE : turn.deliveryState));
        }
        activeAssistantMessage = null;
        activeToolName = null;
        activeToolResultBlock = null;
        activePlanAgentBlock = null;
        currentPlanAgentName = null;
        currentPlanAgentBuffer.setLength(0);
        displayedImagePaths.clear();
        if (message != null && message.root().getParent() == null) message.close();
    }

    private MarkdownBubble activeReply() {
        AssistantMessageView message = activeAssistantMessage;
        return message == null ? null : message.reply();
    }

    private String currentReplyText() {
        ActiveTurn turn = activeTurn;
        if (isPlanStream() && turn != null
                && turn.finalPlanDraft != null && !turn.finalPlanDraft.isBlank()) {
            return turn.finalPlanDraft;
        }
        if (isPlanStream() && activePlanAgentBlock != null
                && activePlanAgentBlock.bubble().getLength() > 0) {
            return activePlanAgentBlock.bubble().getText();
        }
        MarkdownBubble reply = activeReply();
        return reply != null && reply.getLength() > 0 ? reply.getText() : null;
    }

    private TurnMetrics currentTurnMetrics() {
        ActiveTurn turn = activeTurn;
        return turn == null ? new TurnMetrics(0, 0, 0) : turn.metrics();
    }

    private String formatMessageMeta(ChatMessage message) {
        if (message.getMetrics() == null) return "—";
        DeliveryState state = message.getDeliveryState();
        return formatTurnMeta(message.getMetrics(), state);
    }

    private String formatTurnMeta(TurnMetrics metrics, DeliveryState state) {
        String duration = metrics.durationMs() >= 1000
                ? String.format("%.1fs", metrics.durationMs() / 1000.0)
                : metrics.durationMs() + "ms";
        StringBuilder text = new StringBuilder(duration)
                .append(" · ")
                .append(String.format("%,d tok", metrics.totalTokens()));
        if (state == DeliveryState.CANCELLED) text.append(" · 已取消");
        else if (state == DeliveryState.FAILED) text.append(" · 失败");
        return text.toString();
    }

    private void showThinkingIndicator(boolean show) {
        composerController.setThinkingVisible(show);
    }

    private void setInputEnabled(boolean enabled) {
        streamingActive = !enabled;
        composerController.setStreaming(streamingActive);
        updateTopTitleStatusDot();
    }

    /**
     * 将顶部标题前的状态点按当前生成状态刷新（生成中=绿色脉动，空闲=灰色）。
     */
    private void updateTopTitleStatusDot() {
        if (topTitleStatusDot == null) return;
        topTitleStatusDot.getStyleClass().removeAll("status-idle", "status-executing");
        topTitleStatusDot.getStyleClass().add(streamingActive ? "status-executing" : "status-idle");
    }

    /**
     * 打开设置对话框（供顶栏按钮与系统托盘菜单复用）
     */
    public void openSettings() {
        openSettings(null);
    }

    /**
     * 打开设置对话框并可直达指定分类（如「嵌入模型」）；category 为 null 时打开默认分类。
     */
    public void openSettings(String category) {
        log.info("打开设置对话框{}", category != null ? "（直达：" + category + "）" : "");
        javafx.stage.Stage ownerStage = (javafx.stage.Stage) outerRoot.getScene().getWindow();
        SettingsView settingsView = new SettingsView(ownerStage,
                applicationKernel.current().agentSettingsPanels(),
                applicationKernel.current().siteCredentialPanels(),
                applicationKernel.current().mcpCenters(),
                applicationKernel.current().modelSettingsSections());
        settingsView.setOnModelConfigChanged(this::rebuildAgentService);
        settingsView.show(category);
    }

    /**
     * 处理 ConversationEvent.Usage：累加本次流式会话的真实输入/输出 token，
     * 刷新右侧「处理进度」面板的 TOKENS IN / TOKENS OUT / 费用 三联指标。
     *
     * <p>Usage 事件由 StreamEventHandler 在每次模型 ChatUsage 出现时发出，
     * 一次流式会话会触发多次（编排器首轮 + 中间轮 + 子智能体），故采用累加。</p>
     */
    private void updateThinkingPanelMetrics(ConversationEvent.Usage u) {
        ActiveTurn turn = activeTurn;
        if (turn == null) return;
        turn.inputTokens += Math.max(0, u.inputTokens());
        turn.outputTokens += Math.max(0, u.outputTokens());
        String model = AgentConfig.getInstance().getModelName();
        double cost = PricingTable.estimateCostCny(model,
                turn.inputTokens, turn.outputTokens);
        thinkingPanel.updateMetrics(turn.inputTokens, turn.outputTokens,
                TokenTracker.formatCostCny(cost));
    }

    /** 绑定 TokenTracker 回调到模式栏摘要（服务重建后需重新调用）。 */
    private void wireTokenTracker() {
        runtime.getTokenTracker().setOnTokensChanged(() -> {
            fx.dispatch(this::refreshStatusBar);
        });
        // 初始化显示
        refreshStatusBar();
        refreshLocalModeBadge();
    }

    /**
     * 根据当前 Provider 刷新"本地模式"徽标可见性
     */
    private void refreshLocalModeBadge() {
        if (localModeBadge == null) return;
        boolean isLocal = "Ollama".equalsIgnoreCase(AgentConfig.getInstance().getProviderType());
        localModeBadge.setVisible(isLocal);
        localModeBadge.setManaged(isLocal);
    }

    /**
     * 刷新底部 Token 徽标摘要 + Tooltip 详情
     *
     * <p>主显示「今日 + 会话」两个维度：今日累计来自 H2 token_usage_daily 表，
     * 应用重启后立即可见；会话仅在本次进程内累加。避免重启后状态栏一直显示 0 的体感问题。</p>
     */
    private void refreshStatusBar() {
        try {
            TokenTracker tracker = runtime.getTokenTracker();
            long sessionTokens = tracker.getSessionTokens();
            long todayTokens = tracker.getTodayTokens();
            long monthlyTokens = tracker.getMonthlyTokens();
            String monthlyCost = TokenTracker.formatCostCny(tracker.getMonthlyCostCny());
            TokenTracker.DailyUsage today = tracker.getTodayUsage();
            TokenTracker.DailyUsage month = tracker.getMonthlyUsage();
            String summary = "今日 " + TokenTracker.formatTokens(todayTokens)
                    + " · 会话 " + TokenTracker.formatTokens(sessionTokens)
                    + " · " + monthlyCost;
            String details = "今日累计：" + TokenTracker.formatTokens(todayTokens) + " tokens"
                    + "（输入 " + TokenTracker.formatTokens(today.input)
                    + " / 输出 " + TokenTracker.formatTokens(today.output) + "）\n"
                    + "本月累计：" + TokenTracker.formatTokens(monthlyTokens) + " tokens"
                    + "（输入 " + TokenTracker.formatTokens(month.input)
                    + " / 输出 " + TokenTracker.formatTokens(month.output) + "）\n"
                    + "本月成本：" + monthlyCost + "（估算，仅供参考）\n"
                    + "本次会话：" + TokenTracker.formatTokens(sessionTokens) + " tokens · 耗时 "
                    + TokenTracker.formatDuration(tracker.getSessionDurationSeconds()) + "\n"
                    + "点击可重置本次会话计数";
            modeBarController.updateTokenSummary(summary, details);
        } catch (Exception e) {
            log.debug("刷新 Token 徽标失败", e);
        }
        refreshSidebarBadges();
    }

    /**
     * 刷新侧边栏导航徽章（设计稿 sb-navrow badge）：
     * 技能中心 = 待审提案数；托管任务 = 进行中 + 待人工任务数。
     * 随状态栏 10 秒节拍刷新，开销极小（均为内存读取）。
     */
    private void refreshSidebarBadges() {
        try {
            int proposals = com.javaclaw.skill.curation.SkillProposalQueue.getInstance().pendingCount();
            sidebarController.updateSkillBadge(proposals);
            int activeTasks = (int) com.javaclaw.task.sdd.run.SddTaskManager.getInstance().list().stream()
                    .filter(t -> t.state == com.javaclaw.task.sdd.run.SddTaskState.RUNNING
                            || t.state == com.javaclaw.task.sdd.run.SddTaskState.NEEDS_HUMAN)
                    .count();
            sidebarController.updateTaskBadge(activeTasks);
        } catch (Exception e) {
            log.debug("刷新侧边栏徽章失败", e);
        }
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
        if (streamingActive) {
            stopActiveStream(CancellationReason.RUNTIME_REBUILD, false,
                    StopPolicy.DISCARD_AND_INVALIDATE);
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
                        knowledgeMenuController.reset();
                        knowledgeMenuController.refresh();
                        wireTokenTracker();
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
        wireEmbeddingHealth();
    }

    private void wireEmbeddingHealth() {
        if (embeddingHealthSubscription != null) {
            try { embeddingHealthSubscription.close(); }
            catch (Exception ignored) { }
            embeddingHealthSubscription = null;
        }
        if (embeddingHealthBadge == null || runtime == null) return;
        embeddingHealthSubscription = runtime.getEmbeddingGateway().addHealthListener(snapshot ->
                fx.dispatch(() -> {
                    if (embeddingHealthBadge == null) return;
                    String text = switch (snapshot.status()) {
                        case HEALTHY -> "嵌入：正常";
                        case CHECKING -> "嵌入：检查中";
                        case DEGRADED -> "嵌入：降级";
                        case UNAVAILABLE -> "嵌入：不可用";
                        case UNCONFIGURED -> "嵌入：未配置";
                    };
                    embeddingHealthBadge.setText(text);
                    embeddingHealthBadge.setTooltip(new Tooltip(snapshot.lastError() == null
                            ? text : text + "\n" + snapshot.lastError()));
                    boolean visible = snapshot.status()
                            != com.javaclaw.memory.embed.EmbeddingHealthStatus.HEALTHY;
                    embeddingHealthBadge.setVisible(visible);
                    embeddingHealthBadge.setManaged(visible);
                }));
    }

    /**
     * 取消运行中的循环（无活跃循环时为空操作）。
     *
     * <p>循环在 {@code boundedElastic} 后台跑、可跨会话存续，停止入口必须与
     * chat/plan 一样挂在 {@link #stopActiveStream} 上，否则 UI 复位后循环
     * 仍在后台烧 token 且单活跃闸拒绝新循环，用户无路可停。</p>
     */
    /**
     * 打开技能中心对话框
     */
    private void openSkillCenter() {
        log.info("打开技能中心");
        javafx.stage.Stage ownerStage = (javafx.stage.Stage) outerRoot.getScene().getWindow();
        SkillCenterView skillCenterView = new SkillCenterView(ownerStage);
        skillCenterView.show();
    }

    private void openMemoryCenter() {
        // 重建窗口内旧 MemoryService/EclipseStore 正被后台线程关闭：此刻构建视图会读到已关库
        // （加载抛异常或空数据），在陈旧视图里做的事实编辑/人格保存也会写进旧服务而丢失
        if (rejectIfRebuilding("打开记忆中心")) {
            return;
        }
        log.info("打开记忆中心");
        javafx.stage.Stage ownerStage = (javafx.stage.Stage) outerRoot.getScene().getWindow();
        new com.javaclaw.ui.javafx.memory.MemoryCenterView(
                ownerStage, chatService.getMemoryService(), runtime.getKnowledgeExpert(),
                windowToasts).show();
    }

    /**
     * 打开插件中心对话框
     */
    private void openPluginCenter() {
        log.info("打开插件中心");
        javafx.stage.Stage ownerStage = (javafx.stage.Stage) outerRoot.getScene().getWindow();
        pluginCenterViews.create(ownerStage).showAndWait();
    }

    /**
     * 打开 MCP 服务器独立窗口（侧边栏「MCP 服务器」入口 / ⌘M）
     */
    private void openMcpServers() {
        log.info("打开 MCP 服务器窗口");
        javafx.stage.Stage ownerStage = (javafx.stage.Stage) outerRoot.getScene().getWindow();
        applicationKernel.current().mcpCenters()
                .createWindow(ownerStage, this::rebuildAgentService)
                .show();
    }

    /**
     * 打开定时任务管理对话框
     */
    private void openScheduler() {
        log.info("打开定时任务管理");
        javafx.stage.Stage ownerStage = (javafx.stage.Stage) outerRoot.getScene().getWindow();
        ScheduleView scheduleView = new ScheduleView(ownerStage);
        scheduleView.show();
    }

    /**
     * 打开任务管理对话框。
     *
     * <p>通过 {@link ModeRegistry} 查找 id 为 "task" 的 {@link ActionMode} 并调用其 {@code open}，
     * 保证任务模式的实际触发路径跟其他模式一致、可被配置禁用或替换。</p>
     */
    @FXML
    private void openTaskManager() {
        log.info("打开任务管理");
        modeRegistry.getById("task")
                .filter(ActionMode.class::isInstance)
                .map(ActionMode.class::cast)
                .ifPresentOrElse(
                        ActionMode::open,
                        () -> log.warn("未注册任务模式（id=task）"));
    }

    @FXML
    private void openWorkflowCenter() {
        log.info("打开工作流中心");
        modeRegistry.getById("workflow-center")
                .filter(ActionMode.class::isInstance)
                .map(ActionMode.class::cast)
                .ifPresentOrElse(ActionMode::open,
                        () -> log.warn("未注册工作流中心模式（id=workflow-center）"));
    }

    /**
     * 打开任务创建对话框（由 /任务 命令触发）
     */
    private void openTaskCreation(String description) {
        log.info("打开任务创建对话框（SDD）");
        javafx.stage.Stage ownerStage = (javafx.stage.Stage) outerRoot.getScene().getWindow();
        new SddTaskView(ownerStage).showCreate(description);
    }

    /**
     * 打开知识库中心（全窗口视图，按设计稿重建）
     */
    private void openKnowledgeBase() {
        // 同 openMemoryCenter：重建窗口内 runtime.getKnowledgeExpert() 的 EclipseStore 正被关闭
        if (rejectIfRebuilding("打开知识库")) {
            return;
        }
        log.info("打开知识库中心");
        javafx.stage.Stage ownerStage = (javafx.stage.Stage) outerRoot.getScene().getWindow();
        var view = new com.javaclaw.ui.javafx.knowledge.KnowledgeCenterView(
                ownerStage, runtime.getKnowledgeExpert(),
                com.javaclaw.agent.ToolConfirmationManager.getPort(),
                this::rebuildAgentService,
                () -> openSettings("嵌入模型"),
                windowToasts);
        // 关闭后重建顶栏知识库菜单（文档增删 / 启用状态可能已变化）
        // 重建进行中跳过：此刻旧 runtime 正被后台线程关闭（EclipseStore 知识库已 close），
        // 读它会抛异常且刚建好的菜单也会被重建收尾清掉，收尾刷新是唯一补偿点。
        view.setOnHidden(() -> {
            if (!rebuildInProgress.get()) {
                knowledgeMenuController.refresh();
            }
        });
        view.show();
    }

    /**
     * 清空当前会话的对话历史
     */
    @FXML
    private void onClearHistory() {
        if (currentSession == null) return;
        // 全局快捷键 Ctrl/Cmd+L 不受输入禁用影响：重建窗口内会对已关闭服务清空/删检查点
        if (rejectIfRebuilding("清空对话")) {
            return;
        }
        // 流在别的会话后台运行时，清空会误伤其智能体上下文，先行拦截
        if (streamingActive && streamingSession != null && currentSession != streamingSession) {
            addStaticBubble(ChatMessage.Role.SYSTEM, "另一会话正在生成回复，完成后再清空本会话");
            return;
        }
        log.info("用户请求清空当前会话: {}", currentSession.getId());

        stopActiveStream(CancellationReason.USER_REQUEST, false,
                StopPolicy.DISCARD_AND_INVALIDATE);
        clearAllHistory();
        runtime.getTokenTracker().resetSession();
        chatService.deleteSession(currentSession.getId());
        disposeMessageList();
        currentSession.getMessages().clear();
        currentSession.setTitle("新的对话");

        // 清除附件
        composerController.clearAttachments();

        thinkingPanel.reset();

        // 更新 UI
        addWelcomeBubble();
        updateTopTitle();
        sidebarController.updateSessionTitle(currentSession.getId(), currentSession.getTitle());
        saveCurrentSession();
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
        if (streamingActive) {
            stopActiveStream(CancellationReason.RUNTIME_REBUILD, false,
                    StopPolicy.DISCARD_AND_INVALIDATE);
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
        saveCurrentSession();
        if (currentSession != null) {
            chatService.saveSession(currentSession.getId());
        }

        workspaceSwitchOverlayController.show("正在切换工作区...");

        // 在后台线程执行非 UI 操作（步骤 2-7）
        backgroundTasks.submit(TaskSpec.io("workspace-switch"), context -> {
            try {
                // 生命周期、配置重载、浏览器重绑定和失败回滚统一由应用内核完成。
                adoptRuntime(applicationKernel.switchWorkspace(targetWorkspaceId));

                // UI 更新回到 JavaFX 线程（步骤 8-12）
                fx.dispatch(() -> {
                    try {
                        // 8. 清空所有 UI 状态
                        disposeMessageList();
                        composerController.clearAttachments();
                        clearActiveReferences();
                        thinkingPanel.reset();

                        // 9. 清空并重新加载会话列表
                        sidebarController.clearSessions();
                        sessions.clear();
                        currentSession = null;
                        chatHistoryManager = new ChatHistoryManager();
                        loadSessions();

                        // 10. 重置知识库菜单（清除旧工作区的文档列表和选中状态）
                        knowledgeMenuController.reset();

                        // 10.5. 重新绑定 TokenTracker 回调（新工作区的追踪器）
                        wireTokenTracker();

                        // 11. 重置会话模式回对话（规划/循环同等对待：切工作区后残留循环
                        // chip 会把用户随手一问路由成最多几十轮的自动循环）
                        modeBarController.refreshModes("chat");
                        modeBarController.refreshWorkflows();

                        // 12. 更新侧边栏工作区下拉
                        sidebarController.refreshWorkspaceCombo();

                        // 12.5. 重新加载新工作区记忆的界面风格
                        themeMenuController.reloadFromWorkspace();

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
     * 聚合清理三条路径的对话历史。
     *
     * <p>原 {@code AgentService.clearHistory()} 的职责被拆到
     * {@link ChatService#clearHistory()} 和 {@link PlanModeService#clearHistory()}，
     * UI 侧统一由此方法串联触发。任务模式的状态由 TaskManager 自管，不在此处介入。</p>
     */
    private void clearAllHistory() {
        chatService.clearHistory();
        planModeService.clearHistory();
    }

    /**
     * 当前会话被删除后的停流与上下文清理（单删/批删共用，语义微妙勿散落拷贝）：
     * ① 兜底停掉「流活跃但无归属会话」的异常状态（归属被删会话的流已在调用方停掉）；
     * ② 后台流式期间智能体上下文归属流所在会话（不是被删的当前会话），此刻清空会掏空
     * 在跑流的工作记忆、且流结束时被保存回其会话造成永久丢失——仅在无后台流时清空，
     * 有后台流则留待 finishBackgroundStreamIfAway 统一把上下文对齐到当前展示会话。
     */
    private void stopAndClearForCurrentSessionDeleted() {
        if (streamingActive && streamingSession == null) {
            stopActiveStream(CancellationReason.SESSION_SWITCH, false,
                    StopPolicy.DISCARD_AND_INVALIDATE);
        }
        if (!(streamingActive && streamingSession != null)) {
            clearAllHistory();
        }
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
        AgentConfig config = AgentConfig.getInstance();
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
