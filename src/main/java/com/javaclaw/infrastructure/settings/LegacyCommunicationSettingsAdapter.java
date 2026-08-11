package com.javaclaw.infrastructure.settings;

import com.javaclaw.application.settings.CommunicationSettingsApplicationService.EmailSettings;
import com.javaclaw.application.settings.CommunicationSettingsApplicationService.Encryption;
import com.javaclaw.application.settings.CommunicationSettingsApplicationService.NotificationSettings;
import com.javaclaw.application.settings.CommunicationSettingsApplicationService.Snapshot;
import com.javaclaw.application.settings.CommunicationSettingsPort;
import com.javaclaw.config.EmailConfig;
import com.javaclaw.config.NotificationConfig;

import java.util.Objects;

/** 在旧配置存储被 JdbcTemplate 替换前，为通信设置用例提供隔离适配层。 */
public final class LegacyCommunicationSettingsAdapter implements CommunicationSettingsPort {

    private final EmailConfig email;
    private final NotificationConfig notifications;

    public LegacyCommunicationSettingsAdapter(
            EmailConfig email, NotificationConfig notifications) {
        this.email = Objects.requireNonNull(email, "email");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
    }

    @Override
    public synchronized Snapshot load() {
        return new Snapshot(email(), notifications(), email.getConfigFilePath());
    }

    @Override
    public synchronized void saveEmail(EmailSettings value) {
        email.setSmtpHost(value.smtpHost());
        email.setSmtpPort(value.smtpPort());
        email.setImapHost(value.imapHost());
        email.setImapPort(value.imapPort());
        email.setUsername(value.username());
        email.setPassword(value.password());
        email.setFromAddress(value.fromAddress());
        email.setUseSsl(value.encryption() == Encryption.SSL);
        email.setUseStarttls(value.encryption() == Encryption.STARTTLS);
        email.save();
    }

    @Override
    public synchronized void saveNotifications(NotificationSettings value) {
        notifications.setDingtalkEnabled(value.dingtalkEnabled());
        notifications.setDingtalkWebhook(value.dingtalkWebhook());
        notifications.setDingtalkSecret(value.dingtalkSecret());
        notifications.setWechatEnabled(value.wechatEnabled());
        notifications.setWechatWebhook(value.wechatWebhook());
        notifications.setFeishuEnabled(value.feishuEnabled());
        notifications.setFeishuWebhook(value.feishuWebhook());
        notifications.setFeishuSecret(value.feishuSecret());
        notifications.setEmailNotifyEnabled(value.emailEnabled());
        notifications.setEmailNotifyTo(value.emailRecipient());
        notifications.setCustomEnabled(value.customEnabled());
        notifications.setCustomWebhook(value.customWebhook());
        notifications.setCustomBodyTemplate(value.customBodyTemplate());
        notifications.save();
    }

    private EmailSettings email() {
        Encryption encryption = email.isUseSsl() ? Encryption.SSL
                : email.isUseStarttls() ? Encryption.STARTTLS : Encryption.NONE;
        return new EmailSettings(email.getSmtpHost(), email.getSmtpPort(),
                email.getImapHost(), email.getImapPort(), email.getUsername(),
                email.getPassword(), email.getFromAddress(), encryption);
    }

    private NotificationSettings notifications() {
        return new NotificationSettings(
                notifications.isDingtalkEnabled(), notifications.getDingtalkWebhook(),
                notifications.getDingtalkSecret(), notifications.isWechatEnabled(),
                notifications.getWechatWebhook(), notifications.isFeishuEnabled(),
                notifications.getFeishuWebhook(), notifications.getFeishuSecret(),
                notifications.isEmailNotifyEnabled(), notifications.getEmailNotifyTo(),
                notifications.isCustomEnabled(), notifications.getCustomWebhook(),
                notifications.getCustomBodyTemplate());
    }
}
