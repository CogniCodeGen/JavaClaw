package com.javaclaw.application.settings;

import com.javaclaw.application.error.ValidationException;
import com.javaclaw.application.settings.CommunicationSettingsApplicationService.EmailProbeResult;
import com.javaclaw.application.settings.CommunicationSettingsApplicationService.EmailSettings;
import com.javaclaw.application.settings.CommunicationSettingsApplicationService.NotificationSettings;
import com.javaclaw.application.settings.CommunicationSettingsApplicationService.SaveResult;
import com.javaclaw.application.settings.CommunicationSettingsApplicationService.Snapshot;

import java.net.URI;
import java.util.Objects;

/** 通信设置用例：完整校验后持久化，并编排 SMTP/IMAP 探测。 */
public final class CommunicationSettingsUseCase
        implements CommunicationSettingsApplicationService {

    private final CommunicationSettingsPort settings;
    private final EmailConnectionProbePort probes;

    public CommunicationSettingsUseCase(
            CommunicationSettingsPort settings, EmailConnectionProbePort probes) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.probes = Objects.requireNonNull(probes, "probes");
    }

    @Override
    public Snapshot snapshot() {
        return settings.load();
    }

    @Override
    public SaveResult saveEmail(EmailSettings value) {
        EmailSettings validated = validateEmail(value);
        settings.saveEmail(validated);
        return new SaveResult(snapshot(), "✓ 已保存");
    }

    @Override
    public EmailProbeResult saveAndProbeEmail(EmailSettings value) throws Exception {
        EmailSettings validated = validateEmail(value);
        settings.saveEmail(validated);
        SaveResult saved = new SaveResult(snapshot(), "✓ 已保存");
        EmailConnectionProbePort.Result result = probes.probe(validated);
        return new EmailProbeResult(saved, result.succeeded(), probeMessage(result));
    }

    @Override
    public SaveResult saveNotifications(NotificationSettings value) {
        NotificationSettings validated = validateNotifications(value);
        settings.saveNotifications(validated);
        return new SaveResult(snapshot(), "✓ 已保存");
    }

    private static EmailSettings validateEmail(EmailSettings value) {
        Objects.requireNonNull(value, "settings");
        required(value.smtpHost(), "SMTP 地址");
        port(value.smtpPort(), "SMTP 端口");
        required(value.imapHost(), "IMAP 地址");
        port(value.imapPort(), "IMAP 端口");
        String from = value.fromAddress().isBlank() ? value.username() : value.fromAddress();
        return new EmailSettings(value.smtpHost(), value.smtpPort(), value.imapHost(),
                value.imapPort(), value.username(), value.password(), from, value.encryption());
    }

    private static NotificationSettings validateNotifications(NotificationSettings value) {
        Objects.requireNonNull(value, "settings");
        if (value.dingtalkEnabled()) httpUri(value.dingtalkWebhook(), "钉钉 Webhook");
        if (value.wechatEnabled()) httpUri(value.wechatWebhook(), "企业微信 Webhook");
        if (value.feishuEnabled()) httpUri(value.feishuWebhook(), "飞书 Webhook");
        if (value.emailEnabled()) required(value.emailRecipient(), "邮件通知收件人");
        if (value.customEnabled()) {
            httpUri(value.customWebhook(), "自定义 Webhook");
            required(value.customBodyTemplate(), "自定义请求体模板");
        }
        return value;
    }

    private static String probeMessage(EmailConnectionProbePort.Result result) {
        if (result.succeeded()) return "✓ SMTP 已连接 · IMAP 已连接";
        String smtp = result.smtpError().isBlank()
                ? "SMTP 正常" : "SMTP 失败: " + result.smtpError();
        String imap = result.imapError().isBlank()
                ? "IMAP 正常" : "IMAP 失败: " + result.imapError();
        return smtp + " · " + imap;
    }

    private static void port(int value, String label) {
        if (value < 1 || value > 65535) {
            throw new ValidationException(label + "需在 1 ~ 65535 之间");
        }
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(label + "不能为空");
        }
        return value.strip();
    }

    private static void httpUri(String value, String label) {
        try {
            URI uri = URI.create(required(value, label));
            if (!("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) {
                throw new IllegalArgumentException("unsupported URI");
            }
        } catch (IllegalArgumentException failure) {
            throw new ValidationException(label + "需为有效的 http:// 或 https:// 地址");
        }
    }
}
