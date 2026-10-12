package com.javaclaw.notification;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.agent.model.ToolResponse;
import com.javaclaw.framework.spi.ToolEffectCapture;
import com.javaclaw.config.EmailConfig;
import com.javaclaw.config.CredentialUsage;
import com.javaclaw.config.NotificationConfig;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.AddressException;
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
 * 通知工具类（基于 Spring AI {@code @Tool} 注解）
 *
 * <p>为通知智能体提供多渠道消息发送工具，支持钉钉机器人、企业微信机器人、
 * 飞书机器人、邮件通知和自定义 Webhook。所有方法返回 {@link ToolResponse} 格式化响应。</p>
 *
 * @author JavaClaw
 */
@com.javaclaw.framework.spi.ToolContract(group = "notification", permissions = {"tool.execute"}, idempotent = false)
public class NotificationTools {

    private static final Logger log = LoggerFactory.getLogger(NotificationTools.class);
    private static final JsonFactory WEBHOOK_JSON = new JsonFactory();

    private static final class DeliveryUncertainException extends Exception {
        DeliveryUncertainException(String message, Throwable cause) { super(message, cause); }
        DeliveryUncertainException(String message) { super(message); }
    }

    public enum DeliveryStatus { ACCEPTED, PARTIAL, UNCERTAIN, FAILED, SKIPPED }

    /** Transport acceptance is distinct from proof of recipient delivery. */
    public record DeliveryResult(DeliveryStatus status, String message,
            java.util.Map<String, DeliveryStatus> channels) {
        public DeliveryResult {
            status = java.util.Objects.requireNonNull(status, "status");
            message = java.util.Objects.requireNonNullElse(message, "");
            channels = java.util.Map.copyOf(java.util.Objects.requireNonNullElse(channels,
                    java.util.Map.of()));
        }
        public DeliveryResult(DeliveryStatus status, String message) {
            this(status, message, java.util.Map.of());
        }
        boolean success() { return status == DeliveryStatus.ACCEPTED; }
        static DeliveryResult sent() {
            return new DeliveryResult(DeliveryStatus.ACCEPTED, "传输端已接受，实际送达未验证");
        }
        static DeliveryResult failed(String message) {
            return new DeliveryResult(DeliveryStatus.FAILED, "发送失败: " + message);
        }
        static DeliveryResult uncertain(String message) {
            return new DeliveryResult(DeliveryStatus.UNCERTAIN,
                    "发送结果不确定，请先核对接收端，避免重复发送: " + message);
        }
        static DeliveryResult skipped(String message) {
            return new DeliveryResult(DeliveryStatus.SKIPPED, message);
        }
    }

    /**
     * 全局共享 HttpClient：自带连接池/selector 线程，无 close 只能靠 GC 回收——本工具随
     * ExpertManager 逐 run 重建（定时任务逐 tick），每实例一个客户端会持续搅拌套接字与线程；
     * 客户端无令牌语义，来源令牌只绑在工具包装层。
     */
    private static final HttpClient SHARED_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final HttpClient httpClient;

    /** 调用来源令牌（装配期绑定），高风险确认随调用传给 ToolConfirmationManager。 */
    private final ToolCallOrigin origin;
    private final NotificationConfig notificationConfig;
    private final EmailConfig emailConfig;

    public NotificationTools(ToolCallOrigin origin, NotificationConfig notificationConfig,
                             EmailConfig emailConfig) {
        this.origin = origin == null ? ToolCallOrigin.UNKNOWN : origin;
        this.notificationConfig = java.util.Objects.requireNonNull(
                notificationConfig, "notificationConfig");
        this.emailConfig = java.util.Objects.requireNonNull(emailConfig, "emailConfig");
        this.httpClient = SHARED_HTTP_CLIENT;
    }

    // ==================== 发送通知工具 ====================

    @Tool(name = "notify_send", description = "向所有已启用的通知渠道发送消息。" +
            "会自动检测已配置并启用的渠道（钉钉、企业微信、飞书、邮件、自定义Webhook），逐一发送。")
    public String sendNotification(
            @ToolParam( description = "要发送的通知消息内容") String message,
            @ToolParam( description = "通知标题，部分渠道会显示标题") String title) {
        log.debug("工具调用: notify_send(title={})", title);
        if (!ToolConfirmationManager.requestConfirmation(origin, "notify_send",
                "向所有已启用渠道发送通知: " + (title == null ? "" : title))) {
            return ToolResponse.error("notify_send", "用户取消了操作");
        }

        NotificationConfig config = notificationConfig;
        if (!config.hasEnabledChannel()) {
            return ToolResponse.error("notify_send", "没有已启用的通知渠道，请先在设置中配置并启用至少一个通知渠道。");
        }

        DeliveryResult result = broadcast(title, message, config);
        var payload = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .put("schemaVersion", 1).put("kind", "notification.delivery")
                .put("status", result.status().name());
        var channels = payload.putObject("channels");
        result.channels().forEach((name, status) -> channels.put(name, status.name()));
        ToolEffectCapture.noteData("notify_send", payload);
        return switch (result.status()) {
            case ACCEPTED -> ToolResponse.success("notify_send", result.message());
            case PARTIAL, UNCERTAIN -> ToolResponse.uncertain("notify_send", result.message());
            case FAILED, SKIPPED -> ToolResponse.error("notify_send", result.message());
        };
    }

    private DeliveryResult broadcast(String title, String message, NotificationConfig config) {
        StringBuilder results = new StringBuilder();
        int successCount = 0;
        int failCount = 0;
        int uncertainCount = 0;
        java.util.Map<String, DeliveryStatus> channels = new java.util.LinkedHashMap<>();

        if (config.isDingtalkEnabled()) {
            DeliveryResult result = sendDingtalkInternal(title, message);
            channels.put("dingtalk", result.status());
            results.append("钉钉: ").append(result.message()).append("\n");
            if (result.success()) successCount++;
            else if (result.status() == DeliveryStatus.UNCERTAIN) uncertainCount++;
            else failCount++;
        }

        if (config.isWechatEnabled()) {
            DeliveryResult result = sendWechatInternal(title, message);
            channels.put("wechat", result.status());
            results.append("企业微信: ").append(result.message()).append("\n");
            if (result.success()) successCount++;
            else if (result.status() == DeliveryStatus.UNCERTAIN) uncertainCount++;
            else failCount++;
        }

        if (config.isFeishuEnabled()) {
            DeliveryResult result = sendFeishuInternal(title, message);
            channels.put("feishu", result.status());
            results.append("飞书: ").append(result.message()).append("\n");
            if (result.success()) successCount++;
            else if (result.status() == DeliveryStatus.UNCERTAIN) uncertainCount++;
            else failCount++;
        }

        if (config.isEmailNotifyEnabled()) {
            DeliveryResult result = sendEmailNotifyInternal(title, message);
            channels.put("email", result.status());
            results.append("邮件: ").append(result.message()).append("\n");
            if (result.success()) successCount++;
            else if (result.status() == DeliveryStatus.UNCERTAIN) uncertainCount++;
            else failCount++;
        }

        if (config.isCustomEnabled()) {
            DeliveryResult result = sendCustomWebhookInternal(title, message);
            channels.put("custom", result.status());
            results.append("自定义Webhook: ").append(result.message()).append("\n");
            if (result.success()) successCount++;
            else if (result.status() == DeliveryStatus.UNCERTAIN) uncertainCount++;
            else failCount++;
        }

        String summary = String.format("发送请求处理完成 — 已接受: %d, 失败: %d, 结果不确定: %d\n\n%s",
                successCount, failCount, uncertainCount, results);
        DeliveryStatus status = uncertainCount > 0
                ? successCount == 0 && failCount == 0 ? DeliveryStatus.UNCERTAIN
                        : DeliveryStatus.PARTIAL
                : failCount == 0 ? DeliveryStatus.ACCEPTED
                        : successCount == 0 ? DeliveryStatus.FAILED : DeliveryStatus.PARTIAL;
        return new DeliveryResult(status, summary, channels);
    }

    @Tool(name = "notify_dingtalk", description = "通过钉钉机器人发送消息通知。" +
            "需要在设置中配置钉钉 Webhook 地址。支持文本消息和 Markdown 格式。")
    public String sendDingtalk(
            @ToolParam( description = "消息标题") String title,
            @ToolParam( description = "消息内容，支持 Markdown 格式") String message,
            @ToolParam( description = "是否使用 Markdown 格式，true 为 Markdown，false 为纯文本") boolean isMarkdown) {
        log.debug("工具调用: notify_dingtalk(title={})", title);
        if (!ToolConfirmationManager.requestConfirmation(origin, "notify_dingtalk",
                "向钉钉机器人发送通知: " + (title == null ? "" : title))) {
            return ToolResponse.error("notify_dingtalk", "用户取消了操作");
        }

        NotificationConfig config = notificationConfig;
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
            if (webhookAccepted("dingtalk", response)) {
                return ToolResponse.success("notify_dingtalk", "钉钉传输端已接受，实际送达未验证");
            }
            return ToolResponse.error("notify_dingtalk", "钉钉返回错误: " + response);
        } catch (DeliveryUncertainException e) {
            log.warn("钉钉消息发送结果不确定", e);
            return ToolResponse.uncertain("notify_dingtalk", e.getMessage());
        } catch (Exception e) {
            log.error("钉钉消息发送失败", e);
            return ToolResponse.fromException("notify_dingtalk", e);
        }
    }

    @Tool(name = "notify_wechat", description = "通过企业微信机器人发送消息通知。" +
            "需要在设置中配置企业微信 Webhook 地址。支持文本消息和 Markdown 格式。")
    public String sendWechat(
            @ToolParam( description = "消息内容，支持 Markdown 格式") String message,
            @ToolParam( description = "是否使用 Markdown 格式") boolean isMarkdown) {
        log.debug("工具调用: notify_wechat");
        if (!ToolConfirmationManager.requestConfirmation(origin, "notify_wechat",
                "向企业微信机器人发送通知")) {
            return ToolResponse.error("notify_wechat", "用户取消了操作");
        }

        NotificationConfig config = notificationConfig;
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
            if (webhookAccepted("wechat", response)) {
                return ToolResponse.success("notify_wechat", "企业微信传输端已接受，实际送达未验证");
            }
            return ToolResponse.error("notify_wechat", "企业微信返回错误: " + response);
        } catch (DeliveryUncertainException e) {
            log.warn("企业微信消息发送结果不确定", e);
            return ToolResponse.uncertain("notify_wechat", e.getMessage());
        } catch (Exception e) {
            log.error("企业微信消息发送失败", e);
            return ToolResponse.fromException("notify_wechat", e);
        }
    }

    @Tool(name = "notify_feishu", description = "通过飞书机器人发送消息通知。" +
            "需要在设置中配置飞书 Webhook 地址。支持富文本格式。")
    public String sendFeishu(
            @ToolParam( description = "消息标题") String title,
            @ToolParam( description = "消息内容") String message) {
        log.debug("工具调用: notify_feishu(title={})", title);
        if (!ToolConfirmationManager.requestConfirmation(origin, "notify_feishu",
                "向飞书机器人发送通知: " + (title == null ? "" : title))) {
            return ToolResponse.error("notify_feishu", "用户取消了操作");
        }

        NotificationConfig config = notificationConfig;
        if (!config.isFeishuEnabled() || config.getFeishuWebhook().isBlank()) {
            return ToolResponse.error("notify_feishu", "飞书通知未启用或未配置 Webhook 地址。");
        }

        try {
            String webhookUrl = config.getFeishuWebhook();
            String body;

            // 如果配置了签名密钥，添加签名
            String secret = CredentialUsage.requirePlaintext(
                    config.getFeishuSecret(), "飞书签名密钥");
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
            if (webhookAccepted("feishu", response)) {
                return ToolResponse.success("notify_feishu", "飞书传输端已接受，实际送达未验证");
            }
            return ToolResponse.error("notify_feishu", "飞书返回错误: " + response);
        } catch (DeliveryUncertainException e) {
            log.warn("飞书消息发送结果不确定", e);
            return ToolResponse.uncertain("notify_feishu", e.getMessage());
        } catch (Exception e) {
            log.error("飞书消息发送失败", e);
            return ToolResponse.fromException("notify_feishu", e);
        }
    }

    @Tool(name = "notify_email", description = "通过邮件发送通知。" +
            "使用已配置的邮件账号向指定收件人发送通知邮件。")
    public String sendEmailNotify(
            @ToolParam( description = "收件人邮箱地址，留空则使用默认通知收件人") String to,
            @ToolParam( description = "邮件主题") String subject,
            @ToolParam( description = "邮件正文") String body) {
        log.debug("工具调用: notify_email(to={}, subject={})", to, subject);
        if (!ToolConfirmationManager.requestConfirmation(origin, "notify_email",
                "发送通知邮件到 " + (to == null || to.isBlank() ? "默认收件人" : to)
                        + " (主题: " + subject + ")")) {
            return ToolResponse.error("notify_email", "用户取消了操作");
        }

        NotificationConfig notifyConfig = notificationConfig;

        if (!emailConfig.isConfigured()) {
            return ToolResponse.error("notify_email", "邮件账号未配置，请先在设置中配置邮件账号。");
        }

        // 确定收件人
        String recipient = (to != null && !to.isBlank()) ? to : notifyConfig.getEmailNotifyTo();
        if (recipient.isBlank()) {
            return ToolResponse.error("notify_email", "未指定收件人，且未配置默认通知收件人。");
        }

        try {
            String password = CredentialUsage.requirePlaintext(emailConfig.getPassword(), "邮件密码");
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
                    return new PasswordAuthentication(emailConfig.getUsername(), password);
                }
            });

            MimeMessage message = new MimeMessage(session);
            message.setFrom(senderAddress());
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient));
            message.setSubject(subject, "UTF-8");
            message.setText(body, "UTF-8");

            try {
                Transport.send(message);
            } catch (MessagingException uncertain) {
                return ToolResponse.uncertain("notify_email",
                        "SMTP 发送结果不确定，请先检查收件端再重试: " + uncertain.getMessage());
            }
            log.info("通知邮件已被 SMTP 传输端接受: to={}", recipient);
            return ToolResponse.success("notify_email",
                    "通知邮件已被 SMTP 传输端接受，实际送达未验证，收件人: " + recipient);
        } catch (Exception e) {
            log.error("通知邮件发送失败", e);
            return ToolResponse.fromException("notify_email", e);
        }
    }

    @Tool(name = "notify_custom_webhook", description = "通过自定义 Webhook 发送通知。" +
            "使用用户配置的自定义 Webhook URL 和请求模板发送消息。")
    public String sendCustomWebhook(
            @ToolParam( description = "通知消息内容") String message) {
        log.debug("工具调用: notify_custom_webhook");
        if (!ToolConfirmationManager.requestConfirmation(origin, "notify_custom_webhook",
                "通过自定义 Webhook 发送通知")) {
            return ToolResponse.error("notify_custom_webhook", "用户取消了操作");
        }

        NotificationConfig config = notificationConfig;
        if (!config.isCustomEnabled() || config.getCustomWebhook().isBlank()) {
            return ToolResponse.error("notify_custom_webhook", "自定义 Webhook 未启用或未配置 URL。");
        }

        try {
            String bodyTemplate = config.getCustomBodyTemplate();
            String body = bodyTemplate.replace("${message}", escapeJson(message));

            String response = sendHttpPost(config.getCustomWebhook(), body, config.getCustomContentType());
            return ToolResponse.success("notify_custom_webhook",
                    "自定义 Webhook 已接受请求，实际送达未验证，响应: " + truncate(response, 200));
        } catch (DeliveryUncertainException e) {
            log.warn("自定义 Webhook 发送结果不确定", e);
            return ToolResponse.uncertain("notify_custom_webhook", e.getMessage());
        } catch (Exception e) {
            log.error("自定义 Webhook 发送失败", e);
            return ToolResponse.fromException("notify_custom_webhook", e);
        }
    }

    @com.javaclaw.framework.spi.ToolContract(group = "notification", permissions = {"tool.read"}, idempotent = true)
    @Tool(name = "notify_list_channels", description = "列出所有已配置的通知渠道及其启用状态。")
    public String listChannels() {
        log.debug("工具调用: notify_list_channels");
        NotificationConfig config = notificationConfig;

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
     * @return 展示文案；状态消费方应调用 {@link #sendByChannelResult(String, String, String)}
     */
    public String sendByChannel(String channel, String title, String message) {
        return sendByChannelResult(channel, title, message).message();
    }

    /** Returns typed transport acceptance for schedulers and other programmatic callers. */
    public DeliveryResult sendByChannelResult(String channel, String title, String message) {
        if (channel == null || channel.isBlank() || "none".equalsIgnoreCase(channel)) {
            return DeliveryResult.skipped("未选择通知渠道");
        }
        NotificationConfig config = notificationConfig;
        String key = channel.toLowerCase(java.util.Locale.ROOT);
        return switch (key) {
            case "all" -> {
                if (!config.hasEnabledChannel()) {
                    yield DeliveryResult.skipped("未启用任何通知渠道");
                }
                yield broadcast(title, message, config);
            }
            case "dingtalk" -> config.isDingtalkEnabled()
                    ? sendDingtalkInternal(title, message) : DeliveryResult.skipped("钉钉通知未启用");
            case "wechat" -> config.isWechatEnabled()
                    ? sendWechatInternal(title, message) : DeliveryResult.skipped("企业微信通知未启用");
            case "feishu" -> config.isFeishuEnabled()
                    ? sendFeishuInternal(title, message) : DeliveryResult.skipped("飞书通知未启用");
            case "email" -> config.isEmailNotifyEnabled()
                    ? sendEmailNotifyInternal(title, message) : DeliveryResult.skipped("邮件通知未启用");
            case "custom" -> config.isCustomEnabled()
                    ? sendCustomWebhookInternal(title, message) : DeliveryResult.skipped("自定义 Webhook 未启用");
            default -> DeliveryResult.failed("未知通知渠道: " + channel);
        };
    }

    // ==================== 内部发送方法（供 notify_send 调用） ====================

    private DeliveryResult sendDingtalkInternal(String title, String message) {
        try {
            NotificationConfig config = notificationConfig;
            String webhookUrl = buildDingtalkUrl(config);
            String body = String.format(
                    "{\"msgtype\":\"text\",\"text\":{\"content\":\"%s\"}}",
                    escapeJson(title + "\n" + message));
            String response = sendHttpPost(webhookUrl, body, "application/json");
            if (webhookAccepted("dingtalk", response)) {
                return DeliveryResult.sent();
            }
            return DeliveryResult.failed(truncate(response, 100));
        } catch (DeliveryUncertainException e) {
            return DeliveryResult.uncertain(e.getMessage());
        } catch (Exception e) {
            return DeliveryResult.failed(e.getMessage());
        }
    }

    private DeliveryResult sendWechatInternal(String title, String message) {
        try {
            NotificationConfig config = notificationConfig;
            String body = String.format(
                    "{\"msgtype\":\"text\",\"text\":{\"content\":\"%s\"}}",
                    escapeJson(title + "\n" + message));
            String response = sendHttpPost(config.getWechatWebhook(), body, "application/json");
            if (webhookAccepted("wechat", response)) {
                return DeliveryResult.sent();
            }
            return DeliveryResult.failed(truncate(response, 100));
        } catch (DeliveryUncertainException e) {
            return DeliveryResult.uncertain(e.getMessage());
        } catch (Exception e) {
            return DeliveryResult.failed(e.getMessage());
        }
    }

    private DeliveryResult sendFeishuInternal(String title, String message) {
        try {
            NotificationConfig config = notificationConfig;
            String secret = CredentialUsage.requirePlaintext(
                    config.getFeishuSecret(), "飞书签名密钥");
            String content = escapeJson(title + "\n" + message);
            String body;
            if (secret != null && !secret.isBlank()) {
                long timestamp = System.currentTimeMillis() / 1000;
                body = String.format(
                        "{\"timestamp\":\"%d\",\"sign\":\"%s\",\"msg_type\":\"text\",\"content\":{\"text\":\"%s\"}}",
                        timestamp, escapeJson(generateFeishuSign(timestamp, secret)), content);
            } else {
                body = String.format(
                        "{\"msg_type\":\"text\",\"content\":{\"text\":\"%s\"}}",
                        content);
            }
            String response = sendHttpPost(config.getFeishuWebhook(), body, "application/json");
            if (webhookAccepted("feishu", response)) {
                return DeliveryResult.sent();
            }
            return DeliveryResult.failed(truncate(response, 100));
        } catch (DeliveryUncertainException e) {
            return DeliveryResult.uncertain(e.getMessage());
        } catch (Exception e) {
            return DeliveryResult.failed(e.getMessage());
        }
    }

    private DeliveryResult sendEmailNotifyInternal(String title, String message) {
        try {
            String password = CredentialUsage.requirePlaintext(emailConfig.getPassword(), "邮件密码");
            NotificationConfig notifyConfig = notificationConfig;

            if (!emailConfig.isConfigured() || notifyConfig.getEmailNotifyTo().isBlank()) {
                return DeliveryResult.failed("邮件未配置");
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
                    return new PasswordAuthentication(emailConfig.getUsername(), password);
                }
            });

            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(senderAddress());
            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(notifyConfig.getEmailNotifyTo()));
            msg.setSubject(title, "UTF-8");
            msg.setText(message, "UTF-8");
            try {
                Transport.send(msg);
            } catch (MessagingException uncertain) {
                return DeliveryResult.uncertain(uncertain.getMessage());
            }
            return DeliveryResult.sent();
        } catch (Exception e) {
            return DeliveryResult.failed(e.getMessage());
        }
    }

    private DeliveryResult sendCustomWebhookInternal(String title, String message) {
        try {
            NotificationConfig config = notificationConfig;
            String bodyTemplate = config.getCustomBodyTemplate();
            String body = bodyTemplate.replace("${message}", escapeJson(title + "\n" + message));
            sendHttpPost(config.getCustomWebhook(), body, config.getCustomContentType());
            return DeliveryResult.sent();
        } catch (DeliveryUncertainException e) {
            return DeliveryResult.uncertain(e.getMessage());
        } catch (Exception e) {
            return DeliveryResult.failed(e.getMessage());
        }
    }

    // ==================== HTTP 和签名工具方法 ====================

    private InternetAddress senderAddress() throws AddressException {
        String configured = emailConfig.getFromAddress();
        if (configured != null && !configured.isBlank()) {
            try {
                InternetAddress address = new InternetAddress(configured, true);
                address.validate();
                return address;
            } catch (AddressException invalidConfiguredAddress) {
                log.warn("通知发件地址无效，改用已配置邮箱账号");
            }
        }
        InternetAddress account = new InternetAddress(emailConfig.getUsername(), true);
        account.validate();
        return account;
    }

    /** Provider acknowledgements are protocol fields, never words in a response body. */
    static boolean webhookAccepted(String provider, String response) {
        if (response == null || response.isBlank()) return false;
        try (JsonParser parser = WEBHOOK_JSON.createParser(response)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return false;
            Long errorCode = null;
            Long providerCode = null;
            Long statusCode = null;
            boolean providerCodePresent = false;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) return false;
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if (value == null) return false;
                if ("errcode".equals(field) || "code".equals(field)
                        || "StatusCode".equals(field)) {
                    Long numeric = value == JsonToken.VALUE_NUMBER_INT
                            ? parser.getLongValue() : null;
                    if ("errcode".equals(field)) errorCode = numeric;
                    else if ("code".equals(field)) {
                        providerCode = numeric;
                        providerCodePresent = true;
                    } else statusCode = numeric;
                }
                parser.skipChildren();
            }
            if (parser.nextToken() != null) return false;
            return switch (provider) {
                case "dingtalk", "wechat" -> Long.valueOf(0).equals(errorCode);
                case "feishu" -> Long.valueOf(0).equals(
                        providerCodePresent ? providerCode : statusCode);
                default -> false;
            };
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 构建钉钉 Webhook URL（含签名）
     */
    private String buildDingtalkUrl(NotificationConfig config) throws Exception {
        String url = config.getDingtalkWebhook();
        String secret = CredentialUsage.requirePlaintext(
                config.getDingtalkSecret(), "钉钉签名密钥");
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

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new DeliveryUncertainException("HTTP 请求中断", interrupted);
        } catch (java.io.IOException uncertain) {
            throw new DeliveryUncertainException("HTTP 请求未取得确定响应", uncertain);
        }
        log.debug("Webhook 响应: status={}, body={}", response.statusCode(), truncate(response.body(), 200));

        if (response.statusCode() >= 500) {
            throw new DeliveryUncertainException("HTTP " + response.statusCode()
                    + "，服务端可能已接收请求");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
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
