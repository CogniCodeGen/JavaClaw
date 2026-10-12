package com.javaclaw.ui.javafx.theme;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.Region;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** FXML Controller：把主题描述映射到一个菜单项，并转发选择事件。 */
public final class ThemeMenuEntryController implements AutoCloseable {

    @FXML private MenuItem root;
    @FXML private Region brandSwatch;
    @FXML private Region backgroundSwatch;
    @FXML private Region surfaceSwatch;
    @FXML private Label nameLabel;
    @FXML private Label subtitleLabel;
    @FXML private Label selectedMark;

    private final ThemeMenuEntryViewModel viewModel = new ThemeMenuEntryViewModel();
    private final AtomicBoolean closed = new AtomicBoolean();
    private Runnable selection = () -> { };

    @FXML
    private void initialize() {
        nameLabel.textProperty().bind(viewModel.nameProperty());
        subtitleLabel.textProperty().bind(viewModel.subtitleProperty());
        selectedMark.textProperty().bind(viewModel.selectedMarkProperty());
        viewModel.brandProperty().addListener(
                (ignored, previous, current) -> applyColor(brandSwatch, current));
        viewModel.backgroundProperty().addListener(
                (ignored, previous, current) -> applyColor(backgroundSwatch, current));
        viewModel.surfaceProperty().addListener(
                (ignored, previous, current) -> applyColor(surfaceSwatch, current));
    }

    void configure(ThemeOption option, boolean selected, Runnable selection) {
        this.selection = Objects.requireNonNull(selection, "selection");
        viewModel.configure(Objects.requireNonNull(option, "option"), selected);
    }

    @FXML
    private void selected() {
        if (!closed.get()) selection.run();
    }

    private static void applyColor(Region swatch, String color) {
        swatch.setStyle("-fx-background-color: " + color + ";");
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) selection = () -> { };
    }

    boolean isClosed() {
        return closed.get();
    }
}
