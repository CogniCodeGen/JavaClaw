package com.javaclaw.ui.javafx.memory;

import com.javaclaw.memory.graph.MemoryGraph;
import com.javaclaw.ui.javafx.theme.ThemeManager;
import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 记忆图谱视图 —— 纯 JavaFX Canvas 力导向布局渲染 {@link MemoryGraph}（已去 WebView 化）。
 *
 * <p><b>零外部依赖</b>：力导向模拟走 {@link AnimationTimer}（稳定后自动停帧零 CPU），
 * 绘制走 Canvas {@link GraphicsContext}，缩放/拖拽/点击/悬停为原生鼠标事件，
 * 不引入任何图可视化第三方库。原为项目内最后一处 WebView 使用点，迁移后
 * {@code javafx-web} 依赖已整体移除。</p>
 *
 * <p><b>配色随主题</b>：背景/文字/次要/边线从当前 {@link ThemeManager} 主题派生（深浅自适应），
 * 主题切换实时重绘；三类节点的类别色（事实/情景/实体）跨主题固定，属语义色
 * （与 CLAUDE.md「状态语义色跨主题固定」一致）。Canvas 像素绘制无法走 CSS 令牌，
 * 程序化派生是本视图的既定模式。</p>
 *
 * @author JavaClaw
 */
public class MemoryGraphView {

    private static final Logger log = LoggerFactory.getLogger(MemoryGraphView.class);

    /** 选中节点详情（供外部检视器渲染）。 */
    public record NodeDetail(String id, String label, String type, String group,
                             String detail, List<String> related) {}

    // ==================== 场景图结构 ====================

    private final StackPane root = new StackPane();
    private final Canvas canvas = new Canvas();
    private final GraphicsContext gc = canvas.getGraphicsContext2D();
    private final VBox emptyBox = new VBox(4);
    private final VBox legendBox = new VBox(3);
    private final VBox infoBox = new VBox(4);
    private final Label hudLabel = new Label();
    private final Button resetButton = new Button("重置视图");

    // ==================== 模拟状态（与旧 JS 实现一一对应） ====================

    /** 模拟节点 */
    private static final class GNode {
        final String id, label, type, group, detail;
        final int weight;
        double x, y, vx, vy;
        GNode(MemoryGraph.Node n, double x, double y) {
            this.id = n.id(); this.label = n.label(); this.type = n.type();
            this.group = n.group(); this.detail = n.detail();
            this.weight = Math.max(1, n.weight());
            this.x = x; this.y = y;
        }
    }

    /** 模拟边 */
    private record GEdge(GNode a, GNode b, String kind, double weight) {}

    private List<GNode> nodes = new ArrayList<>();
    private List<GEdge> edges = new ArrayList<>();
    private Map<String, List<String>> adj = new HashMap<>();
    private Map<String, GNode> byId = new HashMap<>();

    private double scale = 1, offX = 0, offY = 0;
    private double alpha = 0;                    // 模拟温度
    private GNode selected, hover, dragNode;
    private boolean dragging, panning, moved;
    private double lastX, lastY, downX, downY;

    private final boolean[] visibleTypes = {true, true, true};   // fact / episode / entity
    private int focusDepth = 3;
    private Map<String, Integer> focusSet = null;                // null = 不聚焦

    private Consumer<NodeDetail> selectionListener;
    private volatile boolean disposed = false;

    // ==================== 主题配色（派生自 ThemeManager，类别色语义固定） ====================

    private record Palette(Color bg, Color text, Color muted, Color edge, Color panel,
                           Color fact, Color episode, Color entity, boolean dark) {}
    private Palette pal = derivePalette();

    /** 主题切换监听：实时重派生配色（dispose 时解除，避免泄漏） */
    private final javafx.beans.value.ChangeListener<String> themeListener =
            (obs, o, n) -> { if (!disposed) applyTheme(); };

    // ==================== 动画循环（alpha 冷却自动停帧） ====================

    private boolean loopRunning = false;
    private final AnimationTimer timer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            step();
            draw();
            if (alpha < 0.01) {
                stop();
                loopRunning = false;
                draw();
            }
        }
    };

    private void ensureLoop() {
        if (!loopRunning) {
            loopRunning = true;
            timer.start();
        }
    }

    // ==================== 构建 ====================

    public MemoryGraphView() {
        // Canvas 尺寸跟随容器（Pane 承载，Canvas 自身不可 resize）
        Pane canvasHolder = new Pane(canvas);
        canvas.widthProperty().bind(canvasHolder.widthProperty());
        canvas.heightProperty().bind(canvasHolder.heightProperty());
        canvas.widthProperty().addListener((o, ov, nv) -> draw());
        canvas.heightProperty().addListener((o, ov, nv) -> draw());
        canvas.setCursor(Cursor.OPEN_HAND);

        // 空态提示（居中）
        Label emptyTitle = new Label("暂无记忆图谱数据");
        emptyTitle.setFont(Font.font(14));
        Label emptyHint = new Label("随着对话积累事实与实体，这里会逐渐长出关联网络");
        emptyHint.setFont(Font.font(12));
        emptyBox.getChildren().addAll(emptyTitle, emptyHint);
        emptyBox.setAlignment(Pos.CENTER);
        emptyBox.setPickOnBounds(false);
        emptyBox.setVisible(false);
        emptyBox.setMouseTransparent(true);

        // 图例（左上）
        legendBox.setPadding(new Insets(8, 10, 8, 10));
        legendBox.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane.setAlignment(legendBox, Pos.TOP_LEFT);
        StackPane.setMargin(legendBox, new Insets(10));

        // 内置详情面板（右上；注册外部检视器后不再使用）
        infoBox.setPadding(new Insets(10, 12, 10, 12));
        infoBox.setMaxWidth(280);
        infoBox.setMaxHeight(Region.USE_PREF_SIZE);
        infoBox.setVisible(false);
        StackPane.setAlignment(infoBox, Pos.TOP_RIGHT);
        StackPane.setMargin(infoBox, new Insets(10));

        // HUD（左下）与重置按钮（右下）
        hudLabel.setFont(Font.font(11.5));
        hudLabel.setOpacity(0.65);
        hudLabel.setMouseTransparent(true);
        StackPane.setAlignment(hudLabel, Pos.BOTTOM_LEFT);
        StackPane.setMargin(hudLabel, new Insets(10));
        resetButton.setFont(Font.font(12));
        resetButton.setCursor(Cursor.HAND);
        resetButton.setFocusTraversable(false);
        resetButton.setOnAction(e -> { fitView(); reheat(); draw(); });
        StackPane.setAlignment(resetButton, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(resetButton, new Insets(10));

        root.getChildren().addAll(canvasHolder, emptyBox, legendBox, infoBox, hudLabel, resetButton);
        wireMouse();

        ThemeManager.themeProperty().addListener(themeListener);
        applyTheme();
    }

    /** 获取视图根节点（用于添加到场景图）。 */
    public Region getView() {
        return root;
    }

    // ==================== 对外 API（与旧 WebView 版保持一致） ====================

    /** 渲染一张图谱快照（须在 JavaFX 线程调用）。 */
    public void render(MemoryGraph graph) {
        if (disposed) return;
        MemoryGraph g = graph == null ? MemoryGraph.empty() : graph;
        double w = viewW() > 0 ? viewW() : 800;
        double h = viewH() > 0 ? viewH() : 600;
        Map<String, GNode> idMap = new HashMap<>();
        List<GNode> ns = new ArrayList<>(g.nodes().size());
        java.util.Random rnd = new java.util.Random();
        for (MemoryGraph.Node n : g.nodes()) {
            GNode gn = new GNode(n,
                    w / 2 + (rnd.nextDouble() - 0.5) * Math.min(600, w),
                    h / 2 + (rnd.nextDouble() - 0.5) * Math.min(400, h));
            idMap.put(gn.id, gn);
            ns.add(gn);
        }
        Map<String, List<String>> adjacency = new HashMap<>();
        List<GEdge> es = new ArrayList<>();
        for (MemoryGraph.Edge e : g.edges()) {
            GNode a = idMap.get(e.from()), b = idMap.get(e.to());
            if (a == null || b == null) continue;
            es.add(new GEdge(a, b, e.kind(), e.weight()));
            adjacency.computeIfAbsent(a.id, k -> new ArrayList<>()).add(b.id);
            adjacency.computeIfAbsent(b.id, k -> new ArrayList<>()).add(a.id);
        }
        nodes = ns;
        edges = es;
        adj = adjacency;
        byId = idMap;
        selected = null;
        hover = null;
        focusSet = null;
        infoBox.setVisible(false);
        emptyBox.setVisible(nodes.isEmpty());
        fitView();
        alpha = 1.0;
        ensureLoop();
        hudLabel.setText(nodes.size() + " 节点 · " + edges.size() + " 边");
    }

    /** 设置类别可见性（事实 / 情景 / 实体），实时过滤显示。 */
    public void setVisibleTypes(boolean fact, boolean episode, boolean entity) {
        visibleTypes[0] = fact;
        visibleTypes[1] = episode;
        visibleTypes[2] = entity;
        draw();
    }

    /** 设置聚焦深度：1=直接邻居 / 2=两跳 / 3=全部（仅在选中节点时生效）。 */
    public void setFocusDepth(int depth) {
        focusDepth = Math.max(1, Math.min(3, depth));
        computeFocus();
        draw();
    }

    /** 注册外部检视器回调：启用后改用外部面板展示节点详情（隐藏内置面板）。 */
    public void setOnNodeSelected(Consumer<NodeDetail> cb) {
        this.selectionListener = cb;
        if (cb != null) infoBox.setVisible(false);
    }

    /** 释放动画循环与监听（视图关闭时调用）。 */
    public void dispose() {
        if (disposed) return;
        disposed = true;
        timer.stop();
        loopRunning = false;
        ThemeManager.themeProperty().removeListener(themeListener);
    }

    // ==================== 力导向模拟（参数与旧 JS 版一致） ====================

    private void step() {
        if (alpha < 0.01) return;
        int n = nodes.size();
        final double rep = 5200, grav = 0.025, damp = 0.86;
        double cx = viewW() / 2, cy = viewH() / 2;
        for (int i = 0; i < n; i++) {
            GNode a = nodes.get(i);
            for (int j = i + 1; j < n; j++) {
                GNode b = nodes.get(j);
                double dx = a.x - b.x, dy = a.y - b.y;
                double d2 = dx * dx + dy * dy + 0.01;
                double d = Math.sqrt(d2);
                double f = rep / d2;
                double ux = dx / d, uy = dy / d;
                a.vx += ux * f; a.vy += uy * f;
                b.vx -= ux * f; b.vy -= uy * f;
            }
        }
        for (GEdge ed : edges) {
            double len = switch (ed.kind()) {
                case "semantic" -> 78; case "about" -> 92; default -> 104;
            };
            double k = "semantic".equals(ed.kind()) ? 0.012 : 0.02;
            double dx = ed.b().x - ed.a().x, dy = ed.b().y - ed.a().y;
            double dist = Math.sqrt(dx * dx + dy * dy) + 0.01;
            double diff = (dist - len) / dist * k;
            double mx = dx * diff, my = dy * diff;
            ed.a().vx += mx; ed.a().vy += my;
            ed.b().vx -= mx; ed.b().vy -= my;
        }
        for (GNode p : nodes) {
            if (p == dragNode) { p.vx = 0; p.vy = 0; continue; }
            p.vx += (cx - p.x) * grav;
            p.vy += (cy - p.y) * grav;
            p.x += p.vx * alpha;
            p.y += p.vy * alpha;
            p.vx *= damp;
            p.vy *= damp;
        }
        alpha *= 0.985;
    }

    // ==================== 绘制 ====================

    private double viewW() { return canvas.getWidth(); }
    private double viewH() { return canvas.getHeight(); }
    private double sx(double x) { return x * scale + offX; }
    private double sy(double y) { return y * scale + offY; }

    private static double radius(GNode n) {
        return Math.min(22, 6 + Math.sqrt(n.weight) * 2.2);
    }

    private Color nodeColor(GNode n) {
        return switch (n.type) {
            case "fact" -> pal.fact(); case "episode" -> pal.episode(); default -> pal.entity();
        };
    }

    private boolean inFocus(GNode nd) {
        return focusSet == null || focusSet.containsKey(nd.id);
    }

    private boolean typeVisible(GNode nd) {
        return switch (nd.type) {
            case "fact" -> visibleTypes[0];
            case "episode" -> visibleTypes[1];
            default -> visibleTypes[2];
        };
    }

    private boolean visible(GNode nd) {
        return typeVisible(nd) && inFocus(nd);
    }

    private boolean isNeighbor(GNode nd) {
        if (selected == null) return false;
        List<String> ns = adj.get(selected.id);
        return ns != null && ns.contains(nd.id);
    }

    private static final Font LABEL_FONT = Font.font(null, 11);
    private static final Font LABEL_FONT_SEL = Font.font(null, FontWeight.SEMI_BOLD, 11);

    private void draw() {
        double w = viewW(), h = viewH();
        gc.setFill(pal.bg());
        gc.fillRect(0, 0, w, h);
        // 边
        for (GEdge ed : edges) {
            if (!visible(ed.a()) || !visible(ed.b())) continue;
            boolean hot = selected != null && (ed.a() == selected || ed.b() == selected);
            switch (ed.kind()) {
                case "semantic" -> {
                    gc.setStroke(pal.fact());
                    gc.setGlobalAlpha(hot ? 0.8 : (0.12 + 0.5 * ed.weight()));
                    gc.setLineWidth(hot ? 1.6 : 1.0);
                    gc.setLineDashes((double[]) null);
                }
                case "about" -> {
                    gc.setStroke(pal.entity());
                    gc.setGlobalAlpha(hot ? 0.95 : 0.6);
                    gc.setLineWidth(1.2);
                    gc.setLineDashes(4, 3);
                }
                default -> {
                    gc.setStroke(pal.muted());
                    gc.setGlobalAlpha(hot ? 0.9 : 0.4);
                    gc.setLineWidth(1.0);
                    gc.setLineDashes((double[]) null);
                }
            }
            gc.strokeLine(sx(ed.a().x), sy(ed.a().y), sx(ed.b().x), sy(ed.b().y));
        }
        gc.setGlobalAlpha(1);
        gc.setLineDashes((double[]) null);
        // 节点
        boolean showLabel = scale > 0.75;
        for (GNode nd : nodes) {
            if (!visible(nd)) continue;
            double r = radius(nd) * Math.max(0.6, Math.min(1.6, scale));
            boolean isSel = nd == selected, isHov = nd == hover;
            gc.setGlobalAlpha(selected != null && !isSel && !isNeighbor(nd) ? 0.3 : 1);
            gc.setFill(nodeColor(nd));
            gc.fillOval(sx(nd.x) - r, sy(nd.y) - r, r * 2, r * 2);
            if (isSel || isHov) {
                gc.setGlobalAlpha(1);
                gc.setLineWidth(2.5);
                gc.setStroke(pal.text());
                gc.strokeOval(sx(nd.x) - r, sy(nd.y) - r, r * 2, r * 2);
            }
            gc.setGlobalAlpha(1);
            if (showLabel || isSel || isHov) {
                gc.setFill(pal.text());
                gc.setGlobalAlpha(selected != null && !isSel && !isNeighbor(nd) ? 0.35 : 0.95);
                gc.setFont(isSel ? LABEL_FONT_SEL : LABEL_FONT);
                gc.fillText(nd.label, sx(nd.x) + r + 3, sy(nd.y) + 3);
                gc.setGlobalAlpha(1);
            }
        }
    }

    // ==================== 视图适配 / 聚焦 ====================

    private void fitView() {
        scale = 1;
        offX = 0;
        offY = 0;
    }

    private void reheat() {
        alpha = Math.max(alpha, 0.5);
        ensureLoop();
    }

    /** BFS 计算选中节点 focusDepth 跳内的可见集（depth>=3 视为全部可见）。 */
    private void computeFocus() {
        if (selected == null || focusDepth >= 3) { focusSet = null; return; }
        Map<String, Integer> dist = new HashMap<>();
        dist.put(selected.id, 0);
        java.util.ArrayDeque<String> q = new java.util.ArrayDeque<>();
        q.add(selected.id);
        while (!q.isEmpty()) {
            String id = q.poll();
            int d = dist.get(id);
            if (d >= focusDepth) continue;
            for (String nb : adj.getOrDefault(id, List.of())) {
                if (!dist.containsKey(nb)) { dist.put(nb, d + 1); q.add(nb); }
            }
        }
        focusSet = dist;
    }

    // ==================== 命中测试与交互 ====================

    private GNode pick(double mx, double my) {
        for (int i = nodes.size() - 1; i >= 0; i--) {
            GNode nd = nodes.get(i);
            if (!visible(nd)) continue;
            double dx = mx - sx(nd.x), dy = my - sy(nd.y);
            double r = radius(nd) * Math.max(0.6, Math.min(1.6, scale)) + 3;
            if (dx * dx + dy * dy <= r * r) return nd;
        }
        return null;
    }

    private void wireMouse() {
        canvas.setOnMousePressed(e -> {
            double mx = e.getX(), my = e.getY();
            downX = mx; downY = my; lastX = mx; lastY = my; moved = false;
            GNode nd = pick(mx, my);
            if (nd != null) {
                dragNode = nd;
                dragging = true;
            } else {
                panning = true;
                canvas.setCursor(Cursor.CLOSED_HAND);
            }
        });
        canvas.setOnMouseDragged(e -> {
            double mx = e.getX(), my = e.getY();
            if (Math.abs(mx - downX) + Math.abs(my - downY) > 3) moved = true;
            if (dragging && dragNode != null) {
                dragNode.x = (mx - offX) / scale;
                dragNode.y = (my - offY) / scale;
                reheat();
                return;
            }
            if (panning) {
                offX += mx - lastX;
                offY += my - lastY;
                lastX = mx;
                lastY = my;
                draw();
            }
        });
        canvas.setOnMouseReleased(e -> {
            if (dragging && !moved && dragNode != null) {
                selected = (selected == dragNode) ? null : dragNode;
                computeFocus();
                if (selected != null) {
                    if (selectionListener != null) emitSelect(); else showInfo(selected);
                } else {
                    infoBox.setVisible(false);
                    emitClear();
                }
                draw();
            } else if (panning && !moved) {
                selected = null;
                computeFocus();
                infoBox.setVisible(false);
                emitClear();
                draw();
            }
            dragging = false;
            panning = false;
            dragNode = null;
            canvas.setCursor(hover != null ? Cursor.HAND : Cursor.OPEN_HAND);
        });
        canvas.setOnMouseMoved(e -> {
            GNode h = pick(e.getX(), e.getY());
            if (h != hover) {
                hover = h;
                canvas.setCursor(h != null ? Cursor.HAND : Cursor.OPEN_HAND);
                draw();
            }
        });
        canvas.setOnScroll(e -> {
            double mx = e.getX(), my = e.getY();
            double factor = e.getDeltaY() > 0 ? 1.1 : 0.9;
            double ns = Math.max(0.2, Math.min(4, scale * factor));
            // 以光标为锚点缩放
            offX = mx - (mx - offX) * (ns / scale);
            offY = my - (my - offY) * (ns / scale);
            scale = ns;
            draw();
            e.consume();
        });
    }

    // ==================== 选择回调 / 内置详情 ====================

    private void emitSelect() {
        if (selected == null || selectionListener == null) return;
        List<String> related = new ArrayList<>();
        for (String nb : adj.getOrDefault(selected.id, List.of())) {
            if (related.size() >= 8) break;
            GNode g = byId.get(nb);
            if (g != null) related.add(g.label);
        }
        try {
            selectionListener.accept(new NodeDetail(
                    selected.id, selected.label, selected.type,
                    selected.group == null ? "" : selected.group,
                    selected.detail == null ? "" : selected.detail, related));
        } catch (Exception e) {
            log.warn("图谱选择回调失败: {}", e.getMessage());
        }
    }

    private void emitClear() {
        if (selectionListener == null) return;
        try {
            selectionListener.accept(null);
        } catch (Exception e) {
            log.warn("图谱清除回调失败: {}", e.getMessage());
        }
    }

    /** 内置详情面板（未注册外部检视器时使用）。 */
    private void showInfo(GNode nd) {
        String typeName = switch (nd.type) {
            case "fact" -> "事实"; case "episode" -> "情景"; default -> "实体";
        };
        infoBox.getChildren().clear();
        Label title = new Label(nd.label);
        title.setFont(Font.font(null, FontWeight.SEMI_BOLD, 12.5));
        title.setWrapText(true);
        Label meta = new Label("类型：" + typeName + "　分组：" + (nd.group == null ? "" : nd.group));
        meta.setFont(Font.font(12.5));
        meta.setOpacity(0.7);
        Label detail = new Label(nd.detail == null ? "" : nd.detail);
        detail.setFont(Font.font(12.5));
        detail.setWrapText(true);
        infoBox.getChildren().addAll(title, meta, detail);
        styleInfoBox();
        infoBox.setVisible(true);
    }

    // ==================== 主题配色派生 ====================

    private static Palette derivePalette() {
        String bgHex = "#FBFAF6", surfaceHex = "#FFFFFF", brandHex = "#2E9A6A";
        try {
            ThemeManager.Theme t = ThemeManager.getCurrentTheme();
            bgHex = t.bg();
            surfaceHex = t.surface();
            brandHex = t.brand();
        } catch (Exception ignore) {}
        Color bg = safeColor(bgHex, Color.web("#FBFAF6"));
        boolean dark = 0.2126 * bg.getRed() + 0.7152 * bg.getGreen() + 0.0722 * bg.getBlue() < 0.42;
        return new Palette(
                bg,
                Color.web(dark ? "#F3F1EB" : "#27251F"),
                Color.web(dark ? "#9C9587" : "#706B5F"),
                Color.web(dark ? "#3A372F" : "#D9D4C8"),
                safeColor(surfaceHex, Color.WHITE),
                safeColor(brandHex, Color.web("#2E9A6A")),   // 事实 = 品牌色（随主题）
                Color.web(dark ? "#D9A23C" : "#C68A1E"),     // 情景 = 琥珀
                Color.web(dark ? "#9B79D6" : "#7E57C2"),     // 实体 = 梅紫
                dark);
    }

    private static Color safeColor(String hex, Color fallback) {
        try {
            return Color.web(hex);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String hex(Color c) {
        return String.format("#%02X%02X%02X",
                (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255));
    }

    /** 重派生配色并应用到覆盖层控件 + 重绘画布。 */
    private void applyTheme() {
        pal = derivePalette();
        root.setStyle("-fx-background-color: " + hex(pal.bg()) + ";");
        String panelStyle = "-fx-background-color: " + hex(pal.panel())
                + "; -fx-background-radius: 8; -fx-border-radius: 8; -fx-border-color: "
                + hex(pal.edge()) + ";";
        legendBox.setStyle(panelStyle);
        resetButton.setStyle(panelStyle + "-fx-text-fill: " + hex(pal.text()) + ";");
        hudLabel.setTextFill(pal.muted());
        for (var child : emptyBox.getChildren()) {
            if (child instanceof Label l) l.setTextFill(pal.muted());
        }
        rebuildLegend();
        styleInfoBox();
        draw();
    }

    private void styleInfoBox() {
        infoBox.setStyle("-fx-background-color: " + hex(pal.panel())
                + "; -fx-background-radius: 10; -fx-border-radius: 10; -fx-border-color: "
                + hex(pal.edge()) + ";"
                + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.18), 16, 0, 0, 4);");
        for (var child : infoBox.getChildren()) {
            if (child instanceof Label l) l.setTextFill(pal.text());
        }
    }

    private void rebuildLegend() {
        legendBox.getChildren().setAll(
                legendDotRow(pal.fact(), "事实"),
                legendDotRow(pal.episode(), "情景"),
                legendDotRow(pal.entity(), "实体"),
                spacer(4),
                legendLineRow(pal.muted(), "来源", false),
                legendLineRow(pal.entity(), "关联实体", true),
                legendLineRow(pal.fact(), "语义相近", false));
    }

    private HBox legendDotRow(Color color, String text) {
        Circle dot = new Circle(5, color);
        return legendRow(dot, text);
    }

    private HBox legendLineRow(Color color, String text, boolean dashed) {
        Line ln = new Line(0, 0, 16, 0);
        ln.setStroke(color);
        ln.setStrokeWidth(2);
        if (dashed) ln.getStrokeDashArray().setAll(4d, 3d);
        return legendRow(ln, text);
    }

    private HBox legendRow(javafx.scene.Node mark, String text) {
        Label l = new Label(text);
        l.setFont(Font.font(12));
        l.setTextFill(pal.text());
        HBox row = new HBox(6, mark, l);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Region spacer(double h) {
        Region r = new Region();
        r.setPrefHeight(h);
        return r;
    }
}
