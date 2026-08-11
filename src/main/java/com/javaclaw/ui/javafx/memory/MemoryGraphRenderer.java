package com.javaclaw.ui.javafx.memory;

import com.javaclaw.memory.graph.MemoryGraph;
import com.javaclaw.ui.javafx.theme.ThemeManager;
import javafx.animation.AnimationTimer;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.function.Consumer;

/**
 * FXML Canvas 上的记忆图谱力导向算法。
 *
 * <p>本类不创建布局控件；Canvas、空态和 HUD 均由 FXML 提供。所有公开方法必须在 FX
 * 线程调用。模拟稳定后自动停止，关闭会停止动画并解除主题监听。</p>
 */
final class MemoryGraphRenderer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MemoryGraphRenderer.class);
    private static final Font LABEL_FONT = Font.font(null, 11);
    private static final Font SELECTED_LABEL_FONT =
            Font.font(null, FontWeight.SEMI_BOLD, 11);

    record NodeDetail(
            String id, String label, String type, String group,
            String detail, List<String> related) {}

    private static final class GraphNode {
        private final String id;
        private final String label;
        private final String type;
        private final String group;
        private final String detail;
        private final int weight;
        private double x;
        private double y;
        private double velocityX;
        private double velocityY;

        private GraphNode(MemoryGraph.Node source, double x, double y) {
            id = source.id();
            label = source.label();
            type = source.type();
            group = source.group();
            detail = source.detail();
            weight = Math.max(1, source.weight());
            this.x = x;
            this.y = y;
        }
    }

    private record GraphEdge(GraphNode from, GraphNode to, String kind, double weight) {}

    private record Palette(
            Color background, Color text, Color muted, Color fact,
            Color episode, Color entity) {}

    private final StackPane surface;
    private final Canvas canvas;
    private final GraphicsContext graphics;
    private final VBox emptyState;
    private final Label hud;
    private final ThemeManager themes;
    private final boolean[] visibleTypes = {true, true, true};
    private final javafx.beans.value.ChangeListener<String> themeListener =
            (ignored, previous, current) -> applyTheme();
    private final AnimationTimer timer = new AnimationTimer() {
        @Override public void handle(long now) {
            step();
            draw();
            if (temperature < 0.01) {
                stop();
                loopRunning = false;
                draw();
            }
        }
    };

    private List<GraphNode> nodes = List.of();
    private List<GraphEdge> edges = List.of();
    private Map<String, List<String>> adjacency = Map.of();
    private Map<String, GraphNode> nodesById = Map.of();
    private Map<String, Integer> focusSet;
    private GraphNode selected;
    private GraphNode hovered;
    private GraphNode dragged;
    private Consumer<NodeDetail> selectionListener;
    private Palette palette;
    private double scale = 1;
    private double offsetX;
    private double offsetY;
    private double temperature;
    private double lastX;
    private double lastY;
    private double downX;
    private double downY;
    private int focusDepth = 3;
    private boolean dragging;
    private boolean panning;
    private boolean moved;
    private boolean loopRunning;
    private boolean closed;

    MemoryGraphRenderer(
            StackPane surface,
            Pane canvasHolder,
            Canvas canvas,
            VBox emptyState,
            Label hud,
            ThemeManager themes) {
        this.surface = Objects.requireNonNull(surface, "surface");
        this.canvas = Objects.requireNonNull(canvas, "canvas");
        this.emptyState = Objects.requireNonNull(emptyState, "emptyState");
        this.hud = Objects.requireNonNull(hud, "hud");
        this.themes = Objects.requireNonNull(themes, "themes");
        palette = derivePalette();
        graphics = canvas.getGraphicsContext2D();
        canvas.widthProperty().bind(canvasHolder.widthProperty());
        canvas.heightProperty().bind(canvasHolder.heightProperty());
        canvas.widthProperty().addListener((ignored, previous, current) -> draw());
        canvas.heightProperty().addListener((ignored, previous, current) -> draw());
        canvas.setCursor(Cursor.OPEN_HAND);
        wireMouse();
        themes.themeProperty().addListener(themeListener);
        applyTheme();
    }

    void render(MemoryGraph graph) {
        if (closed) return;
        MemoryGraph source = graph == null ? MemoryGraph.empty() : graph;
        double width = width() > 0 ? width() : 800;
        double height = height() > 0 ? height() : 600;
        Map<String, GraphNode> mapped = new HashMap<>();
        List<GraphNode> newNodes = new ArrayList<>(source.nodes().size());
        Random random = new Random();
        for (MemoryGraph.Node node : source.nodes()) {
            GraphNode mappedNode = new GraphNode(node,
                    width / 2 + (random.nextDouble() - 0.5) * Math.min(600, width),
                    height / 2 + (random.nextDouble() - 0.5) * Math.min(400, height));
            mapped.put(mappedNode.id, mappedNode);
            newNodes.add(mappedNode);
        }
        Map<String, List<String>> newAdjacency = new HashMap<>();
        List<GraphEdge> newEdges = new ArrayList<>();
        for (MemoryGraph.Edge edge : source.edges()) {
            GraphNode from = mapped.get(edge.from());
            GraphNode to = mapped.get(edge.to());
            if (from == null || to == null) continue;
            newEdges.add(new GraphEdge(from, to, edge.kind(), edge.weight()));
            newAdjacency.computeIfAbsent(from.id, ignored -> new ArrayList<>()).add(to.id);
            newAdjacency.computeIfAbsent(to.id, ignored -> new ArrayList<>()).add(from.id);
        }
        nodes = newNodes;
        edges = newEdges;
        adjacency = newAdjacency;
        nodesById = mapped;
        selected = null;
        hovered = null;
        focusSet = null;
        emptyState.setVisible(nodes.isEmpty());
        fitView();
        temperature = 1;
        ensureLoop();
        hud.setText(nodes.size() + " 节点 · " + edges.size() + " 边");
        emitSelection(null);
    }

    void setVisibleTypes(boolean fact, boolean episode, boolean entity) {
        visibleTypes[0] = fact;
        visibleTypes[1] = episode;
        visibleTypes[2] = entity;
        draw();
    }

    void setFocusDepth(int depth) {
        focusDepth = Math.max(1, Math.min(3, depth));
        computeFocus();
        draw();
    }

    void setOnNodeSelected(Consumer<NodeDetail> listener) {
        selectionListener = listener;
    }

    void resetView() {
        fitView();
        reheat();
        draw();
    }

    private void ensureLoop() {
        if (loopRunning || closed) return;
        loopRunning = true;
        timer.start();
    }

    private void step() {
        if (temperature < 0.01) return;
        final double repulsion = 5200;
        final double gravity = 0.025;
        final double damping = 0.86;
        double centerX = width() / 2;
        double centerY = height() / 2;
        for (int first = 0; first < nodes.size(); first++) {
            GraphNode left = nodes.get(first);
            for (int second = first + 1; second < nodes.size(); second++) {
                GraphNode right = nodes.get(second);
                double dx = left.x - right.x;
                double dy = left.y - right.y;
                double squaredDistance = dx * dx + dy * dy + 0.01;
                double distance = Math.sqrt(squaredDistance);
                double force = repulsion / squaredDistance;
                double unitX = dx / distance;
                double unitY = dy / distance;
                left.velocityX += unitX * force;
                left.velocityY += unitY * force;
                right.velocityX -= unitX * force;
                right.velocityY -= unitY * force;
            }
        }
        for (GraphEdge edge : edges) applyEdgeForce(edge);
        for (GraphNode node : nodes) {
            if (node == dragged) {
                node.velocityX = 0;
                node.velocityY = 0;
                continue;
            }
            node.velocityX += (centerX - node.x) * gravity;
            node.velocityY += (centerY - node.y) * gravity;
            node.x += node.velocityX * temperature;
            node.y += node.velocityY * temperature;
            node.velocityX *= damping;
            node.velocityY *= damping;
        }
        temperature *= 0.985;
    }

    private static void applyEdgeForce(GraphEdge edge) {
        double targetLength = switch (edge.kind()) {
            case "semantic" -> 78;
            case "about" -> 92;
            default -> 104;
        };
        double strength = "semantic".equals(edge.kind()) ? 0.012 : 0.02;
        double dx = edge.to().x - edge.from().x;
        double dy = edge.to().y - edge.from().y;
        double distance = Math.sqrt(dx * dx + dy * dy) + 0.01;
        double difference = (distance - targetLength) / distance * strength;
        double moveX = dx * difference;
        double moveY = dy * difference;
        edge.from().velocityX += moveX;
        edge.from().velocityY += moveY;
        edge.to().velocityX -= moveX;
        edge.to().velocityY -= moveY;
    }

    private void draw() {
        double width = width();
        double height = height();
        graphics.setFill(palette.background());
        graphics.fillRect(0, 0, width, height);
        for (GraphEdge edge : edges) drawEdge(edge);
        graphics.setGlobalAlpha(1);
        graphics.setLineDashes((double[]) null);
        boolean showLabels = scale > 0.75;
        for (GraphNode node : nodes) drawNode(node, showLabels);
    }

    private void drawEdge(GraphEdge edge) {
        if (!visible(edge.from()) || !visible(edge.to())) return;
        boolean highlighted = selected != null
                && (edge.from() == selected || edge.to() == selected);
        switch (edge.kind()) {
            case "semantic" -> {
                graphics.setStroke(palette.fact());
                graphics.setGlobalAlpha(highlighted ? 0.8 : 0.12 + 0.5 * edge.weight());
                graphics.setLineWidth(highlighted ? 1.6 : 1);
                graphics.setLineDashes((double[]) null);
            }
            case "about" -> {
                graphics.setStroke(palette.entity());
                graphics.setGlobalAlpha(highlighted ? 0.95 : 0.6);
                graphics.setLineWidth(1.2);
                graphics.setLineDashes(4, 3);
            }
            default -> {
                graphics.setStroke(palette.muted());
                graphics.setGlobalAlpha(highlighted ? 0.9 : 0.4);
                graphics.setLineWidth(1);
                graphics.setLineDashes((double[]) null);
            }
        }
        graphics.strokeLine(screenX(edge.from().x), screenY(edge.from().y),
                screenX(edge.to().x), screenY(edge.to().y));
    }

    private void drawNode(GraphNode node, boolean showLabels) {
        if (!visible(node)) return;
        double radius = radius(node) * Math.max(0.6, Math.min(1.6, scale));
        boolean selectedNode = node == selected;
        boolean hoveredNode = node == hovered;
        graphics.setGlobalAlpha(selected != null && !selectedNode && !isNeighbor(node) ? 0.3 : 1);
        graphics.setFill(nodeColor(node));
        graphics.fillOval(screenX(node.x) - radius, screenY(node.y) - radius,
                radius * 2, radius * 2);
        if (selectedNode || hoveredNode) {
            graphics.setGlobalAlpha(1);
            graphics.setLineWidth(2.5);
            graphics.setStroke(palette.text());
            graphics.strokeOval(screenX(node.x) - radius, screenY(node.y) - radius,
                    radius * 2, radius * 2);
        }
        graphics.setGlobalAlpha(1);
        if (!showLabels && !selectedNode && !hoveredNode) return;
        graphics.setFill(palette.text());
        graphics.setGlobalAlpha(selected != null && !selectedNode && !isNeighbor(node) ? 0.35 : 0.95);
        graphics.setFont(selectedNode ? SELECTED_LABEL_FONT : LABEL_FONT);
        graphics.fillText(node.label, screenX(node.x) + radius + 3, screenY(node.y) + 3);
        graphics.setGlobalAlpha(1);
    }

    private double width() { return canvas.getWidth(); }
    private double height() { return canvas.getHeight(); }
    private double screenX(double x) { return x * scale + offsetX; }
    private double screenY(double y) { return y * scale + offsetY; }

    private static double radius(GraphNode node) {
        return Math.min(22, 6 + Math.sqrt(node.weight) * 2.2);
    }

    private Color nodeColor(GraphNode node) {
        return switch (node.type) {
            case "fact" -> palette.fact();
            case "episode" -> palette.episode();
            default -> palette.entity();
        };
    }

    private boolean visible(GraphNode node) {
        return typeVisible(node) && (focusSet == null || focusSet.containsKey(node.id));
    }

    private boolean typeVisible(GraphNode node) {
        return switch (node.type) {
            case "fact" -> visibleTypes[0];
            case "episode" -> visibleTypes[1];
            default -> visibleTypes[2];
        };
    }

    private boolean isNeighbor(GraphNode node) {
        return selected != null && adjacency.getOrDefault(selected.id, List.of()).contains(node.id);
    }

    private void fitView() {
        scale = 1;
        offsetX = 0;
        offsetY = 0;
    }

    private void reheat() {
        temperature = Math.max(temperature, 0.5);
        ensureLoop();
    }

    private void computeFocus() {
        if (selected == null || focusDepth >= 3) {
            focusSet = null;
            return;
        }
        Map<String, Integer> distance = new HashMap<>();
        distance.put(selected.id, 0);
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(selected.id);
        while (!queue.isEmpty()) {
            String id = queue.remove();
            int depth = distance.get(id);
            if (depth >= focusDepth) continue;
            for (String neighbor : adjacency.getOrDefault(id, List.of())) {
                if (!distance.containsKey(neighbor)) {
                    distance.put(neighbor, depth + 1);
                    queue.add(neighbor);
                }
            }
        }
        focusSet = distance;
    }

    private GraphNode pick(double mouseX, double mouseY) {
        for (int index = nodes.size() - 1; index >= 0; index--) {
            GraphNode node = nodes.get(index);
            if (!visible(node)) continue;
            double dx = mouseX - screenX(node.x);
            double dy = mouseY - screenY(node.y);
            double radius = radius(node) * Math.max(0.6, Math.min(1.6, scale)) + 3;
            if (dx * dx + dy * dy <= radius * radius) return node;
        }
        return null;
    }

    private void wireMouse() {
        canvas.setOnMousePressed(event -> {
            downX = event.getX();
            downY = event.getY();
            lastX = downX;
            lastY = downY;
            moved = false;
            dragged = pick(downX, downY);
            dragging = dragged != null;
            panning = !dragging;
            if (panning) canvas.setCursor(Cursor.CLOSED_HAND);
        });
        canvas.setOnMouseDragged(event -> drag(event.getX(), event.getY()));
        canvas.setOnMouseReleased(event -> release());
        canvas.setOnMouseMoved(event -> {
            GraphNode current = pick(event.getX(), event.getY());
            if (current == hovered) return;
            hovered = current;
            canvas.setCursor(current == null ? Cursor.OPEN_HAND : Cursor.HAND);
            draw();
        });
        canvas.setOnScroll(event -> {
            double nextScale = Math.max(0.2, Math.min(4,
                    scale * (event.getDeltaY() > 0 ? 1.1 : 0.9)));
            offsetX = event.getX() - (event.getX() - offsetX) * (nextScale / scale);
            offsetY = event.getY() - (event.getY() - offsetY) * (nextScale / scale);
            scale = nextScale;
            draw();
            event.consume();
        });
    }

    private void drag(double mouseX, double mouseY) {
        if (Math.abs(mouseX - downX) + Math.abs(mouseY - downY) > 3) moved = true;
        if (dragging && dragged != null) {
            dragged.x = (mouseX - offsetX) / scale;
            dragged.y = (mouseY - offsetY) / scale;
            reheat();
            return;
        }
        if (!panning) return;
        offsetX += mouseX - lastX;
        offsetY += mouseY - lastY;
        lastX = mouseX;
        lastY = mouseY;
        draw();
    }

    private void release() {
        if (dragging && !moved && dragged != null) {
            selected = selected == dragged ? null : dragged;
            computeFocus();
            emitSelection(selected);
            draw();
        } else if (panning && !moved) {
            selected = null;
            computeFocus();
            emitSelection(null);
            draw();
        }
        dragging = false;
        panning = false;
        dragged = null;
        canvas.setCursor(hovered == null ? Cursor.OPEN_HAND : Cursor.HAND);
    }

    private void emitSelection(GraphNode node) {
        if (selectionListener == null) return;
        NodeDetail detail = null;
        if (node != null) {
            List<String> related = adjacency.getOrDefault(node.id, List.of()).stream()
                    .limit(8).map(nodesById::get).filter(Objects::nonNull)
                    .map(item -> item.label).toList();
            detail = new NodeDetail(node.id, node.label, node.type,
                    node.group == null ? "" : node.group,
                    node.detail == null ? "" : node.detail, related);
        }
        try {
            selectionListener.accept(detail);
        } catch (RuntimeException failure) {
            log.warn("图谱选择回调失败: {}", failure.getMessage());
        }
    }

    private void applyTheme() {
        if (closed) return;
        palette = derivePalette();
        surface.setStyle("-fx-background-color: " + hex(palette.background()) + ";");
        hud.setTextFill(palette.muted());
        draw();
    }

    private Palette derivePalette() {
        String background = "#FBFAF6";
        String brand = "#2E9A6A";
        try {
            ThemeManager.Theme theme = themes.getCurrentTheme();
            background = theme.bg();
            brand = theme.brand();
        } catch (RuntimeException ignored) {
            // 主题系统尚未初始化时使用默认调色板。
        }
        Color bg = safeColor(background, Color.web("#FBFAF6"));
        boolean dark = 0.2126 * bg.getRed() + 0.7152 * bg.getGreen()
                + 0.0722 * bg.getBlue() < 0.42;
        return new Palette(bg,
                Color.web(dark ? "#F3F1EB" : "#27251F"),
                Color.web(dark ? "#9C9587" : "#706B5F"),
                safeColor(brand, Color.web("#2E9A6A")),
                Color.web(dark ? "#D9A23C" : "#C68A1E"),
                Color.web(dark ? "#9B79D6" : "#7E57C2"));
    }

    private static Color safeColor(String value, Color fallback) {
        try { return Color.web(value); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static String hex(Color color) {
        return String.format("#%02X%02X%02X",
                (int) Math.round(color.getRed() * 255),
                (int) Math.round(color.getGreen() * 255),
                (int) Math.round(color.getBlue() * 255));
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        timer.stop();
        loopRunning = false;
        themes.themeProperty().removeListener(themeListener);
        canvas.widthProperty().unbind();
        canvas.heightProperty().unbind();
        canvas.setOnMousePressed(null);
        canvas.setOnMouseDragged(null);
        canvas.setOnMouseReleased(null);
        canvas.setOnMouseMoved(null);
        canvas.setOnScroll(null);
        selectionListener = null;
    }
}
