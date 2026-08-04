package com.javaclaw.ui.javafx.mcp;

import com.javaclaw.mcp.*;

import com.javaclaw.app.UIHelper;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP 服务器设置面板（v2 — 状态可见 + 热启停 + 测试连接）
 *
 * <p>可选注入 {@link McpClientManager}：
 * <ul>
 *   <li>注入：每张服务器卡片显示运行状态徽章、工具数、运行时长，
 *       支持「启动 / 重启 / 停止 / 测试连接」热操作，启动失败时可查看 stderr。</li>
 *   <li>未注入：退化为基础 CRUD（与旧版本兼容），改动需重启应用生效。</li>
 * </ul>
 *
 * @author JavaClaw
 */
public class McpSettingsView {

    private static final Logger log = LoggerFactory.getLogger(McpSettingsView.class);

    private final McpConfigManager configManager;

    /** 可选：用于运行时状态查询和热操作；为 null 时面板退化为基础 CRUD */
    private McpClientManager mcpClientManager;
    private Runnable managerStateListener;

    private VBox serverListBox;
    private Label statusLabel;
    private TextField searchField;
    private Label contentTitle;
    private Label resultCountLabel;
    private final Map<String, Button> filterButtons = new LinkedHashMap<>();
    private String activeFilter = "all";
    private boolean standaloneMode;
    private javafx.stage.Stage window;
    /** 操作反馈自动消失计时器：连续操作时重启同一个计时器而非叠加多个 */
    private javafx.animation.PauseTransition statusClearTimer;
    /** 概览条容器：mono 文本 + 可选红色失败提示，整条带面板底色圆角 */
    private HBox overviewBar;
    private Label overviewText;
    private Label overviewFail;
    private Runnable onConfigChanged;

    public McpSettingsView() {
        this.configManager = McpConfigManager.getInstance();
    }

    /**
     * 设置配置变更回调（添加/删除服务器时触发，用于让外部重建 agent toolkit）
     */
    public void setOnConfigChanged(Runnable callback) {
        this.onConfigChanged = callback;
    }

    /**
     * 注入运行时管理器，启用状态可见性 / 热启停 / 测试连接。
     * 多次调用安全：会先解绑上一个监听器。
     */
    public void setMcpClientManager(McpClientManager manager) {
        // 解绑旧监听器
        if (this.mcpClientManager != null && this.managerStateListener != null) {
            this.mcpClientManager.removeStateListener(this.managerStateListener);
        }
        this.mcpClientManager = manager;
        if (manager != null) {
            this.managerStateListener = () -> Platform.runLater(this::refreshServerList);
            manager.addStateListener(this.managerStateListener);
        }
    }

    public Node buildPanel() {
        return buildPanel(false);
    }

    /**
     * 构建 MCP 主内容区。独立窗口与设置页复用相同的卡片、筛选和操作逻辑，
     * 仅在独立窗口中把创建/导入入口移到左侧导航栏。
     */
    private Node buildPanel(boolean standalone) {
        standaloneMode = standalone;

        Label sectionTitle = new Label(standalone ? "全部服务器" : "MCP 服务器");
        sectionTitle.getStyleClass().add("sec-title");
        contentTitle = sectionTitle;

        Label description = new Label("管理 MCP (Model Context Protocol) 服务器，连接外部工具和数据源。");
        description.getStyleClass().add("sec-hint");
        description.setWrapText(true);

        // 概览条：面板底色圆角条，mono 文本 + 可选红色失败提示
        overviewText = new Label();
        overviewText.getStyleClass().add("mcp-overview-text");
        overviewText.setWrapText(true);
        overviewText.setMinWidth(Region.USE_PREF_SIZE);
        overviewFail = new Label();
        overviewFail.getStyleClass().add("mcp-overview-fail");
        overviewFail.setWrapText(true);
        overviewFail.setMinWidth(0);
        overviewFail.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(overviewFail, Priority.ALWAYS);
        overviewBar = new HBox(8, overviewText, overviewFail);
        overviewBar.setAlignment(Pos.CENTER_LEFT);
        overviewBar.getStyleClass().add("mcp-overview-bar");

        // 服务器列表
        serverListBox = new VBox(10);

        // 搜索与刷新在两种承载形态中保持一致。
        searchField = new TextField();
        searchField.setPromptText("⌕  搜索名称、命令或 URL…");
        searchField.getStyleClass().addAll("settings-field", "mcp-search-field");
        searchField.textProperty().addListener((obs, oldValue, newValue) -> refreshServerList());
        HBox.setHgrow(searchField, Priority.ALWAYS);

        resultCountLabel = new Label();
        resultCountLabel.getStyleClass().add("mcp-result-count");

        Button refreshButton = new Button("↻ 刷新");
        refreshButton.getStyleClass().addAll("jc-btn", "jc-btn-ghost", "jc-btn-sm");
        refreshButton.setTooltip(new Tooltip("刷新服务器运行状态和工具数量"));
        refreshButton.setOnAction(e -> {
            refreshServerList();
            setStatus("服务器状态已刷新");
        });

        HBox toolbar = new HBox(10, searchField, resultCountLabel, refreshButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("mcp-toolbar");

        statusLabel = new Label();
        statusLabel.getStyleClass().addAll("settings-status", "mcp-status-label");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(Double.MAX_VALUE);

        FlowPane addBar = buildCreateActions();

        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.getStyleClass().add("settings-scroll-pane");

        VBox panel = new VBox(14);
        panel.getStyleClass().add("mcp-content-panel");
        panel.getChildren().addAll(sectionTitle, description, overviewBar, toolbar);
        if (!standalone) {
            panel.getChildren().add(addBar);
        }
        panel.getChildren().add(serverListBox);
        if (!standalone) {
            Label pathHint = new Label("配置位置: " + configManager.getConfigFilePath());
            pathHint.getStyleClass().add("mcp-path-hint");
            pathHint.setWrapText(true);
            panel.getChildren().addAll(pathHint, statusLabel);
        }
        panel.setPadding(standalone
                ? new Insets(22, 26, 22, 26)
                : new Insets(4));

        scrollPane.setContent(panel);
        attachMcpStyles(scrollPane);

        refreshServerList();

        return scrollPane;
    }

    /**
     * 以与技能中心、插件中心一致的独立中心窗口展示 MCP 服务。
     */
    public void showAsWindow(javafx.stage.Stage owner) {
        window = new javafx.stage.Stage();
        window.initOwner(owner);
        window.initModality(javafx.stage.Modality.WINDOW_MODAL);
        window.setTitle("MCP 服务器");
        window.setResizable(true);
        window.setMinWidth(780);
        window.setMinHeight(560);

        VBox leftPane = buildWindowSidebar();
        Node panelScroll = buildPanel(true);
        VBox.setVgrow(panelScroll, Priority.ALWAYS);

        Button closeButton = new Button("关闭");
        closeButton.getStyleClass().addAll("jc-btn", "jc-btn-ghost");
        closeButton.setOnAction(e -> window.close());

        Region footSpacer = new Region();
        HBox.setHgrow(footSpacer, Priority.ALWAYS);

        HBox foot = new HBox(10, statusLabel, footSpacer, closeButton);
        foot.getStyleClass().add("modal-foot");
        foot.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(statusLabel, Priority.ALWAYS);

        VBox rightPane = new VBox(panelScroll, foot);
        VBox.setVgrow(panelScroll, Priority.ALWAYS);
        HBox.setHgrow(rightPane, Priority.ALWAYS);

        HBox root = new HBox(leftPane, rightPane);
        root.getStyleClass().addAll("settings-root", "mcp-window-root");

        javafx.scene.Scene scene = new javafx.scene.Scene(root, 960, 680);
        addStylesheet(scene, "/css/chat.css");
        addStylesheet(scene, "/css/mcp-settings.css");

        scene.getAccelerators().put(
                new javafx.scene.input.KeyCodeCombination(javafx.scene.input.KeyCode.ESCAPE),
                window::close);

        window.setScene(scene);
        window.setOnHidden(e -> dispose());
        window.show();
    }

    /**
     * 刷新服务器列表 + 顶部总览栏
     */
    private void refreshServerList() {
        if (serverListBox == null) return;
        serverListBox.getChildren().clear();
        List<McpServerConfig> servers = configManager.getAllServers();

        refreshOverview(servers);
        refreshFilterButtons(servers);
        refreshContentTitle();

        List<McpServerConfig> visibleServers = servers.stream()
                .filter(this::matchesActiveFilter)
                .filter(this::matchesSearch)
                .toList();
        if (resultCountLabel != null) {
            resultCountLabel.setText(visibleServers.size() == servers.size()
                    ? servers.size() + " 个"
                    : visibleServers.size() + " / " + servers.size() + " 个");
        }

        if (visibleServers.isEmpty()) {
            serverListBox.getChildren().add(buildEmptyState(servers.isEmpty()));
            return;
        }

        for (McpServerConfig server : visibleServers) {
            serverListBox.getChildren().add(buildServerCard(server));
        }
    }

    private FlowPane buildCreateActions() {
        Button addButton = new Button("＋ 添加服务器");
        addButton.getStyleClass().addAll("jc-btn", "jc-btn-primary", "jc-btn-sm");
        addButton.setOnAction(e -> showAddServerDialog());

        Button templateButton = new Button("从模板添加");
        templateButton.getStyleClass().addAll("jc-btn", "jc-btn-soft", "jc-btn-sm");
        templateButton.setOnAction(e -> showTemplateDialog());

        Button pasteJsonButton = new Button("导入 JSON");
        pasteJsonButton.getStyleClass().addAll("jc-btn", "jc-btn-ghost", "jc-btn-sm");
        pasteJsonButton.setOnAction(e -> showPasteJsonDialog());

        FlowPane actions = new FlowPane(8, 8, addButton, templateButton, pasteJsonButton);
        actions.setAlignment(Pos.CENTER_LEFT);
        return actions;
    }

    private VBox buildWindowSidebar() {
        Label title = new Label("MCP 服务器");
        title.getStyleClass().add("modal-left-title");

        Label hint = new Label("外部工具与数据源");
        hint.getStyleClass().add("mcp-sidebar-hint");

        VBox heading = new VBox(3, title, hint);
        heading.setPadding(new Insets(18, 16, 12, 16));

        VBox filters = new VBox(2);
        filters.setPadding(new Insets(0, 10, 10, 10));
        filters.getChildren().addAll(
                createFilterButton("all", "全部服务器"),
                createFilterButton("running", "运行中"),
                createFilterButton("failed", "需要处理"),
                createFilterButton("stopped", "已停止"));

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        VBox createActions = buildSidebarCreateActions();

        Label locationTitle = new Label("配置位置");
        locationTitle.getStyleClass().add("mcp-sidebar-section");
        Label path = new Label(configManager.getConfigFilePath());
        path.getStyleClass().add("mcp-sidebar-path");
        path.setWrapText(true);
        path.setTooltip(new Tooltip(configManager.getConfigFilePath()));

        Button copyPath = new Button("复制路径");
        copyPath.getStyleClass().addAll("jc-btn", "jc-btn-ghost", "jc-btn-sm");
        copyPath.setMaxWidth(Double.MAX_VALUE);
        copyPath.setOnAction(e -> {
            copyText(configManager.getConfigFilePath());
            setStatus("配置位置已复制");
        });

        VBox bottom = new VBox(10, createActions, locationTitle, path, copyPath);
        bottom.getStyleClass().add("mcp-sidebar-bottom");

        VBox sidebar = new VBox(heading, filters, spacer, bottom);
        sidebar.getStyleClass().add("modal-left-pane");
        sidebar.setPrefWidth(224);
        sidebar.setMinWidth(204);
        return sidebar;
    }

    private VBox buildSidebarCreateActions() {
        Button add = new Button("＋ 添加服务器");
        add.getStyleClass().addAll("jc-btn", "jc-btn-primary", "jc-btn-sm");
        add.setMaxWidth(Double.MAX_VALUE);
        add.setOnAction(e -> showAddServerDialog());

        Button template = new Button("从模板添加");
        template.getStyleClass().addAll("jc-btn", "jc-btn-soft", "jc-btn-sm");
        template.setMaxWidth(Double.MAX_VALUE);
        template.setOnAction(e -> showTemplateDialog());

        Button importJson = new Button("导入 JSON");
        importJson.getStyleClass().addAll("jc-btn", "jc-btn-ghost", "jc-btn-sm");
        importJson.setMaxWidth(Double.MAX_VALUE);
        importJson.setOnAction(e -> showPasteJsonDialog());

        return new VBox(6, add, template, importJson);
    }

    private Button createFilterButton(String id, String label) {
        Button button = new Button(label);
        button.setUserData(label);
        button.setMaxWidth(Double.MAX_VALUE);
        button.getStyleClass().add("modal-nav-btn");
        button.setOnAction(e -> {
            activeFilter = id;
            refreshServerList();
        });
        filterButtons.put(id, button);
        return button;
    }

    private void refreshFilterButtons(List<McpServerConfig> servers) {
        if (filterButtons.isEmpty()) return;
        int running = 0;
        int failed = 0;
        int stopped = 0;
        for (McpServerConfig server : servers) {
            McpClient.ServerState state = serverState(server);
            if (state == McpClient.ServerState.RUNNING
                    || state == McpClient.ServerState.STARTING) {
                running++;
            } else if (state == McpClient.ServerState.FAILED) {
                failed++;
            } else {
                stopped++;
            }
        }
        Map<String, Integer> counts = Map.of(
                "all", servers.size(),
                "running", running,
                "failed", failed,
                "stopped", stopped);
        filterButtons.forEach((id, button) -> {
            button.setText(button.getUserData() + "  " + counts.getOrDefault(id, 0));
            button.getStyleClass().remove("modal-nav-btn-selected");
            if (id.equals(activeFilter)) {
                button.getStyleClass().add("modal-nav-btn-selected");
            }
        });
    }

    private void refreshContentTitle() {
        if (contentTitle == null) return;
        contentTitle.setText(switch (activeFilter) {
            case "running" -> "运行中的服务器";
            case "failed" -> "需要处理";
            case "stopped" -> "已停止的服务器";
            default -> standaloneMode ? "全部服务器" : "MCP 服务器";
        });
    }

    private boolean matchesActiveFilter(McpServerConfig server) {
        McpClient.ServerState state = serverState(server);
        return switch (activeFilter) {
            case "running" -> state == McpClient.ServerState.RUNNING
                    || state == McpClient.ServerState.STARTING;
            case "failed" -> state == McpClient.ServerState.FAILED;
            case "stopped" -> state == McpClient.ServerState.STOPPED;
            default -> true;
        };
    }

    private boolean matchesSearch(McpServerConfig server) {
        if (searchField == null || searchField.getText() == null
                || searchField.getText().isBlank()) {
            return true;
        }
        String query = searchField.getText().trim().toLowerCase(java.util.Locale.ROOT);
        String target = String.join(" ",
                safe(server.getName()),
                safe(server.getCommand()),
                safe(server.getUrl()),
                safe(server.getTransport()),
                String.join(" ", server.getArgs()))
                .toLowerCase(java.util.Locale.ROOT);
        return target.contains(query);
    }

    private McpClient.ServerState serverState(McpServerConfig server) {
        if (mcpClientManager == null) {
            return McpClient.ServerState.STOPPED;
        }
        return mcpClientManager.getServerStatus(server.getName()).state();
    }

    private Node buildEmptyState(boolean noServersConfigured) {
        Label icon = new Label(noServersConfigured ? "⌁" : "⌕");
        icon.getStyleClass().add("empty-state-icon");

        Label title = new Label(noServersConfigured ? "还没有 MCP 服务器" : "没有匹配的服务器");
        title.getStyleClass().add("empty-state-text");

        Label hint = new Label(noServersConfigured
                ? "添加本地 stdio 或远程 HTTP 服务，让智能体使用外部工具和数据。"
                : "尝试更换筛选条件或清空搜索关键词。");
        hint.getStyleClass().addAll("sec-hint", "empty-state-hint");
        hint.setWrapText(true);
        hint.setMaxWidth(420);

        VBox empty = new VBox(10, icon, title, hint);
        empty.setAlignment(Pos.CENTER);
        empty.getStyleClass().add("mcp-empty-state");

        if (noServersConfigured) {
            FlowPane actions = buildCreateActions();
            actions.setAlignment(Pos.CENTER);
            empty.getChildren().add(actions);
        } else if (searchField != null && !searchField.getText().isBlank()) {
            Button clear = new Button("清空搜索");
            clear.getStyleClass().addAll("jc-btn", "jc-btn-soft", "jc-btn-sm");
            clear.setOnAction(e -> searchField.clear());
            empty.getChildren().add(clear);
        }
        return empty;
    }

    /**
     * 顶部总览：运行 X/Y 个服务器、共 N 个工具、最近一次失败摘要
     */
    private void refreshOverview(List<McpServerConfig> servers) {
        if (overviewBar == null) return;
        if (mcpClientManager == null) {
            overviewBar.setManaged(false);
            overviewBar.setVisible(false);
            return;
        }
        overviewBar.setManaged(true);
        overviewBar.setVisible(true);

        int enabled = 0;
        int running = 0;
        int totalTools = 0;
        String firstFailure = null;
        for (McpServerConfig s : servers) {
            if (s.isEnabled()) enabled++;
            McpClientManager.ServerStatus st = mcpClientManager.getServerStatus(s.getName());
            if (st.state() == McpClient.ServerState.RUNNING) {
                running++;
                totalTools += st.toolCount();
            } else if (st.state() == McpClient.ServerState.FAILED && firstFailure == null) {
                firstFailure = s.getName() + ": "
                        + (st.startupError() != null ? st.startupError() : "启动失败");
            }
        }
        overviewText.setText("运行中 " + running + "/" + enabled + " 服务器 · 共 "
                + totalTools + " 个工具");

        // 失败提示：有失败服务器时以红色 mono 追加，否则隐藏
        if (firstFailure != null) {
            overviewFail.setText("⚠ " + firstFailure);
            overviewFail.setManaged(true);
            overviewFail.setVisible(true);
        } else {
            overviewFail.setText("");
            overviewFail.setManaged(false);
            overviewFail.setVisible(false);
        }
    }

    /**
     * 单个服务器卡片：状态徽章 + 名称 + 元信息 + 操作组 + 可展开工具列表
     */
    private Node buildServerCard(McpServerConfig server) {
        VBox card = new VBox(8);
        card.getStyleClass().add("mcp-server-card");

        McpClientManager.ServerStatus status = mcpClientManager != null
                ? mcpClientManager.getServerStatus(server.getName())
                : new McpClientManager.ServerStatus(McpClient.ServerState.STOPPED, 0, null, 0L);

        // ====== 首行：状态、名称与传输类型。操作区独立成行，避免窄窗口挤压。 ======
        Label badge = buildStateBadge(server, status);

        Label nameLabel = new Label(server.getName());
        nameLabel.getStyleClass().add("mcp-server-name");
        nameLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        nameLabel.setMaxWidth(Double.MAX_VALUE);

        Label metaLabel = new Label(buildMetaText(server, status));
        metaLabel.getStyleClass().add("mcp-server-meta");
        metaLabel.setManaged(!metaLabel.getText().isBlank());
        metaLabel.setVisible(!metaLabel.getText().isBlank());

        VBox identity = new VBox(2, nameLabel, metaLabel);
        identity.setMinWidth(0);
        identity.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(identity, Priority.ALWAYS);

        Label transportPill = new Label(
                "http".equals(server.getTransport()) ? "http" : "stdio");
        transportPill.getStyleClass().add("mcp-transport-pill");

        HBox headerRow = new HBox(10, badge, identity, transportPill);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        card.getChildren().add(headerRow);

        // ====== 命令 / URL 摘要 + 显式复制入口 ======
        Label summaryLabel = new Label();
        summaryLabel.getStyleClass().add("mcp-server-summary");
        summaryLabel.setWrapText(true);
        summaryLabel.setMinWidth(0);
        summaryLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(summaryLabel, Priority.ALWAYS);
        String copyValue;
        if ("http".equals(server.getTransport())) {
            copyValue = safe(server.getUrl());
            summaryLabel.setText("HTTP: " + copyValue);
        } else {
            String cmdText = (server.getCommand() == null ? "" : server.getCommand())
                    + " " + String.join(" ", server.getArgs());
            copyValue = cmdText.trim();
            summaryLabel.setText("命令: " + copyValue);
        }
        Button copyButton = new Button("复制");
        copyButton.getStyleClass().addAll("jc-btn", "jc-btn-ghost", "jc-btn-sm", "mcp-copy-button");
        copyButton.setTooltip(new Tooltip("复制" + ("http".equals(server.getTransport()) ? " URL" : "启动命令")));
        copyButton.setOnAction(e -> {
            copyText(copyValue);
            setStatus(("http".equals(server.getTransport()) ? "URL" : "启动命令") + "已复制");
        });
        HBox summaryRow = new HBox(8, summaryLabel, copyButton);
        summaryRow.setAlignment(Pos.CENTER_LEFT);
        summaryRow.getStyleClass().add("mcp-summary-row");
        card.getChildren().add(summaryRow);

        // ====== 环境变量（stdio）/ HTTP Headers（http） ======
        Map<String, String> kv = "http".equals(server.getTransport())
                ? server.getHeaders()
                : server.getEnv();
        String kvLabelPrefix = "http".equals(server.getTransport()) ? "Headers: " : "环境变量: ";
        if (kv != null && !kv.isEmpty()) {
            String text = kv.entrySet().stream()
                    .map(e -> e.getKey() + "=" + maskValue(e.getValue()))
                    .collect(Collectors.joining(", "));
            Label kvLabel = new Label(kvLabelPrefix + text);
            kvLabel.getStyleClass().add("mcp-server-kv");
            kvLabel.setWrapText(true);
            card.getChildren().add(kvLabel);
        }

        // ====== 失败时：红色 ⚠ 错误信息 + 「查看日志」按钮 ======
        if (status.state() == McpClient.ServerState.FAILED) {
            HBox errRow = new HBox(8);
            errRow.setAlignment(Pos.CENTER_LEFT);
            Label errLabel = new Label("⚠ " + (status.startupError() != null
                    ? status.startupError() : "启动失败"));
            errLabel.getStyleClass().add("mcp-error-text");
            errLabel.setWrapText(true);
            errLabel.setMinWidth(0);
            errLabel.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(errLabel, Priority.ALWAYS);
            Button viewLogBtn = new Button("查看日志");
            viewLogBtn.getStyleClass().addAll("jc-btn", "jc-btn-ghost", "jc-btn-sm");
            viewLogBtn.setOnAction(e -> showStderrDialog(server.getName()));
            errRow.getChildren().addAll(errLabel, viewLogBtn);
            card.getChildren().add(errRow);
        }

        FlowPane actionBar = buildActionBar(server, status);
        actionBar.getStyleClass().add("mcp-card-actions");
        card.getChildren().add(actionBar);

        // ====== 可展开的工具浏览器（任何状态都展示，让用户始终能折叠查看/触发发现） ======
        if (mcpClientManager != null) {
            card.getChildren().add(buildToolsPane(server, status));
        }

        return card;
    }

    /**
     * 工具浏览器折叠面板：
     * - RUNNING & toolCount>0：列出已发现工具
     * - RUNNING & toolCount=0：提示"该服务器未提供工具"
     * - STARTING：提示"正在启动..."
     * - STOPPED/FAILED：内嵌「启动以发现工具」按钮，让用户无需跳到操作栏
     */
    private TitledPane buildToolsPane(McpServerConfig server, McpClientManager.ServerStatus status) {
        TitledPane pane = new TitledPane();
        pane.getStyleClass().add("mcp-tools-pane");
        pane.setExpanded(false);

        switch (status.state()) {
            case RUNNING -> {
                if (status.toolCount() > 0) {
                    pane.setText("已发现 " + status.toolCount() + " 个工具");
                    McpClient client = mcpClientManager.getClient(server.getName());
                    pane.setContent(buildToolsList(client != null ? client.getTools() : List.of()));
                } else {
                    pane.setText("可用工具（0 个）");
                    pane.setContent(buildToolsHint("该服务器已连接，但未声明任何工具。"));
                }
            }
            case STARTING -> {
                pane.setText("可用工具（启动中…）");
                pane.setContent(buildToolsHint("正在启动并发现工具，稍候自动刷新…"));
            }
            case FAILED -> {
                pane.setText("可用工具（启动失败）");
                pane.setContent(buildToolsHint("服务器启动失败，请先排查错误。"));
            }
            case STOPPED -> {
                pane.setText("可用工具（启动后发现）");
                pane.setContent(buildToolsLauncher(server));
            }
        }
        return pane;
    }

    /** 折叠面板里的纯文本提示 */
    private Node buildToolsHint(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("settings-hint");
        l.setWrapText(true);
        VBox box = new VBox(l);
        box.setPadding(new Insets(8, 10, 8, 10));
        return box;
    }

    /** STOPPED 状态时折叠面板里展示的「启动以发现工具」按钮组 */
    private Node buildToolsLauncher(McpServerConfig server) {
        Label hint = new Label("当前未启动。点击下方按钮启动后即可看到该服务器提供的工具列表。");
        hint.getStyleClass().add("settings-hint");
        hint.setWrapText(true);

        Button startBtn = new Button("启动以发现工具");
        startBtn.getStyleClass().addAll("jc-btn", "jc-btn-soft", "jc-btn-sm");
        startBtn.setOnAction(e -> hotStart(server));

        HBox row = new HBox(startBtn);
        row.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(8, hint, row);
        box.setPadding(new Insets(8, 10, 8, 10));
        return box;
    }

    /**
     * 构造状态徽章。颜色：绿（运行中）/灰（已停止/禁用）/橙（启动中）/红（失败）
     */
    private Label buildStateBadge(McpServerConfig server, McpClientManager.ServerStatus status) {
        // 设计稿徽标：jc-badge 基类 + jc-badge-{running/starting/failed/stopped}
        Label badge = new Label();
        badge.getStyleClass().add("jc-badge");

        if (mcpClientManager == null) {
            // 无管理器，只反映 enabled 配置
            if (server.isEnabled()) {
                badge.setText("● 已启用");
                badge.getStyleClass().add("jc-badge-soft");
            } else {
                badge.setText("○ 已禁用");
                badge.getStyleClass().add("jc-badge-stopped");
            }
            return badge;
        }

        switch (status.state()) {
            case RUNNING -> {
                badge.setText("● 运行中");
                badge.getStyleClass().add("jc-badge-running");
            }
            case STARTING -> {
                badge.setText("◐ 启动中");
                badge.getStyleClass().add("jc-badge-starting");
            }
            case FAILED -> {
                badge.setText("● 启动失败");
                badge.getStyleClass().add("jc-badge-failed");
            }
            case STOPPED -> {
                badge.setText(server.isEnabled() ? "○ 已停止" : "○ 已禁用");
                badge.getStyleClass().add("jc-badge-stopped");
            }
        }
        return badge;
    }

    /**
     * 卡片元信息：工具数 · 运行时长
     */
    private String buildMetaText(McpServerConfig server, McpClientManager.ServerStatus status) {
        if (mcpClientManager == null) return "";
        if (status.state() == McpClient.ServerState.RUNNING) {
            String tools = status.toolCount() + " 个工具";
            String upTime = humanizeUptime(status.startedAtMs());
            return upTime.isEmpty() ? "· " + tools : "· " + tools + " · " + upTime;
        }
        return "";
    }

    private String humanizeUptime(long startedAtMs) {
        if (startedAtMs <= 0) return "";
        Duration d = Duration.between(Instant.ofEpochMilli(startedAtMs), Instant.now());
        long sec = Math.max(0, d.getSeconds());
        if (sec < 60) return "运行 " + sec + "s";
        if (sec < 3600) return "运行 " + (sec / 60) + "m";
        if (sec < 86400) return "运行 " + (sec / 3600) + "h";
        return "运行 " + (sec / 86400) + "d";
    }

    /**
     * 构建操作按钮组：启动 / 重启 / 停止 / 编辑 / 删除
     * 无管理器时退化为「启用复选框 + 编辑 + 删除」
     */
    private FlowPane buildActionBar(McpServerConfig server, McpClientManager.ServerStatus status) {
        FlowPane bar = new FlowPane(6, 6);
        bar.setAlignment(Pos.CENTER_LEFT);

        if (mcpClientManager == null) {
            CheckBox enabledCheck = new CheckBox("启用");
            enabledCheck.getStyleClass().add("settings-checkbox");
            enabledCheck.setSelected(server.isEnabled());
            enabledCheck.setOnAction(e -> {
                server.setEnabled(enabledCheck.isSelected());
                configManager.putServer(server);
                setStatus("服务器 " + server.getName() + " 已"
                        + (server.isEnabled() ? "启用" : "禁用") + "（重启生效）");
                refreshServerList();
            });
            bar.getChildren().add(enabledCheck);
        } else {
            // 启动 / 重启 / 停止：根据状态显隐（运行中走 ghost，停止走 soft）
            switch (status.state()) {
                case RUNNING, STARTING -> {
                    Button restartBtn = new Button("重启");
                    restartBtn.getStyleClass().addAll("jc-btn", "jc-btn-ghost", "jc-btn-sm");
                    restartBtn.setOnAction(e -> hotRestart(server));
                    restartBtn.setDisable(status.state() == McpClient.ServerState.STARTING);
                    Button stopBtn = new Button("停止");
                    stopBtn.getStyleClass().addAll("jc-btn", "jc-btn-ghost", "jc-btn-sm");
                    stopBtn.setOnAction(e -> hotStop(server));
                    bar.getChildren().addAll(restartBtn, stopBtn);
                }
                case FAILED, STOPPED -> {
                    Button startBtn = new Button("启动");
                    startBtn.getStyleClass().addAll("jc-btn", "jc-btn-soft", "jc-btn-sm");
                    startBtn.setOnAction(e -> hotStart(server));
                    bar.getChildren().add(startBtn);
                }
            }

            // 启用复选框（控制配置层 enabled，影响下次启动）
            CheckBox enabledCheck = new CheckBox("启用");
            enabledCheck.getStyleClass().add("settings-checkbox");
            enabledCheck.setSelected(server.isEnabled());
            enabledCheck.setOnAction(e -> {
                server.setEnabled(enabledCheck.isSelected());
                configManager.putServer(server);
                if (!enabledCheck.isSelected()
                        && status.state() == McpClient.ServerState.RUNNING) {
                    // 用户禁用一个正在运行的服务器，立即停掉
                    hotStop(server);
                } else {
                    setStatus("服务器 " + server.getName() + " 已"
                            + (server.isEnabled() ? "启用" : "禁用"));
                    refreshServerList();
                }
            });
            bar.getChildren().add(enabledCheck);
        }

        Button editButton = new Button("编辑");
        editButton.getStyleClass().addAll("jc-btn", "jc-btn-ghost", "jc-btn-sm");
        editButton.setOnAction(e -> showEditServerDialog(server));

        Button deleteButton = new Button("删除");
        deleteButton.getStyleClass().addAll("jc-btn", "jc-btn-danger", "jc-btn-sm");
        deleteButton.setOnAction(e -> {
            Alert alert = UIHelper.createConfirmAlert("确认删除",
                    "确定删除 MCP 服务器「" + server.getName() + "」？此操作不会删除服务端数据。",
                    ownerStage());
            alert.showAndWait().ifPresent(bt -> {
                if (bt == ButtonType.OK) {
                    if (mcpClientManager != null) {
                        Thread.ofVirtual()
                                .name("mcp-stop-before-delete-" + server.getName())
                                .start(() -> mcpClientManager.stopServer(server.getName()));
                    }
                    configManager.removeServer(server.getName());
                    refreshServerList();
                    setStatus("已删除服务器: " + server.getName());
                    if (onConfigChanged != null) onConfigChanged.run();
                }
            });
        });

        bar.getChildren().addAll(editButton, deleteButton);
        return bar;
    }

    /**
     * 工具列表（卡片展开时显示）
     */
    private Node buildToolsList(List<McpClient.McpToolInfo> tools) {
        VBox list = new VBox(4);
        list.setPadding(new Insets(8, 0, 4, 0));
        for (McpClient.McpToolInfo tool : tools) {
            VBox row = new VBox(2);
            row.getStyleClass().add("mcp-tool-row");
            Label name = new Label(tool.getName());
            name.getStyleClass().add("mcp-tool-name");
            row.getChildren().add(name);
            if (tool.getDescription() != null && !tool.getDescription().isBlank()) {
                Label desc = new Label(tool.getDescription());
                desc.getStyleClass().add("mcp-tool-desc");
                desc.setWrapText(true);
                row.getChildren().add(desc);
            }
            list.getChildren().add(row);
        }
        return list;
    }

    // ==================== 热操作 ====================

    private void hotStart(McpServerConfig server) {
        if (!server.isEnabled()) {
            server.setEnabled(true);
            configManager.putServer(server);
        }
        setStatusInfo("正在启动 " + server.getName() + " …");
        new Thread(() -> {
            boolean ok = mcpClientManager.startServer(server);
            Platform.runLater(() -> {
                if (ok) {
                    setStatus("服务器 " + server.getName() + " 已启动");
                    if (onConfigChanged != null) onConfigChanged.run();
                } else {
                    setStatusError("启动 " + server.getName() + " 失败，点击「查看日志」排查");
                }
            });
        }, "mcp-hot-start").start();
    }

    private void hotRestart(McpServerConfig server) {
        setStatusInfo("正在重启 " + server.getName() + " …");
        new Thread(() -> {
            boolean ok = mcpClientManager.restartServer(server);
            Platform.runLater(() -> {
                if (ok) {
                    setStatus("服务器 " + server.getName() + " 已重启");
                    if (onConfigChanged != null) onConfigChanged.run();
                } else {
                    setStatusError("重启 " + server.getName() + " 失败，点击「查看日志」排查");
                }
            });
        }, "mcp-hot-restart").start();
    }

    private void hotStop(McpServerConfig server) {
        setStatusInfo("正在停止 " + server.getName() + " …");
        Thread.ofVirtual()
                .name("mcp-hot-stop-" + server.getName())
                .start(() -> {
                    mcpClientManager.stopServer(server.getName());
                    Platform.runLater(() -> {
                        setStatus("服务器 " + server.getName() + " 已停止");
                        refreshServerList();
                        if (onConfigChanged != null) onConfigChanged.run();
                    });
                });
    }

    /**
     * 「查看日志」抽屉：展示该 server 进程的 stderr 最近输出 + 启动错误
     */
    private void showStderrDialog(String serverName) {
        if (mcpClientManager == null) return;
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("MCP 服务器日志：" + serverName);
        dialog.setHeaderText(null);

        McpClientManager.ServerStatus status = mcpClientManager.getServerStatus(serverName);
        List<String> tail = mcpClientManager.getStderrTail(serverName);

        VBox content = new VBox(8);
        content.setPadding(new Insets(8));

        if (status.startupError() != null) {
            Label errTitle = new Label("启动错误");
            errTitle.getStyleClass().add("settings-section-title");
            Label errBody = new Label(status.startupError());
            errBody.getStyleClass().addAll("settings-hint", "status-error");
            errBody.setWrapText(true);
            content.getChildren().addAll(errTitle, errBody, new Separator());
        }

        Label tailTitle = new Label("stderr 最近输出（" + tail.size() + " 行）");
        tailTitle.getStyleClass().add("settings-section-title");
        TextArea tailArea = new TextArea(tail.isEmpty()
                ? "（无 stderr 输出）"
                : String.join("\n", tail));
        tailArea.setEditable(false);
        tailArea.setWrapText(false);
        tailArea.setPrefRowCount(16);
        tailArea.setPrefColumnCount(80);
        tailArea.getStyleClass().add("mcp-env-area");

        content.getChildren().addAll(tailTitle, tailArea);

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().setPrefWidth(640);
        styleMcpDialog(dialog);
        dialog.showAndWait();
    }

    // ==================== 添加/编辑对话框入口 ====================

    private void showAddServerDialog() {
        showServerDialog(null);
    }

    private void showEditServerDialog(McpServerConfig existing) {
        showServerDialog(existing);
    }

    private void showTemplateDialog() {
        Dialog<McpTemplateLibrary.McpTemplate> dialog = new Dialog<>();
        dialog.setTitle("从模板添加 MCP 服务器");
        dialog.setHeaderText(null);

        Label title = new Label("从模板添加");
        title.getStyleClass().add("sec-title");
        Label hint = new Label("选择常用服务模板，下一步可补充路径、密钥和启动参数。");
        hint.getStyleClass().add("sec-hint");
        hint.setWrapText(true);

        ListView<McpTemplateLibrary.McpTemplate> list = new ListView<>();
        list.getItems().addAll(McpTemplateLibrary.ALL);
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(McpTemplateLibrary.McpTemplate tpl, boolean empty) {
                super.updateItem(tpl, empty);
                if (empty || tpl == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                VBox box = new VBox(2);
                Label name = new Label(tpl.displayName());
                name.getStyleClass().add("mcp-template-name");
                Label desc = new Label(tpl.description());
                desc.getStyleClass().addAll("settings-hint");
                desc.setWrapText(true);
                Label tools = new Label("工具：" + tpl.toolsHint());
                tools.getStyleClass().addAll("settings-hint", "mcp-template-tools");
                tools.setWrapText(true);
                box.getChildren().addAll(name, desc, tools);
                setText(null);
                setGraphic(box);
            }
        });
        list.setPrefHeight(360);
        list.setPrefWidth(520);
        list.getSelectionModel().selectFirst();
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2
                    && list.getSelectionModel().getSelectedItem() != null) {
                dialog.setResult(list.getSelectionModel().getSelectedItem());
                dialog.close();
            }
        });

        VBox content = new VBox(10, title, hint, list);
        content.setPadding(new Insets(4));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(btn -> btn == ButtonType.OK
                ? list.getSelectionModel().getSelectedItem() : null);
        styleMcpDialog(dialog);

        dialog.showAndWait().ifPresent(tpl -> {
            Map<String, String> env = new LinkedHashMap<>();
            for (String key : tpl.envKeys()) env.put(key, "");
            McpServerConfig seed = new McpServerConfig(tpl.id(), tpl.command(),
                    new ArrayList<>(tpl.args()), env, true);
            showServerDialog(seed);
        });
    }

    private void showPasteJsonDialog() {
        Dialog<List<McpServerConfig>> dialog = new Dialog<>();
        dialog.setTitle("粘贴 JSON 导入 MCP 服务器");
        dialog.setHeaderText(null);

        Label title = new Label("粘贴 MCP JSON 配置");
        title.getStyleClass().add("settings-section-title");

        Label hint = new Label("""
                兼容 Claude Desktop / Cursor / Cline 的 mcpServers JSON。支持三种粘贴形态：
                  1. 完整文件：{"mcpServers": {"foo": {"command": "npx", ...}, ...}}
                  2. 仅 servers 映射：{"foo": {"command": "npx", ...}, "bar": {...}}
                  3. 单条配置：{"command": "npx", "args": [...]}（需在下方填写名称）
                导入时同名服务器会被覆盖。""");
        hint.getStyleClass().add("settings-hint");
        hint.setWrapText(true);

        TextField nameField = new TextField();
        nameField.setPromptText("名称（仅当粘贴单条配置时使用）");
        nameField.getStyleClass().add("settings-field");

        TextArea jsonArea = new TextArea();
        jsonArea.setPromptText("把 MCP JSON 配置粘贴到这里...");
        jsonArea.setPrefRowCount(14);
        jsonArea.setPrefColumnCount(60);
        jsonArea.setWrapText(false);
        jsonArea.getStyleClass().add("mcp-env-area");

        Label preview = new Label();
        preview.getStyleClass().add("settings-hint");
        preview.setWrapText(true);

        Runnable refreshPreview = () -> {
            String text = jsonArea.getText();
            if (text == null || text.isBlank()) {
                preview.setText("");
                preview.getStyleClass().setAll("settings-hint");
                return;
            }
            try {
                List<McpServerConfig> parsed = McpJsonImporter.parse(text, nameField.getText().trim());
                if (parsed.isEmpty()) {
                    preview.setText("（解析得到 0 个服务器）");
                    preview.getStyleClass().setAll("settings-hint");
                    return;
                }
                StringBuilder sb = new StringBuilder("将导入 " + parsed.size() + " 个服务器：\n");
                for (McpServerConfig s : parsed) {
                    sb.append("  • ").append(s.getName()).append("  →  ").append(s.getCommand());
                    if (!s.getArgs().isEmpty()) sb.append(" ").append(String.join(" ", s.getArgs()));
                    sb.append("\n");
                }
                preview.setText(sb.toString().trim());
                preview.getStyleClass().setAll("settings-hint", "status-success");
            } catch (Exception e) {
                preview.setText("⚠ " + e.getMessage());
                preview.getStyleClass().setAll("settings-hint", "status-error");
            }
        };
        jsonArea.textProperty().addListener((obs, o, n) -> refreshPreview.run());
        nameField.textProperty().addListener((obs, o, n) -> refreshPreview.run());

        VBox content = new VBox(10, title, hint, nameField, jsonArea, new Separator(), preview);
        content.setPadding(new Insets(4));

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().setPrefWidth(560);

        dialog.setResultConverter(button -> {
            if (button != ButtonType.OK) return null;
            try {
                return McpJsonImporter.parse(jsonArea.getText(), nameField.getText().trim());
            } catch (Exception ex) {
                setStatusError("导入失败：" + ex.getMessage());
                return null;
            }
        });
        Button importButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        importButton.setText("导入");
        importButton.getStyleClass().addAll("jc-btn", "jc-btn-primary");
        importButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            try {
                List<McpServerConfig> parsed = McpJsonImporter.parse(
                        jsonArea.getText(), nameField.getText().trim());
                if (parsed.isEmpty()) {
                    preview.setText("⚠ JSON 中未发现任何 MCP 服务器");
                    preview.getStyleClass().setAll("settings-hint", "status-error");
                    event.consume();
                }
            } catch (Exception ex) {
                preview.setText("⚠ " + ex.getMessage());
                preview.getStyleClass().setAll("settings-hint", "status-error");
                event.consume();
            }
        });
        styleMcpDialog(dialog);

        dialog.showAndWait().ifPresent(servers -> {
            if (servers.isEmpty()) {
                setStatusError("JSON 中未发现任何 MCP 服务器");
                return;
            }
            for (McpServerConfig s : servers) {
                configManager.putServer(s);
            }
            refreshServerList();

            // 已启用的服务器后台自动启动，让用户立刻在折叠面板里看到工具
            List<McpServerConfig> toStart = mcpClientManager != null
                    ? servers.stream().filter(McpServerConfig::isEnabled).toList()
                    : List.of();
            if (toStart.isEmpty()) {
                setStatus("已导入 " + servers.size() + " 个服务器");
            } else {
                setStatusInfo("已导入 " + servers.size() + " 个服务器，正在启动 "
                        + toStart.size() + " 个 …");
                new Thread(() -> {
                    int ok = 0, fail = 0;
                    for (McpServerConfig s : toStart) {
                        if (mcpClientManager.startServer(s)) ok++;
                        else fail++;
                    }
                    int finalOk = ok, finalFail = fail;
                    Platform.runLater(() -> {
                        if (finalFail == 0) {
                            setStatus("已导入并启动 " + finalOk + " 个服务器，可展开折叠面板查看工具");
                        } else {
                            setStatusError("已导入：" + finalOk + " 个启动成功，"
                                    + finalFail + " 个失败，点击「查看日志」排查");
                        }
                        refreshServerList();
                    });
                }, "mcp-bulk-start-after-import").start();
            }
            if (onConfigChanged != null) onConfigChanged.run();
        });
    }

    /**
     * 添加/编辑服务器对话框（v2 — args 多行 + env 表格 + 测试连接）
     */
    private void showServerDialog(McpServerConfig existing) {
        Dialog<McpServerConfig> dialog = new Dialog<>();
        dialog.setTitle(existing == null ? "添加 MCP 服务器" : "编辑 MCP 服务器");
        dialog.setHeaderText(null);

        Label titleLabel = new Label(existing == null ? "添加 MCP 服务器" : "编辑 MCP 服务器");
        titleLabel.getStyleClass().add("settings-section-title");

        // ====== 传输类型选择：分段控件（seg-container + seg-btn，对齐设计稿 AddServerInline） ======
        ToggleGroup transportGroup = new ToggleGroup();
        ToggleButton stdioRadio = new ToggleButton("stdio（本地进程）");
        stdioRadio.setToggleGroup(transportGroup);
        stdioRadio.getStyleClass().add("seg-btn");
        ToggleButton httpRadio = new ToggleButton("http（远程 URL）");
        httpRadio.setToggleGroup(transportGroup);
        httpRadio.getStyleClass().add("seg-btn");
        HBox transportBar = new HBox(stdioRadio, httpRadio);
        transportBar.getStyleClass().add("seg-container");
        transportBar.setAlignment(Pos.CENTER_LEFT);

        boolean isHttp = existing != null && "http".equals(existing.getTransport());
        if (isHttp) httpRadio.setSelected(true); else stdioRadio.setSelected(true);
        // 分段控件至少保持一项选中：点已选项不允许取消
        transportGroup.selectedToggleProperty().addListener((o, oldT, newT) -> {
            if (newT == null && oldT != null) oldT.setSelected(true);
        });

        // ====== 名称 ======
        TextField nameField = new TextField();
        nameField.setPromptText("服务器名称（唯一标识）");
        nameField.getStyleClass().add("settings-field");
        nameField.setPrefWidth(420);

        // ====== stdio 字段 ======
        TextField commandField = new TextField();
        commandField.setPromptText("npx / python / node / uvx");
        commandField.getStyleClass().add("settings-field");
        commandField.setPrefWidth(420);

        TextArea argsArea = new TextArea();
        argsArea.setPromptText("命令参数（一行一个，按顺序执行）\n例如：\n-y\n@modelcontextprotocol/server-filesystem\n/Users/me/projects");
        argsArea.setPrefRowCount(5);
        argsArea.setPrefColumnCount(48);
        argsArea.setWrapText(false);
        argsArea.getStyleClass().add("mcp-env-area");

        Label commandPreview = new Label();
        commandPreview.getStyleClass().add("settings-hint");
        commandPreview.setWrapText(true);

        VBox envRowsBox = new VBox(4);
        Button addEnvBtn = new Button("+ 添加环境变量");
        addEnvBtn.getStyleClass().add("settings-test-button");
        addEnvBtn.setOnAction(e -> envRowsBox.getChildren()
                .add(buildEnvRow("", "", false, envRowsBox)));

        // ====== http 字段 ======
        TextField urlField = new TextField();
        urlField.setPromptText("MCP 端点 URL（如 https://api.example.com/mcp）");
        urlField.getStyleClass().add("settings-field");
        urlField.setPrefWidth(420);

        VBox headerRowsBox = new VBox(4);
        Button addHeaderBtn = new Button("+ 添加 Header");
        addHeaderBtn.getStyleClass().add("settings-test-button");
        addHeaderBtn.setOnAction(e -> headerRowsBox.getChildren()
                .add(buildEnvRow("", "", false, headerRowsBox)));

        // ====== 启用 ======
        CheckBox enabledCheck = new CheckBox("启用");
        enabledCheck.getStyleClass().add("settings-checkbox");
        enabledCheck.setSelected(true);

        // ====== 测试连接结果展示区 ======
        Label testResultLabel = new Label();
        testResultLabel.getStyleClass().add("settings-hint");
        testResultLabel.setWrapText(true);
        testResultLabel.setMaxWidth(420);

        Label formErrorLabel = new Label();
        formErrorLabel.getStyleClass().addAll("settings-hint", "status-error");
        formErrorLabel.setWrapText(true);
        formErrorLabel.setManaged(false);
        formErrorLabel.setVisible(false);

        // ====== 预填编辑内容 ======
        if (existing != null) {
            nameField.setText(existing.getName());
            nameField.setDisable(true);
            if (existing.getCommand() != null) commandField.setText(existing.getCommand());
            if (existing.getArgs() != null) argsArea.setText(String.join("\n", existing.getArgs()));
            if (existing.getEnv() != null) {
                for (Map.Entry<String, String> e : existing.getEnv().entrySet()) {
                    envRowsBox.getChildren().add(buildEnvRow(e.getKey(), e.getValue(),
                            isLikelySecret(e.getKey()), envRowsBox));
                }
            }
            if (existing.getUrl() != null) urlField.setText(existing.getUrl());
            if (existing.getHeaders() != null) {
                for (Map.Entry<String, String> e : existing.getHeaders().entrySet()) {
                    headerRowsBox.getChildren().add(buildEnvRow(e.getKey(), e.getValue(),
                            isLikelySecret(e.getKey()), headerRowsBox));
                }
            }
            enabledCheck.setSelected(existing.isEnabled());
        }

        // 实时拼接命令预览
        Runnable refreshCmdPreview = () -> {
            String cmd = commandField.getText() == null ? "" : commandField.getText().trim();
            String[] lines = argsArea.getText() == null ? new String[0]
                    : argsArea.getText().split("\\R");
            StringBuilder sb = new StringBuilder("$ ");
            sb.append(cmd.isEmpty() ? "<command>" : cmd);
            for (String line : lines) {
                String t = line.trim();
                if (!t.isEmpty()) sb.append(' ').append(t);
            }
            commandPreview.setText(sb.toString());
        };
        commandField.textProperty().addListener((o, a, b) -> refreshCmdPreview.run());
        argsArea.textProperty().addListener((o, a, b) -> refreshCmdPreview.run());
        refreshCmdPreview.run();

        // ====== 布局 ======
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        int row = 0;
        grid.add(groupLabel("名称"), 0, row++);
        grid.add(nameField, 0, row++);
        grid.add(groupLabel("传输类型"), 0, row++);
        grid.add(transportBar, 0, row++);

        // stdio 区
        Label stdioCmdLabel = groupLabel("启动命令");
        Label stdioArgsLabel = groupLabel("参数（一行一个）");
        Label stdioEnvLabel = groupLabel("环境变量");
        grid.add(stdioCmdLabel, 0, row++);
        grid.add(commandField, 0, row++);
        grid.add(stdioArgsLabel, 0, row++);
        grid.add(argsArea, 0, row++);
        grid.add(commandPreview, 0, row++);
        grid.add(stdioEnvLabel, 0, row++);
        grid.add(envRowsBox, 0, row++);
        grid.add(addEnvBtn, 0, row++);

        // http 区
        Label httpUrlLabel = groupLabel("MCP 端点 URL");
        Label httpHeaderLabel = groupLabel("HTTP Headers（如 Authorization）");
        grid.add(httpUrlLabel, 0, row++);
        grid.add(urlField, 0, row++);
        grid.add(httpHeaderLabel, 0, row++);
        grid.add(headerRowsBox, 0, row++);
        grid.add(addHeaderBtn, 0, row++);

        grid.add(enabledCheck, 0, row++);

        // 切换传输 → 控制可见性
        Runnable applyTransportVisibility = () -> {
            boolean http = httpRadio.isSelected();
            for (Node n : List.of(stdioCmdLabel, commandField, stdioArgsLabel,
                    argsArea, commandPreview, stdioEnvLabel, envRowsBox, addEnvBtn)) {
                n.setVisible(!http);
                n.setManaged(!http);
            }
            for (Node n : List.of(httpUrlLabel, urlField, httpHeaderLabel,
                    headerRowsBox, addHeaderBtn)) {
                n.setVisible(http);
                n.setManaged(http);
            }
        };
        transportGroup.selectedToggleProperty().addListener((o, a, b) -> applyTransportVisibility.run());
        applyTransportVisibility.run();

        // ====== 测试连接按钮（在底部按钮栏左侧） ======
        ButtonType testButtonType = mcpClientManager != null
                ? new ButtonType("🔌 测试连接", ButtonBar.ButtonData.LEFT)
                : null;

        VBox content = new VBox(12, titleLabel, grid, formErrorLabel, testResultLabel);
        content.setPadding(new Insets(4));

        dialog.getDialogPane().setContent(content);
        if (testButtonType != null) {
            dialog.getDialogPane().getButtonTypes()
                    .addAll(testButtonType, ButtonType.OK, ButtonType.CANCEL);

            Button testBtn = (Button) dialog.getDialogPane().lookupButton(testButtonType);
            testBtn.addEventFilter(javafx.event.ActionEvent.ACTION, evt -> {
                evt.consume();
                String validation = validateServerForm(existing, httpRadio.isSelected(),
                        nameField, commandField, urlField, envRowsBox, headerRowsBox);
                if (validation != null) {
                    showFormError(formErrorLabel, validation);
                    return;
                }
                McpServerConfig draft = collectFormConfig(httpRadio.isSelected(),
                        nameField, commandField, argsArea, envRowsBox,
                        urlField, headerRowsBox, enabledCheck);
                hideFormError(formErrorLabel);
                runTestConnection(draft, testResultLabel, testBtn);
            });
        } else {
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        }
        // 「添加 / 更新」使用中心窗口统一的主操作样式
        Node okBtn = dialog.getDialogPane().lookupButton(ButtonType.OK);
        if (okBtn instanceof Button ok) {
            ok.setText(existing == null ? "添加" : "更新");
            ok.getStyleClass().addAll("jc-btn", "jc-btn-primary");
            ok.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                String validation = validateServerForm(existing, httpRadio.isSelected(),
                        nameField, commandField, urlField, envRowsBox, headerRowsBox);
                if (validation != null) {
                    showFormError(formErrorLabel, validation);
                    event.consume();
                } else {
                    hideFormError(formErrorLabel);
                }
            });
        }
        dialog.getDialogPane().setPrefWidth(560);

        dialog.setResultConverter(button -> {
            if (button == ButtonType.OK) {
                return collectFormConfig(httpRadio.isSelected(),
                        nameField, commandField, argsArea, envRowsBox,
                        urlField, headerRowsBox, enabledCheck);
            }
            return null;
        });
        nameField.textProperty().addListener((o, a, b) -> hideFormError(formErrorLabel));
        commandField.textProperty().addListener((o, a, b) -> hideFormError(formErrorLabel));
        urlField.textProperty().addListener((o, a, b) -> hideFormError(formErrorLabel));
        transportGroup.selectedToggleProperty().addListener((o, a, b) -> hideFormError(formErrorLabel));
        styleMcpDialog(dialog);

        dialog.showAndWait().ifPresent(config -> {
            configManager.putServer(config);
            // 添加 / 编辑：自动热重启该 server，不让用户再手动点
            if (mcpClientManager != null && config.isEnabled()) {
                new Thread(() -> {
                    boolean ok = mcpClientManager.restartServer(config);
                    Platform.runLater(() -> {
                        if (ok) {
                            setStatus("已" + (existing == null ? "添加并启动" : "更新并重启")
                                    + ": " + config.getName());
                        } else {
                            setStatusError("已保存配置，但启动 " + config.getName()
                                    + " 失败，点击「查看日志」排查");
                        }
                        refreshServerList();
                        if (onConfigChanged != null) onConfigChanged.run();
                    });
                }, "mcp-restart-after-save").start();
            } else {
                refreshServerList();
                setStatus("已" + (existing == null ? "添加" : "更新")
                        + "服务器: " + config.getName());
                if (onConfigChanged != null) onConfigChanged.run();
            }
        });
    }

    /**
     * 单行环境变量编辑：key 字段 + value 字段（按 secret 切换密码框）+ 删除按钮
     */
    private HBox buildEnvRow(String key, String value, boolean isSecret, VBox parent) {
        HBox row = new HBox(6);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("mcp-env-row");

        TextField keyField = new TextField(key);
        keyField.setPromptText("KEY");
        keyField.getStyleClass().add("settings-field");
        keyField.setPrefWidth(145);
        keyField.setMinWidth(110);

        // value 用 StringProperty 桥接两个控件，secret 切换不丢值
        StringProperty valueProp = new SimpleStringProperty(value);
        TextField plainValue = new TextField();
        plainValue.setPromptText("VALUE");
        plainValue.getStyleClass().add("settings-field");
        plainValue.setPrefWidth(230);
        plainValue.setMaxWidth(Double.MAX_VALUE);
        plainValue.textProperty().bindBidirectional(valueProp);

        PasswordField secretValue = new PasswordField();
        secretValue.setPromptText("VALUE");
        secretValue.getStyleClass().add("settings-field");
        secretValue.setPrefWidth(230);
        secretValue.setMaxWidth(Double.MAX_VALUE);
        secretValue.textProperty().bindBidirectional(valueProp);

        StackPane valueSlot = new StackPane();
        valueSlot.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(valueSlot, Priority.ALWAYS);
        plainValue.setVisible(!isSecret);
        plainValue.setManaged(!isSecret);
        secretValue.setVisible(isSecret);
        secretValue.setManaged(isSecret);
        valueSlot.getChildren().addAll(plainValue, secretValue);

        ToggleButton secretToggle = new ToggleButton(isSecret ? "🔒" : "👁");
        secretToggle.setSelected(isSecret);
        secretToggle.getStyleClass().add("settings-test-button");
        Tooltip.install(secretToggle, new Tooltip("切换为密钥/明文"));
        secretToggle.setOnAction(e -> {
            boolean nowSecret = secretToggle.isSelected();
            plainValue.setVisible(!nowSecret);
            plainValue.setManaged(!nowSecret);
            secretValue.setVisible(nowSecret);
            secretValue.setManaged(nowSecret);
            secretToggle.setText(nowSecret ? "🔒" : "👁");
        });

        Button removeBtn = new Button("✕");
        removeBtn.getStyleClass().add("settings-test-button");
        removeBtn.setOnAction(e -> parent.getChildren().remove(row));

        row.getChildren().addAll(keyField, valueSlot, secretToggle, removeBtn);
        return row;
    }

    private String validateServerForm(McpServerConfig existing,
                                      boolean http,
                                      TextField nameField,
                                      TextField commandField,
                                      TextField urlField,
                                      VBox envRowsBox,
                                      VBox headerRowsBox) {
        String name = safe(nameField.getText()).trim();
        if (name.isEmpty()) {
            return "请填写服务器名称。";
        }
        if (existing == null && configManager.getServer(name) != null) {
            return "服务器名称「" + name + "」已存在，请换一个名称，或返回列表编辑原配置。";
        }

        if (http) {
            String url = safe(urlField.getText()).trim();
            if (url.isEmpty()) {
                return "请填写 MCP 端点 URL。";
            }
            try {
                java.net.URI uri = new java.net.URI(url);
                String scheme = uri.getScheme();
                if (scheme == null
                        || (!"http".equalsIgnoreCase(scheme)
                        && !"https".equalsIgnoreCase(scheme))
                        || uri.getHost() == null) {
                    return "MCP 端点需为有效的 http:// 或 https:// URL。";
                }
            } catch (java.net.URISyntaxException ex) {
                return "MCP 端点 URL 格式不正确：" + ex.getMessage();
            }
            String duplicate = findDuplicateKey(headerRowsBox);
            return duplicate == null ? null : "Header 名称「" + duplicate + "」重复。";
        }

        if (safe(commandField.getText()).trim().isEmpty()) {
            return "请填写 stdio 服务的启动命令。";
        }
        String duplicate = findDuplicateKey(envRowsBox);
        return duplicate == null ? null : "环境变量名称「" + duplicate + "」重复。";
    }

    private String findDuplicateKey(VBox rowsBox) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (Node node : rowsBox.getChildren()) {
            if (!(node instanceof HBox row)
                    || row.getChildren().isEmpty()
                    || !(row.getChildren().getFirst() instanceof TextField field)) {
                continue;
            }
            String key = safe(field.getText()).trim();
            if (!key.isEmpty() && !keys.add(key)) {
                return key;
            }
        }
        return null;
    }

    private void showFormError(Label label, String message) {
        label.setText("⚠ " + message);
        label.setManaged(true);
        label.setVisible(true);
    }

    private void hideFormError(Label label) {
        label.setText("");
        label.setManaged(false);
        label.setVisible(false);
    }

    /**
     * 从表单收集配置：按 http 标志走两种校验。
     * 必填校验失败返回 null（调用方据此提示错误）。
     */
    private McpServerConfig collectFormConfig(boolean http,
                                              TextField nameField,
                                              TextField commandField, TextArea argsArea,
                                              VBox envRowsBox,
                                              TextField urlField, VBox headerRowsBox,
                                              CheckBox enabledCheck) {
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        if (name.isEmpty()) return null;

        if (http) {
            String url = urlField.getText() == null ? "" : urlField.getText().trim();
            if (url.isEmpty()) return null;
            Map<String, String> headers = collectKvRows(headerRowsBox);
            return new McpServerConfig(name, url, headers, enabledCheck.isSelected());
        }

        String command = commandField.getText() == null ? "" : commandField.getText().trim();
        if (command.isEmpty()) return null;

        List<String> args = new ArrayList<>();
        if (argsArea.getText() != null) {
            for (String line : argsArea.getText().split("\\R")) {
                String t = line.trim();
                if (!t.isEmpty()) args.add(t);
            }
        }
        Map<String, String> env = collectKvRows(envRowsBox);
        return new McpServerConfig(name, command, args, env, enabledCheck.isSelected());
    }

    /**
     * 通用 key/value 表格行收集（env 与 headers 共用同一表单结构）
     */
    private Map<String, String> collectKvRows(VBox rowsBox) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Node node : rowsBox.getChildren()) {
            if (!(node instanceof HBox row)) continue;
            if (row.getChildren().size() < 2) continue;
            TextField keyField = (TextField) row.getChildren().get(0);
            String k = keyField.getText() == null ? "" : keyField.getText().trim();
            if (k.isEmpty()) continue;
            StackPane valueSlot = (StackPane) row.getChildren().get(1);
            String v = "";
            for (Node n : valueSlot.getChildren()) {
                if (n instanceof TextField tf && tf.getText() != null) {
                    v = tf.getText();
                    break;
                }
            }
            map.put(k, v);
        }
        return map;
    }

    /**
     * 在编辑对话框内执行测试连接，把结果写到 inline label
     */
    private void runTestConnection(McpServerConfig draft, Label resultLabel, Button testBtn) {
        if (mcpClientManager == null) return;
        testBtn.setDisable(true);
        resultLabel.getStyleClass().setAll("settings-hint", "status-info");
        resultLabel.setText("正在测试 " + draft.getName() + " ...");
        new Thread(() -> {
            McpClientManager.TestResult r = mcpClientManager.testConnection(draft);
            Platform.runLater(() -> {
                testBtn.setDisable(false);
                if (r.success()) {
                    StringBuilder sb = new StringBuilder("✓ 连接成功（")
                            .append(r.elapsedMs()).append("ms）");
                    if (r.serverName() != null) {
                        sb.append("，server=").append(r.serverName());
                        if (r.serverVersion() != null) sb.append(" v").append(r.serverVersion());
                    }
                    sb.append("，发现 ").append(r.tools().size()).append(" 个工具");
                    if (!r.tools().isEmpty()) {
                        sb.append("：\n  ");
                        sb.append(r.tools().stream().limit(8)
                                .map(McpClient.McpToolInfo::getName)
                                .collect(Collectors.joining(", ")));
                        if (r.tools().size() > 8) sb.append(", ...");
                    }
                    resultLabel.getStyleClass().setAll("settings-hint", "status-success");
                    resultLabel.setText(sb.toString());
                } else {
                    resultLabel.getStyleClass().setAll("settings-hint", "status-error");
                    resultLabel.setText("✗ 连接失败：" + r.errorMessage());
                }
            });
        }, "mcp-test-connection").start();
    }

    // ==================== 工具方法 ====================

    private Label groupLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("settings-group-title");
        return l;
    }

    /**
     * 关键字启发式判断：常见 secret 名包含 KEY/TOKEN/SECRET/PASSWORD
     * 仅用于 UI 默认隐藏明文，不影响存储
     */
    private boolean isLikelySecret(String key) {
        if (key == null) return false;
        String upper = key.toUpperCase();
        return upper.contains("KEY") || upper.contains("TOKEN")
                || upper.contains("SECRET") || upper.contains("PASSWORD")
                || upper.contains("PASSWD");
    }

    private String maskValue(String value) {
        if (value == null || value.length() <= 8) return value;
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private void copyText(String value) {
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(safe(value));
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
    }

    private javafx.stage.Stage ownerStage() {
        if (window != null) {
            return window;
        }
        if (serverListBox != null
                && serverListBox.getScene() != null
                && serverListBox.getScene().getWindow() instanceof javafx.stage.Stage stage) {
            return stage;
        }
        return null;
    }

    private void styleMcpDialog(Dialog<?> dialog) {
        javafx.stage.Stage owner = ownerStage();
        if (owner != null && dialog.getOwner() == null) {
            dialog.initOwner(owner);
        }
        UIHelper.styleDialog(dialog);
        var css = getClass().getResource("/css/mcp-settings.css");
        if (css != null) {
            String external = css.toExternalForm();
            if (!dialog.getDialogPane().getStylesheets().contains(external)) {
                dialog.getDialogPane().getStylesheets().add(external);
            }
        }
    }

    private void attachMcpStyles(Node node) {
        var css = getClass().getResource("/css/mcp-settings.css");
        if (css == null) return;
        String external = css.toExternalForm();
        java.util.function.Consumer<javafx.scene.Scene> attach = scene -> {
            if (scene != null && !scene.getStylesheets().contains(external)) {
                scene.getStylesheets().add(external);
            }
        };
        attach.accept(node.getScene());
        node.sceneProperty().addListener((obs, oldScene, newScene) -> attach.accept(newScene));
    }

    private void addStylesheet(javafx.scene.Scene scene, String path) {
        var css = getClass().getResource(path);
        if (css == null) return;
        String external = css.toExternalForm();
        if (!scene.getStylesheets().contains(external)) {
            scene.getStylesheets().add(external);
        }
    }

    private void dispose() {
        if (statusClearTimer != null) {
            statusClearTimer.stop();
        }
        if (mcpClientManager != null && managerStateListener != null) {
            mcpClientManager.removeStateListener(managerStateListener);
            managerStateListener = null;
        }
        window = null;
    }

    private void setStatus(String message) {
        if (statusLabel != null) {
            statusLabel.setText(message);
            statusLabel.getStyleClass().removeAll("status-success", "status-error", "status-info");
            statusLabel.getStyleClass().add("status-success");
            scheduleStatusClear();
        }
    }

    private void setStatusInfo(String message) {
        if (statusLabel != null) {
            statusLabel.setText(message);
            statusLabel.getStyleClass().removeAll("status-success", "status-error", "status-info");
            statusLabel.getStyleClass().add("status-info");
            scheduleStatusClear();
        }
    }

    private void setStatusError(String message) {
        if (statusLabel != null) {
            statusLabel.setText(message);
            statusLabel.getStyleClass().removeAll("status-success", "status-error", "status-info");
            statusLabel.getStyleClass().add("status-error");
            scheduleStatusClear();
        }
    }

    /**
     * 安排操作反馈 3 秒后自动清空。连续操作时重启同一个计时器（先 stop 再
     * playFromStart），避免叠加多个计时器导致提前/重复清空。
     */
    private void scheduleStatusClear() {
        if (statusLabel == null) return;
        if (statusClearTimer == null) {
            statusClearTimer = new javafx.animation.PauseTransition(
                    javafx.util.Duration.seconds(3));
            statusClearTimer.setOnFinished(e -> {
                if (statusLabel != null) statusLabel.setText("");
            });
        }
        statusClearTimer.stop();
        statusClearTimer.playFromStart();
    }
}
