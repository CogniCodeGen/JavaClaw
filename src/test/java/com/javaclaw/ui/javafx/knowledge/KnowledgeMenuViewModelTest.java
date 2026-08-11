package com.javaclaw.ui.javafx.knowledge;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeMenuViewModelTest {

    @Test
    void selectedCountControlsButtonTextAndActiveState() {
        KnowledgeMenuViewModel model = new KnowledgeMenuViewModel();

        model.updateSelectedCount(3);
        assertEquals("知识库(3)", model.buttonTextProperty().get());
        assertTrue(model.activeProperty().get());

        model.updateSelectedCount(0);
        assertEquals("知识库", model.buttonTextProperty().get());
        assertFalse(model.activeProperty().get());
    }

    @Test
    void entryModelMapsTextSelectionAndDisabledState() {
        KnowledgeMenuEntryViewModel model = new KnowledgeMenuEntryViewModel();

        model.configure("设计文档  4 片段", true, false);

        assertEquals("设计文档  4 片段", model.textProperty().get());
        assertTrue(model.selectedProperty().get());
        assertFalse(model.disabledProperty().get());
    }

    @Test
    void snapshotDefensivelyCopiesCollections() {
        List<KnowledgeMenuSnapshot.Document> global = new ArrayList<>(List.of(
                new KnowledgeMenuSnapshot.Document("guide.md", 4)));
        Set<String> selected = new HashSet<>(Set.of("guide.md"));

        KnowledgeMenuSnapshot snapshot = new KnowledgeMenuSnapshot(
                true, global, List.of(), selected);
        global.clear();
        selected.clear();

        assertEquals(1, snapshot.documentCount());
        assertEquals(Set.of("guide.md"), snapshot.selectedDocumentNames());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.globalDocuments().clear());
    }
}
