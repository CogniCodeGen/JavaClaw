package com.javaclaw.ui.javafx.knowledge;

import com.javaclaw.application.knowledge.KnowledgeApplicationService.Document;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Creates document cells that load their FXML once in the constructor. */
public final class KnowledgeDocumentCellFactory {
    KnowledgeDocumentCell create(
            Consumer<Document> selection,
            BiConsumer<Document, Boolean> toggle) {
        return new KnowledgeDocumentCell(selection, toggle);
    }
}
