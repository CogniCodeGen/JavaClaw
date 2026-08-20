package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.inference.InferenceManagementApplicationService;
import com.javaclaw.application.inference.InferenceRuntimePort;
import com.javaclaw.inference.api.InferenceModelProfile;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Owns the compact configured/loaded model list and its actions on the service console. */
final class InferenceServiceModelConsole implements AutoCloseable {
    private final InferenceManagementApplicationService useCases;
    private final InferenceSettingsUiActions ui;
    private final Runnable reload;
    private final Consumer<UUID> edit;
    private final ListView<InferenceSettingsChoice<InferenceModelPresentation.ServiceModel>> list;
    private final TextArea details;
    private final TextField identifier;
    private final TextField path;
    private final TextArea curl;
    private final Button runtime;
    private final Button settings;
    private final Button delete;
    private InferenceManagementApplicationService.Snapshot snapshot;
    private Map<UUID, InferenceRuntimePort.RuntimeProfileStatus> statuses = Map.of();
    private String serviceUrl = "";
    private UUID preferredProfileId;

    InferenceServiceModelConsole(
            InferenceManagementApplicationService useCases, InferenceSettingsUiActions ui,
            Runnable reload, Consumer<UUID> edit,
            ListView<InferenceSettingsChoice<InferenceModelPresentation.ServiceModel>> list,
            TextArea details, TextField identifier, TextField path, TextArea curl,
            Button runtime, Button settings, Button delete) {
        this.useCases = useCases;
        this.ui = ui;
        this.reload = reload;
        this.edit = edit;
        this.list = list;
        this.details = details;
        this.identifier = identifier;
        this.path = path;
        this.curl = curl;
        this.runtime = runtime;
        this.settings = settings;
        this.delete = delete;
        list.setCellFactory(InferenceModelListCells.service());
        list.getSelectionModel().selectedItemProperty().addListener(
                (ignored, previous, selected) -> render(selected));
    }

    void apply(InferenceManagementApplicationService.Snapshot value,
               Map<UUID, InferenceRuntimePort.RuntimeProfileStatus> runtimeStatuses,
               String endpoint) {
        snapshot = value;
        statuses = runtimeStatuses == null ? Map.of() : Map.copyOf(runtimeStatuses);
        serviceUrl = endpoint == null ? "" : endpoint;
        UUID currentProfileId = selected() == null ? null : selected().profileId();
        UUID requestedProfileId = preferredProfileId == null ? currentProfileId : preferredProfileId;
        List<InferenceSettingsChoice<InferenceModelPresentation.ServiceModel>> models =
                InferenceModelPresentation.serviceModels(snapshot, statuses);
        list.getItems().setAll(models);
        var requested = models.stream().filter(item ->
                item.value().profileId().equals(requestedProfileId)).findFirst();
        requested.ifPresentOrElse(list.getSelectionModel()::select, () -> {
                    if (models.isEmpty()) render(null);
                    else list.getSelectionModel().selectFirst();
                });
        if (requested.isPresent() && requestedProfileId != null
                && requestedProfileId.equals(preferredProfileId)) preferredProfileId = null;
    }

    void prefer(UUID profileId) { if (profileId != null) preferredProfileId = profileId; }

    void toggleRuntime() {
        var selected = selected();
        if (selected == null) return;
        boolean loaded = selected.loaded();
        ui.run(loaded ? "卸载模型" : "加载模型", context -> {
            useCases.setProfileRunning(selected.profileId(), !loaded);
            return null;
        }, ignored -> {
            reload.run();
            ui.status(loaded ? "模型已卸载，本地资产和公开别名仍保留" : "模型已加载");
        });
    }

    void edit() {
        var selected = selected();
        if (selected != null) edit.accept(selected.profileId());
    }

    void deleteConfiguration() {
        var selected = selected();
        if (selected == null) return;
        ui.confirm("删除模型配置", "仍被工作区或 API 别名使用的配置不能删除，确定继续？",
                context -> {
                    useCases.deleteProfile(selected.profileId());
                    return null;
                }, ignored -> {
                    reload.run();
                    ui.status("模型配置已删除；本地模型资产仍保留");
                });
    }

    private InferenceModelPresentation.ServiceModel selected() {
        var choice = list.getSelectionModel().getSelectedItem();
        return choice == null ? null : choice.value();
    }

    private void render(InferenceSettingsChoice<InferenceModelPresentation.ServiceModel> choice) {
        if (snapshot == null || choice == null) {
            details.clear();
            identifier.clear();
            path.clear();
            curl.clear();
            runtime.setDisable(true);
            settings.setDisable(true);
            delete.setDisable(true);
            return;
        }
        var model = choice.value();
        var value = InferenceModelPresentation.serviceDetails(snapshot, statuses, model, serviceUrl);
        details.setText(value.text());
        identifier.setText(value.identifier());
        path.setText(value.path());
        curl.setText(value.curl());
        InferenceModelProfile profile = snapshot.profiles().stream()
                .filter(candidate -> candidate.id().equals(model.profileId())).findFirst().orElse(null);
        runtime.setText(model.loaded() ? "卸载" : "加载");
        runtime.setDisable(profile == null || !model.loaded()
                && profile.state() != InferenceModelProfile.State.READY);
        settings.setDisable(profile == null);
        delete.setDisable(profile == null || model.loaded());
    }

    @Override public void close() { list.setCellFactory(null); }
}
