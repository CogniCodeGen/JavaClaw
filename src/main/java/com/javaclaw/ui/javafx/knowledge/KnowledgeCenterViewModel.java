package com.javaclaw.ui.javafx.knowledge;

import com.javaclaw.application.knowledge.KnowledgeApplicationService.Snapshot;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleObjectProperty;

/** Window-level navigation, snapshot and feedback state; contains no services. */
public final class KnowledgeCenterViewModel {

    public enum Page { DOCUMENTS, SEARCH, SETTINGS }

    private final ObjectProperty<Snapshot> snapshot = new SimpleObjectProperty<>();
    private final ObjectProperty<Page> page = new SimpleObjectProperty<>(Page.DOCUMENTS);
    private final ReadOnlyStringWrapper feedback = new ReadOnlyStringWrapper("");

    public ObjectProperty<Snapshot> snapshotProperty() { return snapshot; }
    public ObjectProperty<Page> pageProperty() { return page; }
    public ReadOnlyStringProperty feedbackProperty() { return feedback.getReadOnlyProperty(); }
    public Snapshot snapshot() { return snapshot.get(); }
    public Page page() { return page.get(); }
    public void apply(Snapshot value) { snapshot.set(value); }
    public void show(Page value) { page.set(value == null ? Page.DOCUMENTS : value); }
    public void feedback(String value) { feedback.set(value == null ? "" : value); }
}
