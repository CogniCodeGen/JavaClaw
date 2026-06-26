package com.javaclaw.notification;

import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.agent.model.ToolResponse;
import com.javaclaw.config.EmailConfig;
import com.javaclaw.config.NotificationConfig;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Properties;

/**
 * 通知工具类（基于 AgentScope @Tool 注解）
 *
 * <p>为通知智能体提供多渠道消息发送工具，支持钉钉机器人、企业微信机器人、
 * 飞书机器人、邮件通知和自定义 Webhook。所有方法返回 {@link ToolResponse} 格式化响应。</p>
 *
 * @author JavaClaw
 */
public class NotificationTools {

    private static final Logger log = LoggerFactory.getLogger(NotificationTools.class);

    private final HttpClient httpClient;

    public NotificationTools() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ==================== 发送通知工具 ====================

    @Tool(name = "notify_send", description = "向所有已启用的通知渠道发送消息。" +
            "会自动检测已配置并启用的渠道（钉钉、企业微信、飞书、邮件、自定义Webhook），逐一发送。")
    public String sendNotification(
            @ToolParam(name = "message", description = "要发送的通知消息内容") String message,
            @ToolParam(name = "title", description = "通知标题，部分渠道会显示标题") String title) {
        log.debug("工具调用: notify_send(title={})", title);
        if (!ToolConfirmationManager.requestConfirmation("notify_send",
                "向所有已启用渠道发送通知: " + (title == null ? "" : title))) {
            return ToolResponse.error("notify_send", "用户取消了操作");
        }

        NotificationConfig config = NotificationConfig.getInstance();
        if (!config.hasEnabledChannel()) {
            return ToolResponse.error("notify_send", "没有已启用的通知渠道，请先在设置中配置并启用至少一个通知渠道。");
        }

        StringBuilder results = new StringBuilder();
        int successCount = 0;
        int failCount = 0;

        if (config.isDingtalkEnabled()) {
            String result = sendDingtalkInternal(title, message);
            results.append("钉钉: ").append(result).append("\n");
            if (result.contains("成功")) successCount++;
            else failCount++;
        }

        if (config.isWechatEnabled()) {
            String result = sendWechatInternal(title, message);
            results.append("企业微信: ").append(result).append("\n");
            if (result.contains("成功")) successCount++;
            else failCount++;
        }

        if (config.isFeishuEnabled()) {
            String result = sendFeishuInternal(title, message);
            results.append("飞书: ").append(result).append("\n");
            if (result.contains("成功")) successCount++;
            else failCount++;
        }

        if (config.isEmailNotifyEnabled()) {
            String result = sendEmailNotifyInternal(title, message);
            results.append("邮件: ").append(result).append("\n");
            if (result.contains("成功")) successCount++;
            else failCount++;
        }

        if (config.isCustomEnabled()) {
            String result = sendCustomWebhookInternal(title, message);
            results.append("自定义Webhook: ").append(result).append("\n");
            if (result.contains("成功")) successCount++;
            else failCount++;
        }

        String summary = String.format("发送完成 — 成功: %d, 失败: %d\n\n%s", successCount, failCount, results);
        return failCount == 0
                ? ToolResponse.success("notify_send", summary)
                : ToolResponse.error("notify_send", summary);
    }

    @Tool(name = "notify_dingtalk", description = "通过钉钉机器人发送消息通知。" +
            "需要在设置中配置钉钉 Webhook 地址。支持文本消息和 Markdown 格式。")
    public String sendDingtalk(
            @ToolParam(name = "title", description = "消息标题") String title,
            @ToolParam(name = "message", description = "消息内容，支持 Markdown 格式") String message,
            @ToolParam(name = "is_markdown", description = "是否使用 Markdown 格式，true 为 Markdown，false 为纯文本") boolean isMarkdown) {
        log.debug("工具调用: notify_dingtalk(title={})", title);
        if (!ToolConfirmationManager.requestConfirmation("notify_dingtalk",
                "向钉钉机器人发送通知: " + (title == null ? "" : title))) {
            return ToolResponse.error("notify_dingtalk", "用户取消了操作");
        }

        NotificationConfig config = NotificationConfig.getInstance();
        if (!config.isDingtalkEnabled() || config.getDingtalkWebhook().isBlank()) {
            return ToolResponse.error("notify_dingtalk", "钉钉通知未启用或未配置 Webhook 地址。");
        }

        try {
            String webhookUrl = buildDingtalkUrl(config);
            String body;
            if (isMarkdown) {
                body = String.format(
                        "{\"msgtype\":\"markdown\",\"markdown\":{\"title\":\"%s\",\"text\":\"%s\"}}",
                        escapeJson(title), escapeJson(message));
            } else {
                body = String.format(
                        "{\"msgtype\":\"text\",\"text\":{\"content\":\"%s\"}}",
                        escapeJson(title + "\n" + message));
            }

            String response = sendHttpPost(webhookUrl, body, "application/json");
            if (response.contains("\"errcode\":0") || response.contains("\"errmsg\":\"ok\"")) {
                return ToolResponse.success("notify_dingtalk", "钉钉消息发送成功");
            }
            return ToolResponse.error("notify_dingtalk", "钉钉返回错误: " + response);
        } catch (Exception e) {
            log.error("钉钉消息发送失败", e);
            return ToolResponse.fromException("notify_dingtalk", e);
        }
    }

    @Tool(name = "notify_wechat", description = "通过企业微信机器人发送消息通知。" +
            "需要在设置中配置企业微信 Webhook 地址。支持文本消息和 Markdown 格式。")
    public String sendWechat(
            @ToolParam(name = "message", description = "消息内容，支持 Markdown 格式") String message,
            @ToolParam(name = "is_markdown", description = "是否使用 Markdown 格式") boolean isMarkdown) {
        log.debug("工具调用: notify_wechat");
        if (!ToolConfirmationManager.requestConfirmation("notify_wechat",
                "向企业微信机器人发送通知")) {
            return ToolResponse.error("notify_wechat", "用户取消了操作");
        }

        NotificationConfig config = NotificationConfig.getInstance();
        if (!config.isWechatEnabled() || config.getWechatWebhook().isBlank()) {
            return ToolResponse.error("notify_wechat", "企业微信通知未启用或未配置 Webhook 地址。");
        }

        try {
            String body;
            if (isMarkdown) {
                body = String.format(
                        "{\"msgtype\":\"markdown\",\"markdown\":{\"content\":\"%s\"}}",
                        escapeJson(message));
            } else {
                body = String.format(
                        "{\"msgtype\":\"text\",\"text\":{\"content\":\"%s\"}}",
                        escapeJson(message));
            }

            String response = sendHttpPost(config.getWechatWebhook(), body, "application/json");
            if (response.contains("\"errcode\":0") || response.contains("\"errmsg\":\"ok\"")) {
                return ToolResponse.success("notify_wechat", "企业微信消息发送成功");
            }
            return ToolResponse.error("notify_wechat", "企业微信返回错误: " + response);
        } catch (Exception e) {
            log.error("企业微信消息发送失败", e);
            return ToolResponse.fromException("notify_wechat", e);
        }
    }

    @Tool(name = "notify_feishu", description = "通过飞书机器人发送消息通知。" +
            "需要在设置中配置飞书 Webhook 地址。支持富文本格式。")
    public String sendFeishu(
            @ToolParam(name = "title", description = "消息标题") String title,
            @ToolParam(name = "message", description = "消息内容") String message) {
        log.debug("工具调用: notify_feishu(title={})", title);
        if (!ToolConfirmationManager.requestConfirmation("notify_feishu",
                "向飞书机器人发送通知: " + (title == null ? "" : title))) {
            return ToolResponse.error("notify_feishu", "用户取消了操作");
        }

        NotificationConfig config = NotificationConfig.getInstance();
        if (!config.isFeishuEnabled() || config.getFeishuWebhook().isBlank()) {
            return ToolResponse.error("notify_feishu", "飞书通知未启用或未配置 Webhook 地址。");
        }

        try {
            String webhookUrl = config.getFeishuWebhook();
            String body;

            // 如果配置了签名密钥，添加签名
            String secret = config.getFeishuSecret();
            if (secret != null && !secret.isBlank()) {
                long timestamp = System.currentTimeMillis() / 1000;
                String sign = generateFeishuSign(timestamp, secret);
                body = String.format(
                        "{\"timestamp\":\"%d\",\"sign\":\"%s\",\"msg_type\":\"post\",\"content\":{\"post\":{\"zh_cn\":{\"title\":\"%s\",\"content\":[[{\"tag\":\"text\",\"text\":\"%s\"}]]}}}}",
                        timestamp, escapeJson(sign), escapeJson(title), escapeJson(message));
            } else {
                body = String.format(
                        "{\"msg_type\":\"post\",\"content\":{\"post\":{\"zh_cn\":{\"title\":\"%s\",\"content\":[[{\"tag\":\"text\",\"text\":\"%s\"}]]}}}}",
                        escapeJson(title), escapeJson(message));
            }

            String response = sendHttpPost(webhookUrl, body, "application/json");
            if (response.contains("\"StatusCode\":0") || response.contains("\"code\":0") || response.contains("\"StatusMessage\":\"success\"")) {
                return ToolResponse.success("notify_feishu", "飞书消息发送成功");
            }
            return ToolResponse.error("notify_feishu", "飞书返回错误: " + response);
        } catch (Exception e) {
            log.error("飞书消息发送失败", e);
            return ToolResponse.fromException("notify_feishu", e);
        }
    }

    @Tool(name = "notify_email", description = "通过邮件发送通知。" +
            "使用已配置的邮件账号向指定收件人发送通知邮件。")
    public String sendEmailNotify(
            @ToolParam(name = "to", description = "收件人邮箱地址，留空则使用默认通知收件人") String to,
            @ToolParam(name = "subject", description = "邮件主题") String subject,
            @ToolParam(name = "body", description = "邮件正文") String body) {
        log.debug("工具调用: notify_email(to={}, subject={})", to, subject);
        if (!ToolConfirmationManager.requestConfirmation("notify_email",
                "发送通知邮件到 " + (to == null || to.isBlank() ? "默认收件人" : to)
                        + " (主题: " + subject + ")")) {
            return ToolResponse.error("notify_email", "用户取消了操作");
        }

        NotificationConfig notifyConfig = NotificationConfig.getInstance();
        EmailConfig emailConfig = EmailConfig.getInstance();

        if (!emailConfig.isConfigured()) {
            return ToolResponse.error("notify_email", "邮件账号未配置，请先在设置中配置邮件账号。");
        }

        // 确定收件人
        String recipient = (to != null && !to.isBlank()) ? to : notifyConfig.getEmailNotifyTo();
        if (recipient.isBlank()) {
            return ToolResponse.error("notify_email", "未指定收件人，且未配置默认通知收件人。");
        }

        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", emailConfig.getSmtpHost());
            props.put("mail.smtp.port", String.valueOf(emailConfig.getSmtpPort()));
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.starttls.enable", String.valueOf(emailConfig.isUseStarttls()));
            props.put("mail.smtp.ssl.enable", String.valueOf(emailConfig.isUseSsl()));
            props.put("mail.smtp.connectiontimeout", "10000");
            props.put("mail.smtp.timeout", "15000");

            Session session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(emailConfig.getUsername(), emailConfig.getPassword());
                }
            });

            MimeMessage message = new MimeMessage(session);
            String fromAddr = emailConfig.getFromAddress();
            if (fromAddr == null || fromAddr.isBlank() || !fromAddr.contains("@")) {
                fromAddr = emailConfig.getUsername();
            }
            message.setFrom(new InternetAddress(fromAddr));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient));
            message.setSubject(subject, "UTF-8");
            message.setText(body, "UTF-8");

            Transport.send(message);
            log.info("通知邮件发送成功: to={}", recipient);
            return ToolResponse.success("notify_email", "通知邮件发送成功，收件人: " + recipient);
        } catch (Exception e) {
            log.error("通知邮件发送失败", e);
            return ToolResponse.fromException("notify_email", e);
        }
    }

    @Tool(name = "notify_custom_webhook", description = "通过自定义 Webhook 发送通知。" +
            "使用用户配置的自定义 Webhook URL 和请求模板发送消息。")
    public String sendCustomWebhook(
            @ToolParam(name = "message", description = "通知消息内容") String message) {
        log.debug("工具调用: notify_custom_webhook");
        if (!ToolConfirmationManager.requestConfirmation("notify_custom_webhook",
                "通过自定义 Webhook 发送通知")) {
            return ToolResponse.error("notify_custom_webhook", "用户取消了操作");
        }

        NotificationConfig config = NotificationConfig.getInstance();
        if (!config.isCustomEnabled() || config.getCustomWebhook().isBlank()) {
            return ToolResponse.error("notify_custom_webhook", "自定义 Webhook 未启用或未配置 URL。");
        }

        try {
            String bodyTemplate = config.getCustomBodyTemplate();
            String body = bodyTemplate.replace("${message}", escapeJson(message));

            String response = sendHttpPost(config.getCustomWebhook(), body, config.getCustomContentType());
            return ToolResponse.success("notify_custom_webhook", "自定义 Webhook 发送成功，响应: " + truncate(response, 200));
        } catch (Exception e) {
            log.error("自定义 Webhook 发送失败", e);
            return ToolResponse.fromException("notify_custom_webhook", e);
        }
    }

    @Tool(name = "notify_list_channels", description = "列出所有已配置的通知渠道及其启用状态。")
    public String listChannels() {
        log.debug("工具调用: notify_list_channels");
        NotificationConfig config = NotificationConfig.getInstance();

        StringBuilder sb = new StringBuilder("通知渠道配置状态：\n\n");
        sb.append(String.format("1. 钉钉机器人: %s %s\n",
                config.isDingtalkEnabled() ? "已启用" : "未启用",
                config.getDingtalkWebhook().isBlank() ? "（未配置 Webhook）" : "（已配置）"));
        sb.append(String.format("2. 企业微信机器人: %s %s\n",
                config.isWechatEnabled() ? "已启用" : "未启用",
                config.getWechatWebhook().isBlank() ? "（未配置 Webhook）" : "（已配置）"));
        sb.append(String.format("3. 飞书机器人: %s %s\n",
                config.isFeishuEnabled() ? "已启用" : "未启用",
                config.getFeishuWebhook().isBlank() ? "（未配置 Webhook）" : "（已配置）"));
        sb.append(String.format("4. 邮件通知: %s %s\n",
                config.isEmailNotifyEnabled() ? "已启用" : "未启用",
                config.getEmailNotifyTo().isBlank() ? "（未配置收件人）" : "（收件人: " + config.getEmailNotifyTo() + "）"));
        sb.append(String.format("5. 自定义 Webhook: %s %s\n",
                config.isCustomEnabled() ? "已启用" : "未启用",
                config.getCustomWebhook().isBlank() ? "（未配置 URL）" : "（已配置）"));

        return ToolResponse.success("notify_list_channels", sb.toString());
    }

    // ==================== 程序化调用接口（非 @Tool，供任务系统等业务流程使用） ====================

    /**
     * 按指定渠道发送一条通知（非 @Tool，直接 Java 调用）
     *
     * <p>不同于 {@link #sendNotification(String, String)} 总是向所有渠道广播，
     * 本方法只路由到单个渠道。任务完成/自动暂停通知使用此接口。</p>
     *
     * @param channel 渠道 key，取值参见 {@link com.javaclaw.task.TaskNotificationChannel}
     * @param title   通知标题
     * @param message 通知正文
     * @return 简短的中文状态描述（"发送成功"/"发送失败: ..." 等）
     */
    public String sendByChannel(String channel, String title, String message) {
        if (channel == null || channel.isBlank() || "none".equalsIgnoreCase(channel)) {
            return "未选择通知渠道";
        }
        NotificationConfig config = NotificationConfig.getInstance();
        String key = channel.toLowerCase();
        return switch (key) {
            case "all" -> {
                if (!config.hasEnabledChannel()) {
                    yield "未启用任何通知渠道";
                }
                yield sendNotification(message, title);
            }
            case "dingtalk" -> config.isDingtalkEnabled()
                    ? sendDingtalkInternal(title, message) : "钉钉通知未启用";
            case "wechat" -> config.isWechatEnabled()
                    ? sendWechatInternal(title, message) : "企业微信通知未启用";
            case "feishu" -> config.isFeishuEnabled()
                    ? sendFeishuInternal(title, message) : "飞书通知未启用";
            case "email" -> config.isEmailNotifyEnabled()
                    ? sendEmailNotifyInternal(title, message) : "邮件通知未启用";
            case "custom" -> config.isCustomEnabled()
                    ? sendCustomWebhookInternal(title, message) : "自定义 Webhook 未启用";
            default -> "未知通知渠道: " + channel;
        };
    }

    // ==================== 内部发送方法（供 notify_send 调用） ====================

    private String sendDingtalkInternal(String title, String message) {
        try {
            NotificationConfig config = NotificationConfig.getInstance();
            String webhookUrl = buildDingtalkUrl(config);
            String body = String.format(
                    "{\"msgtype\":\"text\",\"text\":{\"content\":\"%s\"}}",
                    escapeJson(title + "\n" + message));
            String response = sendHttpPost(webhookUrl, body, "application/json");
            if (response.contains("\"errcode\":0") || response.contains("\"errmsg\":\"ok\"")) {
                return "发送成功";
            }
            return "发送失败: " + truncate(response, 100);
        } catch (Exception e) {
            return "发送失败: " + e.getMessage();
        }
    }

    private String sendWechatInternal(String title, String message) {
        try {
            NotificationConfig config = NotificationConfig.getInstance();
            String body = String.format(
                    "{\"msgtype\":\"text\",\"text\":{\"content\":\"%s\"}}",
                    escapeJson(title + "\n" + message));
            String response = sendHttpPost(config.getWechatWebhook(), body, "application/json");
            if (response.contains("\"errcode\":0") || response.contains("\"errmsg\":\"ok\"")) {
                return "发送成功";
            }
            return "发送失败: " + truncate(response, 100);
        } catch (Exception e) {
            return "发送失败: " + e.getMessage();
        }
    }

    private String sendFeishuInternal(String title, String message) {
        try {
            NotificationConfig config = NotificationConfig.getInstance();
            String body = String.format(
                    "{\"msg_type\":\"text\",\"content\":{\"text\":\"%s\"}}",
                    escapeJson(title + "\n" + message));
            String response = sendHttpPost(config.getFeishuWebhook(), body, "application/json");
            if (response.contains("\"StatusCode\":0") || response.contains("\"code\":0") || response.contains("\"StatusMessage\":\"success\"")) {
                return "发送成功";
            }
            return "发送失败: " + truncate(response, 100);
        } catch (Exception e) {
            return "发送失败: " + e.getMessage();
        }
    }

    private String sendEmailNotifyInternal(String title, String message) {
        try {
            NotificationConfig notifyConfig = NotificationConfig.getInstance();
            EmailConfig emailConfig = EmailConfig.getInstance();

            if (!emailConfig.isConfigured() || notifyConfig.getEmailNotifyTo().isBlank()) {
                return "发送失败: 邮件未配置";
            }

            Properties props = new Properties();
            props.put("mail.smtp.host", emailConfig.getSmtpHost());
            props.put("mail.smtp.port", String.valueOf(emailConfig.getSmtpPort()));
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.starttls.enable", String.valueOf(emailConfig.isUseStarttls()));
            props.put("mail.smtp.ssl.enable", String.valueOf(emailConfig.isUseSsl()));
            props.put("mail.smtp.connectiontimeout", "10000");
            props.put("mail.smtp.timeout", "15000");

            Session session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(emailConfig.getUsername(), emailConfig.getPassword());
                }
            });

            MimeMessage msg = new MimeMessage(session);
            String fromAddr = emailConfig.getFromAddress();
            if (fromAddr == null || fromAddr.isBlank() || !fromAddr.contains("@")) {
                fromAddr = emailConfig.getUsername();
            }
            msg.setFrom(new InternetAddress(fromAddr));
            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(notifyConfig.getEmailNotifyTo()));
            msg.setSubject(title, "UTF-8");
            msg.setText(message, "UTF-8");
            Transport.send(msg);
            return "发送成功";
        } catch (Exception e) {
            return "发送失败: " + e.getMessage();
        }
    }

    private String sendCustomWebhookInternal(String title, String message) {
        try {
            NotificationConfig config = NotificationConfig.getInstance();
            String bodyTemplate = config.getCustomBodyTemplate();
            String body = bodyTemplate.replace("${message}", escapeJson(title + "\n" + message));
            sendHttpPost(config.getCustomWebhook(), body, config.getCustomContentType());
            return "发送成功";
        } catch (Exception e) {
            return "发送失败: " + e.getMessage();
        }
    }

    // ==================== HTTP 和签名工具方法 ====================

    /**
     * 构建钉钉 Webhook URL（含签名）
     */
    private String buildDingtalkUrl(NotificationConfig config) throws Exception {
        String url = config.getDingtalkWebhook();
        String secret = config.getDingtalkSecret();
        if (secret != null && !secret.isBlank()) {
            long timestamp = System.currentTimeMillis();
            String stringToSign = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            String sign = URLEncoder.encode(Base64.getEncoder().encodeToString(signData), StandardCharsets.UTF_8);
            url += "&timestamp=" + timestamp + "&sign=" + sign;
        }
        return url;
    }

    /**
     * 生成飞书签名
     */
    private String generateFeishuSign(long timestamp, String secret) throws Exception {
        String stringToSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(stringToSign.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signData = mac.doFinal(new byte[]{});
        return Base64.getEncoder().encodeToString(signData);
    }

    /**
     * 发送 HTTP POST 请求
     */
    private String sendHttpPost(String url, String body, String contentType) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", contentType)
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        log.debug("Webhook 响应: status={}, body={}", response.statusCode(), truncate(response.body(), 200));

        if (response.statusCode() >= 400) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + truncate(response.body(), 200));
        }
        return response.body();
    }

    /**
     * JSON 字符串转义
     */
    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 截断字符串
     */
    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}
