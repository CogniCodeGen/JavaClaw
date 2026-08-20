package com.javaclaw.ui.javafx.settings;

import com.javaclaw.ui.javafx.theme.FontSelectionService;
import com.javaclaw.ui.javafx.theme.FontSelectionService.FontOption;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** 字体设置 Controller；只协调固定 FXML 控件与可注入字体选择端口。 */
public final class FontSettingsController implements AutoCloseable {

    @FXML private ScrollPane root;
    @FXML private FontCard nativeFontCard;
    @FXML private FontCard interFontCard;
    @FXML private FontCard notoFontCard;
    @FXML private FontCard systemFontCard;
    @FXML private ToggleGroup monospaceGroup;
    @FXML private ToggleButton nativeMonoButton;
    @FXML private ToggleButton cascadiaMonoButton;
    @FXML private ToggleButton jetbrainsMonoButton;
    @FXML private ToggleButton sfmonoMonoButton;
    @FXML private ToggleGroup densityGroup;
    @FXML private ToggleButton compactDensityButton;
    @FXML private ToggleButton cozyDensityButton;
    @FXML private ToggleButton relaxedDensityButton;

    private final FontSelectionService fonts;
    private final FontSettingsViewModel viewModel = new FontSettingsViewModel();
    private final ChangeListener<Number> revisionListener =
            (ignored, previous, current) -> reload();
    private Map<FontCard, String> cards = Map.of();
    private List<ToggleButton> monospaceButtons = List.of();
    private List<ToggleButton> densityButtons = List.of();

    public FontSettingsController(FontSelectionService fonts) {
        this.fonts = Objects.requireNonNull(fonts, "fonts");
    }

    @FXML
    private void initialize() {
        cards = Map.of(nativeFontCard, "native", interFontCard, "inter",
                notoFontCard, "noto", systemFontCard, "system");
        monospaceButtons = List.of(nativeMonoButton, cascadiaMonoButton,
                jetbrainsMonoButton, sfmonoMonoButton);
        densityButtons = List.of(compactDensityButton, cozyDensityButton,
                relaxedDensityButton);
        fonts.revisionProperty().addListener(revisionListener);
    }

    public FontSettingsViewModel viewModel() {
        return viewModel;
    }

    public void reload() {
        viewModel.load(fonts.availableFonts(), fonts.availableMonospaceFonts(),
                fonts.availableDensities(), fonts.currentFontId(),
                fonts.currentMonospaceFontId(), fonts.currentDensityId());
        configureCards();
        configureButtons(monospaceButtons,
                viewModel.monospaceFonts().stream().map(value -> value.id()).collect(
                        java.util.stream.Collectors.toSet()));
        configureButtons(densityButtons,
                viewModel.densities().stream().map(value -> value.id()).collect(
                        java.util.stream.Collectors.toSet()));
        select(monospaceGroup, viewModel.currentMonospaceFontIdProperty().get());
        select(densityGroup, viewModel.currentDensityIdProperty().get());
    }

    @FXML
    private void monospaceChanged() {
        selectRequested(monospaceGroup, viewModel.currentMonospaceFontIdProperty().get(),
                fonts::selectMonospaceFont);
    }

    @FXML
    private void densityChanged() {
        selectRequested(densityGroup, viewModel.currentDensityIdProperty().get(),
                fonts::selectDensity);
    }

    private void configureCards() {
        for (var entry : cards.entrySet()) {
            FontOption option = viewModel.fonts().stream()
                    .filter(value -> value.id().equals(entry.getValue()))
                    .findFirst()
                    .orElse(null);
            show(entry.getKey(), option != null);
            if (option != null) {
                entry.getKey().present(option, fonts::selectFont);
                entry.getKey().setSelected(option.id().equals(
                        viewModel.currentFontIdProperty().get()));
            }
        }
    }

    private static void configureButtons(List<ToggleButton> buttons, Set<String> available) {
        for (ToggleButton button : buttons) {
            boolean shown = available.contains(String.valueOf(button.getUserData()));
            button.setVisible(shown);
            button.setManaged(shown);
        }
    }

    private void selectRequested(ToggleGroup group, String current, Consumer<String> selection) {
        Toggle selected = group.getSelectedToggle();
        if (selected == null) {
            select(group, current);
            return;
        }
        selection.accept(String.valueOf(selected.getUserData()));
        reload();
    }

    private static void select(ToggleGroup group, String id) {
        group.getToggles().stream()
                .filter(toggle -> id.equals(String.valueOf(toggle.getUserData())))
                .findFirst()
                .ifPresent(group::selectToggle);
    }

    private static void show(FontCard card, boolean visible) {
        card.setVisible(visible);
        card.setManaged(visible);
    }

    @Override
    public void close() {
        fonts.revisionProperty().removeListener(revisionListener);
        cards.keySet().forEach(FontCard::clearSelectionHandler);
        cards = Map.of();
        monospaceButtons = List.of();
        densityButtons = List.of();
    }
}
