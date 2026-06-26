package com.javaclaw.chat;

import com.javaclaw.app.UIHelper;
import com.javaclaw.config.Workspace;
import com.javaclaw.config.WorkspaceManager;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 左侧侧边栏视图（支持多会话管理）
 *
 * <p>包含功能入口按钮、会话列表和底部用户信息区域。
 * 会话列表支持新建、选中切换、右键删除。</p>
 *
 * @author JavaClaw
 */
public class SidebarView {

    private static final Logger log = LoggerFactory.getLogger(SidebarView.class);

    private final VBox root;
    private final VBox conversationList;
    private final ComboBox<Workspace> workspaceCombo;

    /** 当前选中的会话 ID */
    private String selectedSessionId;

    /** 新建会话回调 */
    private Runnable onNewChat;

    /** 切换会话回调（参数为目标会话 ID） */
    private Consumer<String> onSwitchSession;

    /** 删除会话回调（参数为目标会话 ID） */
    private Consumer<String> onDeleteSession;

    /** 批量删除会话回调（参数为会话 ID 列表） */
    private Consumer<List<String>> onBatchDeleteSessions;

    /** 批量选择模式 */
    private boolean batchMode;
    private final Set<String> checkedSessionIds = java.util.Collections.synchronizedSet(new HashSet<>());
    private Button manageLabel;
    private Button batchDeleteBtn;
    private Button selectAllBtn;
    private HBox batchDeleteRow;

    /** 搜索框 */
    private TextField searchField;

    /** 当前最后一个分组标签的 key */
    private String lastGroupKey = "";

    /** 设置按钮回调 */
    private Runnable onOpenSettings;

    /** 技能中心回调 */
    private Runnable onOpenSkillCenter;

    /** 定时任务回调 */
    private Runnable onOpenScheduler;

    /** 知识库回调 */
    private Runnable onOpenKnowledgeBase;

    /** 任务管理回调 */
    private Runnable onOpenTaskManager;

    /** 工作区切换回调（参数为目标工作区 ID） */
    private Consumer<String> onSwitchWorkspace;

    /** 正在刷新工作区下拉，暂时禁止触发切换回调 */
    private boolean refreshingWorkspaceCombo = false;

    /** MCP 服务器入口回调 */
    private Runnable onOpenMcp;

    /** 插件中心入口回调 */
    private Runnable onOpenPluginCenter;

    /** 定时任务导航行（用于徽章更新） */
    private HBox scheduleNavRow;

    /** 技能中心导航行（徽章 = 待审提案数） */
    private HBox skillNavRow;

    /** 托管任务导航行（徽章 = 进行中/待人工任务数） */
    private HBox taskNavRow;

    /** 会话数量计数（section header 右侧显示） */
    private Label sessionCountLabel;

    public SidebarView() {
        log.info("开始构建侧边栏");

        // ==================== 品牌头（设计稿 sb-brand：✦ 渐变方标 + 字标 + 版本号） ====================

        Label brandMark = new Label("✦");
        brandMark.getStyleClass().add("sb-brand-mark");

        Label brandName = new Label("JavaClaw");
        brandName.getStyleClass().add("sb-brand-name");

        Label brandVer = new Label("v1.0");
        brandVer.getStyleClass().add("sb-brand-ver");

        Region brandSpacer = new Region();
        HBox.setHgrow(brandSpacer, Priority.ALWAYS);

        HBox brandRow = new HBox(10, brandMark, brandName, brandSpacer, brandVer);
        brandRow.setAlignment(Pos.CENTER_LEFT);
        brandRow.setPadding(new Insets(16, 16, 12, 16));

        // ==================== 工作区选择器（设计稿单行紧凑形态 + 保留新建/删除能力） ====================

        WorkspaceManager wsMgr = WorkspaceManager.getInstance();

        workspaceCombo = new ComboBox<>();
        workspaceCombo.getItems().addAll(wsMgr.getWorkspaces());
        workspaceCombo.getStyleClass().add("sidebar-workspace-combo");
        workspaceCombo.setMaxWidth(Double.MAX_VALUE);
        // 选中当前工作区
        for (Workspace ws : wsMgr.getWorkspaces()) {
            if (ws.getId().equals(wsMgr.getCurrentWorkspaceId())) {
                workspaceCombo.getSelectionModel().select(ws);
                break;
            }
        }
        workspaceCombo.setOnAction(e -> {
            if (refreshingWorkspaceCombo) return;
            Workspace selected = workspaceCombo.getSelectionModel().getSelectedItem();
            if (selected != null && !selected.getId().equals(wsMgr.getCurrentWorkspaceId())) {
                // 延迟到 ComboBox 事件处理完成后再执行切换，
                // 避免在 onAction 内部修改 ComboBox items 导致 JavaFX 内部状态异常
                String targetId = selected.getId();
                javafx.application.Platform.runLater(() -> {
                    if (onSwitchWorkspace != null) {
                        onSwitchWorkspace.accept(targetId);
                    }
                });
            }
        });

        // 新建/删除工作区按钮
        Button addWsBtn = new Button("+");
        addWsBtn.getStyleClass().add("sidebar-ws-btn");
        addWsBtn.setTooltip(new Tooltip("新建工作区"));
        addWsBtn.setOnAction(e -> onCreateWorkspace());

        Button delWsBtn = new Button("-");
        delWsBtn.getStyleClass().add("sidebar-ws-btn");
        delWsBtn.setTooltip(new Tooltip("删除选中的工作区"));
        delWsBtn.setOnAction(e -> onDeleteWorkspace());

        HBox.setHgrow(workspaceCombo, Priority.ALWAYS);
        HBox wsBox = new HBox(4, workspaceCombo, addWsBtn, delWsBtn);
        wsBox.setAlignment(Pos.CENTER_LEFT);
        wsBox.setPadding(new Insets(2, 12, 8, 12));

        // ==================== 「＋ 新建对话」主按钮（设计稿 sb-new 渐变全宽） ====================

        Button newChatBtn = new Button("＋ 新建对话");
        newChatBtn.getStyleClass().add("sb-new-btn");
        newChatBtn.setMaxWidth(Double.MAX_VALUE);
        newChatBtn.setTooltip(new Tooltip("新建对话（⌘N）"));
        newChatBtn.setOnAction(e -> {
            if (onNewChat != null) onNewChat.run();
        });
        VBox newChatBox = new VBox(newChatBtn);
        newChatBox.setPadding(new Insets(4, 12, 8, 12));

        // ==================== 底部功能导航（设计稿置于会话列表之下、用户栏之上） ====================

        HBox skillRow = buildNavRow("\u2699", "技能中心", null, null, false, () -> {
            if (onOpenSkillCenter != null) onOpenSkillCenter.run();
        });
        this.skillNavRow = skillRow;
        HBox mcpRow = buildNavRow("\uD83D\uDD0C", "MCP 服务器", "\u2318M", null, false, () -> {
            if (onOpenMcp != null) onOpenMcp.run();
        });
        HBox scheduleRow = buildNavRow("\u23F0", "定时任务", null, null, false, () -> {
            if (onOpenScheduler != null) onOpenScheduler.run();
        });
        this.scheduleNavRow = scheduleRow;
        HBox knowledgeRow = buildNavRow("\uD83D\uDCDA", "知识库", null, null, false, () -> {
            if (onOpenKnowledgeBase != null) onOpenKnowledgeBase.run();
        });
        HBox taskMgrRow = buildNavRow("\u25A6", "托管任务", null, null, false, () -> {
            if (onOpenTaskManager != null) onOpenTaskManager.run();
        });
        this.taskNavRow = taskMgrRow;
        HBox settingsRow = buildNavRow("\u2699", "设置", "\u2318,", null, false, () -> {
            if (onOpenSettings != null) onOpenSettings.run();
        });

        HBox pluginRow = buildNavRow("🧩", "插件中心", null, null, false, () -> {
            if (onOpenPluginCenter != null) onOpenPluginCenter.run();
        });
        VBox actionButtons = new VBox(2, skillRow, mcpRow, scheduleRow, knowledgeRow, taskMgrRow, pluginRow, settingsRow);
        actionButtons.setPadding(new Insets(8, 8, 8, 8));

        // ==================== 任务/会话列表区域 ====================

        Label taskSectionLabel = new Label("会话");
        taskSectionLabel.getStyleClass().add("sidebar-section-title");

        sessionCountLabel = new Label("");
        sessionCountLabel.getStyleClass().add("sidebar-section-count");

        manageLabel = new Button("管理");
        manageLabel.getStyleClass().add("sidebar-manage-btn");
        manageLabel.setTooltip(new Tooltip("批量管理会话"));
        manageLabel.setOnAction(e -> toggleBatchMode());

        Region sectionSpacer = new Region();
        HBox.setHgrow(sectionSpacer, Priority.ALWAYS);

        HBox sectionHeader = new HBox(6, taskSectionLabel, sectionSpacer, sessionCountLabel, manageLabel);
        sectionHeader.setAlignment(Pos.CENTER_LEFT);
        sectionHeader.setPadding(new Insets(10, 16, 6, 16));

        // 搜索框（设计稿：内嵌 ⌕ 放大镜图标）
        searchField = new TextField();
        searchField.setPromptText("搜索会话…");
        searchField.getStyleClass().add("sidebar-search-field");
        searchField.setStyle("-fx-padding: 8 12 8 28;");  // 左侧留出图标位
        searchField.textProperty().addListener((obs, oldVal, newVal) -> filterSessions(newVal));

        Label searchIcon = new Label("⌕");
        searchIcon.getStyleClass().add("sidebar-search-icon");
        searchIcon.setMouseTransparent(true);
        javafx.scene.layout.StackPane searchRow = new javafx.scene.layout.StackPane(searchField, searchIcon);
        javafx.scene.layout.StackPane.setAlignment(searchIcon, Pos.CENTER_LEFT);
        javafx.scene.layout.StackPane.setMargin(searchIcon, new Insets(0, 0, 0, 10));
        VBox searchBox = new VBox(searchRow);
        searchBox.setPadding(new Insets(0, 12, 6, 12));

        // 批量操作按钮行（默认隐藏）
        selectAllBtn = new Button("全选");
        selectAllBtn.getStyleClass().add("sidebar-batch-select-all-btn");
        selectAllBtn.setOnAction(e -> onToggleSelectAll());

        batchDeleteBtn = new Button("删除选中");
        batchDeleteBtn.getStyleClass().add("sidebar-batch-delete-btn");
        batchDeleteBtn.setDisable(true);
        batchDeleteBtn.setOnAction(e -> onBatchDelete());
        HBox.setHgrow(batchDeleteBtn, Priority.ALWAYS);

        batchDeleteRow = new HBox(6, selectAllBtn, batchDeleteBtn);
        batchDeleteRow.setPadding(new Insets(4, 8, 4, 8));
        batchDeleteRow.setVisible(false);
        batchDeleteRow.setManaged(false);

        // 会话列表容器
        conversationList = new VBox(6);
        conversationList.setPadding(new Insets(6, 8, 6, 8));

        ScrollPane listScroll = new ScrollPane(conversationList);
        listScroll.getStyleClass().add("sidebar-list-scroll");
        listScroll.setFitToWidth(true);
        listScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        listScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        VBox.setVgrow(listScroll, Priority.ALWAYS);

        // ==================== 底部用户信息区域 ====================

        Separator bottomSep = new Separator();
        bottomSep.getStyleClass().add("sidebar-separator");

        // 用户身份栏（设计稿 sb-foot：头像 Y + 「你」 + 「本地工作区 · 离线优先」；
        // 设置入口移至上方功能导航行，主题切换统一走顶栏「风格」菜单）
        Label userNameLabel = new Label("你");
        userNameLabel.getStyleClass().add("sidebar-user-name");

        Label userTeamLabel = new Label("本地工作区 · 离线优先");
        userTeamLabel.getStyleClass().add("sidebar-user-team");

        VBox userInfo = new VBox(1, userNameLabel, userTeamLabel);

        Label avatarLabel = new Label("Y");
        avatarLabel.getStyleClass().add("sidebar-avatar");

        HBox bottomBar = new HBox(10, avatarLabel, userInfo);
        bottomBar.setAlignment(Pos.CENTER_LEFT);
        bottomBar.setPadding(new Insets(12, 14, 12, 14));
        bottomBar.getStyleClass().add("sidebar-bottom-bar");

        // ==================== 组装侧边栏（设计稿顺序：品牌 → 工作区 → 搜索 → 新建 → 会话列表 → 功能导航 → 用户栏） ====================

        Separator navSep = new Separator();
        navSep.getStyleClass().add("sidebar-separator");

        root = new VBox();
        root.getStyleClass().add("sidebar-root");
        root.getChildren().addAll(
                brandRow,
                wsBox,
                searchBox,
                newChatBox,
                sectionHeader,
                batchDeleteRow,
                listScroll,
                navSep,
                actionButtons,
                bottomSep,
                bottomBar
        );
        root.setPrefWidth(240);
        root.setMinWidth(200);
        root.setMaxWidth(280);

        log.info("侧边栏构建完成");
    }

    // ==================== 会话列表操作 ====================

    /**
     * 添加一个会话到列表
     *
     * @param session  会话对象
     * @param selected 是否选中
     */
    public void addSession(ChatSession session, boolean selected) {
        // 时间分组
        String groupKey = getGroupKey(session.getCreatedAt());
        if (!groupKey.equals(lastGroupKey)) {
            conversationList.getChildren().add(createGroupHeader(groupKey));
            lastGroupKey = groupKey;
        }

        HBox row = createConversationRow(session);
        if (selected) {
            row.getStyleClass().add("sidebar-conv-selected");
            selectedSessionId = session.getId();
        }
        conversationList.getChildren().add(row);
        updateSessionCount(getSessionCount());
        recomputeGroupCounts();
    }

    /**
     * 在列表顶部插入一个新会话（新建的会话显示在最前面）
     */
    public void insertSessionAtTop(ChatSession session, boolean selected) {
        HBox row = createConversationRow(session);
        if (selected) {
            clearSelection();
            row.getStyleClass().add("sidebar-conv-selected");
            selectedSessionId = session.getId();
        }
        // 新会话总是"今天"，检查是否需要在顶部插入分组标签
        String groupKey = "今天";
        boolean hasGroupHeader = false;
        if (!conversationList.getChildren().isEmpty()) {
            javafx.scene.Node first = conversationList.getChildren().getFirst();
            if (isGroupHeader(first)) {
                hasGroupHeader = "今天".equals(groupHeaderText(first));
            }
        }
        if (!hasGroupHeader) {
            conversationList.getChildren().addFirst(createGroupHeader(groupKey));
        }
        // 插入到"今天"标签之后
        conversationList.getChildren().add(1, row);
        updateSessionCount(getSessionCount());
        recomputeGroupCounts();
    }

    /**
     * 更新指定会话的标题
     */
    public void updateSessionTitle(String sessionId, String newTitle) {
        for (javafx.scene.Node node : conversationList.getChildren()) {
            if (sessionId.equals(node.getUserData())) {
                HBox row = (HBox) node;
                // 按样式类定位标题标签（批量模式下列序会变化）
                for (javafx.scene.Node child : row.getChildren()) {
                    if (child instanceof Label titleLabel
                            && titleLabel.getStyleClass().contains("sidebar-conv-title")) {
                        String display = newTitle.length() > 18
                                ? newTitle.substring(0, 18) + "..." : newTitle;
                        titleLabel.setText(display);
                        titleLabel.setTooltip(new javafx.scene.control.Tooltip(newTitle));
                        break;
                    }
                }
                return;
            }
        }
    }

    /**
     * 从列表中移除指定会话
     */
    public void removeSession(String sessionId) {
        conversationList.getChildren().removeIf(node -> sessionId.equals(node.getUserData()));
        updateSessionCount(getSessionCount());
        recomputeGroupCounts();
    }

    /**
     * 选中指定会话
     */
    public void selectSession(String sessionId) {
        clearSelection();
        for (javafx.scene.Node node : conversationList.getChildren()) {
            if (sessionId.equals(node.getUserData())) {
                node.getStyleClass().add("sidebar-conv-selected");
                selectedSessionId = sessionId;
                return;
            }
        }
    }

    /**
     * 清除所有选中状态
     */
    private void clearSelection() {
        for (javafx.scene.Node node : conversationList.getChildren()) {
            node.getStyleClass().remove("sidebar-conv-selected");
        }
    }

    /**
     * 创建会话列表项 UI
     */
    private HBox createConversationRow(ChatSession session) {
        return createConversationRow(session, formatConvTime(session.getCreatedAt()));
    }

    /**
     * 创建会话列表项 UI（设计稿：✦ 图标 + 标题 + 右侧 mono 时间）。
     *
     * @param timeText 右侧时间文案（重建列表时透传原值，避免时间被重置）
     */
    private HBox createConversationRow(ChatSession session, String timeText) {
        Label icon = new Label("✦");
        icon.getStyleClass().add("sidebar-conv-icon");

        String displayTitle = session.getTitle();
        if (displayTitle.length() > 18) {
            displayTitle = displayTitle.substring(0, 18) + "...";
        }
        Label titleLabel = new Label(displayTitle);
        titleLabel.getStyleClass().add("sidebar-conv-title");
        titleLabel.setMaxWidth(160);
        titleLabel.setTooltip(new javafx.scene.control.Tooltip(session.getTitle()));

        Region rowSpacer = new Region();
        HBox.setHgrow(rowSpacer, Priority.ALWAYS);
        Label timeLabel = new Label(timeText == null ? "" : timeText);
        timeLabel.getStyleClass().add("sidebar-conv-time");

        HBox row = new HBox(6);
        row.getProperties().put("convTime", timeText == null ? "" : timeText);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("sidebar-conv-row");
        row.setPadding(new Insets(6, 8, 6, 8));

        // 用 userData 存储会话 ID
        row.setUserData(session.getId());

        if (batchMode) {
            CheckBox checkBox = new CheckBox();
            checkBox.setSelected(checkedSessionIds.contains(session.getId()));
            checkBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal) {
                    checkedSessionIds.add(session.getId());
                } else {
                    checkedSessionIds.remove(session.getId());
                }
                updateBatchDeleteBtn();
            });
            row.getChildren().addAll(checkBox, icon, titleLabel, rowSpacer, timeLabel);

            // 批量模式下点击行切换勾选
            row.setOnMouseClicked(e -> {
                checkBox.setSelected(!checkBox.isSelected());
            });
        } else {
            row.getChildren().addAll(icon, titleLabel, rowSpacer, timeLabel);

            // 点击切换会话
            row.setOnMouseClicked(e -> {
                String targetId = (String) row.getUserData();
                if (!targetId.equals(selectedSessionId)) {
                    clearSelection();
                    row.getStyleClass().add("sidebar-conv-selected");
                    selectedSessionId = targetId;
                    if (onSwitchSession != null) {
                        onSwitchSession.accept(targetId);
                    }
                }
            });

            // 右键菜单：删除会话
            ContextMenu contextMenu = UIHelper.createContextMenu();
            MenuItem deleteItem = UIHelper.createDangerMenuItem("删除会话");
            deleteItem.setOnAction(e -> {
                String targetId = (String) row.getUserData();
                if (onDeleteSession != null) {
                    onDeleteSession.accept(targetId);
                }
            });
            contextMenu.getItems().add(deleteItem);
            row.setOnContextMenuRequested(e ->
                    contextMenu.show(row, e.getScreenX(), e.getScreenY()));
        }

        return row;
    }

    public String getSelectedSessionId() {
        return selectedSessionId;
    }

    public int getSessionCount() {
        return (int) conversationList.getChildren().stream()
                .filter(n -> n instanceof HBox && !isGroupHeader(n))
                .count();
    }

    public VBox getRoot() {
        return root;
    }

    // ==================== 回调设置 ====================

    public void setOnNewChat(Runnable callback) {
        this.onNewChat = callback;
    }

    public void setOnSwitchSession(Consumer<String> callback) {
        this.onSwitchSession = callback;
    }

    public void setOnDeleteSession(Consumer<String> callback) {
        this.onDeleteSession = callback;
    }

    public void setOnBatchDeleteSessions(Consumer<List<String>> callback) {
        this.onBatchDeleteSessions = callback;
    }

    public void setOnOpenSettings(Runnable callback) {
        this.onOpenSettings = callback;
    }

    public void setOnOpenSkillCenter(Runnable callback) {
        this.onOpenSkillCenter = callback;
    }

    public void setOnOpenScheduler(Runnable callback) {
        this.onOpenScheduler = callback;
    }

    public void setOnOpenKnowledgeBase(Runnable callback) {
        this.onOpenKnowledgeBase = callback;
    }

    public void setOnOpenTaskManager(Runnable callback) {
        this.onOpenTaskManager = callback;
    }

    public void setOnOpenMcp(Runnable callback) {
        this.onOpenMcp = callback;
    }

    public void setOnOpenPluginCenter(Runnable callback) {
        this.onOpenPluginCenter = callback;
    }

    public void setOnSwitchWorkspace(Consumer<String> callback) {
        this.onSwitchWorkspace = callback;
    }

    // ==================== 工作区操作 ====================

    /**
     * 新建工作区
     */
    private void onCreateWorkspace() {
        TextInputDialog dialog = UIHelper.createTextInputDialog("新工作区", "新建工作区", "工作区名称:", null);
        Optional<String> result = dialog.showAndWait();
        result.ifPresent(name -> {
            if (!name.isBlank()) {
                Workspace ws = WorkspaceManager.getInstance().createWorkspace(name.trim());
                workspaceCombo.getItems().add(ws);
                workspaceCombo.getSelectionModel().select(ws);
                if (onSwitchWorkspace != null) {
                    onSwitchWorkspace.accept(ws.getId());
                }
            }
        });
    }

    /**
     * 删除当前选中的工作区
     */
    private void onDeleteWorkspace() {
        WorkspaceManager wsMgr = WorkspaceManager.getInstance();
        Workspace selected = workspaceCombo.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        if (wsMgr.getWorkspaces().size() <= 1) {
            UIHelper.createWarningAlert("不能删除最后一个工作区", null).showAndWait();
            return;
        }

        Alert confirm = UIHelper.createConfirmAlert("删除工作区",
                "确定要删除工作区「" + selected.getName() + "」吗？\n此操作将永久删除该工作区的所有数据。", null);
        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK) {
                boolean isCurrent = selected.getId().equals(wsMgr.getCurrentWorkspaceId());
                if (isCurrent) {
                    // 删除当前工作区前，先切换到另一个工作区
                    Workspace switchTarget = wsMgr.getWorkspaces().stream()
                            .filter(ws -> !ws.getId().equals(selected.getId()))
                            .findFirst().orElse(null);
                    if (switchTarget != null && onSwitchWorkspace != null) {
                        onSwitchWorkspace.accept(switchTarget.getId());
                    }
                }
                wsMgr.deleteWorkspace(selected.getId());
                workspaceCombo.getItems().remove(selected);
                if (isCurrent) {
                    // 选中切换后的当前工作区
                    Workspace current = wsMgr.findById(wsMgr.getCurrentWorkspaceId());
                    if (current != null) {
                        workspaceCombo.getSelectionModel().select(current);
                    }
                }
            }
        });
    }

    /**
     * 刷新工作区下拉列表（工作区切换完成后调用）
     */
    public void refreshWorkspaceCombo() {
        refreshingWorkspaceCombo = true;
        try {
            WorkspaceManager wsMgr = WorkspaceManager.getInstance();
            workspaceCombo.getItems().clear();
            workspaceCombo.getItems().addAll(wsMgr.getWorkspaces());
            for (Workspace ws : wsMgr.getWorkspaces()) {
                if (ws.getId().equals(wsMgr.getCurrentWorkspaceId())) {
                    workspaceCombo.getSelectionModel().select(ws);
                    break;
                }
            }
        } finally {
            refreshingWorkspaceCombo = false;
        }
    }

    // ==================== 批量管理 ====================

    private void toggleBatchMode() {
        batchMode = !batchMode;
        checkedSessionIds.clear();
        manageLabel.setText(batchMode ? "完成" : "管理");
        batchDeleteRow.setVisible(batchMode);
        batchDeleteRow.setManaged(batchMode);
        updateBatchDeleteBtn();
        rebuildConversationList();
    }

    private void onToggleSelectAll() {
        int total = getSessionCount();
        boolean selectAll = checkedSessionIds.size() < total;

        checkedSessionIds.clear();
        if (selectAll) {
            for (javafx.scene.Node node : conversationList.getChildren()) {
                if (!(node instanceof HBox)) continue;
                String id = (String) node.getUserData();
                if (id != null) checkedSessionIds.add(id);
            }
        }

        // 同步所有 CheckBox 状态
        for (javafx.scene.Node node : conversationList.getChildren()) {
            if (!(node instanceof HBox row)) continue;
            if (!row.getChildren().isEmpty() && row.getChildren().getFirst() instanceof CheckBox cb) {
                cb.setSelected(selectAll);
            }
        }

        updateBatchDeleteBtn();
    }

    private void updateBatchDeleteBtn() {
        int count = checkedSessionIds.size();
        int total = getSessionCount();
        batchDeleteBtn.setText(count > 0 ? "删除选中（" + count + "）" : "删除选中");
        batchDeleteBtn.setDisable(count == 0);
        selectAllBtn.setText(count >= total && total > 0 ? "取消全选" : "全选");
    }

    private void onBatchDelete() {
        if (checkedSessionIds.isEmpty()) return;

        int count = checkedSessionIds.size();
        Alert alert = UIHelper.createConfirmAlert("确认批量删除",
                "确定要删除选中的 " + count + " 个会话吗？", null);

        alert.showAndWait().ifPresent(result -> {
            if (result == ButtonType.OK && onBatchDeleteSessions != null) {
                List<String> ids = new ArrayList<>(checkedSessionIds);
                checkedSessionIds.clear();
                // 退出批量模式
                batchMode = false;
                manageLabel.setText("管理");
                batchDeleteRow.setVisible(false);
                batchDeleteRow.setManaged(false);
                onBatchDeleteSessions.accept(ids);
            }
        });
    }

    /**
     * 重建会话列表（批量模式切换时刷新 CheckBox 状态）
     *
     * <p>保留原有的时间分组标签（今天 / 昨天 / 本周 / 更早）— 按当前列表顺序记录
     * 每个会话行所属的分组名，重建时按顺序插回分组标签，避免分组丢失。
     */
    private void rebuildConversationList() {
        // 按序收集 (groupKey, sessionId, title) 三元组，保留原始分组归属
        List<String[]> sessionInfos = new ArrayList<>();
        String currentGroup = "";
        for (javafx.scene.Node node : conversationList.getChildren()) {
            if (isGroupHeader(node)) {
                currentGroup = groupHeaderText(node);
                continue;
            }
            if (!(node instanceof HBox row)) continue;
            String id = (String) row.getUserData();
            String title = "";
            for (javafx.scene.Node child : row.getChildren()) {
                if (child instanceof Label label && label.getStyleClass().contains("sidebar-conv-title")) {
                    title = label.getTooltip() != null ? label.getTooltip().getText() : label.getText();
                    break;
                }
            }
            String timeText = String.valueOf(row.getProperties().getOrDefault("convTime", ""));
            sessionInfos.add(new String[]{currentGroup, id, title, timeText});
        }

        conversationList.getChildren().clear();
        lastGroupKey = "";
        String lastEmittedGroup = "";
        for (String[] info : sessionInfos) {
            String groupKey = info[0];
            String id = info[1];
            String title = info[2];
            String timeText = info[3];

            // 分组切换时插入分组标题
            if (groupKey != null && !groupKey.isEmpty() && !groupKey.equals(lastEmittedGroup)) {
                conversationList.getChildren().add(createGroupHeader(groupKey));
                lastEmittedGroup = groupKey;
            }

            ChatSession tempSession = new ChatSession(id, title, LocalDateTime.now(), null);
            HBox row = createConversationRow(tempSession, timeText);
            if (!batchMode && id.equals(selectedSessionId)) {
                row.getStyleClass().add("sidebar-conv-selected");
            }
            conversationList.getChildren().add(row);
        }
        lastGroupKey = lastEmittedGroup;
        recomputeGroupCounts();
    }

    /**
     * 清空会话列表（工作区切换时调用）
     */
    public void clearSessions() {
        conversationList.getChildren().clear();
        selectedSessionId = null;
        lastGroupKey = "";
        updateSessionCount(0);
    }

    // ==================== 分组标题（设计稿 sb-section：名称 + 右侧「共 N 条」） ====================

    /** 创建会话时间分组标题行（HBox 形态：名称 + 弹性空隙 + 计数） */
    private HBox createGroupHeader(String key) {
        Label name = new Label(key);
        name.getStyleClass().add("sidebar-group-header-text");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label count = new Label("");
        count.getStyleClass().add("sidebar-section-count");
        HBox header = new HBox(6, name, spacer, count);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("sidebar-group-header");
        return header;
    }

    /** 判断节点是否为分组标题行 */
    private static boolean isGroupHeader(javafx.scene.Node node) {
        return node instanceof HBox && node.getStyleClass().contains("sidebar-group-header");
    }

    /** 取分组标题行的分组名 */
    private static String groupHeaderText(javafx.scene.Node node) {
        if (node instanceof HBox header) {
            for (javafx.scene.Node child : header.getChildren()) {
                if (child instanceof Label label
                        && label.getStyleClass().contains("sidebar-group-header-text")) {
                    return label.getText();
                }
            }
        }
        return "";
    }

    /** 重算各分组右侧「共 N 条」计数 */
    private void recomputeGroupCounts() {
        HBox currentHeader = null;
        int count = 0;
        for (javafx.scene.Node node : conversationList.getChildren()) {
            if (isGroupHeader(node)) {
                applyGroupCount(currentHeader, count);
                currentHeader = (HBox) node;
                count = 0;
            } else if (node instanceof HBox) {
                count++;
            }
        }
        applyGroupCount(currentHeader, count);
    }

    private void applyGroupCount(HBox header, int count) {
        if (header == null) return;
        for (javafx.scene.Node child : header.getChildren()) {
            if (child instanceof Label label
                    && label.getStyleClass().contains("sidebar-section-count")) {
                label.setText(count > 0 ? "共 " + count + " 条" : "");
                return;
            }
        }
    }

    /**
     * 会话行右侧时间文案（设计稿：今天显示 HH:mm，昨天/周几/更早显示相对描述）
     */
    private static String formatConvTime(LocalDateTime createdAt) {
        if (createdAt == null) return "";
        LocalDate today = LocalDate.now();
        LocalDate date = createdAt.toLocalDate();
        if (date.equals(today)) {
            return String.format("%02d:%02d", createdAt.getHour(), createdAt.getMinute());
        }
        if (date.equals(today.minusDays(1))) return "昨天";
        if (date.isAfter(today.minusDays(7))) {
            return switch (date.getDayOfWeek()) {
                case MONDAY -> "周一"; case TUESDAY -> "周二"; case WEDNESDAY -> "周三";
                case THURSDAY -> "周四"; case FRIDAY -> "周五"; case SATURDAY -> "周六";
                case SUNDAY -> "周日";
            };
        }
        return String.format("%02d-%02d", date.getMonthValue(), date.getDayOfMonth());
    }

    // ==================== 搜索与分组 ====================

    /**
     * 根据创建时间获取分组标签
     */
    private String getGroupKey(LocalDateTime createdAt) {
        LocalDate today = LocalDate.now();
        LocalDate date = createdAt.toLocalDate();
        if (date.equals(today)) return "今天";
        if (date.equals(today.minusDays(1))) return "昨天";
        if (date.isAfter(today.minusDays(7))) return "本周";
        return "更早";
    }

    /**
     * 根据搜索关键词过滤会话列表
     */
    private void filterSessions(String query) {
        String keyword = query == null ? "" : query.trim().toLowerCase();
        for (javafx.scene.Node node : conversationList.getChildren()) {
            if (isGroupHeader(node)) {
                // 分组标签的可见性取决于下面是否有可见的会话
                continue;
            }
            if (node instanceof HBox row) {
                if (keyword.isEmpty()) {
                    row.setVisible(true);
                    row.setManaged(true);
                } else {
                    String title = "";
                    for (javafx.scene.Node child : row.getChildren()) {
                        if (child instanceof Label l && l.getStyleClass().contains("sidebar-conv-title")) {
                            title = l.getTooltip() != null ? l.getTooltip().getText() : l.getText();
                            break;
                        }
                    }
                    boolean match = title.toLowerCase().contains(keyword);
                    row.setVisible(match);
                    row.setManaged(match);
                }
            }
        }
        // 更新分组标签可见性
        updateGroupHeaderVisibility();
    }

    /**
     * 更新分组标签可见性（如果分组内没有可见会话则隐藏标签）
     */
    private void updateGroupHeaderVisibility() {
        javafx.scene.Node currentHeader = null;
        boolean hasVisibleChild = false;
        for (javafx.scene.Node node : conversationList.getChildren()) {
            if (isGroupHeader(node)) {
                // 处理上一个分组
                if (currentHeader != null) {
                    currentHeader.setVisible(hasVisibleChild);
                    currentHeader.setManaged(hasVisibleChild);
                }
                currentHeader = node;
                hasVisibleChild = false;
            } else if (node instanceof HBox row) {
                if (row.isVisible()) {
                    hasVisibleChild = true;
                }
            }
        }
        // 处理最后一个分组
        if (currentHeader != null) {
            currentHeader.setVisible(hasVisibleChild);
            currentHeader.setManaged(hasVisibleChild);
        }
    }

    // ==================== 导航行构建辅助 ====================

    /**
     * 构建一个侧边栏导航行（图标 + 文案 + 可选快捷键 + 可选徽章）。
     *
     * @param iconText 前缀图标文字（emoji / unicode 符号）
     * @param label    主文案
     * @param shortcut 右侧快捷键提示（如 {@code "⌘N"}），{@code null} 表示不显示
     * @param badge    右侧徽章文字（如 {@code "3"}），{@code null} 表示不显示
     * @param active   是否初始高亮（品牌色背景）
     * @param onClick  点击回调
     * @return 可直接加入侧边栏的 HBox
     */
    private HBox buildNavRow(String iconText, String label, String shortcut,
                             String badge, boolean active, Runnable onClick) {
        Label icon = new Label(iconText);
        icon.getStyleClass().add("sidebar-nav-icon");

        Label text = new Label(label);
        text.getStyleClass().add("sidebar-nav-text");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().addAll("sidebar-action-row", "sidebar-nav-row");
        if (active) {
            row.getStyleClass().add("sidebar-nav-row-active");
        }
        row.setPadding(new Insets(6, 10, 6, 12));
        row.getChildren().addAll(icon, text, spacer);

        if (badge != null && !badge.isBlank()) {
            Label badgeLabel = new Label(badge);
            badgeLabel.getStyleClass().add("sidebar-nav-badge");
            row.getChildren().add(badgeLabel);
        }
        if (shortcut != null && !shortcut.isBlank()) {
            Label shortcutLabel = new Label(shortcut);
            shortcutLabel.getStyleClass().add("sidebar-nav-shortcut");
            row.getChildren().add(shortcutLabel);
        }

        row.setCursor(javafx.scene.Cursor.HAND);
        row.setOnMouseClicked(e -> {
            if (onClick != null) onClick.run();
        });
        return row;
    }

    /**
     * 更新定时任务徽章（右侧"3"之类的小圆角标签）。
     *
     * @param count 待执行任务数，{@code <= 0} 时移除徽章
     */
    public void updateScheduleBadge(int count) {
        updateNavBadge(scheduleNavRow, count);
    }

    /**
     * 更新技能中心徽章（待审提案数，设计稿 sb-navrow badge）。
     */
    public void updateSkillBadge(int count) {
        updateNavBadge(skillNavRow, count);
    }

    /**
     * 更新托管任务徽章（进行中 + 待人工任务数）。
     */
    public void updateTaskBadge(int count) {
        updateNavBadge(taskNavRow, count);
    }

    /** 通用导航行徽章更新：插到快捷键之前、spacer 之后；count <= 0 时移除 */
    private void updateNavBadge(HBox navRow, int count) {
        if (navRow == null) return;
        // 先移除已有徽章
        navRow.getChildren().removeIf(n ->
                n instanceof Label l && l.getStyleClass().contains("sidebar-nav-badge"));
        if (count <= 0) return;
        Label badge = new Label(Integer.toString(count));
        badge.getStyleClass().add("sidebar-nav-badge");
        int insertAt = navRow.getChildren().size();
        for (int i = 0; i < navRow.getChildren().size(); i++) {
            javafx.scene.Node node = navRow.getChildren().get(i);
            if (node instanceof Label lb && lb.getStyleClass().contains("sidebar-nav-shortcut")) {
                insertAt = i;
                break;
            }
        }
        navRow.getChildren().add(insertAt, badge);
    }

    /**
     * 更新会话分区"共 N 条"计数。
     */
    public void updateSessionCount(int count) {
        if (sessionCountLabel != null) {
            sessionCountLabel.setText(count > 0 ? "共 " + count + " 条" : "");
        }
    }

}
