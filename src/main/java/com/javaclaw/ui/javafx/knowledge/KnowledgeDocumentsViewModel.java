package com.javaclaw.ui.javafx.knowledge;

import com.javaclaw.application.knowledge.KnowledgeApplicationService.Document;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.Scope;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.Snapshot;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.Locale;

/** Document filter, scope and selection state; contains no application dependencies. */
public final class KnowledgeDocumentsViewModel {
    private final ObservableList<Document> documents = FXCollections.observableArrayList();
    private final ObjectProperty<Scope> scope = new SimpleObjectProperty<>(Scope.ALL);
    private final ObjectProperty<Document> selected = new SimpleObjectProperty<>();
    private final StringProperty filter = new SimpleStringProperty("");
    private Snapshot snapshot;

    public KnowledgeDocumentsViewModel() {
        scope.addListener((ignored, previous, value) -> refresh());
        filter.addListener((ignored, previous, value) -> refresh());
    }

    public ObservableList<Document> documents() { return documents; }
    public ObjectProperty<Document> selectedProperty() { return selected; }
    public StringProperty filterProperty() { return filter; }
    public Scope scope() { return scope.get(); }
    public Document selected() { return selected.get(); }

    public void apply(Snapshot value) {
        snapshot = value;
        refresh();
    }

    public void setScope(Scope value) {
        scope.set(value == null ? Scope.ALL : value);
    }

    public void select(Document document) { selected.set(document); }

    public void clearSelection() { selected.set(null); }

    private void refresh() {
        String selectedName = selected.get() == null ? "" : selected.get().name();
        if (snapshot == null) {
            documents.clear();
            selected.set(null);
            return;
        }
        Scope currentScope = scope.get();
        String query = filter.get() == null ? "" : filter.get().strip().toLowerCase(Locale.ROOT);
        documents.setAll(snapshot.documents().stream()
                .filter(document -> currentScope == Scope.ALL || document.scope() == currentScope)
                .filter(document -> matches(document, query))
                .toList());
        selected.set(documents.stream().filter(document -> document.name().equals(selectedName))
                .findFirst().orElse(null));
    }

    private static boolean matches(Document document, String query) {
        if (query.isBlank()) return true;
        return document.name().toLowerCase(Locale.ROOT).contains(query)
                || document.summary().toLowerCase(Locale.ROOT).contains(query);
    }
}
