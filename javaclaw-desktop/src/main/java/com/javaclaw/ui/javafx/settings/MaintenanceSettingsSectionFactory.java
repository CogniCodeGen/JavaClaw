package com.javaclaw.ui.javafx.settings;

import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.Node;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Objects;

/** 创建系统维护 FXML 设置分区。 */
public final class MaintenanceSettingsSectionFactory {

    private final SpringFxmlLoader loader;

    public MaintenanceSettingsSectionFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    public SettingsSectionView<TestDataMaintenanceController> createTestDataMaintenance() {
        URL resource = Objects.requireNonNull(
                MaintenanceSettingsSectionFactory.class.getResource(
                        "/fxml/settings/test-data-maintenance.fxml"),
                "缺少设置 FXML: test-data-maintenance.fxml");
        try {
            ViewHandle<Node> handle = loader.load(resource);
            return new SettingsSectionView<>(handle,
                    handle.controller(TestDataMaintenanceController.class));
        } catch (IOException failure) {
            throw new UncheckedIOException("加载测试数据维护分区失败", failure);
        }
    }
}
