package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.inference.InferenceManagementApplicationService;
import com.javaclaw.application.inference.InferenceRuntimePort;
import com.javaclaw.inference.api.InferenceModelProfile;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Pure list filtering and selection helpers for the profile controller. */
final class InferenceProfilePresentation {
    private InferenceProfilePresentation() { }

    static List<InferenceSettingsChoice<UUID>> assets(
            InferenceManagementApplicationService.Snapshot snapshot,
            InferenceModelProfile.Kind kind) {
        return snapshot.assets().stream().filter(asset -> InferenceModelPurposeClassifier.purposes(
                        asset, snapshot.runtimes(), snapshot.profiles()).contains(kind))
                .map(asset -> new InferenceSettingsChoice<>(asset.displayName() + " · "
                        + InferenceModelPresentation.bytes(asset.sizeBytes()), asset.id())).toList();
    }

    static List<InferenceSettingsChoice<UUID>> profiles(
            InferenceManagementApplicationService.Snapshot snapshot,
            InferenceModelProfile.Kind kind,
            Map<UUID, InferenceRuntimePort.RuntimeProfileStatus> statuses) {
        return snapshot.profiles().stream().filter(profile -> profile.kind() == kind)
                .map(profile -> new InferenceSettingsChoice<>(profile.name() + " · "
                        + (statuses.containsKey(profile.id()) ? "已加载"
                        : InferenceModelPurposeClassifier.stateLabel(profile.state())),
                        profile.id())).toList();
    }

    static List<InferenceSettingsChoice<UUID>> ready(
            InferenceManagementApplicationService.Snapshot snapshot,
            InferenceModelProfile.Kind kind) {
        return snapshot.profiles().stream().filter(profile -> profile.kind() == kind
                        && profile.state() == InferenceModelProfile.State.READY)
                .map(profile -> new InferenceSettingsChoice<>(profile.name(), profile.id())).toList();
    }

    static List<InferenceSettingsChoice<String>> compatibleRuntimes(
            InferenceManagementApplicationService.Snapshot snapshot, UUID assetId,
            InferenceModelProfile.Kind kind) {
        String modelType = assetId == null ? null : snapshot.assets().stream()
                .filter(asset -> asset.id().equals(assetId)).map(asset -> asset.modelType())
                .findFirst().orElse(null);
        return snapshot.runtimes().stream().filter(runtime -> modelType == null
                        ? !runtime.manifest().supportedModelTypes(kind).isEmpty()
                        : runtime.manifest().supportedModelTypes(kind).contains(modelType))
                .map(runtime -> new InferenceSettingsChoice<>(runtime.manifest().engineVersion()
                        + " / adapter " + runtime.manifest().adapterVersion()
                        + (runtime.active() ? " · 当前" : ""),
                        runtime.manifest().runtimeId())).toList();
    }

    static <T> void select(ComboBox<InferenceSettingsChoice<T>> box, T value) {
        if (value == null) { box.setValue(null); return; }
        box.setValue(box.getItems().stream().filter(item -> item.value().equals(value))
                .findFirst().orElse(null));
    }

    static <T> T selectedValue(ListView<InferenceSettingsChoice<T>> list) {
        var selected = list.getSelectionModel().getSelectedItem();
        return selected == null ? null : selected.value();
    }

    static <T> void selectList(ListView<InferenceSettingsChoice<T>> list, T value) {
        list.getItems().stream().filter(item -> item.value().equals(value)).findFirst()
                .ifPresent(item -> list.getSelectionModel().select(item));
    }

    static void show(Node node, boolean visible) {
        if (node != null) { node.setVisible(visible); node.setManaged(visible); }
    }

    static void renderCapacity(
            InferenceManagementApplicationService.RecommendedProfile value,
            Label capacityLabel, Label warningLabel) {
        capacityLabel.setText(InferenceModelPresentation.capacity(value.capacity()));
        var memory = value.memory();
        warningLabel.getStyleClass().removeAll(
                "service-plugin-memory-danger", "service-plugin-memory-warning");
        warningLabel.setText(memory.message());
        if (memory.exceedsPhysicalMemory()) {
            warningLabel.getStyleClass().add("service-plugin-memory-danger");
        } else if (memory.exceedsSafeBudget()) {
            warningLabel.getStyleClass().add("service-plugin-memory-warning");
        }
        show(warningLabel, !memory.message().isBlank());
    }
}
