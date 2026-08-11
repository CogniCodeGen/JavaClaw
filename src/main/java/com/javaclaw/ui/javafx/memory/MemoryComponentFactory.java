package com.javaclaw.ui.javafx.memory;

import com.javaclaw.application.memory.MemoryApplicationService.ChangeItem;
import com.javaclaw.application.memory.MemoryApplicationService.CorrectionItem;
import com.javaclaw.application.memory.MemoryApplicationService.EntityItem;
import com.javaclaw.application.memory.MemoryApplicationService.EpisodeItem;
import com.javaclaw.application.memory.MemoryApplicationService.FactItem;
import com.javaclaw.application.memory.MemoryApplicationService.KnowledgeDocument;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** 加载记忆中心的动态行、卡片和分组 FXML。 */
public final class MemoryComponentFactory {

    private final SpringFxmlLoader loader;

    public MemoryComponentFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    MemoryChildView<HBox> recentChange(ChangeItem item) {
        return configured("memory-recent-change.fxml", HBox.class,
                MemoryRecentChangeController.class, controller -> controller.configure(item));
    }

    MemoryChildView<VBox> factGroup(
            String section,
            List<FactItem> facts,
            boolean batchMode,
            java.util.Set<String> selected,
            MemoryFactActions actions,
            boolean expanded,
            Consumer<Boolean> expandedChanged) {
        return configured("memory-fact-group.fxml", VBox.class,
                MemoryFactGroupController.class,
                controller -> controller.configure(
                        section, facts, batchMode, selected, actions, expanded, expandedChanged));
    }

    MemoryChildView<HBox> factRow(
            FactItem fact,
            boolean batchMode,
            boolean selected,
            MemoryFactActions actions) {
        return configured("memory-fact-row.fxml", HBox.class,
                MemoryFactRowController.class,
                controller -> controller.configure(fact, batchMode, selected, actions));
    }

    MemoryChildView<HBox> episode(EpisodeItem item) {
        return configured("memory-episode-row.fxml", HBox.class,
                MemoryEpisodeRowController.class, controller -> controller.configure(item));
    }

    MemoryChildView<VBox> entityGroup(String type, List<EntityItem> entities) {
        return configured("memory-entity-group.fxml", VBox.class,
                MemoryEntityGroupController.class,
                controller -> controller.configure(type, entities));
    }

    MemoryChildView<HBox> entity(EntityItem entity) {
        return configured("memory-entity-card.fxml", HBox.class,
                MemoryEntityCardController.class, controller -> controller.configure(entity));
    }

    MemoryChildView<HBox> knowledgeDocument(
            KnowledgeDocument document, Consumer<String> reindex, Consumer<String> delete) {
        return configured("memory-knowledge-row.fxml", HBox.class,
                MemoryKnowledgeRowController.class,
                controller -> controller.configure(document, reindex, delete));
    }

    MemoryChildView<HBox> correction(
            CorrectionItem item, Consumer<String> revoke, Consumer<String> delete) {
        return configured("memory-correction-row.fxml", HBox.class,
                MemoryCorrectionRowController.class,
                controller -> controller.configure(item, revoke, delete));
    }

    MemoryChildView<HBox> change(ChangeItem item, boolean first) {
        return configured("memory-change-row.fxml", HBox.class,
                MemoryChangeRowController.class,
                controller -> controller.configure(item, first));
    }

    MemoryChildView<HBox> personaEntry(
            String value, boolean danger, Consumer<String> remove) {
        return configured("memory-persona-entry.fxml", HBox.class,
                MemoryPersonaEntryController.class,
                controller -> controller.configure(value, danger, remove));
    }

    MemoryChildView<VBox> relatedMemories(List<String> related) {
        return configured("memory-related-list.fxml", VBox.class,
                MemoryRelatedListController.class, controller -> controller.configure(related));
    }

    MemoryChildView<HBox> relatedMemory(String value) {
        return configured("memory-related-item.fxml", HBox.class,
                MemoryRelatedItemController.class, controller -> controller.configure(value));
    }

    private <T extends javafx.scene.Node, C> MemoryChildView<T> configured(
            String name, Class<T> rootType, Class<C> controllerType, Consumer<C> configure) {
        ViewHandle<T> handle = load(name);
        try {
            if (!rootType.isInstance(handle.root())) {
                throw new IllegalStateException(name + " 根节点类型错误");
            }
            configure.accept(handle.controller(controllerType));
            return new MemoryChildView<>(handle);
        } catch (RuntimeException | Error failure) {
            try { handle.close(); } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private <T> ViewHandle<T> load(String name) {
        URL resource = Objects.requireNonNull(MemoryComponentFactory.class.getResource(
                "/fxml/memory/" + name), "缺少记忆中心 FXML: " + name);
        try {
            return loader.load(resource);
        } catch (IOException failure) {
            throw new UncheckedIOException("加载记忆中心 FXML 失败: " + name, failure);
        }
    }
}
