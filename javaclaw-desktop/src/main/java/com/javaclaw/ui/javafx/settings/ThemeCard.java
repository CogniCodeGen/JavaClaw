package com.javaclaw.ui.javafx.settings;

import com.javaclaw.platform.fxml.EmbeddedFxmlLoader;
import com.javaclaw.ui.javafx.theme.ThemeOption;
import javafx.fxml.FXML;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.Objects;
import java.util.function.Consumer;

/** 可复用主题卡片；内部 FXML 只在构造时加载一次。 */
public final class ThemeCard extends StackPane {

    @FXML private VBox root;
    @FXML private Region brandStrip;
    @FXML private Region backgroundStrip;
    @FXML private Region surfaceStrip;
    @FXML private Label nameLabel;
    @FXML private Label subtitleLabel;
    @FXML private Label selectedLabel;

    private String themeId = "";
    private Consumer<String> onSelected = ignored -> { };

    public ThemeCard() {
        VBox content = EmbeddedFxmlLoader.load(
                ThemeCard.class.getResource("/fxml/settings/theme-card.fxml"),
                this, VBox.class);
        if (content != root) {
            throw new IllegalStateException("主题卡片 FXML 根节点注入不一致");
        }
        getChildren().setAll(root);
        setAccessibleRole(AccessibleRole.BUTTON);
        setFocusTraversable(true);
        setOnMouseClicked(ignored -> onSelected.accept(themeId));
    }

    public void present(ThemeOption theme, Consumer<String> selection) {
        Objects.requireNonNull(theme, "theme");
        themeId = theme.id();
        onSelected = Objects.requireNonNull(selection, "selection");
        nameLabel.setText(theme.name());
        subtitleLabel.setText(theme.subtitle());
        brandStrip.setStyle(color(theme.brand()));
        backgroundStrip.setStyle(color(theme.background()));
        surfaceStrip.setStyle(color(theme.surface()));
        setAccessibleText("界面风格 " + theme.name());
    }

    public String themeId() {
        return themeId;
    }

    public void setSelected(boolean selected) {
        root.getStyleClass().remove("theme-card-selected");
        if (selected) root.getStyleClass().add("theme-card-selected");
        selectedLabel.setVisible(selected);
    }

    public void clearSelectionHandler() {
        onSelected = ignored -> { };
    }

    private static String color(String value) {
        return "-fx-background-color: " + value + ";";
    }
}
