package com.javaclaw.ui.javafx.settings;

import com.javaclaw.ui.javafx.theme.ThemeOption;
import com.javaclaw.ui.javafx.theme.ThemeSelectionService;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.ScrollPane;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 界面风格 Controller；主题卡片只协调即时选择与外部主题变化。 */
public final class AppearanceSettingsController implements AutoCloseable {

    @FXML private ScrollPane root;
    @FXML private ThemeCard emeraldCard;
    @FXML private ThemeCard midnightCard;
    @FXML private ThemeCard carbonCard;
    @FXML private ThemeCard sapphireCard;
    @FXML private ThemeCard oceanCard;
    @FXML private ThemeCard plumCard;
    @FXML private ThemeCard terracottaCard;
    @FXML private ThemeCard honeyCard;
    @FXML private ThemeCard graphiteCard;

    private final ThemeSelectionService themes;
    private final AppearanceSettingsViewModel viewModel = new AppearanceSettingsViewModel();
    private final ChangeListener<String> themeListener =
            (ignored, previous, current) -> refreshSelection(current);
    private Map<ThemeCard, String> cards = Map.of();

    public AppearanceSettingsController(ThemeSelectionService themes) {
        this.themes = Objects.requireNonNull(themes, "themes");
    }

    @FXML
    private void initialize() {
        cards = Map.of(
                emeraldCard, "emerald",
                midnightCard, "midnight",
                carbonCard, "carbon",
                sapphireCard, "sapphire",
                oceanCard, "ocean",
                plumCard, "plum",
                terracottaCard, "terracotta",
                honeyCard, "honey",
                graphiteCard, "graphite");
        themes.currentThemeProperty().addListener(themeListener);
        reload();
    }

    public AppearanceSettingsViewModel viewModel() {
        return viewModel;
    }

    public void reload() {
        viewModel.load(themes.availableThemes(), themes.currentThemeId());
        for (var entry : cards.entrySet()) {
            ThemeOption option = viewModel.themes().stream()
                    .filter(value -> value.id().equals(entry.getValue()))
                    .findFirst()
                    .orElse(null);
            show(entry.getKey(), option != null);
            if (option != null) entry.getKey().present(option, themes::select);
        }
        refreshSelection(viewModel.currentThemeIdProperty().get());
    }

    private void refreshSelection(String current) {
        viewModel.currentThemeIdProperty().set(current == null ? "" : current);
        cards.forEach((card, id) -> card.setSelected(id.equals(current)));
    }

    private static void show(ThemeCard card, boolean visible) {
        card.setVisible(visible);
        card.setManaged(visible);
    }

    @Override
    public void close() {
        themes.currentThemeProperty().removeListener(themeListener);
        cards.keySet().forEach(ThemeCard::clearSelectionHandler);
        cards = Map.of();
    }
}
