package com.javaclaw.desktop;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextInputControl;

import com.javaclaw.sdk.RpcException;

/** 单个管理资源的本地草稿快照、保存排队与 revision 冲突状态。 */
final class ManagementEditSession {
    private static final String SESSION_KEY = "javaclaw.management.edit-session";
    private final String resource;
    private final ManagementViewModel model;
    private final Button saveButton;
    private final Runnable reload;
    private final BooleanProperty dirty = new SimpleBooleanProperty();
    private final BooleanProperty canSave = new SimpleBooleanProperty();
    private final List<TrackedValue> values = new ArrayList<>();
    private final List<Runnable> detach = new ArrayList<>();
    private final List<Consumer<Boolean>> saveWaiters = new ArrayList<>();
    private boolean saving;
    private boolean restoring;
    private final ObjectProperty<Throwable> conflict = new SimpleObjectProperty<>();

    ManagementEditSession(ManagementViewModel model, String resource, Button saveButton, Runnable reload) {
        this.model = Objects.requireNonNull(model, "model");
        this.resource = resource == null || resource.isBlank() ? "当前内容" : resource;
        this.saveButton = Objects.requireNonNull(saveButton, "saveButton");
        this.reload = reload == null ? () -> {} : reload;
        ManagementForms.guard(saveButton, dirty.not());
    }

    String resource() {
        return resource;
    }

    ManagementViewModel model() {
        return model;
    }

    ReadOnlyBooleanProperty dirtyProperty() {
        return dirty;
    }

    ReadOnlyBooleanProperty canSaveProperty() {
        return canSave;
    }

    Throwable conflict() {
        return conflict.get();
    }

    ReadOnlyObjectProperty<Throwable> conflictProperty() {
        return conflict;
    }

    void watchTree(Node node) {
        if (node == null || ManagementForms.ignoresDirty(node)) {
            return;
        }
        if (node instanceof TextInputControl input) {
            watch(input.textProperty());
        } else if (node instanceof CheckBox input) {
            watch(input.selectedProperty());
        } else if (node instanceof ComboBox<?> input) {
            watch(input.valueProperty());
        } else if (node instanceof Spinner<?> input) {
            watch(input.getValueFactory().valueProperty());
        } else if (node instanceof ListView<?> input) {
            watchList(input.getItems());
        }
        if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(this::watchTree);
        }
        updateDirty();
    }

    void watch(ObservableValue<?> value) {
        var tracked = new TrackedValue(value, value.getValue());
        values.add(tracked);
        ChangeListener<Object> listener = (ignored, old, current) -> updateDirty();
        @SuppressWarnings("unchecked")
        ObservableValue<Object> observable = (ObservableValue<Object>) value;
        observable.addListener(listener);
        detach.add(() -> observable.removeListener(listener));
        updateDirty();
    }

    private void watchList(ObservableList<?> list) {
        var tracked = new TrackedValue(new ListSnapshotObservable(list), List.copyOf(list));
        values.add(tracked);
        ListChangeListener<Object> listener = ignored -> updateDirty();
        @SuppressWarnings("unchecked")
        ObservableList<Object> observable = (ObservableList<Object>) list;
        observable.addListener(listener);
        detach.add(() -> observable.removeListener(listener));
        updateDirty();
    }

    void requestSave(Consumer<Boolean> completion) {
        Objects.requireNonNull(completion, "completion");
        if (!dirty.get()) {
            completion.accept(true);
            return;
        }
        saveWaiters.add(completion);
        if (saving) {
            return;
        }
        saving = true;
        canSave.set(false);
        saveButton.fire();
        Platform.runLater(() -> {
            if (saving && !ManagementForms.commandRunning(saveButton)) {
                saveFailed(null);
            }
        });
    }

    void saveSucceeded() {
        conflict.set(null);
        values.forEach(TrackedValue::acceptCurrent);
        updateDirty();
        finishSave(true);
    }

    void saveFailed(Throwable failure) {
        if (failure instanceof RpcException rpc && rpc.isConflict()) {
            conflict.set(failure);
        }
        finishSave(false);
    }

    void discard() {
        restoring = true;
        try {
            for (TrackedValue value : values) {
                value.restore();
            }
            conflict.set(null);
        } finally {
            restoring = false;
            updateDirty();
        }
    }

    void reloadServerVersion() {
        discard();
        reload.run();
    }

    void dispose() {
        detach.forEach(Runnable::run);
        detach.clear();
        values.clear();
        finishSave(false);
    }

    void install(Node fields) {
        fields.getProperties().put(SESSION_KEY, this);
    }

    static ManagementEditSession find(Node fields) {
        Object value = fields.getProperties().get(SESSION_KEY);
        return value instanceof ManagementEditSession session ? session : null;
    }

    private void finishSave(boolean success) {
        saving = false;
        updateDirty();
        if (saveWaiters.isEmpty()) {
            return;
        }
        List<Consumer<Boolean>> waiters = List.copyOf(saveWaiters);
        saveWaiters.clear();
        Platform.runLater(() -> waiters.forEach(waiter -> waiter.accept(success)));
    }

    private void updateDirty() {
        if (restoring) {
            return;
        }
        boolean changed = values.stream().anyMatch(TrackedValue::changed);
        dirty.set(changed);
        canSave.set(changed && !saving);
    }

    private static final class TrackedValue {
        private final ObservableValue<?> observable;
        private Object baseline;

        private TrackedValue(ObservableValue<?> observable, Object baseline) {
            this.observable = observable;
            this.baseline = copy(baseline);
        }

        private boolean changed() {
            return !Objects.equals(baseline, copy(observable.getValue()));
        }

        private void acceptCurrent() {
            baseline = copy(observable.getValue());
        }

        private void restore() {
            ObjectPropertySetter.restore(observable, baseline);
        }

        private static Object copy(Object value) {
            return value instanceof List<?> list ? List.copyOf(list) : value;
        }
    }

    private static final class ObjectPropertySetter {
        private ObjectPropertySetter() {}

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static void restore(ObservableValue observable, Object value) {
            if (observable instanceof javafx.beans.property.Property property) {
                property.setValue(value);
            } else if (observable instanceof ListSnapshotObservable list) {
                list.restore((List<?>) value);
            }
        }
    }

    private static final class ListSnapshotObservable implements ObservableValue<List<?>> {
        private final ObservableList<?> source;

        private ListSnapshotObservable(ObservableList<?> source) {
            this.source = source;
        }

        private void restore(List<?> value) {
            @SuppressWarnings("unchecked")
            ObservableList<Object> target = (ObservableList<Object>) source;
            target.setAll(value);
        }

        @Override
        public void addListener(ChangeListener<? super List<?>> listener) {}

        @Override
        public void removeListener(ChangeListener<? super List<?>> listener) {}

        @Override
        public List<?> getValue() {
            return List.copyOf(source);
        }

        @Override
        public void addListener(javafx.beans.InvalidationListener listener) {}

        @Override
        public void removeListener(javafx.beans.InvalidationListener listener) {}
    }
}
