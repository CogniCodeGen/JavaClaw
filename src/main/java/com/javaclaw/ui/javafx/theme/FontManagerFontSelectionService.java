package com.javaclaw.ui.javafx.theme;

import javafx.beans.property.ReadOnlyIntegerProperty;

import java.util.List;

/** 将现有全局字体引擎隔离在可注入的 Presentation 端口之后。 */
public final class FontManagerFontSelectionService implements FontSelectionService {

    @Override
    public List<FontOption> availableFonts() {
        return FontManager.availableFontOptions().stream()
                .map(value -> new FontOption(
                        value.id(), value.name(), value.subtitle(), value.stack()))
                .toList();
    }

    @Override
    public List<MonoOption> availableMonospaceFonts() {
        return FontManager.availableMonoOptions().stream()
                .map(value -> new MonoOption(value.id(), value.name(), value.stack()))
                .toList();
    }

    @Override
    public List<DensityOption> availableDensities() {
        return FontManager.DENSITIES.stream()
                .map(value -> new DensityOption(
                        value.id(), value.name(), value.fontPx(), value.lineHeight()))
                .toList();
    }

    @Override public String currentFontId() { return FontManager.getFontFamily(); }
    @Override public String currentMonospaceFontId() { return FontManager.getMonoFamily(); }
    @Override public String currentDensityId() { return FontManager.getDensity(); }
    @Override public ReadOnlyIntegerProperty revisionProperty() {
        return FontManager.revisionProperty();
    }
    @Override public void selectFont(String id) { FontManager.setFontFamily(id); }
    @Override public void selectMonospaceFont(String id) { FontManager.setMonoFamily(id); }
    @Override public void selectDensity(String id) { FontManager.setDensity(id); }
    @Override public void reloadFromWorkspace() { FontManager.reload(); }
}
