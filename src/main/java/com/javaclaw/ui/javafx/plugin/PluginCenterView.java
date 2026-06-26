package com.javaclaw.ui.javafx.plugin;

import com.javaclaw.app.UIHelper;
import com.javaclaw.plugin.PluginInfo;
import com.javaclaw.plugin.PluginManager;
import com.javaclaw.plugin.PluginState;
import com.javaclaw.plugin.api.PluginDescriptor;
import com.javaclaw.ui.javafx.control.ToggleSwitch;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件中心 —— 插件系统的可视化管理入口。
 *
 * <p>左侧导航（已安装 / 市场 + 刷新与从文件安装），右侧卡片网格列出 {@code plugins/} 目录下
 * 发现的插件；点卡片进入详情（能力授权、暴露的技能/工具、配置表单、启停、打开目录、卸载）。
 * 「市场」页为占位（暂无在线市场后端）。沿用统一骨架与令牌化样式（{@code -jc-*}），不硬编码颜色。</p>
 *
 * @author JavaClaw
 */
public final class PluginCenterView {

    private static final Logger log = LoggerFactory.getLogger(PluginCenterView.class);

    private final Stage stage;
    private final PluginManager manager = PluginManager.getInstance();

    private String tab = "installed";        // installed | market
    private String query = "";
    private String selectedId;               // 非 null 时展示详情

    private FlowPane cardGrid;
    private StackPane rightContent;
    private VBox listView;       // 卡片网格视图根
    private VBox detailView;     // 详情视图根
    private Label countLabel;
    private TextField searchField;
    private ToggleButton installedTab;
    private ToggleButton marketTab;

    public PluginCenterView(Stage owner) {
        this.stage = new Stage();
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initOwner(owner);
        stage.setTitle("插件中心");
        stage.setResizable(true);
        buildUI();
    }

    // ==================== 骨架 ====================

    private void buildUI() {
        // ----- 左侧导航 -----
        Label title = new Label("插件中心");
        title.getStyleClass().add("modal-left-title");
        title.setPadding(new Insets(18, 16, 10, 16));

        ToggleGroup tabGroup = new ToggleGroup();
        installedTab = segButton("已安装", "installed", tabGroup, true);
        marketTab = segButton("市场", "market", tabGroup, false);
        HBox tabs = new HBox(3, installedTab, marketTab);
        tabs.getStyleClass().add("seg-container");
        installedTab.setMaxWidth(Double.MAX_VALUE);
        marketTab.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(installedTab, Priority.ALWAYS);
        HBox.setHgrow(marketTab, Priority.ALWAYS);
        VBox tabsWrap = new VBox(tabs);
        tabsWrap.setPadding(new Insets(0, 12, 12, 12));

        Region navSpacer = new Region();
        VBox.setVgrow(navSpacer, Priority.ALWAYS);

        Button refreshBtn = new Button("⟳ 刷新目录");
        refreshBtn.getStyleClass().addAll("jc-btn", "jc-btn-ghost", "jc-btn-sm");
        refreshBtn.setMaxWidth(Double.MAX_VALUE);
        refreshBtn.setOnAction(e -> { manager.refresh(); render(); });

        Button installBtn = new Button("从文件安装…");
        installBtn.getStyleClass().addAll("jc-btn", "jc-btn-soft", "jc-btn-sm");
        installBtn.setMaxWidth(Double.MAX_VALUE);
        installBtn.setOnAction(e -> onInstallFromFile());

        VBox navBottom = new VBox(6, installBtn, refreshBtn);
        navBottom.setPadding(new Insets(10, 12, 12, 12));
        navBottom.setStyle("-fx-border-color: -jc-border transparent transparent transparent; -fx-border-width: 1 0 0 0;");

        VBox leftPane = new VBox(title, tabsWrap, navSpacer, navBottom);
        leftPane.getStyleClass().add("modal-left-pane");
        leftPane.setPrefWidth(210);
        leftPane.setMinWidth(180);

        // ----- 右侧 -----
        // 顶部搜索行
        searchField = new TextField();
        searchField.setPromptText("⌕  搜索插件…");
        searchField.getStyleClass().add("settings-field");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.textProperty().addListener((o, a, b) -> { query = b == null ? "" : b.trim(); renderCards(); });

        countLabel = new Label();
        countLabel.getStyleClass().add("sec-hint");

        HBox searchRow = new HBox(12, searchField, countLabel);
        searchRow.setAlignment(Pos.CENTER_LEFT);
        searchRow.setPadding(new Insets(14, 22, 6, 22));

        cardGrid = new FlowPane(12, 12);
        cardGrid.setPadding(new Insets(8, 22, 16, 22));
        ScrollPane cardScroll = new ScrollPane(cardGrid);
        cardScroll.setFitToWidth(true);
        cardScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        cardScroll.getStyleClass().add("settings-scroll-pane");
        VBox.setVgrow(cardScroll, Priority.ALWAYS);

        listView = new VBox(searchRow, cardScroll);

        detailView = new VBox(12);
        detailView.setPadding(new Insets(18, 22, 16, 22));
        ScrollPane detailScroll = new ScrollPane(detailView);
        detailScroll.setFitToWidth(true);
        detailScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        detailScroll.getStyleClass().add("settings-scroll-pane");
        this.detailScroll = detailScroll;

        rightContent = new StackPane(listView, detailScroll);
        rightContent.getStyleClass().add("modal-content-area");
        VBox.setVgrow(rightContent, Priority.ALWAYS);
        detailScroll.setVisible(false);
        detailScroll.setManaged(false);

        // 页脚
        Label dirHint = new Label(manager.pluginsDir().toString());
        dirHint.getStyleClass().add("sec-hint");
        Region footSpacer = new Region();
        HBox.setHgrow(footSpacer, Priority.ALWAYS);
        Button closeBtn = new Button("关闭");
        closeBtn.getStyleClass().addAll("jc-btn", "jc-btn-ghost");
        closeBtn.setOnAction(e -> stage.close());
        HBox foot = new HBox(10, dirHint, footSpacer, closeBtn);
        foot.setAlignment(Pos.CENTER_LEFT);
        foot.getStyleClass().add("modal-foot");

        VBox rightPane = new VBox(rightContent, foot);
        HBox.setHgrow(rightPane, Priority.ALWAYS);

        HBox main = new HBox(leftPane, rightPane);
        main.getStyleClass().add("settings-root");

        Scene scene = new Scene(main, 960, 680);
        applyStylesheets(scene);
        stage.setScene(scene);
    }

    private ScrollPane detailScroll;

    private ToggleButton segButton(String text, String id, ToggleGroup grp, boolean selected) {
        ToggleButton b = new ToggleButton(text);
        b.getStyleClass().add("seg-btn");
        b.setToggleGroup(grp);
        b.setUserData(id);
        b.setSelected(selected);
        b.setOnAction(e -> {
            if (b.isSelected()) {
                tab = id;
                selectedId = null;
                render();
            } else {
                b.setSelected(true);
            }
        });
        return b;
    }

    public void show() {
        manager.setChangeListener(() -> Platform.runLater(this::render));
        stage.setOnHidden(e -> manager.setChangeListener(null));
        manager.refresh();
        render();
        stage.showAndWait();
    }

    // ==================== 渲染 ====================

    private void render() {
        if (selectedId != null && manager.list().stream().anyMatch(i -> i.id().equals(selectedId))) {
            showDetail();
        } else {
            selectedId = null;
            showList();
        }
    }

    private void showList() {
        detailScroll.setVisible(false);
        detailScroll.setManaged(false);
        listView.setVisible(true);
        listView.setManaged(true);
        renderCards();
    }

    private void renderCards() {
        cardGrid.getChildren().clear();
        if ("market".equals(tab)) {
            countLabel.setText("");
            searchField.setDisable(true);
            cardGrid.getChildren().add(buildMarketPlaceholder());
            return;
        }
        searchField.setDisable(false);
        List<PluginInfo> infos = manager.list().stream()
                .filter(i -> query.isEmpty()
                        || i.name().contains(query)
                        || (i.description() != null && i.description().contains(query)))
                .toList();
        countLabel.setText(manager.list().size() + " 个已安装");

        if (infos.isEmpty()) {
            cardGrid.getChildren().add(buildEmpty(manager.list().isEmpty()
                    ? "plugins/ 目录暂无插件\n点「从文件安装…」或把插件子目录放入该目录后刷新"
                    : "没有匹配的插件"));
            return;
        }
        for (PluginInfo info : infos) {
            cardGrid.getChildren().add(buildCard(info));
        }
    }

    private VBox buildMarketPlaceholder() {
        Label icon = new Label("⧉");
        icon.getStyleClass().add("empty-state-icon");
        Label text = new Label("插件市场即将上线");
        text.getStyleClass().add("empty-state-text");
        Label hint = new Label("在线插件市场（搜索 / 一键安装 / 自动更新）尚未接入。\n"
                + "当前可通过「从文件安装…」加载本地插件 jar。");
        hint.getStyleClass().addAll("sec-hint", "empty-state-hint");
        hint.setWrapText(true);
        hint.setMaxWidth(360);
        VBox box = new VBox(12, icon, text, hint);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(60, 0, 0, 0));
        box.setPrefWidth(820);
        return box;
    }

    private VBox buildEmpty(String message) {
        Label icon = new Label("⧉");
        icon.getStyleClass().add("empty-state-icon");
        Label text = new Label(message);
        text.getStyleClass().addAll("sec-hint", "empty-state-hint");
        text.setWrapText(true);
        text.setMaxWidth(360);
        VBox box = new VBox(10, icon, text);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(60, 0, 0, 0));
        box.setPrefWidth(820);
        return box;
    }

    /** 插件卡片：图标 + 名称/状态徽标 + 元信息 + 描述 + 启停开关。 */
    private VBox buildCard(PluginInfo info) {
        boolean active = info.state() == PluginState.ACTIVE;
        boolean failed = info.state() == PluginState.FAILED;

        Label glyph = new Label(glyphFor(info));
        glyph.getStyleClass().add("plugin-glyph");

        Label name = new Label(info.name());
        name.getStyleClass().add("plugin-card-name");
        name.setMaxWidth(160);

        HBox nameRow = new HBox(6, name);
        nameRow.setAlignment(Pos.CENTER_LEFT);
        nameRow.getChildren().add(stateBadge(info.state()));

        Label meta = new Label("v" + nz(info.version()) + " · " + capsLine(info));
        meta.getStyleClass().add("plugin-card-meta");

        VBox head = new VBox(3, nameRow, meta);
        HBox.setHgrow(head, Priority.ALWAYS);
        HBox headRow = new HBox(11, glyph, head);
        headRow.setAlignment(Pos.CENTER_LEFT);
        headRow.setStyle("-fx-cursor: hand;");
        headRow.setOnMouseClicked(e -> { selectedId = info.id(); showDetail(); });

        Label desc = new Label(nz(info.description()).isBlank() ? "（无描述）" : info.description());
        desc.getStyleClass().add("plugin-card-desc");
        desc.setWrapText(true);
        desc.setMaxWidth(280);
        desc.setMaxHeight(40);

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox actions = new HBox(8, sp);
        actions.setAlignment(Pos.CENTER_LEFT);
        if (failed) {
            Label errTag = new Label("启用失败");
            errTag.getStyleClass().addAll("jc-badge", "jc-badge-failed");
            errTag.setStyle("-fx-font-size:10px; -fx-padding:1 8 1 8;");
            actions.getChildren().add(errTag);
        }
        ToggleSwitch toggle = new ToggleSwitch(active);
        toggle.selectedProperty().addListener((o, was, now) -> onToggle(info, now, toggle));
        actions.getChildren().add(toggle);

        VBox card = new VBox(10, headRow, desc, actions);
        card.getStyleClass().add("plugin-card");
        card.setPrefWidth(330);
        card.setPadding(new Insets(13));
        return card;
    }

    // ==================== 详情 ====================

    private void showDetail() {
        PluginInfo info = manager.list().stream()
                .filter(i -> i.id().equals(selectedId)).findFirst().orElse(null);
        if (info == null) { selectedId = null; showList(); return; }

        listView.setVisible(false);
        listView.setManaged(false);
        detailScroll.setVisible(true);
        detailScroll.setManaged(true);

        detailView.getChildren().clear();

        Button back = new Button("← 返回列表");
        back.getStyleClass().addAll("jc-btn", "jc-btn-ghost", "jc-btn-sm");
        back.setOnAction(e -> { selectedId = null; showList(); });

        // 头部：大图标 + 名称/版本 + 启停
        Label glyph = new Label(glyphFor(info));
        glyph.getStyleClass().add("plugin-glyph-lg");

        Label name = new Label(info.name());
        name.getStyleClass().add("sec-title");
        HBox nameRow = new HBox(8, name, stateBadge(info.state()));
        nameRow.setAlignment(Pos.CENTER_LEFT);
        Label sub = new Label("id：" + info.id() + " · v" + nz(info.version()) + " · " + capsLine(info));
        sub.getStyleClass().add("plugin-card-meta");
        VBox titleBox = new VBox(5, nameRow, sub);
        HBox.setHgrow(titleBox, Priority.ALWAYS);

        boolean active = info.state() == PluginState.ACTIVE;
        Label stateText = new Label(active ? "已启用" : (info.state() == PluginState.FAILED ? "失败" : "已停用"));
        stateText.getStyleClass().add("sec-hint");
        ToggleSwitch toggle = new ToggleSwitch(active);
        toggle.selectedProperty().addListener((o, was, now) -> onToggle(info, now, toggle));
        HBox headRight = new HBox(8, stateText, toggle);
        headRight.setAlignment(Pos.CENTER_RIGHT);

        HBox head = new HBox(16, glyph, titleBox, headRight);
        head.setAlignment(Pos.CENTER_LEFT);

        Label desc = new Label(nz(info.description()).isBlank() ? "（无描述）" : info.description());
        desc.setWrapText(true);
        desc.getStyleClass().add("plugin-detail-desc");

        detailView.getChildren().addAll(back, head, desc);

        // 所需权限（声明能力）
        Label permTitle = new Label("所需权限");
        permTitle.getStyleClass().add("grp-title");
        detailView.getChildren().add(permTitle);
        if (info.capabilities().isEmpty()) {
            Label none = new Label("该插件未申请任何宿主能力。");
            none.getStyleClass().add("sec-hint");
            detailView.getChildren().add(none);
        } else {
            for (var cap : info.capabilities()) {
                boolean granted = info.granted().contains(cap);
                Label dot = new Label("●");
                dot.setStyle("-fx-text-fill: -jc-primary-500; -fx-font-size: 11px;");
                Label lab = new Label(cap.displayName() + (granted ? "（已授权）" : ""));
                lab.getStyleClass().add("plugin-perm-text");
                HBox row = new HBox(9, dot, lab);
                row.setAlignment(Pos.CENTER_LEFT);
                row.getStyleClass().add("jc-card-sunken");
                row.setPadding(new Insets(8, 12, 8, 12));
                detailView.getChildren().add(row);
            }
        }

        // 暴露的技能 / 工具
        if (!info.skills().isEmpty()) detailView.getChildren().add(buildExposed("提供的技能", info.skills()));
        if (!info.tools().isEmpty()) detailView.getChildren().add(buildExposed("提供的工具", info.tools()));
        if (active && info.skills().isEmpty() && info.tools().isEmpty()) {
            Label none = new Label("该插件未对外暴露技能或工具。");
            none.getStyleClass().add("sec-hint");
            detailView.getChildren().add(none);
        } else if (!active) {
            Label hint = new Label("启用后此处显示插件对外暴露的技能与工具。");
            hint.getStyleClass().add("sec-hint");
            hint.setWrapText(true);
            detailView.getChildren().add(hint);
        }

        // 配置表单
        if (!info.config().isEmpty()) detailView.getChildren().add(buildConfigForm(info));

        // 失败原因
        if (info.state() == PluginState.FAILED && !nz(info.error()).isEmpty()) {
            Label err = new Label("失败原因：" + info.error());
            err.setWrapText(true);
            err.getStyleClass().add("sec-hint");
            err.setStyle("-fx-text-fill: #EF4444;");
            detailView.getChildren().add(err);
        }

        // 底部操作：打开目录 / 卸载
        detailView.getChildren().add(new Separator());
        Button openDir = new Button("打开插件目录");
        openDir.getStyleClass().addAll("jc-btn", "jc-btn-ghost", "jc-btn-sm");
        openDir.setOnAction(e -> openPluginsDir());
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Button uninstall = new Button("卸载");
        uninstall.getStyleClass().addAll("jc-btn", "jc-btn-danger", "jc-btn-sm");
        uninstall.setOnAction(e -> onUninstall(info));
        HBox ops = new HBox(10, openDir, sp, uninstall);
        ops.setAlignment(Pos.CENTER_LEFT);
        detailView.getChildren().add(ops);
    }

    private VBox buildExposed(String title, List<PluginInfo.NamedItem> items) {
        Label t = new Label(title + "（" + items.size() + "）");
        t.getStyleClass().add("grp-title");
        VBox box = new VBox(6, t);
        for (var it : items) {
            Label nm = new Label("• " + it.name());
            nm.getStyleClass().add("plugin-perm-text");
            Label d = new Label(it.description() == null ? "" : it.description());
            d.getStyleClass().add("sec-hint");
            d.setWrapText(true);
            d.setPadding(new Insets(0, 0, 2, 14));
            box.getChildren().addAll(nm, d);
        }
        return box;
    }

    private VBox buildConfigForm(PluginInfo info) {
        Label t = new Label("插件配置");
        t.getStyleClass().add("grp-title");
        VBox box = new VBox(8, t);

        Map<String, String> current = manager.getConfig(info.id());
        Map<String, TextInputControl> inputs = new LinkedHashMap<>();
        for (PluginDescriptor.ConfigField f : info.config()) {
            TextInputControl input = f.secret() ? new PasswordField() : new TextField();
            ((TextField) input).setPromptText(f.key());
            input.setText(current.getOrDefault(f.key(), ""));
            input.getStyleClass().add("settings-field");
            Label lab = new Label(f.label() + (f.secret() ? "（加密存储）" : ""));
            lab.getStyleClass().add("jc-stat-label");
            box.getChildren().add(new VBox(4, lab, input));
            inputs.put(f.key(), input);
        }
        Button save = new Button("保存配置");
        save.getStyleClass().addAll("jc-btn", "jc-btn-soft", "jc-btn-sm");
        Label saved = new Label("");
        saved.getStyleClass().add("sec-hint");
        save.setOnAction(e -> {
            Map<String, String> vals = new LinkedHashMap<>();
            inputs.forEach((k, c) -> vals.put(k, c.getText()));
            manager.setConfig(info.id(), vals);
            saved.setText("已保存（已启用的插件需停用后重新启用方生效）");
        });
        HBox saveRow = new HBox(10, save, saved);
        saveRow.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().add(saveRow);
        return box;
    }

    // ==================== 操作 ====================

    private void onToggle(PluginInfo info, boolean enable, ToggleSwitch toggle) {
        if ((info.state() == PluginState.ACTIVE) == enable) return;
        toggle.setDisable(true);
        log.info("用户请求{}插件：{}", enable ? "启用" : "停用", info.id());
        Thread th = new Thread(() -> {
            if (enable) manager.enable(info.id()); else manager.disable(info.id());
            Platform.runLater(this::render);
        }, "plugin-toggle-" + info.id());
        th.setDaemon(true);
        th.start();
    }

    private void onInstallFromFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择插件 jar");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("插件 jar", "*.jar"));
        File f = chooser.showOpenDialog(stage);
        if (f == null) return;
        Thread th = new Thread(() -> {
            String id = manager.installFromFile(f.toPath());
            Platform.runLater(() -> {
                if (id != null) { tab = "installed"; if (installedTab != null) installedTab.setSelected(true); selectedId = id; }
                else UIHelper.createConfirmAlert("安装失败",
                        "无法从该 jar 安装插件：descriptor 非法或与宿主不兼容。", stage).show();
                render();
            });
        }, "plugin-install");
        th.setDaemon(true);
        th.start();
    }

    private void onUninstall(PluginInfo info) {
        Alert alert = UIHelper.createConfirmAlert("确认卸载",
                "确定要卸载插件「" + info.name() + "」吗？将停用并删除其在 plugins/ 下的目录。", stage);
        alert.showAndWait().ifPresent(r -> {
            if (r == ButtonType.OK) {
                Thread th = new Thread(() -> {
                    manager.uninstall(info.id());
                    Platform.runLater(() -> { selectedId = null; render(); });
                }, "plugin-uninstall-" + info.id());
                th.setDaemon(true);
                th.start();
            }
        });
    }

    private void openPluginsDir() {
        File dir = manager.pluginsDir().toFile();
        try {
            if (java.awt.Desktop.isDesktopSupported() && dir.exists()) {
                java.awt.Desktop.getDesktop().open(dir);
            }
        } catch (Exception e) {
            log.warn("打开插件目录失败：{}", e.toString());
        }
    }

    // ==================== 小工具 ====================

    private Label stateBadge(PluginState state) {
        Label b = new Label(switch (state) {
            case ACTIVE -> "运行中";
            case FAILED -> "失败";
            case STOPPED -> "已停用";
            default -> "未启用";
        });
        b.getStyleClass().add("jc-badge");
        b.getStyleClass().add(switch (state) {
            case ACTIVE -> "jc-badge-running";
            case FAILED -> "jc-badge-failed";
            default -> "jc-badge-stopped";
        });
        b.setStyle("-fx-font-size:9.5px; -fx-padding:1 7 1 7;");
        return b;
    }

    /** 取一个稳定的字符做图标（无 descriptor 图标字段时由名称首字派生）。 */
    private String glyphFor(PluginInfo info) {
        String n = info.name();
        if (n == null || n.isBlank()) return "⧉";
        int cp = n.codePointAt(0);
        return new String(Character.toChars(cp));
    }

    private String capsLine(PluginInfo info) {
        int caps = info.capabilities().size();
        int tools = info.tools().size();
        if (info.state() == PluginState.ACTIVE && tools > 0) return tools + " 个工具";
        return caps == 0 ? "无需授权" : caps + " 项能力";
    }

    private String nz(String s) { return s == null ? "" : s; }

    private void applyStylesheets(Scene scene) {
        addCss(scene, "/css/chat.css");
        addCss(scene, "/css/controls.css");
        addCss(scene, "/css/plugins.css");
    }

    private void addCss(Scene scene, String path) {
        var url = getClass().getResource(path);
        if (url != null) scene.getStylesheets().add(url.toExternalForm());
    }
}
