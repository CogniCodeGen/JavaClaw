package com.javaclaw.desktop.settings;

import java.util.Optional;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.desktop.component.FormSection;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;

/** Provider 页面内显式更新旧 Agent Profile 引用的可复用面板。 */
final class ProviderProfileReferencePanel {
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final ProviderProfileReferencePresenter presenter;
    private final FormSection content = new FormSection("旧版本引用", "保存新版本不会改变已有智能体方案；这里只按用户选择创建新的精确引用版本。");
    private final ListView<AgentProfile> profiles = new ListView<>();
    private final Label status = new Label();
    private final Button update;
    private boolean rendering;

    ProviderProfileReferencePanel(CoreSettingsGateway gateway) {
        presenter = new ProviderProfileReferencePresenter(gateway);
        update = components.action("更新所选智能体引用", ActionStyle.SOFT, ActionSize.COMPACT);
        update.setOnAction(event -> presenter.updateSelected());
        profiles.setPrefHeight(120);
        profiles.setCellFactory(ignored -> components.detailCell(
                profile -> profile.spec().displayName(),
                profile -> profile.id() + " · 智能体版本 " + profile.revision() + " · Provider 版本 "
                        + profile.spec().provider().endpointRevision()));
        profiles.getSelectionModel().selectedItemProperty().addListener((ignored, previous, selected) -> {
            if (!rendering) {
                presenter.select(selected);
            }
        });
        status.setWrapText(true);
        status.getStyleClass().add("sec-hint");
        content.addFullWidth(profiles);
        content.addFullWidth(new HBox(8, update, status));
        presenter.subscribe(this::render);
    }

    Node content() {
        return content;
    }

    void bind(Optional<ProviderEndpoint> provider) {
        presenter.bind(provider);
    }

    private void render(ProviderProfileReferenceState state) {
        rendering = true;
        try {
            profiles.getItems().setAll(state.staleProfiles());
            profiles.getSelectionModel().select(state.selected().orElse(null));
        } finally {
            rendering = false;
        }
        profiles.setDisable(state.pending());
        update.setDisable(state.pending() || state.selected().isEmpty());
        status.setText(state.message());
        status.getStyleClass().remove("platform-action-error");
        if (state.phase() == SettingsLoadState.ERROR) {
            status.getStyleClass().add("platform-action-error");
        }
    }
}
