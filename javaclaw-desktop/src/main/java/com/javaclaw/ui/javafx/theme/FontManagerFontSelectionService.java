package com.javaclaw.ui.javafx.theme;

import javafx.beans.property.ReadOnlyIntegerProperty;

import java.util.List;

/** 将现有全局字体引擎隔离在可注入的 Presentation 端口之后。 */
public final class FontManagerFontSelectionService implements FontSelectionService {

    private final FontManager manager;

    public FontManagerFontSelectionService(FontManager manager) {
        this.manager = java.util.Objects.requireNonNull(manager, "manager");
    }

    @Override
    public List<FontOption> availableFonts() {
        return manager.availableFontOptions().stream()
                .map(value -> new FontOption(
                        value.id(), value.name(), value.subtitle(), value.stack()))
                .toList();
    }

    @Override
    public List<MonoOption> availableMonospaceFonts() {
        return manager.availableMonoOptions().stream()
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

    @Override public String currentFontId() { return manager.getFontFamily(); }
    @Override public String currentMonospaceFontId() { return manager.getMonoFamily(); }
    @Override public String currentDensityId() { return manager.getDensity(); }
    @Override public ReadOnlyIntegerProperty revisionProperty() {
        return manager.revisionProperty();
    }
    @Override public void selectFont(String id) { manager.setFontFamily(id); }
    @Override public void selectMonospaceFont(String id) { manager.setMonoFamily(id); }
    @Override public void selectDensity(String id) { manager.setDensity(id); }
    @Override public void reloadFromWorkspace() { manager.reload(); }
}
