package com.javaclaw.ui.javafx.settings;

import com.javaclaw.platform.fxml.EmbeddedFxmlLoader;
import com.javaclaw.ui.javafx.theme.FontSelectionService.FontOption;
import javafx.fxml.FXML;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.Objects;
import java.util.function.Consumer;

/** 可复用字体预览卡片；内部 FXML 只在构造时加载一次。 */
public final class FontCard extends StackPane {

    @FXML private VBox root;
    @FXML private Label nameLabel;
    @FXML private Label subtitleLabel;
    @FXML private Label selectedLabel;
    @FXML private Label sampleLabel;

    private String fontId = "";
    private Consumer<String> onSelected = ignored -> { };

    public FontCard() {
        VBox content = EmbeddedFxmlLoader.load(
                FontCard.class.getResource("/fxml/settings/font-card.fxml"),
                this, VBox.class);
        if (content != root) {
            throw new IllegalStateException("字体卡片 FXML 根节点注入不一致");
        }
        getChildren().setAll(root);
        setAccessibleRole(AccessibleRole.BUTTON);
        setFocusTraversable(true);
        setOnMouseClicked(ignored -> onSelected.accept(fontId));
    }

    public void present(FontOption font, Consumer<String> selection) {
        Objects.requireNonNull(font, "font");
        fontId = font.id();
        onSelected = Objects.requireNonNull(selection, "selection");
        nameLabel.setText(font.name());
        subtitleLabel.setText(font.subtitle());
        sampleLabel.setStyle("-fx-font-family: " + font.stack()
                + "; -fx-font-size: 18px; -fx-text-fill: -jc-text-title;");
        setAccessibleText("界面字体 " + font.name());
    }

    public String fontId() {
        return fontId;
    }

    public void setSelected(boolean selected) {
        root.getStyleClass().remove("theme-card-selected");
        if (selected) root.getStyleClass().add("theme-card-selected");
        selectedLabel.setVisible(selected);
    }

    public void clearSelectionHandler() {
        onSelected = ignored -> { };
    }
}
