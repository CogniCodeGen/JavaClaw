package com.javaclaw.ui.javafx.settings;

import com.javaclaw.ui.javafx.theme.FontSelectionService.DensityOption;
import com.javaclaw.ui.javafx.theme.FontSelectionService.FontOption;
import com.javaclaw.ui.javafx.theme.FontSelectionService.MonoOption;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.List;

/** 字体设置页的纯 JavaFX 状态。 */
public final class FontSettingsViewModel {

    private final ObservableList<FontOption> fonts = FXCollections.observableArrayList();
    private final ObservableList<MonoOption> monospaceFonts = FXCollections.observableArrayList();
    private final ObservableList<DensityOption> densities = FXCollections.observableArrayList();
    private final StringProperty currentFontId = new SimpleStringProperty("");
    private final StringProperty currentMonospaceFontId = new SimpleStringProperty("");
    private final StringProperty currentDensityId = new SimpleStringProperty("");

    public void load(
            List<FontOption> fontValues,
            List<MonoOption> monospaceValues,
            List<DensityOption> densityValues,
            String fontId,
            String monospaceId,
            String densityId) {
        fonts.setAll(fontValues);
        monospaceFonts.setAll(monospaceValues);
        densities.setAll(densityValues);
        currentFontId.set(value(fontId));
        currentMonospaceFontId.set(value(monospaceId));
        currentDensityId.set(value(densityId));
    }

    public ObservableList<FontOption> fonts() { return fonts; }
    public ObservableList<MonoOption> monospaceFonts() { return monospaceFonts; }
    public ObservableList<DensityOption> densities() { return densities; }
    public StringProperty currentFontIdProperty() { return currentFontId; }
    public StringProperty currentMonospaceFontIdProperty() { return currentMonospaceFontId; }
    public StringProperty currentDensityIdProperty() { return currentDensityId; }

    private static String value(String value) { return value == null ? "" : value; }
}
