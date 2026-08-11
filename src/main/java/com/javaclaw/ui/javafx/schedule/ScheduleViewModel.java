package com.javaclaw.ui.javafx.schedule;

import com.javaclaw.application.schedule.ScheduleApplicationService.Snapshot;
import com.javaclaw.application.schedule.ScheduleApplicationService.Task;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/** 定时任务窗口的列表、选择、草稿和状态。 */
public final class ScheduleViewModel {

    private final ObservableList<Task> tasks = FXCollections.observableArrayList();
    private final ObjectProperty<Task> selected = new SimpleObjectProperty<>();
    private final ObjectProperty<Task> draft = new SimpleObjectProperty<>();
    private final ReadOnlyStringWrapper subtitle = new ReadOnlyStringWrapper("0 个启用 · 共 0 个");
    private final ReadOnlyStringWrapper status = new ReadOnlyStringWrapper("");

    public ObservableList<Task> tasks() { return tasks; }
    public ObjectProperty<Task> selectedProperty() { return selected; }
    public ObjectProperty<Task> draftProperty() { return draft; }
    public ReadOnlyStringProperty subtitleProperty() { return subtitle.getReadOnlyProperty(); }
    public ReadOnlyStringProperty statusProperty() { return status.getReadOnlyProperty(); }

    public void apply(Snapshot snapshot) {
        String selectedId = selected.get() == null ? "" : selected.get().id();
        tasks.setAll(snapshot.tasks());
        long enabled = tasks.stream().filter(Task::enabled).count();
        subtitle.set(enabled + " 个启用 · 共 " + tasks.size() + " 个");
        if (draft.get() == null && !selectedId.isBlank()) {
            selected.set(tasks.stream().filter(task -> task.id().equals(selectedId))
                    .findFirst().orElse(null));
        }
    }

    public void select(Task task) { selected.set(task); }

    public void beginDraft(Task task) {
        draft.set(task);
        selected.set(task);
    }

    public boolean hasDraft() { return draft.get() != null; }

    public boolean selectedIsDraft() {
        return draft.get() != null && selected.get() != null
                && draft.get().id().equals(selected.get().id());
    }

    public void finishDraft() { draft.set(null); }

    public void clearSelection() { selected.set(null); }

    public void showStatus(String message) { status.set(message == null ? "" : message); }
}
