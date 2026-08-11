package com.javaclaw.ui.javafx.plugin;

import com.javaclaw.application.plugin.PluginManagementApplicationService.Plugin;
import com.javaclaw.ui.javafx.control.ToggleSwitch;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** 单个插件卡片的 FXML Controller；实例只在卡片生命周期内存活。 */
public final class PluginCardController implements AutoCloseable {

    @FXML private HBox headerRow;
    @FXML private Label glyphLabel;
    @FXML private Label nameLabel;
    @FXML private Label stateBadge;
    @FXML private Label metadataLabel;
    @FXML private Label descriptionLabel;
    @FXML private Label failureBadge;
    @FXML private ToggleSwitch enabledToggle;

    private final AtomicBoolean closed = new AtomicBoolean();
    private Plugin plugin;
    private Consumer<String> detailsAction;
    private BiConsumer<String, Boolean> toggleAction;
    private boolean configuring;

    @FXML
    private void initialize() {
        enabledToggle.selectedProperty().addListener((ignored, previous, selected) -> {
            if (!configuring && toggleAction != null && plugin != null) {
                toggleAction.accept(plugin.id(), selected);
            }
        });
    }

    void configure(
            Plugin value,
            Consumer<String> onDetails,
            BiConsumer<String, Boolean> onToggle) {
        plugin = Objects.requireNonNull(value, "value");
        detailsAction = Objects.requireNonNull(onDetails, "onDetails");
        toggleAction = Objects.requireNonNull(onToggle, "onToggle");
        glyphLabel.setText(PluginUiText.glyph(value));
        nameLabel.setText(value.name());
        metadataLabel.setText(PluginUiText.metadata(value));
        descriptionLabel.setText(value.description().isBlank() ? "（无描述）" : value.description());
        configureBadge(stateBadge, value);
        failureBadge.setVisible(value.state()
                == com.javaclaw.application.plugin.PluginManagementApplicationService.State.FAILED);
        failureBadge.setManaged(failureBadge.isVisible());
        configuring = true;
        enabledToggle.setSelected(value.active());
        configuring = false;
        enabledToggle.setDisable(false);
    }

    @FXML
    private void detailsRequested() {
        if (detailsAction != null && plugin != null) detailsAction.accept(plugin.id());
    }

    private static void configureBadge(Label badge, Plugin value) {
        badge.setText(PluginUiText.state(value.state()));
        badge.getStyleClass().removeAll(
                "jc-badge-running", "jc-badge-failed", "jc-badge-stopped");
        badge.getStyleClass().add(PluginUiText.stateStyle(value.state()));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        detailsAction = null;
        toggleAction = null;
        plugin = null;
    }
}
