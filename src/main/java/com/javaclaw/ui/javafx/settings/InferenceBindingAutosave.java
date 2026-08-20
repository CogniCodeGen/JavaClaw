package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.inference.InferenceCatalogPort;
import com.javaclaw.application.inference.InferenceManagementApplicationService;
import com.javaclaw.application.workspace.WorkspaceApplicationService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import javafx.animation.PauseTransition;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.util.Duration;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** Debounces workspace model choices and keeps their asynchronous save state out of the controller. */
final class InferenceBindingAutosave implements AutoCloseable {
    private static final String AUTOSAVE_HINT = "更改后自动保存";

    private final InferenceManagementApplicationService useCases;
    private final WorkspaceApplicationService workspaces;
    private final ComboBox<InferenceSettingsChoice<UUID>> high;
    private final ComboBox<InferenceSettingsChoice<UUID>> normal;
    private final ComboBox<InferenceSettingsChoice<UUID>> light;
    private final ComboBox<InferenceSettingsChoice<UUID>> embedding;
    private final Label status;
    private final Button retry;
    private final Consumer<String> pageStatus;
    private final UiAsyncAction<SaveRequest> save;
    private final PauseTransition delay = new PauseTransition(Duration.millis(450));
    private Runnable runtimeChanged = () -> { };
    private boolean applying;
    private boolean closed;
    private long revision;
    private Map<InferenceCatalogPort.ModelTier, UUID> persisted = Map.of();
    private Map<InferenceCatalogPort.ModelTier, UUID> pending;
    private Map<InferenceCatalogPort.ModelTier, UUID> submitted;

    InferenceBindingAutosave(
            InferenceManagementApplicationService useCases,
            WorkspaceApplicationService workspaces,
            ManagedTaskExecutor tasks,
            FxDispatcher fx,
            ComboBox<InferenceSettingsChoice<UUID>> high,
            ComboBox<InferenceSettingsChoice<UUID>> normal,
            ComboBox<InferenceSettingsChoice<UUID>> light,
            ComboBox<InferenceSettingsChoice<UUID>> embedding,
            Label status,
            Button retry,
            Consumer<String> pageStatus) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.high = Objects.requireNonNull(high, "high");
        this.normal = Objects.requireNonNull(normal, "normal");
        this.light = Objects.requireNonNull(light, "light");
        this.embedding = Objects.requireNonNull(embedding, "embedding");
        this.status = Objects.requireNonNull(status, "status");
        this.retry = Objects.requireNonNull(retry, "retry");
        this.pageStatus = Objects.requireNonNull(pageStatus, "pageStatus");
        save = new UiAsyncAction<>(Objects.requireNonNull(tasks, "tasks"),
                Objects.requireNonNull(fx, "fx"));
        high.valueProperty().addListener((ignored, previous, selected) -> changed());
        normal.valueProperty().addListener((ignored, previous, selected) -> changed());
        light.valueProperty().addListener((ignored, previous, selected) -> changed());
        embedding.valueProperty().addListener((ignored, previous, selected) -> changed());
        delay.setOnFinished(ignored -> persistPending());
    }

    void configure(Runnable callback) {
        runtimeChanged = Objects.requireNonNull(callback, "callback");
    }

    void apply(
            Map<InferenceCatalogPort.ModelTier, UUID> bindings,
            List<InferenceSettingsChoice<UUID>> generation,
            List<InferenceSettingsChoice<UUID>> embeddings) {
        applying = true;
        try {
            items(high, generation, bindings.get(InferenceCatalogPort.ModelTier.HIGH));
            items(normal, generation, bindings.get(InferenceCatalogPort.ModelTier.NORMAL));
            items(light, generation, bindings.get(InferenceCatalogPort.ModelTier.LIGHT));
            items(embedding, embeddings, bindings.get(InferenceCatalogPort.ModelTier.EMBEDDING));
            if (pending == null) {
                persisted = current();
                state(AUTOSAVE_HINT, false);
            } else {
                select(high, pending.get(InferenceCatalogPort.ModelTier.HIGH));
                select(normal, pending.get(InferenceCatalogPort.ModelTier.NORMAL));
                select(light, pending.get(InferenceCatalogPort.ModelTier.LIGHT));
                select(embedding, pending.get(InferenceCatalogPort.ModelTier.EMBEDDING));
            }
        } finally {
            applying = false;
        }
    }

    void retry() {
        persistPending();
    }

    private void changed() {
        if (closed || applying) return;
        Map<InferenceCatalogPort.ModelTier, UUID> desired = current();
        revision++;
        if (desired.equals(persisted) && submitted == null) {
            pending = null;
            delay.stop();
            state("已同步", false);
            return;
        }
        pending = desired;
        state("即将自动保存…", false);
        delay.playFromStart();
    }

    private void persistPending() {
        if (closed || pending == null) return;
        SaveRequest request = new SaveRequest(revision, Map.copyOf(pending));
        submitted = request.bindings();
        state("正在自动保存…", false);
        save.execute(TaskSpec.io("自动保存工作区模型选择"), context -> {
            useCases.saveBindings(workspaces.currentWorkspaceId(), request.bindings());
            return request;
        }, saved -> saved(saved), failure -> failed(request, failure));
    }

    private void saved(SaveRequest request) {
        if (request.revision() != revision) return;
        persisted = request.bindings();
        pending = null;
        submitted = null;
        state("已自动保存", false);
        pageStatus.accept("工作区模型选择已自动保存");
        runtimeChanged.run();
    }

    private void failed(SaveRequest request, Throwable failure) {
        if (request.revision() != revision) return;
        submitted = null;
        String detail = SettingsFieldSupport.failureMessage(failure);
        state("自动保存失败：" + detail, true);
        pageStatus.accept("工作区模型选择保存失败：" + detail);
    }

    private Map<InferenceCatalogPort.ModelTier, UUID> current() {
        EnumMap<InferenceCatalogPort.ModelTier, UUID> result =
                new EnumMap<>(InferenceCatalogPort.ModelTier.class);
        put(result, InferenceCatalogPort.ModelTier.HIGH, high.getValue());
        put(result, InferenceCatalogPort.ModelTier.NORMAL, normal.getValue());
        put(result, InferenceCatalogPort.ModelTier.LIGHT, light.getValue());
        put(result, InferenceCatalogPort.ModelTier.EMBEDDING, embedding.getValue());
        return Map.copyOf(result);
    }

    private void state(String text, boolean retryVisible) {
        status.setText(text);
        retry.setVisible(retryVisible);
        retry.setManaged(retryVisible);
    }

    private static void put(
            Map<InferenceCatalogPort.ModelTier, UUID> result,
            InferenceCatalogPort.ModelTier tier,
            InferenceSettingsChoice<UUID> choice) {
        if (choice != null) result.put(tier, choice.value());
    }

    private static void items(
            ComboBox<InferenceSettingsChoice<UUID>> box,
            List<InferenceSettingsChoice<UUID>> values,
            UUID selected) {
        box.getItems().setAll(values);
        select(box, selected);
    }

    private static void select(ComboBox<InferenceSettingsChoice<UUID>> box, UUID value) {
        if (value == null) {
            box.setValue(null);
            return;
        }
        box.setValue(box.getItems().stream().filter(item -> item.value().equals(value))
                .findFirst().orElse(null));
    }

    void cancel() {
        delay.stop();
        save.cancel();
        submitted = null;
        if (pending != null) state("自动保存已取消", true);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        runtimeChanged = () -> { };
        delay.stop();
        delay.setOnFinished(null);
        save.close();
    }

    private record SaveRequest(
            long revision, Map<InferenceCatalogPort.ModelTier, UUID> bindings) { }
}
