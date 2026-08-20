package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.settings.ModelSettingsApplicationService.SaveResult;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.Node;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Objects;
import java.util.function.Consumer;

/** 创建模型、分级模型和嵌入模型三个 FXML 设置分区。 */
public final class ModelSettingsSectionFactory {

    private final SpringFxmlLoader loader;

    public ModelSettingsSectionFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    public SettingsSectionView<ModelSettingsController> createModel(
            Consumer<SaveResult> onApplied) {
        return createModel(onApplied, () -> { });
    }

    public SettingsSectionView<ModelSettingsController> createModel(
            Consumer<SaveResult> onApplied, Runnable runtimeConfigurationChanged) {
        return load("model-settings.fxml", ModelSettingsController.class,
                onApplied, runtimeConfigurationChanged);
    }

    public SettingsSectionView<TieredModelSettingsController> createTiers(
            Consumer<SaveResult> onApplied) {
        return createTiers(onApplied, () -> { });
    }

    public SettingsSectionView<TieredModelSettingsController> createTiers(
            Consumer<SaveResult> onApplied, Runnable runtimeConfigurationChanged) {
        return load("tiered-model-settings.fxml", TieredModelSettingsController.class,
                onApplied, runtimeConfigurationChanged);
    }

    public SettingsSectionView<EmbeddingSettingsController> createEmbedding(
            Consumer<SaveResult> onApplied) {
        return createEmbedding(onApplied, () -> { });
    }

    public SettingsSectionView<EmbeddingSettingsController> createEmbedding(
            Consumer<SaveResult> onApplied, Runnable runtimeConfigurationChanged) {
        return load("embedding-settings.fxml", EmbeddingSettingsController.class,
                onApplied, runtimeConfigurationChanged);
    }

    private <C extends AppliedSettingsController> SettingsSectionView<C> load(
            String file, Class<C> type, Consumer<SaveResult> onApplied,
            Runnable runtimeConfigurationChanged) {
        URL resource = Objects.requireNonNull(
                ModelSettingsSectionFactory.class.getResource("/fxml/settings/" + file),
                "缺少设置 FXML: " + file);
        try {
            ViewHandle<Node> handle = loader.load(resource);
            C controller = handle.controller(type);
            controller.configure(onApplied == null ? ignored -> { } : onApplied,
                    runtimeConfigurationChanged == null ? () -> { } : runtimeConfigurationChanged);
            return new SettingsSectionView<>(handle, controller);
        } catch (IOException failure) {
            throw new UncheckedIOException("加载设置分区失败: " + file, failure);
        }
    }

    interface AppliedSettingsController {
        void configure(Consumer<SaveResult> onApplied, Runnable runtimeConfigurationChanged);
    }
}
