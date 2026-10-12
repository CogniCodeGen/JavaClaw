package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.settings.CommunicationSettingsApplicationService.EmailSettings;
import com.javaclaw.application.settings.CommunicationSettingsApplicationService.Encryption;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.util.Objects;

/** 邮件设置页的纯 JavaFX 状态，不持有应用服务或持久化对象。 */
public final class EmailSettingsViewModel {

    public enum Preset {
        QQ,
        NETEASE_163,
        GMAIL,
        OUTLOOK,
        CUSTOM
    }

    private final ObjectProperty<Preset> preset = new SimpleObjectProperty<>(Preset.CUSTOM);
    private final StringProperty smtpHost = new SimpleStringProperty("");
    private final StringProperty smtpPort = new SimpleStringProperty("");
    private final StringProperty imapHost = new SimpleStringProperty("");
    private final StringProperty imapPort = new SimpleStringProperty("");
    private final StringProperty username = new SimpleStringProperty("");
    private final StringProperty password = new SimpleStringProperty("");
    private final StringProperty fromAddress = new SimpleStringProperty("");
    private final ObjectProperty<Encryption> encryption =
            new SimpleObjectProperty<>(Encryption.SSL);
    private final StringProperty storageDescription = new SimpleStringProperty("");
    private final StringProperty error = new SimpleStringProperty("");
    private final BooleanProperty busy = new SimpleBooleanProperty(false);

    public void load(EmailSettings value, String storage) {
        smtpHost.set(value.smtpHost());
        smtpPort.set(Integer.toString(value.smtpPort()));
        imapHost.set(value.imapHost());
        imapPort.set(Integer.toString(value.imapPort()));
        username.set(value.username());
        password.set(value.password());
        fromAddress.set(value.fromAddress());
        encryption.set(value.encryption());
        preset.set(detectPreset(value.smtpHost()));
        storageDescription.set("配置文件: " + (storage == null ? "" : storage));
        error.set("");
    }

    public void applyPreset(Preset value) {
        if (value == null) return;
        switch (value) {
            case QQ -> endpoints("smtp.qq.com", "465", "imap.qq.com", "993", Encryption.SSL);
            case NETEASE_163 -> endpoints("smtp.163.com", "465", "imap.163.com", "993",
                    Encryption.SSL);
            case GMAIL -> endpoints("smtp.gmail.com", "587", "imap.gmail.com", "993",
                    Encryption.STARTTLS);
            case OUTLOOK -> endpoints("smtp.office365.com", "587",
                    "outlook.office365.com", "993", Encryption.STARTTLS);
            case CUSTOM -> { }
        }
    }

    public Encryption encryptionValue() {
        return Objects.requireNonNull(encryption.get(), "encryption");
    }

    public ObjectProperty<Preset> presetProperty() { return preset; }
    public StringProperty smtpHostProperty() { return smtpHost; }
    public StringProperty smtpPortProperty() { return smtpPort; }
    public StringProperty imapHostProperty() { return imapHost; }
    public StringProperty imapPortProperty() { return imapPort; }
    public StringProperty usernameProperty() { return username; }
    public StringProperty passwordProperty() { return password; }
    public StringProperty fromAddressProperty() { return fromAddress; }
    public ObjectProperty<Encryption> encryptionProperty() { return encryption; }
    public StringProperty storageDescriptionProperty() { return storageDescription; }
    public StringProperty errorProperty() { return error; }
    public BooleanProperty busyProperty() { return busy; }

    private void endpoints(
            String smtp, String smtpPortValue, String imap, String imapPortValue,
            Encryption encryptionValue) {
        smtpHost.set(smtp);
        smtpPort.set(smtpPortValue);
        imapHost.set(imap);
        imapPort.set(imapPortValue);
        encryption.set(encryptionValue);
    }

    private static Preset detectPreset(String smtp) {
        String host = smtp == null ? "" : smtp.strip().toLowerCase(java.util.Locale.ROOT);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return switch (host) {
            case "smtp.qq.com" -> Preset.QQ;
            case "smtp.163.com" -> Preset.NETEASE_163;
            case "smtp.gmail.com" -> Preset.GMAIL;
            case "smtp.office365.com" -> Preset.OUTLOOK;
            default -> Preset.CUSTOM;
        };
    }
}
