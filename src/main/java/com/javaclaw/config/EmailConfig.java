package com.javaclaw.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 邮件配置管理器（持久化到本地文件）
 *
 * <p>负责读取和保存邮件相关配置项，配置文件保存在程序运行目录下的 {@code javaclaw-email.properties}。
 * 采用单例模式，全局共享同一份配置。</p>
 *
 * @author JavaClaw
 */
public final class EmailConfig {

    private static final Logger log = LoggerFactory.getLogger(EmailConfig.class);

    /** 配置文件名 */
    private static final String CONFIG_FILE_NAME = "javaclaw-email.properties";

    /** 单例实例 */
    private static EmailConfig INSTANCE;

    private Path configFilePath;
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
        resolveConfigPath();
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
        resolveConfigPath();
        load();
        log.info("邮件配置已重新加载: {}", configFilePath);
    }

    private void resolveConfigPath() {
        this.configFilePath = WorkspaceManager.getInstance()
                .getCurrentWorkspacePath().resolve(CONFIG_FILE_NAME);
    }

    /**
     * 从文件加载配置，文件不存在时使用默认值
     */
    private void load() {
        File file = configFilePath.toFile();
        if (file.exists()) {
            try (InputStream in = new FileInputStream(file)) {
                properties.load(new InputStreamReader(in, "UTF-8"));
                log.info("邮件配置已从文件加载: {}", configFilePath);
            } catch (IOException e) {
                log.warn("加载邮件配置文件失败，使用默认值: {}", e.getMessage());
                setDefaults();
            }
        } else {
            log.info("邮件配置文件不存在，使用默认值: {}", configFilePath);
            setDefaults();
            save();
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
     * 保存配置到文件
     */
    public void save() {
        try (OutputStream out = new FileOutputStream(configFilePath.toFile())) {
            properties.store(new OutputStreamWriter(out, "UTF-8"),
                    "JavaClaw 邮件配置 - 请勿手动修改密码字段");
            log.info("邮件配置已保存: {}", configFilePath);
        } catch (IOException e) {
            log.error("保存邮件配置文件失败", e);
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
        return configFilePath.toString();
    }
}
