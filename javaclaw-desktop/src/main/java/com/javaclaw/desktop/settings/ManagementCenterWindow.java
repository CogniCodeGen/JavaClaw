package com.javaclaw.desktop.settings;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.javaclaw.desktop.DesktopConfigurationChange;
import com.javaclaw.desktop.DesktopNotificationSubscription;
import com.javaclaw.desktop.DesktopStylesheets;
import com.javaclaw.desktop.appearance.DesktopAppearanceManager;
import com.javaclaw.desktop.component.ManagementPageShell;
import com.javaclaw.desktop.component.PlatformDialogs;

/** 单实例、非阻塞的 JavaClaw 6 设置与管理中心窗口。 */
public final class ManagementCenterWindow {
    static final double MINIMUM_WIDTH = 880;
    static final double MINIMUM_HEIGHT = 620;
    private static final KeyCodeCombination SHORTCUT =
            new KeyCodeCombination(KeyCode.COMMA, KeyCombination.SHORTCUT_DOWN);
    private static final List<Destination> DESTINATIONS = destinations();
    private static final Set<String> WORKSPACE_SCOPED_PAGES = Set.of(
            "roles",
            "learning",
            "permissions",
            "unattended-grants",
            "network-grants",
            "mcp",
            "site",
            "workspace",
            "coding",
            "instructions",
            "worktrees",
            "jobs",
            "plan",
            "loop",
            "workflow",
            "sdd",
            "schedule",
            "memory",
            "knowledge",
            "skill");

    private final DesktopAppearanceManager appearance;
    private final ManagementSettingsGateways gateways;
    private final ManagementWindowPreferenceStore preferences;
    private final DesktopNotificationSubscription configurationSubscription;
    private final ObservableList<Destination> filtered = FXCollections.observableArrayList();
    private final Map<String, ScrollPane> pageScrollers = new HashMap<>();
    private Stage stage;
    private ManagementPageShell shell;
    private SettingsPageRegistry pages;
    private ListView<Destination> navigation;
    private ManagementScopeSession scope;
    private Destination selected;
    private ManagedSettingsPage activePage;
    private boolean activePageActivated;
    private boolean restoringSelection;
    private boolean scopeWriteAvailable;
    private Alert pendingNotice;

    /**
     * 创建设置与管理中心协调器；窗口在第一次打开时才创建。
     *
     * @param appearance 跨 Scene 外观协调器
     * @param gateways 强类型 Java SDK 管理边界集合
     */
    public ManagementCenterWindow(DesktopAppearanceManager appearance, ManagementSettingsGateways gateways) {
        this(appearance, gateways, new JavaPreferencesManagementWindowStore());
    }

    ManagementCenterWindow(
            DesktopAppearanceManager appearance,
            ManagementSettingsGateways gateways,
            ManagementWindowPreferenceStore preferences) {
        this.appearance = Objects.requireNonNull(appearance, "appearance");
        this.gateways = Objects.requireNonNull(gateways, "gateways");
        this.preferences = Objects.requireNonNull(preferences, "preferences");
        configurationSubscription = gateways.core().onConfigurationChanged(change -> {
            if (scope != null && change.kind() == DesktopConfigurationChange.Kind.WORKSPACES) {
                scope.invalidate();
                if (isShowing()) {
                    scope.activate();
                }
            }
        });
    }

    /**
     * 为主 Scene 安装平台快捷键 Ctrl/Cmd+,。
     *
     * @param scene 接收快捷键的 Scene
     */
    public void installShortcut(Scene scene) {
        Scene checked = Objects.requireNonNull(scene, "scene");
        checked.getAccelerators().put(SHORTCUT, () -> show(checked.getWindow()));
    }

    /**
     * 打开或聚焦设置中心；不会等待 App Server 连接。
     *
     * @param owner 主窗口
     */
    public void show(Window owner) {
        show(owner, null);
    }

    /** 服务重连时使页面缓存失效并重验可见页面；隐藏页延至下次激活读取，保留独立工作区和草稿。 */
    public void refreshExecutionConfiguration() {
        if (pages != null) {
            pages.invalidateCaches();
            scope.invalidate();
        }
        if (!isShowing()) {
            return;
        }
        scope.activate();
        if (activePage instanceof WorkspaceSettingsPage workspacePage) {
            workspacePage.refreshExecutionConfiguration();
        } else if (activePage != null && !activePage.dirty() && !activePage.pending()) {
            activePage.activate();
        }
    }

    /**
     * 打开或聚焦设置中心并导航到指定页面。
     *
     * <p>当前操作未完成时先阻止离页，再检查未保存草稿；导航请求不会中断操作或丢弃草稿。
     *
     * @param owner 主窗口
     * @param pageKey {@link #destinations()} 中的平台页面 key
     */
    public void show(Window owner, String pageKey) {
        if (stage == null) {
            createStage(Objects.requireNonNull(owner, "owner"));
        }
        if (pageKey != null) {
            select(destination(pageKey));
        }
        if (stage.isShowing()) {
            stage.toFront();
            stage.requestFocus();
            return;
        }
        scope.activate();
        activatePage();
        stage.show();
        stage.toFront();
    }

    /**
     * 打开统一模型设置页并开始新增；离页受阻或既有草稿尚未完成时保留原页面与草稿。
     *
     * @param owner 主窗口
     */
    public void showProviderCreation(Window owner) {
        show(owner, "providers");
        if (selected != null
                && selected.key().equals("providers")
                && activePage instanceof ProviderSettingsPage providerPage
                && !activePage.dirty()) {
            // 首次目录读取由页面排队新增意图；真正的保存中、结果未知等状态仍由页面拒绝覆盖。
            providerPage.beginCreate();
        }
    }

    private static Destination destination(String pageKey) {
        String checked = Objects.requireNonNull(pageKey, "pageKey").strip();
        return DESTINATIONS.stream()
                .filter(candidate -> candidate.key().equals(checked))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知设置页面: " + checked));
    }

    /**
     * 返回窗口是否正在显示。
     *
     * @return 显示状态
     */
    public boolean isShowing() {
        return stage != null && stage.isShowing();
    }

    /** 关闭窗口；操作未完成或存在未保存草稿时保留窗口并显示统一离页提示。 */
    public void close() {
        if (!mayLeavePage()) {
            return;
        }
        if (stage != null) {
            stage.hide();
        }
    }

    /** 释放页面订阅并销毁窗口；仅由 Desktop 进程关闭调用。 */
    public void dispose() {
        if (pendingNotice != null) {
            pendingNotice.close();
        }
        configurationSubscription.close();
        deactivatePage();
        if (pages != null) {
            pages.dispose();
        }
        pageScrollers.clear();
        scope = null;
        if (stage != null) {
            stage.hide();
        }
        activePage = null;
    }

    private void returnToChat() {
        if (stage == null || !mayLeavePage()) {
            return;
        }
        Window owner = stage.getOwner();
        // 成功应用模型并通过离页保护后隐藏窗口；再次打开时按原独立作用域恢复有效缓存。
        stage.hide();
        if (owner instanceof Stage mainStage) {
            mainStage.toFront();
        }
        if (owner != null) {
            owner.requestFocus();
        }
    }

    private void createStage(Window owner) {
        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.NONE);
        stage.setTitle("JavaClaw 设置与管理中心");
        stage.setMinWidth(MINIMUM_WIDTH);
        stage.setMinHeight(MINIMUM_HEIGHT);
        shell = new ManagementPageShell("设置与管理");
        shell.setNavigationContent(createNavigation());
        pages = new SettingsPageRegistry(appearance, gateways, this::close, this::returnToChat);
        scope = new ManagementScopeSession(
                gateways.core(), gateways.preferredWorkspace(), () -> activePage, this::scopeAvailabilityChanged);
        shell.setScopeContent(scope.content());
        Scene scene = new Scene(shell, 1_040, 720);
        DesktopStylesheets.apply(scene);
        appearance.register(scene);
        installShortcut(scene);
        stage.setScene(scene);
        ManagementWindowPreferences restored = preferences.load();
        restoreBounds(restored.bounds());
        stage.setOnCloseRequest(event -> {
            if (!mayLeavePage()) {
                event.consume();
            }
        });
        stage.setOnHidden(event -> {
            if (scope != null) {
                scope.deactivate();
            }
            deactivatePage();
            savePreferences();
        });
        stage.focusedProperty().addListener((observable, previous, focused) -> {
            if (focused) {
                scope.activate();
            }
        });
        select(restoreDestination(restored.lastPageKey()));
    }

    private VBox createNavigation() {
        TextField search = new TextField();
        search.setPromptText("搜索设置与管理功能");
        search.setAccessibleText("搜索设置与管理功能");
        search.getStyleClass().add("settings-search-field");
        navigation = new ListView<>(filtered);
        navigation.setAccessibleText("设置与管理页面");
        navigation.setCellFactory(ignored -> new DestinationCell());
        navigation.getStyleClass().addAll("platform-navigation-list", "management-navigation-list");
        navigation
                .getSelectionModel()
                .selectedItemProperty()
                .addListener((observable, previous, value) -> select(value));
        search.textProperty().addListener((observable, previous, value) -> filter(value));
        VBox box = new VBox(10, search, navigation);
        VBox.setVgrow(navigation, Priority.ALWAYS);
        box.getStyleClass().add("management-navigation-content");
        filter("");
        return box;
    }

    private void filter(String query) {
        String normalized = Objects.requireNonNullElse(query, "").strip().toLowerCase(Locale.ROOT);
        restoringSelection = true;
        try {
            // 排序只改变候选展示；屏蔽 ListView 替换目录时的中间选择，避免误切页或触发草稿离页提示。
            filtered.setAll(DESTINATIONS.stream()
                    .filter(destination -> destination.searchText().contains(normalized))
                    .sorted(Comparator.comparingInt(destination -> destination.searchRank(normalized)))
                    .toList());
        } finally {
            restoringSelection = false;
        }
        if (pages == null
                || filtered.isEmpty()
                || (activePage != null && (activePage.pending() || activePage.dirty()))) {
            restoreSelection();
            return;
        }
        Destination target = filtered.stream()
                .filter(destination ->
                        destination.title().toLowerCase(Locale.ROOT).equals(normalized))
                .findFirst()
                .orElse(filtered.contains(selected) ? selected : filtered.getFirst());
        select(target);
        restoreSelection();
    }

    private void select(Destination destination) {
        if (restoringSelection || destination == null || destination.equals(selected)) {
            return;
        }
        if (!mayLeavePage()) {
            restoreSelection();
            return;
        }
        deactivatePage();
        selected = destination;
        navigation.getSelectionModel().select(destination);
        activePage = pages.resolve(destination.key());
        scope.bind(activePage);
        Node viewport = activePage.ownsViewport()
                ? activePage.content()
                : pageScrollers.computeIfAbsent(destination.key(), ignored -> scroll(activePage.content()));
        shell.showPage(destination.title(), viewport);
        shell.setActionContent(activePage.actionContent());
        updatePageInteraction();
        if (stage.isShowing()) {
            activatePage();
        }
    }

    private boolean mayLeavePage() {
        if (activePage == null) {
            return true;
        }
        // 未确认写入必须优先于脏草稿检查，不能让离页提示覆盖页面的在途或回执恢复状态。
        if (activePage.pending()) {
            showPendingNotice();
            return false;
        }
        if (activePage.dirty()) {
            activePage.warnUnsavedChanges();
            return false;
        }
        return true;
    }

    private void showPendingNotice() {
        if (pendingNotice != null && pendingNotice.isShowing()) {
            return;
        }
        pendingNotice = new Alert(Alert.AlertType.INFORMATION, "请等待当前操作完成；保存结果尚未确认时，请先在当前页面查询结果。", ButtonType.OK);
        pendingNotice.setTitle("操作尚未完成");
        pendingNotice.setHeaderText("暂时无法离开当前页面");
        PlatformDialogs.style(pendingNotice, stage);
        pendingNotice.show();
    }

    private void scopeAvailabilityChanged(boolean available) {
        scopeWriteAvailable = available;
        updatePageInteraction();
    }

    private void updatePageInteraction() {
        if (shell == null || selected == null) {
            return;
        }
        boolean workspaceScoped = workspaceScopeRequired(selected.key());
        shell.setPageInteractionEnabled(!workspaceScoped || scopeWriteAvailable);
    }

    static boolean workspaceScopeRequired(String pageKey) {
        return WORKSPACE_SCOPED_PAGES.contains(Objects.requireNonNull(pageKey, "pageKey"));
    }

    private void activatePage() {
        if (activePage == null || activePageActivated) {
            return;
        }
        activePageActivated = true;
        try {
            activePage.activate();
        } catch (RuntimeException | Error failure) {
            activePageActivated = false;
            throw failure;
        }
    }

    private void deactivatePage() {
        if (activePage == null || !activePageActivated) {
            return;
        }
        activePage.deactivate();
        activePageActivated = false;
    }

    private Destination restoreDestination(String pageKey) {
        return DESTINATIONS.stream()
                .filter(candidate -> candidate.key().equals(pageKey))
                .findFirst()
                .orElse(DESTINATIONS.getFirst());
    }

    private void restoreBounds(java.util.Optional<ManagementWindowPreferences.WindowBounds> savedBounds) {
        if (savedBounds.isEmpty() || !intersectsVisibleScreen(savedBounds.orElseThrow())) {
            stage.setWidth(1_040);
            stage.setHeight(720);
            stage.centerOnScreen();
            return;
        }
        ManagementWindowPreferences.WindowBounds bounds = savedBounds.orElseThrow();
        stage.setX(bounds.x());
        stage.setY(bounds.y());
        stage.setWidth(bounds.width());
        stage.setHeight(bounds.height());
    }

    private static boolean intersectsVisibleScreen(ManagementWindowPreferences.WindowBounds bounds) {
        return Screen.getScreens().stream()
                .map(Screen::getVisualBounds)
                .anyMatch(screen -> bounds.x() + bounds.width() > screen.getMinX()
                        && bounds.y() + bounds.height() > screen.getMinY()
                        && bounds.x() < screen.getMaxX()
                        && bounds.y() < screen.getMaxY());
    }

    private void savePreferences() {
        Destination current = selected == null ? DESTINATIONS.getFirst() : selected;
        preferences.save(new ManagementWindowPreferences(
                current.key(),
                java.util.Optional.of(new ManagementWindowPreferences.WindowBounds(
                        stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()))));
    }

    private void restoreSelection() {
        restoringSelection = true;
        try {
            navigation.getSelectionModel().select(selected);
        } finally {
            restoringSelection = false;
        }
    }

    private static ScrollPane scroll(javafx.scene.Node page) {
        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().addAll("settings-scroll-pane", "platform-content-scroll");
        return scroll;
    }

    private static List<Destination> destinations() {
        return List.of(
                new Destination("appearance", "常规", "外观", "主题、字号与界面密度"),
                new Destination("providers", "模型与智能体", "模型服务", "配置模型接口、密钥和可用模型"),
                new Destination("roles", "模型与智能体", "Agent Studio", "编辑角色指令、能力收窄和可选模型偏好"),
                new Destination("learning", "模型与智能体", "学习策略", "设置记忆学习和技能建议规则"),
                new Destination("permissions", "安全与连接", "权限方案", "设置文件、网络、审批和资源上限"),
                new Destination("vault", "安全与连接", "密钥库", "管理主密钥、锁定状态和凭据记录"),
                new Destination("unattended-grants", "安全与连接", "无人值守授权", "限制定时任务可调用的工具和额度"),
                new Destination("network-grants", "安全与连接", "私网授权", "管理临时、精确、可撤销的访问地址"),
                new Destination("mcp", "安全与连接", "MCP 外部工具", "管理连接、OAuth、工具目录和健康状态"),
                new Destination("site", "安全与连接", "网站会话", "管理受控网站、登录会话和凭据"),
                new Destination("builtins", "扩展", "内置扩展", "可选内置能力与运行状态"),
                new Destination("bundles", "扩展", "第三方扩展", "安装、升级、隔离、启用或停用扩展包"),
                new Destination("trust", "扩展", "信任公钥", "签名公钥、指纹和撤销"),
                new Destination("trash", "扩展", "扩展回收站", "恢复或永久清除已卸载的扩展"),
                new Destination("workspace", "工作区", "工作区", "设置默认 Agent、模型、权限和归档"),
                new Destination("coding", "工作区", "编程环境", "托管工具链、依赖准备和安装进度"),
                new Destination("instructions", "工作区", "项目约定", "AGENTS 层级、摘要与冻结状态"),
                new Destination("worktrees", "工作区", "隔离工作区恢复", "管理补丁、备份和清理"),
                new Destination("jobs", "功能管理", "后台任务", "查看工作单元、检查点和恢复操作"),
                new Destination("plan", "功能管理", "计划", "结构化计划、决策与执行"),
                new Destination("loop", "功能管理", "循环任务", "迭代目标、验证和停止条件"),
                new Destination("workflow", "功能管理", "工作流", "安全编排和持久执行"),
                new Destination("sdd", "功能管理", "规格驱动开发（SDD）", "管理规格、审批、实现和验收"),
                new Destination("schedule", "功能管理", "定时任务", "管理触发规则、执行记录和失败恢复"),
                new Destination("memory", "功能管理", "记忆", "管理记忆来源、历史和建议"),
                new Destination("knowledge", "功能管理", "知识库", "管理资料、索引版本和检索"),
                new Destination("skill", "功能管理", "技能", "管理草稿、发布、目录和资源"),
                new Destination("connection", "系统", "服务连接", "管理 JavaClaw 服务的连接、重试和启动"),
                new Destination("lifecycle", "系统", "运行与启动", "管理运行保活、开机启动和托盘"),
                new Destination("diagnostics", "系统", "诊断", "查看脱敏状态、复制和导出"));
    }

    private record Destination(String key, String group, String title, String description) {
        private Destination {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(description, "description");
        }

        private String searchText() {
            return (group + " " + title + " " + description).toLowerCase(Locale.ROOT);
        }

        private int searchRank(String query) {
            String normalizedTitle = title.toLowerCase(Locale.ROOT);
            if (query.isEmpty() || normalizedTitle.equals(query)) {
                return 0;
            }
            if (normalizedTitle.startsWith(query)) {
                return 1;
            }
            return normalizedTitle.contains(query) ? 2 : 3;
        }
    }

    private final class DestinationCell extends ListCell<Destination> {
        private final Label group = new Label();
        private final Label title = new Label();
        private final Label detail = new Label();
        private final VBox item = new VBox(3, title, detail);
        private final VBox content = new VBox(3, group, item);

        private DestinationCell() {
            // VirtualFlow 负责把 Cell 扩到视口宽度；零首选宽度避免说明文本的单行宽度触发横向滚动条。
            setPrefWidth(0);
            group.getStyleClass().add("modal-nav-group");
            title.getStyleClass().add("platform-detail-title");
            detail.setWrapText(true);
            detail.getStyleClass().add("platform-detail-text");
            item.getStyleClass().add("platform-detail-cell");
        }

        @Override
        protected void updateItem(Destination destination, boolean empty) {
            super.updateItem(destination, empty);
            if (empty || destination == null) {
                setGraphic(null);
                setAccessibleText(null);
                return;
            }
            group.setText(destination.group());
            int index = getIndex();
            boolean firstInGroup = index <= 0
                    || index >= navigation.getItems().size()
                    || !navigation.getItems().get(index - 1).group().equals(destination.group());
            group.setVisible(firstInGroup);
            group.setManaged(firstInGroup);
            title.setText(destination.title());
            detail.setText(destination.description());
            setGraphic(content);
            setAccessibleText(destination.group() + "，" + destination.title() + "。" + destination.description());
        }
    }
}
