package com.javaclaw.ui.javafx.theme;

import javafx.beans.property.ReadOnlyStringProperty;

import java.util.List;

/** 将现有全局主题引擎隔离在可注入的 Presentation 端口之后。 */
public final class ThemeManagerThemeSelectionService implements ThemeSelectionService {

    private final ThemeManager manager;
    private final List<ThemeOption> themes = ThemeManager.THEMES.stream()
            .map(theme -> new ThemeOption(
                    theme.id(), theme.name(), theme.subtitle(),
                    theme.brand(), theme.bg(), theme.surface()))
            .toList();

    public ThemeManagerThemeSelectionService(ThemeManager manager) {
        this.manager = java.util.Objects.requireNonNull(manager, "manager");
    }

    @Override
    public List<ThemeOption> availableThemes() {
        return themes;
    }

    @Override
    public ThemeOption currentTheme() {
        String current = currentThemeId();
        return themes.stream()
                .filter(theme -> theme.id().equals(current))
                .findFirst()
                .orElse(themes.getFirst());
    }

    @Override
    public String currentThemeId() {
        return manager.getTheme();
    }

    @Override
    public ReadOnlyStringProperty currentThemeProperty() {
        return manager.themeProperty();
    }

    @Override
    public void select(String themeId) {
        manager.setTheme(themeId);
    }

    @Override
    public void reloadFromWorkspace() {
        manager.reload();
    }
}
