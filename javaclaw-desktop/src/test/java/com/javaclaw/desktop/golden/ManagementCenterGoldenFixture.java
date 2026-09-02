package com.javaclaw.desktop.golden;

import com.javaclaw.desktop.appearance.AppearancePreferences;
import com.javaclaw.desktop.appearance.FontScale;
import com.javaclaw.desktop.settings.ProductionManagementCenterScene;

final class ManagementCenterGoldenFixture {
    ProductionManagementCenterScene.RenderedCenter render(UiGoldenCase golden) {
        AppearancePreferences preferences =
                new AppearancePreferences(golden.theme(), FontScale.STANDARD, golden.density());
        return ProductionManagementCenterScene.render(
                preferences, golden.viewport().width(), golden.viewport().height());
    }
}
