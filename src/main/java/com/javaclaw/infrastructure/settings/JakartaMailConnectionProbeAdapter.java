package com.javaclaw.infrastructure.settings;

import com.javaclaw.application.settings.CommunicationSettingsApplicationService.EmailSettings;
import com.javaclaw.application.settings.CommunicationSettingsApplicationService.Encryption;
import com.javaclaw.application.settings.EmailConnectionProbePort;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Transport;

import java.util.Properties;

/** 使用 Jakarta Mail 对表单中的 SMTP 与 IMAP 连接分别执行有限超时探测。 */
public final class JakartaMailConnectionProbeAdapter implements EmailConnectionProbePort {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(
            JakartaMailConnectionProbeAdapter.class);
    private static final String TIMEOUT_MILLIS = "5000";

    @Override
    public Result probe(EmailSettings settings) throws InterruptedException {
        checkInterrupted();
        String smtpError = probeSmtp(settings);
        checkInterrupted();
        String imapError = probeImap(settings);
        checkInterrupted();
        return new Result(smtpError, imapError);
    }

    private static String probeSmtp(EmailSettings settings) throws InterruptedException {
        Transport transport = null;
        try {
            Properties properties = common("smtp", settings.smtpHost(), settings.smtpPort());
            properties.put("mail.smtp.auth", "true");
            properties.put("mail.smtp.ssl.enable",
                    Boolean.toString(settings.encryption() == Encryption.SSL));
            properties.put("mail.smtp.starttls.enable",
                    Boolean.toString(settings.encryption() == Encryption.STARTTLS));
            transport = Session.getInstance(properties).getTransport("smtp");
            transport.connect(settings.smtpHost(), settings.username(), settings.password());
            return "";
        } catch (Exception failure) {
            checkInterrupted();
            return describe(failure);
        } finally {
            close(transport);
        }
    }

    private static String probeImap(EmailSettings settings) throws InterruptedException {
        Store store = null;
        try {
            Properties properties = common("imap", settings.imapHost(), settings.imapPort());
            properties.put("mail.imap.ssl.enable",
                    Boolean.toString(settings.encryption() == Encryption.SSL));
            properties.put("mail.imap.starttls.enable",
                    Boolean.toString(settings.encryption() == Encryption.STARTTLS));
            store = Session.getInstance(properties).getStore("imap");
            store.connect(settings.imapHost(), settings.username(), settings.password());
            return "";
        } catch (Exception failure) {
            checkInterrupted();
            return describe(failure);
        } finally {
            close(store);
        }
    }

    private static Properties common(String protocol, String host, int port) {
        Properties properties = new Properties();
        properties.put("mail." + protocol + ".host", host);
        properties.put("mail." + protocol + ".port", Integer.toString(port));
        properties.put("mail." + protocol + ".connectiontimeout", TIMEOUT_MILLIS);
        properties.put("mail." + protocol + ".timeout", TIMEOUT_MILLIS);
        properties.put("mail." + protocol + ".writetimeout", TIMEOUT_MILLIS);
        return properties;
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("邮件连接探测已取消");
        }
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : message;
    }

    private static void close(jakarta.mail.Service service) {
        if (service == null || !service.isConnected()) return;
        try {
            service.close();
        } catch (Exception closeFailure) {
            // 探测结果由 connect 决定；关闭失败只记录诊断，不覆盖已获得的连接结果。
            log.debug("关闭邮件探测连接失败: {}", closeFailure.getMessage());
        }
    }
}
