package com.javaclaw.application.settings;

import com.javaclaw.application.error.ValidationException;
import com.javaclaw.application.settings.CommunicationSettingsApplicationService.EmailSettings;
import com.javaclaw.application.settings.CommunicationSettingsApplicationService.Encryption;
import com.javaclaw.application.settings.CommunicationSettingsApplicationService.NotificationSettings;
import com.javaclaw.application.settings.CommunicationSettingsApplicationService.Snapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommunicationSettingsUseCaseTest {

    @Test
    void validatesTheWholeEmailFormAndDefaultsTheFromAddressBeforeSaving() {
        FakeSettingsPort settings = new FakeSettingsPort(snapshot());
        CommunicationSettingsUseCase useCase = new CommunicationSettingsUseCase(
                settings, value -> new EmailConnectionProbePort.Result("", ""));
        EmailSettings changed = new EmailSettings("smtp.example.com", 587,
                "imap.example.com", 993, "owner@example.com", "secret", "",
                Encryption.STARTTLS);

        var result = useCase.saveEmail(changed);

        assertEquals(1, settings.emailSaves);
        assertEquals("owner@example.com", result.snapshot().email().fromAddress());

        EmailSettings invalid = new EmailSettings("", 70000, "imap.example.com", 993,
                "owner@example.com", "secret", "", Encryption.SSL);
        assertThrows(ValidationException.class, () -> useCase.saveEmail(invalid));
        assertEquals(1, settings.emailSaves, "校验失败不得产生部分写入");
    }

    @Test
    void connectionProbePersistsFirstAndReportsBothIndependentChannels() throws Exception {
        FakeSettingsPort settings = new FakeSettingsPort(snapshot());
        CommunicationSettingsUseCase useCase = new CommunicationSettingsUseCase(
                settings, value -> {
                    assertEquals(1, settings.emailSaves, "探测前应先保存当前表单");
                    return new EmailConnectionProbePort.Result("认证失败", "");
                });

        var result = useCase.saveAndProbeEmail(snapshot().email());

        assertFalse(result.succeeded());
        assertEquals("SMTP 失败: 认证失败 · IMAP 正常", result.message());
        assertEquals("✓ 已保存", result.saved().message());
    }

    @Test
    void enabledNotificationEndpointsAreValidatedBeforeOneSave() {
        FakeSettingsPort settings = new FakeSettingsPort(snapshot());
        CommunicationSettingsUseCase useCase = new CommunicationSettingsUseCase(
                settings, value -> new EmailConnectionProbePort.Result("", ""));
        NotificationSettings valid = new NotificationSettings(
                true, "https://notify.example/dingtalk", "secret",
                false, "", false, "", "",
                true, "owner@example.com", true, "https://notify.example/custom",
                "{\"content\":\"${message}\"}");

        assertTrue(useCase.saveNotifications(valid).message().contains("已保存"));
        assertEquals(1, settings.notificationSaves);

        NotificationSettings invalid = new NotificationSettings(
                true, "file:///tmp/hook", "", false, "", false, "", "",
                false, "", false, "", "");
        assertThrows(ValidationException.class, () -> useCase.saveNotifications(invalid));
        assertEquals(1, settings.notificationSaves);
    }

    private static Snapshot snapshot() {
        return new Snapshot(
                new EmailSettings("smtp.qq.com", 465, "imap.qq.com", 993,
                        "owner@example.com", "secret", "owner@example.com", Encryption.SSL),
                new NotificationSettings(false, "", "", false, "", false, "", "",
                        false, "", false, "", "{\"content\":\"${message}\"}"),
                "fake-h2");
    }

    private static final class FakeSettingsPort implements CommunicationSettingsPort {
        private Snapshot snapshot;
        private int emailSaves;
        private int notificationSaves;

        private FakeSettingsPort(Snapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override public Snapshot load() { return snapshot; }

        @Override public void saveEmail(EmailSettings value) {
            emailSaves++;
            snapshot = new Snapshot(value, snapshot.notifications(),
                    snapshot.storageDescription());
        }

        @Override public void saveNotifications(NotificationSettings value) {
            notificationSaves++;
            snapshot = new Snapshot(snapshot.email(), value, snapshot.storageDescription());
        }
    }
}
