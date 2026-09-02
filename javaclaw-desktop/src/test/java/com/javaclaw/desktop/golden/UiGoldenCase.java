package com.javaclaw.desktop.golden;

import java.util.Arrays;
import java.util.List;

import com.javaclaw.desktop.appearance.AppearanceTheme;
import com.javaclaw.desktop.appearance.InterfaceDensity;

record UiGoldenCase(AppearanceTheme theme, InterfaceDensity density, GoldenViewport viewport) {
    private static final String FILE_PREFIX = "settings-center";

    static List<UiGoldenCase> matrix() {
        return Arrays.stream(AppearanceTheme.values())
                .flatMap(theme -> Arrays.stream(InterfaceDensity.values())
                        .flatMap(density -> Arrays.stream(GoldenViewport.values())
                                .map(viewport -> new UiGoldenCase(theme, density, viewport))))
                .toList();
    }

    String fileName() {
        return "%s--theme-%s--density-%s--viewport-%s.png"
                .formatted(FILE_PREFIX, theme.id(), density.id(), viewport.id());
    }
}
