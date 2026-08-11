package com.javaclaw.ui.javafx.memory;

import com.javaclaw.application.memory.MemoryApplicationService;
import com.javaclaw.application.memory.MemoryApplicationService.Snapshot;
import com.javaclaw.memory.graph.MemoryGraph;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import javafx.fxml.FXML;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.Objects;

/** 记忆图谱分区；图计算走托管 I/O，Canvas 只在 FX 线程渲染。 */
public final class MemoryGraphController implements MemorySectionController, AutoCloseable {
    @FXML private StackPane graphSurface;
    @FXML private Pane canvasHolder;
    @FXML private Canvas graphCanvas;
    @FXML private VBox graphEmpty;
    @FXML private Label graphHud;
    @FXML private Label graphStatus;
    @FXML private Slider focusDepth;
    @FXML private HBox factToggle;
    @FXML private HBox episodeToggle;
    @FXML private HBox entityToggle;
    @FXML private Label factCheck;
    @FXML private Label episodeCheck;
    @FXML private Label entityCheck;
    @FXML private VBox emptyInspector;
    @FXML private VBox detailInspector;
    @FXML private Label typeBadge;
    @FXML private Label nodeName;
    @FXML private VBox relatedHost;

    private final MemoryApplicationService useCases;
    private final MemoryComponentFactory components;
    private final UiAsyncAction<MemoryGraph> loadAction;
    private MemoryGraphRenderer renderer;
    private MemoryChildView<?> relatedView;
    private boolean factVisible = true;
    private boolean episodeVisible = true;
    private boolean entityVisible = true;
    private boolean loaded;
    private boolean dirty = true;

    public MemoryGraphController(
            MemoryApplicationService useCases,
            MemoryComponentFactory components,
            @Qualifier("workspaceTaskScope") TaskScope tasks,
            FxDispatcher fx) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.components = Objects.requireNonNull(components, "components");
        loadAction = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        renderer = new MemoryGraphRenderer(
                graphSurface, canvasHolder, graphCanvas, graphEmpty, graphHud);
        focusDepth.valueProperty().addListener((ignored, previous, value) ->
                renderer.setFocusDepth((int) Math.round(value.doubleValue())));
        renderer.setOnNodeSelected(this::showInspector);
        showInspector(null);
        refreshToggles();
    }

    @Override
    public void apply(Snapshot snapshot, String query) {
        dirty = true;
        if (loaded && !loadAction.busyProperty().get()) loadGraph();
    }

    @Override
    public void activated() {
        if (!loaded || dirty) loadGraph();
    }

    @FXML private void toggleFacts() { factVisible = !factVisible; applyTypes(); }
    @FXML private void toggleEpisodes() { episodeVisible = !episodeVisible; applyTypes(); }
    @FXML private void toggleEntities() { entityVisible = !entityVisible; applyTypes(); }
    @FXML private void rebuild() { dirty = true; loadGraph(); }

    private void loadGraph() {
        loaded = true;
        dirty = false;
        graphStatus.setText("构建中…");
        loadAction.execute(TaskSpec.io("memory-graph-build"), context -> useCases.graph(), graph -> {
            renderer.render(graph);
            graphStatus.setText(graph.nodes().size() + " 个节点 · " + graph.edges().size() + " 条边");
        }, failure -> {
            dirty = true;
            renderer.render(MemoryGraph.empty());
            graphStatus.setText("构建失败：" + failure.getMessage());
        });
    }

    private void applyTypes() {
        refreshToggles();
        renderer.setVisibleTypes(factVisible, episodeVisible, entityVisible);
    }

    private void refreshToggles() {
        toggle(factToggle, factCheck, factVisible);
        toggle(episodeToggle, episodeCheck, episodeVisible);
        toggle(entityToggle, entityCheck, entityVisible);
    }

    private static void toggle(HBox row, Label check, boolean visible) {
        check.setText(visible ? "✓" : "");
        row.setOpacity(visible ? 1.0 : 0.45);
    }

    @FXML private void resetView() { renderer.resetView(); }

    private void showInspector(MemoryGraphRenderer.NodeDetail detail) {
        boolean selected = detail != null;
        emptyInspector.setVisible(!selected);
        emptyInspector.setManaged(!selected);
        detailInspector.setVisible(selected);
        detailInspector.setManaged(selected);
        closeRelated();
        if (!selected) return;
        String[] type = type(detail.type());
        typeBadge.setText(type[0]);
        typeBadge.getStyleClass().removeAll(
                "jc-badge-ok", "jc-badge-amber", "jc-badge-soft", "jc-badge-stopped");
        typeBadge.getStyleClass().add(type[1]);
        nodeName.setText(detail.label());
        MemoryChildView<VBox> child = components.relatedMemories(detail.related());
        relatedView = child;
        relatedHost.getChildren().setAll(child.root());
    }

    private static String[] type(String value) {
        return switch (value == null ? "" : value) {
            case "fact" -> new String[]{"事实", "jc-badge-ok"};
            case "episode" -> new String[]{"情景", "jc-badge-amber"};
            case "entity" -> new String[]{"实体", "jc-badge-soft"};
            default -> new String[]{value == null ? "节点" : value, "jc-badge-stopped"};
        };
    }

    private void closeRelated() {
        relatedHost.getChildren().clear();
        if (relatedView != null) relatedView.close();
        relatedView = null;
    }

    String statusText() { return graphStatus.getText(); }

    @Override
    public void close() {
        loadAction.close();
        renderer.close();
        closeRelated();
    }
}
