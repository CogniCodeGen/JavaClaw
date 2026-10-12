package com.javaclaw.ui.javafx.onboarding;

import com.javaclaw.application.onboarding.OnboardingApplicationService.Provider;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.Objects;
import java.util.function.Consumer;

/** 单个 Provider 卡片的 FXML 事件协调器。 */
public final class ProviderCardController {

    private static final String SELECTED_CLASS = "onboarding-provider-card-selected";

    @FXML private VBox root;
    @FXML private Label nameLabel;
    @FXML private Label descriptionLabel;
    @FXML private HBox tagsBox;
    @FXML private Label recommendedTag;
    @FXML private Label localTag;

    private Provider provider;
    private Consumer<Provider> onSelected = ignored -> {};

    void configure(Provider value, boolean selected, Consumer<Provider> selection) {
        provider = Objects.requireNonNull(value, "value");
        onSelected = Objects.requireNonNull(selection, "selection");
        nameLabel.setText(value.displayName());
        descriptionLabel.setText(value.description());
        showTag(recommendedTag, value.recommended());
        showTag(localTag, value.local());
        tagsBox.setManaged(value.recommended() || value.local());
        tagsBox.setVisible(tagsBox.isManaged());
        root.setAccessibleText("模型提供商 " + value.displayName());
        setSelected(selected);
    }

    void setSelected(boolean selected) {
        root.getStyleClass().remove(SELECTED_CLASS);
        if (selected) root.getStyleClass().add(SELECTED_CLASS);
    }

    String providerId() {
        return provider == null ? "" : provider.id();
    }

    @FXML
    private void selectRequested(MouseEvent event) {
        if (provider != null) onSelected.accept(provider);
    }

    private static void showTag(Label label, boolean visible) {
        label.setVisible(visible);
        label.setManaged(visible);
    }
}
