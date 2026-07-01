package com.javaclaw.ui.javafx.knowledge;

import com.javaclaw.agent.expert.KnowledgeExpert;
import com.javaclaw.agent.expert.KnowledgeExpert.KnowledgeHit;
import com.javaclaw.agent.expert.KnowledgeExpert.Scope;
import com.javaclaw.agent.model.ToolResponse;
import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.app.UIHelper;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.config.WorkspaceManager;
import com.javaclaw.ui.javafx.control.ToggleSwitch;
import com.javaclaw.ui.javafx.control.WindowToast;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.Set;

/**
 * 知识库中心（全窗口视图，按设计稿 知识库中心.dc.html 实现）
 *
 * <p>左导航（浏览范围：全部 / 工作区 / 全局；工具：检索测试 / 索引设置）+ 右主区三视图。
 * 文档视图支持逐篇「参与检索」开关（持久化，默认全部启用）、导入、详情抽屉与删除；
 * 检索测试不进入对话、仅验证召回质量；索引设置只读展示嵌入模型并跳转「设置 › 嵌入模型」、
 * 调分块参数、重建索引、清空知识库。嵌入向量模型本身已迁移到设置统一配置。</p>
 *
 * @author JavaClaw
 */
public class KnowledgeCenterView {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeCenterView.class);

    /** 单文件最大导入大小（50MB），与旧实现一致 */
    private static final long MAX_IMPORT_FILE_SIZE = 50 * 1024 * 1024;

    private final Stage stage;
    private final KnowledgeExpert expert;
    private final UserInteractionPort interaction;
    private final Runnable onConfigChanged;
    private final Runnable onOpenModelSettings;
    /** 窗口关闭回调（供调用方刷新顶栏等）。 */
    private Runnable onHidden;

    /** 通用窗内 Toast 浮层；打开期间接管端口 toastHandler（见构造函数 bindToPort）。 */
    private final WindowToast windowToast = new WindowToast();

    // ---- 视图状态 ----
    private String view = "docs";          // docs / search / settings
    private String scope = "all";          // all / workspace / global
    private String selectedDoc = null;     // 详情抽屉选中文档
    private String filter = "";            // 文档筛选
    private boolean importOpen = false;     // 导入气泡展开
    private boolean chunkParamsDirty = false; // 分块参数变更（关闭时触发服务重建）

    // ---- 容器引用 ----
    private VBox leftRail;
    private StackPane mainPane;

    public KnowledgeCenterView(Stage owner, KnowledgeExpert expert, UserInteractionPort interaction,
                               Runnable onConfigChanged, Runnable onOpenModelSettings) {
        this.expert = expert;
        this.interaction = interaction;
        this.onConfigChanged = onConfigChanged;
        this.onOpenModelSettings = onOpenModelSettings;
        this.stage = new Stage();
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initOwner(owner);
        stage.setTitle("知识库中心");
        stage.setResizable(true);
        stage.setMinWidth(1040);
        stage.setMinHeight(680);
        buildUI();
        // 打开期间让窗内浮层接管端口的 Toast 渲染器（模态子窗置顶，主窗横幅会被遮挡），隐藏时自动还原
        windowToast.bindToPort(stage, interaction);
        stage.setOnHidden(e -> {
            if (chunkParamsDirty && onConfigChanged != null) {
                log.info("分块参数已变更，关闭知识库中心后重建知识服务");
                onConfigChanged.run();
            }
            if (onHidden != null) onHidden.run();
        });
    }

    /** 注册窗口关闭回调（如刷新顶栏知识库菜单）。 */
    public void setOnHidden(Runnable onHidden) {
        this.onHidden = onHidden;
    }

    // ==================== 总体骨架 ====================

    private void buildUI() {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("kc-root");
        root.setTop(buildHeader());

        leftRail = new VBox();
        leftRail.getStyleClass().add("kc-rail");
        leftRail.setPrefWidth(252);
        leftRail.setMinWidth(252);

        mainPane = new StackPane();
        mainPane.getStyleClass().add("kc-main");

        HBox body = new HBox(leftRail, mainPane);
        HBox.setHgrow(mainPane, Priority.ALWAYS);
        root.setCenter(body);

        refreshRail();
        refreshMain();

        // 根容器叠一层窗内 Toast 浮层（底部居中、鼠标穿透，不遮挡交互）
        StackPane sceneRoot = new StackPane(root, windowToast.node());
        Scene scene = new Scene(sceneRoot, 1340, 864);
        addStylesheet(scene, "/css/chat.css");
        addStylesheet(scene, "/css/controls.css");
        addStylesheet(scene, "/css/knowledge-center.css");
        stage.setScene(scene);
    }

    private void addStylesheet(Scene scene, String path) {
        var url = getClass().getResource(path);
        if (url != null) scene.getStylesheets().add(url.toExternalForm());
    }

    // ==================== 顶栏 ====================

    private Region buildHeader() {
        Label logo = new Label("爪");
        logo.getStyleClass().add("kc-logo");
        Label title = new Label("知识库中心");
        title.getStyleClass().add("kc-title");

        Label ragBadge = new Label();
        Region dot = new Region();
        dot.getStyleClass().add("kc-badge-dot");
        HBox badge = new HBox(6, dot, ragBadge);
        badge.setAlignment(Pos.CENTER_LEFT);
        badge.getStyleClass().add("kc-rag-badge");
        if (expert.isRagEnabled()) {
            ragBadge.setText("RAG 已启用");
        } else {
            ragBadge.setText("RAG 未启用");
            badge.getStyleClass().add("kc-rag-badge-off");
        }

        HBox left = new HBox(12, logo, title, badge);
        left.setAlignment(Pos.CENTER_LEFT);

        // 嵌入模型小卡（点击跳转模型设置）
        AgentConfig cfg = AgentConfig.getInstance();
        Region embDot = new Region();
        embDot.getStyleClass().add(expert.isRagEnabled() ? "kc-dot-on" : "kc-dot-off");
        Label embTag = new Label("嵌入");
        embTag.getStyleClass().add("kc-emb-tag");
        Label embName = new Label(shortModel(cfg.getRagEmbeddingModelName()));
        embName.getStyleClass().add("kc-emb-name");
        Label embSep = new Label("·");
        embSep.getStyleClass().add("kc-emb-sep");
        Label embDim = new Label(cfg.getRagEmbeddingDimensions() + "维");
        embDim.getStyleClass().add("kc-emb-dim");
        HBox embChip = new HBox(8, embDot, embTag, embName, embSep, embDim);
        embChip.setAlignment(Pos.CENTER_LEFT);
        embChip.getStyleClass().add("kc-emb-chip");
        embChip.setOnMouseClicked(e -> openModelSettings());
        Tooltip.install(embChip, new Tooltip("前往「设置 › 嵌入模型」配置向量模型"));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(left, spacer, embChip);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("kc-header");
        return header;
    }

    // ==================== 左导航 ====================

    private void refreshRail() {
        leftRail.getChildren().clear();

        // 工作区小卡
        Label wsIcon = new Label("💼");
        wsIcon.getStyleClass().add("kc-ws-icon");
        Label wsCaption = new Label("当前工作区");
        wsCaption.getStyleClass().add("kc-ws-caption");
        Label wsName = new Label(currentWorkspaceName());
        wsName.getStyleClass().add("kc-ws-name");
        VBox wsText = new VBox(1, wsCaption, wsName);
        HBox wsChip = new HBox(9, wsIcon, wsText);
        wsChip.setAlignment(Pos.CENTER_LEFT);
        wsChip.getStyleClass().add("kc-ws-chip");

        int globalCount = expert.getDocumentNames(Scope.GLOBAL).size();
        int wsCount = expert.getDocumentNames(Scope.WORKSPACE).size();
        int allCount = globalCount + wsCount;

        Label scopeGroup = groupLabel("浏览范围");
        VBox scopeItems = new VBox(2,
                railItem("📚", "全部文档", String.valueOf(allCount),
                        view.equals("docs") && scope.equals("all"), () -> setScope("all")),
                railItem("💼", "工作区 · " + currentWorkspaceName(), String.valueOf(wsCount),
                        view.equals("docs") && scope.equals("workspace"), () -> setScope("workspace")),
                railItem("🌐", "全局知识库", String.valueOf(globalCount),
                        view.equals("docs") && scope.equals("global"), () -> setScope("global")));

        Region divider = new Region();
        divider.getStyleClass().add("kc-rail-divider");

        Label toolGroup = groupLabel("工具");
        VBox toolItems = new VBox(2,
                railItem("🔍", "检索测试", null, view.equals("search"), () -> setView("search")),
                railItem("⚙️", "索引设置", null, view.equals("settings"), () -> setView("settings")));

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        // 底部统计
        int totalChunks = expert.getTotalChunkCount();
        int enabled = expert.getEnabledDocCount();
        VBox stats = new VBox(9,
                statRow("文档总数", String.valueOf(allCount), false),
                statRow("向量片段", String.valueOf(totalChunks), false),
                statRow("参与检索", enabled + " 篇", true));
        stats.getStyleClass().add("kc-rail-stats");

        leftRail.getChildren().addAll(wsChip, scopeGroup, scopeItems, divider,
                toolGroup, toolItems, spacer, stats);
    }

    private Label groupLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("kc-rail-group");
        return l;
    }

    private Region railItem(String icon, String label, String count, boolean active, Runnable onClick) {
        Label ic = new Label(icon);
        ic.getStyleClass().add("kc-rail-icon");
        Label lb = new Label(label);
        lb.getStyleClass().add("kc-rail-label");
        HBox.setHgrow(lb, Priority.ALWAYS);
        lb.setMaxWidth(Double.MAX_VALUE);
        HBox row = new HBox(10, ic, lb);
        row.setAlignment(Pos.CENTER_LEFT);
        if (count != null) {
            Label cnt = new Label(count);
            cnt.getStyleClass().add("kc-rail-count");
            row.getChildren().add(cnt);
        }
        row.getStyleClass().add("kc-rail-item");
        if (active) row.getStyleClass().add("kc-rail-item-active");
        row.setOnMouseClicked(e -> onClick.run());
        return row;
    }

    private Region statRow(String label, String value, boolean accent) {
        Label l = new Label(label);
        l.getStyleClass().add("kc-stat-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label v = new Label(value);
        v.getStyleClass().add(accent ? "kc-stat-value-accent" : "kc-stat-value");
        HBox row = new HBox(l, spacer, v);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    // ==================== 主区切换 ====================

    private void setView(String v) {
        this.view = v;
        if (!v.equals("docs")) this.selectedDoc = null;
        refreshRail();
        refreshMain();
    }

    private void setScope(String s) {
        this.view = "docs";
        this.scope = s;
        this.selectedDoc = null;
        this.importOpen = false;
        refreshRail();
        refreshMain();
    }

    private void refreshMain() {
        mainPane.getChildren().clear();
        if (!expert.isRagEnabled()) {
            mainPane.getChildren().add(buildDisabledNotice());
            return;
        }
        Region content = switch (view) {
            case "search" -> buildSearchView();
            case "settings" -> buildSettingsView();
            default -> buildDocsView();
        };
        mainPane.getChildren().add(content);
    }

    private Region buildDisabledNotice() {
        Label icon = new Label("🧬");
        icon.setStyle("-fx-font-size: 40px;");
        Label title = new Label("知识库（RAG）未启用");
        title.getStyleClass().add("kc-empty-title");
        Label desc = new Label("请先在「设置 › 嵌入模型」中开启知识库并配置向量嵌入模型，即可导入文档与检索。");
        desc.getStyleClass().add("kc-empty-desc");
        desc.setWrapText(true);
        desc.setMaxWidth(420);
        desc.setAlignment(Pos.CENTER);
        Button go = new Button("前往嵌入模型设置 →");
        go.getStyleClass().addAll("jc-btn", "jc-btn-primary");
        go.setOnAction(e -> openModelSettings());
        VBox box = new VBox(14, icon, title, desc, go);
        box.setAlignment(Pos.CENTER);
        StackPane.setMargin(box, new Insets(40));
        return box;
    }

    // ==================== 文档视图 ====================

    private Region buildDocsView() {
        // ----- 左侧列表列 -----
        VBox listCol = new VBox();
        HBox.setHgrow(listCol, Priority.ALWAYS);

        // 工具栏
        ScopeMeta meta = scopeMeta();
        Label scopeTitle = new Label(meta.title);
        scopeTitle.getStyleClass().add("kc-pane-title");
        Label scopeSub = new Label(meta.sub);
        scopeSub.getStyleClass().add("kc-pane-sub");
        VBox titleBox = new VBox(3, scopeTitle, scopeSub);
        HBox.setHgrow(titleBox, Priority.ALWAYS);

        TextField filterField = new TextField(filter);
        filterField.setPromptText("筛选文档…");
        filterField.getStyleClass().add("kc-field-input");
        filterField.setPrefWidth(120);
        filterField.textProperty().addListener((o, a, b) -> {
            filter = b == null ? "" : b;
            refreshDocList(listCol);
        });
        HBox filterBox = searchFieldContainer(filterField, "kc-filter-box");

        Button importBtn = new Button("＋  导入");
        importBtn.getStyleClass().addAll("jc-btn", "jc-btn-primary");
        importBtn.setOnAction(e -> { importOpen = !importOpen; refreshDocList(listCol); });

        HBox toolbar = new HBox(10, titleBox, filterBox, importBtn);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("kc-toolbar");

        listCol.getChildren().add(toolbar);
        refreshDocList(listCol);

        // ----- 详情抽屉 -----
        HBox docsRow = new HBox(listCol);
        if (selectedDoc != null && documentExists(selectedDoc)) {
            docsRow.getChildren().add(buildDetailDrawer(listCol));
        }
        return docsRow;
    }

    /** 刷新文档列表列：保留工具栏（首个子节点），重建其下内容。 */
    private void refreshDocList(VBox listCol) {
        // 移除工具栏以外的旧内容
        if (listCol.getChildren().size() > 1) {
            listCol.getChildren().remove(1, listCol.getChildren().size());
        }

        VBox below = new VBox();
        VBox.setVgrow(below, Priority.ALWAYS);

        // 导入气泡
        if (importOpen) {
            Region popover = buildImportPopover();
            VBox.setMargin(popover, new Insets(0, 24, 12, 24));
            below.getChildren().add(popover);
        }

        // 列头
        below.getChildren().add(buildColumnHeader());

        // 列表
        VBox listBox = new VBox(2);
        listBox.getStyleClass().add("kc-doc-list");
        List<String> docs = visibleDocs();
        if (docs.isEmpty()) {
            Label empty = new Label("该范围下暂无文档");
            empty.getStyleClass().add("kc-doc-empty");
            empty.setMaxWidth(Double.MAX_VALUE);
            empty.setAlignment(Pos.CENTER);
            listBox.getChildren().add(empty);
        } else {
            for (String name : docs) listBox.getChildren().add(buildDocRow(name, listCol));
        }

        // 批量提示
        listBox.getChildren().add(buildBulkHint(listCol));

        ScrollPane sp = new ScrollPane(listBox);
        sp.setFitToWidth(true);
        sp.getStyleClass().add("kc-scroll");
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(sp, Priority.ALWAYS);
        below.getChildren().add(sp);

        listCol.getChildren().add(below);
    }

    private Region buildImportPopover() {
        Label t = new Label("导入到 " + scopeMeta().shortTitle);
        t.getStyleClass().add("kc-import-title");

        VBox file = importCard("📄", "选择文件", "PDF · MD · TXT · CSV", this::onImportFiles);
        VBox folder = importCard("📁", "批量导入目录", "递归扫描支持的格式", this::onImportFolder);
        VBox text = importCard("✏️", "粘贴文本", "手动录入一段内容", this::onImportText);
        HBox cards = new HBox(10, file, folder, text);
        HBox.setHgrow(file, Priority.ALWAYS);
        HBox.setHgrow(folder, Priority.ALWAYS);
        HBox.setHgrow(text, Priority.ALWAYS);

        VBox box = new VBox(10, t, cards);
        box.getStyleClass().add("kc-import-popover");
        return box;
    }

    private VBox importCard(String icon, String title, String sub, Runnable onClick) {
        Label ic = new Label(icon);
        ic.setStyle("-fx-font-size: 16px;");
        Label t = new Label(title);
        t.getStyleClass().add("kc-import-card-title");
        Label s = new Label(sub);
        s.getStyleClass().add("kc-import-card-sub");
        VBox card = new VBox(6, ic, t, s);
        card.getStyleClass().add("kc-import-card");
        card.setOnMouseClicked(e -> onClick.run());
        return card;
    }

    private Region buildColumnHeader() {
        Label retr = colHead("检索", 40, Pos.CENTER);
        Label doc = colHead("文档", -1, Pos.CENTER_LEFT);
        HBox.setHgrow(doc, Priority.ALWAYS);
        doc.setMaxWidth(Double.MAX_VALUE);
        Label chunks = colHead("片段", 74, Pos.CENTER_RIGHT);
        Label time = colHead("导入时间", 96, Pos.CENTER_LEFT);
        Region tail = new Region();
        tail.setMinWidth(24);
        tail.setPrefWidth(24);
        HBox row = new HBox(14, retr, doc, chunks, time, tail);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("kc-col-header");
        return row;
    }

    private Label colHead(String text, double width, Pos align) {
        Label l = new Label(text);
        l.getStyleClass().add("kc-col-head");
        if (width > 0) { l.setMinWidth(width); l.setPrefWidth(width); }
        l.setAlignment(align);
        l.setMaxWidth(Double.MAX_VALUE);
        return l;
    }

    private Region buildDocRow(String name, VBox listCol) {
        boolean enabled = expert.isDocEnabled(name);
        Scope sc = expert.getDocumentScope(name);
        int chunks = expert.getDocumentChunkCount(name);
        String time = expert.getDocumentImportTime(name);

        // 开关（复用通用 ToggleSwitch 控件）
        ToggleSwitch sw = new ToggleSwitch(enabled);
        sw.selectedProperty().addListener((o, a, on) -> {
            expert.setDocEnabled(name, on);
            refreshRail();
            refreshDocList(listCol);
        });
        HBox swCell = new HBox(sw);
        swCell.setAlignment(Pos.CENTER);
        swCell.setMinWidth(40);
        swCell.setPrefWidth(40);
        // 拦截点击冒泡，避免切换开关时误触发所在行的选中
        swCell.setOnMouseClicked(Event::consume);

        // 类型徽标 + 名称
        Label typeBadge = new Label(ext(name));
        typeBadge.getStyleClass().addAll("kc-type-badge", typeClass(name));
        Label nameLabel = new Label(name);
        nameLabel.getStyleClass().add("kc-doc-name");
        nameLabel.setMaxWidth(280);
        Label scopeTag = scopeTag(sc);
        HBox nameRow = new HBox(8, nameLabel, scopeTag);
        nameRow.setAlignment(Pos.CENTER_LEFT);
        VBox nameBox = new VBox(2, nameRow);
        String desc = firstChunkSnippet(name);
        if (desc != null && !desc.isBlank()) {
            Label descLabel = new Label(desc);
            descLabel.getStyleClass().add("kc-doc-desc");
            descLabel.setMaxWidth(340);
            nameBox.getChildren().add(descLabel);
        }
        HBox info = new HBox(11, typeBadge, nameBox);
        info.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(info, Priority.ALWAYS);

        Label chunksLabel = new Label(String.valueOf(chunks));
        chunksLabel.getStyleClass().add("kc-doc-chunks");
        chunksLabel.setMinWidth(74);
        chunksLabel.setPrefWidth(74);
        chunksLabel.setAlignment(Pos.CENTER_RIGHT);

        Label timeLabel = new Label(time == null ? "—" : shortDate(time));
        timeLabel.getStyleClass().add("kc-doc-time");
        timeLabel.setMinWidth(96);
        timeLabel.setPrefWidth(96);

        Label arrow = new Label("›");
        arrow.getStyleClass().add("kc-doc-arrow");
        arrow.setMinWidth(24);
        arrow.setPrefWidth(24);
        arrow.setAlignment(Pos.CENTER);

        HBox row = new HBox(14, swCell, info, chunksLabel, timeLabel, arrow);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("kc-doc-row");
        if (name.equals(selectedDoc)) row.getStyleClass().add("kc-doc-row-selected");
        row.setOnMouseClicked(e -> {
            selectedDoc = name.equals(selectedDoc) ? null : name;
            refreshMain();
        });
        return row;
    }

    private Region buildBulkHint(VBox listCol) {
        Label hint = new Label("勾选的文档参与对话检索 · 默认全部启用，可在此统一管理（对话中仍可临时调整）");
        hint.getStyleClass().add("kc-bulk-text");
        hint.setWrapText(true);
        HBox.setHgrow(hint, Priority.ALWAYS);
        hint.setMaxWidth(Double.MAX_VALUE);
        Hyperlink enableAll = bulkLink("全部启用", () -> {
            expert.setAllEnabled(true, scopeForBulk());
            refreshRail();
            refreshDocList(listCol);
        });
        Label sep = new Label("|");
        sep.getStyleClass().add("kc-bulk-sep");
        Hyperlink disableAll = bulkLink("全部停用", () -> {
            expert.setAllEnabled(false, scopeForBulk());
            refreshRail();
            refreshDocList(listCol);
        });
        HBox box = new HBox(8, hint, enableAll, sep, disableAll);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("kc-bulk-hint");
        return box;
    }

    private Hyperlink bulkLink(String text, Runnable onClick) {
        Hyperlink h = new Hyperlink(text);
        h.getStyleClass().add("kc-bulk-link");
        h.setOnAction(e -> onClick.run());
        return h;
    }

    /** 批量操作作用域：「全部」视图作用于全部文档，否则仅当前作用域。 */
    private Scope scopeForBulk() {
        return switch (scope) {
            case "workspace" -> Scope.WORKSPACE;
            case "global" -> Scope.GLOBAL;
            default -> null;
        };
    }

    // ==================== 详情抽屉 ====================

    private Region buildDetailDrawer(VBox listCol) {
        String name = selectedDoc;
        Scope sc = expert.getDocumentScope(name);
        boolean enabled = expert.isDocEnabled(name);
        int chunks = expert.getDocumentChunkCount(name);
        String time = expert.getDocumentImportTime(name);

        // 头部
        Label typeBadge = new Label(ext(name));
        typeBadge.getStyleClass().addAll("kc-type-badge", typeClass(name));
        Label scopeTag = scopeTag(sc);
        HBox tags = new HBox(9, typeBadge, scopeTag);
        tags.setAlignment(Pos.CENTER_LEFT);
        Label nameLabel = new Label(name);
        nameLabel.getStyleClass().add("kc-detail-name");
        nameLabel.setWrapText(true);
        VBox headLeft = new VBox(6, tags, nameLabel);
        HBox.setHgrow(headLeft, Priority.ALWAYS);
        Label close = new Label("×");
        close.getStyleClass().add("kc-detail-close");
        close.setOnMouseClicked(e -> { selectedDoc = null; refreshMain(); });
        HBox head = new HBox(10, headLeft, close);
        head.getStyleClass().add("kc-detail-head");

        // 元信息网格
        VBox chunkCard = metaCard("向量片段", String.valueOf(chunks), "kc-meta-value-lg");
        VBox stateCard = metaCard("参与检索", enabled ? "已启用" : "已停用",
                enabled ? "kc-meta-value-accent" : "kc-meta-value-muted");
        HBox metaRow = new HBox(12, chunkCard, stateCard);
        HBox.setHgrow(chunkCard, Priority.ALWAYS);
        HBox.setHgrow(stateCard, Priority.ALWAYS);
        VBox timeCard = metaCard("导入时间", time == null ? "—" : time, "kc-meta-value-time");

        Label previewLabel = new Label("片段预览");
        previewLabel.getStyleClass().add("kc-detail-section");
        VBox previews = new VBox(9);
        List<String> chunkTexts = expert.getDocumentChunkPreviews(name, 3);
        if (chunkTexts.isEmpty()) {
            Label none = new Label("（无可预览的片段）");
            none.getStyleClass().add("kc-doc-empty");
            previews.getChildren().add(none);
        } else {
            int i = 1;
            for (String txt : chunkTexts) previews.getChildren().add(previewCard("#" + pad(i++), txt));
        }

        VBox content = new VBox(20, metaRow, timeCard, previewLabel, previews);
        content.setPadding(new Insets(18, 20, 18, 20));
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.getStyleClass().add("kc-scroll");
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(sp, Priority.ALWAYS);

        // 动作栏
        Button toggleBtn = new Button(enabled ? "停用检索" : "启用检索");
        toggleBtn.getStyleClass().addAll("jc-btn", "jc-btn-soft");
        toggleBtn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(toggleBtn, Priority.ALWAYS);
        toggleBtn.setOnAction(e -> {
            expert.setDocEnabled(name, !enabled);
            refreshRail();
            refreshMain();
        });
        Button deleteBtn = new Button("删除");
        deleteBtn.getStyleClass().addAll("jc-btn", "jc-btn-danger-soft");
        deleteBtn.setOnAction(e -> onDeleteDoc(name));
        HBox actions = new HBox(10, toggleBtn, deleteBtn);
        actions.getStyleClass().add("kc-detail-actions");

        VBox drawer = new VBox(head, sp, actions);
        drawer.getStyleClass().add("kc-detail-drawer");
        drawer.setPrefWidth(368);
        drawer.setMinWidth(368);
        return drawer;
    }

    private VBox metaCard(String label, String value, boolean accent) {
        return metaCard(label, value, accent ? "kc-meta-value-accent" : "kc-meta-value");
    }

    private VBox metaCard(String label, String value, String valueClass) {
        Label l = new Label(label);
        l.getStyleClass().add("kc-meta-label");
        Label v = new Label(value);
        v.getStyleClass().add(valueClass);
        VBox card = new VBox(3, l, v);
        card.getStyleClass().add("kc-meta-card");
        return card;
    }

    private Region previewCard(String id, String text) {
        Label idLabel = new Label(id);
        idLabel.getStyleClass().add("kc-preview-id");
        Label txt = new Label(text);
        txt.getStyleClass().add("kc-preview-text");
        txt.setWrapText(true);
        VBox card = new VBox(5, idLabel, txt);
        card.getStyleClass().add("kc-preview-card");
        return card;
    }

    // ==================== 检索测试视图 ====================

    private Region buildSearchView() {
        Label title = new Label("检索测试");
        title.getStyleClass().add("kc-pane-title");
        Label sub = new Label("输入一句话，预览它会命中哪些知识片段 — 不会进入对话，仅用于验证召回质量。");
        sub.getStyleClass().add("kc-pane-sub");

        TextField queryField = new TextField();
        queryField.setPromptText("例如：切换嵌入模型后要做什么？");
        queryField.getStyleClass().addAll("kc-field-input", "kc-search-input");
        HBox searchBox = searchFieldContainer(queryField, "kc-search-box");
        HBox.setHgrow(searchBox, Priority.ALWAYS);
        Button runBtn = new Button("检索");
        runBtn.getStyleClass().addAll("jc-btn", "jc-btn-primary", "kc-search-btn");
        HBox searchRow = new HBox(10, searchBox, runBtn);

        int enabled = expert.getEnabledDocCount();
        int topK = AgentConfig.getInstance().getRagRetrieveLimit();
        Label rangeInfo = new Label("检索范围  ");
        rangeInfo.getStyleClass().add("kc-search-meta");
        Label enabledInfo = new Label(enabled + " 篇已启用文档");
        enabledInfo.getStyleClass().add("kc-search-meta-accent");
        Label metaTail = new Label(" · Top-K = " + topK + " · 余弦相似度");
        metaTail.getStyleClass().add("kc-search-meta");
        HBox rangeRow = new HBox(rangeInfo, enabledInfo, metaTail);
        rangeRow.setAlignment(Pos.CENTER_LEFT);

        VBox resultsBox = new VBox(12);
        VBox.setVgrow(resultsBox, Priority.ALWAYS);
        Region placeholder = searchPlaceholder();
        resultsBox.getChildren().add(placeholder);

        Runnable run = () -> {
            String q = queryField.getText();
            if (q == null || q.isBlank()) return;
            runBtn.setDisable(true);
            resultsBox.getChildren().setAll(centeredHint("检索中…"));
            new Thread(() -> {
                List<KnowledgeHit> hits = expert.searchTest(q, topK);
                Platform.runLater(() -> {
                    runBtn.setDisable(false);
                    renderSearchResults(resultsBox, q, hits);
                });
            }, "kc-search").start();
        };
        runBtn.setOnAction(e -> run.run());
        queryField.setOnAction(e -> run.run());

        VBox inner = new VBox(14, title, sub, searchRow, rangeRow, resultsBox);
        inner.getStyleClass().add("kc-centered-col");
        inner.setMaxWidth(760);
        VBox.setVgrow(resultsBox, Priority.ALWAYS);

        ScrollPane sp = new ScrollPane(wrapCentered(inner));
        sp.setFitToWidth(true);
        sp.getStyleClass().add("kc-scroll");
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return sp;
    }

    private void renderSearchResults(VBox box, String query, List<KnowledgeHit> hits) {
        box.getChildren().clear();
        if (hits.isEmpty()) {
            box.getChildren().add(centeredHint("未命中任何片段 · 试试更宽泛的查询，或确认已有启用的文档"));
            return;
        }
        Label summary = new Label("命中 " + hits.size() + " 个片段 · 查询「" + query + "」");
        summary.getStyleClass().add("kc-result-summary");
        box.getChildren().add(summary);
        for (KnowledgeHit h : hits) box.getChildren().add(buildResultCard(h, query));
    }

    private Region buildResultCard(KnowledgeHit h, String query) {
        Label doc = new Label(h.docName());
        doc.getStyleClass().add("kc-result-doc");
        Label tag = scopeTag("GLOBAL".equals(h.scope()) ? Scope.GLOBAL : Scope.WORKSPACE);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Region barFill = new Region();
        barFill.getStyleClass().add("kc-score-fill");
        double pct = Math.max(0.04, Math.min(1.0, h.score()));
        barFill.setMinWidth(80 * pct);
        barFill.setPrefWidth(80 * pct);
        barFill.setMaxWidth(80 * pct);
        StackPane bar = new StackPane(barFill);
        StackPane.setAlignment(barFill, Pos.CENTER_LEFT);
        bar.getStyleClass().add("kc-score-bar");
        bar.setMinWidth(80);
        bar.setPrefWidth(80);
        Label score = new Label(String.format("%.2f", h.score()));
        score.getStyleClass().add("kc-result-score");
        HBox scoreBox = new HBox(7, bar, score);
        scoreBox.setAlignment(Pos.CENTER_RIGHT);

        HBox header = new HBox(9, doc, tag, spacer, scoreBox);
        header.setAlignment(Pos.CENTER_LEFT);

        TextFlow snippet = highlightSnippet(snippet(h.content()), query);
        snippet.getStyleClass().add("kc-result-snippet");

        VBox card = new VBox(9, header, snippet);
        card.getStyleClass().add("kc-result-card");
        return card;
    }

    /** 把片段中命中的查询词高亮（primary 底 + 深绿字），其余为正文。 */
    private TextFlow highlightSnippet(String text, String query) {
        TextFlow flow = new TextFlow();
        if (text == null) text = "";
        List<int[]> ranges = new java.util.ArrayList<>();
        String lower = text.toLowerCase();
        for (String term : queryTerms(query)) {
            String t = term.toLowerCase();
            int from = 0, idx;
            while (!t.isEmpty() && (idx = lower.indexOf(t, from)) >= 0) {
                ranges.add(new int[]{idx, idx + t.length()});
                from = idx + t.length();
            }
        }
        ranges.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> merged = new java.util.ArrayList<>();
        for (int[] r : ranges) {
            if (!merged.isEmpty() && r[0] <= merged.get(merged.size() - 1)[1]) {
                merged.get(merged.size() - 1)[1] = Math.max(merged.get(merged.size() - 1)[1], r[1]);
            } else {
                merged.add(r);
            }
        }
        int pos = 0;
        for (int[] r : merged) {
            if (r[0] > pos) flow.getChildren().add(plainText(text.substring(pos, r[0])));
            flow.getChildren().add(hitText(text.substring(r[0], r[1])));
            pos = r[1];
        }
        if (pos < text.length()) flow.getChildren().add(plainText(text.substring(pos)));
        if (flow.getChildren().isEmpty()) flow.getChildren().add(plainText(text));
        return flow;
    }

    private Text plainText(String s) {
        Text t = new Text(s);
        t.getStyleClass().add("kc-snippet-text");
        return t;
    }

    private Label hitText(String s) {
        Label l = new Label(s);
        l.getStyleClass().add("kc-snippet-hit");
        return l;
    }

    /** 查询词切分：按空白拆，保留长度≥2 的词；否则整句作为一个词。 */
    private List<String> queryTerms(String query) {
        List<String> out = new java.util.ArrayList<>();
        if (query == null || query.isBlank()) return out;
        for (String s : query.trim().split("\\s+")) {
            if (s.length() >= 2) out.add(s);
        }
        if (out.isEmpty()) out.add(query.trim());
        return out;
    }

    private Region searchPlaceholder() {
        Label icon = new Label("🔍");
        icon.setStyle("-fx-font-size: 34px;");
        Label hint = new Label("输入查询并点击「检索」，查看会命中的知识片段");
        hint.getStyleClass().add("kc-empty-desc");
        VBox box = new VBox(10, icon, hint);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(48, 0, 0, 0));
        return box;
    }

    private Region centeredHint(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("kc-empty-desc");
        l.setWrapText(true);
        VBox box = new VBox(l);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(40, 0, 0, 0));
        return box;
    }

    // ==================== 索引设置视图 ====================

    private Region buildSettingsView() {
        AgentConfig cfg = AgentConfig.getInstance();

        Label title = new Label("索引设置");
        title.getStyleClass().add("kc-pane-title");
        Label sub = new Label("控制文档如何被切分并建立向量索引。嵌入模型本身在「设置 › 嵌入模型」中统一配置。");
        sub.getStyleClass().add("kc-pane-sub");
        VBox titleBox = new VBox(3, title, sub);

        // 嵌入模型只读卡
        VBox embCard = buildEmbeddingReadonlyCard(cfg);

        // 分块参数
        VBox chunkCard = buildChunkParamsCard(cfg);

        // 危险区
        VBox danger = buildDangerZone();

        VBox inner = new VBox(22, titleBox, embCard, chunkCard, danger);
        inner.getStyleClass().add("kc-centered-col");
        inner.setMaxWidth(760);

        ScrollPane sp = new ScrollPane(wrapCentered(inner));
        sp.setFitToWidth(true);
        sp.getStyleClass().add("kc-scroll");
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return sp;
    }

    private VBox buildEmbeddingReadonlyCard(AgentConfig cfg) {
        Label icon = new Label("🧬");
        icon.getStyleClass().add("kc-emb-card-icon");

        Label name = new Label("嵌入模型");
        name.getStyleClass().add("kc-emb-card-title");
        Region dot = new Region();
        dot.getStyleClass().add("kc-dot-on");
        Label connected = new Label("已连接");
        connected.getStyleClass().add("kc-emb-connected");
        HBox titleRow = new HBox(8, name, dot, connected);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Label model = new Label(blankTo(cfg.getRagEmbeddingModelName(), "未配置"));
        model.getStyleClass().add("kc-emb-card-model");
        Label provider = new Label(cfg.getRagEmbeddingProvider() + " · " + blankTo(cfg.getRagEmbeddingBaseUrl(), "—"));
        provider.getStyleClass().add("kc-emb-card-provider");
        VBox infoBox = new VBox(3, titleRow, model, provider);
        HBox.setHgrow(infoBox, Priority.ALWAYS);

        HBox infoRow = new HBox(12, icon, infoBox);
        infoRow.setAlignment(Pos.CENTER_LEFT);

        Button goBtn = new Button("前往模型设置 →");
        goBtn.getStyleClass().addAll("jc-btn", "jc-btn-soft", "kc-emb-go");
        goBtn.setOnAction(e -> openModelSettings());

        HBox top = new HBox(14, infoRow, goBtn);
        top.setAlignment(Pos.CENTER_LEFT);

        // 底部三联
        VBox dim = miniStat("向量维度", String.valueOf(cfg.getRagEmbeddingDimensions()), true);
        VBox dist = miniStat("距离度量", "COSINE", false);
        Label note = new Label("维度由嵌入模型决定，无法在此修改 · 切换模型后需重建索引");
        note.getStyleClass().add("kc-emb-note");
        note.setWrapText(true);
        HBox.setHgrow(note, Priority.ALWAYS);
        note.setMaxWidth(Double.MAX_VALUE);
        HBox bottom = new HBox(10, dim, dist, note);
        bottom.setAlignment(Pos.CENTER_LEFT);
        bottom.getStyleClass().add("kc-emb-card-bottom");

        VBox card = new VBox(14, top, bottom);
        card.getStyleClass().add("kc-emb-card");
        return card;
    }

    private VBox miniStat(String label, String value, boolean mono) {
        Label l = new Label(label);
        l.getStyleClass().add("kc-meta-label");
        Label v = new Label(value);
        v.getStyleClass().add(mono ? "kc-mini-stat-mono" : "kc-mini-stat");
        VBox box = new VBox(2, l, v);
        return box;
    }

    private VBox buildChunkParamsCard(AgentConfig cfg) {
        // 分块大小
        Label chunkTitle = new Label("分块大小");
        chunkTitle.getStyleClass().add("kc-param-title");
        Label chunkSub = new Label("每个片段的最大字符数");
        chunkSub.getStyleClass().add("kc-param-sub");
        HBox chunkHead = new HBox(8, chunkTitle, chunkSub);
        chunkHead.setAlignment(Pos.BASELINE_LEFT);
        HBox.setHgrow(chunkHead, Priority.ALWAYS);
        Label chunkVal = new Label(cfg.getRagChunkSize() + " 字符");
        chunkVal.getStyleClass().add("kc-param-value");
        HBox chunkRow = new HBox(chunkHead, chunkVal);
        chunkRow.setAlignment(Pos.CENTER_LEFT);
        Slider chunkSlider = new Slider(128, 1024, clamp(cfg.getRagChunkSize(), 128, 1024));
        chunkSlider.setBlockIncrement(64);
        chunkSlider.setMajorTickUnit(64);
        chunkSlider.setSnapToTicks(true);
        chunkSlider.getStyleClass().add("kc-slider");
        Region chunkScale = scaleLabel("128", "推荐 512", "1024");

        // 片段重叠
        Label overlapTitle = new Label("片段重叠");
        overlapTitle.getStyleClass().add("kc-param-title");
        Label overlapSub = new Label("相邻片段共享的字符数，保持上下文连续");
        overlapSub.getStyleClass().add("kc-param-sub");
        HBox overlapHead = new HBox(8, overlapTitle, overlapSub);
        overlapHead.setAlignment(Pos.BASELINE_LEFT);
        HBox.setHgrow(overlapHead, Priority.ALWAYS);
        Label overlapVal = new Label("");
        overlapVal.getStyleClass().add("kc-param-value");
        HBox overlapRow = new HBox(overlapHead, overlapVal);
        overlapRow.setAlignment(Pos.CENTER_LEFT);
        Slider overlapSlider = new Slider(0, 256, clamp(cfg.getRagChunkOverlap(), 0, 256));
        overlapSlider.setBlockIncrement(8);
        overlapSlider.setMajorTickUnit(8);
        overlapSlider.setSnapToTicks(true);
        overlapSlider.getStyleClass().add("kc-slider");
        Region overlapScale = scaleLabel("0", "推荐 10%~20%", "256");

        // 值联动 + 持久化
        Runnable updateOverlapText = () -> {
            int c = (int) chunkSlider.getValue();
            int o = (int) overlapSlider.getValue();
            int pct = c > 0 ? Math.round(o * 100f / c) : 0;
            overlapVal.setText(o + " 字符 · " + pct + "%");
        };
        chunkSlider.valueProperty().addListener((ob, a, b) -> {
            chunkVal.setText(b.intValue() + " 字符");
            updateOverlapText.run();
        });
        overlapSlider.valueProperty().addListener((ob, a, b) -> updateOverlapText.run());
        updateOverlapText.run();
        // 仅在用户松手时落盘，避免拖动中高频写
        chunkSlider.setOnMouseReleased(e -> persistChunkParams(chunkSlider, overlapSlider));
        overlapSlider.setOnMouseReleased(e -> persistChunkParams(chunkSlider, overlapSlider));

        VBox chunkSection = new VBox(10, chunkRow, chunkSlider, chunkScale);
        Region divider = new Region();
        divider.getStyleClass().add("kc-param-divider");
        VBox overlapSection = new VBox(10, overlapRow, overlapSlider, overlapScale);

        // 重建索引行
        Label rebuildHint = new Label("修改分块参数仅对此后新导入的文档生效；已有文档需重建索引后应用。");
        rebuildHint.getStyleClass().add("kc-param-foot-text");
        rebuildHint.setWrapText(true);
        HBox.setHgrow(rebuildHint, Priority.ALWAYS);
        rebuildHint.setMaxWidth(Double.MAX_VALUE);
        Button rebuildBtn = new Button("重建索引");
        rebuildBtn.getStyleClass().addAll("jc-btn", "jc-btn-soft");
        rebuildBtn.setOnAction(e -> onRebuildIndex(rebuildBtn));
        HBox rebuildRow = new HBox(10, rebuildHint, rebuildBtn);
        rebuildRow.setAlignment(Pos.CENTER_LEFT);
        rebuildRow.getStyleClass().add("kc-param-foot");

        VBox card = new VBox(22, chunkSection, divider, overlapSection, rebuildRow);
        card.getStyleClass().add("kc-param-card");
        return card;
    }

    private Region scaleLabel(String left, String mid, String right) {
        Label l = new Label(left);
        Label m = new Label(mid);
        Label r = new Label(right);
        Region s1 = new Region(); HBox.setHgrow(s1, Priority.ALWAYS);
        Region s2 = new Region(); HBox.setHgrow(s2, Priority.ALWAYS);
        HBox row = new HBox(l, s1, m, s2, r);
        row.getStyleClass().add("kc-slider-scale");
        return row;
    }

    private VBox buildDangerZone() {
        Label title = new Label("清空知识库");
        title.getStyleClass().add("kc-danger-title");
        Label desc = new Label("删除全局 + 工作区的全部文档与向量片段，操作不可撤销。");
        desc.getStyleClass().add("kc-danger-desc");
        VBox text = new VBox(3, title, desc);
        HBox.setHgrow(text, Priority.ALWAYS);
        Button clearBtn = new Button("清空全部");
        clearBtn.getStyleClass().addAll("jc-btn", "jc-btn-danger-soft");
        clearBtn.setOnAction(e -> onClearAll());
        HBox row = new HBox(14, text, clearBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(row);
        box.getStyleClass().add("kc-danger-zone");
        return box;
    }

    private void persistChunkParams(Slider chunk, Slider overlap) {
        AgentConfig cfg = AgentConfig.getInstance();
        int c = (int) chunk.getValue();
        int o = (int) overlap.getValue();
        if (c == cfg.getRagChunkSize() && o == cfg.getRagChunkOverlap()) return;
        cfg.setRagChunkSize(c);
        cfg.setRagChunkOverlap(o);
        cfg.save();
        chunkParamsDirty = true;
        log.info("分块参数已保存: size={}, overlap={}", c, o);
    }

    // ==================== 操作：导入 / 删除 / 清空 / 重建 ====================

    private Scope currentImportScope() {
        // 「全局知识库」视图导入到全局，其余（全部 / 工作区）导入到工作区
        return "global".equals(scope) ? Scope.GLOBAL : Scope.WORKSPACE;
    }

    private void onImportFiles() {
        FileChooser fc = new FileChooser();
        fc.setTitle("选择要导入到知识库的文件");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("支持的文档格式",
                        "*.txt", "*.text", "*.md", "*.markdown", "*.log", "*.csv", "*.json", "*.xml", "*.html", "*.htm", "*.pdf"),
                new FileChooser.ExtensionFilter("PDF 文件", "*.pdf"),
                new FileChooser.ExtensionFilter("所有文件", "*.*"));
        List<File> files = fc.showOpenMultipleDialog(stage);
        if (files == null || files.isEmpty()) return;
        importFiles(files.toArray(new File[0]));
    }

    private void onImportFolder() {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("选择要导入的目录");
        File dir = dc.showDialog(stage);
        if (dir == null) return;
        File[] files = dir.listFiles(f -> f.isFile() && isSupportedImportFile(f.getName()));
        if (files == null || files.length == 0) {
            toast("目录中没有找到支持的文件（PDF / TXT / Markdown 等）");
            return;
        }
        importFiles(files);
    }

    private void importFiles(File[] files) {
        Scope sc = currentImportScope();
        toast("正在导入 " + files.length + " 个文件…");
        new Thread(() -> {
            int ok = 0, fail = 0;
            for (File f : files) {
                String result = f.length() > MAX_IMPORT_FILE_SIZE
                        ? ToolResponse.error("import", "文件过大（最大 50MB）")
                        : expert.importFile(f.getAbsolutePath(), sc);
                if (ToolResponse.isSuccess(result)) ok++; else { fail++; log.warn("导入失败: {} — {}", f.getName(), result); }
            }
            int fok = ok, ffail = fail;
            Platform.runLater(() -> {
                toast(ffail == 0 ? "全部导入成功（" + fok + " 个文件）"
                        : "导入完成：" + fok + " 成功，" + ffail + " 失败");
                importOpen = false;
                refreshRail();
                refreshMain();
            });
        }, "kc-import").start();
    }

    private void onImportText() {
        Dialog<Pair> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle("粘贴文本导入");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        TextField titleField = new TextField();
        titleField.setPromptText("文档标题（可选）");
        TextArea area = new TextArea();
        area.setPromptText("在此粘贴要导入的文本内容…");
        area.setPrefRowCount(10);
        area.setWrapText(true);
        VBox box = new VBox(10, titleField, area);
        box.setPadding(new Insets(12));
        box.setPrefWidth(520);
        dialog.getDialogPane().setContent(box);
        applyDialogStylesheet(dialog);
        dialog.setResultConverter(bt -> bt == ButtonType.OK ? new Pair(titleField.getText(), area.getText()) : null);
        dialog.showAndWait().ifPresent(p -> {
            if (p.text == null || p.text.isBlank()) { toast("文本内容为空"); return; }
            String t = (p.title == null || p.title.isBlank()) ? "手动导入文本" : p.title;
            Scope sc = currentImportScope();
            toast("正在导入文本…");
            new Thread(() -> {
                String result = expert.importText(p.text, t, sc);
                Platform.runLater(() -> {
                    toast(ToolResponse.isSuccess(result) ? "文本导入成功" : "导入失败");
                    importOpen = false;
                    refreshRail();
                    refreshMain();
                });
            }, "kc-import-text").start();
        });
    }

    private record Pair(String title, String text) {}

    private void onDeleteDoc(String name) {
        int chunks = expert.getDocumentChunkCount(name);
        Alert confirm = UIHelper.createConfirmAlert("确认删除",
                String.format("确定要删除文档 [%s] 吗？\n该文档包含 %d 个片段，删除后不可撤销。", name, chunks), stage);
        confirm.showAndWait().ifPresent(bt -> {
            if (bt == ButtonType.OK) {
                expert.knowledge_delete(name);
                expert.setDocEnabled(name, true); // 清理停用记录，避免遗留无效条目
                selectedDoc = null;
                toast("已删除文档：" + name);
                refreshRail();
                refreshMain();
            }
        });
    }

    private void onClearAll() {
        int docs = expert.getDocumentCount();
        int chunks = expert.getTotalChunkCount();
        Alert confirm = UIHelper.createConfirmAlert("确认清空",
                String.format("确定要清空所有知识库（全局 + 工作区）吗？\n当前共 %d 个文档，%d 个片段。\n此操作不可撤销。",
                        docs, chunks), stage);
        confirm.showAndWait().ifPresent(bt -> {
            if (bt == ButtonType.OK) {
                expert.knowledge_clear();
                selectedDoc = null;
                toast("知识库已清空");
                refreshRail();
                refreshMain();
            }
        });
    }

    private void onRebuildIndex(Button btn) {
        btn.setDisable(true);
        btn.setText("重建中…");
        toast("正在重建向量索引…");
        new Thread(() -> {
            int n = expert.reindexAll();
            Platform.runLater(() -> {
                btn.setDisable(false);
                btn.setText("重建索引");
                toast("索引重建完成，已重新嵌入 " + n + " 个片段");
            });
        }, "kc-reindex").start();
    }

    // ==================== Toast ====================

    private void toast(String msg) {
        // 经用户交互端口发非阻塞通知；本窗口打开期间已把渲染器接管到窗内浮层（见构造函数 bindToPort）。
        // 端口不可用时兜底直接渲染浮层，保证仍有可见反馈。
        log.info("[知识库中心] {}", msg);
        if (interaction != null) {
            interaction.notify(new ToastRequest("知识库中心", msg));
        } else {
            windowToast.show("[知识库中心] " + msg);
        }
    }

    // ==================== 小部件 / 工具 ====================

    /** 带放大镜图标的搜索/筛选输入容器（设计稿：图标 + 无边框输入，边框在容器上）。 */
    private HBox searchFieldContainer(TextField field, String boxClass) {
        HBox box = new HBox(7, magnifier(), field);
        box.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(field, Priority.ALWAYS);
        box.getStyleClass().add(boxClass);
        return box;
    }

    /** 14×14 描边放大镜（圆 + 手柄线），描边色走令牌。 */
    private Region magnifier() {
        Circle c = new Circle(6, 6, 4.5);
        c.getStyleClass().add("kc-search-icon");
        Line l = new Line(9.4, 9.4, 12.5, 12.5);
        l.getStyleClass().add("kc-search-icon");
        l.setStrokeLineCap(StrokeLineCap.ROUND);
        Pane p = new Pane(c, l);
        p.setMinSize(14, 14);
        p.setPrefSize(14, 14);
        p.setMaxSize(14, 14);
        return p;
    }

    /** 文档行副标题：取首个片段预览的一句话摘要（无则返回 null）。 */
    private String firstChunkSnippet(String name) {
        try {
            List<String> p = expert.getDocumentChunkPreviews(name, 1);
            if (!p.isEmpty() && p.get(0) != null) {
                String s = p.get(0).strip().replaceAll("\\s+", " ");
                if (s.isEmpty()) return null;
                return s.length() > 60 ? s.substring(0, 60) + "…" : s;
            }
        } catch (Exception ignore) { }
        return null;
    }

    private Label scopeTag(Scope sc) {
        Label tag = new Label(sc == Scope.GLOBAL ? "全局" : "工作区");
        tag.getStyleClass().addAll("kc-scope-tag", sc == Scope.GLOBAL ? "kc-scope-global" : "kc-scope-workspace");
        return tag;
    }

    private Region wrapCentered(Region inner) {
        HBox wrap = new HBox(inner);
        wrap.setAlignment(Pos.TOP_CENTER);
        wrap.setPadding(new Insets(24, 28, 24, 28));
        HBox.setHgrow(inner, Priority.ALWAYS);
        return wrap;
    }

    private List<String> visibleDocs() {
        List<String> base = switch (scope) {
            case "workspace" -> expert.getDocumentNames(Scope.WORKSPACE);
            case "global" -> expert.getDocumentNames(Scope.GLOBAL);
            default -> {
                List<String> all = new java.util.ArrayList<>(expert.getDocumentNames(Scope.GLOBAL));
                all.addAll(expert.getDocumentNames(Scope.WORKSPACE));
                yield all;
            }
        };
        String f = filter.trim().toLowerCase();
        if (f.isEmpty()) return base;
        return base.stream().filter(n -> n.toLowerCase().contains(f)).toList();
    }

    private boolean documentExists(String name) {
        return expert.getDocumentNames(Scope.GLOBAL).contains(name)
                || expert.getDocumentNames(Scope.WORKSPACE).contains(name);
    }

    private record ScopeMeta(String title, String shortTitle, String sub) {}

    private ScopeMeta scopeMeta() {
        int globalCount = expert.getDocumentNames(Scope.GLOBAL).size();
        int wsCount = expert.getDocumentNames(Scope.WORKSPACE).size();
        int totalChunks = expert.getTotalChunkCount();
        return switch (scope) {
            case "workspace" -> new ScopeMeta("工作区知识库", "工作区知识库",
                    wsCount + " 篇文档 · 仅当前工作区「" + currentWorkspaceName() + "」可用");
            case "global" -> new ScopeMeta("全局知识库", "全局知识库",
                    globalCount + " 篇文档 · 所有工作区共享");
            default -> new ScopeMeta("全部文档", "工作区知识库",
                    (globalCount + wsCount) + " 篇文档 · " + totalChunks + " 个向量片段 · 全局与当前工作区合并视图");
        };
    }

    private String currentWorkspaceName() {
        try {
            var ws = WorkspaceManager.getInstance().getCurrentWorkspace();
            if (ws != null && ws.getName() != null && !ws.getName().isBlank()) return ws.getName();
        } catch (Exception ignore) { }
        return "默认工作区";
    }

    private String ext(String name) {
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) return name.substring(dot + 1).toUpperCase();
        return "文本";
    }

    private String typeClass(String name) {
        return switch (ext(name).toLowerCase()) {
            case "pdf" -> "kc-type-pdf";
            case "md", "markdown" -> "kc-type-md";
            case "csv" -> "kc-type-csv";
            default -> "kc-type-txt";
        };
    }

    private String shortModel(String name) {
        if (name == null || name.isBlank()) return "未配置";
        String s = name;
        int slash = s.lastIndexOf('/');
        if (slash >= 0 && slash < s.length() - 1) s = s.substring(slash + 1);
        return s.replaceFirst("(?i)^text-embedding-", "");
    }

    private String shortDate(String time) {
        if (time == null) return "—";
        return time.length() >= 10 ? time.substring(0, 10) : time;
    }

    private String snippet(String content) {
        if (content == null) return "";
        String s = content.strip().replaceAll("\\s+", " ");
        return s.length() > 220 ? s.substring(0, 220) + "…" : s;
    }

    private String blankTo(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }

    private String pad(int n) {
        return String.format("%04d", n);
    }

    private int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void openModelSettings() {
        if (onOpenModelSettings != null) onOpenModelSettings.run();
    }

    private boolean isSupportedImportFile(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".pdf") || lower.endsWith(".txt") || lower.endsWith(".text")
                || lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".log")
                || lower.endsWith(".csv") || lower.endsWith(".json") || lower.endsWith(".xml")
                || lower.endsWith(".html") || lower.endsWith(".htm");
    }

    private void applyDialogStylesheet(Dialog<?> dialog) {
        var url = getClass().getResource("/css/chat.css");
        if (url != null) dialog.getDialogPane().getStylesheets().add(url.toExternalForm());
        dialog.getDialogPane().getStyleClass().add("root");
    }

    public void show() {
        stage.show();
    }
}
