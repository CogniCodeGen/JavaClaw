package com.javaclaw.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * 邮件配置管理器（持久化到全局 H2 数据库）
 *
 * <p>负责读取和保存邮件相关配置项，配置保存在全局 {@code javaclaw.mv.db}
 * 的 {@code app_properties} 表中，并按 {@code workspace_id} 隔离。
 * 采用单例模式，全局共享同一份配置。</p>
 *
 * @author JavaClaw
 */
public final class EmailConfig {

    private static final Logger log = LoggerFactory.getLogger(EmailConfig.class);

    private static final String CONFIG_NAMESPACE = "email";

    /** 单例实例 */
    private static EmailConfig INSTANCE;

    private final Properties properties;

    // ==================== 配置项 key ====================
    private static final String KEY_SMTP_HOST = "smtp.host";
    private static final String KEY_SMTP_PORT = "smtp.port";
    private static final String KEY_IMAP_HOST = "imap.host";
    private static final String KEY_IMAP_PORT = "imap.port";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_FROM_ADDRESS = "from.address";
    private static final String KEY_USE_STARTTLS = "use.starttls";
    private static final String KEY_USE_SSL = "use.ssl";

    private EmailConfig() {
        this.properties = new Properties();
        load();
    }

    public static synchronized EmailConfig getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new EmailConfig();
        }
        return INSTANCE;
    }

    /**
     * 重新加载配置（工作区切换时调用）
     */
    public void reload() {
        properties.clear();
        load();
        log.info("邮件配置已重新加载: {}", AppDatabase.databaseDisplayPath());
    }

    /**
     * 从 H2 加载配置。
     */
    private void load() {
        Properties loaded = SqlPropertyStore.load(CONFIG_NAMESPACE);
        properties.putAll(loaded);
        if (properties.isEmpty()) {
            log.info("邮件配置数据库为空，使用默认值: {}", AppDatabase.databaseDisplayPath());
            setDefaults();
        } else {
            log.info("邮件配置已从 H2 加载: {}", AppDatabase.databaseDisplayPath());
        }
    }

    /**
     * 设置默认值
     */
    private void setDefaults() {
        properties.setProperty(KEY_SMTP_HOST, "smtp.qq.com");
        properties.setProperty(KEY_SMTP_PORT, "465");
        properties.setProperty(KEY_IMAP_HOST, "imap.qq.com");
        properties.setProperty(KEY_IMAP_PORT, "993");
        properties.setProperty(KEY_USERNAME, "");
        properties.setProperty(KEY_PASSWORD, "");
        properties.setProperty(KEY_FROM_ADDRESS, "");
        properties.setProperty(KEY_USE_STARTTLS, "false");
        properties.setProperty(KEY_USE_SSL, "true");
    }

    /**
     * 保存配置到 H2
     */
    public void save() {
        if (SqlPropertyStore.save(CONFIG_NAMESPACE, properties)) {
            log.info("邮件配置已保存到 H2: {}", AppDatabase.databaseDisplayPath());
        }
    }

    /**
     * 判断邮件配置是否已完成（用户名和密码非空）
     */
    public boolean isConfigured() {
        return !getUsername().isBlank() && !getPassword().isBlank();
    }

    // ==================== Getter / Setter ====================

    public String getSmtpHost() {
        return properties.getProperty(KEY_SMTP_HOST, "smtp.qq.com");
    }

    public void setSmtpHost(String value) {
        properties.setProperty(KEY_SMTP_HOST, value);
    }

    public int getSmtpPort() {
        try {
            return Integer.parseInt(properties.getProperty(KEY_SMTP_PORT, "465"));
        } catch (NumberFormatException e) {
            return 465;
        }
    }

    public void setSmtpPort(int value) {
        properties.setProperty(KEY_SMTP_PORT, String.valueOf(value));
    }

    public String getImapHost() {
        return properties.getProperty(KEY_IMAP_HOST, "imap.qq.com");
    }

    public void setImapHost(String value) {
        properties.setProperty(KEY_IMAP_HOST, value);
    }

    public int getImapPort() {
        try {
            return Integer.parseInt(properties.getProperty(KEY_IMAP_PORT, "993"));
        } catch (NumberFormatException e) {
            return 993;
        }
    }

    public void setImapPort(int value) {
        properties.setProperty(KEY_IMAP_PORT, String.valueOf(value));
    }

    public String getUsername() {
        return properties.getProperty(KEY_USERNAME, "");
    }

    public void setUsername(String value) {
        properties.setProperty(KEY_USERNAME, value);
    }

    public String getPassword() {
        String raw = properties.getProperty(KEY_PASSWORD, "");
        return CredentialEncryptor.decrypt(raw);
    }

    public void setPassword(String value) {
        properties.setProperty(KEY_PASSWORD, CredentialEncryptor.encrypt(value));
    }

    public String getFromAddress() {
        return properties.getProperty(KEY_FROM_ADDRESS, "");
    }

    public void setFromAddress(String value) {
        properties.setProperty(KEY_FROM_ADDRESS, value);
    }

    public boolean isUseStarttls() {
        return Boolean.parseBoolean(properties.getProperty(KEY_USE_STARTTLS, "false"));
    }

    public void setUseStarttls(boolean value) {
        properties.setProperty(KEY_USE_STARTTLS, String.valueOf(value));
    }

    public boolean isUseSsl() {
        return Boolean.parseBoolean(properties.getProperty(KEY_USE_SSL, "true"));
    }

    public void setUseSsl(boolean value) {
        properties.setProperty(KEY_USE_SSL, String.valueOf(value));
    }

    /**
     * 获取配置文件路径（用于界面显示）
     */
    public String getConfigFilePath() {
        return AppDatabase.databaseDisplayPath();
    }
}
