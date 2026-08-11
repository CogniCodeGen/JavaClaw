package com.javaclaw.ui.javafx.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.json.JsonCodec;
import com.javaclaw.workflow.model.ConditionOperator;
import com.javaclaw.workflow.model.ConditionRule;
import com.javaclaw.workflow.model.EdgeDefinition;
import com.javaclaw.workflow.model.EdgeKind;
import com.javaclaw.workflow.model.GraphDefinition;
import com.javaclaw.workflow.model.NodeDefinition;
import com.javaclaw.workflow.model.NodeType;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseButton;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** 工作流画布 Controller；静态结构来自 FXML，仅节点、边和网格由算法动态渲染。 */
public final class WorkflowCanvasController implements AutoCloseable {

    private final WorkflowNodeCardFactory cards;
    private final WorkflowConditionDialogFactory conditions;
    private final JsonCodec json;
    private final FxDispatcher fx;
    private final Map<String, WorkflowNodeCard> renderedCards = new LinkedHashMap<>();

    @FXML private StackPane root;
    @FXML private ScrollPane viewport;
    @FXML private Group graphGroup;
    @FXML private Pane graphPane;
    @FXML private Pane gridPane;
    @FXML private Pane edgePane;
    @FXML private Label zoomLabel;
    @FXML private Label canvasHint;
    @FXML private ContextMenu nodeMenu;
    @FXML private MenuItem finishConnectionItem;
    @FXML private MenuItem normalConnectionItem;
    @FXML private MenuItem errorConnectionItem;
    @FXML private MenuItem cancelConnectionItem;
    @FXML private MenuItem deleteNodeItem;
    @FXML private ContextMenu edgeMenu;

    private WorkflowViewModel viewModel;
    private Runnable changed = () -> { };
    private Consumer<String> logger = ignored -> { };
    private String connectionSource;
    private EdgeKind connectionKind = EdgeKind.NORMAL;
    private NodeDefinition contextNode;
    private EdgeDefinition contextEdge;
    private ChangeListener<GraphDefinition> graphListener;
    private ChangeListener<NodeDefinition> selectionListener;

    public WorkflowCanvasController(
            WorkflowNodeCardFactory cards,
            WorkflowConditionDialogFactory conditions,
            JsonCodec json,
            FxDispatcher fx) {
        this.cards = Objects.requireNonNull(cards, "cards");
        this.conditions = Objects.requireNonNull(conditions, "conditions");
        this.json = Objects.requireNonNull(json, "json");
        this.fx = Objects.requireNonNull(fx, "fx");
    }

    @FXML
    private void initialize() {
        WorkflowGraphRenderer.drawGrid(gridPane);
    }

    void configure(WorkflowViewModel viewModel, Runnable changed, Consumer<String> logger) {
        this.viewModel = Objects.requireNonNull(viewModel, "viewModel");
        this.changed = Objects.requireNonNull(changed, "changed");
        this.logger = Objects.requireNonNull(logger, "logger");
        canvasHint.textProperty().bind(viewModel.canvasHintProperty());
        graphListener = (ignored, previous, graph) -> render(graph);
        selectionListener = (ignored, previous, node) -> updateSelectedStyle(
                node == null ? null : node.id());
        viewModel.graphProperty().addListener(graphListener);
        viewModel.selectedNodeProperty().addListener(selectionListener);
        viewModel.zoomProperty().addListener((ignored, previous, value) -> applyZoom(value.doubleValue()));
        applyZoom(viewModel.zoomProperty().get());
        render(viewModel.graphProperty().get());
    }

    void beginConnection(NodeDefinition source, EdgeKind kind) {
        if (source == null || viewModel == null) return;
        if (viewModel.readOnlyProperty().get()) {
            logger.accept("系统图为只读，复制为草稿后才能连线");
            return;
        }
        if (source.type() == NodeType.END) {
            logger.accept("END 节点不能创建出口");
            return;
        }
        connectionSource = source.id();
        connectionKind = kind == null ? EdgeKind.NORMAL : kind;
        viewModel.beginConnection(source, connectionKind == EdgeKind.ERROR);
        updateConnectionStyles();
        logger.accept("已选择源节点 [" + source.label() + "]，请点击或右键目标节点");
    }

    void cancelConnection() {
        connectionSource = null;
        connectionKind = EdgeKind.NORMAL;
        if (viewModel != null) viewModel.endConnection();
        updateConnectionStyles();
    }

    @FXML
    private void zoomScrolled(ScrollEvent event) {
        if (!event.isControlDown() || viewModel == null) return;
        setZoom(viewModel.zoomProperty().get() * (event.getDeltaY() > 0 ? 1.1 : 0.9));
        event.consume();
    }

    @FXML private void zoomIn() { setZoom(viewModel.zoomProperty().get() * 1.15); }

    @FXML private void zoomOut() { setZoom(viewModel.zoomProperty().get() / 1.15); }

    @FXML
    private void fitGraph() {
        GraphDefinition graph = viewModel == null ? null : viewModel.currentGraph();
        if (graph == null || graph.nodes().isEmpty()) return;
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (NodeDefinition node : graph.nodes()) {
            minX = Math.min(minX, node.x());
            minY = Math.min(minY, node.y());
            maxX = Math.max(maxX, node.x() + 210);
            maxY = Math.max(maxY, node.y() + 96);
        }
        double viewportWidth = Math.max(1, viewport.getViewportBounds().getWidth());
        double viewportHeight = Math.max(1, viewport.getViewportBounds().getHeight());
        double fitted = Math.min((viewportWidth - 64) / Math.max(1, maxX - minX),
                (viewportHeight - 64) / Math.max(1, maxY - minY));
        setZoom(fitted);
        double centerX = (minX + maxX) / 2.0 * viewModel.zoomProperty().get();
        double centerY = (minY + maxY) / 2.0 * viewModel.zoomProperty().get();
        fx.dispatchLater(() -> {
            viewport.setHvalue(normalizedCenter(centerX, viewportWidth,
                    graphGroup.getLayoutBounds().getWidth()));
            viewport.setVvalue(normalizedCenter(centerY, viewportHeight,
                    graphGroup.getLayoutBounds().getHeight()));
        });
    }

    @FXML private void finishConnectionFromMenu() { finishConnection(contextNode); }

    @FXML private void beginNormalFromMenu() { beginConnection(contextNode, EdgeKind.NORMAL); }

    @FXML private void beginErrorFromMenu() { beginConnection(contextNode, EdgeKind.ERROR); }

    @FXML private void inspectFromMenu() { selectNode(contextNode); }

    @FXML private void cancelConnectionFromMenu() { cancelConnection(); }

    @FXML
    private void deleteNodeFromMenu() {
        if (contextNode == null || viewModel == null || viewModel.readOnlyProperty().get()) return;
        viewModel.editor().deleteNode(contextNode.id());
        viewModel.selectNode(null);
        cancelConnection();
        changed.run();
    }

    @FXML
    private void deleteEdgeFromMenu() {
        if (contextEdge == null || viewModel == null || viewModel.readOnlyProperty().get()) return;
        viewModel.editor().deleteEdge(contextEdge.id());
        changed.run();
    }

    private void render(GraphDefinition graph) {
        renderedCards.clear();
        graphPane.getChildren().setAll(gridPane, edgePane);
        edgePane.getChildren().clear();
        if (graph == null) return;
        for (NodeDefinition node : graph.nodes()) addNodeCard(node);
        redrawEdges();
        updateConnectionStyles();
    }

    private void addNodeCard(NodeDefinition node) {
        WorkflowNodeCard card = cards.create(node);
        card.selected(viewModel.selectedNodeProperty().get() != null
                && viewModel.selectedNodeProperty().get().id().equals(node.id()));
        Runnable activate = () -> {
            if (connectionSource != null && !connectionSource.equals(node.id())
                    && !viewModel.readOnlyProperty().get()) {
                finishConnection(node);
            } else {
                selectNode(node);
            }
        };
        card.root().setOnAccessibleAction(activate);
        card.root().setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY) activate.run();
        });
        card.root().setOnContextMenuRequested(event -> showNodeMenu(card, event));
        installDrag(card);
        renderedCards.put(node.id(), card);
        graphPane.getChildren().add(card.root());
    }

    private void installDrag(WorkflowNodeCard card) {
        double[] offset = new double[2];
        boolean[] dragging = new boolean[1];
        card.root().setOnMousePressed(event -> {
            if (event.getButton() != MouseButton.PRIMARY || viewModel.readOnlyProperty().get()) return;
            Point2D point = graphPane.sceneToLocal(event.getSceneX(), event.getSceneY());
            offset[0] = point.getX() - card.root().getLayoutX();
            offset[1] = point.getY() - card.root().getLayoutY();
            dragging[0] = true;
        });
        card.root().setOnMouseDragged(event -> {
            if (!dragging[0] || viewModel.readOnlyProperty().get()) return;
            Point2D point = graphPane.sceneToLocal(event.getSceneX(), event.getSceneY());
            card.root().relocate(Math.max(0, point.getX() - offset[0]),
                    Math.max(52, point.getY() - offset[1]));
            redrawEdges();
        });
        card.root().setOnMouseReleased(event -> {
            if (!dragging[0] || event.getButton() != MouseButton.PRIMARY
                    || viewModel.readOnlyProperty().get()) return;
            dragging[0] = false;
            viewModel.editor().moveNode(card.node().id(), card.root().getLayoutX(),
                    card.root().getLayoutY());
            changed.run();
        });
    }

    private void redrawEdges() {
        GraphDefinition graph = viewModel == null ? null : viewModel.currentGraph();
        WorkflowGraphRenderer.drawEdges(edgePane, graph, renderedCards,
                (edge, curve, event) -> {
                    if (viewModel.readOnlyProperty().get()) return;
                    contextEdge = edge;
                    edgeMenu.show(curve, event.getScreenX(), event.getScreenY());
                    event.consume();
                });
    }

    private void showNodeMenu(WorkflowNodeCard card,
                              javafx.scene.input.ContextMenuEvent event) {
        contextNode = card.node();
        selectNode(contextNode);
        boolean connecting = connectionSource != null;
        finishConnectionItem.setVisible(connecting && !connectionSource.equals(contextNode.id()));
        cancelConnectionItem.setVisible(connecting);
        boolean canConnect = !viewModel.readOnlyProperty().get()
                && contextNode.type() != NodeType.END;
        normalConnectionItem.setDisable(!canConnect);
        errorConnectionItem.setDisable(!canConnect);
        normalConnectionItem.setText(contextNode.type() == NodeType.CONDITION
                ? "创建条件出口…" : "创建出口");
        deleteNodeItem.setVisible(!viewModel.readOnlyProperty().get()
                && contextNode.type() != NodeType.START && contextNode.type() != NodeType.END);
        nodeMenu.show(card.root(), event.getScreenX(), event.getScreenY());
        event.consume();
    }

    private void finishConnection(NodeDefinition target) {
        if (connectionSource == null || target == null) return;
        if (connectionSource.equals(target.id())) {
            logger.accept("不能连接节点自身，请选择其他节点");
            return;
        }
        try {
            if (!connect(connectionSource, target.id(), connectionKind)) return;
            cancelConnection();
            changed.run();
        } catch (RuntimeException failure) {
            logger.accept("连线失败：" + failure.getMessage());
        }
    }

    private boolean connect(String sourceId, String targetId, EdgeKind requestedKind) {
        NodeDefinition source = viewModel.currentGraph().nodes().stream()
                .filter(node -> node.id().equals(sourceId)).findFirst().orElseThrow();
        if (requestedKind == EdgeKind.ERROR) {
            viewModel.editor().connect(sourceId, targetId, EdgeKind.ERROR, null, 0, false);
            return true;
        } else if (source.type() != NodeType.CONDITION) {
            viewModel.editor().connect(sourceId, targetId);
            return true;
        }
        var selection = conditions.show(
                root.getScene() == null ? null : root.getScene().getWindow());
        if (selection.isEmpty()) return false;
        connectConditional(sourceId, targetId, selection.get());
        return true;
    }

    private void connectConditional(
            String sourceId,
            String targetId,
            WorkflowConditionDialogController.Selection selection) {
        ConditionRule rule = null;
        if (!selection.fallback()) {
            if (selection.path().isBlank()) {
                throw new IllegalArgumentException("条件状态路径不能为空");
            }
            JsonNode value = null;
            if (selection.operator() != ConditionOperator.EXISTS) {
                try {
                    value = json.tree(selection.value());
                } catch (Exception ignored) {
                    value = json.mapper().getNodeFactory().textNode(selection.value());
                }
            }
            rule = new ConditionRule(selection.path(), selection.operator(), value);
        }
        viewModel.editor().connect(sourceId, targetId, EdgeKind.CONDITIONAL, rule,
                selection.priority(), selection.fallback());
    }

    private void selectNode(NodeDefinition node) {
        if (viewModel != null) viewModel.selectNode(node);
        updateSelectedStyle(node == null ? null : node.id());
    }

    private void updateSelectedStyle(String selectedId) {
        renderedCards.forEach((id, card) -> card.selected(id.equals(selectedId)));
    }

    private void updateConnectionStyles() {
        renderedCards.forEach((id, card) -> {
            card.connectionSource(id.equals(connectionSource));
            card.connectionTarget(connectionSource != null && !id.equals(connectionSource));
        });
    }

    private void setZoom(double value) {
        if (viewModel != null) viewModel.zoomProperty().set(Math.max(0.1, Math.min(2.0, value)));
    }

    private void applyZoom(double value) {
        graphPane.setScaleX(value);
        graphPane.setScaleY(value);
        zoomLabel.setText(Math.round(value * 100) + "%");
    }

    private static double normalizedCenter(double center, double viewport, double content) {
        double scrollable = content - viewport;
        if (scrollable <= 0) return 0.5;
        return Math.max(0, Math.min(1, (center - viewport / 2.0) / scrollable));
    }

    @Override
    public void close() {
        nodeMenu.hide();
        edgeMenu.hide();
        if (viewModel != null) {
            canvasHint.textProperty().unbind();
            if (graphListener != null) viewModel.graphProperty().removeListener(graphListener);
            if (selectionListener != null) {
                viewModel.selectedNodeProperty().removeListener(selectionListener);
            }
        }
        renderedCards.clear();
    }
}
