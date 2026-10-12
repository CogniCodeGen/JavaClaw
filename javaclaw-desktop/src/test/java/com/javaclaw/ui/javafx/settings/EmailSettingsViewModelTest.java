package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.settings.CommunicationSettingsApplicationService.EmailSettings;
import com.javaclaw.application.settings.CommunicationSettingsApplicationService.Encryption;
import com.javaclaw.ui.javafx.settings.EmailSettingsViewModel.Preset;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmailSettingsViewModelTest {

    @Test
    void presetAndEncryptionUseTypedValuesIndependentOfDisplayLabels() {
        EmailSettingsViewModel model = new EmailSettingsViewModel();

        model.applyPreset(Preset.GMAIL);
        assertEquals("smtp.gmail.com", model.smtpHostProperty().get());
        assertEquals("587", model.smtpPortProperty().get());
        assertEquals("imap.gmail.com", model.imapHostProperty().get());
        assertEquals(Encryption.STARTTLS, model.encryptionValue());

        model.encryptionProperty().set(Encryption.NONE);
        assertEquals(Encryption.NONE, model.encryptionValue());

        model.applyPreset(Preset.OUTLOOK);
        assertEquals("smtp.office365.com", model.smtpHostProperty().get());
        assertEquals("outlook.office365.com", model.imapHostProperty().get());
        assertEquals(Encryption.STARTTLS, model.encryptionValue());

        model.applyPreset(Preset.QQ);
        assertEquals("smtp.qq.com", model.smtpHostProperty().get());
        assertEquals("imap.qq.com", model.imapHostProperty().get());
        assertEquals(Encryption.SSL, model.encryptionValue());

        model.applyPreset(Preset.NETEASE_163);
        assertEquals("smtp.163.com", model.smtpHostProperty().get());
        assertEquals("imap.163.com", model.imapHostProperty().get());
        assertEquals(Encryption.SSL, model.encryptionValue());
    }

    @Test
    void loadingMapsExactMailHostToProviderId() {
        EmailSettingsViewModel model = new EmailSettingsViewModel();
        model.load(settings("smtp.gmail.com", Encryption.NONE), "mail.properties");
        assertEquals(Preset.GMAIL, model.presetProperty().get());
        assertEquals(Encryption.NONE, model.encryptionProperty().get());

        model.load(settings("smtp.gmail.com.attacker.invalid", Encryption.SSL), "mail.properties");
        assertEquals(Preset.CUSTOM, model.presetProperty().get());
        assertEquals(Encryption.SSL, model.encryptionValue());
    }

    private static EmailSettings settings(String smtpHost, Encryption encryption) {
        return new EmailSettings(smtpHost, 465, "imap.example.com", 993,
                "user@example.com", "secret", "user@example.com", encryption);
    }
}
