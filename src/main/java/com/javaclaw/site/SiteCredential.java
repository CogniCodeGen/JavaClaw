package com.javaclaw.site;

/**
 * 站点凭据条目
 *
 * <p>登记一个网站的访问凭据：浏览器智能体导航到匹配的 URL 时，
 * 系统会优先尝试恢复已保存的会话（cookies + storageState）；
 * 若无可用会话，再用此处的用户名/密码自动登录并保存会话。</p>
 *
 * <p>字段约定：</p>
 * <ul>
 *   <li>{@link #hostPattern} — 主机匹配规则。支持精确匹配（{@code github.com}）
 *       和前缀通配（{@code *.github.com}，匹配任意子域）。</li>
 *   <li>{@link #loginUrl} — 登录页 URL（可选）。当用户主动调用「立即登录」操作时优先打开它。</li>
 *   <li>{@link #password} — 与 API Key 等敏感配置一致，**明文** 存储于工作区 JSON。
 *       绝不进入 LLM 上下文：浏览器工具内部通过 {@code SiteCredentialManager} 直接读取。</li>
 *   <li>{@link #hasSession} — 是否已经为该条目持久化过 storageState；
 *       仅用于 UI 显示徽章，真实文件存在性以 {@code SiteCredentialManager.sessionFile(id)} 判断。</li>
 * </ul>
 *
 * @author JavaClaw
 */
public class SiteCredential {

    /** 唯一 ID（UUID 字符串），同时用作会话文件名 */
    private String id;

    /** 用户友好的展示名 */
    private String name;

    /** 主机匹配模式：{@code github.com} 或 {@code *.github.com} */
    private String hostPattern;

    /** 登录页 URL（可选） */
    private String loginUrl;

    /** 用户名 / 邮箱 */
    private String username;

    /** 密码（明文，不进入 LLM 上下文） */
    private String password;

    /** 备注（可选） */
    private String notes;

    /** 创建时间戳（毫秒） */
    private long createdAt;

    /** 最近一次成功使用的时间戳；恢复会话或登录成功后更新 */
    private long lastUsedAt;

    /** 是否已持久化过会话（持久化文件存在与否的快照） */
    private boolean hasSession;

    public SiteCredential() {}

    public SiteCredential(String id, String name, String hostPattern,
                          String loginUrl, String username, String password,
                          String notes) {
        this.id = id;
        this.name = name;
        this.hostPattern = hostPattern;
        this.loginUrl = loginUrl;
        this.username = username;
        this.password = password;
        this.notes = notes;
        this.createdAt = System.currentTimeMillis();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getHostPattern() { return hostPattern; }
    public void setHostPattern(String hostPattern) { this.hostPattern = hostPattern; }

    public String getLoginUrl() { return loginUrl; }
    public void setLoginUrl(String loginUrl) { this.loginUrl = loginUrl; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(long lastUsedAt) { this.lastUsedAt = lastUsedAt; }

    public boolean isHasSession() { return hasSession; }
    public void setHasSession(boolean hasSession) { this.hasSession = hasSession; }
}
