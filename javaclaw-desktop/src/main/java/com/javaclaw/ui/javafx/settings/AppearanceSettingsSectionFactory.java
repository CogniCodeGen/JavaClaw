package com.javaclaw.ui.javafx.settings;

import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.Node;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Objects;

/** 创建界面风格与字体 FXML 设置分区。 */
public final class AppearanceSettingsSectionFactory {

    private final SpringFxmlLoader loader;

    public AppearanceSettingsSectionFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    public SettingsSectionView<AppearanceSettingsController> createAppearance() {
        return load("appearance-settings.fxml", AppearanceSettingsController.class);
    }

    public SettingsSectionView<FontSettingsController> createFonts() {
        return load("font-settings.fxml", FontSettingsController.class);
    }

    private <C> SettingsSectionView<C> load(String file, Class<C> type) {
        URL resource = Objects.requireNonNull(
                AppearanceSettingsSectionFactory.class.getResource("/fxml/settings/" + file),
                "缺少设置 FXML: " + file);
        try {
            ViewHandle<Node> handle = loader.load(resource);
            return new SettingsSectionView<>(handle, handle.controller(type));
        } catch (IOException failure) {
            throw new UncheckedIOException("加载外观设置分区失败: " + file, failure);
        }
    }
}
