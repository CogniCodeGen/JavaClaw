package com.javaclaw.ui.javafx.memory;

import com.javaclaw.application.memory.MemoryApplicationService.KnowledgeDocument;
import javafx.fxml.FXML;
import javafx.scene.control.Label;

import java.util.Objects;
import java.util.function.Consumer;

/** 知识文档表格行。 */
public final class MemoryKnowledgeRowController {
    @FXML private Label name;
    @FXML private Label chunks;
    @FXML private Label size;
    @FXML private Label importedAt;
    private String documentName = "";
    private Consumer<String> reindex = ignored -> {};
    private Consumer<String> delete = ignored -> {};

    void configure(
            KnowledgeDocument document,
            Consumer<String> reindex,
            Consumer<String> delete) {
        documentName = document.name();
        this.reindex = Objects.requireNonNull(reindex, "reindex");
        this.delete = Objects.requireNonNull(delete, "delete");
        name.setText("📄 " + document.name());
        chunks.setText(document.chunkCount() + " 块");
        size.setText(MemoryUiText.humanSize(document.characterCount()));
        importedAt.setText(document.importedAt());
    }

    @FXML private void reindexRequested() { reindex.accept(documentName); }
    @FXML private void deleteRequested() { delete.accept(documentName); }
}
