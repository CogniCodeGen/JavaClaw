package com.javaclaw.ui.javafx.memory;

import com.javaclaw.application.memory.MemoryApplicationService;
import com.javaclaw.application.memory.MemoryApplicationService.OperationResult;
import com.javaclaw.application.memory.MemoryApplicationService.Snapshot;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import com.javaclaw.ui.javafx.control.WindowToastController;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Window;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 记忆中心窗口 Controller：只协调导航、异步 UseCase 和分区状态。 */
public final class MemoryViewController implements MemorySectionHost, AutoCloseable {

    @FXML private HBox layout;
    @FXML private TextField searchField;
    @FXML private Label scaleMain;
    @FXML private Label scaleSub;
    @FXML private HBox degradeBanner;
    @FXML private Label degradeText;
    @FXML private Button refillButton;
    @FXML private StackPane loadingOverlay;
    @FXML private Button overviewButton;
    @FXML private Button factsButton;
    @FXML private Button graphButton;
    @FXML private Button episodesButton;
    @FXML private Button entitiesButton;
    @FXML private Button knowledgeButton;
    @FXML private Button personaButton;
    @FXML private Button correctionsButton;
    @FXML private Button logButton;
    @FXML private Node overview;
    @FXML private Node facts;
    @FXML private Node graph;
    @FXML private Node episodes;
    @FXML private Node entities;
    @FXML private Node knowledge;
    @FXML private Node persona;
    @FXML private Node corrections;
    @FXML private Node log;
    @FXML private MemoryOverviewController overviewController;
    @FXML private MemoryFactsController factsController;
    @FXML private MemoryGraphController graphController;
    @FXML private MemoryEpisodesController episodesController;
    @FXML private MemoryEntitiesController entitiesController;
    @FXML private MemoryKnowledgeController knowledgeController;
    @FXML private MemoryPersonaController personaController;
    @FXML private MemoryCorrectionsController correctionsController;
    @FXML private MemoryLogController logController;
    @FXML private WindowToastController toastController;

    private final MemoryApplicationService useCases;
    private final MemoryViewModel viewModel = new MemoryViewModel();
    private final UiAsyncAction<Snapshot> loadAction;
    private final UiAsyncAction<OperationResult> refillAction;
    private final Map<String, Section> sections = new LinkedHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private Runnable closeAction = () -> {};
    private Window window;

    public MemoryViewController(
            MemoryApplicationService useCases,
            ManagedTaskExecutor tasks,
            FxDispatcher fx) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        loadAction = new UiAsyncAction<>(tasks, fx);
        refillAction = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        registerSections();
        scaleMain.textProperty().bind(viewModel.scaleMainProperty());
        scaleSub.textProperty().bind(viewModel.scaleSubProperty());
        degradeText.textProperty().bind(viewModel.degradeTextProperty());
        degradeBanner.visibleProperty().bind(Bindings.createBooleanBinding(
                () -> currentEmbeddingDegraded(), viewModel.snapshotProperty()));
        degradeBanner.managedProperty().bind(degradeBanner.visibleProperty());
        refillButton.visibleProperty().bind(Bindings.createBooleanBinding(
                () -> canRefill(), viewModel.snapshotProperty()));
        refillButton.managedProperty().bind(refillButton.visibleProperty());
        refillButton.disableProperty().bind(refillAction.busyProperty());
        loadingOverlay.visibleProperty().bind(loadAction.busyProperty().or(refillAction.busyProperty()));
        loadingOverlay.managedProperty().bind(loadingOverlay.visibleProperty());
        searchField.textProperty().addListener((ignored, previous, value) -> {
            viewModel.queryProperty().set(value == null ? "" : value.strip());
            refreshSelected();
        });
        select("overview");
    }

    void configure(Runnable closeAction, Window window) {
        this.closeAction = closeAction == null ? () -> {} : closeAction;
        this.window = window;
    }

    void prepare() {
        requestSnapshot();
        refillAction.execute(TaskSpec.io("memory-embedding-probe"),
                context -> useCases.probeAndRefill(), this::apply,
                failure -> showMessage("嵌入探测失败：" + failure.getMessage()));
    }

    @FXML private void showOverview() { select("overview"); }
    @FXML private void showFacts() { select("facts"); }
    @FXML private void showGraph() { select("graph"); }
    @FXML private void showEpisodes() { select("episodes"); }
    @FXML private void showEntities() { select("entities"); }
    @FXML private void showKnowledge() { select("knowledge"); }
    @FXML private void showPersona() { select("persona"); }
    @FXML private void showCorrections() { select("corrections"); }
    @FXML private void showLog() { select("log"); }
    @FXML private void requestClose() { closeAction.run(); }

    @FXML
    private void refillPending() {
        refillAction.execute(TaskSpec.io("memory-pending-refill"),
                context -> useCases.refillPending(), this::apply,
                failure -> showMessage("记忆回填失败：" + failure.getMessage()));
    }

    private void registerSections() {
        sections.put("overview", new Section(overviewButton, overview, overviewController));
        sections.put("facts", new Section(factsButton, facts, factsController));
        sections.put("graph", new Section(graphButton, graph, graphController));
        sections.put("episodes", new Section(episodesButton, episodes, episodesController));
        sections.put("entities", new Section(entitiesButton, entities, entitiesController));
        sections.put("knowledge", new Section(knowledgeButton, knowledge, knowledgeController));
        sections.put("persona", new Section(personaButton, persona, personaController));
        sections.put("corrections", new Section(correctionsButton, corrections, correctionsController));
        sections.put("log", new Section(logButton, log, logController));
        sections.values().forEach(section -> {
            if (section.controller() instanceof MemoryHostedSection hosted) hosted.configure(this);
        });
    }

    private void select(String id) {
        viewModel.sectionProperty().set(id);
        sections.forEach((key, section) -> {
            boolean selected = key.equals(id);
            section.button().getStyleClass().remove("modal-nav-btn-selected");
            if (selected) section.button().getStyleClass().add("modal-nav-btn-selected");
            section.content().setVisible(selected);
            section.content().setManaged(selected);
        });
        refreshSelected();
        Section selected = sections.get(id);
        if (selected != null) selected.controller().activated();
    }

    private void requestSnapshot() {
        if (closed.get()) return;
        loadAction.execute(TaskSpec.io("memory-snapshot"), context -> useCases.snapshot(),
                this::applySnapshot,
                failure -> showMessage("加载记忆中心失败：" + failure.getMessage()));
    }

    @Override
    public void apply(OperationResult result) {
        if (result == null) return;
        applySnapshot(result.snapshot());
        showMessage(result.message());
    }

    private void applySnapshot(Snapshot snapshot) {
        viewModel.apply(snapshot);
        refreshSelected();
    }

    private void refreshSelected() {
        Snapshot snapshot = viewModel.snapshotProperty().get();
        Section selected = sections.get(viewModel.sectionProperty().get());
        if (snapshot != null && selected != null) {
            selected.controller().apply(snapshot, viewModel.queryProperty().get());
        }
    }

    private boolean currentEmbeddingDegraded() {
        Snapshot current = viewModel.snapshotProperty().get();
        return current != null && current.embedding().degraded();
    }

    private boolean canRefill() {
        Snapshot current = viewModel.snapshotProperty().get();
        return current != null && current.embedding().canRefill();
    }

    @Override public void showMessage(String message) { toastController.show(message); }
    @Override public Window window() { return window; }

    MemorySectionController sectionController(String id) {
        Section section = sections.get(id);
        return section == null ? null : section.controller();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        loadAction.close();
        refillAction.close();
    }

    private record Section(Button button, Node content, MemorySectionController controller) {}
}
