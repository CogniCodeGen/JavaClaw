package com.javaclaw.ui.javafx.memory;

import com.javaclaw.agent.expert.KnowledgeExpert;
import com.javaclaw.memory.MemoryService;
import com.javaclaw.memory.graph.MemoryGraph;
import com.javaclaw.memory.model.ChangeLogEntry;
import com.javaclaw.memory.model.EntityNode;
import com.javaclaw.memory.model.Episode;
import com.javaclaw.memory.model.Fact;
import com.javaclaw.memory.model.KnowledgeChunk;
import com.javaclaw.memory.model.MemoryStats;
import com.javaclaw.memory.model.Persona;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 记忆中心 —— 查看 / 编辑 / 审计 EclipseStore 记忆库。
 *
 * <p>设计方向 1a「温室 · Refined Classic」：左导航栏 + 八个分区，全部引用 {@code -jc-*}
 * 主题令牌随主题换肤。</p>
 *
 * <ul>
 *   <li><b>总览</b>：统计卡 + 记忆构成 + 近期变更</li>
 *   <li><b>事实</b>：按主题分组、行内编辑（重嵌入 + 用户保护位）、批量删除、新增</li>
 *   <li><b>记忆图谱</b>：嵌入 {@link MemoryGraphView}（首次选中懒加载）</li>
 *   <li><b>情景记忆</b>：对话轮时间线</li>
 *   <li><b>实体</b>：记忆图实体节点，按类型分组</li>
 *   <li><b>知识库</b>：导入文档的分块（按文档聚合）</li>
 *   <li><b>人格</b>：人格正文编辑 + 注入预览 + 导出 Markdown</li>
 *   <li><b>变更日志</b>：append-only 审计轨，可按操作筛选</li>
 * </ul>
 *
 * <p>设计稿中模型尚不支持的字段（如事实置顶、结构化人格、命中趋势）按现有数据降级，不造假。</p>
 *
 * @author JavaClaw
 */
public class MemoryCenterView {

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** 分区定义：id → 显示名。 */
    private static final String[][] SECTIONS = {
            {"overview", "总览"},
            {"facts", "事实"},
            {"graph", "🕸 记忆图谱"},
            {"episodes", "情景记忆"},
            {"entities", "实体"},
            {"knowledge", "知识库"},
            {"persona", "人格"},
            {"log", "变更日志"},
    };

    private final Stage stage;
    private final MemoryService svc;
    /** 知识库读写：记忆中心「知识库」页签的真实数据源（独立于 memory-store 的 knowledge/store）。 */
    private final KnowledgeExpert knowledgeExpert;

    private final StackPane contentArea = new StackPane();
    private final Map<String, Region> panels = new HashMap<>();
    private final Map<String, Button> navButtons = new HashMap<>();
    private String currentSection = "overview";

    private final TextField searchField = new TextField();
    private String query = "";

    // —— 事实页状态 ——
    private boolean batchMode = false;
    private final Set<String> selectedFacts = new HashSet<>();
    private String editingFactId = null;
    private final Map<String, Boolean> groupOpen = new HashMap<>();

    // —— 变更日志筛选 —— (空串=全部，否则为 op：ADD/UPDATE/REMOVE/MERGE)
    private String logFilter = "";

    // —— 人格（结构化）——
    private static final String[] PERSONA_TONES = {"简洁直接", "耐心细致", "活泼鼓励"};
    private final TextArea personaIdentity = new TextArea();
    private String personaTone = PERSONA_TONES[0];
    private final List<String> personaPrefs = new ArrayList<>();
    private final List<String> personaTaboos = new ArrayList<>();
    private VBox personaPrefsList;
    private VBox personaTaboosList;
    private final List<Button> toneChips = new ArrayList<>();
    private Label personaPreview;

    // —— 图谱 ——
    private final MemoryGraphView graphView = new MemoryGraphView();
    private Label graphStatus;
    private boolean graphLoaded = false;
    private VBox graphInspector;
    private final boolean[] catVisible = {true, true, true}; // 事实 / 情景 / 实体

    // —— 左下角规模卡 ——
    private Label scaleMain;
    private Label scaleSub;

    public MemoryCenterView(Stage owner, MemoryService svc, KnowledgeExpert knowledgeExpert) {
        this.svc = svc;
        this.knowledgeExpert = knowledgeExpert;
        this.stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.NONE);
        stage.setTitle("记忆中心");

        HBox root = buildLayout();
        Scene scene = new Scene(root, 1000, 680);
        loadStylesheets(scene, owner);
        stage.setScene(scene);
        stage.setOnHidden(e -> graphView.dispose());

        selectSection("overview");
    }

    public void show() {
        stage.show();
        stage.toFront();
    }

    private void loadStylesheets(Scene scene, Stage owner) {
        // 先继承父窗口样式（含当前主题表），再补齐本视图依赖的表
        if (owner != null && owner.getScene() != null) {
            scene.getStylesheets().addAll(owner.getScene().getStylesheets());
        }
        addCss(scene, "/css/chat.css");
        addCss(scene, "/css/controls.css");
        addCss(scene, "/css/memory-center.css");
    }

    private void addCss(Scene scene, String path) {
        if (scene == null) return;
        addCss(scene.getStylesheets(), path);
    }

    private void addCss(javafx.collections.ObservableList<String> sheets, String path) {
        var url = getClass().getResource(path);
        if (url == null) return;
        String ext = url.toExternalForm();
        if (!sheets.contains(ext)) {
            sheets.add(ext);
        }
    }

    /** 给弹出对话框的 DialogPane 应用本视图样式（其 Scene 在 show 前为 null，故直接挂到 DialogPane）。 */
    private void styleDialog(javafx.scene.control.DialogPane pane) {
        addCss(pane.getStylesheets(), "/css/chat.css");
        addCss(pane.getStylesheets(), "/css/controls.css");
        addCss(pane.getStylesheets(), "/css/memory-center.css");
    }

    // ==================== 主布局 ====================

    private HBox buildLayout() {
        // —— 左导航栏 ——
        VBox leftPane = new VBox();
        leftPane.getStyleClass().add("modal-left-pane");
        leftPane.setPrefWidth(212);
        leftPane.setMinWidth(190);
        leftPane.setPadding(new Insets(14, 12, 12, 12));
        leftPane.setSpacing(2);

        Label group = new Label("记忆库");
        group.getStyleClass().add("modal-nav-group");
        leftPane.getChildren().add(group);

        for (String[] s : SECTIONS) {
            Button b = new Button(s[1]);
            b.getStyleClass().add("modal-nav-btn");
            b.setMaxWidth(Double.MAX_VALUE);
            b.setOnAction(e -> selectSection(s[0]));
            navButtons.put(s[0], b);
            leftPane.getChildren().add(b);
        }

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        leftPane.getChildren().add(spacer);
        leftPane.getChildren().add(buildScaleCard());

        // —— 右侧：头部 + 内容 + 页脚 ——
        HBox header = buildHeader();

        contentArea.getStyleClass().add("modal-content-area");
        VBox.setVgrow(contentArea, Priority.ALWAYS);

        // 预构建所有分区
        panels.put("overview", wrapScroll(buildOverview()));
        panels.put("facts", wrapScroll(buildFactsContainer()));
        panels.put("graph", buildGraphPanel());
        panels.put("episodes", wrapScroll(buildEpisodesContainer()));
        panels.put("entities", wrapScroll(buildEntitiesContainer()));
        panels.put("knowledge", wrapScroll(buildKnowledgeContainer()));
        panels.put("persona", wrapScroll(buildPersona()));
        panels.put("log", wrapScroll(buildLogContainer()));
        for (Region p : panels.values()) {
            p.setVisible(false);
            p.setManaged(false);
            contentArea.getChildren().add(p);
        }

        Button close = new Button("关闭");
        close.getStyleClass().addAll("jc-btn", "jc-btn-ghost");
        close.setOnAction(e -> stage.close());
        Region footSpacer = new Region();
        HBox.setHgrow(footSpacer, Priority.ALWAYS);
        HBox foot = new HBox(footSpacer, close);
        foot.getStyleClass().add("modal-foot");
        foot.setAlignment(Pos.CENTER_RIGHT);

        VBox rightPane = new VBox(header, contentArea, foot);
        HBox.setHgrow(rightPane, Priority.ALWAYS);

        HBox layout = new HBox(leftPane, rightPane);
        return layout;
    }

    private HBox buildHeader() {
        Label icon = new Label("🔍");
        icon.setStyle("-fx-text-fill: -jc-text-hint;");
        searchField.setPromptText("搜索记忆 — 事实 / 情景 / 实体…");
        searchField.getStyleClass().add("mc-search-field");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.textProperty().addListener((o, ov, nv) -> {
            query = nv == null ? "" : nv.trim();
            refreshSection(currentSection);
        });
        HBox searchBox = new HBox(icon, searchField);
        searchBox.getStyleClass().add("mc-search");
        searchBox.setAlignment(Pos.CENTER_LEFT);
        searchBox.setPrefWidth(420);
        searchBox.setMaxWidth(440);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label tag = new Label("全局检索 · 向量召回");
        tag.getStyleClass().add("mc-header-tag");

        HBox header = new HBox(searchBox, spacer, tag);
        header.getStyleClass().add("mc-header");
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    private VBox buildScaleCard() {
        Label cap = new Label("当前记忆规模");
        cap.getStyleClass().add("mc-scale-cap");
        scaleMain = new Label();
        scaleMain.getStyleClass().add("mc-scale-main");
        scaleSub = new Label();
        scaleSub.getStyleClass().add("mc-scale-sub");
        VBox card = new VBox(2, cap, scaleMain, scaleSub);
        card.getStyleClass().add("mc-scale-card");
        return card;
    }

    private void updateScaleCard() {
        int facts = svc.facts().size();
        int eps = svc.episodes().size();
        int ents = svc.entities().size();
        long docs = knowledgeChunks().stream().map(k -> k.docName).distinct().count();
        scaleMain.setText(facts + " 事实 · " + eps + " 情景");
        scaleSub.setText(ents + " 实体 · " + docs + " 文档");
    }

    private ScrollPane wrapScroll(Region content) {
        content.setPadding(new Insets(24));
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.getStyleClass().add("modal-content-area");
        sp.setStyle("-fx-background-color: transparent;");
        return sp;
    }

    private void selectSection(String id) {
        currentSection = id;
        navButtons.forEach((k, b) -> {
            b.getStyleClass().remove("modal-nav-btn-selected");
            if (k.equals(id)) b.getStyleClass().add("modal-nav-btn-selected");
        });
        panels.forEach((k, p) -> {
            boolean on = k.equals(id);
            p.setVisible(on);
            p.setManaged(on);
        });
        if ("graph".equals(id) && !graphLoaded) {
            loadGraph();
        }
        refreshSection(id);
        updateScaleCard();
    }

    /** 重建指定分区的动态内容（受搜索/筛选/编辑影响的分区）。 */
    private void refreshSection(String id) {
        switch (id) {
            case "overview" -> rebuildOverview();
            case "facts" -> rebuildFacts();
            case "episodes" -> rebuildEpisodes();
            case "entities" -> rebuildEntities();
            case "knowledge" -> rebuildKnowledge();
            case "persona" -> loadPersona();
            case "log" -> rebuildLog();
            default -> { }
        }
    }

    private boolean matches(String... fields) {
        if (query.isEmpty()) return true;
        String q = query.toLowerCase();
        for (String f : fields) {
            if (f != null && f.toLowerCase().contains(q)) return true;
        }
        return false;
    }

    // ==================== 总览 ====================

    private VBox overviewBox;

    private VBox buildOverview() {
        overviewBox = new VBox(20);
        return overviewBox;
    }

    private void rebuildOverview() {
        if (overviewBox == null) return;
        overviewBox.getChildren().clear();

        Label h = new Label("总览");
        h.getStyleClass().add("sec-title");
        Label sub = new Label("记忆的健康度与最近活动一目了然。");
        sub.getStyleClass().add("sec-hint");
        VBox head = new VBox(3, h, sub);

        MemoryStats st = svc.stats();
        long recalls = st != null ? st.totalRecalls : 0;
        long hits = st != null ? st.totalFactHits : 0;
        long distilled = st != null ? st.totalFactsDistilled : 0;
        long merged = st != null ? st.totalFactsMerged : 0;
        String hitRate = recalls > 0 ? Math.round(hits * 100.0 / recalls) + "%" : "—";

        GridPane stats = new GridPane();
        stats.setHgap(14);
        stats.setMaxWidth(Double.MAX_VALUE);
        for (int i = 0; i < 4; i++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(25);
            stats.getColumnConstraints().add(cc);
        }
        stats.add(statCard("累计召回", String.valueOf(recalls), "检索注入次数", false), 0, 0);
        stats.add(statCard("命中注入事实", String.valueOf(hits), "命中率 " + hitRate, true), 1, 0);
        stats.add(statCard("蒸馏新增", String.valueOf(distilled), "自动从对话沉淀", false), 2, 0);
        stats.add(statCard("去重合并", String.valueOf(merged), "向量去重 upsert", false), 3, 0);

        // 记忆构成 + 近期变更
        GridPane lower = new GridPane();
        lower.setHgap(14);
        lower.setMaxWidth(Double.MAX_VALUE);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setPercentWidth(52);
        ColumnConstraints c2 = new ColumnConstraints();
        c2.setPercentWidth(48);
        lower.getColumnConstraints().addAll(c1, c2);
        lower.add(buildComposition(), 0, 0);
        lower.add(buildRecentChanges(), 1, 0);
        GridPane.setHgrow(lower, Priority.ALWAYS);

        overviewBox.getChildren().addAll(head, stats, lower);
    }

    private VBox statCard(String cap, String num, String note, boolean noteIsDelta) {
        Label c = new Label(cap);
        c.getStyleClass().add("mc-stat-cap");
        Label n = new Label(num);
        n.getStyleClass().add("mc-stat-num");
        Label note2 = new Label(note);
        note2.getStyleClass().add(noteIsDelta ? "mc-stat-delta" : "mc-stat-note");
        VBox card = new VBox(6, c, n, note2);
        card.getStyleClass().add("mc-stat-card");
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    private VBox buildComposition() {
        Label title = new Label("记忆构成");
        title.getStyleClass().add("mc-card-title");
        int facts = svc.facts().size();
        int eps = svc.episodes().size();
        int ents = svc.entities().size();
        int chunks = knowledgeChunks().size();
        int max = Math.max(1, Math.max(Math.max(facts, eps), Math.max(ents, chunks)));
        VBox bars = new VBox(13,
                compBar("事实", facts, max, "-jc-success"),
                compBar("情景", eps, max, "-jc-warning"),
                compBar("实体", ents, max, "-jc-primary-400"),
                compBar("知识分块", chunks, max, "-jc-accent-400"));
        VBox card = new VBox(15, title, bars);
        card.getStyleClass().add("jc-card");
        card.setPadding(new Insets(18, 19, 18, 19));
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    private VBox compBar(String label, int count, int max, String colorToken) {
        Label l = new Label(label);
        l.setStyle("-fx-font-size: 12.5px; -fx-text-fill: -jc-text-body;");
        Label v = new Label(String.valueOf(count));
        v.setStyle("-fx-font-size: 12.5px; -fx-font-weight: 600; -fx-text-fill: -jc-text-muted;");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox row = new HBox(l, sp, v);

        Region fill = new Region();
        fill.getStyleClass().add("mc-bar-fill");
        fill.setStyle("-fx-background-color: " + colorToken + ";");
        StackPane track = new StackPane(fill);
        track.getStyleClass().add("mc-bar-track");
        track.setAlignment(Pos.CENTER_LEFT);
        double frac = Math.max(0.04, (double) count / max);
        fill.maxWidthProperty().bind(track.widthProperty().multiply(frac));

        return new VBox(5, row, track);
    }

    private VBox buildRecentChanges() {
        Label title = new Label("近期变更");
        title.getStyleClass().add("mc-card-title");
        VBox list = new VBox(11);
        List<ChangeLogEntry> entries = svc.recentChangeLog(6);
        if (entries.isEmpty()) {
            list.getChildren().add(emptyHint("暂无变更记录"));
        }
        for (ChangeLogEntry e : entries) {
            String[] m = opMeta(e.op);
            Label badge = new Label(m[0]);
            badge.getStyleClass().addAll("mc-op-badge", m[1]);
            badge.setMinWidth(Region.USE_PREF_SIZE);
            Label detail = new Label(oneLine(e.detail == null ? (e.type + " " + e.targetId) : e.detail, 48));
            detail.getStyleClass().add("mc-log-detail");
            detail.setWrapText(true);
            Label time = new Label(fmt(e.timestamp));
            time.getStyleClass().add("mc-ep-time");
            VBox txt = new VBox(2, detail, time);
            HBox.setHgrow(txt, Priority.ALWAYS);
            HBox row = new HBox(10, badge, txt);
            row.setAlignment(Pos.TOP_LEFT);
            list.getChildren().add(row);
        }
        VBox card = new VBox(13, title, list);
        card.getStyleClass().add("jc-card");
        card.setPadding(new Insets(18, 19, 18, 19));
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    // ==================== 事实 ====================

    private VBox factsBox;

    private VBox buildFactsContainer() {
        factsBox = new VBox(16);
        return factsBox;
    }

    private void rebuildFacts() {
        if (factsBox == null) return;
        factsBox.getChildren().clear();

        // 标题 + 操作
        Label h = new Label("事实 · 语义记忆");
        h.getStyleClass().add("sec-title");
        Label sub = new Label("蒸馏出的可长期记住的陈述。行内编辑后会重嵌入并打上「用户保护位」。");
        sub.getStyleClass().add("sec-hint");
        VBox titleBox = new VBox(3, h, sub);
        HBox.setHgrow(titleBox, Priority.ALWAYS);

        Button batchBtn = new Button(batchMode ? "退出批量" : "批量选择");
        batchBtn.getStyleClass().addAll("jc-btn", "jc-btn-ghost", "jc-btn-sm");
        batchBtn.setOnAction(e -> {
            batchMode = !batchMode;
            selectedFacts.clear();
            rebuildFacts();
        });
        Button addBtn = new Button("＋ 新增事实");
        addBtn.getStyleClass().addAll("jc-btn", "jc-btn-primary", "jc-btn-sm");
        addBtn.setOnAction(e -> addFactDialog());
        HBox actions = new HBox(8, batchBtn, addBtn);
        actions.setAlignment(Pos.CENTER_RIGHT);
        HBox header = new HBox(10, titleBox, actions);
        header.setAlignment(Pos.BOTTOM_LEFT);
        factsBox.getChildren().add(header);

        // 批量条
        if (batchMode) {
            Label cnt = new Label("已选 " + selectedFacts.size() + " 条");
            cnt.getStyleClass().add("mc-batch-count");
            Button delSel = new Button("删除所选");
            delSel.getStyleClass().addAll("jc-btn", "jc-btn-danger", "jc-btn-sm");
            delSel.setDisable(selectedFacts.isEmpty());
            delSel.setOnAction(e -> deleteSelectedFacts());
            Region sp = new Region();
            HBox.setHgrow(sp, Priority.ALWAYS);
            Button done = new Button("完成");
            done.getStyleClass().addAll("jc-btn", "jc-btn-ghost", "jc-btn-sm");
            done.setOnAction(e -> { batchMode = false; selectedFacts.clear(); rebuildFacts(); });
            HBox bar = new HBox(cnt, delSel, sp, done);
            bar.getStyleClass().add("mc-batch-bar");
            factsBox.getChildren().add(bar);
        }

        // 按主题分组
        Map<String, List<Fact>> groups = new TreeMap<>();
        for (Fact f : svc.facts()) {
            if (!matches(f.text, f.section)) continue;
            groups.computeIfAbsent(f.section == null ? "其它" : f.section, k -> new ArrayList<>()).add(f);
        }
        if (groups.isEmpty()) {
            factsBox.getChildren().add(emptyHint(query.isEmpty() ? "尚无事实，对话后会自动蒸馏沉淀" : "没有匹配的事实"));
            return;
        }
        for (var en : groups.entrySet()) {
            factsBox.getChildren().add(buildFactGroup(en.getKey(), en.getValue()));
        }
    }

    private VBox buildFactGroup(String section, List<Fact> facts) {
        boolean open = groupOpen.getOrDefault(section, true);

        Label arrow = new Label(open ? "▾" : "▸");
        arrow.setStyle("-fx-font-size: 11px; -fx-text-fill: -jc-text-hint;");
        Label title = new Label(section);
        title.getStyleClass().add("mc-group-title");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Label count = new Label(String.valueOf(facts.size()));
        count.getStyleClass().add("mc-group-count");
        HBox headRow = new HBox(arrow, title, sp, count);
        headRow.getStyleClass().add("mc-group-head");
        headRow.setOnMouseClicked(e -> {
            groupOpen.put(section, !open);
            rebuildFacts();
        });

        VBox card = new VBox(headRow);
        card.getStyleClass().add("mc-group-card");
        card.setMaxWidth(Double.MAX_VALUE);

        if (open) {
            // 置顶优先，其次按更新时间倒序
            facts.sort((a, b) -> {
                if (a.pinned != b.pinned) return a.pinned ? -1 : 1;
                return Long.compare(b.updatedAt, a.updatedAt);
            });
            VBox rows = new VBox(2);
            rows.setPadding(new Insets(2, 10, 8, 10));
            Region sep = new Region();
            sep.setMinHeight(1);
            sep.setStyle("-fx-background-color: -jc-border;");
            for (Fact f : facts) {
                rows.getChildren().add(buildFactRow(f));
            }
            card.getChildren().addAll(sep, rows);
        }
        return card;
    }

    private HBox buildFactRow(Fact f) {
        HBox row = new HBox();
        row.getStyleClass().add("mc-fact-row");

        if (batchMode) {
            boolean sel = selectedFacts.contains(f.id);
            Button check = new Button(sel ? "✓" : "");
            check.getStyleClass().add("mc-check");
            if (sel) check.getStyleClass().add("mc-check-on");
            check.setOnAction(e -> {
                if (selectedFacts.contains(f.id)) selectedFacts.remove(f.id);
                else selectedFacts.add(f.id);
                rebuildFacts();
            });
            row.getChildren().add(check);
        }

        VBox body = new VBox(6);
        HBox.setHgrow(body, Priority.ALWAYS);

        if (f.id != null && f.id.equals(editingFactId)) {
            TextArea ta = new TextArea(f.text);
            ta.getStyleClass().add("mc-fact-edit-area");
            ta.setWrapText(true);
            ta.setPrefRowCount(2);
            Button save = new Button("保存并重嵌入");
            save.getStyleClass().addAll("jc-btn", "jc-btn-save", "jc-btn-sm");
            save.setOnAction(e -> {
                String nt = ta.getText() == null ? "" : ta.getText().trim();
                if (!nt.isBlank() && !nt.equals(f.text)) {
                    svc.editFact(f, nt);
                }
                editingFactId = null;
                rebuildFacts();
            });
            Button cancel = new Button("取消");
            cancel.getStyleClass().addAll("jc-btn", "jc-btn-ghost", "jc-btn-sm");
            cancel.setOnAction(e -> { editingFactId = null; rebuildFacts(); });
            HBox btns = new HBox(8, save, cancel);
            body.getChildren().addAll(ta, btns);
        } else {
            Label text = new Label(f.text);
            text.getStyleClass().add("mc-fact-text");
            text.setWrapText(true);
            FlowPane meta = new FlowPane(9, 4);
            meta.setAlignment(Pos.CENTER_LEFT);
            meta.getChildren().add(metaLabel("更新 " + fmt(f.updatedAt)));
            meta.getChildren().add(metaLabel("· 命中 " + f.hitCount));
            if (f.userEdited) {
                Label prot = new Label("用户保护");
                prot.getStyleClass().addAll("jc-badge", "jc-badge-soft");
                meta.getChildren().add(prot);
            }
            if (f.pinned) {
                Label pin = new Label("置顶");
                pin.getStyleClass().addAll("jc-badge", "jc-badge-amber");
                meta.getChildren().add(pin);
            }
            body.getChildren().addAll(text, meta);
        }
        row.getChildren().add(body);

        if (!batchMode && (f.id == null || !f.id.equals(editingFactId))) {
            Button pin = new Button(f.pinned ? "★" : "☆");
            pin.getStyleClass().add("mc-icon-btn");
            pin.setTooltip(new javafx.scene.control.Tooltip(f.pinned ? "取消置顶" : "置顶"));
            pin.setOnAction(e -> { svc.togglePin(f); rebuildFacts(); });
            Button edit = new Button("✎");
            edit.getStyleClass().add("mc-icon-btn");
            edit.setOnAction(e -> { editingFactId = f.id; rebuildFacts(); });
            Button del = new Button("🗑");
            del.getStyleClass().addAll("mc-icon-btn", "mc-icon-btn-danger");
            del.setOnAction(e -> deleteFact(f));
            HBox tools = new HBox(3, pin, edit, del);
            tools.setAlignment(Pos.CENTER);
            row.getChildren().add(tools);
        }
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Label metaLabel(String t) {
        Label l = new Label(t);
        l.getStyleClass().add("mc-fact-meta");
        return l;
    }

    private void deleteFact(Fact f) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "删除事实：" + f.text + " ?", ButtonType.OK, ButtonType.CANCEL);
        a.initOwner(stage);
        if (a.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            svc.deleteFact(f);
            rebuildFacts();
            updateScaleCard();
        }
    }

    private void deleteSelectedFacts() {
        if (selectedFacts.isEmpty()) return;
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "删除所选 " + selectedFacts.size() + " 条事实？此操作不可撤销。",
                ButtonType.OK, ButtonType.CANCEL);
        a.initOwner(stage);
        if (a.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        for (Fact f : svc.facts()) {
            if (selectedFacts.contains(f.id)) svc.deleteFact(f);
        }
        selectedFacts.clear();
        rebuildFacts();
        updateScaleCard();
    }

    private void addFactDialog() {
        Dialog<Boolean> dlg = new Dialog<>();
        dlg.initOwner(stage);
        dlg.setTitle("新增事实");
        dlg.setHeaderText("手动新增的事实将打上「用户保护位」，蒸馏不会静默覆盖。");
        ButtonType ok = new ButtonType("保存", ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);

        ComboBox<String> section = new ComboBox<>();
        section.setEditable(true);
        Set<String> secs = new java.util.TreeSet<>();
        for (Fact f : svc.facts()) if (f.section != null) secs.add(f.section);
        section.getItems().addAll(secs);
        section.setValue(secs.isEmpty() ? "其它" : secs.iterator().next());
        section.setMaxWidth(Double.MAX_VALUE);
        TextArea text = new TextArea();
        text.setPromptText("一句话陈述，例如：用户主力语言是 Java，偏好不可变数据结构。");
        text.setWrapText(true);
        text.setPrefRowCount(3);

        GridPane gp = new GridPane();
        gp.setHgap(10);
        gp.setVgap(10);
        gp.setPadding(new Insets(8, 4, 4, 4));
        gp.add(new Label("主题"), 0, 0);
        gp.add(section, 1, 0);
        gp.add(new Label("事实"), 0, 1);
        gp.add(text, 1, 1);
        ColumnConstraints g2 = new ColumnConstraints();
        g2.setHgrow(Priority.ALWAYS);
        gp.getColumnConstraints().addAll(new ColumnConstraints(), g2);
        dlg.getDialogPane().setContent(gp);
        styleDialog(dlg.getDialogPane());

        dlg.setResultConverter(bt -> bt == ok);
        if (Boolean.TRUE.equals(dlg.showAndWait().orElse(false))) {
            String t = text.getText();
            if (t != null && !t.isBlank()) {
                svc.addFact(section.getValue(), t);
                rebuildFacts();
                updateScaleCard();
            }
        }
    }

    // ==================== 记忆图谱 ====================

    private Region buildGraphPanel() {
        // —— 左：类别筛选 + 聚焦深度 ——
        Label catTitle = new Label("类别");
        catTitle.setStyle("-fx-font-size: 12.5px; -fx-font-weight: 700; -fx-text-fill: -jc-text-title;");
        VBox cats = new VBox(8,
                catToggle(0, "事实", "-jc-success"),
                catToggle(1, "情景", "-jc-warning"),
                catToggle(2, "实体", "-jc-primary-400"));

        Label depthTitle = new Label("聚焦深度");
        depthTitle.setStyle("-fx-font-size: 12.5px; -fx-font-weight: 700; -fx-text-fill: -jc-text-title;");
        javafx.scene.control.Slider depth = new javafx.scene.control.Slider(1, 3, 3);
        depth.setMajorTickUnit(1);
        depth.setMinorTickCount(0);
        depth.setSnapToTicks(true);
        depth.setShowTickMarks(true);
        depth.valueProperty().addListener((o, ov, nv) -> graphView.setFocusDepth((int) Math.round(nv.doubleValue())));
        Label dDirect = new Label("直接");
        Label d2 = new Label("2 跳");
        Label dAll = new Label("全部");
        for (Label l : List.of(dDirect, d2, dAll)) l.setStyle("-fx-font-size: 10.5px; -fx-text-fill: -jc-text-hint;");
        Region ds = new Region();
        HBox.setHgrow(ds, Priority.ALWAYS);
        Region ds2 = new Region();
        HBox.setHgrow(ds2, Priority.ALWAYS);
        HBox depthLabels = new HBox(dDirect, ds, d2, ds2, dAll);

        Button refresh = new Button("重新构建");
        refresh.getStyleClass().addAll("jc-btn", "jc-btn-ghost", "jc-btn-sm");
        refresh.setMaxWidth(Double.MAX_VALUE);
        refresh.setOnAction(e -> loadGraph());
        graphStatus = new Label("切到此页签后自动构建…");
        graphStatus.getStyleClass().add("sec-hint");
        graphStatus.setWrapText(true);
        Label hint = new Label("拖拽平移 · 滚轮缩放\n点击节点查看关联");
        hint.getStyleClass().add("sec-hint");

        VBox left = new VBox(11, catTitle, cats, depthTitle, depth, depthLabels,
                new Separator(), refresh, graphStatus, hint);
        left.getStyleClass().add("jc-card");
        left.setPadding(new Insets(15));
        left.setPrefWidth(196);
        left.setMinWidth(176);

        // —— 中：画布 ——（WebView 包一层便于描边/圆角）
        StackPane canvas = new StackPane(graphView.getView());
        VBox.setVgrow(canvas, Priority.ALWAYS);
        HBox.setHgrow(canvas, Priority.ALWAYS);
        canvas.setStyle("-fx-border-color: -jc-border; -fx-border-width: 1; -fx-border-radius: 12;");

        // —— 右：检视器 ——
        graphInspector = new VBox(10);
        graphInspector.getStyleClass().add("jc-card");
        graphInspector.setPadding(new Insets(16));
        graphInspector.setPrefWidth(224);
        graphInspector.setMinWidth(200);
        updateInspector(null);
        graphView.setOnNodeSelected(d -> Platform.runLater(() -> updateInspector(d)));

        HBox row = new HBox(14, left, canvas, graphInspector);
        row.setPadding(new Insets(20, 24, 24, 24));
        VBox.setVgrow(row, Priority.ALWAYS);
        return row;
    }

    /** 类别筛选行：彩色圆点 + 名称，点击切换显示。 */
    private HBox catToggle(int idx, String label, String colorToken) {
        Region dot = new Region();
        dot.setStyle("-fx-background-color: " + colorToken + "; -fx-background-radius: 999;"
                + "-fx-min-width: 9; -fx-min-height: 9; -fx-max-width: 9; -fx-max-height: 9;");
        Label name = new Label(label);
        name.setStyle("-fx-font-size: 12.5px; -fx-text-fill: -jc-text-body;");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Label check = new Label(catVisible[idx] ? "✓" : "");
        check.setStyle("-fx-font-size: 12px; -fx-text-fill: -jc-primary-600;");
        HBox rowBox = new HBox(8, dot, name, sp, check);
        rowBox.setAlignment(Pos.CENTER_LEFT);
        rowBox.setStyle("-fx-cursor: hand; -fx-padding: 4 6 4 6; -fx-background-radius: 7;");
        rowBox.setOpacity(catVisible[idx] ? 1.0 : 0.45);
        rowBox.setOnMouseClicked(e -> {
            catVisible[idx] = !catVisible[idx];
            check.setText(catVisible[idx] ? "✓" : "");
            rowBox.setOpacity(catVisible[idx] ? 1.0 : 0.45);
            graphView.setVisibleTypes(catVisible[0], catVisible[1], catVisible[2]);
        });
        return rowBox;
    }

    /** 渲染右侧检视器：选中节点的类型 / 名称 / 关联记忆；null 为未选中空态。 */
    private void updateInspector(MemoryGraphView.NodeDetail d) {
        if (graphInspector == null) return;
        graphInspector.getChildren().clear();
        if (d == null) {
            Label empty = new Label("点击图中节点\n查看其类型与关联记忆");
            empty.getStyleClass().add("sec-hint");
            empty.setWrapText(true);
            graphInspector.getChildren().add(empty);
            return;
        }
        String[] tm = nodeTypeMeta(d.type());
        Label badge = new Label(tm[0]);
        badge.getStyleClass().addAll("jc-badge", tm[1]);
        Label name = new Label(d.label());
        name.setStyle("-fx-font-size: 15px; -fx-font-weight: 700; -fx-text-fill: -jc-text-title;");
        name.setWrapText(true);
        Label relTitle = new Label("关联记忆");
        relTitle.setStyle("-fx-font-size: 12px; -fx-font-weight: 600; -fx-text-fill: -jc-text-muted;");
        VBox rel = new VBox(8);
        List<String> related = d.related();
        if (related == null || related.isEmpty()) {
            Label none = new Label("（无直接关联）");
            none.getStyleClass().add("sec-hint");
            rel.getChildren().add(none);
        } else {
            for (String r : related) {
                Label item = new Label(r);
                item.setWrapText(true);
                item.setMaxWidth(Double.MAX_VALUE);
                item.setStyle("-fx-font-size: 12px; -fx-text-fill: -jc-text-body; -fx-background-color: -jc-surface-sunken;"
                        + "-fx-background-radius: 8; -fx-padding: 8 10 8 10;");
                rel.getChildren().add(item);
            }
        }
        graphInspector.getChildren().addAll(badge, name, relTitle, rel);
    }

    private static String[] nodeTypeMeta(String type) {
        if (type == null) return new String[]{"节点", "jc-badge-stopped"};
        return switch (type) {
            case "fact" -> new String[]{"事实", "jc-badge-ok"};
            case "episode" -> new String[]{"情景", "jc-badge-amber"};
            case "entity" -> new String[]{"实体", "jc-badge-soft"};
            default -> new String[]{type, "jc-badge-stopped"};
        };
    }

    private void loadGraph() {
        if (svc == null) return;
        graphLoaded = true;
        graphStatus.setText("构建中…");
        Thread t = new Thread(() -> {
            MemoryGraph g;
            try {
                g = svc.graph();
            } catch (Exception ex) {
                g = MemoryGraph.empty();
            }
            final MemoryGraph graph = g;
            Platform.runLater(() -> {
                graphView.render(graph);
                updateInspector(null);
                graphStatus.setText(graph.nodes().size() + " 节点 · " + graph.edges().size() + " 边");
            });
        }, "memory-graph-build");
        t.setDaemon(true);
        t.start();
    }

    // ==================== 情景记忆 ====================

    private VBox episodesBox;

    private VBox buildEpisodesContainer() {
        episodesBox = new VBox(16);
        return episodesBox;
    }

    private void rebuildEpisodes() {
        if (episodesBox == null) return;
        episodesBox.getChildren().clear();
        Label h = new Label("情景记忆");
        h.getStyleClass().add("sec-title");
        Label sub = new Label("每一轮对话与当时做过的事。可回溯「当时发生了什么」并看到沉淀出的事实数。");
        sub.getStyleClass().add("sec-hint");
        episodesBox.getChildren().add(new VBox(3, h, sub));

        // episode.id → 沉淀事实数
        Map<String, Integer> derived = new HashMap<>();
        for (Fact f : svc.facts()) {
            if (f.source != null && f.source.id != null) {
                derived.merge(f.source.id, 1, Integer::sum);
            }
        }

        List<Episode> eps = new ArrayList<>(svc.episodes());
        eps.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
        eps.removeIf(e -> !matches(e.userInput, e.assistantReply));
        if (eps.isEmpty()) {
            episodesBox.getChildren().add(emptyHint(query.isEmpty() ? "暂无情景记录" : "没有匹配的情景"));
            return;
        }

        // 时间线：每张卡片左侧一个圆点标记
        VBox timeline = new VBox(14);
        for (Episode e : eps) {
            timeline.getChildren().add(buildEpisodeCard(e, derived.getOrDefault(e.id, 0)));
        }
        episodesBox.getChildren().add(timeline);
    }

    private HBox buildEpisodeCard(Episode e, int factCount) {
        Label time = new Label(fmt(e.timestamp));
        time.getStyleClass().add("mc-ep-time");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Label derived = new Label("沉淀 " + factCount + " 条事实 →");
        derived.getStyleClass().addAll("jc-badge", "jc-badge-soft");
        HBox top = new HBox(time, sp, derived);
        top.setAlignment(Pos.CENTER_LEFT);

        HBox userRow = roleLine("用户", "mc-ep-role-user", e.userInput, "mc-ep-user");
        HBox asstRow = roleLine("助手", "mc-ep-role-asst", e.assistantReply, "mc-ep-reply");

        VBox card = new VBox(8, top, userRow, asstRow);
        if (e.toolTraceJson != null && !e.toolTraceJson.isBlank()) {
            Label chip = new Label("🔧 工具轨迹");
            chip.getStyleClass().add("mc-tool-chip");
            FlowPane tools = new FlowPane(6, 6, chip);
            card.getChildren().add(tools);
        }
        card.getStyleClass().add("mc-episode-card");

        // 时间线圆点
        Region dot = new Region();
        dot.getStyleClass().add("mc-timeline-dot");
        StackPane dotWrap = new StackPane(dot);
        dotWrap.setPadding(new Insets(16, 10, 0, 0));
        dotWrap.setAlignment(Pos.TOP_CENTER);
        HBox.setHgrow(card, Priority.ALWAYS);
        HBox row = new HBox(dotWrap, card);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    private HBox roleLine(String role, String roleClass, String text, String textClass) {
        Label r = new Label(role);
        r.getStyleClass().add(roleClass);
        r.setMinWidth(30);
        Label t = new Label(text == null ? "" : text);
        t.getStyleClass().add(textClass);
        t.setWrapText(true);
        HBox.setHgrow(t, Priority.ALWAYS);
        HBox row = new HBox(8, r, t);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    // ==================== 实体 ====================

    private VBox entitiesBox;

    private VBox buildEntitiesContainer() {
        entitiesBox = new VBox(16);
        return entitiesBox;
    }

    private void rebuildEntities() {
        if (entitiesBox == null) return;
        entitiesBox.getChildren().clear();
        Label h = new Label("实体");
        h.getStyleClass().add("sec-title");
        Label sub = new Label("记忆图中的节点 — 项目、工具、主题、人物。括号内为其上关联的事实数。");
        sub.getStyleClass().add("sec-hint");
        entitiesBox.getChildren().add(new VBox(3, h, sub));

        // 实体名 → 关联事实数
        Map<String, Integer> factsByEntity = new HashMap<>();
        for (Fact f : svc.facts()) {
            if (f.about != null) {
                for (EntityNode en : f.about) {
                    if (en != null && en.name != null) factsByEntity.merge(en.name, 1, Integer::sum);
                }
            }
        }

        Map<String, List<EntityNode>> byType = new TreeMap<>();
        for (EntityNode en : svc.entities()) {
            if (!matches(en.name, en.type)) continue;
            byType.computeIfAbsent(en.type == null ? "其它" : en.type, k -> new ArrayList<>()).add(en);
        }
        if (byType.isEmpty()) {
            entitiesBox.getChildren().add(emptyHint(query.isEmpty() ? "暂无实体（轮后实体抽取后长出）" : "没有匹配的实体"));
            return;
        }
        for (var en : byType.entrySet()) {
            entitiesBox.getChildren().add(buildEntityGroup(en.getKey(), en.getValue(), factsByEntity));
        }
    }

    private VBox buildEntityGroup(String type, List<EntityNode> items, Map<String, Integer> factsByEntity) {
        Region dot = new Region();
        dot.setStyle("-fx-background-color: " + colorForType(type) + "; -fx-background-radius: 999; "
                + "-fx-min-width: 9; -fx-min-height: 9; -fx-max-width: 9; -fx-max-height: 9;");
        Label label = new Label(type);
        label.setStyle("-fx-font-size: 13.5px; -fx-font-weight: 700; -fx-text-fill: -jc-text-title;");
        HBox head = new HBox(8, dot, label);
        head.setAlignment(Pos.CENTER_LEFT);

        FlowPane grid = new FlowPane(11, 11);
        for (EntityNode en : items) {
            Label name = new Label(en.name);
            name.getStyleClass().add("mc-entity-name");
            Region sp = new Region();
            HBox.setHgrow(sp, Priority.ALWAYS);
            Label cnt = new Label(factsByEntity.getOrDefault(en.name, 0) + " 事实");
            cnt.getStyleClass().add("mc-group-count");
            HBox card = new HBox(8, name, sp, cnt);
            card.setAlignment(Pos.CENTER_LEFT);
            card.getStyleClass().add("mc-entity-card");
            card.setPrefWidth(225);
            grid.getChildren().add(card);
        }
        return new VBox(11, head, grid);
    }

    // ==================== 知识库 ====================

    private VBox knowledgeBox;

    private VBox buildKnowledgeContainer() {
        knowledgeBox = new VBox(16);
        return knowledgeBox;
    }

    private void rebuildKnowledge() {
        if (knowledgeBox == null) return;
        knowledgeBox.getChildren().clear();
        Label h = new Label("知识库");
        h.getStyleClass().add("sec-title");
        Label sub = new Label("导入的文档被切分为分块并向量化，供检索增强。");
        sub.getStyleClass().add("sec-hint");
        knowledgeBox.getChildren().add(new VBox(3, h, sub));

        // 按文档聚合：[分块数, 累计字符数]
        Map<String, long[]> docs = new LinkedHashMap<>();
        Map<String, String> imported = new HashMap<>();
        for (KnowledgeChunk k : knowledgeChunks()) {
            if (!matches(k.docName, k.content)) continue;
            long[] agg = docs.computeIfAbsent(k.docName, x -> new long[]{0, 0});
            agg[0]++;
            agg[1] += k.content == null ? 0 : k.content.length();
            if (k.importTime != null) imported.putIfAbsent(k.docName, k.importTime);
        }

        if (docs.isEmpty()) {
            VBox drop = new VBox(4,
                    boldHint("将文件拖到此处导入，或在对话中使用知识工具"),
                    emptyHint("支持 .md · .pdf · .txt · .docx"));
            drop.getStyleClass().add("mc-dropzone");
            drop.setAlignment(Pos.CENTER);
            knowledgeBox.getChildren().add(drop);
            return;
        }

        VBox table = new VBox();
        table.getStyleClass().add("mc-log-card");
        table.setMaxWidth(Double.MAX_VALUE);
        // 表头
        HBox head = new HBox(
                headCell("文档", Priority.ALWAYS, 0),
                headCell("分块", null, 80),
                headCell("大小", null, 90),
                headCell("导入时间", null, 140),
                headCell("操作", null, 92));
        head.getStyleClass().add("jc-table-head");
        head.setPadding(new Insets(11, 16, 11, 16));
        table.getChildren().add(head);
        for (var en : docs.entrySet()) {
            String doc = en.getKey();
            Label name = new Label("📄 " + doc);
            name.setStyle("-fx-font-size: 13px; -fx-font-weight: 600; -fx-text-fill: -jc-text-title;");
            HBox.setHgrow(name, Priority.ALWAYS);
            name.setMaxWidth(Double.MAX_VALUE);
            Label chunks = fixedCell(en.getValue()[0] + " 块", 80);
            Label size = fixedCell(humanSize(en.getValue()[1]), 90);
            Label imp = fixedCell(imported.getOrDefault(doc, "—"), 140);

            Button reindex = new Button("↻");
            reindex.getStyleClass().add("mc-icon-btn");
            reindex.setTooltip(new javafx.scene.control.Tooltip("重建索引（重新嵌入全部分块）"));
            reindex.setOnAction(e -> reindexDoc(doc));
            Button del = new Button("🗑");
            del.getStyleClass().addAll("mc-icon-btn", "mc-icon-btn-danger");
            del.setTooltip(new javafx.scene.control.Tooltip("删除文档"));
            del.setOnAction(e -> deleteKnowledgeDoc(doc));
            HBox ops = new HBox(3, reindex, del);
            ops.setMinWidth(92);
            ops.setPrefWidth(92);
            ops.setAlignment(Pos.CENTER_LEFT);

            HBox row = new HBox(name, chunks, size, imp, ops);
            row.getStyleClass().add("mc-log-row");
            row.setAlignment(Pos.CENTER_LEFT);
            table.getChildren().add(row);
        }
        knowledgeBox.getChildren().add(table);
    }

    /** 知识库分块数据源：真实的 KnowledgeExpert（knowledge/store），expert 未注入时回退空。 */
    private List<KnowledgeChunk> knowledgeChunks() {
        return knowledgeExpert != null ? knowledgeExpert.allKnowledgeChunks() : List.of();
    }

    private void reindexDoc(String doc) {
        if (knowledgeExpert == null) return;
        toast("正在后台重建「" + doc + "」索引…");
        Thread t = new Thread(() -> {
            int n = knowledgeExpert.reindexDocument(doc);
            Platform.runLater(() -> { rebuildKnowledge(); toast("已重建 " + n + " 个分块的索引"); });
        }, "knowledge-reindex");
        t.setDaemon(true);
        t.start();
    }

    private void deleteKnowledgeDoc(String doc) {
        if (knowledgeExpert == null) return;
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "删除文档「" + doc + "」及其全部分块？", ButtonType.OK, ButtonType.CANCEL);
        a.initOwner(stage);
        if (a.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        knowledgeExpert.knowledge_delete(doc);
        rebuildKnowledge();
        updateScaleCard();
        toast("已删除文档「" + doc + "」");
    }

    private static String humanSize(long chars) {
        // 以 UTF-8 粗略估算字节（中文约 3 字节/字），仅供展示
        long bytes = chars * 2;
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        return String.format("%.1f MB", kb / 1024);
    }

    private Label headCell(String t, Priority grow, double w) {
        Label l = new Label(t);
        l.getStyleClass().add("jc-table-head-cell");
        if (grow != null) {
            HBox.setHgrow(l, grow);
            l.setMaxWidth(Double.MAX_VALUE);
        } else {
            l.setMinWidth(w);
            l.setPrefWidth(w);
        }
        return l;
    }

    private Label fixedCell(String t, double w) {
        Label l = new Label(t);
        l.getStyleClass().add("jc-table-cell-muted");
        l.setMinWidth(w);
        l.setPrefWidth(w);
        return l;
    }

    // ==================== 人格 ====================

    private VBox buildPersona() {
        Label h = new Label("人格");
        h.getStyleClass().add("sec-title");
        Label sub = new Label("结构化人格，每轮注入系统提示词（替代 AGENTS.md）。右侧为实际注入预览。");
        sub.getStyleClass().add("sec-hint");
        VBox head = new VBox(3, h, sub);

        // —— 身份 ——
        personaIdentity.setWrapText(true);
        personaIdentity.setPrefRowCount(3);
        personaIdentity.textProperty().addListener((o, ov, nv) -> updatePersonaPreview());
        VBox idCard = personaCard("身份", personaIdentity);

        // —— 语气（单选芯片）——
        HBox tones = new HBox(8);
        toneChips.clear();
        for (String t : PERSONA_TONES) {
            Button chip = new Button(t);
            chip.getStyleClass().add("mc-tone-chip");
            chip.setOnAction(e -> { personaTone = t; refreshToneChips(); updatePersonaPreview(); });
            toneChips.add(chip);
            tones.getChildren().add(chip);
        }
        VBox toneCard = personaCard("语气", tones);

        // —— 偏好 ——
        personaPrefsList = new VBox(7);
        VBox prefCard = personaCard("偏好", listEditor(personaPrefs, personaPrefsList, false));

        // —— 禁忌 ——
        personaTaboosList = new VBox(7);
        VBox tabooCard = personaCard("禁忌", listEditor(personaTaboos, personaTaboosList, true));

        // —— 操作 ——
        Button save = new Button("保存人格");
        save.getStyleClass().addAll("jc-btn", "jc-btn-save", "jc-btn-sm");
        save.setOnAction(e -> {
            svc.setPersonaStructured(personaIdentity.getText(), personaTone, personaPrefs, personaTaboos);
            toast("人格已保存（下一轮对话生效）");
        });
        Button export = new Button("导出 Markdown");
        export.getStyleClass().addAll("jc-btn", "jc-btn-ghost", "jc-btn-sm");
        export.setOnAction(e -> exportPersona());
        HBox btns = new HBox(9, save, export);

        VBox editor = new VBox(14, idCard, toneCard, prefCard, tabooCard, btns);
        HBox.setHgrow(editor, Priority.ALWAYS);

        // 注入预览（深色控制台块）
        Label pvTitle = new Label("● 注入预览 · system prompt");
        pvTitle.getStyleClass().add("mc-persona-preview-title");
        personaPreview = new Label();
        personaPreview.getStyleClass().add("mc-persona-preview-text");
        personaPreview.setWrapText(true);
        ScrollPane pvScroll = new ScrollPane(personaPreview);
        pvScroll.setFitToWidth(true);
        pvScroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        pvScroll.setPrefViewportHeight(420);
        VBox preview = new VBox(11, pvTitle, pvScroll);
        preview.getStyleClass().add("mc-persona-preview");
        preview.setPrefWidth(360);
        preview.setMinWidth(300);

        HBox split = new HBox(16, editor, preview);
        VBox.setVgrow(split, Priority.ALWAYS);

        return new VBox(18, head, split);
    }

    private VBox personaCard(String title, javafx.scene.Node body) {
        Label t = new Label(title);
        t.setStyle("-fx-font-size: 13px; -fx-font-weight: 700; -fx-text-fill: -jc-text-title;");
        VBox card = new VBox(9, t, body);
        card.getStyleClass().add("jc-card");
        card.setPadding(new Insets(15, 16, 15, 16));
        return card;
    }

    private void refreshToneChips() {
        for (Button c : toneChips) {
            c.getStyleClass().remove("mc-tone-chip-on");
            if (c.getText().equals(personaTone)) c.getStyleClass().add("mc-tone-chip-on");
        }
    }

    /** 可增删的清单编辑器（偏好 / 禁忌共用）。danger=true 用红色系（禁忌）。 */
    private VBox listEditor(List<String> model, VBox listBox, boolean danger) {
        TextField input = new TextField();
        input.setPromptText(danger ? "添加一条禁忌…" : "添加一条偏好…");
        HBox.setHgrow(input, Priority.ALWAYS);
        Button add = new Button("＋ 添加");
        add.getStyleClass().addAll("jc-btn", "jc-btn-soft", "jc-btn-sm");
        Runnable addAction = () -> {
            String v = input.getText() == null ? "" : input.getText().trim();
            if (!v.isEmpty()) {
                model.add(v);
                input.clear();
                renderPersonaList(model, listBox, danger);
                updatePersonaPreview();
            }
        };
        add.setOnAction(e -> addAction.run());
        input.setOnAction(e -> addAction.run());
        HBox addRow = new HBox(8, input, add);
        addRow.setAlignment(Pos.CENTER_LEFT);
        renderPersonaList(model, listBox, danger);
        return new VBox(9, listBox, addRow);
    }

    private void renderPersonaList(List<String> model, VBox listBox, boolean danger) {
        listBox.getChildren().clear();
        for (int i = 0; i < model.size(); i++) {
            final int idx = i;
            Label icon = new Label(danger ? "⊘" : "✓");
            icon.setStyle("-fx-font-size: 12px; -fx-text-fill: " + (danger ? "-jc-danger" : "-jc-success") + ";");
            Label text = new Label(model.get(i));
            text.setWrapText(true);
            text.setStyle("-fx-font-size: 12.5px; -fx-text-fill: -jc-text-title;");
            HBox.setHgrow(text, Priority.ALWAYS);
            text.setMaxWidth(Double.MAX_VALUE);
            Button rm = new Button("×");
            rm.getStyleClass().add("mc-icon-btn");
            rm.setOnAction(e -> {
                model.remove(idx);
                renderPersonaList(model, listBox, danger);
                updatePersonaPreview();
            });
            HBox row = new HBox(9, icon, text, rm);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setStyle("-fx-background-color: " + (danger ? "-jc-danger-bg" : "-jc-success-bg")
                    + "; -fx-background-radius: 8; -fx-padding: 8 11 8 11;");
            listBox.getChildren().add(row);
        }
    }

    private void updatePersonaPreview() {
        if (personaPreview == null) return;
        personaPreview.setText(MemoryService.assemblePersona(
                personaIdentity.getText(), personaTone, personaPrefs, personaTaboos));
    }

    private void loadPersona() {
        Persona p = svc.getPersona();
        personaPrefs.clear();
        personaTaboos.clear();
        if (p != null && p.structured) {
            personaIdentity.setText(p.identity == null ? "" : p.identity);
            personaTone = p.tone == null || p.tone.isBlank() ? PERSONA_TONES[0] : p.tone;
            if (p.preferences != null) personaPrefs.addAll(p.preferences);
            if (p.taboos != null) personaTaboos.addAll(p.taboos);
        } else {
            // 旧的纯正文人格：整段迁入「身份」，不丢数据，用户可逐步拆分
            personaIdentity.setText(p != null && p.content != null ? p.content : "");
            personaTone = PERSONA_TONES[0];
        }
        refreshToneChips();
        if (personaPrefsList != null) renderPersonaList(personaPrefs, personaPrefsList, false);
        if (personaTaboosList != null) renderPersonaList(personaTaboos, personaTaboosList, true);
        updatePersonaPreview();
    }

    private void exportPersona() {
        FileChooser fc = new FileChooser();
        fc.setTitle("导出人格为 Markdown");
        fc.setInitialFileName("persona.md");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Markdown", "*.md"));
        var file = fc.showSaveDialog(stage);
        if (file == null) return;
        try {
            String md = MemoryService.assemblePersona(
                    personaIdentity.getText(), personaTone, personaPrefs, personaTaboos);
            Files.writeString(file.toPath(), md);
            toast("已导出到 " + file.getName());
        } catch (IOException ex) {
            toast("导出失败：" + ex.getMessage());
        }
    }

    // ==================== 变更日志 ====================

    private VBox logBox;

    private VBox buildLogContainer() {
        logBox = new VBox(16);
        return logBox;
    }

    private void rebuildLog() {
        if (logBox == null) return;
        logBox.getChildren().clear();
        Label h = new Label("变更日志");
        h.getStyleClass().add("sec-title");
        Label sub = new Label("append-only 审计轨，记录记忆的每一次变更（取代备份策略）。");
        sub.getStyleClass().add("sec-hint");
        logBox.getChildren().add(new VBox(3, h, sub));

        // 筛选芯片
        HBox filters = new HBox(7);
        filters.getChildren().add(filterChip("全部", ""));
        filters.getChildren().add(filterChip("新增", "ADD"));
        filters.getChildren().add(filterChip("编辑", "UPDATE"));
        filters.getChildren().add(filterChip("删除", "REMOVE"));
        filters.getChildren().add(filterChip("合并", "MERGE"));
        logBox.getChildren().add(filters);

        VBox card = new VBox();
        card.getStyleClass().add("mc-log-card");
        card.setMaxWidth(Double.MAX_VALUE);
        boolean first = true;
        int shown = 0;
        for (ChangeLogEntry e : svc.recentChangeLog(500)) {
            if (!logFilter.isEmpty() && !logFilter.equalsIgnoreCase(e.op)) continue;
            if (!matches(e.detail, e.type, e.targetId)) continue;
            card.getChildren().add(buildLogRow(e, first));
            first = false;
            shown++;
        }
        if (shown == 0) {
            logBox.getChildren().add(emptyHint("没有匹配的变更记录"));
        } else {
            logBox.getChildren().add(card);
        }
    }

    private Label filterChip(String label, String op) {
        Label chip = new Label(label);
        chip.getStyleClass().add("mc-filter-chip");
        if (logFilter.equals(op)) chip.getStyleClass().add("mc-filter-chip-on");
        chip.setOnMouseClicked(e -> { logFilter = op; rebuildLog(); });
        return chip;
    }

    private HBox buildLogRow(ChangeLogEntry e, boolean first) {
        String[] m = opMeta(e.op);
        Label badge = new Label(m[0]);
        badge.getStyleClass().addAll("mc-op-badge", m[1]);
        badge.setMinWidth(42);
        badge.setAlignment(Pos.CENTER);
        Label type = new Label(e.type == null ? "" : e.type);
        type.getStyleClass().add("mc-log-type");
        type.setMinWidth(96);
        Label detail = new Label(oneLine(e.detail == null ? "" : e.detail, 120));
        detail.getStyleClass().add("mc-log-detail");
        detail.setWrapText(true);
        HBox.setHgrow(detail, Priority.ALWAYS);
        detail.setMaxWidth(Double.MAX_VALUE);
        Label time = new Label(fmt(e.timestamp));
        time.getStyleClass().add("mc-log-time");
        HBox row = new HBox(13, badge, type, detail, time);
        row.getStyleClass().add("mc-log-row");
        row.setAlignment(Pos.CENTER_LEFT);
        if (first) row.setStyle("-fx-border-width: 0;");
        return row;
    }

    // ==================== 公共小件 ====================

    /** 实体类型 → 圆点颜色令牌（按常见类型分色，未知类型回退中性色）。 */
    private static String colorForType(String type) {
        if (type == null) return "-jc-text-muted";
        return switch (type.toLowerCase()) {
            case "project", "项目" -> "-jc-primary-400";
            case "tool", "工具" -> "-jc-accent-400";
            case "person", "人物", "人" -> "-jc-warning";
            case "topic", "主题", "concept", "概念" -> "-jc-primary-300";
            default -> "-jc-text-muted";
        };
    }

    /** op → {显示标签, 徽标样式类}。 */
    private static String[] opMeta(String op) {
        if (op == null) return new String[]{"?", "jc-badge-stopped"};
        return switch (op.toUpperCase()) {
            case "ADD" -> new String[]{"新增", "jc-badge-ok"};
            case "UPDATE" -> new String[]{"编辑", "jc-badge-amber"};
            case "REMOVE" -> new String[]{"删除", "jc-badge-fail"};
            case "MERGE" -> new String[]{"合并", "jc-badge-indigo"};
            case "PERSONA_EDIT" -> new String[]{"人格", "jc-badge-soft"};
            case "CLEAR" -> new String[]{"清空", "jc-badge-fail"};
            default -> new String[]{op, "jc-badge-stopped"};
        };
    }

    /** 压成单行并截断（去掉换行，超出 max 截断加省略号）。 */
    private static String oneLine(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    private Label emptyHint(String t) {
        Label l = new Label(t);
        l.getStyleClass().add("sec-hint");
        l.setStyle("-fx-padding: 14 0 14 0; -fx-text-fill: -jc-text-hint;");
        return l;
    }

    private Label boldHint(String t) {
        Label l = new Label(t);
        l.setStyle("-fx-font-size: 13.5px; -fx-font-weight: 500; -fx-text-fill: -jc-text-muted;");
        return l;
    }

    private void toast(String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK);
        a.initOwner(stage);
        a.setHeaderText(null);
        a.showAndWait();
    }

    private static String fmt(long epochMs) {
        if (epochMs <= 0) return "";
        return TS_FMT.format(Instant.ofEpochMilli(epochMs));
    }
}
