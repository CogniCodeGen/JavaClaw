package com.javaclaw.ui.javafx.memory;

import com.javaclaw.application.memory.MemoryApplicationService.ChangeItem;
import com.javaclaw.application.memory.MemoryApplicationService.Snapshot;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 记忆总览，把统计快照映射到预定义 FXML 卡片。 */
public final class MemoryOverviewController implements MemorySectionController, AutoCloseable {
    @FXML private Label recalls;
    @FXML private Label hits;
    @FXML private Label hitRate;
    @FXML private Label distilled;
    @FXML private Label merged;
    @FXML private Label factCount;
    @FXML private Label episodeCount;
    @FXML private Label entityCount;
    @FXML private Label documentCount;
    @FXML private StackPane factTrack;
    @FXML private StackPane episodeTrack;
    @FXML private StackPane entityTrack;
    @FXML private StackPane documentTrack;
    @FXML private Region factFill;
    @FXML private Region episodeFill;
    @FXML private Region entityFill;
    @FXML private Region documentFill;
    @FXML private VBox recentChanges;
    @FXML private Label noChanges;

    private final MemoryComponentFactory components;
    private final List<MemoryChildView<?>> children = new ArrayList<>();

    public MemoryOverviewController(MemoryComponentFactory components) {
        this.components = Objects.requireNonNull(components, "components");
    }

    @Override
    public void apply(Snapshot snapshot, String query) {
        var statistics = snapshot.statistics();
        recalls.setText(String.valueOf(statistics.recalls()));
        hits.setText(String.valueOf(statistics.factHits()));
        hitRate.setText("命中率 " + (statistics.recalls() == 0 ? "—"
                : String.format("%.0f%%", 100.0 * statistics.factHits() / statistics.recalls())));
        distilled.setText(String.valueOf(statistics.factsDistilled()));
        merged.setText(String.valueOf(statistics.factsMerged()));
        int facts = snapshot.facts().size();
        int episodes = snapshot.episodes().size();
        int entities = snapshot.entities().size();
        int documents = snapshot.documents().size();
        factCount.setText(String.valueOf(facts));
        episodeCount.setText(String.valueOf(episodes));
        entityCount.setText(String.valueOf(entities));
        documentCount.setText(String.valueOf(documents));
        int max = Math.max(1, Math.max(Math.max(facts, episodes), Math.max(entities, documents)));
        bindWidth(factFill, factTrack, facts, max);
        bindWidth(episodeFill, episodeTrack, episodes, max);
        bindWidth(entityFill, entityTrack, entities, max);
        bindWidth(documentFill, documentTrack, documents, max);
        renderChanges(snapshot.changes().stream().limit(6).toList());
    }

    private void renderChanges(List<ChangeItem> changes) {
        closeChildren();
        noChanges.setVisible(changes.isEmpty());
        noChanges.setManaged(changes.isEmpty());
        for (ChangeItem change : changes) {
            MemoryChildView<javafx.scene.layout.HBox> child = components.recentChange(change);
            children.add(child);
            recentChanges.getChildren().add(child.root());
        }
    }

    private static void bindWidth(Region fill, StackPane track, int value, int max) {
        fill.maxWidthProperty().unbind();
        double fraction = Math.max(0.04, (double) value / max);
        fill.maxWidthProperty().bind(track.widthProperty().multiply(fraction));
    }

    private void closeChildren() {
        recentChanges.getChildren().clear();
        children.forEach(MemoryChildView::close);
        children.clear();
    }

    @Override public void close() { closeChildren(); }
}
