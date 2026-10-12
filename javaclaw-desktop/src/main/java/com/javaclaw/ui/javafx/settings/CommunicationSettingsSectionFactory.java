package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.settings.CommunicationSettingsApplicationService.SaveResult;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.Node;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Objects;
import java.util.function.Consumer;

/** 创建邮件与通知 FXML 设置分区。 */
public final class CommunicationSettingsSectionFactory {

    private final SpringFxmlLoader loader;

    public CommunicationSettingsSectionFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    public SettingsSectionView<EmailSettingsController> createEmail(
            Consumer<SaveResult> onApplied) {
        var view = load("email-settings.fxml", EmailSettingsController.class);
        view.controller().configure(callback(onApplied));
        return view;
    }

    public SettingsSectionView<NotificationSettingsController> createNotifications(
            Consumer<SaveResult> onApplied) {
        var view = load("notification-settings.fxml", NotificationSettingsController.class);
        view.controller().configure(callback(onApplied));
        return view;
    }

    private <C> SettingsSectionView<C> load(String file, Class<C> type) {
        URL resource = Objects.requireNonNull(
                CommunicationSettingsSectionFactory.class.getResource(
                        "/fxml/settings/" + file),
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
