package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.settings.CommunicationSettingsApplicationService.EmailSettings;
import com.javaclaw.application.settings.CommunicationSettingsApplicationService.Encryption;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** 邮件设置页的纯 JavaFX 状态，不持有应用服务或持久化对象。 */
public final class EmailSettingsViewModel {

    private final StringProperty preset = new SimpleStringProperty("自定义");
    private final StringProperty smtpHost = new SimpleStringProperty("");
    private final StringProperty smtpPort = new SimpleStringProperty("");
    private final StringProperty imapHost = new SimpleStringProperty("");
    private final StringProperty imapPort = new SimpleStringProperty("");
    private final StringProperty username = new SimpleStringProperty("");
    private final StringProperty password = new SimpleStringProperty("");
    private final StringProperty fromAddress = new SimpleStringProperty("");
    private final StringProperty encryption = new SimpleStringProperty("SSL");
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
        encryption.set(label(value.encryption()));
        preset.set(detectPreset(value.smtpHost()));
        storageDescription.set("配置文件: " + (storage == null ? "" : storage));
        error.set("");
    }

    public void applyPreset(String value) {
        switch (value == null ? "" : value) {
            case "QQ 邮箱" -> endpoints("smtp.qq.com", "465", "imap.qq.com", "993", "SSL");
            case "163 邮箱" -> endpoints("smtp.163.com", "465", "imap.163.com", "993", "SSL");
            case "Gmail" -> endpoints("smtp.gmail.com", "587", "imap.gmail.com", "993", "STARTTLS");
            case "Outlook" -> endpoints("smtp.office365.com", "587",
                    "outlook.office365.com", "993", "STARTTLS");
            default -> { }
        }
    }

    public Encryption encryptionValue() {
        return switch (encryption.get()) {
            case "STARTTLS" -> Encryption.STARTTLS;
            case "无" -> Encryption.NONE;
            default -> Encryption.SSL;
        };
    }

    public StringProperty presetProperty() { return preset; }
    public StringProperty smtpHostProperty() { return smtpHost; }
    public StringProperty smtpPortProperty() { return smtpPort; }
    public StringProperty imapHostProperty() { return imapHost; }
    public StringProperty imapPortProperty() { return imapPort; }
    public StringProperty usernameProperty() { return username; }
    public StringProperty passwordProperty() { return password; }
    public StringProperty fromAddressProperty() { return fromAddress; }
    public StringProperty encryptionProperty() { return encryption; }
    public StringProperty storageDescriptionProperty() { return storageDescription; }
    public StringProperty errorProperty() { return error; }
    public BooleanProperty busyProperty() { return busy; }

    private void endpoints(
            String smtp, String smtpPortValue, String imap, String imapPortValue,
            String encryptionValue) {
        smtpHost.set(smtp);
        smtpPort.set(smtpPortValue);
        imapHost.set(imap);
        imapPort.set(imapPortValue);
        encryption.set(encryptionValue);
    }

    private static String detectPreset(String smtp) {
        if (smtp.contains("qq.com")) return "QQ 邮箱";
        if (smtp.contains("163.com")) return "163 邮箱";
        if (smtp.contains("gmail.com")) return "Gmail";
        if (smtp.contains("office365.com")) return "Outlook";
        return "自定义";
    }

    private static String label(Encryption value) {
        return switch (value) {
            case SSL -> "SSL";
            case STARTTLS -> "STARTTLS";
            case NONE -> "无";
        };
    }
}
