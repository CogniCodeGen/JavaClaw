package com.javaclaw.email;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.config.EmailConfig;
import com.javaclaw.config.NotificationConfig;
import com.javaclaw.framework.spi.ToolEffectCapture;
import com.javaclaw.notification.NotificationTools;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.spring.ApplicationContexts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end mail transport acceptance against local SMTP and IMAP servers. */
class EmailToolsLocalFixtureTest {

    private static final String ADDRESS = "fixture@javaclaw.test";
    private static final String CC_ADDRESS = "fixture-cc@javaclaw.test";
    private static final String LOGIN = "fixture";
    private static final String PASSWORD = "fixture-secret";

    @TempDir
    Path temporaryDirectory;

    private AnnotationConfigApplicationContext context;
    private GreenMail mail;
    private boolean previousConfirmationState;
    private EmailConfig emailConfig;
    private EmailTools tools;

    @BeforeEach
    void startLocalMailServers() {
        ServerSetup smtp = new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP);
        ServerSetup imap = new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_IMAP);
        mail = new GreenMail(new ServerSetup[] {smtp, imap});
        mail.start();
        mail.setUser(ADDRESS, LOGIN, PASSWORD);
        mail.setUser(CC_ADDRESS, "fixture-cc", PASSWORD);

        context = ApplicationContexts.createRoot(
                new DataRoot(temporaryDirectory.resolve("data")));
        emailConfig = context.getBean(EmailConfig.class);
        emailConfig.setSmtpHost("127.0.0.1");
        emailConfig.setSmtpPort(mail.getSmtp().getPort());
        emailConfig.setImapHost("127.0.0.1");
        emailConfig.setImapPort(mail.getImap().getPort());
        emailConfig.setUsername(LOGIN);
        emailConfig.setPassword(PASSWORD);
        emailConfig.setFromAddress(ADDRESS);
        emailConfig.setUseStarttls(false);
        emailConfig.setUseSsl(false);

        previousConfirmationState = ToolConfirmationManager.isEnabled();
        ToolConfirmationManager.setEnabled(false);
        tools = new EmailTools(null, emailConfig);
    }

    @AfterEach
    void stopLocalMailServers() {
        ToolConfirmationManager.setEnabled(previousConfirmationState);
        if (context != null) context.close();
        if (mail != null) mail.stop();
    }

    @Test
    void allMailToolsCompleteThroughLocalSmtpAndImap() {
        String sent = tools.sendEmail(ADDRESS, "E2E fixture subject",
                "E2E fixture body", false);
        assertTrue(sent.contains("邮件发送成功"), sent);
        assertTrue(mail.waitForIncomingEmail(5_000, 1), "本地 SMTP 未投递邮件");

        String inbox = tools.listInbox(10);
        String unread = tools.listUnread(10);
        String read = tools.readEmail(1);
        String search = tools.searchEmail("fixture subject", 10);
        assertAll(
                () -> assertTrue(inbox.contains("E2E fixture subject"), inbox),
                () -> assertTrue(read.contains("E2E fixture body"), read),
                () -> assertTrue(search.contains("E2E fixture subject"), search),
                () -> assertTrue(unread.contains("E2E fixture subject"), unread));

        String withCc = tools.sendEmailWithCc(ADDRESS, CC_ADDRESS, "",
                "E2E cc subject", "E2E cc body", true);
        assertTrue(withCc.contains("邮件发送成功"), withCc);
        assertTrue(mail.waitForIncomingEmail(5_000, 3), "本地 SMTP 未投递 TO/CC 邮件");

        String reply;
        try (ToolEffectCapture.Scope capture = ToolEffectCapture.begin("email_reply")) {
            reply = tools.replyEmail(1, "E2E fixture reply", false);
            assertEquals(ToolEffectCapture.Signal.SUCCESS, capture.signal());
            assertEquals(ADDRESS, capture.target());
        }
        assertTrue(reply.contains("回复邮件成功"), reply);
        assertTrue(mail.waitForIncomingEmail(5_000, 4), "本地 SMTP 未投递回复邮件");

        NotificationConfig notificationConfig = context.getBean(NotificationConfig.class);
        notificationConfig.setEmailNotifyEnabled(true);
        notificationConfig.setEmailNotifyTo(ADDRESS);
        NotificationTools notificationTools = new NotificationTools(
                null, notificationConfig, emailConfig);
        try (ToolEffectCapture.Scope capture = ToolEffectCapture.begin("notify_email")) {
            notificationTools.sendEmailNotify(
                    "", "E2E notification subject", "E2E notification body");
            assertEquals(ToolEffectCapture.Signal.SUCCESS, capture.signal());
        }
        assertTrue(mail.waitForIncomingEmail(5_000, 5), "本地 SMTP 未投递通知邮件");
    }

    @Test
    void unreadablePasswordNeverReachesMailAuthentication() {
        emailConfig.setPassword("ENC(YmFk)");
        assertTrue(emailConfig.getPassword().startsWith("ENC("));

        String send = tools.sendEmail(ADDRESS, "subject", "body", false);
        String inbox = tools.listInbox(10);
        NotificationConfig notificationConfig = context.getBean(NotificationConfig.class);
        notificationConfig.setEmailNotifyEnabled(true);
        notificationConfig.setEmailNotifyTo(ADDRESS);
        NotificationTools notificationTools = new NotificationTools(
                null, notificationConfig, emailConfig);
        String notify = notificationTools.sendEmailNotify(
                "", "subject", "body");
        String routed = notificationTools.sendByChannel("email", "subject", "body");

        assertAll(
                () -> assertTrue(send.contains("邮件密码无法解密"), send),
                () -> assertTrue(inbox.contains("邮件密码无法解密"), inbox),
                () -> assertTrue(notify.contains("邮件密码无法解密"), notify),
                () -> assertTrue(routed.contains("邮件密码无法解密"), routed),
                () -> assertTrue(mail.getReceivedMessages().length == 0));
    }
}
