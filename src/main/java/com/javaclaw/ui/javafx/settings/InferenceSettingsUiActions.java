package com.javaclaw.ui.javafx.settings;

import com.javaclaw.api.interaction.ConfirmKind;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.ManagedTask;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.scene.control.Label;

import java.util.Objects;
import java.util.function.Consumer;

/** 本地推理三个子页共用的托管异步、取消、确认与状态协调器。 */
final class InferenceSettingsUiActions implements AutoCloseable {
    private final DialogService dialogs;
    private final UiAsyncAction<Object> action;
    private Label status;

    InferenceSettingsUiActions(
            DialogService dialogs, ManagedTaskExecutor tasks, FxDispatcher fx) {
        this.dialogs = Objects.requireNonNull(dialogs, "dialogs");
        action = new UiAsyncAction<>(Objects.requireNonNull(tasks, "tasks"),
                Objects.requireNonNull(fx, "fx"));
    }

    void attach(Label target) { status = Objects.requireNonNull(target, "target"); }
    ReadOnlyBooleanProperty busyProperty() { return action.busyProperty(); }

    void run(String name, ManagedTask<Object> task, Consumer<Object> success) {
        status(name + "…");
        action.execute(TaskSpec.io(name), task, success,
                failure -> status("失败：" + SettingsFieldSupport.failureMessage(failure)));
    }

    void confirm(String title, String message,
                 ManagedTask<Object> task, Consumer<Object> success) {
        run(title, context -> dialogs.confirm(new ConfirmRequest(title, title, message,
                ConfirmKind.CONFIRM, 60, "", false)).isAllow()
                ? task.run(context) : Cancelled.INSTANCE,
                result -> { if (result != Cancelled.INSTANCE) success.accept(result); });
    }

    void cancel() { action.cancel(); status("操作已取消"); }
    void status(String text) { if (status != null) status.setText(text); }
    @Override public void close() { action.close(); }

    private enum Cancelled { INSTANCE }
}
