package com.javaclaw.ui.javafx.memory;

import com.javaclaw.application.memory.MemoryApplicationService.EpisodeItem;
import com.javaclaw.application.memory.MemoryApplicationService.Snapshot;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** 情景记忆分区。 */
public final class MemoryEpisodesController implements MemorySectionController, AutoCloseable {
    @FXML private VBox timeline;
    @FXML private Label empty;
    private final MemoryComponentFactory components;
    private final List<MemoryChildView<?>> children = new ArrayList<>();

    public MemoryEpisodesController(MemoryComponentFactory components) {
        this.components = Objects.requireNonNull(components, "components");
    }

    @Override
    public void apply(Snapshot snapshot, String query) {
        closeChildren();
        List<EpisodeItem> matches = snapshot.episodes().stream()
                .filter(item -> MemoryUiText.matches(query,
                        item.userInput(), item.assistantReply()))
                .sorted(Comparator.comparingLong(EpisodeItem::timestamp).reversed()).toList();
        empty.setText(query == null || query.isBlank() ? "暂无情景记录" : "没有匹配的情景");
        empty.setVisible(matches.isEmpty());
        empty.setManaged(matches.isEmpty());
        for (EpisodeItem item : matches) {
            MemoryChildView<javafx.scene.layout.HBox> child = components.episode(item);
            children.add(child);
            timeline.getChildren().add(child.root());
        }
    }

    private void closeChildren() {
        timeline.getChildren().clear();
        children.forEach(MemoryChildView::close);
        children.clear();
    }

    int renderedEpisodeCount() { return children.size(); }

    @Override public void close() { closeChildren(); }
}
