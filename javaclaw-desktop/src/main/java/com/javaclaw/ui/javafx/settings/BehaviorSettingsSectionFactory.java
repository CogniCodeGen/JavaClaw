package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.settings.BehaviorSettingsApplicationService.SaveResult;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.Node;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Objects;
import java.util.function.Consumer;

/** 创建 GEPA、技能进化和通用行为 FXML 设置分区。 */
public final class BehaviorSettingsSectionFactory {

    private final SpringFxmlLoader loader;

    public BehaviorSettingsSectionFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    public SettingsSectionView<GepaSettingsController> createGepa(
            Consumer<SaveResult> onApplied) {
        var view = load("gepa-settings.fxml", GepaSettingsController.class);
        view.controller().configure(callback(onApplied));
        return view;
    }

    public SettingsSectionView<SkillEvolutionSettingsController> createSkillEvolution(
            Consumer<SaveResult> onApplied) {
        var view = load("skill-evolution-settings.fxml",
                SkillEvolutionSettingsController.class);
        view.controller().configure(callback(onApplied));
        return view;
    }

    public SettingsSectionView<GeneralSettingsController> createGeneral(
            Consumer<SaveResult> onApplied) {
        var view = load("general-settings.fxml", GeneralSettingsController.class);
        view.controller().configure(callback(onApplied));
        return view;
    }

    private <C> SettingsSectionView<C> load(String file, Class<C> type) {
        URL resource = Objects.requireNonNull(
                BehaviorSettingsSectionFactory.class.getResource("/fxml/settings/" + file),
                "缺少设置 FXML: " + file);
        try {
            ViewHandle<Node> handle = loader.load(resource);
            return new SettingsSectionView<>(handle, handle.controller(type));
        } catch (IOException failure) {
            throw new UncheckedIOException("加载设置分区失败: " + file, failure);
        }
    }

    private static Consumer<SaveResult> callback(Consumer<SaveResult> value) {
        return value == null ? ignored -> { } : value;
    }
}
