package com.javaclaw.config;

import com.javaclaw.ui.javafx.agent.AgentSettingsPanel;
import com.javaclaw.ui.javafx.agent.AgentSettingsPanelFactory;
import com.javaclaw.app.UIHelper;
import com.javaclaw.ui.javafx.control.ToggleSwitch;
import com.javaclaw.ui.javafx.site.SiteCredentialPanel;
import com.javaclaw.ui.javafx.site.SiteCredentialPanelFactory;
import com.javaclaw.ui.javafx.settings.BehaviorSettingsSectionFactory;
import com.javaclaw.ui.javafx.settings.EmbeddingSettingsController;
import com.javaclaw.ui.javafx.settings.CommunicationSettingsSectionFactory;
import com.javaclaw.ui.javafx.settings.EmailSettingsController;
import com.javaclaw.ui.javafx.settings.ModelSettingsController;
import com.javaclaw.ui.javafx.settings.ModelSettingsSectionFactory;
import com.javaclaw.ui.javafx.settings.SettingsFieldSupport;
import com.javaclaw.ui.javafx.settings.SettingsSectionView;
import com.javaclaw.ui.javafx.settings.TieredModelSettingsController;
import com.javaclaw.ui.javafx.settings.NotificationSettingsController;
import com.javaclaw.ui.javafx.settings.GeneralSettingsController;
import com.javaclaw.ui.javafx.settings.GepaSettingsController;
import com.javaclaw.ui.javafx.settings.SkillEvolutionSettingsController;
import com.javaclaw.ui.javafx.settings.MaintenanceSettingsSectionFactory;
import com.javaclaw.ui.javafx.settings.TestDataMaintenanceController;
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

/**
 * 设置界面（模态对话框）
 *
 * <p>左侧分类导航 + 右侧配置面板的布局，
 * 支持按功能分类管理配置项，方便后续扩展。</p>
 *
 * @author JavaClaw
 */
public class SettingsView {

    private final Stage stage;
    private Runnable onModelConfigChanged;
    private final AgentSettingsPanelFactory agentSettingsPanels;
    private AgentSettingsPanel agentSettingsPanel;
    private final SiteCredentialPanelFactory siteCredentialPanels;
    private SiteCredentialPanel siteCredentialPanel;
    private final com.javaclaw.ui.javafx.mcp.McpCenterViewFactory mcpCenters;
    private com.javaclaw.ui.javafx.mcp.McpCenterView mcpCenter;
    private final ModelSettingsSectionFactory modelSettingsSections;
    private SettingsSectionView<ModelSettingsController> modelSettingsSection;
    private SettingsSectionView<TieredModelSettingsController> tieredModelSettingsSection;
    private SettingsSectionView<EmbeddingSettingsController> embeddingSettingsSection;
    private final CommunicationSettingsSectionFactory communicationSettingsSections;
    private SettingsSectionView<EmailSettingsController> emailSettingsSection;
    private SettingsSectionView<NotificationSettingsController> notificationSettingsSection;
    private final BehaviorSettingsSectionFactory behaviorSettingsSections;
    private SettingsSectionView<GepaSettingsController> gepaSettingsSection;
    private SettingsSectionView<SkillEvolutionSettingsController> skillEvolutionSettingsSection;
    private SettingsSectionView<GeneralSettingsController> generalSettingsSection;
    private final MaintenanceSettingsSectionFactory maintenanceSettingsSections;
    private SettingsSectionView<TestDataMaintenanceController> testDataMaintenanceSection;

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
    @FunctionalInterface
    private interface PanelSaveAction {
        void run(Runnable success, java.util.function.Consumer<Throwable> failure);
    }

    private record PanelActions(PanelSaveAction save,
                                java.util.function.Supplier<String> savedTip,
                                Runnable test,
                                String testLabel) {
        static PanelActions saveOnly(Runnable save) {
            return saveOnly(save, null);
        }
        static PanelActions saveOnly(Runnable save, java.util.function.Supplier<String> savedTip) {
            return new PanelActions(synchronous(save), savedTip, null, null);
        }
        static PanelActions saveAndTest(Runnable save,
                                        java.util.function.Supplier<String> savedTip,
                                        Runnable test,
                                        String testLabel) {
            return new PanelActions(synchronous(save), savedTip, test, testLabel);
        }
        static PanelActions asyncSaveAndTest(
                PanelSaveAction save,
                java.util.function.Supplier<String> savedTip,
                Runnable test,
                String testLabel) {
            return new PanelActions(save, savedTip, test, testLabel);
        }
        static PanelActions asyncSaveOnly(
                PanelSaveAction save,
                java.util.function.Supplier<String> savedTip) {
            return new PanelActions(save, savedTip, null, null);
        }
        /** 组件型面板：内部交互自管理，全局保存/测试均禁用 */
        static PanelActions none() {
            return new PanelActions(null, null, null, null);
        }

        private static PanelSaveAction synchronous(Runnable save) {
            return (success, failure) -> {
                try {
                    save.run();
                    success.run();
                } catch (Throwable thrown) {
                    failure.accept(thrown);
                }
            };
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

    public SettingsView(Stage owner,
                        AgentSettingsPanelFactory agentSettingsPanels,
                        SiteCredentialPanelFactory siteCredentialPanels,
                        com.javaclaw.ui.javafx.mcp.McpCenterViewFactory mcpCenters,
                        ModelSettingsSectionFactory modelSettingsSections,
                        CommunicationSettingsSectionFactory communicationSettingsSections,
                        BehaviorSettingsSectionFactory behaviorSettingsSections,
                        MaintenanceSettingsSectionFactory maintenanceSettingsSections) {
        this.agentSettingsPanels = java.util.Objects.requireNonNull(
                agentSettingsPanels, "agentSettingsPanels");
        this.siteCredentialPanels = java.util.Objects.requireNonNull(
                siteCredentialPanels, "siteCredentialPanels");
        this.mcpCenters = java.util.Objects.requireNonNull(mcpCenters, "mcpCenters");
        this.modelSettingsSections = java.util.Objects.requireNonNull(
                modelSettingsSections, "modelSettingsSections");
        this.communicationSettingsSections = java.util.Objects.requireNonNull(
                communicationSettingsSections, "communicationSettingsSections");
        this.behaviorSettingsSections = java.util.Objects.requireNonNull(
                behaviorSettingsSections, "behaviorSettingsSections");
        this.maintenanceSettingsSections = java.util.Objects.requireNonNull(
                maintenanceSettingsSections, "maintenanceSettingsSections");
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

        // 模型配置：FXML Controller 负责保存和 API 连通性测试。
        modelSettingsSection = modelSettingsSections.createModel(ignored -> { });
        Node modelPanel = modelSettingsSection.root();
        modelSettingsSection.controller().configure(
                result -> coreSettingsApplied(modelPanel, result));
        addCategory("模型配置", modelPanel, true,
                "api key base url provider openai anthropic ollama dashscope gemini 模型 思考 thinking 高级 http 超时 timeout 迭代 循环");
        registerPanelActions(modelPanel, PanelActions.asyncSaveAndTest(
                (success, failure) -> modelSettingsSection.controller().save(
                        ignored -> success.run(), failure),
                this::appliedModelConfigTip,
                () -> modelSettingsSection.controller().probe(
                        result -> finishTest(modelPanel, result.message(),
                                result.succeeded() ? "status-success" : "status-error"),
                        failure -> finishTest(modelPanel, "连接失败: " + failureMessage(failure),
                                "status-error")),
                "测试连接"));

        // 分级模型：仅保存（含重建回调）
        tieredModelSettingsSection = modelSettingsSections.createTiers(ignored -> { });
        Node tieredModelPanel = tieredModelSettingsSection.root();
        tieredModelSettingsSection.controller().configure(
                result -> coreSettingsApplied(tieredModelPanel, result));
        addCategory("分级模型", tieredModelPanel, false,
                "tier 分级 轻量 light 普通 normal 高性能 high 路由 routing 意图 intent 规划");
        registerPanelActions(tieredModelPanel, PanelActions.asyncSaveOnly(
                (success, failure) -> tieredModelSettingsSection.controller().save(
                        ignored -> success.run(), failure), this::appliedModelConfigTip));

        // 嵌入模型（知识库向量模型）：保存（含重建回调）+ 测试嵌入。
        // 文档导入/检索/分块管理已迁移到「知识库中心」，此处仅统一配置向量嵌入模型。
        embeddingSettingsSection = modelSettingsSections.createEmbedding(ignored -> { });
        Node embeddingPanel = embeddingSettingsSection.root();
        embeddingSettingsSection.controller().configure(
                result -> coreSettingsApplied(embeddingPanel, result));
        addCategory("嵌入模型", embeddingPanel, false,
                "rag embedding 嵌入 向量 vector 检索 文档 knowledge 知识库 维度 dimension");
        registerPanelActions(embeddingPanel, PanelActions.asyncSaveAndTest(
                (success, failure) -> embeddingSettingsSection.controller().save(
                        ignored -> success.run(), failure),
                this::appliedModelConfigTip,
                () -> embeddingSettingsSection.controller().probe(
                        result -> finishTest(embeddingPanel, result.message(),
                                result.succeeded() ? "status-success" : "status-error"),
                        failure -> finishTest(embeddingPanel,
                                "嵌入测试失败: " + failureMessage(failure), "status-error")),
                "测试嵌入"));

        // 智能体：组件型面板，自管理（全局保存/测试禁用）
        agentSettingsPanel = agentSettingsPanels.create(this::notifyModelConfigChanged);
        Node agentPanel = agentSettingsPanel.root();
        addCategory("智能体", agentPanel, false,
                "agent expert orchestrator iters 迭代 子智能体 编排");
        registerPanelActions(agentPanel, PanelActions.none());

        // 智能能力：影响智能体推理过程的高阶能力
        addCategoryGroup("智能能力");

        gepaSettingsSection = behaviorSettingsSections.createGepa(ignored -> { });
        Node gepaPanel = gepaSettingsSection.root();
        gepaSettingsSection.controller().configure(
                result -> behaviorSettingsApplied(gepaPanel, result));
        addCategory("GEPA 能力", gepaPanel, false,
                "gepa 自适应 规划 trajectory 目标");
        registerPanelActions(gepaPanel, PanelActions.asyncSaveOnly(
                (success, failure) -> gepaSettingsSection.controller().save(
                        ignored -> success.run(), failure), this::appliedModelConfigTip));

        skillEvolutionSettingsSection = behaviorSettingsSections.createSkillEvolution(
                ignored -> { });
        Node skillEvolutionPanel = skillEvolutionSettingsSection.root();
        skillEvolutionSettingsSection.controller().configure(
                result -> behaviorSettingsApplied(skillEvolutionPanel, result));
        addCategory("技能进化", skillEvolutionPanel, false,
                "skill 技能 自学习 进化 沉淀 提案 hermes");
        registerPanelActions(skillEvolutionPanel, PanelActions.asyncSaveOnly(
                (success, failure) -> skillEvolutionSettingsSection.controller().save(
                        ignored -> success.run(), failure),
                () -> "✓ 已保存，下一轮对话生效"));

        // 外部集成：连接外部系统与资源
        addCategoryGroup("外部集成");

        // MCP 服务器：组件型面板，自管理
        mcpCenter = mcpCenters.createPanel(this::notifyModelConfigChanged);
        Node mcpPanel = mcpCenter.root();
        addCategory("MCP 服务器", mcpPanel, false,
                "mcp model context protocol server 服务器 claude desktop");
        registerPanelActions(mcpPanel, PanelActions.none());

        // 站点管理：组件型面板，自管理
        siteCredentialPanel = siteCredentialPanels.create();
        Node sitePanel = siteCredentialPanel.root();
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

        // 字体：组件型面板，选择即时全局生效并按工作区记忆
        Node fontPanel = buildFontPanel();
        addCategory("字体", fontPanel, false,
                "字体 font typeface sans mono 等宽 字号 密度 缩放 inter noto cascadia jetbrains 排版 对话");
        registerPanelActions(fontPanel, PanelActions.none());

        // 通用：界面与后台行为
        addCategoryGroup("通用");

        generalSettingsSection = behaviorSettingsSections.createGeneral(ignored -> { });
        Node generalPanel = generalSettingsSection.root();
        generalSettingsSection.controller().configure(
                result -> behaviorSettingsApplied(generalPanel, result));
        addCategory("通用设置", generalPanel, false,
                "托盘 tray 后台 常驻 最小化 关闭 minimize 窗口 退出 background "
                        + "托管任务 风险 评估 自动放行 确认 目录 risk autoapprove 免确认");
        registerPanelActions(generalPanel, PanelActions.asyncSaveOnly(
                (success, failure) -> generalSettingsSection.controller().save(
                        ignored -> success.run(), failure), () -> "✓ 已保存"));

        // 系统维护：历史测试数据只读扫描，清理必须二次人工确认。
        addCategoryGroup("系统维护");

        testDataMaintenanceSection = maintenanceSettingsSections.createTestDataMaintenance();
        Node dataMaintenancePanel = testDataMaintenanceSection.root();
        addCategory("测试数据清理", dataMaintenancePanel, false,
                "data junit test 测试 数据 临时目录 清理 storage maintenance");
        registerPanelActions(dataMaintenancePanel, PanelActions.none());

        // 通信渠道：邮件与外部通知
        addCategoryGroup("通信渠道");

        // 邮件配置：FXML Controller 负责持久化和 SMTP/IMAP 探测。
        emailSettingsSection = communicationSettingsSections.createEmail(ignored -> { });
        Node emailPanel = emailSettingsSection.root();
        emailSettingsSection.controller().configure(
                result -> communicationSettingsApplied(emailPanel, result));
        addCategory("邮件配置", emailPanel, false,
                "smtp imap email mail 邮箱 发件 收件");
        registerPanelActions(emailPanel, PanelActions.asyncSaveAndTest(
                (success, failure) -> emailSettingsSection.controller().save(
                        ignored -> success.run(), failure),
                () -> "✓ 已保存",
                () -> emailSettingsSection.controller().probe(
                        result -> finishTest(emailPanel, result.message(),
                                result.succeeded() ? "status-success" : "status-error"),
                        failure -> finishTest(emailPanel,
                                "邮件测试失败: " + failureMessage(failure), "status-error")),
                "测试收发"));

        notificationSettingsSection = communicationSettingsSections.createNotifications(
                ignored -> { });
        Node notificationPanel = notificationSettingsSection.root();
        notificationSettingsSection.controller().configure(
                result -> communicationSettingsApplied(notificationPanel, result));
        addCategory("通知配置", notificationPanel, false,
                "钉钉 dingtalk 企业微信 wework 飞书 lark webhook notification 通知");
        registerPanelActions(notificationPanel, PanelActions.asyncSaveOnly(
                (success, failure) -> notificationSettingsSection.controller().save(
                        ignored -> success.run(), failure), () -> "✓ 已保存"));

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
            try {
                actions.test().run();
            } catch (Throwable failure) {
                finishTest(currentPanel, "测试失败: " + failureMessage(failure), "status-error");
            }
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
        stage.addEventHandler(javafx.stage.WindowEvent.WINDOW_HIDDEN, event -> {
            if (agentSettingsPanel != null) {
                agentSettingsPanel.close();
                agentSettingsPanel = null;
            }
            if (siteCredentialPanel != null) {
                siteCredentialPanel.close();
                siteCredentialPanel = null;
            }
            if (mcpCenter != null) {
                mcpCenter.close();
                mcpCenter = null;
            }
            if (modelSettingsSection != null) {
                modelSettingsSection.close();
                modelSettingsSection = null;
            }
            if (tieredModelSettingsSection != null) {
                tieredModelSettingsSection.close();
                tieredModelSettingsSection = null;
            }
            if (embeddingSettingsSection != null) {
                embeddingSettingsSection.close();
                embeddingSettingsSection = null;
            }
            if (emailSettingsSection != null) {
                emailSettingsSection.close();
                emailSettingsSection = null;
            }
            if (notificationSettingsSection != null) {
                notificationSettingsSection.close();
                notificationSettingsSection = null;
            }
            if (gepaSettingsSection != null) {
                gepaSettingsSection.close();
                gepaSettingsSection = null;
            }
            if (skillEvolutionSettingsSection != null) {
                skillEvolutionSettingsSection.close();
                skillEvolutionSettingsSection = null;
            }
            if (generalSettingsSection != null) {
                generalSettingsSection.close();
                generalSettingsSection = null;
            }
            if (testDataMaintenanceSection != null) {
                testDataMaintenanceSection.close();
                testDataMaintenanceSection = null;
            }
        });

        stage.setScene(scene);

        // 加载当前配置到表单（formLoading 守卫内，程序性填充不计 dirty）
        loadSettings();

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
        Node savingPanel = currentPanel;
        footSaveButton.setDisable(true);
        setFooterStatus("正在保存…", "status-info");
        try {
            actions.save().run(() -> {
                dirtyPanels.remove(savingPanel);
                if (currentPanel == savingPanel) {
                    showSavedTip(actions.savedTip() != null
                            ? actions.savedTip().get() : "✓ 已保存，下一轮对话生效");
                }
                refreshFooterStateOnly();
                refreshNavDirtyMarks();
            }, failure -> {
                if (currentPanel == savingPanel) {
                    setFooterStatus("保存失败: " + failureMessage(failure), "status-error");
                    footSaveButton.setDisable(false);
                }
            });
        } catch (Throwable failure) {
            setFooterStatus("保存失败: " + failureMessage(failure), "status-error");
            footSaveButton.setDisable(false);
        }
    }

    private void refreshFooterStateOnly() {
        PanelActions currentActions = panelActions.getOrDefault(currentPanel, PanelActions.none());
        footSaveButton.setDisable(currentActions.save() == null
                || !dirtyPanels.contains(currentPanel));
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
        if (formLoading || SettingsFieldSupport.isLoading(panel)) return;
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

    /** 测试结束回调：恢复测试按钮文案/可用性并写入结果。 */
    private void finishTest(String resultText, String cssClass) {
        finishTest(currentPanel, resultText, cssClass);
    }

    private void finishTest(Node testedPanel, String resultText, String cssClass) {
        testRunning = false;
        if (testedPanel != currentPanel) {
            refreshFooter();
            return;
        }
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

    private void coreSettingsApplied(Node panel,
            com.javaclaw.application.settings.ModelSettingsApplicationService.SaveResult result) {
        settingsApplied(panel, result == null ? "" : result.message());
        if (result != null && result.runtimeRefreshRequired() && onModelConfigChanged != null) {
            onModelConfigChanged.run();
        }
    }

    private void communicationSettingsApplied(Node panel,
            com.javaclaw.application.settings.CommunicationSettingsApplicationService.SaveResult result) {
        settingsApplied(panel, result == null ? "" : result.message());
    }

    private void behaviorSettingsApplied(Node panel,
            com.javaclaw.application.settings.BehaviorSettingsApplicationService.SaveResult result) {
        settingsApplied(panel, result == null ? "" : result.message());
        if (result != null && result.runtimeRefreshRequired() && onModelConfigChanged != null) {
            onModelConfigChanged.run();
        }
    }

    private void settingsApplied(Node panel, String message) {
        dirtyPanels.remove(panel);
        if (panel == currentPanel && message != null && !message.isBlank()) {
            setFooterStatus(message, "status-success");
        }
        refreshFooterStateOnly();
        refreshNavDirtyMarks();
    }

    private String appliedModelConfigTip() {
        return onModelConfigChanged != null
                ? "✓ 已保存并生效，下一轮对话重建智能体服务"
                : "✓ 已保存（重启后生效）";
    }

    private static String failureMessage(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName() : message;
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

    /**
     * 「设置 › 字体」面板（组件型，选择即时全局生效，无保存按钮）。
     * 与「界面风格」共用样式类：界面字体双列卡片 + 等宽分段 + 密度分段 + 实时说明。
     * 字体选择经 {@link com.javaclaw.ui.javafx.theme.FontManager} 立即应用到全部窗口并按工作区记忆。
     */
    private Node buildFontPanel() {
        Label sectionTitle = new Label("字体");
        sectionTitle.getStyleClass().add("sec-title");

        Label hint = new Label(
                "选择界面与对话使用的字体，立即生效并记忆到本工作区 —— 应用于所有窗口、弹窗与对话气泡。"
                        + "系统原生为默认，仅展示本机或应用包中实际可用的字体。");
        hint.getStyleClass().add("sec-hint");
        hint.setWrapText(true);

        // ===== 界面字体（双列卡片，预览以该字体渲染样张）=====
        Label uiTitle = new Label("界面字体");
        uiTitle.getStyleClass().add("grp-title");

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        ColumnConstraints half = new ColumnConstraints();
        half.setPercentWidth(50);
        grid.getColumnConstraints().addAll(half, half);

        java.util.List<Runnable> refreshers = new java.util.ArrayList<>();
        int idx = 0;
        for (com.javaclaw.ui.javafx.theme.FontManager.FontOption opt
                : com.javaclaw.ui.javafx.theme.FontManager.availableFontOptions()) {
            Label name = new Label(opt.name());
            name.getStyleClass().add("theme-card-name");
            Label sub = new Label(opt.subtitle());
            sub.getStyleClass().add("theme-card-sub");
            VBox text = new VBox(1, name, sub);
            HBox.setHgrow(text, Priority.ALWAYS);

            Label check = new Label("✓");
            check.getStyleClass().add("theme-card-check");

            HBox head = new HBox(8, text, check);
            head.setAlignment(Pos.CENTER_LEFT);

            // 样张：以该字体族渲染（直观对比字形）
            Label sample = new Label("现代极简 Studio 字体 Ag");
            sample.setStyle("-fx-font-family: " + opt.stack() + "; -fx-font-size: 18px; -fx-text-fill: -jc-text-title;");

            VBox card = new VBox(8, head, sample);
            card.getStyleClass().add("theme-card");
            card.setPadding(new Insets(13, 15, 14, 15));
            card.setOnMouseClicked(e -> com.javaclaw.ui.javafx.theme.FontManager.setFontFamily(opt.id()));

            Runnable refresh = () -> {
                boolean on = opt.id().equals(com.javaclaw.ui.javafx.theme.FontManager.getFontFamily());
                card.getStyleClass().remove("theme-card-selected");
                if (on) card.getStyleClass().add("theme-card-selected");
                check.setVisible(on);
            };
            refresh.run();
            refreshers.add(refresh);

            grid.add(card, idx % 2, idx / 2);
            idx++;
        }

        // ===== 等宽字体（分段控件）=====
        Label monoTitle = new Label("等宽字体");
        monoTitle.getStyleClass().add("grp-title");
        HBox monoSeg = new HBox(2);
        monoSeg.getStyleClass().add("seg-container");
        monoSeg.setAlignment(Pos.CENTER_LEFT);
        ToggleGroup monoGroup = new ToggleGroup();
        for (com.javaclaw.ui.javafx.theme.FontManager.MonoOption m
                : com.javaclaw.ui.javafx.theme.FontManager.availableMonoOptions()) {
            ToggleButton tb = new ToggleButton(m.name());
            tb.getStyleClass().add("seg-btn");
            tb.setToggleGroup(monoGroup);
            tb.setSelected(m.id().equals(com.javaclaw.ui.javafx.theme.FontManager.getMonoFamily()));
            tb.setOnAction(e -> { if (tb.isSelected()) com.javaclaw.ui.javafx.theme.FontManager.setMonoFamily(m.id()); else tb.setSelected(true); });
            Runnable r = () -> tb.setSelected(m.id().equals(com.javaclaw.ui.javafx.theme.FontManager.getMonoFamily()));
            refreshers.add(r);
            monoSeg.getChildren().add(tb);
        }

        // ===== 字号 / 密度（分段控件）=====
        Label densTitle = new Label("字号 / 密度");
        densTitle.getStyleClass().add("grp-title");
        HBox densSeg = new HBox(2);
        densSeg.getStyleClass().add("seg-container");
        densSeg.setAlignment(Pos.CENTER_LEFT);
        ToggleGroup densGroup = new ToggleGroup();
        for (com.javaclaw.ui.javafx.theme.FontManager.Density d : com.javaclaw.ui.javafx.theme.FontManager.DENSITIES) {
            ToggleButton tb = new ToggleButton(d.name());
            tb.getStyleClass().add("seg-btn");
            tb.setToggleGroup(densGroup);
            tb.setSelected(d.id().equals(com.javaclaw.ui.javafx.theme.FontManager.getDensity()));
            tb.setOnAction(e -> { if (tb.isSelected()) com.javaclaw.ui.javafx.theme.FontManager.setDensity(d.id()); else tb.setSelected(true); });
            Runnable r = () -> tb.setSelected(d.id().equals(com.javaclaw.ui.javafx.theme.FontManager.getDensity()));
            refreshers.add(r);
            densSeg.getChildren().add(tb);
        }

        Label note = new Label(
                "选择后立即应用到全部已打开窗口，并写入本工作区配置（ui.font.*）。"
                        + "等宽字体影响代码块、token 计数与时间戳；密度影响对话正文字号与行高。");
        note.getStyleClass().add("sec-hint");
        note.setWrapText(true);

        // 外部变更（如别处切换工作区 reload）时统一刷新本面板选中态
        com.javaclaw.ui.javafx.theme.FontManager.revisionProperty().addListener((obs, o, n) -> refreshers.forEach(Runnable::run));

        VBox panel = new VBox(8,
                sectionTitle, hint,
                uiTitle, grid,
                monoTitle, monoSeg,
                densTitle, densSeg,
                new Separator(),
                note);
        panel.setPadding(new Insets(4));

        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.getStyleClass().add("settings-scroll-pane");
        scrollPane.setContent(panel);
        return scrollPane;
    }

    /**
     * 将当前配置加载到表单控件
     */
    private void loadSettings() {
        if (modelSettingsSection != null) modelSettingsSection.controller().reload();
        if (tieredModelSettingsSection != null) tieredModelSettingsSection.controller().reload();
        if (embeddingSettingsSection != null) embeddingSettingsSection.controller().reload();
        if (emailSettingsSection != null) emailSettingsSection.controller().reload();
        if (notificationSettingsSection != null) {
            notificationSettingsSection.controller().reload();
        }
        if (gepaSettingsSection != null) gepaSettingsSection.controller().reload();
        if (skillEvolutionSettingsSection != null) {
            skillEvolutionSettingsSection.controller().reload();
        }
        if (generalSettingsSection != null) generalSettingsSection.controller().reload();
    }

    /**
     * 设置模型配置变更回调，保存模型配置后自动触发（用于重建智能体服务）
     */
    public void setOnModelConfigChanged(Runnable callback) {
        this.onModelConfigChanged = callback;
    }

    private void notifyModelConfigChanged() {
        if (onModelConfigChanged != null) onModelConfigChanged.run();
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

    /**
     * 打开设置并直达指定分类（按分类名精确匹配，如「嵌入模型」）；找不到则退化为默认分类。
     * 供「知识库中心 › 前往模型设置」等深链入口复用。
     */
    public void show(String categoryName) {
        if (categoryName != null) {
            for (NavGroup group : navGroups) {
                for (ToggleButton btn : group.categories) {
                    if (categoryName.equals(btn.getText())) {
                        group.expanded = true;
                        applyNavVisibility();
                        btn.setSelected(true);
                        if (btn.getProperties().get("navPanel") instanceof Node panel) {
                            showPanel(panel);
                        }
                        show();
                        return;
                    }
                }
            }
        }
        show();
    }

    // ==================== UI 辅助方法 ====================

    /**
     * 密钥输入框包装（设计稿 SecretField）：在 PasswordField 右侧吸附「显示/隐藏」与「复制」小按钮。
     * 明文态用与之双向绑定的 TextField 呈现，状态仍由传入的 PasswordField 承载（load/save 不变）。
     */

    /** 程序性填表（load/reset）统一入口：守卫期间的控件变更不计入 dirty */
    private void runFormLoad(Runnable loader) {
        formLoading = true;
        try {
            loader.run();
        } finally {
            formLoading = false;
        }
    }

}
