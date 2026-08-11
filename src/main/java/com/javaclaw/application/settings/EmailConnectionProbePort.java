package com.javaclaw.application.settings;

import com.javaclaw.application.settings.CommunicationSettingsApplicationService.EmailSettings;

/** 阻塞式 SMTP/IMAP 探测端口；实现必须设置有限网络超时并响应线程中断。 */
public interface EmailConnectionProbePort {

    Result probe(EmailSettings settings) throws InterruptedException;

    record Result(String smtpError, String imapError) {
        public Result {
            smtpError = normalize(smtpError);
            imapError = normalize(imapError);
        }

        public boolean succeeded() {
            return smtpError.isBlank() && imapError.isBlank();
        }

        private static String normalize(String value) {
            return value == null ? "" : value.strip();
        }
    }
}
