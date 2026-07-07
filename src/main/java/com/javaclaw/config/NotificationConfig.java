package com.javaclaw.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * 通知渠道配置管理器（持久化到全局 H2 数据库）
 *
 * <p>管理多种通知渠道的配置：钉钉机器人、企业微信机器人、飞书机器人、邮件通知、自定义 Webhook。
 * 采用单例模式，配置保存在全局 {@code javaclaw.mv.db}
 * 的 {@code app_properties} 表中，并按 {@code workspace_id} 隔离。</p>
 *
 * @author JavaClaw
 */
public final class NotificationConfig {

    private static final Logger log = LoggerFactory.getLogger(NotificationConfig.class);

    private static final String CONFIG_NAMESPACE = "notification";
    private static NotificationConfig INSTANCE;

    private final Properties properties;

    // ==================== 配置项 key ====================

    // 钉钉机器人
    private static final String KEY_DINGTALK_ENABLED = "dingtalk.enabled";
    private static final String KEY_DINGTALK_WEBHOOK = "dingtalk.webhook.url";
    private static final String KEY_DINGTALK_SECRET = "dingtalk.secret";

    // 企业微信机器人
    private static final String KEY_WECHAT_ENABLED = "wechat.enabled";
    private static final String KEY_WECHAT_WEBHOOK = "wechat.webhook.url";

    // 飞书机器人
    private static final String KEY_FEISHU_ENABLED = "feishu.enabled";
    private static final String KEY_FEISHU_WEBHOOK = "feishu.webhook.url";
    private static final String KEY_FEISHU_SECRET = "feishu.secret";

    // 邮件通知
    private static final String KEY_EMAIL_NOTIFY_ENABLED = "email.notify.enabled";
    private static final String KEY_EMAIL_NOTIFY_TO = "email.notify.to";

    // 自定义 Webhook
    private static final String KEY_CUSTOM_ENABLED = "custom.enabled";
    private static final String KEY_CUSTOM_WEBHOOK = "custom.webhook.url";
    private static final String KEY_CUSTOM_METHOD = "custom.method";
    private static final String KEY_CUSTOM_CONTENT_TYPE = "custom.content.type";
    private static final String KEY_CUSTOM_BODY_TEMPLATE = "custom.body.template";

    // ==================== 构造与单例 ====================

    private NotificationConfig() {
        this.properties = new Properties();
        load();
    }

    public static synchronized NotificationConfig getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new NotificationConfig();
        }
        return INSTANCE;
    }

    /**
     * 重新加载配置（工作区切换时调用）
     */
    public void reload() {
        properties.clear();
        load();
        log.info("通知配置已重新加载: {}", AppDatabase.databaseDisplayPath());
    }

    // ==================== H2 读写 ====================

    private void load() {
        Properties loaded = SqlPropertyStore.load(CONFIG_NAMESPACE);
        properties.putAll(loaded);
        if (properties.isEmpty()) {
            log.info("通知配置数据库为空，使用默认值: {}", AppDatabase.databaseDisplayPath());
            setDefaults();
        } else {
            log.info("通知配置已从 H2 加载: {}", AppDatabase.databaseDisplayPath());
        }
    }

    private void setDefaults() {
        properties.setProperty(KEY_DINGTALK_ENABLED, "false");
        properties.setProperty(KEY_DINGTALK_WEBHOOK, "");
        properties.setProperty(KEY_DINGTALK_SECRET, "");
        properties.setProperty(KEY_WECHAT_ENABLED, "false");
        properties.setProperty(KEY_WECHAT_WEBHOOK, "");
        properties.setProperty(KEY_FEISHU_ENABLED, "false");
        properties.setProperty(KEY_FEISHU_WEBHOOK, "");
        properties.setProperty(KEY_FEISHU_SECRET, "");
        properties.setProperty(KEY_EMAIL_NOTIFY_ENABLED, "false");
        properties.setProperty(KEY_EMAIL_NOTIFY_TO, "");
        properties.setProperty(KEY_CUSTOM_ENABLED, "false");
        properties.setProperty(KEY_CUSTOM_WEBHOOK, "");
        properties.setProperty(KEY_CUSTOM_METHOD, "POST");
        properties.setProperty(KEY_CUSTOM_CONTENT_TYPE, "application/json");
        properties.setProperty(KEY_CUSTOM_BODY_TEMPLATE, "{\"content\": \"${message}\"}");
    }

    public void save() {
        if (SqlPropertyStore.save(CONFIG_NAMESPACE, properties)) {
            log.info("通知配置已保存到 H2: {}", AppDatabase.databaseDisplayPath());
        }
    }

    // ==================== 钉钉配置 ====================

    public boolean isDingtalkEnabled() {
        return Boolean.parseBoolean(properties.getProperty(KEY_DINGTALK_ENABLED, "false"));
    }

    public void setDingtalkEnabled(boolean value) {
        properties.setProperty(KEY_DINGTALK_ENABLED, String.valueOf(value));
    }

    public String getDingtalkWebhook() {
        return properties.getProperty(KEY_DINGTALK_WEBHOOK, "");
    }

    public void setDingtalkWebhook(String value) {
        properties.setProperty(KEY_DINGTALK_WEBHOOK, value);
    }

    public String getDingtalkSecret() {
        String raw = properties.getProperty(KEY_DINGTALK_SECRET, "");
        return CredentialEncryptor.decrypt(raw);
    }

    public void setDingtalkSecret(String value) {
        properties.setProperty(KEY_DINGTALK_SECRET, CredentialEncryptor.encrypt(value));
    }

    // ==================== 企业微信配置 ====================

    public boolean isWechatEnabled() {
        return Boolean.parseBoolean(properties.getProperty(KEY_WECHAT_ENABLED, "false"));
    }

    public void setWechatEnabled(boolean value) {
        properties.setProperty(KEY_WECHAT_ENABLED, String.valueOf(value));
    }

    public String getWechatWebhook() {
        return properties.getProperty(KEY_WECHAT_WEBHOOK, "");
    }

    public void setWechatWebhook(String value) {
        properties.setProperty(KEY_WECHAT_WEBHOOK, value);
    }

    // ==================== 飞书配置 ====================

    public boolean isFeishuEnabled() {
        return Boolean.parseBoolean(properties.getProperty(KEY_FEISHU_ENABLED, "false"));
    }

    public void setFeishuEnabled(boolean value) {
        properties.setProperty(KEY_FEISHU_ENABLED, String.valueOf(value));
    }

    public String getFeishuWebhook() {
        return properties.getProperty(KEY_FEISHU_WEBHOOK, "");
    }

    public void setFeishuWebhook(String value) {
        properties.setProperty(KEY_FEISHU_WEBHOOK, value);
    }

    public String getFeishuSecret() {
        String raw = properties.getProperty(KEY_FEISHU_SECRET, "");
        return CredentialEncryptor.decrypt(raw);
    }

    public void setFeishuSecret(String value) {
        properties.setProperty(KEY_FEISHU_SECRET, CredentialEncryptor.encrypt(value));
    }

    // ==================== 邮件通知配置 ====================

    public boolean isEmailNotifyEnabled() {
        return Boolean.parseBoolean(properties.getProperty(KEY_EMAIL_NOTIFY_ENABLED, "false"));
    }

    public void setEmailNotifyEnabled(boolean value) {
        properties.setProperty(KEY_EMAIL_NOTIFY_ENABLED, String.valueOf(value));
    }

    public String getEmailNotifyTo() {
        return properties.getProperty(KEY_EMAIL_NOTIFY_TO, "");
    }

    public void setEmailNotifyTo(String value) {
        properties.setProperty(KEY_EMAIL_NOTIFY_TO, value);
    }

    // ==================== 自定义 Webhook 配置 ====================

    public boolean isCustomEnabled() {
        return Boolean.parseBoolean(properties.getProperty(KEY_CUSTOM_ENABLED, "false"));
    }

    public void setCustomEnabled(boolean value) {
        properties.setProperty(KEY_CUSTOM_ENABLED, String.valueOf(value));
    }

    public String getCustomWebhook() {
        return properties.getProperty(KEY_CUSTOM_WEBHOOK, "");
    }

    public void setCustomWebhook(String value) {
        properties.setProperty(KEY_CUSTOM_WEBHOOK, value);
    }

    public String getCustomMethod() {
        return properties.getProperty(KEY_CUSTOM_METHOD, "POST");
    }

    public void setCustomMethod(String value) {
        properties.setProperty(KEY_CUSTOM_METHOD, value);
    }

    public String getCustomContentType() {
        return properties.getProperty(KEY_CUSTOM_CONTENT_TYPE, "application/json");
    }

    public void setCustomContentType(String value) {
        properties.setProperty(KEY_CUSTOM_CONTENT_TYPE, value);
    }

    public String getCustomBodyTemplate() {
        return properties.getProperty(KEY_CUSTOM_BODY_TEMPLATE, "{\"content\": \"${message}\"}");
    }

    public void setCustomBodyTemplate(String value) {
        properties.setProperty(KEY_CUSTOM_BODY_TEMPLATE, value);
    }

    // ==================== 辅助方法 ====================

    public String getConfigFilePath() {
        return AppDatabase.databaseDisplayPath();
    }

    /**
     * 获取所有已启用渠道的名称列表（用于显示）
     */
    public String getEnabledChannelsSummary() {
        StringBuilder sb = new StringBuilder();
        if (isDingtalkEnabled()) sb.append("钉钉、");
        if (isWechatEnabled()) sb.append("企业微信、");
        if (isFeishuEnabled()) sb.append("飞书、");
        if (isEmailNotifyEnabled()) sb.append("邮件、");
        if (isCustomEnabled()) sb.append("自定义Webhook、");
        if (sb.isEmpty()) return "无";
        return sb.substring(0, sb.length() - 1);
    }

    /**
     * 是否至少有一个渠道已启用
     */
    public boolean hasEnabledChannel() {
        return isDingtalkEnabled() || isWechatEnabled() || isFeishuEnabled()
                || isEmailNotifyEnabled() || isCustomEnabled();
    }
}
