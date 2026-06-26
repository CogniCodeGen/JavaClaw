package com.javaclaw.config;

import com.javaclaw.ui.javafx.agent.AgentSettingsView;
import com.javaclaw.agent.model.ModelFactory;
import com.javaclaw.app.UIHelper;
import com.javaclaw.mcp.McpClientManager;
import com.javaclaw.ui.javafx.control.ToggleSwitch;
import com.javaclaw.ui.javafx.mcp.McpSettingsView;
import com.javaclaw.ui.javafx.site.SiteCredentialView;
import javafx.animation.PauseTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 设置界面（模态对话框）
 *
 * <p>左侧分类导航 + 右侧配置面板的布局，
 * 支持按功能分类管理配置项，方便后续扩展。</p>
 *
 * @author JavaClaw
 */
public class SettingsView {

    private static final Logger log = LoggerFactory.getLogger(SettingsView.class);

    private final Stage stage;
    private final EmailConfig emailConfig;
    private final AgentConfig agentConfig;
    private final NotificationConfig notificationConfig;
    private Runnable onModelConfigChanged;
    /** 可选：注入后 MCP 设置面板可显示运行状态、热启停、测试连接 */
    private McpClientManager mcpClientManager;
    /** 可选：注入后智能体设置面板可启用 AI 智能补全提示词按钮 */
    private final ModelFactory modelFactory;
    /** 可选：注入后 AI 智能补全的 token 消耗将计入会话统计 */
    private final com.javaclaw.agent.TokenTracker tokenTracker;

    // 布局容器
    private VBox categoryList;
    private StackPane contentArea;
    private ToggleGroup categoryGroup;
    private TextField searchField;

    // ==================== 全局页脚（设计稿 modal-foot） ====================
    /** 全局「测试连接」按钮：仅当前面板支持测试时可用 */
    private Button footTestButton;
    /** 全局「保存」按钮：保存当前选中分类的配置 */
    private Button footSaveButton;
    /** 全局保存状态标签：保存后显示「✓ 已保存，下一轮对话生效」 */
    private Label footStatusLabel;
    /** 面板 → 其保存/测试动作的注册表；切换分类时据此刷新页脚按钮可用性 */
    private final java.util.Map<Node, PanelActions> panelActions = new java.util.IdentityHashMap<>();
    /** 当前选中面板（页脚保存/测试针对它生效） */
    private Node currentPanel;
    /** 面板 → 分类名（右侧面包屑「设置 › 分区名」用） */
    private final java.util.Map<Node, String> panelNames = new java.util.IdentityHashMap<>();
    /** 面包屑当前分区名标签 */
    private Label crumbCurrentLabel;
    /** 有未保存更改的面板集合（设计稿 dirty 状态：页脚显示琥珀提示、保存按钮才可用） */
    private final java.util.Set<Node> dirtyPanels =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    /** 表单装载中守卫：loadSettings 等程序性填充不计入 dirty */
    private boolean formLoading;
    /** 保存成功提示的瞬态定时器（约 2.2 秒后自动消失，与设计稿一致） */
    private PauseTransition savedTipTimer;
    /** 测试连接进行中标志（按钮显示「测试中…」并禁用） */
    private boolean testRunning;

    /**
     * 面板可被全局页脚驱动的动作注册结构。
     *
     * @param save      当前面板的保存逻辑；为 null 表示该面板不支持全局保存（组件型面板自管理）
     * @param savedTip  保存成功后状态标签的文案供给器（动态：模型/GEPA/RAG 等据 onModelConfigChanged
     *                  区分「生效/重启后生效」）；null 时用默认「✓ 已保存，下一轮对话生效」
     * @param test      当前面板的测试连接逻辑；为 null 表示该面板无连接测试，按钮禁用
     * @param testLabel 测试按钮在该面板下的文案（设计稿 TEST_CFG：测试连接/测试收发/测试嵌入）
     */
    private record PanelActions(Runnable save,
                                java.util.function.Supplier<String> savedTip,
                                Runnable test,
                                String testLabel) {
        static PanelActions saveOnly(Runnable save) {
            return new PanelActions(save, null, null, null);
        }
        static PanelActions saveOnly(Runnable save, java.util.function.Supplier<String> savedTip) {
            return new PanelActions(save, savedTip, null, null);
        }
        static PanelActions saveAndTest(Runnable save,
                                        java.util.function.Supplier<String> savedTip,
                                        Runnable test,
                                        String testLabel) {
            return new PanelActions(save, savedTip, test, testLabel);
        }
        /** 组件型面板：内部交互自管理，全局保存/测试均禁用 */
        static PanelActions none() {
            return new PanelActions(null, null, null, null);
        }
    }

    // 左侧导航分组（可折叠）
    private final java.util.List<NavGroup> navGroups = new java.util.ArrayList<>();
    private NavGroup currentNavGroup;

    /** 左侧导航的可折叠分组：维护小标题、子分类容器、组内分类按钮，记录展开/折叠状态 */
    private static final class NavGroup {
        final Label header;
        final VBox childContainer;
        final String name;
        final java.util.List<ToggleButton> categories = new java.util.ArrayList<>();
        boolean expanded;
        /** 折叠时透出到分组标题的脏标记点（组内有未保存更改时显示） */
        Region headerDot;

        NavGroup(Label header, VBox childContainer, String name) {
            this.header = header;
            this.childContainer = childContainer;
            this.name = name;
        }
    }

    // 邮件配置表单控件
    private TextField smtpHostField;
    private TextField smtpPortField;
    private TextField imapHostField;
    private TextField imapPortField;
    private TextField usernameField;
    private PasswordField passwordField;
    private TextField fromAddressField;
    /** 加密方式（设计稿 SelectField）：SSL / STARTTLS / 无，映射 EmailConfig 的两个布尔 */
    private ComboBox<String> encryptionCombo;

    // 模型配置表单控件
    private ComboBox<String> providerCombo;
    private TextField baseUrlField;
    private TextField modelNameField;
    private PasswordField apiKeyField;
    private TextField thinkingBudgetField;
    private RadioButton http11Radio;
    private RadioButton http2Radio;
    private TextField connectTimeoutField;
    private TextField readTimeoutField;
    private TextField writeTimeoutField;
    private TextField orchestratorMaxItersField;
    private TextField webAgentMaxItersField;
    private TextField emailAgentMaxItersField;
    private ToggleSwitch thinkingEnabledCheck;
    private TextField maxRepeatedCallsField;
    private TextField loopThresholdField;
    private TextField evaluatorThresholdField;
    private TextField evaluatorMaxRetriesField;

    // 通知配置表单控件
    private ToggleSwitch dingtalkEnabledCheck;
    private TextField dingtalkWebhookField;
    private PasswordField dingtalkSecretField;
    private ToggleSwitch wechatEnabledCheck;
    private TextField wechatWebhookField;
    private ToggleSwitch feishuEnabledCheck;
    private TextField feishuWebhookField;
    private PasswordField feishuSecretField;
    private ToggleSwitch emailNotifyEnabledCheck;
    private TextField emailNotifyToField;
    private ToggleSwitch customEnabledCheck;
    private TextField customWebhookField;
    private TextField customBodyTemplateField;

    // RAG 知识库配置表单控件
    private ToggleSwitch ragEnabledCheck;
    private ComboBox<String> ragProviderCombo;
    private TextField ragBaseUrlField;
    private PasswordField ragApiKeyField;
    private TextField ragModelNameField;
    private TextField ragDimensionsField;
    private TextField ragChunkSizeField;
    private TextField ragChunkOverlapField;
    private TextField ragRetrieveLimitField;
    private TextField ragScoreThresholdField;

    // 通用配置表单控件
    private ToggleSwitch trayMinimizeOnCloseCheck;
    private ToggleSwitch taskRiskAutoApproveCheck;

    // GEPA 配置表单控件
    private ToggleSwitch gepaGoalEnabledCheck;
    private TextField gepaEvalIntervalField;
    private TextField gepaEvalThresholdField;
    private ToggleSwitch gepaPlanAdaptiveCheck;
    private TextField gepaFeedbackMaxRoundsField;

    // 技能进化配置表单控件
    private ComboBox<String> skillEvolutionModeCombo;
    private TextField skillEvolutionMinToolsField;
    private TextField skillEvolutionSuccessThresholdField;
    private ToggleSwitch skillNudgeCheck;
    private ToggleSwitch skillBundlesCheck;

    // 分级模型 — 普通（NORMAL）档表单控件
    private ToggleSwitch normalTierEnabledCheck;
    private ComboBox<String> normalProviderCombo;
    private TextField normalBaseUrlField;
    private TextField normalModelNameField;
    private PasswordField normalApiKeyField;
    private ToggleSwitch normalThinkingEnabledCheck;

    // 分级模型 — 轻量（LIGHT）档表单控件
    private ToggleSwitch lightTierEnabledCheck;
    private ComboBox<String> lightProviderCombo;
    private TextField lightBaseUrlField;
    private TextField lightModelNameField;
    private PasswordField lightApiKeyField;
    private ToggleSwitch lightThinkingEnabledCheck;

    public SettingsView(Stage owner) {
        this(owner, null, null, null);
    }

    public SettingsView(Stage owner, McpClientManager mcpClientManager) {
        this(owner, mcpClientManager, null, null);
    }

    public SettingsView(Stage owner, McpClientManager mcpClientManager, ModelFactory modelFactory) {
        this(owner, mcpClientManager, modelFactory, null);
    }

    /**
     * @param mcpClientManager 注入后 MCP 面板可展示运行状态、热启停、测试连接；
     *                         为 null 时 MCP 面板退化为基础 CRUD
     * @param modelFactory     注入后智能体面板可启用 AI 智能补全提示词按钮；
     *                         为 null 时按钮自动隐藏
     * @param tokenTracker     注入后 AI 智能补全的真实 token 用量将累计到会话统计；
     *                         为 null 时该消耗不入账
     */
    public SettingsView(Stage owner, McpClientManager mcpClientManager,
                        ModelFactory modelFactory,
                        com.javaclaw.agent.TokenTracker tokenTracker) {
        this.emailConfig = EmailConfig.getInstance();
        this.agentConfig = AgentConfig.getInstance();
        this.notificationConfig = NotificationConfig.getInstance();
        this.mcpClientManager = mcpClientManager;
        this.modelFactory = modelFactory;
        this.tokenTracker = tokenTracker;
        this.stage = new Stage();
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initOwner(owner);
        stage.setTitle("设置");
        stage.setResizable(true);
        stage.setMinWidth(900);
        stage.setMinHeight(680);
        buildUI();
    }

    private void buildUI() {
        // ==================== 左侧分类导航 ====================
        categoryGroup = new ToggleGroup();
        categoryList = new VBox(4);
        categoryList.getStyleClass().add("settings-category-list");
        categoryList.setPadding(new Insets(12, 8, 12, 8));
        categoryList.setPrefWidth(140);

        // ==================== 右侧内容区域 ====================
        contentArea = new StackPane();
        contentArea.getStyleClass().add("settings-content-area");
        contentArea.setPadding(new Insets(20, 24, 16, 24));

        // ==================== 注册分类（按类型分组 + 搜索关键词） ====================
        // 核心配置：模型与智能体定义
        addCategoryGroup("核心配置");

        // 模型配置：保存（saveModelSettings + 重建回调）+ 测试连接（API 连通性）
        Node modelPanel = buildModelPanel();
        addCategory("模型配置", modelPanel, true,
                "api key base url provider openai anthropic ollama dashscope gemini 模型 思考 thinking 高级 http 超时 timeout 迭代 循环");
        registerPanelActions(modelPanel, PanelActions.saveAndTest(
                this::saveModelSettings, this::modelConfigSavedTip, this::runModelApiTest, "测试连接"));

        // 分级模型：仅保存（含重建回调）
        Node tieredModelPanel = buildTieredModelPanel();
        addCategory("分级模型", tieredModelPanel, false,
                "tier 分级 轻量 light 普通 normal 高性能 high 路由 routing 意图 intent 规划");
        registerPanelActions(tieredModelPanel, PanelActions.saveOnly(
                this::saveTieredModelSettings, this::modelConfigSavedTip));

        // 智能体：组件型面板，自管理（全局保存/测试禁用）
        AgentSettingsView agentSettingsView = new AgentSettingsView();
        agentSettingsView.setOnConfigChanged(onModelConfigChanged);
        agentSettingsView.setModelFactory(modelFactory);
        agentSettingsView.setTokenTracker(tokenTracker);
        Node agentPanel = agentSettingsView.buildPanel();
        addCategory("智能体", agentPanel, false,
                "agent expert orchestrator iters 迭代 子智能体 编排");
        registerPanelActions(agentPanel, PanelActions.none());

        // 智能能力：影响智能体推理过程的高阶能力
        addCategoryGroup("智能能力");

        Node gepaPanel = buildGepaPanel();
        addCategory("GEPA 能力", gepaPanel, false,
                "gepa 自适应 规划 trajectory 目标");
        registerPanelActions(gepaPanel, PanelActions.saveOnly(
                this::saveGepaSettings, this::modelConfigSavedTip));

        Node skillEvolutionPanel = buildSkillEvolutionPanel();
        addCategory("技能进化", skillEvolutionPanel, false,
                "skill 技能 自学习 进化 沉淀 提案 hermes");
        registerPanelActions(skillEvolutionPanel, PanelActions.saveOnly(
                this::saveSkillEvolutionSettings));

        Node ragPanel = buildRagPanel();
        addCategory("知识库", ragPanel, false,
                "rag embedding 嵌入 向量 chunk 检索 文档 knowledge");
        registerPanelActions(ragPanel, PanelActions.saveAndTest(
                this::saveRagSettings, this::modelConfigSavedTip, this::runRagEmbeddingTest, "测试嵌入"));

        // 外部集成：连接外部系统与资源
        addCategoryGroup("外部集成");

        // MCP 服务器：组件型面板，自管理
        McpSettingsView mcpSettingsView = new McpSettingsView();
        mcpSettingsView.setOnConfigChanged(onModelConfigChanged);
        mcpSettingsView.setMcpClientManager(mcpClientManager);
        Node mcpPanel = mcpSettingsView.buildPanel();
        addCategory("MCP 服务器", mcpPanel, false,
                "mcp model context protocol server 服务器 claude desktop");
        registerPanelActions(mcpPanel, PanelActions.none());

        // 站点管理：组件型面板，自管理
        SiteCredentialView siteView = new SiteCredentialView();
        Node sitePanel = siteView.buildPanel();
        addCategory("站点管理", sitePanel, false,
                "site 站点 网站 凭据 cookie 登录 自动登录 用户名 密码 password");
        registerPanelActions(sitePanel, PanelActions.none());

        // 外观：界面风格实时切换（设计稿新增能力）
        addCategoryGroup("外观");

        // 界面风格：组件型面板，点击立即生效，自管理
        Node appearancePanel = buildAppearancePanel();
        addCategory("界面风格", appearancePanel, false,
                "主题 theme 风格 外观 配色 深色 暗色 dark emerald midnight carbon sapphire ocean plum graphite terracotta honey 翡翠 午夜 碳黑 蓝宝石 海洋 梅紫 石墨 陶土 蜂蜜");
        registerPanelActions(appearancePanel, PanelActions.none());

        // 通用：界面与后台行为
        addCategoryGroup("通用");

        Node generalPanel = buildGeneralPanel();
        addCategory("通用设置", generalPanel, false,
                "托盘 tray 后台 常驻 最小化 关闭 minimize 窗口 退出 background "
                        + "托管任务 风险 评估 自动放行 确认 目录 risk autoapprove 免确认");
        registerPanelActions(generalPanel, PanelActions.saveOnly(this::saveGeneralSettings));

        // 通信渠道：邮件与外部通知
        addCategoryGroup("通信渠道");

        // 邮件配置：保存 + 测试连接（IMAP）
        Node emailPanel = buildEmailPanel();
        addCategory("邮件配置", emailPanel, false,
                "smtp imap email mail 邮箱 发件 收件");
        registerPanelActions(emailPanel, PanelActions.saveAndTest(
                this::saveSettings, () -> "✓ 已保存", this::runEmailTest, "测试收发"));

        Node notificationPanel = buildNotificationPanel();
        addCategory("通知配置", notificationPanel, false,
                "钉钉 dingtalk 企业微信 wework 飞书 lark webhook notification 通知");
        registerPanelActions(notificationPanel, PanelActions.saveOnly(
                this::saveNotificationSettings, () -> "✓ 已保存"));

        // ==================== 全局页脚（测试连接 / 状态 / 关闭 / 保存） ====================
        // 测试按钮（soft 左置）：仅当前面板支持测试时可用，文案随面板（测试连接/测试收发/测试嵌入），
        // 测试期间显示「测试中…」并禁用（设计稿 testing 状态）
        footTestButton = new Button("测试连接");
        footTestButton.getStyleClass().addAll("jc-btn", "jc-btn-soft");
        footTestButton.setOnAction(e -> {
            PanelActions actions = panelActions.getOrDefault(currentPanel, PanelActions.none());
            if (actions.test() == null || testRunning) return;
            testRunning = true;
            footTestButton.setText("测试中…");
            footTestButton.setDisable(true);
            setFooterStatus("", null);
            actions.test().run();
        });

        // 状态文字（成功绿 / 失败红 / 进行中灰 / 未保存琥珀）
        footStatusLabel = new Label();
        footStatusLabel.getStyleClass().add("settings-status");

        // 弹性空隙
        Region footSpacer = new Region();
        HBox.setHgrow(footSpacer, Priority.ALWAYS);

        // 关闭（ghost）：经关闭守卫，有未保存更改时先确认
        Button closeButton = new Button("关闭");
        closeButton.getStyleClass().addAll("jc-btn", "jc-btn-ghost");
        closeButton.setOnAction(e -> guardedClose());

        // 保存（save）：仅当前面板有未保存更改时可用；保存后显示约 2.2 秒瞬态成功提示
        footSaveButton = new Button("保存");
        footSaveButton.getStyleClass().addAll("jc-btn", "jc-btn-save");
        footSaveButton.setOnAction(e -> saveCurrentPanel());

        HBox bottomBar = new HBox(10, footTestButton, footStatusLabel, footSpacer, closeButton, footSaveButton);
        bottomBar.getStyleClass().add("modal-foot");
        bottomBar.setAlignment(Pos.CENTER_LEFT);

        // ==================== 组装主布局 ====================
        // 左侧导航栏（标题 + 搜索框 + 分类列表）
        VBox leftPane = new VBox();
        leftPane.getStyleClass().add("modal-left-pane");
        leftPane.setMinWidth(210);
        leftPane.setPrefWidth(210);
        leftPane.setMaxWidth(210);
        Label navTitle = new Label("设置");
        navTitle.getStyleClass().add("modal-left-title");
        navTitle.setPadding(new Insets(18, 16, 12, 16));

        searchField = new TextField();
        searchField.setPromptText("搜索设置…");
        searchField.getStyleClass().add("settings-search-field");
        searchField.setPadding(new Insets(6, 26, 6, 10));  // 右内边距留给清除按钮
        searchField.textProperty().addListener((obs, oldV, newV) -> applyNavVisibility());

        // 清除按钮（✕）：输入非空时浮现，贴右缘；点击清空搜索
        Button searchClear = new Button("✕");
        searchClear.getStyleClass().add("settings-search-clear");
        searchClear.setFocusTraversable(false);
        searchClear.setVisible(false);
        searchClear.setManaged(false);
        searchClear.setOnAction(e -> { searchField.clear(); searchField.requestFocus(); });
        searchField.textProperty().addListener((obs, oldV, newV) -> {
            boolean has = newV != null && !newV.isEmpty();
            searchClear.setVisible(has);
            searchClear.setManaged(has);
        });
        StackPane searchStack = new StackPane(searchField, searchClear);
        StackPane.setAlignment(searchClear, Pos.CENTER_RIGHT);
        StackPane.setMargin(searchClear, new Insets(0, 4, 0, 0));

        // 键盘：Enter 跳转第一个命中分区；Esc 先清搜索（非空时拦截，不关弹窗）
        searchField.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case ENTER -> {
                    String q = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase();
                    if (!q.isEmpty()) jumpToFirstMatch(q);
                }
                case ESCAPE -> {
                    if (searchField.getText() != null && !searchField.getText().isEmpty()) {
                        searchField.clear();
                        e.consume();  // 拦下本次 Esc，不冒泡到关闭快捷键
                    }
                }
                default -> { }
            }
        });

        VBox searchWrap = new VBox(searchStack);
        searchWrap.setPadding(new Insets(0, 12, 8, 12));

        // 初始化分组折叠状态（含初始选中项的分组保持展开）
        applyNavVisibility();

        leftPane.getChildren().addAll(navTitle, searchWrap, new Separator(), categoryList);
        VBox.setVgrow(categoryList, Priority.ALWAYS);

        // 右侧顶部面包屑（设计稿 win-title：设置 › 当前分区）
        Label crumbRoot = new Label("设置");
        crumbRoot.getStyleClass().add("settings-crumb-root");
        Label crumbSep = new Label("›");
        crumbSep.getStyleClass().add("settings-crumb-sep");
        crumbCurrentLabel = new Label(panelNames.getOrDefault(currentPanel, ""));
        crumbCurrentLabel.getStyleClass().add("settings-crumb-cur");
        HBox crumbBar = new HBox(6, crumbRoot, crumbSep, crumbCurrentLabel);
        crumbBar.getStyleClass().add("settings-crumb");
        crumbBar.setAlignment(Pos.CENTER_LEFT);

        // 右侧面包屑 + 内容 + 底部按钮
        VBox rightPane = new VBox();
        rightPane.getChildren().addAll(crumbBar, contentArea, bottomBar);
        VBox.setVgrow(contentArea, Priority.ALWAYS);
        HBox.setHgrow(rightPane, Priority.ALWAYS);

        // 水平分栏
        HBox mainLayout = new HBox();
        mainLayout.getStyleClass().add("settings-root");
        mainLayout.getChildren().addAll(leftPane, rightPane);

        Scene scene = new Scene(mainLayout, 1100, 760);

        // 加载 CSS（controls.css 提供 ToggleSwitch 样式，依赖 chat.css 令牌须在其后）
        for (String css : new String[]{"/css/chat.css", "/css/controls.css"}) {
            var url = getClass().getResource(css);
            if (url != null) {
                scene.getStylesheets().add(url.toExternalForm());
            }
        }

        // ESCAPE 关闭窗口（经关闭守卫；搜索框内的 Esc 已在其 onKeyPressed 拦截用于清搜索）
        scene.getAccelerators().put(
                new javafx.scene.input.KeyCodeCombination(javafx.scene.input.KeyCode.ESCAPE),
                this::guardedClose);
        // ⌘S / Ctrl+S 保存当前分区（SHORTCUT_DOWN 在 macOS 映射 ⌘、其余平台映射 Ctrl）
        scene.getAccelerators().put(
                new javafx.scene.input.KeyCodeCombination(javafx.scene.input.KeyCode.S,
                        javafx.scene.input.KeyCombination.SHORTCUT_DOWN),
                this::saveCurrentPanel);

        // 窗口关闭按钮（标题栏 ✕）同样经守卫
        stage.setOnCloseRequest(e -> {
            if (!confirmDiscardIfDirty()) e.consume();
        });

        stage.setScene(scene);

        // 加载当前配置到表单（formLoading 守卫内，程序性填充不计 dirty）
        loadSettings();

        // 数值字段实时范围校验（设计稿 NumField：输入即校验，红框 + 范围提示；在加载后挂避免初始填充误报）
        attachLiveValidations();

        // 为所有支持全局保存的面板挂 dirty 监听（设计稿 markDirty：任何输入即标记未保存）
        for (var entry : panelActions.entrySet()) {
            if (entry.getValue().save() != null) {
                watchDirty(entry.getKey(), entry.getKey());
            }
        }

        // 按初始选中面板刷新页脚按钮可用性
        refreshFooter();
    }

    /**
     * 添加一个设置分类
     *
     * @param name     分类名称
     * @param panel    对应的配置面板
     * @param selected 是否默认选中
     */
    private void addCategory(String name, Node panel, boolean selected) {
        addCategory(name, panel, selected, "");
    }

    /**
     * 注册某面板的全局页脚动作（保存/测试连接）。
     * 未注册的面板视为组件型面板（none），全局保存/测试禁用。
     */
    private void registerPanelActions(Node panel, PanelActions actions) {
        panelActions.put(panel, actions);
    }

    /**
     * 在左侧导航中添加一个可折叠的分组小标题 + 缩进的子分类容器。
     * 默认折叠；包含初始选中分类的分组会被自动展开；搜索命中时也会自动展开。
     */
    private void addCategoryGroup(String name) {
        Label header = new Label();
        header.getStyleClass().addAll("settings-nav-group", "modal-nav-group");
        header.setMaxWidth(Double.MAX_VALUE);

        // 折叠时透出的脏标记点：挂在分组标题右侧（graphic + RIGHT 内容显示）
        Region headerDot = new Region();
        headerDot.getStyleClass().add("nav-dirty-dot");
        headerDot.setVisible(false);
        headerDot.setManaged(false);
        header.setGraphic(headerDot);
        header.setContentDisplay(ContentDisplay.RIGHT);
        header.setGraphicTextGap(6);

        VBox childContainer = new VBox(2);
        childContainer.getStyleClass().add("modal-nav-children");

        NavGroup group = new NavGroup(header, childContainer, name);
        group.headerDot = headerDot;
        header.setOnMouseClicked(e -> {
            // 搜索状态下点击不切换 expanded（避免与搜索自动展开冲突），仅在无搜索时生效
            if (searchField == null || searchField.getText() == null || searchField.getText().trim().isEmpty()) {
                group.expanded = !group.expanded;
                applyNavVisibility();
            }
        });
        navGroups.add(group);
        currentNavGroup = group;
        categoryList.getChildren().addAll(header, childContainer);
        renderGroupHeader(group, false);
    }

    /** 渲染分组标题文本：箭头 + 名称（▾ 展开 / ▸ 折叠） */
    private void renderGroupHeader(NavGroup group, boolean expandedDisplay) {
        group.header.setText((expandedDisplay ? "▾  " : "▸  ") + group.name);
    }

    /**
     * 添加一个设置分类（带搜索关键词，用于左侧搜索框模糊匹配）
     */
    private void addCategory(String name, Node panel, boolean selected, String keywords) {
        panelNames.put(panel, name);
        ToggleButton btn = new ToggleButton(name);
        btn.getStyleClass().add("modal-nav-btn");
        btn.setToggleGroup(categoryGroup);
        btn.setMaxWidth(Double.MAX_VALUE);
        // 用 userData 存储 "name + keywords" 用于搜索匹配
        btn.setUserData((name + " " + (keywords == null ? "" : keywords)).toLowerCase());

        // 导航项内容：[文本/高亮 holder（撑开）] + [脏标记点]，整体作为 graphic 铺满按钮宽度，
        // 使脏标记点贴右缘（设计稿 set-nav-item：flex 内容 + 末尾 dirty-dot）。
        Region navDot = new Region();
        navDot.getStyleClass().add("nav-dirty-dot");
        navDot.setVisible(false);
        navDot.setManaged(false);
        HBox navContent = new HBox();
        navContent.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(navContent, Priority.ALWAYS);
        HBox navGraphic = new HBox(6, navContent, navDot);
        navGraphic.setAlignment(Pos.CENTER_LEFT);
        navGraphic.prefWidthProperty().bind(btn.widthProperty().subtract(26));
        btn.setGraphic(navGraphic);
        btn.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        btn.getProperties().put("navDot", navDot);
        btn.getProperties().put("navContent", navContent);
        btn.getProperties().put("navPanel", panel);
        applyNavHighlight(btn, "");  // 填充初始纯文本到 holder

        // 内容列限宽 760px（智能体主从布局除外，铺满）——设计稿 maxWidth 760 / agent: none
        if (!"智能体".equals(name) && panel instanceof ScrollPane sp
                && sp.getContent() instanceof Region content) {
            content.setMaxWidth(760);
        }

        // 切换分类时显示对应面板
        btn.setOnAction(e -> {
            if (btn.isSelected()) {
                showPanel(panel);
            } else {
                // 不允许取消选中，保持当前选中
                btn.setSelected(true);
            }
        });

        contentArea.getChildren().add(panel);
        panel.setVisible(selected);
        panel.setManaged(selected);

        if (selected) {
            btn.setSelected(true);
            currentPanel = panel;  // 记录初始选中面板，供页脚首次刷新
        }

        // 加入当前分组的缩进子容器：含初始选中项的分组默认展开
        if (currentNavGroup != null) {
            currentNavGroup.categories.add(btn);
            currentNavGroup.childContainer.getChildren().add(btn);
            if (selected) {
                currentNavGroup.expanded = true;
            }
        } else {
            // 兜底：未声明分组时，仍直接挂到 categoryList
            categoryList.getChildren().add(btn);
        }
    }

    /**
     * 根据折叠状态与搜索词刷新左侧导航的可见性。
     * <p>规则：
     * <ul>
     *   <li>无搜索：分类按钮按所属分组的 expanded 状态显隐；分组小标题恒可见</li>
     *   <li>有搜索：仅显示命中关键字的分类按钮；命中分组小标题强制以"展开"样式呈现，
     *       未命中分组则与其下分类一并隐藏（折叠状态不变，搜索清空后恢复原样）</li>
     * </ul>
     */
    private void applyNavVisibility() {
        String q = searchField == null ? "" : (searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase());
        boolean searching = !q.isEmpty();

        for (NavGroup group : navGroups) {
            int matchCount = 0;
            for (ToggleButton btn : group.categories) {
                boolean match = !searching
                        || (btn.getUserData() instanceof String idx && idx.contains(q));
                if (match) matchCount++;
                // 搜索状态下按命中过滤；非搜索状态下保持组内全部按钮可见，由子容器统一控制显隐
                boolean btnShow = !searching || match;
                btn.setVisible(btnShow);
                btn.setManaged(btnShow);
                // 命中文字高亮（设计稿 Hl：分类名中命中片段以品牌色 mark 呈现）
                applyNavHighlight(btn, searching && match ? q : "");
            }
            // 子容器：搜索时若有命中则展开；否则按 expanded 控制
            boolean containerShow = searching ? matchCount > 0 : group.expanded;
            group.childContainer.setVisible(containerShow);
            group.childContainer.setManaged(containerShow);
            // 分组小标题：搜索时若组内全无命中则隐藏
            boolean headerShow = !searching || matchCount > 0;
            group.header.setVisible(headerShow);
            group.header.setManaged(headerShow);
            // 箭头：搜索时若有命中即"展开"；否则按 expanded
            renderGroupHeader(group, containerShow);
        }
        refreshNavDirtyMarks();
    }

    /**
     * 切换显示指定面板，刷新面包屑与全局页脚；切换时滚动回顶并做轻微淡入位移
     * （面板始终挂在场景图中，切换不丢未保存编辑——设计稿「面板保活」在 JavaFX 中天然成立）。
     */
    private void showPanel(Node target) {
        boolean changed = target != currentPanel;
        for (Node child : contentArea.getChildren()) {
            child.setVisible(child == target);
            child.setManaged(child == target);
        }
        currentPanel = target;
        if (crumbCurrentLabel != null) {
            crumbCurrentLabel.setText(panelNames.getOrDefault(target, ""));
        }
        if (changed) {
            // 滚动回顶
            if (target instanceof ScrollPane sp) sp.setVvalue(0);
            // 轻微进场位移（仅平移，不经 opacity:0，避免冻结帧不可见——与设计稿 panelIn 一致）
            target.setTranslateY(5);
            TranslateTransition tt = new TranslateTransition(javafx.util.Duration.millis(180), target);
            tt.setFromY(5);
            tt.setToY(0);
            tt.play();
        }
        refreshFooter();
    }

    /** 保存当前分区（页脚「保存」按钮与 ⌘S 共用）；无 save 动作或无未保存更改时为空操作。 */
    private void saveCurrentPanel() {
        PanelActions actions = panelActions.getOrDefault(currentPanel, PanelActions.none());
        if (actions.save() == null || !dirtyPanels.contains(currentPanel)) return;
        actions.save().run();
        dirtyPanels.remove(currentPanel);
        footSaveButton.setDisable(true);
        showSavedTip(actions.savedTip() != null
                ? actions.savedTip().get() : "✓ 已保存，下一轮对话生效");
        refreshNavDirtyMarks();
    }

    /** 关闭守卫：有未保存更改时弹确认，确认放弃才真正关闭。 */
    private void guardedClose() {
        if (confirmDiscardIfDirty()) stage.close();
    }

    /** 若存在未保存更改则弹确认；返回 true 表示可继续关闭（无更改或用户确认放弃）。 */
    private boolean confirmDiscardIfDirty() {
        int n = dirtyPanels.size();
        if (n == 0) return true;
        Alert alert = UIHelper.createConfirmAlert("放弃未保存的更改",
                "有 " + n + " 个分区存在未保存的更改，确定关闭？", stage);
        return alert.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }

    /** 跳转到搜索命中的第一个分区（按导航声明顺序），用于搜索框回车。 */
    private void jumpToFirstMatch(String q) {
        for (NavGroup group : navGroups) {
            for (ToggleButton btn : group.categories) {
                if (btn.getUserData() instanceof String idx && idx.contains(q)) {
                    group.expanded = true;
                    btn.setSelected(true);
                    if (btn.getProperties().get("navPanel") instanceof Node panel) {
                        showPanel(panel);
                    }
                    return;
                }
            }
        }
    }

    /**
     * 切换分类后刷新全局页脚：清空瞬态状态（测试结果随面板切换复位，设计稿 useEffect [sel]）、
     * 按当前面板的注册动作刷新保存/测试可用性与测试按钮文案、按 dirty 状态恢复未保存提示。
     */
    private void refreshFooter() {
        if (footSaveButton == null) return;  // UI 尚未组装完成（构建阶段调用 showPanel）
        setFooterStatus("", null);

        PanelActions actions = panelActions.getOrDefault(currentPanel, PanelActions.none());

        // 保存按钮：当前面板提供 save 且有未保存更改时才可用（设计稿 disabled={!dirty}）
        boolean canSave = actions.save() != null;
        footSaveButton.setDisable(!canSave || !dirtyPanels.contains(currentPanel));
        footSaveButton.setTooltip(new Tooltip(canSave
                ? "⌘S / Ctrl+S" : "当前分区的更改在分区内即时生效或保存"));

        // 测试按钮：仅当前面板提供 test 时可用，文案随面板定制
        boolean canTest = actions.test() != null;
        footTestButton.setText(canTest && actions.testLabel() != null ? actions.testLabel() : "测试连接");
        footTestButton.setDisable(!canTest || testRunning);
        footTestButton.setTooltip(canTest ? null : new Tooltip("当前分区无连接测试"));

        // 有未保存更改 → 恢复琥珀提示
        if (dirtyPanels.contains(currentPanel)) {
            showUnsavedHint();
        }
        refreshNavDirtyMarks();
    }

    // ==================== dirty 跟踪与页脚状态（设计稿 markDirty / saved / testRes） ====================

    /**
     * 递归遍历面板子树，为所有可编辑控件挂变更监听 → 标记该面板 dirty。
     * 带 {@code jc-dirty-exempt} 属性的控件除外（如立即生效的主题下拉）。
     */
    private void watchDirty(Node node, Node panel) {
        if (node == null || node.getProperties().containsKey("jc-dirty-exempt")) return;
        switch (node) {
            case TextInputControl t -> t.textProperty().addListener((obs, o, n) -> markPanelDirty(panel));
            case CheckBox c -> c.selectedProperty().addListener((obs, o, n) -> markPanelDirty(panel));
            case RadioButton r -> r.selectedProperty().addListener((obs, o, n) -> markPanelDirty(panel));
            // 分段控件按钮（状态载体 ComboBox 不在场景图中，须直接监听分段选中态）
            case ToggleButton tb when tb.getStyleClass().contains("seg-btn") ->
                    tb.selectedProperty().addListener((obs, o, n) -> markPanelDirty(panel));
            case ToggleSwitch s -> s.selectedProperty().addListener((obs, o, n) -> markPanelDirty(panel));
            case ComboBox<?> c -> c.valueProperty().addListener((obs, o, n) -> markPanelDirty(panel));
            case ScrollPane sp -> watchDirty(sp.getContent(), panel);
            case javafx.scene.Parent p -> {
                for (Node child : p.getChildrenUnmodifiable()) {
                    watchDirty(child, panel);
                }
            }
            default -> { }
        }
    }

    /** 标记面板有未保存更改：页脚琥珀提示 + 启用保存按钮（表单装载期间忽略） */
    private void markPanelDirty(Node panel) {
        if (formLoading) return;
        dirtyPanels.add(panel);
        if (panel == currentPanel && footSaveButton != null) {
            PanelActions actions = panelActions.getOrDefault(panel, PanelActions.none());
            footSaveButton.setDisable(actions.save() == null);
            showUnsavedHint();
        }
        refreshNavDirtyMarks();
    }

    /** 页脚显示「有未保存的更改」琥珀提示（带圆点 graphic） */
    private void showUnsavedHint() {
        if (savedTipTimer != null) savedTipTimer.stop();
        Region dot = new Region();
        dot.getStyleClass().add("status-warn-dot");
        footStatusLabel.setGraphic(dot);
        footStatusLabel.setGraphicTextGap(6);
        footStatusLabel.setText("有未保存的更改");
        footStatusLabel.getStyleClass().removeAll("status-success", "status-error", "status-info", "status-warn");
        footStatusLabel.getStyleClass().add("status-warn");
    }

    /** 页脚显示保存成功瞬态提示，约 2.2 秒后自动消失（设计稿 setTimeout 2200ms） */
    private void showSavedTip(String text) {
        setFooterStatus(text, "status-success");
        if (savedTipTimer == null) {
            savedTipTimer = new PauseTransition(javafx.util.Duration.millis(2200));
        }
        savedTipTimer.stop();
        savedTipTimer.setOnFinished(e -> {
            // 仅当仍显示这条保存提示时才清空（避免覆盖其后出现的测试结果/未保存提示）
            if (text.equals(footStatusLabel.getText())) {
                setFooterStatus("", null);
            }
        });
        savedTipTimer.playFromStart();
    }

    /** 统一设置页脚状态标签的文字与着色类（null 表示仅清空） */
    private void setFooterStatus(String text, String cssClass) {
        if (savedTipTimer != null) savedTipTimer.stop();
        footStatusLabel.setGraphic(null);
        footStatusLabel.setText(text);
        footStatusLabel.getStyleClass().removeAll("status-success", "status-error", "status-info", "status-warn");
        if (cssClass != null) {
            footStatusLabel.getStyleClass().add(cssClass);
        }
    }

    /** 测试结束回调：恢复测试按钮文案/可用性并写入结果（异步测试线程经 Platform.runLater 调用） */
    private void finishTest(String resultText, String cssClass) {
        testRunning = false;
        PanelActions actions = panelActions.getOrDefault(currentPanel, PanelActions.none());
        footTestButton.setText(actions.test() != null && actions.testLabel() != null
                ? actions.testLabel() : "测试连接");
        footTestButton.setDisable(actions.test() == null);
        setFooterStatus(resultText, cssClass);
    }

    /**
     * 导航搜索命中高亮：在导航项的文本 holder 内渲染。q 为空显示纯文本，否则以
     * 「前段 + mark + 后段」Label 组替换；脏标记点是 holder 的兄弟节点，不受此影响。
     */
    private void applyNavHighlight(ToggleButton btn, String q) {
        if (!(btn.getProperties().get("navContent") instanceof HBox content)) return;
        String name = btn.getText();
        content.getChildren().clear();
        int i = q.isEmpty() ? -1 : name.toLowerCase().indexOf(q);
        if (i < 0) {
            Label plain = new Label(name);
            plain.getStyleClass().add("modal-nav-plain");
            content.getChildren().add(plain);
            return;
        }
        Label before = new Label(name.substring(0, i));
        before.getStyleClass().add("modal-nav-plain");
        Label mark = new Label(name.substring(i, i + q.length()));
        mark.getStyleClass().add("modal-nav-hl");
        Label after = new Label(name.substring(i + q.length()));
        after.getStyleClass().add("modal-nav-plain");
        content.getChildren().addAll(before, mark, after);
    }

    /**
     * 刷新左侧导航的脏标记点：每个分类项按其面板是否在 dirtyPanels 中显隐末尾圆点；
     * 分组标题在「折叠且组内有未保存更改」时透出圆点（设计稿 dirty-dot）。
     */
    private void refreshNavDirtyMarks() {
        for (NavGroup group : navGroups) {
            boolean anyDirty = false;
            for (ToggleButton btn : group.categories) {
                Node panel = (Node) btn.getProperties().get("navPanel");
                boolean dirty = panel != null && dirtyPanels.contains(panel);
                if (dirty) anyDirty = true;
                if (btn.getProperties().get("navDot") instanceof Region dot) {
                    dot.setVisible(dirty);
                    dot.setManaged(dirty);
                }
            }
            if (group.headerDot != null) {
                boolean collapsed = !group.childContainer.isVisible();
                boolean show = collapsed && anyDirty;
                group.headerDot.setVisible(show);
                group.headerDot.setManaged(show);
            }
        }
    }

    /**
     * 模型相关面板（模型配置/分级模型/GEPA/知识库）的保存后副作用：
     * 触发智能体服务重建回调，并按是否注入回调返回对应状态文案。
     */
    private String modelConfigSavedTip() {
        if (onModelConfigChanged != null) {
            onModelConfigChanged.run();
            return "✓ 已保存并生效，下一轮对话重建智能体服务";
        }
        return "✓ 已保存（重启后生效）";
    }

    // ==================== 模型配置面板 ====================

    private Node buildModelPanel() {
        Label sectionTitle = new Label("模型配置");
        sectionTitle.getStyleClass().add("settings-section-title");

        // ==================== 基本配置 / 高级配置 子标签切换 ====================
        ToggleGroup subTabGroup = new ToggleGroup();
        ToggleButton basicTab = new ToggleButton("基本配置");
        basicTab.setToggleGroup(subTabGroup);
        basicTab.getStyleClass().addAll("settings-sub-tab", "settings-sub-tab-left");
        basicTab.setSelected(true);

        ToggleButton advancedTab = new ToggleButton("高级配置");
        advancedTab.setToggleGroup(subTabGroup);
        advancedTab.getStyleClass().addAll("settings-sub-tab", "settings-sub-tab-right");

        HBox subTabBar = new HBox(0, basicTab, advancedTab);
        subTabBar.getStyleClass().add("settings-sub-tab-bar");

        // ==================== 基本配置面板内容 ====================

        // 模型提供商
        Label providerTitle = new Label("模型提供商");
        providerTitle.getStyleClass().add("settings-group-title");

        // providerCombo 作为状态载体保留（loadSettings/save 仍读写它），展示换为设计稿分段控件
        providerCombo = new ComboBox<>();
        providerCombo.getItems().addAll("OpenAI", "DashScope", "Anthropic", "Gemini", "Ollama");
        providerCombo.getStyleClass().add("settings-combo");
        providerCombo.setOnAction(e -> applyProviderPreset(providerCombo.getValue()));

        // 分段控件（沉陷容器 + 选中浮起白片），与 providerCombo 双向同步
        ToggleGroup providerSegGroup = new ToggleGroup();
        HBox providerSeg = new HBox(2);
        providerSeg.getStyleClass().add("seg-container");
        providerSeg.setAlignment(Pos.CENTER_LEFT);
        for (String p : new String[]{"OpenAI", "DashScope", "Anthropic", "Gemini", "Ollama"}) {
            ToggleButton tb = new ToggleButton(p);
            tb.getStyleClass().add("seg-btn");
            tb.setToggleGroup(providerSegGroup);
            tb.setUserData(p);
            tb.setOnAction(e -> {
                if (!tb.isSelected()) {
                    tb.setSelected(true);  // 不允许取消选中
                    return;
                }
                if (!p.equals(providerCombo.getValue())) {
                    providerCombo.setValue(p);  // 触发 onAction → applyProviderPreset
                }
            });
            providerSeg.getChildren().add(tb);
        }
        providerCombo.valueProperty().addListener((obs, o, n) -> {
            for (Node node : providerSeg.getChildren()) {
                ToggleButton tb = (ToggleButton) node;
                tb.setSelected(tb.getUserData().equals(n));
            }
        });

        Label providerHint = new Label("切换提供商会自动填充推荐的 API 地址和模型名称");
        providerHint.getStyleClass().add("settings-hint");

        HBox providerRow = new HBox(10, createLabel("提供商："), providerSeg);
        providerRow.setAlignment(Pos.CENTER_LEFT);

        // API 连接
        Label apiTitle = new Label("API 连接");
        apiTitle.getStyleClass().add("settings-group-title");

        baseUrlField = createTextField("API 地址");
        baseUrlField.setPrefWidth(350);
        modelNameField = createTextField("模型名称");
        modelNameField.setPrefWidth(350);
        apiKeyField = new PasswordField();
        apiKeyField.setPromptText("API 密钥");
        apiKeyField.setPrefWidth(350);

        GridPane apiGrid = new GridPane();
        apiGrid.setHgap(10);
        apiGrid.setVgap(8);
        apiGrid.add(createLabel("API 地址："), 0, 0);
        apiGrid.add(baseUrlField, 1, 0);
        apiGrid.add(createLabel("模型名称："), 0, 1);
        apiGrid.add(modelNameField, 1, 1);
        apiGrid.add(createLabel("API 密钥："), 0, 2);
        apiGrid.add(secretField(apiKeyField), 1, 2);

        // 模型参数
        Label paramTitle = new Label("模型参数");
        paramTitle.getStyleClass().add("settings-group-title");

        thinkingEnabledCheck = new ToggleSwitch();
        HBox thinkingRow = toggleRow(thinkingEnabledCheck, "思考模式",
                "支持的模型生效（DashScope / Anthropic / Gemini 部分模型），OpenAI 兼容端启用会被忽略");

        thinkingBudgetField = createTextField("思考预算（token 数）");
        thinkingBudgetField.setPrefWidth(120);

        // 思考模式关闭时禁用预算输入
        thinkingEnabledCheck.selectedProperty().addListener((obs, oldVal, newVal) ->
                thinkingBudgetField.setDisable(!newVal));

        HBox paramRow = new HBox(10, createLabel("思考预算："), thinkingBudgetField);
        paramRow.setAlignment(Pos.CENTER_LEFT);

        // 恢复默认按钮（保留在面板内；保存/测试连接已上移全局页脚）
        Button resetButton = new Button("恢复默认");
        resetButton.getStyleClass().add("settings-reset-button");
        resetButton.setOnAction(e -> {
            Alert confirm = UIHelper.createConfirmAlert("重置配置",
                    "确定要恢复所有模型配置为默认值？", null);
            confirm.showAndWait().ifPresent(btn -> {
                if (btn == ButtonType.OK) {
                    agentConfig.resetToDefaults();
                    runFormLoad(this::loadModelSettings);
                    dirtyPanels.remove(currentPanel);
                    refreshFooter();
                    setFooterStatus("已恢复为默认配置", "status-info");
                }
            });
        });

        // 配置文件路径提示
        Label pathHint = new Label("配置文件: " + agentConfig.getConfigFilePath());
        pathHint.getStyleClass().add("settings-hint");

        Label restartHint = new Label("修改保存后立即生效，当前对话的智能体服务将重建");
        restartHint.getStyleClass().add("settings-hint");

        HBox basicButtonBar = new HBox(10, resetButton);
        basicButtonBar.setAlignment(Pos.CENTER_LEFT);

        // 基本配置面板
        VBox basicPanel = new VBox(12,
                providerTitle, providerRow, providerHint,
                new Separator(),
                apiTitle, apiGrid,
                new Separator(),
                paramTitle, thinkingRow, paramRow,
                new Separator(),
                pathHint, restartHint,
                basicButtonBar);

        // ==================== 高级配置面板内容 ====================

        // HTTP 版本
        Label httpVersionTitle = new Label("HTTP 版本");
        httpVersionTitle.getStyleClass().add("settings-group-title");

        ToggleGroup httpVersionGroup = new ToggleGroup();
        http11Radio = new RadioButton("HTTP/1.1");
        http11Radio.setToggleGroup(httpVersionGroup);
        http11Radio.getStyleClass().add("settings-checkbox");
        http2Radio = new RadioButton("HTTP/2");
        http2Radio.setToggleGroup(httpVersionGroup);
        http2Radio.getStyleClass().add("settings-checkbox");

        HBox httpVersionRow = new HBox(20, http11Radio, http2Radio);
        httpVersionRow.setAlignment(Pos.CENTER_LEFT);

        // 超时配置
        Label timeoutTitle = new Label("超时配置（秒）");
        timeoutTitle.getStyleClass().add("settings-group-title");

        connectTimeoutField = createTextField("连接超时");
        connectTimeoutField.setPrefWidth(80);
        readTimeoutField = createTextField("读取超时");
        readTimeoutField.setPrefWidth(80);
        writeTimeoutField = createTextField("写入超时");
        writeTimeoutField.setPrefWidth(80);

        HBox timeoutRow = new HBox(10,
                createLabel("连接："), connectTimeoutField,
                createLabel("读取："), readTimeoutField,
                createLabel("写入："), writeTimeoutField);
        timeoutRow.setAlignment(Pos.CENTER_LEFT);

        // 智能体迭代次数
        Label iterTitle = new Label("智能体最大迭代次数");
        iterTitle.getStyleClass().add("settings-group-title");

        orchestratorMaxItersField = createTextField("编排智能体");
        orchestratorMaxItersField.setPrefWidth(80);
        webAgentMaxItersField = createTextField("Web 智能体");
        webAgentMaxItersField.setPrefWidth(80);
        emailAgentMaxItersField = createTextField("邮件智能体");
        emailAgentMaxItersField.setPrefWidth(80);

        HBox iterRow = new HBox(10,
                createLabel("编排："), orchestratorMaxItersField,
                createLabel("Web："), webAgentMaxItersField,
                createLabel("邮件："), emailAgentMaxItersField);
        iterRow.setAlignment(Pos.CENTER_LEFT);

        // 循环检测
        Label loopTitle = new Label("循环检测");
        loopTitle.getStyleClass().add("settings-group-title");

        maxRepeatedCallsField = createTextField("最大重复次数");
        maxRepeatedCallsField.setPrefWidth(80);
        loopThresholdField = createTextField("相似度阈值（0~1）");
        loopThresholdField.setPrefWidth(120);

        HBox loopRow = new HBox(10,
                createLabel("最大重复："), maxRepeatedCallsField,
                createLabel("相似阈值："), loopThresholdField);
        loopRow.setAlignment(Pos.CENTER_LEFT);

        // 任务评估
        Label evalTitle = new Label("任务评估");
        evalTitle.getStyleClass().add("settings-group-title");

        evaluatorThresholdField = createTextField("通过阈值（1~5）");
        evaluatorThresholdField.setPrefWidth(80);
        evaluatorMaxRetriesField = createTextField("最大重试次数");
        evaluatorMaxRetriesField.setPrefWidth(80);

        HBox evalRow = new HBox(10,
                createLabel("通过阈值："), evaluatorThresholdField,
                createLabel("最大重试："), evaluatorMaxRetriesField);
        evalRow.setAlignment(Pos.CENTER_LEFT);

        Label evalHint = new Label("启用评估后建议将编排最大迭代次数调整至 15 以上");
        evalHint.getStyleClass().add("settings-hint");

        // 高级配置的恢复默认按钮（保存上移全局页脚；与基本配置共用一套 saveModelSettings）
        Button advResetButton = new Button("恢复默认");
        advResetButton.getStyleClass().add("settings-reset-button");
        advResetButton.setOnAction(e -> {
            Alert confirm = UIHelper.createConfirmAlert("重置配置",
                    "确定要恢复所有模型配置为默认值？", null);
            confirm.showAndWait().ifPresent(btn -> {
                if (btn == ButtonType.OK) {
                    agentConfig.resetToDefaults();
                    runFormLoad(this::loadModelSettings);
                    dirtyPanels.remove(currentPanel);
                    refreshFooter();
                    setFooterStatus("已恢复为默认配置", "status-info");
                }
            });
        });

        Label advPathHint = new Label("配置文件: " + agentConfig.getConfigFilePath());
        advPathHint.getStyleClass().add("settings-hint");

        Label advRestartHint = new Label("修改保存后立即生效，当前对话的智能体服务将重建");
        advRestartHint.getStyleClass().add("settings-hint");

        HBox advButtonBar = new HBox(10, advResetButton);
        advButtonBar.setAlignment(Pos.CENTER_LEFT);

        // 高级配置面板
        VBox advancedPanel = new VBox(12,
                httpVersionTitle, httpVersionRow,
                new Separator(),
                timeoutTitle, timeoutRow,
                new Separator(),
                iterTitle, iterRow,
                new Separator(),
                loopTitle, loopRow,
                new Separator(),
                evalTitle, evalRow, evalHint,
                new Separator(),
                advPathHint, advRestartHint,
                advButtonBar);
        advancedPanel.setVisible(false);
        advancedPanel.setManaged(false);

        // ==================== 子标签切换逻辑 ====================
        basicTab.setOnAction(e -> {
            if (basicTab.isSelected()) {
                basicPanel.setVisible(true);
                basicPanel.setManaged(true);
                advancedPanel.setVisible(false);
                advancedPanel.setManaged(false);
            } else {
                basicTab.setSelected(true);
            }
        });

        advancedTab.setOnAction(e -> {
            if (advancedTab.isSelected()) {
                advancedPanel.setVisible(true);
                advancedPanel.setManaged(true);
                basicPanel.setVisible(false);
                basicPanel.setManaged(false);
            } else {
                advancedTab.setSelected(true);
            }
        });

        StackPane subContent = new StackPane(basicPanel, advancedPanel);

        // 组装面板
        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.getStyleClass().add("settings-scroll-pane");

        VBox panel = new VBox(12, sectionTitle, subTabBar, subContent);
        panel.setPadding(new Insets(4));

        scrollPane.setContent(panel);
        return scrollPane;
    }

    /**
     * 将模型配置加载到表单
     */
    private void loadModelSettings() {
        providerCombo.setValue(agentConfig.getProviderType());
        baseUrlField.setText(agentConfig.getBaseUrl());
        modelNameField.setText(agentConfig.getModelName());
        apiKeyField.setText(agentConfig.getApiKey());
        thinkingEnabledCheck.setSelected(agentConfig.isThinkingEnabled());
        thinkingBudgetField.setText(String.valueOf(agentConfig.getThinkingBudget()));
        thinkingBudgetField.setDisable(!agentConfig.isThinkingEnabled());
        if (agentConfig.isHttp2()) {
            http2Radio.setSelected(true);
        } else {
            http11Radio.setSelected(true);
        }
        connectTimeoutField.setText(String.valueOf(agentConfig.getConnectTimeoutSeconds()));
        readTimeoutField.setText(String.valueOf(agentConfig.getReadTimeoutSeconds()));
        writeTimeoutField.setText(String.valueOf(agentConfig.getWriteTimeoutSeconds()));
        orchestratorMaxItersField.setText(String.valueOf(agentConfig.getOrchestratorMaxIters()));
        webAgentMaxItersField.setText(String.valueOf(agentConfig.getWebAgentMaxIters()));
        emailAgentMaxItersField.setText(String.valueOf(agentConfig.getEmailAgentMaxIters()));
        maxRepeatedCallsField.setText(String.valueOf(agentConfig.getMaxRepeatedToolCalls()));
        loopThresholdField.setText(String.valueOf(agentConfig.getLoopSimilarityThreshold()));
        evaluatorThresholdField.setText(String.valueOf(agentConfig.getEvaluatorPassThreshold()));
        evaluatorMaxRetriesField.setText(String.valueOf(agentConfig.getEvaluatorMaxRetries()));
    }

    /**
     * 将模型表单内容保存到配置
     */
    private void saveModelSettings() {
        clearAllFieldErrors();
        boolean allValid = true;

        agentConfig.setProviderType(providerCombo.getValue());

        // 校验 API 地址不为空
        String url = baseUrlField.getText().trim();
        if (url.isEmpty()) {
            markFieldError(baseUrlField, "API 地址不能为空");
            allValid = false;
        }
        agentConfig.setBaseUrl(url);
        agentConfig.setModelName(modelNameField.getText().trim());
        agentConfig.setApiKey(apiKeyField.getText().trim());
        agentConfig.setThinkingEnabled(thinkingEnabledCheck.isSelected());
        allValid &= setIntSafe(thinkingBudgetField, agentConfig::setThinkingBudget, 4096, 1024, 65536);
        agentConfig.setHttpVersion(http2Radio.isSelected() ? "HTTP_2" : "HTTP_1_1");
        allValid &= setIntSafe(connectTimeoutField, agentConfig::setConnectTimeoutSeconds, 30, 1, 600);
        allValid &= setIntSafe(readTimeoutField, agentConfig::setReadTimeoutSeconds, 300, 1, 3600);
        allValid &= setIntSafe(writeTimeoutField, agentConfig::setWriteTimeoutSeconds, 30, 1, 600);
        allValid &= setIntSafe(orchestratorMaxItersField, agentConfig::setOrchestratorMaxIters, 10, 1, 100);
        allValid &= setIntSafe(webAgentMaxItersField, agentConfig::setWebAgentMaxIters, 8, 1, 50);
        allValid &= setIntSafe(emailAgentMaxItersField, agentConfig::setEmailAgentMaxIters, 5, 1, 50);
        allValid &= setIntSafe(maxRepeatedCallsField, agentConfig::setMaxRepeatedToolCalls, 8, 1, 50);
        agentConfig.setLoopSimilarityThreshold(
                parseDoubleSafe(loopThresholdField, 0.8, 0.0, 1.0));
        agentConfig.setEvaluatorPassThreshold(
                parseDoubleSafe(evaluatorThresholdField, 3.5, 1.0, 5.0));
        allValid &= setIntSafe(evaluatorMaxRetriesField, agentConfig::setEvaluatorMaxRetries, 2, 0, 10);
        agentConfig.save();

        if (!allValid) {
            log.warn("模型设置已保存，但部分字段值无效已使用默认值");
        } else {
            log.info("模型设置已保存");
        }
    }

    /**
     * 切换模型提供商时自动填充推荐的 API 地址和模型名称
     */
    private void applyProviderPreset(String provider) {
        if (provider == null) return;
        switch (provider) {
            case "OpenAI" -> {
                baseUrlField.setText("https://api.openai.com/v1");
                modelNameField.setText("gpt-4o");
                apiKeyField.setPromptText("OpenAI API 密钥");
            }
            case "DashScope" -> {
                baseUrlField.setText("https://dashscope.aliyuncs.com/compatible-mode/v1");
                modelNameField.setText("qwen-max");
                apiKeyField.setPromptText("DashScope API 密钥");
            }
            case "Anthropic" -> {
                baseUrlField.setText("https://api.anthropic.com");
                modelNameField.setText("claude-sonnet-4-5-20250929");
                apiKeyField.setPromptText("Anthropic API 密钥");
            }
            case "Gemini" -> {
                baseUrlField.setText("");
                modelNameField.setText("gemini-2.5-flash");
                apiKeyField.setPromptText("Gemini API 密钥");
            }
            case "Ollama" -> {
                baseUrlField.setText("http://localhost:11434");
                modelNameField.setText("qwen3:8b");
                apiKeyField.setPromptText("Ollama 无需密钥，可留空");
            }
        }
    }

    /**
     * 模型 API 连通性测试（GET {baseUrl}/models），结果经 finishTest 写入全局页脚。
     * 由全局页脚测试按钮在模型配置面板时触发。
     */
    private void runModelApiTest() {
        Thread testThread = new Thread(() -> {
            String result;
            String cssClass;
            try {
                String url = baseUrlField.getText().trim();
                if (url.isEmpty()) throw new Exception("API 地址不能为空");
                long start = System.currentTimeMillis();
                java.net.URL apiUrl = java.net.URI.create(url.endsWith("/") ? url + "models" : url + "/models").toURL();
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) apiUrl.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestMethod("GET");
                String apiKey = apiKeyField.getText().trim();
                if (!apiKey.isEmpty() && !"not-needed".equals(apiKey)) {
                    conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                }
                int code = conn.getResponseCode();
                conn.disconnect();
                long elapsed = System.currentTimeMillis() - start;
                if (code >= 200 && code < 400) {
                    result = "✓ 连接正常 · " + modelNameField.getText().trim() + " · " + elapsed + "ms";
                    cssClass = "status-success";
                } else {
                    result = "连接异常 (HTTP " + code + ")";
                    cssClass = "status-error";
                }
            } catch (Exception ex) {
                result = "连接失败: " + ex.getMessage();
                cssClass = "status-error";
            }
            String finalResult = result;
            String finalCssClass = cssClass;
            javafx.application.Platform.runLater(() -> finishTest(finalResult, finalCssClass));
        }, "api-test-thread");
        testThread.setDaemon(true);
        testThread.start();
    }

    /**
     * 知识库嵌入测试（设计稿「测试嵌入」）：POST {ragBaseUrl}/embeddings 试嵌一段短文本，
     * 报告实际向量维度与耗时；维度与配置不一致时给出明确警告（维度错配会导致向量库写入失败）。
     */
    private void runRagEmbeddingTest() {
        String url = ragBaseUrlField.getText().trim();
        String model = ragModelNameField.getText().trim();
        String apiKey = ragApiKeyField.getText().trim();
        String dimText = ragDimensionsField.getText().trim();
        Thread testThread = new Thread(() -> {
            String result;
            String cssClass;
            try {
                if (url.isEmpty()) throw new Exception("API 地址不能为空");
                if (model.isEmpty()) throw new Exception("嵌入模型名称不能为空");
                long start = System.currentTimeMillis();
                java.net.URL apiUrl = java.net.URI.create(
                        url.endsWith("/") ? url + "embeddings" : url + "/embeddings").toURL();
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) apiUrl.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(15000);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                if (!apiKey.isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                }
                conn.setDoOutput(true);
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                String body = mapper.writeValueAsString(java.util.Map.of(
                        "model", model, "input", "嵌入连通性测试"));
                try (var os = conn.getOutputStream()) {
                    os.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    conn.disconnect();
                    throw new Exception("HTTP " + code);
                }
                com.fasterxml.jackson.databind.JsonNode root;
                try (var is = conn.getInputStream()) {
                    root = mapper.readTree(is);
                }
                conn.disconnect();
                long elapsed = System.currentTimeMillis() - start;
                int actualDim = root.path("data").path(0).path("embedding").size();
                if (actualDim <= 0) throw new Exception("响应中未找到嵌入向量");
                int configuredDim;
                try {
                    configuredDim = Integer.parseInt(dimText);
                } catch (NumberFormatException nfe) {
                    configuredDim = -1;
                }
                if (configuredDim > 0 && actualDim != configuredDim) {
                    result = "嵌入可用，但实际维度 " + actualDim + " 与配置 " + configuredDim
                            + " 不一致，写入向量库会失败，请修正向量维度";
                    cssClass = "status-error";
                } else {
                    result = "✓ 嵌入正常 · 维度 " + actualDim + " · " + elapsed + "ms";
                    cssClass = "status-success";
                }
            } catch (Exception ex) {
                result = "嵌入测试失败: " + ex.getMessage();
                cssClass = "status-error";
            }
            String finalResult = result;
            String finalCssClass = cssClass;
            javafx.application.Platform.runLater(() -> finishTest(finalResult, finalCssClass));
        }, "rag-embedding-test-thread");
        testThread.setDaemon(true);
        testThread.start();
    }

    /**
     * 安全设置整数值（含范围校验），返回是否校验通过
     */
    private boolean setIntSafe(TextField field, java.util.function.IntConsumer setter, int defaultValue) {
        return setIntSafe(field, setter, defaultValue, 1, Integer.MAX_VALUE);
    }

    /**
     * 安全设置整数值（含范围校验），超出范围时使用默认值并标记错误
     */
    private boolean setIntSafe(TextField field, java.util.function.IntConsumer setter, int defaultValue, int min, int max) {
        clearFieldError(field);
        try {
            int value = Integer.parseInt(field.getText().trim());
            if (value < min || value > max) {
                markFieldError(field, "请输入 " + min + " ~ " + max + " 之间的整数");
                setter.accept(defaultValue);
                return false;
            }
            setter.accept(value);
            return true;
        } catch (NumberFormatException e) {
            markFieldError(field, "请输入有效的整数");
            setter.accept(defaultValue);
            return false;
        }
    }

    /**
     * 安全设置 double 值（含范围校验），超出范围时使用默认值并标记错误
     */
    private double parseDoubleSafe(TextField field, double defaultValue, double min, double max) {
        clearFieldError(field);
        try {
            double value = Double.parseDouble(field.getText().trim());
            if (value < min || value > max) {
                markFieldError(field, "请输入 " + min + " ~ " + max + " 之间的数值");
                return defaultValue;
            }
            return value;
        } catch (NumberFormatException e) {
            markFieldError(field, "请输入有效的数值");
            return defaultValue;
        }
    }

    // ==================== 数值字段实时范围校验（设计稿 NumField） ====================

    /**
     * 给全部数值输入框挂实时范围校验：输入时即标记 field-error + 范围提示 Tooltip，
     * 合法后立即清除。范围与各保存方法中 setIntSafe / parseDoubleSafe 的兜底校验一致；
     * 仅做即时视觉反馈，不替代保存时的兜底逻辑。
     */
    private void attachLiveValidations() {
        // 模型配置 — 基本/高级
        attachLiveIntRange(thinkingBudgetField, 1024, 65536);
        attachLiveIntRange(connectTimeoutField, 1, 600);
        attachLiveIntRange(readTimeoutField, 1, 3600);
        attachLiveIntRange(writeTimeoutField, 1, 600);
        attachLiveIntRange(orchestratorMaxItersField, 1, 100);
        attachLiveIntRange(webAgentMaxItersField, 1, 50);
        attachLiveIntRange(emailAgentMaxItersField, 1, 50);
        attachLiveIntRange(maxRepeatedCallsField, 1, 50);
        attachLiveDoubleRange(loopThresholdField, 0.0, 1.0);
        attachLiveDoubleRange(evaluatorThresholdField, 1.0, 5.0);
        attachLiveIntRange(evaluatorMaxRetriesField, 0, 10);
        // GEPA 能力
        attachLiveIntRange(gepaEvalIntervalField, 1, 20);
        attachLiveDoubleRange(gepaEvalThresholdField, 1.0, 5.0);
        attachLiveIntRange(gepaFeedbackMaxRoundsField, 0, 10);
        // 技能进化
        attachLiveIntRange(skillEvolutionMinToolsField, 1, 50);
        attachLiveDoubleRange(skillEvolutionSuccessThresholdField, 0.0, 1.0);
        // 知识库
        attachLiveIntRange(ragDimensionsField, 1, Integer.MAX_VALUE);
        attachLiveIntRange(ragChunkSizeField, 1, Integer.MAX_VALUE);
        attachLiveIntRange(ragChunkOverlapField, 1, Integer.MAX_VALUE);
        attachLiveIntRange(ragRetrieveLimitField, 1, Integer.MAX_VALUE);
        attachLiveDoubleRange(ragScoreThresholdField, 0.0, 1.0);
        // 邮件端口
        attachLiveIntRange(smtpPortField, 1, 65535);
        attachLiveIntRange(imapPortField, 1, 65535);
    }

    /** 整数字段实时校验；max 为 Integer.MAX_VALUE 时提示语退化为「≥ min 的整数」。 */
    private void attachLiveIntRange(TextField field, int min, int max) {
        String message = max == Integer.MAX_VALUE
                ? "请输入 ≥ " + min + " 的整数"
                : "请输入 " + min + " ~ " + max + " 之间的整数";
        attachLiveRange(field, min, max, false, message);
    }

    /** 小数字段实时校验。 */
    private void attachLiveDoubleRange(TextField field, double min, double max) {
        attachLiveRange(field, min, max, true, "请输入 " + min + " ~ " + max + " 之间的数值");
    }

    /**
     * 实时校验的统一实现：监听文本与禁用状态变化；字段禁用时豁免并清除错误
     * （设计稿 NumField 的 {@code bad = !disabled && …} 语义）。
     */
    private void attachLiveRange(TextField field, double min, double max, boolean isFloat, String message) {
        if (field == null) return;
        Runnable validate = () -> {
            if (field.isDisabled()) {
                clearFieldError(field);
                return;
            }
            String text = field.getText() == null ? "" : field.getText().trim();
            boolean ok;
            try {
                double v = isFloat ? Double.parseDouble(text) : Integer.parseInt(text);
                ok = v >= min && v <= max;
            } catch (NumberFormatException e) {
                ok = false;
            }
            if (ok) {
                clearFieldError(field);
            } else {
                markFieldError(field, message);
            }
        };
        field.textProperty().addListener((obs, oldV, newV) -> validate.run());
        field.disabledProperty().addListener((obs, oldV, newV) -> validate.run());
    }

    // ==================== 字段错误标记辅助方法 ====================

    /**
     * 标记输入框为错误状态，并设置提示信息
     */
    private boolean markFieldError(TextField field, String message) {
        if (!field.getStyleClass().contains("field-error")) {
            field.getStyleClass().add("field-error");
        }
        field.setTooltip(new Tooltip(message));
        return false;
    }

    /**
     * 清除输入框的错误状态
     */
    private void clearFieldError(TextField field) {
        field.getStyleClass().remove("field-error");
        field.setTooltip(null);
    }

    /**
     * 清除所有模型配置输入框的错误状态
     */
    private void clearAllFieldErrors() {
        clearFieldError(baseUrlField);
        clearFieldError(modelNameField);
        clearFieldError(connectTimeoutField);
        clearFieldError(readTimeoutField);
        clearFieldError(writeTimeoutField);
        clearFieldError(orchestratorMaxItersField);
        clearFieldError(webAgentMaxItersField);
        clearFieldError(emailAgentMaxItersField);
        clearFieldError(maxRepeatedCallsField);
        clearFieldError(loopThresholdField);
        clearFieldError(thinkingBudgetField);
        clearFieldError(evaluatorThresholdField);
        clearFieldError(evaluatorMaxRetriesField);
        clearFieldError(gepaEvalIntervalField);
        clearFieldError(gepaEvalThresholdField);
        clearFieldError(gepaFeedbackMaxRoundsField);
    }

    // ==================== GEPA 能力面板 ====================

    private Node buildGepaPanel() {
        Label sectionTitle = new Label("GEPA 自改进能力");
        sectionTitle.getStyleClass().add("settings-section-title");

        Label overviewHint = new Label(
                "GEPA（Goal-Evaluate-Plan-Act）让智能体在执行过程中自动分解目标、评估进度、调整计划，实现闭环自我改进");
        overviewHint.getStyleClass().add("settings-hint");
        overviewHint.setWrapText(true);

        // ===== 目标分解 =====
        Label goalTitle = new Label("目标分解");
        goalTitle.getStyleClass().add("settings-group-title");

        gepaGoalEnabledCheck = new ToggleSwitch();
        HBox goalRow = toggleRow(gepaGoalEnabledCheck, "目标分解",
                "把复杂请求拆为可核验的子目标，并将成功标准注入编排器提示词");

        // ===== 过程评估 =====
        Label evalTitle = new Label("过程评估");
        evalTitle.getStyleClass().add("settings-group-title");

        gepaEvalIntervalField = createTextField("评估间隔（工具调用次数）");
        gepaEvalIntervalField.setPrefWidth(80);
        gepaEvalThresholdField = createTextField("通过阈值（1.0 ~ 5.0）");
        gepaEvalThresholdField.setPrefWidth(80);

        GridPane evalGrid = new GridPane();
        evalGrid.setHgap(10);
        evalGrid.setVgap(8);
        evalGrid.add(createLabel("评估间隔："), 0, 0);
        evalGrid.add(gepaEvalIntervalField, 1, 0);
        evalGrid.add(createLabel("通过阈值："), 0, 1);
        evalGrid.add(gepaEvalThresholdField, 1, 1);

        Label evalHint = new Label("每隔 N 次工具调用触发一次评估；评分低于阈值时触发计划调整");
        evalHint.getStyleClass().add("settings-hint");
        evalHint.setWrapText(true);

        // ===== 自适应规划 =====
        Label planTitle = new Label("自适应规划");
        planTitle.getStyleClass().add("settings-group-title");

        gepaPlanAdaptiveCheck = new ToggleSwitch();
        HBox adaptiveRow = toggleRow(gepaPlanAdaptiveCheck, "自适应规划",
                "根据过程评估动态调整后续步骤，而非一次性定死计划");

        gepaFeedbackMaxRoundsField = createTextField("最大调整轮次（0 ~ 10）");
        gepaFeedbackMaxRoundsField.setPrefWidth(80);

        HBox feedbackRow = new HBox(10, createLabel("最大轮次："), gepaFeedbackMaxRoundsField);
        feedbackRow.setAlignment(Pos.CENTER_LEFT);

        Label planHint = new Label("评估不通过时，智能体将基于当前轨迹重写剩余步骤；超过最大调整轮次后不再调整");
        planHint.getStyleClass().add("settings-hint");
        planHint.setWrapText(true);

        gepaPlanAdaptiveCheck.selectedProperty().addListener((obs, o, n) -> {
            gepaFeedbackMaxRoundsField.setDisable(!n);
        });

        // 提示（保存上移全局页脚）
        Label restartHint = new Label("修改保存后立即生效，当前对话的智能体服务将重建");
        restartHint.getStyleClass().add("settings-hint");

        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.getStyleClass().add("settings-scroll-pane");

        VBox panel = new VBox(12,
                sectionTitle, overviewHint,
                new Separator(),
                goalTitle, goalRow,
                new Separator(),
                evalTitle, evalGrid, evalHint,
                new Separator(),
                planTitle, adaptiveRow, feedbackRow, planHint,
                new Separator(),
                restartHint);
        panel.setPadding(new Insets(4));

        scrollPane.setContent(panel);
        return scrollPane;
    }

    private void loadGepaSettings() {
        gepaGoalEnabledCheck.setSelected(agentConfig.isGepaGoalEnabled());
        gepaEvalIntervalField.setText(String.valueOf(agentConfig.getGepaEvalInterval()));
        gepaEvalThresholdField.setText(String.valueOf(agentConfig.getGepaEvalThreshold()));
        gepaPlanAdaptiveCheck.setSelected(agentConfig.isGepaPlanAdaptive());
        gepaFeedbackMaxRoundsField.setText(String.valueOf(agentConfig.getGepaFeedbackMaxRounds()));
        gepaFeedbackMaxRoundsField.setDisable(!agentConfig.isGepaPlanAdaptive());
    }

    private void saveGepaSettings() {
        agentConfig.setGepaGoalEnabled(gepaGoalEnabledCheck.isSelected());
        setIntSafe(gepaEvalIntervalField, agentConfig::setGepaEvalInterval, 3, 1, 20);
        agentConfig.setGepaEvalThreshold(parseDoubleSafe(gepaEvalThresholdField, 3.5, 1.0, 5.0));
        agentConfig.setGepaPlanAdaptive(gepaPlanAdaptiveCheck.isSelected());
        setIntSafe(gepaFeedbackMaxRoundsField, agentConfig::setGepaFeedbackMaxRounds, 2, 0, 10);
        agentConfig.save();
        log.info("GEPA 设置已保存");
    }

    // ==================== 技能进化面板 ====================

    private Node buildSkillEvolutionPanel() {
        Label sectionTitle = new Label("技能进化（自学习）");
        sectionTitle.getStyleClass().add("settings-section-title");

        Label overviewHint = new Label(
                "借鉴 hermes-agent：智能体在复杂任务成功、踩坑找到出路、被纠正做法后，"
                        + "可把经验沉淀为技能（程序性记忆），并在使用中持续修补改进");
        overviewHint.getStyleClass().add("settings-hint");
        overviewHint.setWrapText(true);

        // ===== 进化模式（设计稿 Seg 分段控件 + 随选中模式联动的说明文案） =====
        Label modeTitle = new Label("进化模式");
        modeTitle.getStyleClass().add("settings-group-title");

        // 状态载体（load/save 仍读写它），展示换为分段控件
        skillEvolutionModeCombo = new ComboBox<>();
        skillEvolutionModeCombo.getItems().addAll("off", "suggest", "auto");

        Label modeHint = new Label();
        modeHint.getStyleClass().add("settings-hint");
        modeHint.setWrapText(true);

        ToggleGroup modeSegGroup = new ToggleGroup();
        HBox modeSeg = new HBox(2);
        modeSeg.getStyleClass().add("seg-container");
        modeSeg.setAlignment(Pos.CENTER_LEFT);
        String[][] modeOptions = {{"off", "关闭"}, {"suggest", "提案（推荐）"}, {"auto", "自动落盘"}};
        for (String[] opt : modeOptions) {
            ToggleButton tb = new ToggleButton(opt[1]);
            tb.getStyleClass().add("seg-btn");
            tb.setToggleGroup(modeSegGroup);
            tb.setUserData(opt[0]);
            tb.setOnAction(e -> {
                if (!tb.isSelected()) {
                    tb.setSelected(true);  // 不允许取消选中
                    return;
                }
                skillEvolutionModeCombo.setValue(opt[0]);
            });
            modeSeg.getChildren().add(tb);
        }
        // 模式 → 分段选中态 + 动态说明文案
        skillEvolutionModeCombo.valueProperty().addListener((obs, o, n) -> {
            for (Node node : modeSeg.getChildren()) {
                ToggleButton tb = (ToggleButton) node;
                tb.setSelected(tb.getUserData().equals(n));
            }
            modeHint.setText(switch (n == null ? "suggest" : n) {
                case "off" -> "skill_manage 工具拒绝写入，SkillCurator 不蒸馏。";
                case "auto" -> "直接落盘并 Toast；但你手动改过的技能仍强制降级为提案，绝不静默覆盖。";
                default -> "变更先入「技能中心 → 待审提案」队列，人工采纳后才落盘。user-modified 技能受保护。";
            });
        });

        HBox modeRow = new HBox(10, modeSeg);
        modeRow.setAlignment(Pos.CENTER_LEFT);

        // ===== 蒸馏触发门槛 =====
        Label thresholdTitle = new Label("自动蒸馏门槛");
        thresholdTitle.getStyleClass().add("settings-group-title");

        skillEvolutionMinToolsField = createTextField("最小工具调用数（1 ~ 50）");
        skillEvolutionMinToolsField.setPrefWidth(80);
        skillEvolutionSuccessThresholdField = createTextField("成功率门槛（0.0 ~ 1.0）");
        skillEvolutionSuccessThresholdField.setPrefWidth(80);

        GridPane thresholdGrid = new GridPane();
        thresholdGrid.setHgap(10);
        thresholdGrid.setVgap(8);
        thresholdGrid.add(createLabel("最小工具调用："), 0, 0);
        thresholdGrid.add(skillEvolutionMinToolsField, 1, 0);
        thresholdGrid.add(createLabel("成功率门槛："), 0, 1);
        thresholdGrid.add(skillEvolutionSuccessThresholdField, 1, 1);

        Label thresholdHint = new Label(
                "对话轮工具调用达到下限且成功率达标时，轮后自动蒸馏经验；踩坑后恢复成功的轮次不受下限约束");
        thresholdHint.getStyleClass().add("settings-hint");
        thresholdHint.setWrapText(true);

        // ===== 开关项 =====
        Label switchTitle = new Label("辅助能力");
        switchTitle.getStyleClass().add("settings-group-title");

        skillNudgeCheck = new ToggleSwitch();
        HBox nudgeRow = toggleRow(skillNudgeCheck, "经验沉淀提示（nudge）",
                "在系统提示词中提示智能体主动沉淀经验");

        skillBundlesCheck = new ToggleSwitch();
        HBox bundlesRow = toggleRow(skillBundlesCheck, "技能包（bundles）",
                "一组技能成组加载，包优先、缺失跳过");

        // 提示（保存上移全局页脚）
        Label restartHint = new Label("修改保存后下一轮对话生效");
        restartHint.getStyleClass().add("settings-hint");

        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.getStyleClass().add("settings-scroll-pane");

        VBox panel = new VBox(12,
                sectionTitle, overviewHint,
                new Separator(),
                modeTitle, modeRow, modeHint,
                new Separator(),
                thresholdTitle, thresholdGrid, thresholdHint,
                new Separator(),
                switchTitle, nudgeRow, bundlesRow,
                new Separator(),
                restartHint);
        panel.setPadding(new Insets(4));

        scrollPane.setContent(panel);
        return scrollPane;
    }

    private void loadSkillEvolutionSettings() {
        skillEvolutionModeCombo.setValue(agentConfig.getSkillEvolutionMode());
        skillEvolutionMinToolsField.setText(String.valueOf(agentConfig.getSkillEvolutionMinTools()));
        skillEvolutionSuccessThresholdField.setText(String.valueOf(agentConfig.getSkillEvolutionSuccessThreshold()));
        skillNudgeCheck.setSelected(agentConfig.isSkillNudgeEnabled());
        skillBundlesCheck.setSelected(agentConfig.isSkillBundlesEnabled());
    }

    private void saveSkillEvolutionSettings() {
        String mode = skillEvolutionModeCombo.getValue();
        agentConfig.setSkillEvolutionMode(mode == null ? "suggest" : mode);
        setIntSafe(skillEvolutionMinToolsField, agentConfig::setSkillEvolutionMinTools, 5, 1, 50);
        agentConfig.setSkillEvolutionSuccessThreshold(
                parseDoubleSafe(skillEvolutionSuccessThresholdField, 0.6, 0.0, 1.0));
        agentConfig.setSkillNudgeEnabled(skillNudgeCheck.isSelected());
        agentConfig.setSkillBundlesEnabled(skillBundlesCheck.isSelected());
        agentConfig.save();
        log.info("技能进化设置已保存");
    }

    // ==================== 通用配置面板 ====================

    private Node buildGeneralPanel() {
        Label sectionTitle = new Label("通用设置");
        sectionTitle.getStyleClass().add("settings-section-title");

        Label overviewHint = new Label("界面与后台运行行为");
        overviewHint.getStyleClass().add("settings-hint");
        overviewHint.setWrapText(true);

        // 外观快捷入口（与「界面风格」面板/顶栏菜单三处联动，设计稿 GeneralPanel）
        Label appearanceTitle = new Label("外观");
        appearanceTitle.getStyleClass().add("settings-group-title");

        ComboBox<com.javaclaw.ui.javafx.theme.ThemeManager.Theme> themeCombo = new ComboBox<>();
        themeCombo.getItems().addAll(com.javaclaw.ui.javafx.theme.ThemeManager.THEMES);
        themeCombo.getStyleClass().add("settings-combo");
        themeCombo.setPrefWidth(260);
        javafx.util.StringConverter<com.javaclaw.ui.javafx.theme.ThemeManager.Theme> themeConverter =
                new javafx.util.StringConverter<>() {
                    @Override public String toString(com.javaclaw.ui.javafx.theme.ThemeManager.Theme t) {
                        return t == null ? "" : t.name() + " — " + t.subtitle();
                    }
                    @Override public com.javaclaw.ui.javafx.theme.ThemeManager.Theme fromString(String s) {
                        return null;
                    }
                };
        themeCombo.setConverter(themeConverter);
        // 选择立即全局生效，无需保存 → 不计入 dirty
        themeCombo.getProperties().put("jc-dirty-exempt", Boolean.TRUE);
        themeCombo.setValue(com.javaclaw.ui.javafx.theme.ThemeManager.getCurrentTheme());
        themeCombo.setOnAction(e -> {
            var selected = themeCombo.getValue();
            if (selected != null) {
                com.javaclaw.ui.javafx.theme.ThemeManager.setTheme(selected.id());
            }
        });
        com.javaclaw.ui.javafx.theme.ThemeManager.themeProperty().addListener((obs, o, n) ->
                themeCombo.setValue(com.javaclaw.ui.javafx.theme.ThemeManager.getCurrentTheme()));

        Label themeHint = new Label("也可在顶部栏「风格」菜单或「界面风格」分区切换，选择立即生效。");
        themeHint.getStyleClass().add("settings-hint");
        themeHint.setWrapText(true);

        HBox themeRow = new HBox(10, createLabel("界面风格："), themeCombo);
        themeRow.setAlignment(Pos.CENTER_LEFT);

        Label trayTitle = new Label("后台常驻");
        trayTitle.getStyleClass().add("settings-group-title");

        trayMinimizeOnCloseCheck = new ToggleSwitch();
        HBox trayRow = toggleRow(trayMinimizeOnCloseCheck, "关闭窗口时最小化到系统托盘",
                "保持后台任务与定时任务运行，仅从托盘菜单「退出」才真正关闭；关闭则点窗口关闭按钮即退出");

        Label taskRiskTitle = new Label("托管任务 · 风险评估");
        taskRiskTitle.getStyleClass().add("settings-group-title");

        taskRiskAutoApproveCheck = new ToggleSwitch();
        HBox taskRiskRow = toggleRow(taskRiskAutoApproveCheck, "目录内高风险操作自动放行",
                "托管任务中由风险评估智能体判定影响范围，只触及任务工作目录内则免人工确认；"
                        + "可能越界或无法确定时仍照常弹确认。仅作用于托管任务");

        VBox panel = new VBox(12,
                sectionTitle, overviewHint,
                new Separator(),
                appearanceTitle, themeRow, themeHint,
                new Separator(),
                trayTitle, trayRow,
                new Separator(),
                taskRiskTitle, taskRiskRow);
        panel.setPadding(new Insets(4));

        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.getStyleClass().add("settings-scroll-pane");
        scrollPane.setContent(panel);
        return scrollPane;
    }

    private void loadGeneralSettings() {
        trayMinimizeOnCloseCheck.setSelected(agentConfig.isTrayMinimizeOnClose());
        taskRiskAutoApproveCheck.setSelected(agentConfig.isTaskRiskAutoApproveEnabled());
    }

    // ==================== 界面风格面板（设计稿 AppearancePanel） ====================

    /**
     * 界面风格选择面板：双列主题卡片（顶部三联色带 + 名称/副标题 + 当前 ✓），
     * 点击立即经 ThemeManager 全局生效并记忆到本工作区，无需保存按钮。
     */
    private Node buildAppearancePanel() {
        Label sectionTitle = new Label("界面风格");
        sectionTitle.getStyleClass().add("sec-title");

        Label overviewHint = new Label(
                "实时切换整个界面的配色风格，立即生效并记忆到本工作区。「翡翠」为随应用发布的默认风格。");
        overviewHint.getStyleClass().add("sec-hint");
        overviewHint.setWrapText(true);

        Label groupTitle = new Label("风格");
        groupTitle.getStyleClass().add("grp-title");

        // 双列卡片网格
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        ColumnConstraints half = new ColumnConstraints();
        half.setPercentWidth(50);
        grid.getColumnConstraints().addAll(half, half);

        // 主题变更时统一刷新所有卡片的选中态
        java.util.Map<String, Runnable> refreshers = new java.util.LinkedHashMap<>();
        int index = 0;
        for (var theme : com.javaclaw.ui.javafx.theme.ThemeManager.THEMES) {
            // 顶部三联色带（品牌 / 页面 / 卡片）
            HBox strip = new HBox();
            strip.setMinHeight(52);
            strip.setPrefHeight(52);
            for (String color : new String[]{theme.brand(), theme.bg(), theme.surface()}) {
                Region cell = new Region();
                cell.setStyle("-fx-background-color: " + color + ";");
                HBox.setHgrow(cell, Priority.ALWAYS);
                strip.getChildren().add(cell);
            }

            Label name = new Label(theme.name());
            name.getStyleClass().add("theme-card-name");
            Label sub = new Label(theme.subtitle());
            sub.getStyleClass().add("theme-card-sub");
            VBox text = new VBox(1, name, sub);
            HBox.setHgrow(text, Priority.ALWAYS);

            Label check = new Label("✓");
            check.getStyleClass().add("theme-card-check");

            HBox meta = new HBox(8, text, check);
            meta.setAlignment(Pos.CENTER_LEFT);
            meta.setPadding(new Insets(9, 12, 9, 12));

            VBox card = new VBox(strip, meta);
            card.getStyleClass().add("theme-card");
            card.setOnMouseClicked(e ->
                    com.javaclaw.ui.javafx.theme.ThemeManager.setTheme(theme.id()));

            Runnable refresh = () -> {
                boolean selected = theme.id()
                        .equals(com.javaclaw.ui.javafx.theme.ThemeManager.getTheme());
                card.getStyleClass().remove("theme-card-selected");
                if (selected) {
                    card.getStyleClass().add("theme-card-selected");
                }
                check.setVisible(selected);
            };
            refresh.run();
            refreshers.put(theme.id(), refresh);

            grid.add(card, index % 2, index / 2);
            index++;
        }
        com.javaclaw.ui.javafx.theme.ThemeManager.themeProperty()
                .addListener((obs, o, n) -> refreshers.values().forEach(Runnable::run));

        Label noteTitle = new Label("说明");
        noteTitle.getStyleClass().add("grp-title");
        Label note = new Label(
                "风格通过语义令牌切换：仅 -jc-* 颜色令牌重新指向，所有组件无需改动。"
                        + "也可在聊天顶部栏「风格」菜单随时切换。");
        note.getStyleClass().add("sec-hint");
        note.setWrapText(true);

        VBox panel = new VBox(8,
                sectionTitle, overviewHint,
                groupTitle, grid,
                new Separator(),
                noteTitle, note);
        panel.setPadding(new Insets(4));

        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.getStyleClass().add("settings-scroll-pane");
        scrollPane.setContent(panel);
        return scrollPane;
    }

    private void saveGeneralSettings() {
        agentConfig.setTrayMinimizeOnClose(trayMinimizeOnCloseCheck.isSelected());
        agentConfig.setTaskRiskAutoApproveEnabled(taskRiskAutoApproveCheck.isSelected());
        agentConfig.save();
        log.info("通用设置已保存");
    }

    // ==================== 邮件配置面板 ====================

    private Node buildEmailPanel() {
        Label sectionTitle = new Label("邮件配置");
        sectionTitle.getStyleClass().add("settings-section-title");

        // 快捷选择邮箱服务商
        Label presetLabel = new Label("邮箱服务商：");
        presetLabel.getStyleClass().add("settings-label");
        ComboBox<String> presetCombo = new ComboBox<>();
        presetCombo.getItems().addAll("QQ 邮箱", "163 邮箱", "Gmail", "Outlook", "自定义");
        presetCombo.getStyleClass().add("settings-combo");
        presetCombo.setOnAction(e -> applyPreset(presetCombo.getValue()));

        HBox presetRow = new HBox(10, presetLabel, presetCombo);
        presetRow.setAlignment(Pos.CENTER_LEFT);

        // SMTP 配置
        Label smtpTitle = new Label("发送服务器 (SMTP)");
        smtpTitle.getStyleClass().add("settings-group-title");

        smtpHostField = createTextField("SMTP 服务器地址");
        smtpPortField = createTextField("端口号");
        smtpPortField.setPrefWidth(80);

        HBox smtpRow = new HBox(10,
                new Label("地址："), smtpHostField,
                new Label("端口："), smtpPortField);
        smtpRow.setAlignment(Pos.CENTER_LEFT);
        applyLabelStyle(smtpRow);

        // IMAP 配置
        Label imapTitle = new Label("接收服务器 (IMAP)");
        imapTitle.getStyleClass().add("settings-group-title");

        imapHostField = createTextField("IMAP 服务器地址");
        imapPortField = createTextField("端口号");
        imapPortField.setPrefWidth(80);

        HBox imapRow = new HBox(10,
                new Label("地址："), imapHostField,
                new Label("端口："), imapPortField);
        imapRow.setAlignment(Pos.CENTER_LEFT);
        applyLabelStyle(imapRow);

        // 加密方式（设计稿 SelectField：SSL / STARTTLS / 无）
        encryptionCombo = new ComboBox<>();
        encryptionCombo.getItems().addAll("SSL", "STARTTLS", "无");
        encryptionCombo.getStyleClass().add("settings-combo");

        Label encryptHint = new Label("SSL 对应端口 465/993，STARTTLS 对应端口 587");
        encryptHint.getStyleClass().add("settings-hint");

        HBox encryptRow = new HBox(10, createLabel("加密方式："), encryptionCombo, encryptHint);
        encryptRow.setAlignment(Pos.CENTER_LEFT);

        // 账号配置
        Label accountTitle = new Label("账号信息");
        accountTitle.getStyleClass().add("settings-group-title");

        usernameField = createTextField("邮箱地址");
        usernameField.setPrefWidth(300);

        passwordField = new PasswordField();
        passwordField.setPromptText("授权码（非登录密码）");
        passwordField.getStyleClass().add("settings-field");
        passwordField.setPrefWidth(300);

        fromAddressField = createTextField("发件人地址（留空则使用登录邮箱）");
        fromAddressField.setPrefWidth(300);

        GridPane accountGrid = new GridPane();
        accountGrid.setHgap(10);
        accountGrid.setVgap(8);
        accountGrid.add(createLabel("登录邮箱："), 0, 0);
        accountGrid.add(usernameField, 1, 0);
        accountGrid.add(createLabel("授权码："), 0, 1);
        accountGrid.add(secretField(passwordField), 1, 1);
        accountGrid.add(createLabel("发件人："), 0, 2);
        accountGrid.add(fromAddressField, 1, 2);

        // 配置文件路径提示（保存/测试连接上移全局页脚）
        Label pathHint = new Label("配置文件: " + emailConfig.getConfigFilePath());
        pathHint.getStyleClass().add("settings-hint");

        // 自动检测当前服务商
        detectPreset(presetCombo);

        // 组装面板
        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.getStyleClass().add("settings-scroll-pane");

        VBox panel = new VBox(12,
                sectionTitle,
                presetRow,
                new Separator(),
                smtpTitle, smtpRow,
                imapTitle, imapRow,
                encryptRow,
                new Separator(),
                accountTitle, accountGrid,
                new Separator(),
                pathHint);
        panel.setPadding(new Insets(4));

        scrollPane.setContent(panel);
        return scrollPane;
    }

    // ==================== 通知配置面板 ====================

    private Node buildNotificationPanel() {
        Label sectionTitle = new Label("通知渠道配置");
        sectionTitle.getStyleClass().add("settings-section-title");

        Label hint = new Label("配置各通知渠道的 Webhook 地址，启用后通知专家可通过这些渠道发送消息");
        hint.getStyleClass().add("settings-hint");
        hint.setWrapText(true);

        // ===== 钉钉机器人 =====
        Label dingtalkTitle = new Label("钉钉机器人");
        dingtalkTitle.getStyleClass().add("settings-group-title");

        dingtalkEnabledCheck = new ToggleSwitch();
        HBox dingtalkRow = toggleRow(dingtalkEnabledCheck, "钉钉机器人", "群机器人 Webhook + 加签");

        dingtalkWebhookField = createTextField("Webhook 地址（群机器人 Webhook URL）");
        dingtalkWebhookField.setPrefWidth(380);
        dingtalkSecretField = new PasswordField();
        dingtalkSecretField.setPromptText("加签密钥（可选）");
        dingtalkSecretField.getStyleClass().add("settings-field");
        dingtalkSecretField.setPrefWidth(380);

        dingtalkEnabledCheck.selectedProperty().addListener((obs, o, n) -> {
            dingtalkWebhookField.setDisable(!n);
            dingtalkSecretField.setDisable(!n);
        });

        GridPane dingtalkGrid = new GridPane();
        dingtalkGrid.setHgap(10);
        dingtalkGrid.setVgap(8);
        dingtalkGrid.add(createLabel("Webhook："), 0, 0);
        dingtalkGrid.add(dingtalkWebhookField, 1, 0);
        dingtalkGrid.add(createLabel("加签密钥："), 0, 1);
        dingtalkGrid.add(secretField(dingtalkSecretField), 1, 1);

        // ===== 企业微信机器人 =====
        Label wechatTitle = new Label("企业微信机器人");
        wechatTitle.getStyleClass().add("settings-group-title");

        wechatEnabledCheck = new ToggleSwitch();
        HBox wechatToggleRow = toggleRow(wechatEnabledCheck, "企业微信", "群机器人 Webhook");

        wechatWebhookField = createTextField("Webhook 地址（群机器人 Webhook URL）");
        wechatWebhookField.setPrefWidth(380);

        wechatEnabledCheck.selectedProperty().addListener((obs, o, n) ->
                wechatWebhookField.setDisable(!n));

        HBox wechatRow = new HBox(10, createLabel("Webhook："), wechatWebhookField);
        wechatRow.setAlignment(Pos.CENTER_LEFT);

        // ===== 飞书机器人 =====
        Label feishuTitle = new Label("飞书机器人");
        feishuTitle.getStyleClass().add("settings-group-title");

        feishuEnabledCheck = new ToggleSwitch();
        HBox feishuToggleRow = toggleRow(feishuEnabledCheck, "飞书", "自定义机器人 Webhook + 签名");

        feishuWebhookField = createTextField("Webhook 地址（群机器人 Webhook URL）");
        feishuWebhookField.setPrefWidth(380);
        feishuSecretField = new PasswordField();
        feishuSecretField.setPromptText("签名校验密钥（可选）");
        feishuSecretField.getStyleClass().add("settings-field");
        feishuSecretField.setPrefWidth(380);

        feishuEnabledCheck.selectedProperty().addListener((obs, o, n) -> {
            feishuWebhookField.setDisable(!n);
            feishuSecretField.setDisable(!n);
        });

        GridPane feishuGrid = new GridPane();
        feishuGrid.setHgap(10);
        feishuGrid.setVgap(8);
        feishuGrid.add(createLabel("Webhook："), 0, 0);
        feishuGrid.add(feishuWebhookField, 1, 0);
        feishuGrid.add(createLabel("签名密钥："), 0, 1);
        feishuGrid.add(secretField(feishuSecretField), 1, 1);

        // ===== 邮件通知 =====
        Label emailNotifyTitle = new Label("邮件通知");
        emailNotifyTitle.getStyleClass().add("settings-group-title");

        emailNotifyEnabledCheck = new ToggleSwitch();
        HBox emailNotifyToggleRow = toggleRow(emailNotifyEnabledCheck, "邮件",
                "复用「邮件配置」中的 SMTP 账号发送");

        emailNotifyToField = createTextField("默认通知收件人邮箱地址");
        emailNotifyToField.setPrefWidth(380);

        emailNotifyEnabledCheck.selectedProperty().addListener((obs, o, n) ->
                emailNotifyToField.setDisable(!n));

        HBox emailNotifyRow = new HBox(10, createLabel("收件人："), emailNotifyToField);
        emailNotifyRow.setAlignment(Pos.CENTER_LEFT);

        // ===== 自定义 Webhook =====
        Label customTitle = new Label("自定义 Webhook");
        customTitle.getStyleClass().add("settings-group-title");

        customEnabledCheck = new ToggleSwitch();
        HBox customToggleRow = toggleRow(customEnabledCheck, "自定义 Webhook",
                "POST JSON 到任意端点");

        customWebhookField = createTextField("Webhook URL");
        customWebhookField.setPrefWidth(380);
        customBodyTemplateField = createTextField("请求体模板，用 ${message} 表示消息内容");
        customBodyTemplateField.setPrefWidth(380);

        customEnabledCheck.selectedProperty().addListener((obs, o, n) -> {
            customWebhookField.setDisable(!n);
            customBodyTemplateField.setDisable(!n);
        });

        GridPane customGrid = new GridPane();
        customGrid.setHgap(10);
        customGrid.setVgap(8);
        customGrid.add(createLabel("URL："), 0, 0);
        customGrid.add(customWebhookField, 1, 0);
        customGrid.add(createLabel("请求体："), 0, 1);
        customGrid.add(customBodyTemplateField, 1, 1);

        Label customHint = new Label("请求体模板示例: {\"content\": \"${message}\"}");
        customHint.getStyleClass().add("settings-hint");

        // 配置文件路径（保存上移全局页脚）
        Label pathHint = new Label("配置文件: " + notificationConfig.getConfigFilePath());
        pathHint.getStyleClass().add("settings-hint");

        // 组装面板
        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.getStyleClass().add("settings-scroll-pane");

        VBox panel = new VBox(12,
                sectionTitle, hint,
                new Separator(),
                dingtalkTitle, dingtalkRow, dingtalkGrid,
                new Separator(),
                wechatTitle, wechatToggleRow, wechatRow,
                new Separator(),
                feishuTitle, feishuToggleRow, feishuGrid,
                new Separator(),
                emailNotifyTitle, emailNotifyToggleRow, emailNotifyRow,
                new Separator(),
                customTitle, customToggleRow, customGrid, customHint,
                new Separator(),
                pathHint);
        panel.setPadding(new Insets(4));

        scrollPane.setContent(panel);
        return scrollPane;
    }

    /**
     * 将通知配置加载到表单
     */
    private void loadNotificationSettings() {
        dingtalkEnabledCheck.setSelected(notificationConfig.isDingtalkEnabled());
        dingtalkWebhookField.setText(notificationConfig.getDingtalkWebhook());
        dingtalkSecretField.setText(notificationConfig.getDingtalkSecret());
        dingtalkWebhookField.setDisable(!notificationConfig.isDingtalkEnabled());
        dingtalkSecretField.setDisable(!notificationConfig.isDingtalkEnabled());

        wechatEnabledCheck.setSelected(notificationConfig.isWechatEnabled());
        wechatWebhookField.setText(notificationConfig.getWechatWebhook());
        wechatWebhookField.setDisable(!notificationConfig.isWechatEnabled());

        feishuEnabledCheck.setSelected(notificationConfig.isFeishuEnabled());
        feishuWebhookField.setText(notificationConfig.getFeishuWebhook());
        feishuSecretField.setText(notificationConfig.getFeishuSecret());
        feishuWebhookField.setDisable(!notificationConfig.isFeishuEnabled());
        feishuSecretField.setDisable(!notificationConfig.isFeishuEnabled());

        emailNotifyEnabledCheck.setSelected(notificationConfig.isEmailNotifyEnabled());
        emailNotifyToField.setText(notificationConfig.getEmailNotifyTo());
        emailNotifyToField.setDisable(!notificationConfig.isEmailNotifyEnabled());

        customEnabledCheck.setSelected(notificationConfig.isCustomEnabled());
        customWebhookField.setText(notificationConfig.getCustomWebhook());
        customBodyTemplateField.setText(notificationConfig.getCustomBodyTemplate());
        customWebhookField.setDisable(!notificationConfig.isCustomEnabled());
        customBodyTemplateField.setDisable(!notificationConfig.isCustomEnabled());
    }

    /**
     * 将通知表单内容保存到配置
     */
    private void saveNotificationSettings() {
        notificationConfig.setDingtalkEnabled(dingtalkEnabledCheck.isSelected());
        notificationConfig.setDingtalkWebhook(dingtalkWebhookField.getText().trim());
        notificationConfig.setDingtalkSecret(dingtalkSecretField.getText().trim());

        notificationConfig.setWechatEnabled(wechatEnabledCheck.isSelected());
        notificationConfig.setWechatWebhook(wechatWebhookField.getText().trim());

        notificationConfig.setFeishuEnabled(feishuEnabledCheck.isSelected());
        notificationConfig.setFeishuWebhook(feishuWebhookField.getText().trim());
        notificationConfig.setFeishuSecret(feishuSecretField.getText().trim());

        notificationConfig.setEmailNotifyEnabled(emailNotifyEnabledCheck.isSelected());
        notificationConfig.setEmailNotifyTo(emailNotifyToField.getText().trim());

        notificationConfig.setCustomEnabled(customEnabledCheck.isSelected());
        notificationConfig.setCustomWebhook(customWebhookField.getText().trim());
        notificationConfig.setCustomBodyTemplate(customBodyTemplateField.getText().trim());

        notificationConfig.save();
        log.info("通知设置已保存");
    }

    // ==================== RAG 知识库配置面板 ====================

    private Node buildRagPanel() {
        Label sectionTitle = new Label("知识库配置（RAG）");
        sectionTitle.getStyleClass().add("settings-section-title");

        // 启用开关
        ragEnabledCheck = new ToggleSwitch();
        HBox ragEnableRow = toggleRow(ragEnabledCheck, "本地知识库（RAG）",
                "启用后，知识专家将具备文档导入和语义检索能力");

        // 嵌入模型配置
        Label embeddingTitle = new Label("嵌入模型");
        embeddingTitle.getStyleClass().add("settings-group-title");

        ragProviderCombo = new ComboBox<>();
        ragProviderCombo.getItems().addAll("OpenAI", "DashScope", "Ollama");
        ragProviderCombo.getStyleClass().add("settings-combo");
        ragProviderCombo.setOnAction(e -> applyRagProviderPreset(ragProviderCombo.getValue()));

        Label providerHint = new Label("选择嵌入模型提供商（可与聊天模型不同）");
        providerHint.getStyleClass().add("settings-hint");

        HBox providerRow = new HBox(10, createLabel("提供商："), ragProviderCombo);
        providerRow.setAlignment(Pos.CENTER_LEFT);

        ragBaseUrlField = createTextField("嵌入模型 API 地址");
        ragBaseUrlField.setPrefWidth(350);
        ragApiKeyField = new PasswordField();
        ragApiKeyField.setPromptText("嵌入模型 API 密钥");
        ragApiKeyField.getStyleClass().add("settings-field");
        ragApiKeyField.setPrefWidth(350);
        ragModelNameField = createTextField("嵌入模型名称");
        ragModelNameField.setPrefWidth(350);
        ragDimensionsField = createTextField("向量维度");
        ragDimensionsField.setPrefWidth(120);

        GridPane embeddingGrid = new GridPane();
        embeddingGrid.setHgap(10);
        embeddingGrid.setVgap(8);
        embeddingGrid.add(createLabel("API 地址："), 0, 0);
        embeddingGrid.add(ragBaseUrlField, 1, 0);
        embeddingGrid.add(createLabel("API 密钥："), 0, 1);
        embeddingGrid.add(secretField(ragApiKeyField), 1, 1);
        embeddingGrid.add(createLabel("模型名称："), 0, 2);
        embeddingGrid.add(ragModelNameField, 1, 2);
        embeddingGrid.add(createLabel("向量维度："), 0, 3);
        embeddingGrid.add(ragDimensionsField, 1, 3);

        // 文档处理参数
        Label chunkTitle = new Label("文档处理");
        chunkTitle.getStyleClass().add("settings-group-title");

        ragChunkSizeField = createTextField("分块大小");
        ragChunkSizeField.setPrefWidth(80);
        ragChunkOverlapField = createTextField("重叠大小");
        ragChunkOverlapField.setPrefWidth(80);

        HBox chunkRow = new HBox(10,
                createLabel("分块大小："), ragChunkSizeField,
                createLabel("重叠大小："), ragChunkOverlapField);
        chunkRow.setAlignment(Pos.CENTER_LEFT);

        Label chunkHint = new Label("分块大小 256~1024 字符，重叠 10%~20% 保持上下文连续性");
        chunkHint.getStyleClass().add("settings-hint");

        // 检索参数
        Label retrieveTitle = new Label("检索参数");
        retrieveTitle.getStyleClass().add("settings-group-title");

        ragRetrieveLimitField = createTextField("返回数量");
        ragRetrieveLimitField.setPrefWidth(80);
        ragScoreThresholdField = createTextField("分数阈值");
        ragScoreThresholdField.setPrefWidth(80);

        HBox retrieveRow = new HBox(10,
                createLabel("返回数量："), ragRetrieveLimitField,
                createLabel("分数阈值："), ragScoreThresholdField);
        retrieveRow.setAlignment(Pos.CENTER_LEFT);

        Label retrieveHint = new Label("返回数量 3~5，分数阈值 0.3~0.5（越高越精确）");
        retrieveHint.getStyleClass().add("settings-hint");

        // 启用开关控制子控件可用性
        ragEnabledCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            ragProviderCombo.setDisable(!newVal);
            ragBaseUrlField.setDisable(!newVal);
            ragApiKeyField.setDisable(!newVal);
            ragModelNameField.setDisable(!newVal);
            ragDimensionsField.setDisable(!newVal);
            ragChunkSizeField.setDisable(!newVal);
            ragChunkOverlapField.setDisable(!newVal);
            ragRetrieveLimitField.setDisable(!newVal);
            ragScoreThresholdField.setDisable(!newVal);
        });

        // 提示信息（保存上移全局页脚）
        Label restartHint = new Label("修改保存后立即生效，当前对话的智能体服务将重建");
        restartHint.getStyleClass().add("settings-hint");

        // 组装面板
        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.getStyleClass().add("settings-scroll-pane");

        VBox panel = new VBox(12,
                sectionTitle,
                ragEnableRow,
                new Separator(),
                embeddingTitle, providerRow, providerHint, embeddingGrid,
                new Separator(),
                chunkTitle, chunkRow, chunkHint,
                new Separator(),
                retrieveTitle, retrieveRow, retrieveHint,
                new Separator(),
                restartHint);
        panel.setPadding(new Insets(4));

        scrollPane.setContent(panel);
        return scrollPane;
    }

    /**
     * 切换嵌入模型提供商时自动填充推荐配置
     */
    private void applyRagProviderPreset(String provider) {
        if (provider == null) return;
        switch (provider) {
            case "OpenAI" -> {
                ragBaseUrlField.setText("https://api.openai.com/v1");
                ragModelNameField.setText("text-embedding-3-small");
                ragDimensionsField.setText("1024");
                ragApiKeyField.setPromptText("OpenAI API 密钥");
            }
            case "DashScope" -> {
                ragBaseUrlField.setText("https://dashscope.aliyuncs.com/compatible-mode/v1");
                ragModelNameField.setText("text-embedding-v3");
                ragDimensionsField.setText("1024");
                ragApiKeyField.setPromptText("DashScope API 密钥");
            }
            case "Ollama" -> {
                ragBaseUrlField.setText("http://localhost:11434/v1");
                ragModelNameField.setText("nomic-embed-text");
                ragDimensionsField.setText("768");
                ragApiKeyField.setPromptText("Ollama 无需密钥");
            }
        }
    }

    /**
     * 将 RAG 配置加载到表单
     */
    private void loadRagSettings() {
        ragEnabledCheck.setSelected(agentConfig.isRagEnabled());
        ragProviderCombo.setValue(agentConfig.getRagEmbeddingProvider());
        ragBaseUrlField.setText(agentConfig.getRagEmbeddingBaseUrl());
        ragApiKeyField.setText(agentConfig.getRagEmbeddingApiKey());
        ragModelNameField.setText(agentConfig.getRagEmbeddingModelName());
        ragDimensionsField.setText(String.valueOf(agentConfig.getRagEmbeddingDimensions()));
        ragChunkSizeField.setText(String.valueOf(agentConfig.getRagChunkSize()));
        ragChunkOverlapField.setText(String.valueOf(agentConfig.getRagChunkOverlap()));
        ragRetrieveLimitField.setText(String.valueOf(agentConfig.getRagRetrieveLimit()));
        ragScoreThresholdField.setText(String.valueOf(agentConfig.getRagScoreThreshold()));

        // 初始化子控件可用性
        boolean enabled = agentConfig.isRagEnabled();
        ragProviderCombo.setDisable(!enabled);
        ragBaseUrlField.setDisable(!enabled);
        ragApiKeyField.setDisable(!enabled);
        ragModelNameField.setDisable(!enabled);
        ragDimensionsField.setDisable(!enabled);
        ragChunkSizeField.setDisable(!enabled);
        ragChunkOverlapField.setDisable(!enabled);
        ragRetrieveLimitField.setDisable(!enabled);
        ragScoreThresholdField.setDisable(!enabled);
    }

    /**
     * 将 RAG 表单内容保存到配置
     */
    private void saveRagSettings() {
        agentConfig.setRagEnabled(ragEnabledCheck.isSelected());
        agentConfig.setRagEmbeddingProvider(ragProviderCombo.getValue());
        agentConfig.setRagEmbeddingBaseUrl(ragBaseUrlField.getText().trim());
        agentConfig.setRagEmbeddingApiKey(ragApiKeyField.getText().trim());
        agentConfig.setRagEmbeddingModelName(ragModelNameField.getText().trim());
        setIntSafe(ragDimensionsField, agentConfig::setRagEmbeddingDimensions, 1024);
        setIntSafe(ragChunkSizeField, agentConfig::setRagChunkSize, 512);
        setIntSafe(ragChunkOverlapField, agentConfig::setRagChunkOverlap, 50);
        setIntSafe(ragRetrieveLimitField, agentConfig::setRagRetrieveLimit, 5);
        try {
            agentConfig.setRagScoreThreshold(
                    Double.parseDouble(ragScoreThresholdField.getText().trim()));
        } catch (NumberFormatException e) {
            agentConfig.setRagScoreThreshold(0.3);
        }
        agentConfig.save();
        log.info("RAG 知识库设置已保存");
    }

    /**
     * 将当前配置加载到表单控件
     */
    private void loadSettings() {
        loadModelSettings();
        loadTieredModelSettings();
        loadNotificationSettings();
        loadRagSettings();
        loadGepaSettings();
        loadSkillEvolutionSettings();
        loadGeneralSettings();
        smtpHostField.setText(emailConfig.getSmtpHost());
        smtpPortField.setText(String.valueOf(emailConfig.getSmtpPort()));
        imapHostField.setText(emailConfig.getImapHost());
        imapPortField.setText(String.valueOf(emailConfig.getImapPort()));
        usernameField.setText(emailConfig.getUsername());
        passwordField.setText(emailConfig.getPassword());
        fromAddressField.setText(emailConfig.getFromAddress());
        // 两个布尔折叠为单选加密方式：SSL 优先，其次 STARTTLS，否则无
        encryptionCombo.setValue(emailConfig.isUseSsl() ? "SSL"
                : emailConfig.isUseStarttls() ? "STARTTLS" : "无");
    }

    /**
     * 将表单内容保存到配置
     */
    private void saveSettings() {
        emailConfig.setSmtpHost(smtpHostField.getText().trim());
        setIntSafe(smtpPortField, emailConfig::setSmtpPort, 465, 1, 65535);
        emailConfig.setImapHost(imapHostField.getText().trim());
        setIntSafe(imapPortField, emailConfig::setImapPort, 993, 1, 65535);
        emailConfig.setUsername(usernameField.getText().trim());
        emailConfig.setPassword(passwordField.getText());
        // 发件人为空时自动使用用户名
        String fromAddr = fromAddressField.getText().trim();
        if (fromAddr.isEmpty()) {
            fromAddr = usernameField.getText().trim();
        }
        emailConfig.setFromAddress(fromAddr);
        String encryption = encryptionCombo.getValue();
        emailConfig.setUseSsl("SSL".equals(encryption));
        emailConfig.setUseStarttls("STARTTLS".equals(encryption));

        emailConfig.save();
        log.info("邮件设置已保存");
    }

    /**
     * 应用邮箱服务商预设
     */
    private void applyPreset(String preset) {
        if (preset == null) return;
        switch (preset) {
            case "QQ 邮箱" -> {
                smtpHostField.setText("smtp.qq.com");
                smtpPortField.setText("465");
                imapHostField.setText("imap.qq.com");
                imapPortField.setText("993");
                encryptionCombo.setValue("SSL");
            }
            case "163 邮箱" -> {
                smtpHostField.setText("smtp.163.com");
                smtpPortField.setText("465");
                imapHostField.setText("imap.163.com");
                imapPortField.setText("993");
                encryptionCombo.setValue("SSL");
            }
            case "Gmail" -> {
                smtpHostField.setText("smtp.gmail.com");
                smtpPortField.setText("587");
                imapHostField.setText("imap.gmail.com");
                imapPortField.setText("993");
                encryptionCombo.setValue("STARTTLS");
            }
            case "Outlook" -> {
                smtpHostField.setText("smtp.office365.com");
                smtpPortField.setText("587");
                imapHostField.setText("outlook.office365.com");
                imapPortField.setText("993");
                encryptionCombo.setValue("STARTTLS");
            }
            // "自定义" 不做任何修改
        }
    }

    /**
     * 根据当前 SMTP 地址检测对应的预设
     */
    private void detectPreset(ComboBox<String> combo) {
        String smtp = emailConfig.getSmtpHost();
        if (smtp.contains("qq.com")) {
            combo.setValue("QQ 邮箱");
        } else if (smtp.contains("163.com")) {
            combo.setValue("163 邮箱");
        } else if (smtp.contains("gmail.com")) {
            combo.setValue("Gmail");
        } else if (smtp.contains("office365.com")) {
            combo.setValue("Outlook");
        } else {
            combo.setValue("自定义");
        }
    }

    /**
     * 全局页脚「测试收发」在邮件配置面板时触发：先保存，再分别测试 SMTP 发送与 IMAP 接收连通性
     * （设计稿 ok 文案「✓ SMTP 已连接 · IMAP 已连接」），结果经 finishTest 写全局状态标签。
     */
    private void runEmailTest() {
        // 与原「先保存再测试」语义一致：保存即清除未保存状态
        saveSettings();
        dirtyPanels.remove(currentPanel);
        footSaveButton.setDisable(true);
        refreshNavDirtyMarks();

        Thread testThread = new Thread(() -> {
            // SMTP 发送通道
            String smtpError = null;
            try {
                java.util.Properties props = new java.util.Properties();
                props.put("mail.smtp.host", emailConfig.getSmtpHost());
                props.put("mail.smtp.port", String.valueOf(emailConfig.getSmtpPort()));
                props.put("mail.smtp.auth", "true");
                props.put("mail.smtp.ssl.enable", String.valueOf(emailConfig.isUseSsl()));
                props.put("mail.smtp.starttls.enable", String.valueOf(emailConfig.isUseStarttls()));
                props.put("mail.smtp.connectiontimeout", "5000");
                props.put("mail.smtp.timeout", "5000");
                jakarta.mail.Session session = jakarta.mail.Session.getInstance(props);
                jakarta.mail.Transport transport = session.getTransport("smtp");
                transport.connect(emailConfig.getSmtpHost(),
                        emailConfig.getUsername(), emailConfig.getPassword());
                transport.close();
            } catch (Exception e) {
                smtpError = e.getMessage();
                log.warn("SMTP 连接测试失败: {}", e.getMessage());
            }

            // IMAP 接收通道
            String imapError = null;
            try {
                java.util.Properties props = new java.util.Properties();
                props.put("mail.imap.host", emailConfig.getImapHost());
                props.put("mail.imap.port", String.valueOf(emailConfig.getImapPort()));
                props.put("mail.imap.ssl.enable", "true");
                props.put("mail.imap.connectiontimeout", "5000");
                props.put("mail.imap.timeout", "5000");
                jakarta.mail.Session session = jakarta.mail.Session.getInstance(props);
                jakarta.mail.Store store = session.getStore("imap");
                store.connect(emailConfig.getImapHost(),
                        emailConfig.getUsername(), emailConfig.getPassword());
                store.close();
            } catch (Exception e) {
                imapError = e.getMessage();
                log.warn("IMAP 连接测试失败: {}", e.getMessage());
            }

            String result;
            String cssClass;
            if (smtpError == null && imapError == null) {
                result = "✓ SMTP 已连接 · IMAP 已连接";
                cssClass = "status-success";
                log.info("邮件收发测试成功");
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append(smtpError == null ? "SMTP 正常" : "SMTP 失败: " + smtpError);
                sb.append(" · ");
                sb.append(imapError == null ? "IMAP 正常" : "IMAP 失败: " + imapError);
                result = sb.toString();
                cssClass = "status-error";
            }

            String finalResult = result;
            String finalCssClass = cssClass;
            javafx.application.Platform.runLater(() -> finishTest(finalResult, finalCssClass));
        }, "email-test-thread");
        testThread.setDaemon(true);
        testThread.start();
    }

    /**
     * 设置模型配置变更回调，保存模型配置后自动触发（用于重建智能体服务）
     */
    public void setOnModelConfigChanged(Runnable callback) {
        this.onModelConfigChanged = callback;
    }

    /**
     * 显示设置对话框
     */
    public void show() {
        runFormLoad(this::loadSettings);
        dirtyPanels.clear();
        refreshFooter();
        stage.showAndWait();
    }

    // ==================== UI 辅助方法 ====================

    /**
     * 行式开关（设计稿 RowToggle）：左侧主文案 + 副说明，右侧滑块开关。
     *
     * @param toggle 状态载体（外部持有引用以便 load/save 读写）
     * @param main   主文案
     * @param sub    副说明；null 或空则不显示
     */
    private HBox toggleRow(ToggleSwitch toggle, String main, String sub) {
        Label mainLabel = new Label(main);
        mainLabel.getStyleClass().add("rt-main");
        VBox text = new VBox(1, mainLabel);
        if (sub != null && !sub.isEmpty()) {
            Label subLabel = new Label(sub);
            subLabel.getStyleClass().add("rt-sub");
            subLabel.setWrapText(true);
            text.getChildren().add(subLabel);
        }
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(12, text, toggle);
        row.getStyleClass().add("row-toggle");
        row.setAlignment(Pos.CENTER_LEFT);

        // 整行可点（设计稿 RowToggle clickable）：点击行任意空白处即切换开关；
        // 点击落在开关本体上时由开关自身处理，避免二次切换。开关禁用时整行降为 disabled。
        row.setOnMouseClicked(e -> {
            if (toggle.isDisabled()) return;
            Node t = e.getPickResult().getIntersectedNode();
            while (t != null) {
                if (t == toggle) return;  // 命中开关本体，交给开关处理
                t = t.getParent();
            }
            toggle.setSelected(!toggle.isSelected());
        });
        Runnable applyRowState = () -> {
            row.getStyleClass().removeAll("clickable", "disabled");
            row.getStyleClass().add(toggle.isDisabled() ? "disabled" : "clickable");
        };
        applyRowState.run();
        toggle.disabledProperty().addListener((o, a, b) -> applyRowState.run());
        return row;
    }

    /**
     * 密钥输入框包装（设计稿 SecretField）：在 PasswordField 右侧吸附「显示/隐藏」与「复制」小按钮。
     * 明文态用与之双向绑定的 TextField 呈现，状态仍由传入的 PasswordField 承载（load/save 不变）。
     */
    private Node secretField(PasswordField secret) {
        TextField plain = new TextField();
        plain.textProperty().bindBidirectional(secret.textProperty());
        plain.promptTextProperty().bind(secret.promptTextProperty());
        // 镜像密钥框的样式类（password-field 行为类除外）
        secret.getStyleClass().stream()
                .filter(c -> !"password-field".equals(c) && !plain.getStyleClass().contains(c))
                .forEach(plain.getStyleClass()::add);
        plain.setVisible(false);
        plain.setManaged(false);
        plain.prefWidthProperty().bind(secret.prefWidthProperty());

        Button showBtn = new Button("显示");
        showBtn.getStyleClass().add("field-adorn");
        showBtn.setFocusTraversable(false);
        showBtn.setOnAction(e -> {
            boolean toPlain = !plain.isVisible();
            plain.setVisible(toPlain);
            plain.setManaged(toPlain);
            secret.setVisible(!toPlain);
            secret.setManaged(!toPlain);
            showBtn.setText(toPlain ? "隐藏" : "显示");
        });

        Button copyBtn = new Button("复制");
        copyBtn.getStyleClass().add("field-adorn");
        copyBtn.setFocusTraversable(false);
        PauseTransition copiedReset = new PauseTransition(javafx.util.Duration.millis(1200));
        copyBtn.setOnAction(e -> {
            var content = new javafx.scene.input.ClipboardContent();
            content.putString(secret.getText() == null ? "" : secret.getText());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            copyBtn.setText("已复制");
            copyBtn.getStyleClass().add("field-adorn-active");
            copiedReset.stop();
            copiedReset.setOnFinished(ev -> {
                copyBtn.setText("复制");
                copyBtn.getStyleClass().remove("field-adorn-active");
            });
            copiedReset.playFromStart();
        });

        HBox adorns = new HBox(4, showBtn, copyBtn);
        adorns.setAlignment(Pos.CENTER_RIGHT);
        adorns.setPickOnBounds(false);
        adorns.setPadding(new Insets(0, 6, 0, 0));
        adorns.setMaxWidth(Region.USE_PREF_SIZE);
        adorns.setMaxHeight(Region.USE_PREF_SIZE);

        StackPane wrap = new StackPane(secret, plain, adorns);
        StackPane.setAlignment(adorns, Pos.CENTER_RIGHT);
        wrap.setMaxWidth(Region.USE_PREF_SIZE);
        return wrap;
    }

    /** 程序性填表（load/reset）统一入口：守卫期间的控件变更不计入 dirty */
    private void runFormLoad(Runnable loader) {
        formLoading = true;
        try {
            loader.run();
        } finally {
            formLoading = false;
        }
    }

    private TextField createTextField(String promptText) {
        TextField field = new TextField();
        field.setPromptText(promptText);
        field.getStyleClass().add("settings-field");
        return field;
    }

    private Label createLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("settings-label");
        label.setMinWidth(70);
        return label;
    }

    private void applyLabelStyle(HBox row) {
        row.getChildren().stream()
                .filter(n -> n instanceof Label)
                .forEach(n -> {
                    ((Label) n).getStyleClass().add("settings-label");
                    ((Label) n).setMinWidth(40);
                });
    }

    // ==================== 分级模型面板 ====================

    private Node buildTieredModelPanel() {
        Label sectionTitle = new Label("分级模型");
        sectionTitle.getStyleClass().add("settings-section-title");

        Label intro = new Label("""
                按任务复杂度划分三档模型：
                  • 轻量模型 — 意图识别、工具路由、记忆蒸馏、视觉描述、过程评估等轻量调用
                  • 普通模型 — 子专家、单步执行体、知识专家等常规任务
                  • 高性能模型 — 主编排器、规划、ChallengerAgent、PlanEvolver 等复杂推理（即「模型配置」中的现有模型）
                未启用独立配置时，对应档自动回落到「模型配置」中的高性能模型，保持向后兼容。""");
        intro.getStyleClass().add("settings-hint");
        intro.setWrapText(true);

        // 普通模型卡片
        Node normalCard = buildTierCard(
                "普通模型（NORMAL）",
                "子专家智能体、知识专家、单步执行体、记忆压缩等常规任务的模型",
                /*tierName=*/"normal");

        // 轻量模型卡片
        Node lightCard = buildTierCard(
                "轻量模型（LIGHT）",
                "工具路由、意图识别、记忆蒸馏、视觉预处理、过程评估等一次性轻量调用；强烈建议关闭思考模式",
                /*tierName=*/"light");

        // 清除分级配置按钮（保留在面板内；保存上移全局页脚）
        Button clearButton = new Button("清除分级配置");
        clearButton.getStyleClass().add("settings-reset-button");
        clearButton.setOnAction(e -> {
            Alert confirm = UIHelper.createConfirmAlert("清除分级配置",
                    "清除后普通/轻量模型都将回落到高性能模型（即「模型配置」中的现有模型），确定继续？",
                    null);
            confirm.showAndWait().ifPresent(btn -> {
                if (btn == ButtonType.OK) {
                    agentConfig.setNormalModelName("");
                    agentConfig.setNormalProviderType("");
                    agentConfig.setNormalBaseUrl("");
                    agentConfig.setNormalApiKey("");
                    agentConfig.setLightModelName("");
                    agentConfig.setLightProviderType("");
                    agentConfig.setLightBaseUrl("");
                    agentConfig.setLightApiKey("");
                    agentConfig.save();
                    runFormLoad(this::loadTieredModelSettings);
                    if (onModelConfigChanged != null) onModelConfigChanged.run();
                    dirtyPanels.remove(currentPanel);
                    refreshFooter();
                    setFooterStatus("已清除分级配置，所有档位回落到高性能模型", "status-info");
                }
            });
        });

        HBox buttonBar = new HBox(10, clearButton);
        buttonBar.setAlignment(Pos.CENTER_LEFT);

        VBox panel = new VBox(14,
                sectionTitle,
                intro,
                new Separator(),
                normalCard,
                new Separator(),
                lightCard,
                new Separator(),
                buttonBar);
        panel.setPadding(new Insets(4));

        ScrollPane scrollPane = new ScrollPane(panel);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.getStyleClass().add("settings-scroll-pane");
        return scrollPane;
    }

    /** 构建单个档位的卡片（NORMAL 或 LIGHT） */
    private Node buildTierCard(String title, String description, String tierName) {
        boolean isLight = "light".equals(tierName);

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("settings-group-title");

        Label descLabel = new Label(description);
        descLabel.getStyleClass().add("settings-hint");
        descLabel.setWrapText(true);

        ToggleSwitch enabledCheck = new ToggleSwitch();
        HBox enabledRow = toggleRow(enabledCheck, "启用独立配置",
                "关闭则该档回落到「模型配置」中的高性能模型");

        ComboBox<String> providerCombo = new ComboBox<>();
        providerCombo.getItems().addAll("OpenAI", "DashScope", "Anthropic", "Gemini", "Ollama");
        providerCombo.getStyleClass().add("settings-combo");

        TextField baseUrlField = createTextField("API 地址（留空则继承高性能模型）");
        baseUrlField.setPrefWidth(350);
        TextField modelNameField = createTextField("模型名称");
        modelNameField.setPrefWidth(350);
        PasswordField apiKeyField = new PasswordField();
        apiKeyField.setPromptText("API 密钥（留空则继承高性能模型）");
        apiKeyField.setPrefWidth(350);

        ToggleSwitch thinkingCheck = new ToggleSwitch();
        HBox thinkingCheckRow = toggleRow(thinkingCheck, "思考模式", isLight
                ? "不推荐，会显著拖慢路由/分类调用"
                : null);

        // 切换 enabled 时启用/禁用所有字段
        Runnable applyEnabled = () -> {
            boolean en = enabledCheck.isSelected();
            providerCombo.setDisable(!en);
            baseUrlField.setDisable(!en);
            modelNameField.setDisable(!en);
            apiKeyField.setDisable(!en);
            thinkingCheck.setDisable(!en);
        };
        enabledCheck.selectedProperty().addListener((obs, oldV, newV) -> applyEnabled.run());

        // provider 切换时帮用户填一份推荐 baseUrl + modelName（仅作为提示，可手动改）
        providerCombo.setOnAction(e -> applyTierProviderPreset(
                providerCombo.getValue(), baseUrlField, modelNameField));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.add(createLabel("提供商："), 0, 0);
        grid.add(providerCombo, 1, 0);
        grid.add(createLabel("API 地址："), 0, 1);
        grid.add(baseUrlField, 1, 1);
        grid.add(createLabel("模型名称："), 0, 2);
        grid.add(modelNameField, 1, 2);
        grid.add(createLabel("API 密钥："), 0, 3);
        grid.add(secretField(apiKeyField), 1, 3);

        VBox card = new VBox(8, titleLabel, descLabel, enabledRow, grid, thinkingCheckRow);

        // 把控件挂到对应字段
        if (isLight) {
            this.lightTierEnabledCheck = enabledCheck;
            this.lightProviderCombo = providerCombo;
            this.lightBaseUrlField = baseUrlField;
            this.lightModelNameField = modelNameField;
            this.lightApiKeyField = apiKeyField;
            this.lightThinkingEnabledCheck = thinkingCheck;
        } else {
            this.normalTierEnabledCheck = enabledCheck;
            this.normalProviderCombo = providerCombo;
            this.normalBaseUrlField = baseUrlField;
            this.normalModelNameField = modelNameField;
            this.normalApiKeyField = apiKeyField;
            this.normalThinkingEnabledCheck = thinkingCheck;
        }

        return card;
    }

    /** 切换分级档的提供商时，按提供商常用值预填 baseUrl + modelName */
    private void applyTierProviderPreset(String provider, TextField baseUrlField, TextField modelNameField) {
        if (provider == null) return;
        switch (provider) {
            case "OpenAI" -> {
                if (baseUrlField.getText().isBlank()) baseUrlField.setText("https://api.openai.com/v1");
                if (modelNameField.getText().isBlank()) modelNameField.setText("gpt-4o-mini");
            }
            case "DashScope" -> {
                if (baseUrlField.getText().isBlank()) {
                    baseUrlField.setText("https://dashscope.aliyuncs.com/compatible-mode/v1");
                }
                if (modelNameField.getText().isBlank()) modelNameField.setText("qwen-turbo");
            }
            case "Anthropic" -> {
                if (baseUrlField.getText().isBlank()) baseUrlField.setText("https://api.anthropic.com");
                if (modelNameField.getText().isBlank()) modelNameField.setText("claude-haiku-4-5-20251001");
            }
            case "Gemini" -> {
                if (modelNameField.getText().isBlank()) modelNameField.setText("gemini-2.5-flash");
            }
            case "Ollama" -> {
                if (baseUrlField.getText().isBlank()) baseUrlField.setText("http://localhost:11434");
                if (modelNameField.getText().isBlank()) modelNameField.setText("qwen3:8b");
            }
        }
    }

    /** 将分级模型配置加载到表单 */
    private void loadTieredModelSettings() {
        // NORMAL 档
        boolean normalEnabled = agentConfig.isNormalTierConfigured();
        normalTierEnabledCheck.setSelected(normalEnabled);
        normalProviderCombo.setValue(agentConfig.getNormalProviderType());
        normalBaseUrlField.setText(agentConfig.getNormalBaseUrl());
        normalModelNameField.setText(normalEnabled ? agentConfig.getNormalModelName() : "");
        normalApiKeyField.setText(normalEnabled ? agentConfig.getNormalApiKey() : "");
        normalThinkingEnabledCheck.setSelected(agentConfig.isNormalThinkingEnabled());
        normalProviderCombo.setDisable(!normalEnabled);
        normalBaseUrlField.setDisable(!normalEnabled);
        normalModelNameField.setDisable(!normalEnabled);
        normalApiKeyField.setDisable(!normalEnabled);
        normalThinkingEnabledCheck.setDisable(!normalEnabled);

        // LIGHT 档
        boolean lightEnabled = agentConfig.isLightTierConfigured();
        lightTierEnabledCheck.setSelected(lightEnabled);
        lightProviderCombo.setValue(agentConfig.getLightProviderType());
        lightBaseUrlField.setText(agentConfig.getLightBaseUrl());
        lightModelNameField.setText(lightEnabled ? agentConfig.getLightModelName() : "");
        lightApiKeyField.setText(lightEnabled ? agentConfig.getLightApiKey() : "");
        lightThinkingEnabledCheck.setSelected(agentConfig.isLightThinkingEnabled());
        lightProviderCombo.setDisable(!lightEnabled);
        lightBaseUrlField.setDisable(!lightEnabled);
        lightModelNameField.setDisable(!lightEnabled);
        lightApiKeyField.setDisable(!lightEnabled);
        lightThinkingEnabledCheck.setDisable(!lightEnabled);
    }

    /** 将分级模型表单保存到配置 */
    private void saveTieredModelSettings() {
        // NORMAL — enabled 未勾选 → 写空字符串触发回落
        if (normalTierEnabledCheck.isSelected()) {
            agentConfig.setNormalProviderType(
                    normalProviderCombo.getValue() == null ? "" : normalProviderCombo.getValue());
            agentConfig.setNormalBaseUrl(normalBaseUrlField.getText().trim());
            agentConfig.setNormalModelName(normalModelNameField.getText().trim());
            agentConfig.setNormalApiKey(normalApiKeyField.getText().trim());
            agentConfig.setNormalThinkingEnabled(normalThinkingEnabledCheck.isSelected());
        } else {
            agentConfig.setNormalProviderType("");
            agentConfig.setNormalBaseUrl("");
            agentConfig.setNormalModelName("");
            agentConfig.setNormalApiKey("");
        }

        // LIGHT
        if (lightTierEnabledCheck.isSelected()) {
            agentConfig.setLightProviderType(
                    lightProviderCombo.getValue() == null ? "" : lightProviderCombo.getValue());
            agentConfig.setLightBaseUrl(lightBaseUrlField.getText().trim());
            agentConfig.setLightModelName(lightModelNameField.getText().trim());
            agentConfig.setLightApiKey(lightApiKeyField.getText().trim());
            agentConfig.setLightThinkingEnabled(lightThinkingEnabledCheck.isSelected());
        } else {
            agentConfig.setLightProviderType("");
            agentConfig.setLightBaseUrl("");
            agentConfig.setLightModelName("");
            agentConfig.setLightApiKey("");
        }

        agentConfig.save();
        log.info("分级模型配置已保存 — NORMAL configured: {}, LIGHT configured: {}",
                agentConfig.isNormalTierConfigured(), agentConfig.isLightTierConfigured());
    }
}
