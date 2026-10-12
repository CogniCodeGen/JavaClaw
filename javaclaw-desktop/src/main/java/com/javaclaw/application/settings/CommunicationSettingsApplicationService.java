package com.javaclaw.application.settings;

import java.util.Objects;

/**
 * 邮件与通知渠道设置的应用入口。
 *
 * <p>方法同步返回不可变快照；保存会先完整校验再持久化。连接探测是可中断的阻塞 I/O，
 * 调用方必须使用托管 I/O 执行器。实现不持有 JavaFX 页面状态。</p>
 */
public interface CommunicationSettingsApplicationService {

    Snapshot snapshot();

    SaveResult saveEmail(EmailSettings settings);

    EmailProbeResult saveAndProbeEmail(EmailSettings settings) throws Exception;

    SaveResult saveNotifications(NotificationSettings settings);

    enum Encryption {
        SSL,
        STARTTLS,
        NONE
    }

    record EmailSettings(
            String smtpHost,
            int smtpPort,
            String imapHost,
            int imapPort,
            String username,
            String password,
            String fromAddress,
            Encryption encryption) {
        public EmailSettings {
            smtpHost = normalize(smtpHost);
            imapHost = normalize(imapHost);
            username = normalize(username);
            password = password == null ? "" : password;
            fromAddress = normalize(fromAddress);
            encryption = Objects.requireNonNull(encryption, "encryption");
        }
    }

    record NotificationSettings(
            boolean dingtalkEnabled,
            String dingtalkWebhook,
            String dingtalkSecret,
            boolean wechatEnabled,
            String wechatWebhook,
            boolean feishuEnabled,
            String feishuWebhook,
            String feishuSecret,
            boolean emailEnabled,
            String emailRecipient,
            boolean customEnabled,
            String customWebhook,
            String customBodyTemplate) {
        public NotificationSettings {
            dingtalkWebhook = normalize(dingtalkWebhook);
            dingtalkSecret = dingtalkSecret == null ? "" : dingtalkSecret;
            wechatWebhook = normalize(wechatWebhook);
            feishuWebhook = normalize(feishuWebhook);
            feishuSecret = feishuSecret == null ? "" : feishuSecret;
            emailRecipient = normalize(emailRecipient);
            customWebhook = normalize(customWebhook);
            customBodyTemplate = customBodyTemplate == null ? "" : customBodyTemplate.strip();
        }
    }

    record Snapshot(
            EmailSettings email,
            NotificationSettings notifications,
            String storageDescription) {
        public Snapshot {
            email = Objects.requireNonNull(email, "email");
            notifications = Objects.requireNonNull(notifications, "notifications");
            storageDescription = normalize(storageDescription);
        }
    }

    record SaveResult(Snapshot snapshot, String message) {
        public SaveResult {
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
            message = normalize(message);
        }
    }

    record EmailProbeResult(SaveResult saved, boolean succeeded, String message) {
        public EmailProbeResult {
            saved = Objects.requireNonNull(saved, "saved");
            message = normalize(message);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
