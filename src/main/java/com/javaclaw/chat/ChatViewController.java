package com.javaclaw.chat;

import com.javaclaw.app.UIHelper;
import com.javaclaw.agent.AgentRuntime;
import com.javaclaw.agent.ChatService;
import com.javaclaw.agent.PlanModeService;
import com.javaclaw.agent.PricingTable;
import com.javaclaw.agent.TokenTracker;
import com.javaclaw.api.conversation.ActionMode;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.api.conversation.ConversationMode;
import com.javaclaw.api.conversation.ConversationRequest;
import com.javaclaw.api.conversation.Mode;
import com.javaclaw.api.conversation.ModeRegistry;
import com.javaclaw.browser.PlaywrightBrowserManager;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.config.SettingsView;
import com.javaclaw.ui.javafx.schedule.ScheduleView;
import com.javaclaw.ui.javafx.skill.SkillCenterView;
import com.javaclaw.ui.javafx.task.SddTaskView;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
// Orientation 已不再使用（浏览器独立窗口化）
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.fxmisc.richtext.InlineCssTextArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
public class ChatViewController {

    private static final Logger log = LoggerFactory.getLogger(ChatViewController.class);

    /** 支持内联显示的图片扩展名 */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "bmp", "webp");

    private final BorderPane outerRoot;
    private final BorderPane chatPane;
    /** chatPane 的 center StackPane（含 scrollPane + 空状态占位 + 浮动新消息按钮）；工作区切换时用作恢复目标，避免回退到只挂 scrollPane 而丢失叠加层 */
    private final StackPane chatCenter;
    private final VBox messageList;
    private final ScrollPane scrollPane;
    private final InlineCssTextArea inputField;
    private final Button sendButton;
    private final HBox typingIndicator;
    private Timeline typingAnimation;
    private final Label typingTextLabel;
    private final Label topTitleLabel;
    private Label topTitleStatusDot;
    private Label topTitleMetaLabel;
    private Label localModeBadge;
    /** 共享基础设施容器（模型工厂 / 记忆 / 知识 / token 追踪等） */
    private AgentRuntime runtime;
    /** 普通聊天模式服务入口 */
    private ChatService chatService;
    /** 规划模式服务入口 */
    private PlanModeService planModeService;
    /** 模式注册表（对话模式 / 动作模式 统一管理，支持运行期扩展） */
    private final ModeRegistry modeRegistry;
    private final PlaywrightBrowserManager browserManager;
    private ChatHistoryManager chatHistoryManager;
    private final SidebarView sidebarView;
    private final ThinkingPanelView thinkingPanel;

    /**
     * 聊天历史 JSON 持久化的串行执行器（单线程 + 守护线程）。
     * Why: 之前 saveCurrentSession 在 JavaFX Application Thread 同步落盘 2 个 JSON 文件，
     * 阻塞用户消息气泡的首帧渲染（用户感受到「回车后卡顿一秒才显示」）。
     * 单线程足以保证写入顺序、避免文件覆盖竞争。
     */
    private final java.util.concurrent.ExecutorService persistExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "chat-persist");
                t.setDaemon(true);
                return t;
            });

    // ==================== 多会话管理 ====================

    /** 所有会话列表（内存索引） */
    private final List<ChatSession> sessions = new ArrayList<>();

    /** 当前活动会话 */
    private ChatSession currentSession;

    // 浏览器已改为独立窗口，不再使用 browserVisible 标志

    // ==================== 附件相关 ====================

    /** 待发送的附件列表 */
    private final List<File> pendingAttachments = new ArrayList<>();

    /** 附件预览容器 */
    private final FlowPane attachmentPreviewPane;

    /** 聊天空状态提示 */
    private final VBox chatEmptyState;

    /** "↓ N 条新消息" 浮动按钮（用户上滑离开底部时显示） */
    private Button newMessagesButton;

    /** 当前未读新消息计数 */
    private int unreadNewCount = 0;

    /** 是否处于流式生成中（用于 Esc 取消逻辑） */
    private boolean streamingActive = false;

    /** 顶栏汉堡菜单按钮（仅当侧栏隐藏时显示） */
    private Button sidebarToggleBtn;

    /** 触发响应式自动收缩的窗口宽度阈值 */
    private static final double RESPONSIVE_BREAKPOINT_PX = 960.0;

    /** 当前侧栏自动隐藏状态（避免响应式监听重复触发） */
    private boolean sidebarAutoHidden = false;

    /** 判断"接近底部"的像素阈值 */
    private static final double NEAR_BOTTOM_THRESHOLD_PX = 100.0;

    // ==================== 流式输出的活动 UI 引用 ====================

    /** 当前正在流式填充的回复 Markdown 气泡 */
    private MarkdownBubble activeReplyBubble;

    /** 当前助手消息头部的"耗时 / Tokens"右侧 meta 标签（设计稿 4.2s · 1,208 tok） */
    private Label activeAssistantMetaLabel;

    /** 当前工具调用结果区域的容器 */
    private VBox activeToolResultsBox;

    /** 当前正在追加结果的工具名称（用于合并同一智能体的多次结果） */
    private String activeToolName;

    /** 当前正在追加结果的工具 Markdown 气泡（回复区域） */
    private MarkdownBubble activeToolResultBubble;

    /** 当前子智能体结果块的���层容器（包含回复） */
    private VBox activeSubResultBubble;

    /** 规划模式开关状态 */
    private boolean planModeEnabled = false;

    /** 规划模式下当前正在流式填充的智能体 Markdown 气泡 */
    private MarkdownBubble activePlanAgentBubble;

    /** 规划模式下当前发言智能体名称（用于在切换或流结束时为右侧面板生成摘要） */
    private String currentPlanAgentName;

    /** 规划模式下当前发言智能体的回复累积文本（用于摘要截断） */
    private final StringBuilder currentPlanAgentBuffer = new StringBuilder();

    /** 当前流式气泡的「采纳」按钮引用（流结束后启用） */
    private Button activeAdoptBtn;

    /**
     * 当前流式气泡关联的已持久化助手消息 holder。
     * 每次新开流式气泡时重新分配 holder 实例，旧按钮闭包仍持有各自 holder 不互相干扰。
     */
    private java.util.concurrent.atomic.AtomicReference<ChatMessage> activeAdoptTargetRef;

    /** 模式切换分段控件 */
    private ToggleButton chatModeBtn;
    private ToggleButton planModeBtn;

    /** 知识库多选菜单按钮 */
    private MenuButton knowledgeMenu;

    /** Token 用量摘要徽标（单一合并标签） */
    private Label tokenLabel;
    /** Token 徽标 Tooltip（内容在 refreshStatusBar 中刷新） */
    private Tooltip tokenSummaryTooltip;
    private Timeline statusBarClock;

    /** 当前流式输出中已显示的图片路径（防止重复显示） */
    private final Set<String> displayedImagePaths = new HashSet<>();

    /** 当前主回复气泡的外层容器（用于添加内联图片） */
    private VBox activeUnifiedBubble;

    /** 首个回复 chunk 到达前的「生成中」占位（三点动画），避免空白气泡 */
    private HBox activeGenPlaceholder;
    /** 占位三点动画（首 chunk 或终态时停止） */
    private Timeline activeGenPlaceholderAnim;

    /** 流式输出代次计数器（会话切换/删除时递增，使旧流回调失效） */
    private volatile int streamGeneration = 0;

    /** 正在流式生成回复的会话（可能不是当前展示的会话；null = 无活跃流） */
    private ChatSession streamingSession;

    /** 流式会话被切走时挂起的消息区节点（仍被流式回调实时更新），切回时原样恢复 */
    private final List<javafx.scene.Node> suspendedStreamingNodes = new ArrayList<>();

    /** 本次流式会话累计的真实输入 token（来自 ChatUsage，每次 startNewStream 重置） */
    private long streamUsageInputTokens = 0;

    /** 本次流式会话累计的真实输出 token */
    private long streamUsageOutputTokens = 0;

    /**
     * 构造聊天界面。
     *
     * <p>按模式注入三个独立服务：{@link AgentRuntime} 持有共享基础设施，
     * {@link ChatService} 承担普通模式，{@link PlanModeService} 承担规划模式。
     * 托管任务模式由 TaskManager 单例通过 runtime 自行拉取依赖，UI 侧通过 {@link ModeRegistry}
     * 中注册的 {@link com.javaclaw.api.conversation.ActionMode} 打开任务视图。</p>
     *
     * <p>{@link ModeRegistry} 是"可插拔模式"的核心：onSendMessage 会根据当前选中模式
     * 从注册表中取出 {@link ConversationMode} 并调用 {@code start}；未来扩展新模式
     * 只需实现 {@link Mode} 接口并注册到 registry，UI 层无需调整。</p>
     *
     * @param runtime           共享基础设施容器
     * @param chatService       普通模式门面
     * @param planModeService   规划模式门面
     * @param modeRegistry      模式注册表（已预先注册好 chat / plan / task 三种内置模式）
     * @param browserManager    浏览器管理器（供 UI 层面直接操作使用）
     */
    public ChatViewController(AgentRuntime runtime,
                              ChatService chatService,
                              PlanModeService planModeService,
                              ModeRegistry modeRegistry,
                              PlaywrightBrowserManager browserManager) {
        this.runtime = runtime;
        this.chatService = chatService;
        this.planModeService = planModeService;
        this.modeRegistry = modeRegistry;
        this.browserManager = browserManager;
        this.chatHistoryManager = new ChatHistoryManager();
        log.info("开始构建聊天界面");

        // ==================== 左侧侧边栏 ====================
        sidebarView = new SidebarView();
        sidebarView.setOnNewChat(this::onNewSession);
        sidebarView.setOnSwitchSession(this::onSwitchSession);
        sidebarView.setOnDeleteSession(this::onDeleteSession);
        sidebarView.setOnBatchDeleteSessions(this::onBatchDeleteSessions);
        sidebarView.setOnOpenSettings(this::openSettings);
        sidebarView.setOnOpenSkillCenter(this::openSkillCenter);
        sidebarView.setOnOpenMemoryCenter(this::openMemoryCenter);
        sidebarView.setOnOpenScheduler(this::openScheduler);
        sidebarView.setOnOpenKnowledgeBase(this::openKnowledgeBase);
        sidebarView.setOnOpenTaskManager(this::openTaskManager);
        sidebarView.setOnOpenMcp(this::openMcpServers);
        sidebarView.setOnOpenPluginCenter(this::openPluginCenter);
        sidebarView.setOnSwitchWorkspace(this::onSwitchWorkspace);

        // ==================== 顶部标题栏（设计稿：状态点 + 标题 + 副 meta） ====================
        topTitleLabel = new Label("JavaClaw 工作区");
        topTitleLabel.getStyleClass().add("chat-top-title");

        // 标题前的状态点（绿色=执行中，灰色=空闲）
        topTitleStatusDot = new Label("●");
        topTitleStatusDot.getStyleClass().addAll("chat-top-status-dot", "status-idle");

        HBox titleLine = new HBox(8, topTitleStatusDot, topTitleLabel);
        titleLine.setAlignment(Pos.CENTER_LEFT);

        // 副标题：N 条消息 · 创建于 HH:MM · ctx N / 200k
        topTitleMetaLabel = new Label("—");
        topTitleMetaLabel.getStyleClass().add("chat-top-meta");

        VBox titleBlock = new VBox(2, titleLine, topTitleMetaLabel);
        titleBlock.setAlignment(Pos.CENTER_LEFT);

        // 汉堡菜单按钮（仅在侧栏隐藏时显示，作为重新打开侧栏的入口）
        sidebarToggleBtn = new Button("☰");
        sidebarToggleBtn.getStyleClass().add("sidebar-toggle-btn");
        sidebarToggleBtn.setTooltip(new Tooltip("显示侧栏 (" + shortcutHint() + " + \\)"));
        sidebarToggleBtn.setOnAction(e -> toggleSidebar());
        sidebarToggleBtn.setVisible(false);
        sidebarToggleBtn.setManaged(false);

        // 本地模式徽标 — Provider=Ollama 时显示
        localModeBadge = new Label("本地模式");
        localModeBadge.getStyleClass().add("local-mode-badge");
        localModeBadge.setTooltip(new Tooltip("当前使用本地 Ollama 模型，对话数据不出本机"));
        localModeBadge.setVisible(false);
        localModeBadge.setManaged(false);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // 模式切换 chips（设计稿：位于输入区提示行左侧；对话 / 研讨 / 托管任务）
        ToggleGroup modeGroup = new ToggleGroup();
        chatModeBtn = new ToggleButton("对话");
        chatModeBtn.setToggleGroup(modeGroup);
        chatModeBtn.getStyleClass().add("jc-mode-chip");
        chatModeBtn.setSelected(true);

        planModeBtn = new ToggleButton("研讨");
        planModeBtn.setToggleGroup(modeGroup);
        planModeBtn.getStyleClass().add("jc-mode-chip");

        // 防止取消选中；对话模式与规划模式互斥
        chatModeBtn.setOnAction(e -> {
            if (!chatModeBtn.isSelected()) chatModeBtn.setSelected(true);
            else { planModeEnabled = false; log.info("切换到对话模式"); }
        });
        planModeBtn.setOnAction(e -> {
            if (!planModeBtn.isSelected()) planModeBtn.setSelected(true);
            else { planModeEnabled = true; log.info("切换到规划模式"); }
        });

        // 托管任务 chip：非会话模式，点击直接打开任务视图（不改变对话/规划选中态）
        Button taskModeChip = new Button("托管任务");
        taskModeChip.getStyleClass().add("jc-mode-chip");
        taskModeChip.setOnAction(e -> openTaskManager());

        // 知识库多选菜单按钮（带图标 + "N 已选"内置徽章）
        knowledgeMenu = new MenuButton("📖 知识库");
        knowledgeMenu.getStyleClass().add("knowledge-menu");
        Tooltip knowledgeTip = new Tooltip("选择知识库中的文档作为对话参考");
        knowledgeMenu.setTooltip(knowledgeTip);
        // 菜单展开时隐藏 Tooltip，避免遮挡
        knowledgeMenu.setOnShowing(e -> {
            knowledgeTip.hide();
            knowledgeMenu.setTooltip(null);
            rebuildKnowledgeMenu();
        });
        knowledgeMenu.setOnHidden(e -> knowledgeMenu.setTooltip(knowledgeTip));

        Button clearButton = new Button("🗑 清空");
        clearButton.getStyleClass().add("clear-button");
        clearButton.setOnAction(e -> onClearHistory());

        // 界面风格切换菜单（设计稿：当前主题色块 + 「风格」 + 下拉五主题预览）
        MenuButton themeMenuBtn = buildThemeMenu();

        // 顶栏方形图标按钮（设计稿 top-icon：▦ 托管任务 / ⚙ 设置）
        Button taskIconBtn = new Button("▦");
        taskIconBtn.getStyleClass().add("top-icon-btn");
        taskIconBtn.setTooltip(new Tooltip("托管任务"));
        taskIconBtn.setOnAction(e -> openTaskManager());

        Button settingsIconBtn = new Button("⚙");
        settingsIconBtn.getStyleClass().add("top-icon-btn");
        settingsIconBtn.setTooltip(new Tooltip("设置（" + shortcutHint() + " + ,）"));
        settingsIconBtn.setOnAction(e -> openSettings());

        HBox topBar = new HBox(12, sidebarToggleBtn, titleBlock, localModeBadge, spacer,
                knowledgeMenu, themeMenuBtn, taskIconBtn, settingsIconBtn, clearButton);
        topBar.getStyleClass().add("chat-top-bar");
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(10, 20, 10, 20));
        HBox.setHgrow(titleBlock, Priority.NEVER);

        // Token 用量摘要徽标（合并今日 + 会话 tokens + 本月成本，Tooltip 展开详情）
        tokenLabel = new Label("今日 0 · 会话 0 · ¥0.00");
        tokenLabel.getStyleClass().add("token-counter-label");
        tokenSummaryTooltip = new Tooltip();
        tokenSummaryTooltip.setShowDelay(Duration.millis(250));
        tokenLabel.setTooltip(tokenSummaryTooltip);
        tokenLabel.setOnMouseClicked(e -> {
            runtime.getTokenTracker().resetSession();
            refreshStatusBar();
        });
        wireTokenTracker();

        // ==================== 中部消息区域 ====================
        messageList = new VBox(10);
        messageList.getStyleClass().add("message-list");
        messageList.setPadding(new Insets(16, 12, 16, 12));

        scrollPane = new ScrollPane(messageList);
        scrollPane.getStyleClass().add("message-scroll");
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        // 气泡内嵌 WebView / InlineCssTextArea 会吞掉滚轮事件，导致鼠标悬停在气泡上时列表无法滚动。
        // 在 ScrollPane 的 capture 阶段抢先处理所有向下路由的 ScrollEvent，确保列表始终响应滚轮。
        // 所有气泡都做过高度自适应（USE_PREF_SIZE / autoFitHeight），无需内部滚动，抢占是安全的。
        scrollPane.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, event -> {
            double deltaY = event.getDeltaY();
            if (deltaY == 0) return;
            double viewportH = scrollPane.getViewportBounds() != null
                    ? scrollPane.getViewportBounds().getHeight() : 0;
            double contentH = messageList.getHeight();
            double scrollable = contentH - viewportH;
            if (scrollable <= 0) return;
            double stepV = deltaY / scrollable;
            double newV = scrollPane.getVvalue() - stepV;
            scrollPane.setVvalue(Math.max(0, Math.min(1, newV)));
            event.consume();
        });

        // 智能自动滚动：用户接近底部时跟随，否则保持当前位置并通过浮动按钮提示
        messageList.heightProperty().addListener((obs, oldVal, newVal) -> {
            if (isNearBottom()) {
                Timeline scrollAnim = new Timeline(
                        new KeyFrame(Duration.millis(150),
                                new KeyValue(scrollPane.vvalueProperty(), 1.0))
                );
                scrollAnim.play();
            }
        });

        // 用户主动滚动接近底部时，重置未读计数并隐藏浮动按钮
        scrollPane.vvalueProperty().addListener((obs, oldVal, newVal) -> {
            if (isNearBottom()) {
                resetUnreadCount();
            }
        });

        // 监听新消息加入：若用户已上滑离开底部，累计未读数；列表清空时重置
        messageList.getChildren().addListener((javafx.collections.ListChangeListener<javafx.scene.Node>) c -> {
            while (c.next()) {
                if (messageList.getChildren().isEmpty()) {
                    resetUnreadCount();
                } else if (c.wasAdded() && !isNearBottom()) {
                    incrementUnreadCount(c.getAddedSize());
                }
            }
        });

        // 打字指示器（三点动画 + 文字）
        Label dot1 = new Label("●");
        Label dot2 = new Label("●");
        Label dot3 = new Label("●");
        dot1.getStyleClass().add("typing-dot");
        dot2.getStyleClass().add("typing-dot");
        dot3.getStyleClass().add("typing-dot");
        typingTextLabel = new Label("助手正在思考中...");
        typingTextLabel.getStyleClass().add("thinking-label");
        typingIndicator = new HBox(4, dot1, dot2, dot3, typingTextLabel);
        typingIndicator.setAlignment(Pos.CENTER_LEFT);
        typingIndicator.setPadding(new Insets(6, 24, 0, 24));
        typingIndicator.setVisible(false);
        typingIndicator.setManaged(false);

        typingAnimation = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(dot1.opacityProperty(), 0.3),
                        new KeyValue(dot2.opacityProperty(), 0.3),
                        new KeyValue(dot3.opacityProperty(), 0.3)),
                new KeyFrame(Duration.millis(200),
                        new KeyValue(dot1.opacityProperty(), 1.0)),
                new KeyFrame(Duration.millis(400),
                        new KeyValue(dot1.opacityProperty(), 0.3),
                        new KeyValue(dot2.opacityProperty(), 1.0)),
                new KeyFrame(Duration.millis(600),
                        new KeyValue(dot2.opacityProperty(), 0.3),
                        new KeyValue(dot3.opacityProperty(), 1.0)),
                new KeyFrame(Duration.millis(800),
                        new KeyValue(dot3.opacityProperty(), 0.3))
        );
        typingAnimation.setCycleCount(Animation.INDEFINITE);

        // ==================== 附件预览区域（初始隐藏） ====================
        attachmentPreviewPane = new FlowPane(10, 10);
        attachmentPreviewPane.getStyleClass().add("attachment-preview-pane");
        attachmentPreviewPane.setPadding(new Insets(8, 20, 8, 20));
        attachmentPreviewPane.setVisible(false);
        attachmentPreviewPane.setManaged(false);

        // ==================== 底部输入区域（RichTextFX 多行输入） ====================
        inputField = new InlineCssTextArea();
        inputField.setWrapText(true);
        inputField.getStyleClass().add("input-field");
        // 单行舒适高度 = 14px 字体行高(~20) + 上下 padding(12+12) + 边框(1.5*2) ≈ 47，预留至 56
        inputField.setPrefHeight(56);
        inputField.setMinHeight(56);
        // 最多 ~10 行；超过自动出现垂直滚动条
        inputField.setMaxHeight(240);

        // 键盘契约：
        //   Enter            → 发送
        //   Shift+Enter      → 换行
        //   Ctrl/Cmd+Enter   → 换行（macOS 习惯）
        //   Esc              → 取消正在进行的流式生成 / 清空输入框
        //   ↑（输入框空时）   → 回填上一条用户消息以编辑重发
        inputField.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER) {
                if (e.isShiftDown() || e.isShortcutDown()) {
                    inputField.insertText(inputField.getCaretPosition(), "\n");
                } else {
                    onSendMessage();
                }
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                // Esc：优先清空输入框；输入为空且正在生成时取消流式生成
                if (inputField.getLength() > 0) {
                    inputField.clear();
                    e.consume();
                } else if (streamingActive) {
                    stopActiveStream();
                    e.consume();
                }
            } else if (e.getCode() == KeyCode.UP && inputField.getLength() == 0) {
                String last = findLastUserMessage();
                if (last != null) {
                    inputField.replaceText(0, 0, last);
                    inputField.moveTo(inputField.getLength());
                    e.consume();
                }
            }
        });

        // 动态调整输入框高度：content + 上下 padding(24) + 边框(3) + 安全垫片
        inputField.totalHeightEstimateProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                double h = Math.max(56, Math.min(240, newVal.doubleValue() + 28));
                inputField.setPrefHeight(h);
            }
        });

        // 占位提示文字
        Label placeholder = new Label("给 JavaClaw 发消息…  按 Enter 发送，Shift+Enter 换行，Esc 取消生成");
        placeholder.getStyleClass().add("input-placeholder");
        placeholder.setMouseTransparent(true);
        placeholder.visibleProperty().bind(
                Bindings.createBooleanBinding(
                        () -> inputField.getLength() == 0,
                        inputField.lengthProperty()
                )
        );
        StackPane inputWrapper = new StackPane(inputField, placeholder);
        // 占位提示与正文同为顶部左对齐，二者位置重合，避免输入时文字相对占位符“跳动”的不协调感
        StackPane.setAlignment(placeholder, Pos.TOP_LEFT);
        placeholder.setPadding(new Insets(14, 0, 0, 21));
        HBox.setHgrow(inputWrapper, Priority.ALWAYS);

        // 附件按钮
        Button attachButton = new Button("+");
        attachButton.getStyleClass().add("attach-button");
        attachButton.setTooltip(new Tooltip("添加图片或文档附件"));
        attachButton.setOnAction(e -> onAddAttachment());

        sendButton = new Button("发送");
        sendButton.getStyleClass().add("send-button");
        // 同一个按钮在空闲态执行发送、在流式态执行中断；具体动作由 streamingActive 决定
        sendButton.setOnAction(e -> onSendOrStop());
        UIHelper.addPressEffect(sendButton);

        HBox inputBar = new HBox(12, attachButton, inputWrapper, sendButton);
        inputBar.getStyleClass().add("input-bar");
        // 多行输入框会随内容向上增高，按钮锚定底部，保持与输入框最后一行对齐
        inputBar.setAlignment(Pos.BOTTOM_CENTER);
        inputBar.setFillHeight(false);
        inputBar.setPadding(new Insets(12, 20, 4, 20));

        // 底部状态栏：只保留一个紧凑的摘要徽标（Tooltip 展开今日/本月/耗时）
        Region tokenSpacer = new Region();
        HBox.setHgrow(tokenSpacer, Priority.ALWAYS);
        HBox tokenRow = new HBox(6, tokenSpacer, tokenLabel);
        tokenRow.setAlignment(Pos.CENTER_RIGHT);
        tokenRow.setPadding(new Insets(0, 24, 4, 24));

        // 每 10 秒刷新一次耗时显示
        statusBarClock = new Timeline(new KeyFrame(Duration.seconds(10), e -> refreshStatusBar()));
        statusBarClock.setCycleCount(Animation.INDEFINITE);
        statusBarClock.play();

        // 底部键盘提示条（设计稿：本次对话 · N tok · ¥0.00 | ↵ 发送 ⇧↵ 换行 Esc 取消）
        Label hintConvo = new Label("本次对话");
        hintConvo.getStyleClass().add("composer-hint-label");
        Label hintSep1 = new Label("·");
        hintSep1.getStyleClass().add("composer-hint-sep");
        Label hintEnter = new Label("↵");
        hintEnter.getStyleClass().add("kbd-chip");
        Label hintEnterText = new Label("发送");
        hintEnterText.getStyleClass().add("composer-hint-label");
        Label hintShiftEnter = new Label("⇧↵");
        hintShiftEnter.getStyleClass().add("kbd-chip");
        Label hintShiftEnterText = new Label("换行");
        hintShiftEnterText.getStyleClass().add("composer-hint-label");
        Label hintEsc = new Label("Esc");
        hintEsc.getStyleClass().add("kbd-chip");
        Label hintEscText = new Label("取消");
        hintEscText.getStyleClass().add("composer-hint-label");
        Region hintSpacer = new Region();
        HBox.setHgrow(hintSpacer, Priority.ALWAYS);
        // 模式 chips 置于提示行左侧（设计稿 composer-hints：对话/研讨/托管任务 + 右侧键位提示）
        HBox modeChips = new HBox(6, chatModeBtn, planModeBtn, taskModeChip);
        modeChips.setAlignment(Pos.CENTER_LEFT);
        HBox shortcutHintRow = new HBox(8,
                modeChips,
                hintConvo, hintSep1, tokenLabel,
                hintSpacer,
                hintEnter, hintEnterText,
                hintShiftEnter, hintShiftEnterText,
                hintEsc, hintEscText);
        shortcutHintRow.setAlignment(Pos.CENTER_LEFT);
        shortcutHintRow.setPadding(new Insets(4, 24, 6, 24));
        shortcutHintRow.getStyleClass().add("composer-hint-row");

        VBox bottomArea = new VBox(4, typingIndicator, attachmentPreviewPane, inputBar, shortcutHintRow);
        bottomArea.setPadding(new Insets(0, 0, 2, 0));

        // ==================== 聊天空状态 ====================
        Label emptyIcon = new Label("\uD83D\uDCAC");
        emptyIcon.getStyleClass().add("empty-state-icon");
        Label emptyTitle = new Label("开始新对话");
        emptyTitle.getStyleClass().add("empty-state-text");
        Label emptyHint = new Label("输入消息开始与智能体对话\n支持发送图片、文档等附件");
        emptyHint.getStyleClass().addAll("settings-hint", "empty-state-hint");
        emptyHint.setWrapText(true);
        emptyHint.setMaxWidth(300);
        chatEmptyState = new VBox(12, emptyIcon, emptyTitle, emptyHint);
        chatEmptyState.setAlignment(Pos.CENTER);
        chatEmptyState.setMouseTransparent(true);
        chatEmptyState.visibleProperty().bind(
                Bindings.isEmpty(messageList.getChildren()));
        chatEmptyState.managedProperty().bind(chatEmptyState.visibleProperty());

        // 新消息浮动按钮（用户上滑离开底部时显示）
        newMessagesButton = new Button("↓ 0 条新消息");
        newMessagesButton.getStyleClass().add("new-messages-pill");
        newMessagesButton.setVisible(false);
        newMessagesButton.setManaged(false);
        newMessagesButton.setOnAction(e -> {
            scrollPane.setVvalue(1.0);
            resetUnreadCount();
        });
        StackPane.setAlignment(newMessagesButton, Pos.BOTTOM_CENTER);
        StackPane.setMargin(newMessagesButton, new Insets(0, 0, 16, 0));

        this.chatCenter = new StackPane(scrollPane, chatEmptyState, newMessagesButton);

        // ==================== 右侧思考进度面板 ====================
        thinkingPanel = new ThinkingPanelView();

        // ==================== 组装聊天面板 ====================
        chatPane = new BorderPane();
        chatPane.getStyleClass().add("chat-root");
        chatPane.setTop(topBar);
        chatPane.setCenter(chatCenter);
        chatPane.setBottom(bottomArea);
        chatPane.setRight(thinkingPanel.getRoot());

        // 最外层布局：左侧侧边栏 + 右侧聊天区域
        this.outerRoot = new BorderPane();
        outerRoot.getStyleClass().add("chat-root");
        outerRoot.setLeft(sidebarView.getRoot());
        outerRoot.setCenter(chatPane);

        // 加载会话列表
        loadSessions();

        // 注册全局快捷键（Scene 就绪后挂载）
        installGlobalShortcuts();

        // 响应式布局监听
        installResponsiveLayout();

        // 首次使用引导
        showFirstUseGuidanceIfNeeded();

        // 注册交互式循环检测处理器（允许用户决定继续或终止）
        chatService.setLoopInteractiveHandler(this::showLoopInteractionBubble);

        // 初始化本地模式徽标
        refreshLocalModeBadge();

        log.info("聊天界面构建完成（含浏览器面板 + 多会话管理）");
    }

    /**
     * 交互式循环检测气泡 — 让用户决定是继续还是终止
     *
     * <p>该方法在 Reactor 线程上被调用，必须切回 JavaFX 线程更新 UI。
     * 用户点击"继续"或"终止"后回调 {@code decision}，超时由 Hook 层处理。</p>
     */
    private void showLoopInteractionBubble(String toolName, int repeats,
                                           java.util.function.Consumer<Boolean> decision) {
        Platform.runLater(() -> {
            HBox bubble = new HBox(12);
            bubble.setAlignment(Pos.CENTER_LEFT);
            bubble.getStyleClass().add("message-system");
            bubble.setPadding(new Insets(12));

            Label text = new Label(String.format(
                    "检测到工具 [%s] 连续 %d 次相似调用，已暂停。是否继续执行？",
                    toolName, repeats));
            text.setWrapText(true);
            HBox.setHgrow(text, Priority.ALWAYS);

            Button continueBtn = new Button("继续");
            continueBtn.getStyleClass().add("mode-segment-btn");
            Button stopBtn = new Button("终止");
            stopBtn.getStyleClass().add("mode-segment-btn");

            continueBtn.setOnAction(e -> resolveLoopDecision(true, decision, bubble, continueBtn, stopBtn));
            stopBtn.setOnAction(e -> resolveLoopDecision(false, decision, bubble, continueBtn, stopBtn));

            bubble.getChildren().addAll(text, continueBtn, stopBtn);

            HBox row = new HBox(bubble);
            row.setAlignment(Pos.CENTER);
            messageList.getChildren().add(row);
        });
    }

    /** 分派用户决定，禁用按钮避免重复点击，然后从消息列表移除气泡 */
    private void resolveLoopDecision(boolean continueRunning,
                                     java.util.function.Consumer<Boolean> decision,
                                     HBox bubble, Button... buttons) {
        for (Button b : buttons) b.setDisable(true);
        try {
            decision.accept(continueRunning);
        } catch (Exception ex) {
            log.warn("循环检测决定回调异常", ex);
        }
        // 将气泡降级为纯文本记录用户选择
        bubble.getChildren().setAll(new Label(continueRunning ? "已选择继续执行" : "已选择终止"));
    }

    /**
     * 获取最外层根节点（用于 Scene 构建）
     */
    public BorderPane getOuterRoot() {
        return outerRoot;
    }

    // ==================== 附件处理 ====================

    /**
     * 打开文件选择器添加附件
     */
    private void onAddAttachment() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("选择附件");

        // 添加文件过滤器
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("所有支持的文件",
                        "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.webp",
                        "*.txt", "*.md", "*.csv", "*.json", "*.xml", "*.html", "*.css",
                        "*.java", "*.py", "*.js", "*.ts", "*.c", "*.cpp", "*.h", "*.go", "*.rs",
                        "*.log", "*.yaml", "*.yml", "*.pdf"),
                new FileChooser.ExtensionFilter("图片文件",
                        "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.webp"),
                new FileChooser.ExtensionFilter("文档文件",
                        "*.txt", "*.md", "*.csv", "*.json", "*.xml", "*.pdf",
                        "*.java", "*.py", "*.js", "*.html"),
                new FileChooser.ExtensionFilter("所有文件", "*.*")
        );

        javafx.stage.Stage stage = (javafx.stage.Stage) outerRoot.getScene().getWindow();
        List<File> selectedFiles = fileChooser.showOpenMultipleDialog(stage);
        if (selectedFiles != null && !selectedFiles.isEmpty()) {
            for (File file : selectedFiles) {
                if (!pendingAttachments.contains(file)) {
                    pendingAttachments.add(file);
                    log.info("已添加附件: {}", file.getName());
                }
            }
            updateAttachmentPreview();
        }
    }

    /**
     * 更新附件预览区域
     */
    private void updateAttachmentPreview() {
        attachmentPreviewPane.getChildren().clear();

        if (pendingAttachments.isEmpty()) {
            attachmentPreviewPane.setVisible(false);
            attachmentPreviewPane.setManaged(false);
            return;
        }

        attachmentPreviewPane.setVisible(true);
        attachmentPreviewPane.setManaged(true);

        for (File file : pendingAttachments) {
            attachmentPreviewPane.getChildren().add(createAttachmentPreviewItem(file));
        }
    }

    /**
     * 创建单个附件预览项
     *
     * <p>图片文件显示缩略图，其他文件显示文件名图标。
     * 每个预览项右上角有删除按钮。</p>
     */
    private StackPane createAttachmentPreviewItem(File file) {
        VBox contentBox = new VBox(2);
        contentBox.setAlignment(Pos.CENTER);
        contentBox.getStyleClass().add("attachment-item");

        if (ChatMessage.isImageFile(file)) {
            // 图片缩略图
            ImageView thumb = new ImageView(new Image(file.toURI().toString(), 60, 60, true, true));
            thumb.setFitWidth(60);
            thumb.setFitHeight(60);
            thumb.setPreserveRatio(true);
            contentBox.getChildren().add(thumb);
        } else {
            // 文件图标
            String ext = ChatMessage.getFileExtension(file).toUpperCase();
            Label iconLabel = new Label(ext.isEmpty() ? "FILE" : ext);
            iconLabel.getStyleClass().add("attachment-icon");
            contentBox.getChildren().add(iconLabel);
        }

        // 文件名
        String displayName = file.getName();
        if (displayName.length() > 12) {
            displayName = displayName.substring(0, 9) + "...";
        }
        Label nameLabel = new Label(displayName);
        nameLabel.getStyleClass().add("attachment-name");
        nameLabel.setTooltip(new Tooltip(file.getName()));
        contentBox.getChildren().add(nameLabel);

        // 删除按钮
        Button removeBtn = new Button("×");
        removeBtn.getStyleClass().add("attachment-remove-btn");
        removeBtn.setOnAction(e -> {
            pendingAttachments.remove(file);
            updateAttachmentPreview();
            log.info("已移除附件: {}", file.getName());
        });

        StackPane itemPane = new StackPane(contentBox, removeBtn);
        StackPane.setAlignment(removeBtn, Pos.TOP_RIGHT);
        return itemPane;
    }

    // ==================== 消息发送 ====================

    /**
     * 发送消息事件处理（多智能体流式模式，支持附件）
     */
    private void onSendMessage() {
        String userText = inputField.getText().trim();
        if (userText.isEmpty() && pendingAttachments.isEmpty()) {
            return;
        }

        // /任务 命令：打开任务创建对话框
        if (userText.startsWith("/任务")) {
            String taskDesc = userText.substring(3).trim();
            inputField.clear();
            openTaskCreation(taskDesc);
            return;
        }

        // /demo 命令：播放离线演示，无需 API Key
        if (userText.equals("/demo") || userText.equals("/演示")) {
            inputField.clear();
            addUserBubbleWithAttachments(userText, List.of());
            setInputEnabled(false);
            streamingSession = currentSession;
            showThinkingIndicator(true);
            streamUsageInputTokens = 0;
        streamUsageOutputTokens = 0;
        thinkingPanel.startNewStream();
            createStreamingBubble();
            runDemoPlayback(streamGeneration);
            return;
        }

        // /诊断 斜杠命令：打开诊断面板
        if (userText.equals("/诊断") || userText.equals("/diagnostics")) {
            inputField.clear();
            Stage owner = (Stage) outerRoot.getScene().getWindow();
            com.javaclaw.diagnostics.DiagnosticsView.open(owner);
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

        log.info("用户发送消息: {}，附件数: {}", userText, pendingAttachments.size());

        // 复制附件列表用于发送（发送后清空预览）
        List<File> attachmentsToSend = new ArrayList<>(pendingAttachments);

        // 立即同步执行的轻量 UI 反馈：用户气泡入场景图、清输入框、禁用输入、思考指示器。
        // 这些 setter 不会自己触发渲染，仍要等当前事件处理器返回后下一次脉冲才能上屏。
        addUserBubbleWithAttachments(userText, attachmentsToSend);
        inputField.clear();
        pendingAttachments.clear();
        updateAttachmentPreview();
        setInputEnabled(false);
        streamingSession = currentSession;  // 记录流所属会话（支持切走后后台继续）
        showThinkingIndicator(true);
        streamUsageInputTokens = 0;
        streamUsageOutputTokens = 0;
        thinkingPanel.startNewStream();

        // 重活推迟到下一帧 —— saveChatHistory（智能体状态落盘）+ createStreamingBubble
        // （新建 WebView，WebKit 实例化重）若同步执行会让用户气泡的首帧推迟约 1 秒。
        // 用 Platform.runLater 让上面的 UI 改动先完成布局/重绘，然后下一脉冲再做这些重活。
        // 捕获当前代次，所有回调中检查代次是否匹配，会话切换/删除后旧回调自动失效。
        final int gen = streamGeneration;
        final String requestText = userText;
        final boolean usePlanMode = forcePlanMode;
        Platform.runLater(() -> {
            if (streamGeneration != gen) return; // 间隙内会话被切换/取消

            saveChatHistory();
            createStreamingBubble();

            // 按当前选中模式（或 /plan 强制）从注册表取出对应的 ConversationMode。
            // 未来扩展新对话模式只需实现 ConversationMode 并注册到 registry，这里无需改动。
            String targetId = (usePlanMode || planModeEnabled) ? "plan" : "chat";
            Mode mode = modeRegistry.getById(targetId).orElse(null);
            if (!(mode instanceof ConversationMode convMode)) {
                log.error("模式 [{}] 未注册或不是对话模式", targetId);
                onStreamError(new IllegalStateException("模式未注册: " + targetId));
                return;
            }
            convMode.start(
                    new ConversationRequest(requestText, attachmentsToSend),
                    buildConversationCallbacks(gen));
        });
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
                Platform.runLater(() -> {
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
                            if ("clarify_request".equals(c.kind())
                                    && c.payload() instanceof com.javaclaw.agent.clarify.ClarifyPayload cp) {
                                // 先渲染琥珀色卡片，再强制收尾流（不抖动 — 已有显眼卡片）。
                                // 工具层已 dispose 订阅，这里再做一次 UI 状态重置，
                                // 确保模型若仍残留事件也被新的 streamGeneration 拦掉。
                                appendClarifyCard(cp.reason(), cp.question());
                                stopActiveStream(false);
                            } else {
                                log.debug("收到自定义事件 [{}] {}", c.kind(), c.payload());
                            }
                        }
                    }
                });
            }

            @Override
            public void onComplete() {
                Platform.runLater(() -> {
                    if (streamGeneration == gen) onStreamComplete();
                });
            }

            @Override
            public void onError(Throwable error) {
                Platform.runLater(() -> {
                    if (streamGeneration == gen) onStreamError(error);
                });
            }
        };
    }

    // ==================== RichTextFX 气泡工厂 ====================

    /**
     * 创建只读气泡文本区域
     *
     * @param textCss 行内文字样式；传 {@code null} 时不设行内样式，
     *                由 chat.css 的 .bubble-text-area 令牌规则接管（随主题换肤）。
     *                仅跨主题固定底色的卡片（琥珀系统消息/澄清卡等）才应传定值颜色。
     */
    private InlineCssTextArea createBubbleTextArea(String content, double prefWidth, String textCss) {
        InlineCssTextArea area = new InlineCssTextArea();
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefWidth(prefWidth);
        area.getStyleClass().add("bubble-text-area");

        // 行内样式优先级高于样式表：只有显式传入时才设置，否则交给令牌级联
        if (textCss != null && !textCss.isBlank()) {
            area.setTextInsertionStyle(textCss);
        }

        if (content != null && !content.isEmpty()) {
            area.replaceText(content);
            if (textCss != null && !textCss.isBlank()) {
                area.setStyle(0, content.length(), textCss);
            }
        }

        // 高度自适应内容
        autoFitHeight(area);

        return area;
    }

    /**
     * 监听文本区域内容变化，自动调整高度以适应内容
     */
    private void autoFitHeight(InlineCssTextArea area) {
        Runnable updateHeight = () -> {
            int lines = area.getParagraphs().size();
            double lineH = 20;
            double h = Math.max(28, lines * lineH + 8);
            area.setPrefHeight(h);
            area.setMinHeight(h);
            area.setMaxHeight(h);
        };
        area.totalHeightEstimateProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal.doubleValue() > 0) {
                double h = newVal.doubleValue() + 4;
                area.setPrefHeight(h);
                area.setMinHeight(h);
                area.setMaxHeight(h);
            } else {
                updateHeight.run();
            }
        });
        // 初始化高度
        updateHeight.run();
    }

    /**
     * 添加带附件的用户消息（设计稿风格：头像 · 姓名+时间头部 · 内容，左对齐）
     */
    private void addUserBubbleWithAttachments(String text, List<File> attachments) {
        ChatMessage message = new ChatMessage(ChatMessage.Role.USER, text, attachments);
        currentSession.getMessages().add(message);

        // 用户头像（翡翠渐变圆形 "Y"）
        Label avatar = new Label("Y");
        avatar.getStyleClass().add("msg-avatar-user");

        // 头部：姓名 + 时间
        Label nameLabel = new Label("You");
        nameLabel.getStyleClass().add("msg-header-name");
        Label timeLabel = new Label(message.getFormattedTime());
        timeLabel.getStyleClass().add("msg-header-time");
        HBox headerRow = new HBox(8, nameLabel, timeLabel);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        // 内容容器
        VBox bubbleContent = new VBox(6);

        // 附件展示区域
        if (!attachments.isEmpty()) {
            FlowPane attachFlow = new FlowPane(8, 8);
            attachFlow.setMaxWidth(520);

            for (File file : attachments) {
                if (ChatMessage.isImageFile(file)) {
                    // 图片缩略图（设计稿：140×180 圆角 tile）
                    ImageView imgView = new ImageView(new Image(file.toURI().toString(), 140, 180, true, true));
                    imgView.setFitWidth(140);
                    imgView.setFitHeight(180);
                    imgView.setPreserveRatio(true);
                    imgView.getStyleClass().add("attachment-thumbnail");
                    enableImageZoom(imgView, file);
                    attachFlow.getChildren().add(imgView);
                } else {
                    String ext = ChatMessage.getFileExtension(file).toUpperCase();
                    Label fileLabel = new Label((ext.isEmpty() ? "FILE" : ext) + " " + file.getName());
                    fileLabel.getStyleClass().add("attachment-file-label");
                    attachFlow.getChildren().add(fileLabel);
                }
            }
            bubbleContent.getChildren().add(attachFlow);
        }

        // 文本内容（设计稿：无气泡底色，纯文本 paragraph；颜色随主题令牌）
        if (!text.isEmpty()) {
            InlineCssTextArea textArea = createBubbleTextArea(text, 520, null);
            bubbleContent.getChildren().add(textArea);
        }

        // 内容整体不再套"大紫色块"，去掉深色气泡 class，改用 plain
        bubbleContent.getStyleClass().add("msg-plain-body");

        VBox rightColumn = new VBox(6, headerRow, bubbleContent);
        HBox.setHgrow(rightColumn, Priority.ALWAYS);

        HBox messageRow = new HBox(10, avatar, rightColumn);
        messageRow.setPadding(new Insets(6, 12, 6, 12));
        messageRow.setAlignment(Pos.TOP_LEFT);

        messageList.getChildren().add(messageRow);
        log.debug("已添加用户消息（含 {} 个附件）", attachments.size());
    }

    // ==================== 流式输出气泡 ====================

    /**
     * 创建流式输出的占位气泡（扩展支持规划和工具调用区域）
     *
     * <pre>
     * messageRow (HBox, 靠左对齐)
     *   └─ bubbleContainer (VBox)
     *       ├─ agentNameLabel ← 当前回复的智能体名称
     *       ├─ planBox (VBox, 初始隐藏) ← 规划展示区域
     *       ├─ toolResultsBox (VBox, 初始隐藏) ← 工具调用结果区域
     *       ├─ unifiedBubble (VBox) ← 回复
     *       └─ timeLabel (Label)
     * </pre>
     */
    private void createStreamingBubble() {
        // ---- 助手头像（白底翡翠火花） ----
        Label avatar = new Label("✦");
        avatar.getStyleClass().add("msg-avatar-assistant");

        // ---- 头部：姓名 · 模型徽章 · 时间 · (右侧) 耗时/tokens ----
        Label agentNameLabel = new Label(AgentConfig.AGENT_NAME);
        agentNameLabel.getStyleClass().add("msg-header-name");

        Label modelBadge = new Label(currentModelDisplayName());
        modelBadge.getStyleClass().add("msg-header-model");

        ChatMessage timeHolder = new ChatMessage(ChatMessage.Role.ASSISTANT, "");
        Label timeLabel = new Label(timeHolder.getFormattedTime());
        timeLabel.getStyleClass().add("msg-header-time");

        Label metaLabel = new Label("—");
        metaLabel.getStyleClass().add("msg-header-meta");
        activeAssistantMetaLabel = metaLabel;

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        HBox headerRow = new HBox(8, agentNameLabel, modelBadge, timeLabel, headerSpacer, metaLabel);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        // ---- 工具调用结果区域（初始隐藏，位于主气泡上方） ----
        activeToolResultsBox = new VBox(4);
        activeToolResultsBox.getStyleClass().add("tool-results-box");
        activeToolResultsBox.setPadding(new Insets(0, 0, 6, 0));
        activeToolResultsBox.setVisible(false);
        activeToolResultsBox.setManaged(false);

        // ---- 回复气泡（Markdown WebView） ----
        activeReplyBubble = new MarkdownBubble(520);
        // 首个回复 chunk 到达前隐藏空 WebView，改由占位动画占位（避免空白气泡）
        activeReplyBubble.getView().setVisible(false);
        activeReplyBubble.getView().setManaged(false);

        // 「生成中」占位：三点呼吸动画 + 文案
        activeGenPlaceholder = buildGenPlaceholder();

        activeUnifiedBubble = new VBox(4, activeGenPlaceholder, activeReplyBubble.getView());
        activeUnifiedBubble.getStyleClass().addAll("message-bubble", "message-assistant");
        activeUnifiedBubble.setPadding(new Insets(10, 14, 10, 14));

        // ---- 操作行（采纳 / 重新生成 / 引用回复） ----
        Button adoptBtn = new Button("✓ 采纳");
        adoptBtn.getStyleClass().add("msg-action-btn");
        adoptBtn.setTooltip(new Tooltip("标记此回复已采纳并复制正文到剪贴板"));
        adoptBtn.setDisable(true); // 流式结束并保存消息后再激活
        final java.util.concurrent.atomic.AtomicReference<ChatMessage> adoptHolder =
                new java.util.concurrent.atomic.AtomicReference<>();
        activeAdoptBtn = adoptBtn;
        activeAdoptTargetRef = adoptHolder;
        adoptBtn.setOnAction(e -> {
            ChatMessage target = adoptHolder.get();
            if (target == null) return;
            target.setAdopted(true);
            String txt = target.getContent();
            if (txt != null && !txt.isEmpty()) {
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(
                        java.util.Map.of(javafx.scene.input.DataFormat.PLAIN_TEXT, txt));
            }
            adoptBtn.setText("✓ 已采纳");
            adoptBtn.setDisable(true);
            com.javaclaw.app.UiMotion.success(adoptBtn);
            saveChatHistory();
            log.info("用户采纳消息（{} 字符）", txt == null ? 0 : txt.length());
        });
        Button regenBtn = new Button("↻ 重新生成");
        regenBtn.getStyleClass().add("msg-action-btn");
        regenBtn.setOnAction(e -> {
            String last = findLastUserMessage();
            if (last != null) {
                inputField.replaceText(0, inputField.getLength(), last);
                onSendMessage();
            }
        });
        Button quoteBtn = new Button("↩ 引用回复");
        quoteBtn.getStyleClass().add("msg-action-btn");
        quoteBtn.setOnAction(e -> {
            String current = activeReplyBubble != null ? activeReplyBubble.getText() : "";
            if (current != null && !current.isEmpty()) {
                String quoted = "> " + current.replace("\n", "\n> ") + "\n\n";
                inputField.replaceText(0, 0, quoted);
                inputField.moveTo(inputField.getLength());
                inputField.requestFocus();
            }
        });
        Button moreBtn = new Button("···");
        moreBtn.getStyleClass().add("msg-action-btn");
        moreBtn.setTooltip(new Tooltip("更多操作"));
        ContextMenu moreMenu = new ContextMenu();
        MenuItem copyAllItem = new MenuItem("复制全文");
        copyAllItem.setOnAction(e -> {
            String txt = activeReplyBubble != null ? activeReplyBubble.getText() : "";
            if (txt != null && !txt.isEmpty()) {
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(
                        java.util.Map.of(javafx.scene.input.DataFormat.PLAIN_TEXT, txt));
                com.javaclaw.app.UiMotion.success(moreBtn);
            }
        });
        MenuItem saveItem = new MenuItem("保存为文件...");
        saveItem.setOnAction(e -> {
            String txt = activeReplyBubble != null ? activeReplyBubble.getText() : "";
            if (txt == null || txt.isEmpty()) return;
            FileChooser chooser = new FileChooser();
            chooser.setTitle("保存回复到文件");
            chooser.setInitialFileName("reply.md");
            chooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("Markdown", "*.md"),
                    new FileChooser.ExtensionFilter("文本文件", "*.txt"),
                    new FileChooser.ExtensionFilter("所有文件", "*.*"));
            Stage owner = (Stage) moreBtn.getScene().getWindow();
            java.io.File file = chooser.showSaveDialog(owner);
            if (file != null) {
                try {
                    java.nio.file.Files.writeString(file.toPath(), txt);
                    com.javaclaw.app.UiMotion.success(moreBtn);
                } catch (java.io.IOException ex) {
                    log.error("保存回复到文件失败", ex);
                    com.javaclaw.app.UiMotion.error(moreBtn);
                }
            }
        });
        MenuItem deleteItem = new MenuItem("删除此消息");
        deleteItem.setOnAction(e -> {
            if (activeUnifiedBubble == null) return;
            javafx.scene.Node row = activeUnifiedBubble.getParent();
            while (row != null && row.getParent() != messageList) row = row.getParent();
            if (row != null) {
                messageList.getChildren().remove(row);
                if (!currentSession.getMessages().isEmpty()) {
                    int lastIdx = currentSession.getMessages().size() - 1;
                    if (currentSession.getMessages().get(lastIdx).getRole() == ChatMessage.Role.ASSISTANT) {
                        currentSession.getMessages().remove(lastIdx);
                        saveChatHistory();
                    }
                }
            }
        });
        moreMenu.getItems().addAll(copyAllItem, saveItem, new SeparatorMenuItem(), deleteItem);
        moreBtn.setOnAction(e -> moreMenu.show(moreBtn,
                javafx.geometry.Side.BOTTOM, 0, 0));

        HBox actionRow = new HBox(2, adoptBtn, regenBtn, quoteBtn, moreBtn);
        actionRow.setAlignment(Pos.CENTER_LEFT);
        actionRow.getStyleClass().add("msg-action-row");

        // ---- 组装（规划和思考已移至右侧面板） ----
        VBox bubbleContainer = new VBox(4,
                headerRow, activeToolResultsBox,
                activeUnifiedBubble, actionRow);
        HBox.setHgrow(bubbleContainer, Priority.ALWAYS);

        HBox messageRow = new HBox(10, avatar, bubbleContainer);
        messageRow.setPadding(new Insets(6, 12, 6, 12));
        messageRow.setAlignment(Pos.TOP_LEFT);

        messageList.getChildren().add(messageRow);
        log.debug("已创建流式输出占位气泡（设计稿头像 + 头部 + 操作行）");
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
     * 构建「生成中」占位：三点呼吸动画 + 文案。
     * 在首个回复 chunk 到达前显示，避免气泡空白。
     */
    private HBox buildGenPlaceholder() {
        Label d1 = new Label("●");
        Label d2 = new Label("●");
        Label d3 = new Label("●");
        d1.getStyleClass().add("gen-placeholder-dot");
        d2.getStyleClass().add("gen-placeholder-dot");
        d3.getStyleClass().add("gen-placeholder-dot");
        Label text = new Label("正在生成回复…");
        text.getStyleClass().add("gen-placeholder-text");

        HBox box = new HBox(5, d1, d2, d3, text);
        box.setAlignment(Pos.CENTER_LEFT);

        activeGenPlaceholderAnim = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(d1.opacityProperty(), 0.3),
                        new KeyValue(d2.opacityProperty(), 0.3),
                        new KeyValue(d3.opacityProperty(), 0.3)),
                new KeyFrame(Duration.millis(200), new KeyValue(d1.opacityProperty(), 1.0)),
                new KeyFrame(Duration.millis(400),
                        new KeyValue(d1.opacityProperty(), 0.3),
                        new KeyValue(d2.opacityProperty(), 1.0)),
                new KeyFrame(Duration.millis(600),
                        new KeyValue(d2.opacityProperty(), 0.3),
                        new KeyValue(d3.opacityProperty(), 1.0)),
                new KeyFrame(Duration.millis(800), new KeyValue(d3.opacityProperty(), 0.3)));
        activeGenPlaceholderAnim.setCycleCount(Timeline.INDEFINITE);
        activeGenPlaceholderAnim.play();
        return box;
    }

    /**
     * 移除「生成中」占位，恢复 WebView 显示。幂等：可在首 chunk 与各终态重复调用。
     */
    private void dismissGenPlaceholder() {
        if (activeGenPlaceholderAnim != null) {
            activeGenPlaceholderAnim.stop();
            activeGenPlaceholderAnim = null;
        }
        if (activeReplyBubble != null) {
            activeReplyBubble.getView().setVisible(true);
            activeReplyBubble.getView().setManaged(true);
        }
        if (activeGenPlaceholder != null && activeUnifiedBubble != null) {
            activeUnifiedBubble.getChildren().remove(activeGenPlaceholder);
        }
        activeGenPlaceholder = null;
    }

    /**
     * 追加回复内容的文本片段
     */
    private void appendReplyChunk(String chunk) {
        if (activeReplyBubble == null) return;

        if (activeReplyBubble.getLength() == 0) {
            dismissGenPlaceholder();
            typingTextLabel.setText("助手正在回复...");
            thinkingPanel.setReplying();
            log.debug("开始接收回复内容");
        }

        activeReplyBubble.appendText(chunk);
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
        if (activeToolResultsBox == null) return;

        if (!activeToolResultsBox.isVisible()) {
            activeToolResultsBox.setVisible(true);
            activeToolResultsBox.setManaged(true);
        }

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
            tryDisplayInlineImages(content, activeSubResultBubble);
        }
    }

    /**
     * 创建新的子智能体结果展示块（思考内容显示在右侧面板）
     *
     * <pre>
     * resultBlock (VBox)
     *   ├─ agentLabel (Label, 子智能体名称)
     *   └─ bubble (VBox, 气泡容器)
     *       └─ textArea (InlineCssTextArea, 回复区域)
     * </pre>
     */
    private void createSubAgentResultBlock(String toolName, String displayName) {
        activeToolName = toolName;

        // 子智能体名称标签
        Label agentLabel = new Label(displayName);
        agentLabel.getStyleClass().add("agent-name-sub");

        // 回复区域（Markdown WebView，宽度提升至 540 便于阅读长结果）
        activeToolResultBubble = new MarkdownBubble(540);
        final MarkdownBubble bubbleRef = activeToolResultBubble;

        // 气泡容器：仅回复区域，初始隐藏（有内容时再显示，避免空白占位）
        activeSubResultBubble = new VBox(4, activeToolResultBubble.getView());
        activeSubResultBubble.getStyleClass().addAll("message-bubble", "message-tool-result");
        activeSubResultBubble.setPadding(new Insets(6, 10, 6, 10));
        activeSubResultBubble.setVisible(false);
        activeSubResultBubble.setManaged(false);

        // 头部操作栏：标签 + 折叠按钮 + 复制按钮（流式期间出现，鼠标悬停才显示）
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        Button collapseBtn = new Button("▼");
        collapseBtn.getStyleClass().add("bubble-icon-btn");
        collapseBtn.setTooltip(new Tooltip("折叠 / 展开此结果"));
        collapseBtn.setOnAction(e -> {
            boolean expand = !activeSubResultBubble.isManaged();
            activeSubResultBubble.setVisible(expand);
            activeSubResultBubble.setManaged(expand);
            collapseBtn.setText(expand ? "▼" : "▶");
        });

        Button copyBtn = new Button("复制");
        copyBtn.getStyleClass().add("bubble-icon-btn");
        copyBtn.setTooltip(new Tooltip("复制此结果文本"));
        copyBtn.setOnAction(e -> {
            String txt = bubbleRef.getText();
            if (txt != null && !txt.isEmpty()) {
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(
                        java.util.Map.of(javafx.scene.input.DataFormat.PLAIN_TEXT, txt));
                String original = copyBtn.getText();
                copyBtn.setText("已复制");
                com.javaclaw.app.UiMotion.success(copyBtn);
                Timeline restore = new Timeline(new KeyFrame(Duration.seconds(1.5),
                        ev -> copyBtn.setText(original)));
                restore.play();
            } else {
                com.javaclaw.app.UiMotion.error(copyBtn);
            }
        });

        HBox header = new HBox(6, agentLabel, headerSpacer, collapseBtn, copyBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("tool-result-header");

        // 外层容器
        VBox resultBlock = new VBox(2, header, activeSubResultBubble);
        resultBlock.setPadding(new Insets(2, 0, 2, 0));

        activeToolResultsBox.getChildren().add(resultBlock);
        log.debug("已创建子智能体结果块 [{}]（初始隐藏，等待内容）", toolName);
    }

    /**
     * 追加子智能体思考内容（路由到右侧面板，中间区域不显示）
     */
    private void appendSubThinking(String displayName, String thinking) {
        // 更新状态提示
        typingTextLabel.setText(displayName + " 正在思考...");

        // 路由到右侧思考面板
        thinkingPanel.appendSubAgentThinking(displayName, thinking);
    }

    /**
     * 追加子智能体回复内容
     */
    private void appendSubReply(String displayName, String displayText, int rawLength) {
        if (activeToolResultBubble == null) return;

        // 跳过空白内容，避免生成空气泡
        if (displayText == null || displayText.isBlank()) {
            log.debug("跳过空白子智能体回复 [{}]", activeToolName);
            return;
        }

        // 首次有内容时才显示气泡容器
        if (activeSubResultBubble != null && !activeSubResultBubble.isVisible()) {
            activeSubResultBubble.setVisible(true);
            activeSubResultBubble.setManaged(true);
        }

        // 更新状态提示
        typingTextLabel.setText(displayName + " 已返回结果...");

        // 通知右侧面板标记完成
        thinkingPanel.markSubAgentResult(displayName,
                displayText.length() > 80 ? displayText.substring(0, 77) + "..." : displayText);

        if (activeToolResultBubble.getLength() == 0) {
            activeToolResultBubble.replaceText(displayText);
            log.debug("已添加子智能体回复 [{}]，内容长度: {} 字符", activeToolName, rawLength);
        } else {
            activeToolResultBubble.appendText(displayText);
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
     * @param container 图片要添加到的容器（通常为 activeSubResultBubble 或 activeUnifiedBubble）
     */
    private void tryDisplayInlineImages(String text, VBox container) {
        if (container == null || text == null) return;

        for (String path : extractImagePaths(text)) {
            if (displayedImagePaths.contains(path)) continue;

            File file = new File(path);
            if (!file.exists() || !file.isFile()) continue;

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
     * <p>双击后弹出 {@link ImageViewerDialog}，支持滚轮缩放与拖拽平移；
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
                ImageViewerDialog.show(owner, file);
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
        typingTextLabel.setText("正在执行规划...");
        thinkingPanel.updatePlan(hint);
        log.debug("规划提示已更新: {} 字符", hint.length());
    }

    // ==================== 规划模式 ====================

    /**
     * 切换规划模式开关
     */
    private void togglePlanMode() {
        planModeEnabled = !planModeEnabled;
        if (planModeEnabled) {
            planModeBtn.setSelected(true);
            log.info("规划模式已开启");
        } else {
            chatModeBtn.setSelected(true);
            log.info("规划模式已关闭");
        }
    }

    /**
     * 动态构建知识库多选下拉菜单（按全局/工作区分组）
     */
    private void rebuildKnowledgeMenu() {
        knowledgeMenu.getItems().clear();

        var expert = runtime.getKnowledgeExpert();
        if (!expert.isRagEnabled()) {
            MenuItem hint = new MenuItem("RAG 未启用（请在设置 → 知识库中开启）");
            hint.setDisable(true);
            knowledgeMenu.getItems().add(hint);
            return;
        }

        var globalDocs = expert.getDocumentNames(com.javaclaw.agent.expert.KnowledgeExpert.Scope.GLOBAL);
        var workspaceDocs = expert.getDocumentNames(com.javaclaw.agent.expert.KnowledgeExpert.Scope.WORKSPACE);

        if (globalDocs.isEmpty() && workspaceDocs.isEmpty()) {
            MenuItem hint = new MenuItem("知识库为空，请先导入文档");
            hint.setDisable(true);
            knowledgeMenu.getItems().add(hint);
            knowledgeMenu.getItems().add(new SeparatorMenuItem());
            MenuItem manageItem = new MenuItem("管理知识库...");
            manageItem.setOnAction(e -> openKnowledgeBase());
            knowledgeMenu.getItems().add(manageItem);
            return;
        }

        Set<String> currentSelected = runtime.getSelectedKnowledgeDocs();
        int totalDocs = globalDocs.size() + workspaceDocs.size();
        List<CheckMenuItem> allCheckItems = new ArrayList<>();

        // 全选/取消全选
        CheckMenuItem selectAllItem = new CheckMenuItem("全选 / 取消全选");
        selectAllItem.setSelected(currentSelected.size() == totalDocs && totalDocs > 0);
        knowledgeMenu.getItems().add(selectAllItem);

        // 全局知识库分组
        if (!globalDocs.isEmpty()) {
            knowledgeMenu.getItems().add(new SeparatorMenuItem());
            addKnowledgeSectionHeader("全局知识库");

            for (String name : globalDocs) {
                int chunks = expert.getDocumentChunkCount(name);
                CheckMenuItem item = new CheckMenuItem(name + "  " + chunks + " 片段");
                item.getStyleClass().add("knowledge-doc-item");
                item.setSelected(currentSelected.contains(name));
                item.setOnAction(e -> onKnowledgeSelectionChanged(allCheckItems, selectAllItem));
                allCheckItems.add(item);
                knowledgeMenu.getItems().add(item);
            }
        }

        // 工作区知识库分组
        if (!workspaceDocs.isEmpty()) {
            knowledgeMenu.getItems().add(new SeparatorMenuItem());
            addKnowledgeSectionHeader("工作区知识库");

            for (String name : workspaceDocs) {
                int chunks = expert.getDocumentChunkCount(name);
                CheckMenuItem item = new CheckMenuItem(name + "  " + chunks + " 片段");
                item.getStyleClass().add("knowledge-doc-item");
                item.setSelected(currentSelected.contains(name));
                item.setOnAction(e -> onKnowledgeSelectionChanged(allCheckItems, selectAllItem));
                allCheckItems.add(item);
                knowledgeMenu.getItems().add(item);
            }
        }

        // 全选逻辑
        selectAllItem.setOnAction(e -> {
            boolean selectAll = selectAllItem.isSelected();
            for (CheckMenuItem ci : allCheckItems) {
                ci.setSelected(selectAll);
            }
            onKnowledgeSelectionChanged(allCheckItems, selectAllItem);
        });

        // 管理知识库入口
        knowledgeMenu.getItems().add(new SeparatorMenuItem());
        MenuItem manageItem = new MenuItem("管理知识库...");
        manageItem.getStyleClass().add("knowledge-manage-entry");
        manageItem.setOnAction(e -> openKnowledgeBase());
        knowledgeMenu.getItems().add(manageItem);
    }

    /**
     * 添加知识库分组标题（使用自定义 Label 代替 disabled MenuItem）
     */
    private void addKnowledgeSectionHeader(String title) {
        Label header = new Label(title);
        header.getStyleClass().add("knowledge-section-header");
        CustomMenuItem headerItem = new CustomMenuItem(header, false);
        headerItem.setHideOnClick(false);
        headerItem.getStyleClass().add("knowledge-header-item");
        knowledgeMenu.getItems().add(headerItem);
    }

    /**
     * 知识库文档勾选状态变更回调
     */
    private void onKnowledgeSelectionChanged(List<CheckMenuItem> docItems, CheckMenuItem selectAllItem) {
        Set<String> selected = new HashSet<>();
        for (CheckMenuItem item : docItems) {
            if (item.isSelected()) {
                String text = item.getText();
                // 解析文档名：去掉末尾的 "  N 片段"
                int sep = text.lastIndexOf("  ");
                String docName = sep > 0 ? text.substring(0, sep) : text;
                selected.add(docName);
            }
        }

        runtime.setSelectedKnowledgeDocs(selected);
        selectAllItem.setSelected(selected.size() == docItems.size());
        updateKnowledgeMenuStyle(selected.size());
    }

    /**
     * 更新知识库按钮样式和文字
     */
    private void updateKnowledgeMenuStyle(int selectedCount) {
        if (selectedCount > 0) {
            knowledgeMenu.setText("知识库(" + selectedCount + ")");
            if (!knowledgeMenu.getStyleClass().contains("knowledge-active")) {
                knowledgeMenu.getStyleClass().add("knowledge-active");
            }
        } else {
            knowledgeMenu.setText("知识库");
            knowledgeMenu.getStyleClass().remove("knowledge-active");
        }
    }

    /**
     * 清空知识库选中状态（工作区切换时调用）
     */
    private void clearKnowledgeSelection() {
        runtime.setSelectedKnowledgeDocs(Set.of());
        updateKnowledgeMenuStyle(0);
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
        if (activeToolResultsBox == null) return;

        // 切换到新智能体前，先把上一位标记为"已完成"，并用累积文本生成摘要
        if (currentPlanAgentName != null && !currentPlanAgentName.equals(agentName)) {
            finalizeCurrentPlanAgent();
        }

        // 首次收到发言时，显示容器
        if (!activeToolResultsBox.isVisible()) {
            activeToolResultsBox.setVisible(true);
            activeToolResultsBox.setManaged(true);
        }

        // 更新状态提示
        typingTextLabel.setText(agentName + " 正在发言...");

        // 在右侧面板建立智能体卡片（初始状态"思考中..."，内容区待 reply chunk 追加）
        currentPlanAgentName = agentName;
        currentPlanAgentBuffer.setLength(0);
        thinkingPanel.appendSubAgentThinking(agentName, "");

        // 智能体名称标签
        Label agentLabel = new Label(agentName);
        agentLabel.getStyleClass().add("plan-agent-name");

        // 创建空的流式 Markdown 气泡（宽度 540 与子智能体气泡一致）
        activePlanAgentBubble = new MarkdownBubble(540);
        final MarkdownBubble bubbleRef = activePlanAgentBubble;

        // 气泡容器
        VBox bubble = new VBox(4, activePlanAgentBubble.getView());
        bubble.getStyleClass().addAll("message-bubble", "message-tool-result");
        bubble.setPadding(new Insets(6, 10, 6, 10));

        // 头部操作栏：发言者 + 折叠/复制
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        Button collapseBtn = new Button("▼");
        collapseBtn.getStyleClass().add("bubble-icon-btn");
        collapseBtn.setTooltip(new Tooltip("折叠 / 展开此发言"));
        collapseBtn.setOnAction(e -> {
            boolean expand = !bubble.isManaged();
            bubble.setVisible(expand);
            bubble.setManaged(expand);
            collapseBtn.setText(expand ? "▼" : "▶");
        });

        Button copyBtn = new Button("复制");
        copyBtn.getStyleClass().add("bubble-icon-btn");
        copyBtn.setTooltip(new Tooltip("复制此发言文本"));
        copyBtn.setOnAction(e -> {
            String txt = bubbleRef.getText();
            if (txt != null && !txt.isEmpty()) {
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(
                        java.util.Map.of(javafx.scene.input.DataFormat.PLAIN_TEXT, txt));
                String original = copyBtn.getText();
                copyBtn.setText("已复制");
                com.javaclaw.app.UiMotion.success(copyBtn);
                Timeline restore = new Timeline(new KeyFrame(Duration.seconds(1.5),
                        ev -> copyBtn.setText(original)));
                restore.play();
            } else {
                com.javaclaw.app.UiMotion.error(copyBtn);
            }
        });

        HBox header = new HBox(6, agentLabel, headerSpacer, collapseBtn, copyBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("tool-result-header");

        // 外层容器
        VBox turnBlock = new VBox(2, header, bubble);
        turnBlock.setPadding(new Insets(2, 0, 2, 0));

        activeToolResultsBox.getChildren().add(turnBlock);

        log.debug("规划模式 [{}] 开始发言", agentName);
    }

    /**
     * 规划模式：为当前发言智能体生成摘要并在右侧面板标记"已完成"。
     *
     * <p>在新智能体开始发言前、或整体流结束时调用。</p>
     */
    private void finalizeCurrentPlanAgent() {
        if (currentPlanAgentName == null) return;
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
        if (activePlanAgentBubble == null) return;

        // 去除 PLAN_COMPLETE 标记
        String displayChunk = chunk;
        if (displayChunk.contains("[PLAN_COMPLETE]")) {
            displayChunk = displayChunk.replace("[PLAN_COMPLETE]", "");
        }
        if (!displayChunk.isEmpty()) {
            activePlanAgentBubble.appendText(displayChunk);
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
        log.info("流式输出已完成 — 回复: {} 字符",
                activeReplyBubble != null ? activeReplyBubble.getLength() : 0);

        // 规划模式：流结束时为最后一位发言智能体生成摘要并标记完成
        if (planModeEnabled) {
            finalizeCurrentPlanAgent();
        }

        // 移除「生成中」占位，恢复 WebView（无论是否有回复内容）
        dismissGenPlaceholder();

        try {
            if (planModeEnabled) {
                // 规划模式：回复内容在 activePlanAgentBubble 中，隐藏空的 activeReplyBubble
                if (activeReplyBubble != null && activeReplyBubble.getLength() == 0
                        && activeUnifiedBubble != null) {
                    activeUnifiedBubble.setVisible(false);
                    activeUnifiedBubble.setManaged(false);
                }
            } else if (activeReplyBubble != null && activeReplyBubble.getLength() == 0) {
                activeReplyBubble.replaceText("[模型未返回有效回复]");
            }

            // 检测主回复中的图片路径并内联显示
            if (activeReplyBubble != null && activeUnifiedBubble != null) {
                tryDisplayInlineImages(activeReplyBubble.getText(), activeUnifiedBubble);
            }

            // 将助手回复添加到消息列表并保存（携带流式过程中收集的图片路径）
            // 规划模式下取 activePlanAgentBubble 的内容，普通模式取 activeReplyBubble
            String replyText = null;
            if (planModeEnabled && activePlanAgentBubble != null && activePlanAgentBubble.getLength() > 0) {
                replyText = activePlanAgentBubble.getText();
            } else if (activeReplyBubble != null && activeReplyBubble.getLength() > 0) {
                replyText = activeReplyBubble.getText();
            }
            // 后台流式：回复必须落到发起流的会话（streamingSession），而非当前展示会话
            ChatSession target = streamingSession != null ? streamingSession : currentSession;
            if (replyText != null && target != null) {
                ChatMessage assistantMsg = new ChatMessage(ChatMessage.Role.ASSISTANT, replyText);
                for (String imgPath : displayedImagePaths) {
                    assistantMsg.addImagePath(imgPath);
                }
                target.getMessages().add(assistantMsg);
                // 绑定当前流式气泡的「采纳」按钮到这条持久化消息
                if (activeAdoptTargetRef != null) {
                    activeAdoptTargetRef.set(assistantMsg);
                }
                if (activeAdoptBtn != null) {
                    activeAdoptBtn.setDisable(false);
                }
                // 首条用户消息发送后自动更新会话标题
                target.autoTitle();
                if (target == currentSession) {
                    updateTopTitle();
                }
                sidebarView.updateSessionTitle(target.getId(), target.getTitle());
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
            typingTextLabel.setText("助手正在思考中...");
            setInputEnabled(true);
            finishBackgroundStreamIfAway();
            inputField.requestFocus();
        }
    }

    /**
     * 流式输出错误时的处理
     */
    private void onStreamError(Throwable error) {
        log.error("流式输出发生错误", error);

        if (planModeEnabled) {
            finalizeCurrentPlanAgent();
        }

        try {
            String errorMsg = "调用失败: " + runtime.extractErrorMessage(error);
            ChatSession target = streamingSession != null ? streamingSession : currentSession;
            if (target != null && target != currentSession) {
                // 后台流式出错：部分回复与错误信息落进所属会话历史，切回可见
                if (activeReplyBubble != null && activeReplyBubble.getLength() > 0) {
                    target.getMessages().add(new ChatMessage(
                            ChatMessage.Role.ASSISTANT, activeReplyBubble.getText()));
                }
                target.getMessages().add(new ChatMessage(ChatMessage.Role.SYSTEM, errorMsg));
                saveSessionMessages(target);
            } else if (activeReplyBubble != null && activeReplyBubble.getLength() == 0) {
                activeReplyBubble.replaceText(errorMsg);
            } else {
                addStaticBubble(ChatMessage.Role.SYSTEM, errorMsg);
            }
        } catch (Exception e) {
            log.error("显示错误信息时发生异常", e);
        } finally {
            clearActiveReferences();
            thinkingPanel.endStream();
            showThinkingIndicator(false);
            typingTextLabel.setText("助手正在思考中...");
            setInputEnabled(true);
            inputField.requestFocus();
            finishBackgroundStreamIfAway();
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
            // 在回复区域追加警告信息
            if (activeReplyBubble != null) {
                if (activeReplyBubble.getLength() == 0) {
                    activeReplyBubble.replaceText("[循环中断] " + warning);
                } else {
                    activeReplyBubble.appendText("\n\n[循环中断] " + warning);
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
            typingTextLabel.setText("助手正在思考中...");
            setInputEnabled(true);
            inputField.requestFocus();
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
        // 1. 构建可视化卡片：头像 + 头部（姓名 + 模型徽章 + 时间）+ 琥珀色卡片正文
        Label avatar = new Label("✦");
        avatar.getStyleClass().add("msg-avatar-assistant");

        Label name = new Label(AgentConfig.AGENT_NAME);
        name.getStyleClass().add("msg-header-name");
        Label modelBadge = new Label(currentModelDisplayName());
        modelBadge.getStyleClass().add("msg-header-model");
        Label time = new Label(new ChatMessage(ChatMessage.Role.ASSISTANT, "").getFormattedTime());
        time.getStyleClass().add("msg-header-time");
        HBox header = new HBox(8, name, modelBadge, time);
        header.setAlignment(Pos.CENTER_LEFT);

        Label cardTitle = new Label("🤔 需要您的澄清");
        cardTitle.getStyleClass().add("clarify-card-title");

        VBox card = new VBox(8, cardTitle);
        card.getStyleClass().add("clarify-card");
        card.setPadding(new Insets(12, 14, 12, 14));

        if (reason != null && !reason.isBlank()) {
            Label reasonHeader = new Label("原因");
            reasonHeader.getStyleClass().add("clarify-card-label");
            javafx.scene.Node reasonBody = createBubbleTextArea(reason, 520, "-fx-fill: #4A3E20;");
            card.getChildren().addAll(reasonHeader, reasonBody);
        }
        if (question != null && !question.isBlank()) {
            Label qHeader = new Label("问题");
            qHeader.getStyleClass().add("clarify-card-label");
            javafx.scene.Node qBody = createBubbleTextArea(question, 520, "-fx-fill: #27251F;");
            card.getChildren().addAll(qHeader, qBody);
        }

        VBox right = new VBox(6, header, card);
        HBox.setHgrow(right, Priority.ALWAYS);

        HBox row = new HBox(10, avatar, right);
        row.setAlignment(Pos.TOP_LEFT);
        row.setPadding(new Insets(6, 12, 6, 12));
        messageList.getChildren().add(row);

        // 2. 持久化到当前会话：用 markdown blockquote 表达澄清结构，重载时也能渲染
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
            try {
                chatHistoryManager.saveSessionMessages(
                        currentSession.getId(), currentSession.getMessages());
            } catch (Exception e) {
                log.warn("保存澄清卡片到会话历史失败: {}", e.getMessage());
            }
        }

        // 3. 让输入框获得焦点，提示用户输入修正信息
        inputField.requestFocus();
        log.info("已渲染澄清卡片: reason='{}' question='{}'", reason, question);
    }

    /**
     * 添加一条静态消息气泡（设计稿：头像 + 姓名/时间头部 + 纯文本正文）
     */
    private void addStaticBubble(ChatMessage.Role role, String content) {
        ChatMessage message = new ChatMessage(role, content);
        currentSession.getMessages().add(message);
        HBox row = buildStaticMessageRow(role, message, java.util.Collections.emptyList());
        messageList.getChildren().add(row);
        log.debug("已添加 {} 静态消息", role.getDisplayName());
    }

    /**
     * 从历史记录添加静态消息气泡（不重复添加到当前会话消息列表）
     */
    private void addStaticBubbleFromHistory(ChatMessage message) {
        List<ImageView> historyImages = new java.util.ArrayList<>();
        for (String path : message.getImagePaths()) {
            File file = new File(path);
            if (!file.exists() || !file.isFile()) continue;
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
        HBox row = buildStaticMessageRow(message.getRole(), message, historyImages);
        messageList.getChildren().add(row);
    }

    /**
     * 构建一条静态消息行（设计稿风格：头像 + 头部姓名/时间 + 纯文本正文 + 可选图片）。
     * 所有角色复用此工厂，USER/ASSISTANT 采用新布局，SYSTEM 保留原来的居中提示气泡。
     */
    private HBox buildStaticMessageRow(ChatMessage.Role role, ChatMessage message,
                                       List<ImageView> extraImages) {
        if (role == ChatMessage.Role.SYSTEM) {
            // 系统消息继续使用居中提示气泡
            javafx.scene.Node contentNode = createBubbleTextArea(message.getContent(), 450,
                    "-fx-fill: #27251F; -fx-font-style: italic;");
            VBox bubble = new VBox(contentNode);
            bubble.getStyleClass().addAll("message-bubble", "message-system");
            bubble.setPadding(new Insets(8, 12, 8, 12));
            Label tl = new Label(message.getFormattedTime());
            tl.getStyleClass().add("time-label");
            VBox box = new VBox(2, bubble, tl);
            HBox row = new HBox(box);
            row.setAlignment(Pos.CENTER);
            row.setPadding(new Insets(2, 5, 2, 5));
            return row;
        }

        boolean isUser = role == ChatMessage.Role.USER;

        // 头像
        Label avatar = new Label(isUser ? "Y" : "✦");
        avatar.getStyleClass().add(isUser ? "msg-avatar-user" : "msg-avatar-assistant");

        // 头部：姓名 + 可选模型徽章 + 时间
        Label name = new Label(isUser ? "You" : AgentConfig.AGENT_NAME);
        name.getStyleClass().add("msg-header-name");
        Label time = new Label(message.getFormattedTime());
        time.getStyleClass().add("msg-header-time");

        HBox header;
        if (isUser) {
            header = new HBox(8, name, time);
        } else {
            Label modelBadge = new Label(currentModelDisplayName());
            modelBadge.getStyleClass().add("msg-header-model");
            header = new HBox(8, name, modelBadge, time);
        }
        header.setAlignment(Pos.CENTER_LEFT);

        // 正文（用户消息用 InlineCssTextArea；助手历史消息走 MarkdownBubble，
        // 与流式路径一致地渲染保存的 markdown，避免历史回放时只显示原文）
        VBox body = new VBox(6);
        body.getStyleClass().add("msg-plain-body");

        javafx.scene.Node text;
        if (role == ChatMessage.Role.ASSISTANT) {
            MarkdownBubble bubble = new MarkdownBubble(520);
            bubble.replaceText(message.getContent() == null ? "" : message.getContent());
            text = bubble.getView();
        } else {
            // 颜色随主题令牌（行内样式会压过样式表，故传 null）
            text = createBubbleTextArea(message.getContent(), 520, null);
        }
        body.getChildren().add(text);
        if (extraImages != null) {
            for (ImageView iv : extraImages) body.getChildren().add(iv);
        }

        VBox right = new VBox(6, header, body);
        HBox.setHgrow(right, Priority.ALWAYS);

        HBox row = new HBox(10, avatar, right);
        row.setAlignment(Pos.TOP_LEFT);
        row.setPadding(new Insets(6, 12, 6, 12));
        return row;
    }

    // ==================== 多会话管理 ====================

    /**
     * 加载所有会话，恢复侧边栏列表和当前会话
     */
    private void loadSessions() {
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
            sidebarView.addSession(defaultSession, true);
            addWelcomeBubble();
        } else {
            sessions.addAll(validSessions);
            // 索引与磁盘同步（清除已过滤掉的空会话记录）
            if (validSessions.size() < loaded.size()) {
                chatHistoryManager.saveSessionIndex(sessions);
            }
            // 在侧边栏中添加所有会话，默认选中第一个
            for (int i = 0; i < sessions.size(); i++) {
                sidebarView.addSession(sessions.get(i), i == 0);
            }
            // 加载第一个会话的消息
            currentSession = sessions.getFirst();
            List<ChatMessage> messages = chatHistoryManager.loadSessionMessages(currentSession.getId());
            currentSession.getMessages().addAll(messages);
            for (ChatMessage msg : messages) {
                addStaticBubbleFromHistory(msg);
            }
            // 启动时同样批量新建 WebView，错峰重绘规避空白气泡
            refreshMarkdownBubblesStaggered();
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
        log.info("用户请求新建会话");

        final boolean streamRunning = streamingActive && streamingSession != null;

        // 保存当前会话消息
        saveCurrentSession();
        if (streamRunning && currentSession == streamingSession) {
            // 流式进行中：挂起场景图让流在后台继续，不杀流、不动智能体上下文
            suspendedStreamingNodes.clear();
            suspendedStreamingNodes.addAll(messageList.getChildren());
            messageList.getChildren().clear();
            typingTextLabel.setText("其他会话正在后台生成回复…");
        } else if (!streamRunning) {
            if (streamingActive) {
                stopActiveStream(false);  // 防御：流活跃但未记录所属会话
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
            sidebarView.removeSession(currentSession.getId());
        }

        // 创建新会话
        ChatSession newSession = new ChatSession("新的对话");
        sessions.addFirst(newSession);
        currentSession = newSession;

        // 更新侧边栏
        sidebarView.insertSessionAtTop(newSession, true);

        // 清空聊天区域并显示欢迎消息（后台流式期间不重置进度面板，保持可观察）
        disposeMessageList();
        pendingAttachments.clear();
        updateAttachmentPreview();
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
            stopActiveStream(false);
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
            suspendedStreamingNodes.addAll(messageList.getChildren());
            messageList.getChildren().clear();
            typingTextLabel.setText("其他会话正在后台生成回复…");
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
            activeReplyBubble = null;
            activeToolResultBubble = null;
            activeSubResultBubble = null;
            activeToolName = null;
            activePlanAgentBubble = null;
            thinkingPanel.reset();
        }
        pendingAttachments.clear();
        updateAttachmentPreview();

        // 切换当前会话
        currentSession = target;

        // ==== 进入目标会话 ====
        if (streamRunning && target == streamingSession) {
            // 切回流式中的会话：恢复挂起的场景图，输出与进度无缝继续
            messageList.getChildren().setAll(suspendedStreamingNodes);
            suspendedStreamingNodes.clear();
            typingTextLabel.setText("助手正在思考中...");
            Platform.runLater(() -> scrollPane.setVvalue(1.0));
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
            // 批量重建 WebView 后错峰强制重绘，规避 WebKit「已加载未绘制」导致的空白气泡
            refreshMarkdownBubblesStaggered();
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

    /** 释放挂起的流式场景图节点（含其中的 MarkdownBubble WebView） */
    private void disposeSuspendedStreamingNodes() {
        if (suspendedStreamingNodes.isEmpty()) return;
        for (javafx.scene.Node n : suspendedStreamingNodes) {
            collectAndDisposeBubbles(n);
        }
        suspendedStreamingNodes.clear();
    }

    /**
     * 错峰强制重绘消息区内全部 Markdown 气泡。
     *
     * <p>会话切换会一次性 dispose 旧 WebView 并批量新建，macOS 的 WebKit 在这种
     * 高峰下偶发「引擎已加载但合成层未绘制」——表现为整段对话空白。等场景图安定
     * 后按 60ms 间隔依次重写 innerHTML 强制重绘（与窗口焦点恢复的兜底同机制）。</p>
     */
    private void refreshMarkdownBubblesStaggered() {
        java.util.List<MarkdownBubble> bubbles = new ArrayList<>();
        collectMarkdownBubbles(messageList, bubbles);
        if (bubbles.isEmpty()) return;
        int slot = 0;
        for (MarkdownBubble bubble : bubbles) {
            javafx.animation.PauseTransition delay = new javafx.animation.PauseTransition(
                    Duration.millis(350 + 60L * slot++));
            delay.setOnFinished(e -> bubble.refresh());
            delay.play();
        }
    }

    private void collectMarkdownBubbles(javafx.scene.Node node, java.util.List<MarkdownBubble> out) {
        if (node instanceof javafx.scene.web.WebView wv) {
            Object attached = wv.getProperties().get("markdownBubble");
            if (attached instanceof MarkdownBubble bubble) {
                out.add(bubble);
            }
            return;
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                collectMarkdownBubbles(child, out);
            }
        }
    }

    /**
     * 删除指定会话
     */
    private void onDeleteSession(String sessionId) {
        log.info("用户请求删除会话: {}", sessionId);

        // 删除的是正在后台流式的会话：先停流并清理挂起节点
        if (streamingSession != null && streamingSession.getId().equals(sessionId)) {
            stopActiveStream(false);
        }

        // 从列表移除
        sessions.removeIf(s -> s.getId().equals(sessionId));
        sidebarView.removeSession(sessionId);
        chatHistoryManager.deleteSession(sessionId);
        chatService.deleteSession(sessionId);

        // 如果删除的是当前会话，终止流式调用并切换到其他会话
        if (currentSession != null && currentSession.getId().equals(sessionId)) {
            stopActiveStream();
            clearAllHistory();

            if (sessions.isEmpty()) {
                // 没有剩余会话，创建新会话
                onNewSession();
            } else {
                // 切换到第一个会话
                currentSession = null;
                String firstId = sessions.getFirst().getId();
                sidebarView.selectSession(firstId);
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
        log.info("用户请求批量删除 {} 个会话", sessionIds.size());

        // 批量删除包含正在后台流式的会话：先停流并清理挂起节点
        if (streamingSession != null && sessionIds.contains(streamingSession.getId())) {
            stopActiveStream(false);
        }

        boolean currentDeleted = currentSession != null && sessionIds.contains(currentSession.getId());

        for (String id : sessionIds) {
            sessions.removeIf(s -> s.getId().equals(id));
            sidebarView.removeSession(id);
            chatHistoryManager.deleteSession(id);
            chatService.deleteSession(id);
        }

        if (currentDeleted) {
            stopActiveStream();
            clearAllHistory();

            if (sessions.isEmpty()) {
                onNewSession();
            } else {
                currentSession = null;
                String firstId = sessions.getFirst().getId();
                sidebarView.selectSession(firstId);
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

        InlineCssTextArea textArea = createBubbleTextArea(welcomeText, 450, null);

        VBox bubble = new VBox(textArea);
        bubble.getStyleClass().addAll("message-bubble", "message-assistant");
        bubble.setPadding(new Insets(8, 12, 8, 12));

        HBox messageRow = new HBox(new VBox(2, bubble));
        messageRow.setPadding(new Insets(2, 5, 2, 5));
        messageRow.setAlignment(Pos.CENTER_LEFT);

        messageList.getChildren().add(messageRow);
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
     * 终止当前活跃的流式调用（普通模式 + 规划模式），递增代次使旧回调失效，重置 UI 状态。
     * 用户主动按"停止"或 Esc 时调用：附带输入框抖动作为"已取消"的视觉反馈。
     */
    private void stopActiveStream() {
        stopActiveStream(true);
    }

    /**
     * @param showCancelFeedback true=显示输入框抖动反馈（用户主动取消）；
     *                           false=静默停止（如模型主动澄清中断，已经有醒目卡片，不需再抖）
     */
    private void stopActiveStream(boolean showCancelFeedback) {
        streamGeneration++;
        chatService.cancelStream();
        planModeService.cancel();
        streamingSession = null;
        disposeSuspendedStreamingNodes();
        clearActiveReferences();
        showThinkingIndicator(false);
        typingTextLabel.setText("助手正在思考中...");
        thinkingPanel.endStream();
        setInputEnabled(true);
        if (showCancelFeedback) {
            com.javaclaw.app.UiMotion.error(inputField);
        }
    }

    /**
     * 注册全局快捷键 — 等待 Scene 就绪后挂载到 Scene 的 accelerators 表
     * <p>使用 {@code SHORTCUT_DOWN}：macOS 上对应 Cmd，其他平台对应 Ctrl。
     */
    private void installGlobalShortcuts() {
        outerRoot.sceneProperty().addListener((obs, oldScene, scene) -> {
            if (scene == null) return;
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
                    javafx.scene.input.KeyCombination.SHORTCUT_DOWN), () -> inputField.requestFocus());
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
    private void toggleSidebar() {
        javafx.scene.Node sidebar = sidebarView.getRoot();
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
            Platform.runLater(() -> applyResponsiveSidebar(scene.getWidth()));
        });
    }

    private void applyResponsiveSidebar(double width) {
        javafx.scene.Node sidebar = sidebarView.getRoot();
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
        dialog.showAndWait();
    }

    /**
     * 判断 ScrollPane 是否接近底部（距底部 &lt;= {@link #NEAR_BOTTOM_THRESHOLD_PX} 像素）
     * <p>短消息列表（内容未撑满视口）始终视为"在底部"。
     */
    private boolean isNearBottom() {
        if (scrollPane == null) return true;
        double viewportH = scrollPane.getViewportBounds() != null ? scrollPane.getViewportBounds().getHeight() : 0;
        double contentH = messageList.getHeight();
        double scrollableH = Math.max(0, contentH - viewportH);
        if (scrollableH <= 0) return true;
        double currentY = scrollableH * scrollPane.getVvalue();
        return (scrollableH - currentY) <= NEAR_BOTTOM_THRESHOLD_PX;
    }

    /**
     * 累加未读计数并显示浮动按钮
     */
    private void incrementUnreadCount(int delta) {
        boolean wasHidden = unreadNewCount == 0;
        unreadNewCount += delta;
        if (newMessagesButton != null) {
            newMessagesButton.setText("↓ " + unreadNewCount + " 条新消息");
            newMessagesButton.setVisible(true);
            newMessagesButton.setManaged(true);
            // 从隐藏变为可见时使用 fadeIn，避免重复触发
            if (wasHidden) {
                com.javaclaw.app.UiMotion.fadeIn(newMessagesButton);
            }
        }
    }

    /**
     * 重置未读计数并隐藏浮动按钮
     */
    private void resetUnreadCount() {
        unreadNewCount = 0;
        if (newMessagesButton != null) {
            newMessagesButton.setVisible(false);
            newMessagesButton.setManaged(false);
        }
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
     * 清空消息列表并释放内嵌的 MarkdownBubble（含其 WebView）资源。
     *
     * <p>仅 {@link MarkdownBubble#getView()} 产生的 WebView 上挂有 {@code markdownBubble}
     * 属性；深度遍历 messageList 所有后代，对命中的 WebView 调用 dispose，
     * 斩断它与主 Stage 的 focusedProperty 之间的 listener 引用链。</p>
     */
    private void disposeMessageList() {
        collectAndDisposeBubbles(messageList);
        messageList.getChildren().clear();
    }

    private void collectAndDisposeBubbles(javafx.scene.Node node) {
        if (node instanceof javafx.scene.web.WebView wv) {
            Object attached = wv.getProperties().get("markdownBubble");
            if (attached instanceof MarkdownBubble bubble) {
                bubble.dispose();
            }
            return;
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                collectAndDisposeBubbles(child);
            }
        }
    }

    private void clearActiveReferences() {
        // 兜底移除「生成中」占位（正常完成/出错/循环中断等各路径终态）
        dismissGenPlaceholder();
        // 流式结束前，写入最终的"耗时 · Tokens"到消息头部
        if (activeAssistantMetaLabel != null) {
            long tokens = runtime != null && runtime.getTokenTracker() != null
                    ? runtime.getTokenTracker().getSessionTokens()
                    : 0;
            String tokDisplay = tokens >= 1000
                    ? String.format("%,d tok", tokens)
                    : tokens + " tok";
            activeAssistantMetaLabel.setText(tokDisplay);
        }
        activeAssistantMetaLabel = null;
        activeReplyBubble = null;
        activeToolResultsBox = null;
        activeToolName = null;
        activeToolResultBubble = null;
        activeSubResultBubble = null;
        activeUnifiedBubble = null;
        activePlanAgentBubble = null;
        currentPlanAgentName = null;
        currentPlanAgentBuffer.setLength(0);
        displayedImagePaths.clear();
    }

    private void showThinkingIndicator(boolean show) {
        typingIndicator.setVisible(show);
        typingIndicator.setManaged(show);
        if (show) {
            typingAnimation.play();
        } else {
            typingAnimation.stop();
        }
    }

    private void setInputEnabled(boolean enabled) {
        inputField.setDisable(!enabled);
        streamingActive = !enabled;
        // 按钮在两种状态都保持可点击：空闲=发送、流式=停止
        sendButton.setDisable(false);
        if (streamingActive) {
            sendButton.setText("停止");
            sendButton.getStyleClass().remove("send-button");
            if (!sendButton.getStyleClass().contains("stop-button")) {
                sendButton.getStyleClass().add("stop-button");
            }
            sendButton.setTooltip(new Tooltip("中断当前对话（Esc）"));
        } else {
            sendButton.setText("发送");
            sendButton.getStyleClass().remove("stop-button");
            if (!sendButton.getStyleClass().contains("send-button")) {
                sendButton.getStyleClass().add("send-button");
            }
            sendButton.setTooltip(null);
        }
        updateTopTitleStatusDot();
    }

    /**
     * 发送/停止按钮的统一处理：根据 streamingActive 决定行为
     */
    private void onSendOrStop() {
        if (streamingActive) {
            stopActiveStream();
        } else {
            onSendMessage();
        }
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
        log.info("打开设置对话框");
        javafx.stage.Stage ownerStage = (javafx.stage.Stage) outerRoot.getScene().getWindow();
        SettingsView settingsView = new SettingsView(ownerStage,
                runtime != null ? runtime.getMcpClientManager() : null,
                runtime != null ? runtime.getModelFactory() : null,
                runtime != null ? runtime.getTokenTracker() : null);
        settingsView.setOnModelConfigChanged(this::rebuildAgentService);
        settingsView.show();
    }

    /**
     * 处理 ConversationEvent.Usage：累加本次流式会话的真实输入/输出 token，
     * 刷新右侧「处理进度」面板的 TOKENS IN / TOKENS OUT / 费用 三联指标。
     *
     * <p>Usage 事件由 StreamEventHandler 在每次模型 ChatUsage 出现时发出，
     * 一次流式会话会触发多次（编排器首轮 + 中间轮 + 子智能体），故采用累加。</p>
     */
    private void updateThinkingPanelMetrics(ConversationEvent.Usage u) {
        streamUsageInputTokens += Math.max(0, u.inputTokens());
        streamUsageOutputTokens += Math.max(0, u.outputTokens());
        String model = AgentConfig.getInstance().getModelName();
        double cost = PricingTable.estimateCostCny(model,
                streamUsageInputTokens, streamUsageOutputTokens);
        thinkingPanel.updateMetrics(streamUsageInputTokens, streamUsageOutputTokens,
                TokenTracker.formatCostCny(cost));
    }

    /**
     * 绑定 TokenTracker 回调到 tokenLabel（服务重建后需重新调用）
     */
    private void wireTokenTracker() {
        runtime.getTokenTracker().setOnTokensChanged(() -> {
            Platform.runLater(this::refreshStatusBar);
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
     * <p>主显示「今日 + 会话」两个维度：今日累计来自持久化的 token-usage.json，
     * 应用重启后立即可见；会话仅在本次进程内累加。避免重启后状态栏一直显示 0 的体感问题。</p>
     */
    private void refreshStatusBar() {
        if (tokenLabel == null) return;
        try {
            TokenTracker tracker = runtime.getTokenTracker();
            long sessionTokens = tracker.getSessionTokens();
            long todayTokens = tracker.getTodayTokens();
            long monthlyTokens = tracker.getMonthlyTokens();
            String monthlyCost = TokenTracker.formatCostCny(tracker.getMonthlyCostCny());
            tokenLabel.setText(
                    "今日 " + TokenTracker.formatTokens(todayTokens)
                            + " · 会话 " + TokenTracker.formatTokens(sessionTokens)
                            + " · " + monthlyCost);
            if (tokenSummaryTooltip != null) {
                TokenTracker.DailyUsage today = tracker.getTodayUsage();
                TokenTracker.DailyUsage month = tracker.getMonthlyUsage();
                tokenSummaryTooltip.setText(
                        "今日累计：" + TokenTracker.formatTokens(todayTokens) + " tokens"
                                + "（输入 " + TokenTracker.formatTokens(today.input)
                                + " / 输出 " + TokenTracker.formatTokens(today.output) + "）\n" +
                        "本月累计：" + TokenTracker.formatTokens(monthlyTokens) + " tokens"
                                + "（输入 " + TokenTracker.formatTokens(month.input)
                                + " / 输出 " + TokenTracker.formatTokens(month.output) + "）\n" +
                        "本月成本：" + monthlyCost + "（估算，仅供参考）\n" +
                        "本次会话：" + TokenTracker.formatTokens(sessionTokens) + " tokens · 耗时 "
                                + TokenTracker.formatDuration(tracker.getSessionDurationSeconds()) + "\n" +
                        "点击可重置本次会话计数"
                );
            }
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
            sidebarView.updateSkillBadge(proposals);
            int activeTasks = (int) com.javaclaw.task.sdd.run.SddTaskManager.getInstance().list().stream()
                    .filter(t -> t.state == com.javaclaw.task.sdd.run.SddTaskState.RUNNING
                            || t.state == com.javaclaw.task.sdd.run.SddTaskState.NEEDS_HUMAN)
                    .count();
            sidebarView.updateTaskBadge(activeTasks);
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
     */
    private void rebuildAgentService() {
        log.info("配置变更，重建三条路径服务");
        try {
            // 1. 关闭旧服务
            chatService.shutdown();
            planModeService.shutdown();
            runtime.shutdown();
            // 2. 按依赖顺序重建
            runtime = new AgentRuntime(browserManager);
            chatService = new ChatService(runtime);
            planModeService = new PlanModeService(runtime);
            chatService.setLoopInteractiveHandler(this::showLoopInteractionBubble);
            // 3. 把 registry 中持有旧 service 引用的 Mode 壳替换为新实例
            rewireModeRegistry();
            // 4. 通知外部订阅方
            com.javaclaw.schedule.ScheduleManager.getInstance().reload(
                    new com.javaclaw.agent.ScheduledTaskAgent(runtime));
            com.javaclaw.task.sdd.run.SddTaskManager.getInstance().reload(
                    com.javaclaw.config.DataManager.getInstance().getDataRoot(),
                    runtime.getModelFactory(), runtime.getCapabilityTools());
            log.info("三条路径服务重建完成");
        } catch (Exception e) {
            log.error("重建服务失败，尝试恢复", e);
            try {
                runtime = new AgentRuntime(browserManager);
                chatService = new ChatService(runtime);
                planModeService = new PlanModeService(runtime);
                chatService.setLoopInteractiveHandler(this::showLoopInteractionBubble);
                rewireModeRegistry();
                com.javaclaw.schedule.ScheduleManager.getInstance().reload(
                    new com.javaclaw.agent.ScheduledTaskAgent(runtime));
                com.javaclaw.task.sdd.run.SddTaskManager.getInstance().reload(
                        com.javaclaw.config.DataManager.getInstance().getDataRoot(),
                        runtime.getModelFactory(), runtime.getCapabilityTools());
            } catch (Exception ex) {
                log.error("恢复服务也失败", ex);
            }
        }
        // 重建后清空知识库菜单和选中状态
        knowledgeMenu.getItems().clear();
        clearKnowledgeSelection();
        // 重新绑定 TokenTracker 回调
        wireTokenTracker();
    }

    /**
     * 重新把 ChatMode / PlanMode 壳注册到 {@link ModeRegistry}。
     *
     * <p>服务实例重建后，Mode 壳里捕获的引用是旧实例，需要注销并重新注册新壳。
     * TaskMode 持有的是 {@code Supplier<Stage>} 懒引用，不受影响，无需重建。</p>
     */
    private void rewireModeRegistry() {
        modeRegistry.unregister("chat");
        modeRegistry.unregister("plan");
        modeRegistry.register(new com.javaclaw.mode.ChatMode(chatService));
        modeRegistry.register(new com.javaclaw.mode.PlanMode(planModeService));
    }

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
        log.info("打开记忆中心");
        javafx.stage.Stage ownerStage = (javafx.stage.Stage) outerRoot.getScene().getWindow();
        new com.javaclaw.ui.javafx.memory.MemoryCenterView(
                ownerStage, chatService.getMemoryService(), runtime.getKnowledgeExpert()).show();
    }

    /**
     * 打开插件中心对话框
     */
    private void openPluginCenter() {
        log.info("打开插件中心");
        javafx.stage.Stage ownerStage = (javafx.stage.Stage) outerRoot.getScene().getWindow();
        com.javaclaw.ui.javafx.plugin.PluginCenterView pluginCenterView =
                new com.javaclaw.ui.javafx.plugin.PluginCenterView(ownerStage);
        pluginCenterView.show();
    }

    /**
     * 打开 MCP 服务器独立窗口（侧边栏「MCP 服务器」入口 / ⌘M）
     */
    private void openMcpServers() {
        log.info("打开 MCP 服务器窗口");
        javafx.stage.Stage ownerStage = (javafx.stage.Stage) outerRoot.getScene().getWindow();
        com.javaclaw.ui.javafx.mcp.McpSettingsView mcpView = new com.javaclaw.ui.javafx.mcp.McpSettingsView();
        mcpView.setMcpClientManager(runtime.getMcpClientManager());
        mcpView.showAsWindow(ownerStage);
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
    private void openTaskManager() {
        log.info("打开任务管理");
        modeRegistry.getById("task")
                .filter(ActionMode.class::isInstance)
                .map(ActionMode.class::cast)
                .ifPresentOrElse(
                        ActionMode::open,
                        () -> log.warn("未注册任务模式（id=task）"));
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
     * 打开知识库管理对话框
     */
    private void openKnowledgeBase() {
        log.info("打开知识库管理");
        javafx.stage.Stage ownerStage = (javafx.stage.Stage) outerRoot.getScene().getWindow();
        KnowledgeBaseView knowledgeBaseView = new KnowledgeBaseView(
                ownerStage, runtime.getKnowledgeExpert());
        knowledgeBaseView.setOnConfigChanged(this::rebuildAgentService);
        knowledgeBaseView.show();
    }

    /**
     * 清空当前会话的对话历史
     */
    private void onClearHistory() {
        if (currentSession == null) return;
        // 流在别的会话后台运行时，清空会误伤其智能体上下文，先行拦截
        if (streamingActive && streamingSession != null && currentSession != streamingSession) {
            addStaticBubble(ChatMessage.Role.SYSTEM, "另一会话正在生成回复，完成后再清空本会话");
            return;
        }
        log.info("用户请求清空当前会话: {}", currentSession.getId());

        stopActiveStream();
        clearAllHistory();
        runtime.getTokenTracker().resetSession();
        chatService.deleteSession(currentSession.getId());
        disposeMessageList();
        currentSession.getMessages().clear();
        currentSession.setTitle("新的对话");

        // 清除附件
        pendingAttachments.clear();
        updateAttachmentPreview();

        thinkingPanel.reset();

        // 更新 UI
        addWelcomeBubble();
        updateTopTitle();
        sidebarView.updateSessionTitle(currentSession.getId(), currentSession.getTitle());
        saveCurrentSession();
    }

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
    /**
     * 构建顶栏「风格」切换菜单（设计稿 ThemeMenu 的 JavaFX 实现）：
     * 按钮 = 当前主题色块 + 「风格」；下拉项 = 三联色块预览 + 名称/副标题 + 当前 ✓。
     * 选择后经 {@link com.javaclaw.ui.javafx.theme.ThemeManager#setTheme} 全局生效并持久化。
     */
    private MenuButton buildThemeMenu() {
        MenuButton btn = new MenuButton("风格");
        btn.getStyleClass().add("theme-menu-btn");
        btn.setTooltip(new Tooltip("切换界面风格（立即生效并记忆到本工作区）"));

        // 按钮左侧的当前主题色块
        Region swatch = new Region();
        swatch.setMinSize(13, 13);
        swatch.setMaxSize(13, 13);
        btn.setGraphic(swatch);
        Runnable refreshSwatch = () -> swatch.setStyle("-fx-background-color: "
                + com.javaclaw.ui.javafx.theme.ThemeManager.getCurrentTheme().brand()
                + "; -fx-background-radius: 4;");
        refreshSwatch.run();
        com.javaclaw.ui.javafx.theme.ThemeManager.themeProperty()
                .addListener((obs, o, n) -> refreshSwatch.run());

        // 每次展开时重建菜单项（保证 ✓ 标记与当前主题同步）
        btn.setOnShowing(e -> {
            btn.getItems().clear();
            String current = com.javaclaw.ui.javafx.theme.ThemeManager.getTheme();
            for (var theme : com.javaclaw.ui.javafx.theme.ThemeManager.THEMES) {
                // 三联色块预览（品牌色 / 页面底色 / 卡片色）
                HBox tri = new HBox();
                for (String color : new String[]{theme.brand(), theme.bg(), theme.surface()}) {
                    Region cell = new Region();
                    cell.setMinSize(16, 24);
                    cell.setMaxSize(16, 24);
                    cell.setStyle("-fx-background-color: " + color + ";");
                    tri.getChildren().add(cell);
                }
                tri.setStyle("-fx-border-color: -jc-border; -fx-border-radius: 6; "
                        + "-fx-background-radius: 6; -fx-border-width: 1;");

                Label name = new Label(theme.name());
                name.setStyle("-fx-font-size: 12.5px; -fx-font-weight: 600; -fx-text-fill: -jc-text-title;");
                Label sub = new Label(theme.subtitle());
                sub.setStyle("-fx-font-size: 10.5px; -fx-text-fill: -jc-text-hint;");
                VBox text = new VBox(1, name, sub);

                Region grow = new Region();
                HBox.setHgrow(grow, Priority.ALWAYS);
                Label check = new Label(theme.id().equals(current) ? "✓" : " ");
                check.setStyle("-fx-text-fill: -jc-primary-500; -fx-font-size: 13px;");

                HBox row = new HBox(10, tri, text, grow, check);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setMinWidth(210);

                MenuItem item = new MenuItem();
                item.setGraphic(row);
                item.setOnAction(ev ->
                        com.javaclaw.ui.javafx.theme.ThemeManager.setTheme(theme.id()));
                btn.getItems().add(item);
            }
        });
        // 初始占位项，保证箭头可点开（展开时会被重建）
        btn.getItems().add(new MenuItem("…"));
        return btn;
    }

    private void onSwitchWorkspace(String targetWorkspaceId) {
        com.javaclaw.config.WorkspaceManager wsMgr = com.javaclaw.config.WorkspaceManager.getInstance();
        String fromId = wsMgr.getCurrentWorkspaceId();
        if (targetWorkspaceId.equals(fromId)) {
            return;
        }
        log.info("切换工作区: {} -> {}", fromId, targetWorkspaceId);

        // 0. 工作区切换会重建全部服务，后台流式无法跨工作区延续：先停流并清理挂起节点
        if (streamingActive) {
            stopActiveStream(false);
        }

        // 1. 保存当前状态（在旧工作区路径下，UI 线程操作）
        saveCurrentSession();
        if (currentSession != null) {
            chatService.saveSession(currentSession.getId());
        }
        browserManager.saveCookies();

        // 显示加载遮罩
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(40, 40);
        Label loadingLabel = new Label("正在切换工作区...");
        loadingLabel.getStyleClass().add("loading-overlay-label");
        VBox loadingBox = new VBox(12, spinner, loadingLabel);
        loadingBox.setAlignment(Pos.CENTER);
        StackPane loadingOverlay = new StackPane(loadingBox);
        loadingOverlay.getStyleClass().add("loading-overlay");
        chatPane.setCenter(loadingOverlay);

        // 在后台线程执行非 UI 操作（步骤 2-7）
        Thread switchThread = new Thread(() -> {
            try {
                // 2. 依次关闭三条路径，并断开共享基础设施（MCP 等）
                chatService.shutdown();
                planModeService.shutdown();
                runtime.shutdown();

                // 3. 切换工作区
                if (!wsMgr.switchWorkspace(targetWorkspaceId)) {
                    log.error("切换工作区失败: {}", targetWorkspaceId);
                    Platform.runLater(() -> chatPane.setCenter(chatCenter));
                    return;
                }

                // 4. 重新加载所有配置（路径切换到新工作区）
                com.javaclaw.config.AgentConfig.getInstance().reload();
                com.javaclaw.config.EmailConfig.getInstance().reload();
                com.javaclaw.config.NotificationConfig.getInstance().reload();
                com.javaclaw.config.DataManager.getInstance().reload();
                com.javaclaw.site.SiteCredentialManager.getInstance().reload();
                com.javaclaw.skill.SkillUsageTracker.getInstance().reload();
                com.javaclaw.skill.curation.SkillProposalQueue.getInstance().reload();

                // 5. 清除旧 Cookie
                browserManager.clearCookies();

                // 5.5 切换诊断日志文件到新工作区
                com.javaclaw.diagnostics.TraceRecorder.getInstance().reload();

                // 6. 重建共享基础设施和三条路径服务（加载新工作区的知识库等）
                runtime = new AgentRuntime(browserManager);
                chatService = new ChatService(runtime);
                planModeService = new PlanModeService(runtime);
                chatService.setLoopInteractiveHandler(this::showLoopInteractionBubble);
                rewireModeRegistry();

                // 7. 重新加载定时任务和任务管理器
                com.javaclaw.schedule.ScheduleManager.getInstance().reload(
                    new com.javaclaw.agent.ScheduledTaskAgent(runtime));
                com.javaclaw.task.sdd.run.SddTaskManager.getInstance().reload(
                        com.javaclaw.config.DataManager.getInstance().getDataRoot(),
                        runtime.getModelFactory(), runtime.getCapabilityTools());

                // 7a. 重载插件系统（旧 runtime 的能力句柄已失效，切到新 runtime 后重新发现）
                com.javaclaw.plugin.PluginManager.getInstance().reload(runtime);

                // UI 更新回到 JavaFX 线程（步骤 8-12）
                Platform.runLater(() -> {
                    try {
                        // 8. 清空所有 UI 状态
                        disposeMessageList();
                        pendingAttachments.clear();
                        updateAttachmentPreview();
                        clearActiveReferences();
                        thinkingPanel.reset();

                        // 9. 清空并重新加载会话列表
                        sidebarView.clearSessions();
                        sessions.clear();
                        currentSession = null;
                        chatHistoryManager = new ChatHistoryManager();
                        loadSessions();

                        // 10. 重置知识库菜单（清除旧工作区的文档列表和选中状态）
                        knowledgeMenu.getItems().clear();
                        clearKnowledgeSelection();

                        // 10.5. 重新绑定 TokenTracker 回调（新工作区的追踪器）
                        wireTokenTracker();

                        // 11. 重置规划模式
                        if (planModeEnabled) {
                            planModeEnabled = false;
                            chatModeBtn.setSelected(true);
                        }

                        // 12. 更新侧边栏工作区下拉
                        sidebarView.refreshWorkspaceCombo();

                        // 12.5. 重新加载新工作区记忆的界面风格
                        com.javaclaw.ui.javafx.theme.ThemeManager.reload();

                        // 12.6. 重新加载新工作区记忆的字体（族 / 等宽 / 密度）
                        com.javaclaw.ui.javafx.theme.FontManager.reload();

                        log.info("工作区切换完成: {} ({})",
                                wsMgr.getCurrentWorkspace().getName(), targetWorkspaceId);
                    } finally {
                        // 恢复消息区域（必须恢复为 chatCenter StackPane，而非内层 scrollPane，
                        // 否则空状态占位与浮动"新消息"按钮会因脱离 StackPane 失去叠加层）
                        chatPane.setCenter(chatCenter);
                    }
                });
            } catch (Exception e) {
                log.error("工作区切换异常", e);
                Platform.runLater(() -> chatPane.setCenter(chatCenter));
            }
        }, "workspace-switch-thread");
        switchThread.setDaemon(true);
        switchThread.start();
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

        Platform.runLater(() -> {
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
