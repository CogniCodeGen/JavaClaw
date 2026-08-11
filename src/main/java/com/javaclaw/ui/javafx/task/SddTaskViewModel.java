package com.javaclaw.ui.javafx.task;

import com.javaclaw.application.task.SddTaskApplicationService.Snapshot;
import com.javaclaw.application.task.SddTaskApplicationService.Task;
import com.javaclaw.task.sdd.run.SddTaskState;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/** SDD 页面的列表、选择和反馈状态；不持有 Service 或 Repository。 */
public final class SddTaskViewModel {

    private final ObservableList<Task> tasks = FXCollections.observableArrayList();
    private final ObjectProperty<Task> selected = new SimpleObjectProperty<>();
    private final ReadOnlyStringWrapper subtitle = new ReadOnlyStringWrapper("共 0 个");
    private final ReadOnlyStringWrapper status = new ReadOnlyStringWrapper("");

    public ObservableList<Task> tasks() { return tasks; }
    public ObjectProperty<Task> selectedProperty() { return selected; }
    public ReadOnlyStringProperty subtitleProperty() { return subtitle.getReadOnlyProperty(); }
    public ReadOnlyStringProperty statusProperty() { return status.getReadOnlyProperty(); }

    public void apply(Snapshot snapshot) {
        String selectedId = selected.get() == null ? "" : selected.get().id();
        tasks.setAll(snapshot.tasks());
        long running = tasks.stream().filter(task -> task.state() == SddTaskState.RUNNING).count();
        long human = tasks.stream().filter(task -> task.state() == SddTaskState.NEEDS_HUMAN).count();
        subtitle.set(running + " 运行中 · " + human + " 待人工 · 共 " + tasks.size());
        if (!selectedId.isBlank()) {
            selected.set(tasks.stream().filter(task -> task.id().equals(selectedId))
                    .findFirst().orElse(null));
        }
    }

    public void select(Task task) { selected.set(task); }
    public void clearSelection() { selected.set(null); }
    public void showStatus(String message) { status.set(message == null ? "" : message); }
}
