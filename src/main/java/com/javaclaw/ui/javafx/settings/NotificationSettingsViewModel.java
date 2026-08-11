package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.settings.CommunicationSettingsApplicationService.NotificationSettings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** 通知渠道设置页的纯 JavaFX 状态。 */
public final class NotificationSettingsViewModel {

    private final BooleanProperty dingtalkEnabled = new SimpleBooleanProperty();
    private final StringProperty dingtalkWebhook = new SimpleStringProperty("");
    private final StringProperty dingtalkSecret = new SimpleStringProperty("");
    private final BooleanProperty wechatEnabled = new SimpleBooleanProperty();
    private final StringProperty wechatWebhook = new SimpleStringProperty("");
    private final BooleanProperty feishuEnabled = new SimpleBooleanProperty();
    private final StringProperty feishuWebhook = new SimpleStringProperty("");
    private final StringProperty feishuSecret = new SimpleStringProperty("");
    private final BooleanProperty emailEnabled = new SimpleBooleanProperty();
    private final StringProperty emailRecipient = new SimpleStringProperty("");
    private final BooleanProperty customEnabled = new SimpleBooleanProperty();
    private final StringProperty customWebhook = new SimpleStringProperty("");
    private final StringProperty customBody = new SimpleStringProperty("");
    private final StringProperty storageDescription = new SimpleStringProperty("");
    private final StringProperty error = new SimpleStringProperty("");
    private final BooleanProperty busy = new SimpleBooleanProperty(false);

    public void load(NotificationSettings value, String storage) {
        dingtalkEnabled.set(value.dingtalkEnabled());
        dingtalkWebhook.set(value.dingtalkWebhook());
        dingtalkSecret.set(value.dingtalkSecret());
        wechatEnabled.set(value.wechatEnabled());
        wechatWebhook.set(value.wechatWebhook());
        feishuEnabled.set(value.feishuEnabled());
        feishuWebhook.set(value.feishuWebhook());
        feishuSecret.set(value.feishuSecret());
        emailEnabled.set(value.emailEnabled());
        emailRecipient.set(value.emailRecipient());
        customEnabled.set(value.customEnabled());
        customWebhook.set(value.customWebhook());
        customBody.set(value.customBodyTemplate());
        storageDescription.set("配置文件: " + (storage == null ? "" : storage));
        error.set("");
    }

    public NotificationSettings value() {
        return new NotificationSettings(dingtalkEnabled.get(), dingtalkWebhook.get(),
                dingtalkSecret.get(), wechatEnabled.get(), wechatWebhook.get(),
                feishuEnabled.get(), feishuWebhook.get(), feishuSecret.get(),
                emailEnabled.get(), emailRecipient.get(), customEnabled.get(),
                customWebhook.get(), customBody.get());
    }

    public BooleanProperty dingtalkEnabledProperty() { return dingtalkEnabled; }
    public StringProperty dingtalkWebhookProperty() { return dingtalkWebhook; }
    public StringProperty dingtalkSecretProperty() { return dingtalkSecret; }
    public BooleanProperty wechatEnabledProperty() { return wechatEnabled; }
    public StringProperty wechatWebhookProperty() { return wechatWebhook; }
    public BooleanProperty feishuEnabledProperty() { return feishuEnabled; }
    public StringProperty feishuWebhookProperty() { return feishuWebhook; }
    public StringProperty feishuSecretProperty() { return feishuSecret; }
    public BooleanProperty emailEnabledProperty() { return emailEnabled; }
    public StringProperty emailRecipientProperty() { return emailRecipient; }
    public BooleanProperty customEnabledProperty() { return customEnabled; }
    public StringProperty customWebhookProperty() { return customWebhook; }
    public StringProperty customBodyProperty() { return customBody; }
    public StringProperty storageDescriptionProperty() { return storageDescription; }
    public StringProperty errorProperty() { return error; }
    public BooleanProperty busyProperty() { return busy; }
}
