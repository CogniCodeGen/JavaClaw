package com.javaclaw.email;

import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.agent.model.ToolResponse;
import com.javaclaw.config.EmailConfig;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.search.FlagTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Properties;

/**
 * 邮件工具类（基于 AgentScope @Tool 注解）
 *
 * <p>为邮件智能体提供邮件收发工具，所有方法返回 {@link ToolResponse} 格式化的结构化响应，
 * 确保智能体能准确判断操作是否成功。</p>
 *
 * @author JavaClaw
 */
public class EmailTools {

    private static final Logger log = LoggerFactory.getLogger(EmailTools.class);

    /**
     * 获取 SMTP 发送会话
     */
    private Session getSmtpSession() {
        EmailConfig config = EmailConfig.getInstance();
        Properties props = new Properties();
        props.put("mail.smtp.host", config.getSmtpHost());
        props.put("mail.smtp.port", String.valueOf(config.getSmtpPort()));
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", String.valueOf(config.isUseStarttls()));
        props.put("mail.smtp.ssl.enable", String.valueOf(config.isUseSsl()));
        // 超时配置
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "15000");
        props.put("mail.smtp.writetimeout", "10000");

        return Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(
                        config.getUsername(), config.getPassword());
            }
        });
    }

    /**
     * 获取 IMAP 接收会话并连接到 Store
     */
    private Store getImapStore() throws MessagingException {
        EmailConfig config = EmailConfig.getInstance();
        Properties props = new Properties();
        props.put("mail.imap.host", config.getImapHost());
        props.put("mail.imap.port", String.valueOf(config.getImapPort()));
        props.put("mail.imap.ssl.enable", "true");
        props.put("mail.imap.connectiontimeout", "10000");
        props.put("mail.imap.timeout", "15000");

        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(
                        config.getUsername(), config.getPassword());
            }
        });

        Store store = session.getStore("imap");
        store.connect();
        return store;
    }

    @Tool(name = "email_send", description = "发送一封邮件。支持纯文本和 HTML 格式。" +
            "用于给指定收件人发送邮件，可选抄送。")
    public String sendEmail(
            @ToolParam(name = "to", description = "收件人邮箱地址，多个地址用逗号分隔") String to,
            @ToolParam(name = "subject", description = "邮件主题") String subject,
            @ToolParam(name = "body", description = "邮件正文内容") String body,
            @ToolParam(name = "is_html", description = "是否为 HTML 格式，true 为 HTML，false 为纯文本") boolean isHtml) {
        log.debug("工具调用: email_send(to={}, subject={})", to, subject);
        if (!ToolConfirmationManager.requestConfirmation("email_send",
                "发送邮件给: " + to + "\n主题: " + subject)) {
            return ToolResponse.error("email_send", "用户取消了发送操作");
        }
        try {
            Session session = getSmtpSession();
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(getValidFromAddress()));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(sanitizeAddress(to)));
            message.setSubject(sanitizeSubject(subject), "UTF-8");

            if (isHtml) {
                message.setContent(body, "text/html; charset=UTF-8");
            } else {
                message.setText(body, "UTF-8");
            }

            Transport.send(message);
            log.info("邮件发送成功: to={}, subject={}", to, subject);
            return ToolResponse.success("email_send",
                    "邮件发送成功！收件人: " + to + ", 主题: " + subject);
        } catch (Exception e) {
            log.error("邮件发送失败", e);
            return ToolResponse.fromException("email_send", e);
        }
    }

    @Tool(name = "email_send_with_cc", description = "发送一封带抄送的邮件。" +
            "支持指定抄送和密送收件人。")
    public String sendEmailWithCc(
            @ToolParam(name = "to", description = "收件人邮箱地址，多个地址用逗号分隔") String to,
            @ToolParam(name = "cc", description = "抄送邮箱地址，多个地址用逗号分隔，不需要抄送时传空字符串") String cc,
            @ToolParam(name = "bcc", description = "密送邮箱地址，多个地址用逗号分隔，不需要密送时传空字符串") String bcc,
            @ToolParam(name = "subject", description = "邮件主题") String subject,
            @ToolParam(name = "body", description = "邮件正文内容") String body,
            @ToolParam(name = "is_html", description = "是否为 HTML 格式") boolean isHtml) {
        log.debug("工具调用: email_send_with_cc(to={}, cc={}, subject={})", to, cc, subject);
        if (!ToolConfirmationManager.requestConfirmation("email_send_with_cc",
                "发送邮件给: " + to + (cc != null && !cc.isBlank() ? "\n抄送: " + cc : "") + "\n主题: " + subject)) {
            return ToolResponse.error("email_send_with_cc", "用户取消了发送操作");
        }
        try {
            Session session = getSmtpSession();
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(getValidFromAddress()));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(sanitizeAddress(to)));
            if (cc != null && !cc.isBlank()) {
                message.setRecipients(Message.RecipientType.CC, InternetAddress.parse(sanitizeAddress(cc)));
            }
            if (bcc != null && !bcc.isBlank()) {
                message.setRecipients(Message.RecipientType.BCC, InternetAddress.parse(sanitizeAddress(bcc)));
            }
            message.setSubject(sanitizeSubject(subject), "UTF-8");

            if (isHtml) {
                message.setContent(body, "text/html; charset=UTF-8");
            } else {
                message.setText(body, "UTF-8");
            }

            Transport.send(message);
            log.info("邮件发送成功（含抄送）: to={}, cc={}, subject={}", to, cc, subject);
            String detail = "邮件发送成功！收件人: " + to +
                    (cc != null && !cc.isBlank() ? ", 抄送: " + cc : "") +
                    ", 主题: " + subject;
            return ToolResponse.success("email_send_with_cc", detail);
        } catch (Exception e) {
            log.error("邮件发送失败", e);
            return ToolResponse.fromException("email_send_with_cc", e);
        }
    }

    @Tool(name = "email_list_inbox", description = "获取收件箱中最近的邮件列表。" +
            "返回每封邮件的序号、发件人、主题、日期和是否已读状态。")
    public String listInbox(
            @ToolParam(name = "count", description = "要获取的邮件数量，建议 10-30") int count) {
        log.debug("工具调用: email_list_inbox(count={})", count);
        Store store = null;
        Folder inbox = null;
        try {
            store = getImapStore();
            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);

            int totalMessages = inbox.getMessageCount();
            if (totalMessages == 0) {
                return ToolResponse.success("email_list_inbox", "收件箱为空，没有邮件。");
            }

            // 获取最近的 count 封邮件
            int start = Math.max(1, totalMessages - count + 1);
            Message[] messages = inbox.getMessages(start, totalMessages);

            StringBuilder sb = new StringBuilder();
            sb.append("收件箱共 ").append(totalMessages).append(" 封邮件，显示最近 ")
                    .append(messages.length).append(" 封：\n\n");

            // 从最新到最旧排列
            for (int i = messages.length - 1; i >= 0; i--) {
                Message msg = messages[i];
                boolean isRead = msg.isSet(Flags.Flag.SEEN);
                String from = msg.getFrom() != null && msg.getFrom().length > 0
                        ? ((InternetAddress) msg.getFrom()[0]).toUnicodeString()
                        : "未知发件人";
                String msgSubject = msg.getSubject() != null ? msg.getSubject() : "(无主题)";
                String date = msg.getSentDate() != null ? msg.getSentDate().toString() : "未知日期";

                sb.append(String.format("[%d] %s | 发件人: %s | 主题: %s | 日期: %s\n",
                        msg.getMessageNumber(),
                        isRead ? "已读" : "未读",
                        from, msgSubject, date));
            }

            return ToolResponse.success("email_list_inbox", sb.toString());
        } catch (Exception e) {
            log.error("获取收件箱失败", e);
            return ToolResponse.fromException("email_list_inbox", e);
        } finally {
            closeQuietly(inbox, store);
        }
    }

    @Tool(name = "email_read", description = "读取指定序号的邮件详细内容。" +
            "返回发件人、收件人、主题、日期和正文内容。")
    public String readEmail(
            @ToolParam(name = "message_number", description = "邮件序号，来自 email_list_inbox 返回的序号") int messageNumber) {
        log.debug("工具调用: email_read(messageNumber={})", messageNumber);
        Store store = null;
        Folder inbox = null;
        try {
            store = getImapStore();
            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);

            if (messageNumber < 1 || messageNumber > inbox.getMessageCount()) {
                return ToolResponse.error("email_read",
                        "邮件序号无效，当前收件箱共 " + inbox.getMessageCount() + " 封邮件。");
            }

            Message msg = inbox.getMessage(messageNumber);
            StringBuilder sb = new StringBuilder();

            // 基本信息
            String from = msg.getFrom() != null && msg.getFrom().length > 0
                    ? ((InternetAddress) msg.getFrom()[0]).toUnicodeString()
                    : "未知发件人";
            sb.append("发件人: ").append(from).append("\n");

            if (msg.getRecipients(Message.RecipientType.TO) != null) {
                sb.append("收件人: ").append(InternetAddress.toString(
                        msg.getRecipients(Message.RecipientType.TO))).append("\n");
            }
            if (msg.getRecipients(Message.RecipientType.CC) != null) {
                sb.append("抄送: ").append(InternetAddress.toString(
                        msg.getRecipients(Message.RecipientType.CC))).append("\n");
            }

            sb.append("主题: ").append(msg.getSubject() != null ? msg.getSubject() : "(无主题)").append("\n");
            sb.append("日期: ").append(msg.getSentDate() != null ? msg.getSentDate().toString() : "未知").append("\n");
            sb.append("状态: ").append(msg.isSet(Flags.Flag.SEEN) ? "已读" : "未读").append("\n");
            sb.append("\n--- 正文 ---\n");

            // 提取正文
            String bodyText = extractTextContent(msg);
            sb.append(bodyText);

            return ToolResponse.success("email_read", sb.toString());
        } catch (Exception e) {
            log.error("读取邮件失败", e);
            return ToolResponse.fromException("email_read", e);
        } finally {
            closeQuietly(inbox, store);
        }
    }

    @Tool(name = "email_search", description = "在收件箱中搜索包含指定关键词的邮件。" +
            "搜索范围包括邮件主题和发件人。")
    public String searchEmail(
            @ToolParam(name = "keyword", description = "搜索关键词") String keyword,
            @ToolParam(name = "max_results", description = "最大返回结果数，建议 5-20") int maxResults) {
        log.debug("工具调用: email_search(keyword={}, maxResults={})", keyword, maxResults);
        Store store = null;
        Folder inbox = null;
        try {
            store = getImapStore();
            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);

            int totalMessages = inbox.getMessageCount();
            if (totalMessages == 0) {
                return ToolResponse.success("email_search", "收件箱为空，无法搜索。");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("搜索关键词: \"").append(keyword).append("\"\n\n");

            int found = 0;
            String lowerKeyword = keyword.toLowerCase();

            // 从最新邮件开始搜索
            for (int i = totalMessages; i >= 1 && found < maxResults; i--) {
                Message msg = inbox.getMessage(i);
                String msgSubject = msg.getSubject() != null ? msg.getSubject() : "";
                String from = msg.getFrom() != null && msg.getFrom().length > 0
                        ? ((InternetAddress) msg.getFrom()[0]).toUnicodeString()
                        : "";

                if (msgSubject.toLowerCase().contains(lowerKeyword)
                        || from.toLowerCase().contains(lowerKeyword)) {
                    boolean isRead = msg.isSet(Flags.Flag.SEEN);
                    String date = msg.getSentDate() != null ? msg.getSentDate().toString() : "未知日期";

                    sb.append(String.format("[%d] %s | 发件人: %s | 主题: %s | 日期: %s\n",
                            msg.getMessageNumber(),
                            isRead ? "已读" : "未读",
                            from,
                            msgSubject.isEmpty() ? "(无主题)" : msgSubject,
                            date));
                    found++;
                }
            }

            if (found == 0) {
                sb.append("未找到匹配的邮件。");
            } else {
                sb.insert(sb.indexOf("\n\n") + 2, "找到 " + found + " 封匹配邮件：\n");
            }

            return ToolResponse.success("email_search", sb.toString());
        } catch (Exception e) {
            log.error("搜索邮件失败", e);
            return ToolResponse.fromException("email_search", e);
        } finally {
            closeQuietly(inbox, store);
        }
    }

    @Tool(name = "email_list_unread", description = "获取收件箱中所有未读邮件。" +
            "返回未读邮件的序号、发件人、主题和日期。")
    public String listUnread(
            @ToolParam(name = "max_results", description = "最大返回结果数，建议 10-50") int maxResults) {
        log.debug("工具调用: email_list_unread(maxResults={})", maxResults);
        Store store = null;
        Folder inbox = null;
        try {
            store = getImapStore();
            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);

            // 搜索未读邮件
            Message[] unreadMessages = inbox.search(
                    new FlagTerm(new Flags(Flags.Flag.SEEN), false));

            if (unreadMessages.length == 0) {
                return ToolResponse.success("email_list_unread", "没有未读邮件。");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("共 ").append(unreadMessages.length).append(" 封未读邮件");

            int showCount = Math.min(unreadMessages.length, maxResults);
            sb.append("，显示最近 ").append(showCount).append(" 封：\n\n");

            // 从最新到最旧
            for (int i = unreadMessages.length - 1; i >= Math.max(0, unreadMessages.length - maxResults); i--) {
                Message msg = unreadMessages[i];
                String from = msg.getFrom() != null && msg.getFrom().length > 0
                        ? ((InternetAddress) msg.getFrom()[0]).toUnicodeString()
                        : "未知发件人";
                String msgSubject = msg.getSubject() != null ? msg.getSubject() : "(无主题)";
                String date = msg.getSentDate() != null ? msg.getSentDate().toString() : "未知日期";

                sb.append(String.format("[%d] 发件人: %s | 主题: %s | 日期: %s\n",
                        msg.getMessageNumber(), from, msgSubject, date));
            }

            return ToolResponse.success("email_list_unread", sb.toString());
        } catch (Exception e) {
            log.error("获取未读邮件失败", e);
            return ToolResponse.fromException("email_list_unread", e);
        } finally {
            closeQuietly(inbox, store);
        }
    }

    @Tool(name = "email_reply", description = "回复一封邮件。自动设置回复的收件人和主题（Re: 前缀）。")
    public String replyEmail(
            @ToolParam(name = "message_number", description = "要回复的邮件序号") int messageNumber,
            @ToolParam(name = "body", description = "回复正文内容") String body,
            @ToolParam(name = "reply_all", description = "是否回复全部（包括所有收件人和抄送人）") boolean replyAll) {
        log.debug("工具调用: email_reply(messageNumber={}, replyAll={})", messageNumber, replyAll);
        if (!ToolConfirmationManager.requestConfirmation("email_reply",
                "回复邮件 #" + messageNumber + (replyAll ? "（回复全部）" : ""))) {
            return ToolResponse.error("email_reply", "用户取消了操作");
        }
        Store store = null;
        Folder inbox = null;
        try {
            store = getImapStore();
            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);

            if (messageNumber < 1 || messageNumber > inbox.getMessageCount()) {
                return ToolResponse.error("email_reply", "邮件序号无效。");
            }

            Message originalMsg = inbox.getMessage(messageNumber);

            Session smtpSession = getSmtpSession();
            MimeMessage reply = (MimeMessage) originalMsg.reply(replyAll);
            reply.setFrom(new InternetAddress(EmailConfig.getInstance().getFromAddress()));

            // 构建回复正文：新内容 + 原始邮件引用
            String originalFrom = originalMsg.getFrom() != null && originalMsg.getFrom().length > 0
                    ? ((InternetAddress) originalMsg.getFrom()[0]).toUnicodeString()
                    : "未知";
            String quotedBody = "\n\n--- 原始邮件 ---\n发件人: " + originalFrom +
                    "\n日期: " + (originalMsg.getSentDate() != null ? originalMsg.getSentDate().toString() : "未知") +
                    "\n\n" + extractTextContent(originalMsg);

            reply.setText(body + quotedBody, "UTF-8");

            // 使用 SMTP 会话发送
            EmailConfig config = EmailConfig.getInstance();
            try (Transport transport = smtpSession.getTransport("smtp")) {
                transport.connect(config.getSmtpHost(),
                        config.getUsername(), config.getPassword());
                transport.sendMessage(reply, reply.getAllRecipients());
            }

            String replyTo = InternetAddress.toString(reply.getRecipients(Message.RecipientType.TO));
            log.info("回复邮件成功: to={}", replyTo);
            return ToolResponse.success("email_reply",
                    "回复邮件成功！" + (replyAll ? "（回复全部）" : "") + " 收件人: " + replyTo);
        } catch (Exception e) {
            log.error("回复邮件失败", e);
            return ToolResponse.fromException("email_reply", e);
        } finally {
            closeQuietly(inbox, store);
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 获取有效的发件人地址：fromAddress 无效时回退到 username
     */
    private String getValidFromAddress() {
        EmailConfig config = EmailConfig.getInstance();
        String from = config.getFromAddress();
        if (from == null || from.isBlank() || !from.contains("@")) {
            log.warn("发件人地址无效（{}），回退使用登录用户名: {}", from, config.getUsername());
            return config.getUsername();
        }
        return from;
    }

    /**
     * 清理邮件主题：移除换行符和控制字符，避免 SMTP 502 错误
     */
    private String sanitizeSubject(String subject) {
        if (subject == null) return "";
        return subject.replaceAll("[\\r\\n\\t]+", " ").trim();
    }

    /**
     * 清理邮件地址：移除多余空白字符，避免 SMTP 502 错误
     */
    private String sanitizeAddress(String address) {
        if (address == null) return "";
        return address.replaceAll("[\\r\\n]+", "").replaceAll("\\s+", " ").trim();
    }

    /**
     * 安全关闭邮件资源（Folder 和 Store）
     */
    private void closeQuietly(Folder folder, Store store) {
        try {
            if (folder != null && folder.isOpen()) {
                folder.close(false);
            }
        } catch (Exception e) {
            log.debug("关闭 Folder 失败", e);
        }
        try {
            if (store != null && store.isConnected()) {
                store.close();
            }
        } catch (Exception e) {
            log.debug("关闭 Store 失败", e);
        }
    }

    /** multipart 递归最大层数，防御嵌套邮件触发 StackOverflow */
    private static final int MAX_PART_DEPTH = 8;

    /**
     * 提取邮件的纯文本内容（递归处理 multipart）
     */
    private String extractTextContent(Part part) throws MessagingException, IOException {
        return extractTextContent(part, 0);
    }

    private String extractTextContent(Part part, int depth) throws MessagingException, IOException {
        if (depth > MAX_PART_DEPTH) {
            log.warn("邮件 multipart 嵌套层数超过 {}，停止递归解析", MAX_PART_DEPTH);
            return "(邮件嵌套层级过深，已截断)";
        }
        if (part.isMimeType("text/plain")) {
            return (String) part.getContent();
        }
        if (part.isMimeType("text/html")) {
            String html = (String) part.getContent();
            return html.replaceAll("<[^>]+>", "").replaceAll("&nbsp;", " ").trim();
        }
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart bodyPart = multipart.getBodyPart(i);
                if (bodyPart.isMimeType("text/plain")) {
                    return (String) bodyPart.getContent();
                }
            }
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart bodyPart = multipart.getBodyPart(i);
                String result = extractTextContent(bodyPart, depth + 1);
                if (result != null && !result.isBlank()) {
                    return result;
                }
            }
        }
        return "(无法解析的邮件内容格式)";
    }
}
