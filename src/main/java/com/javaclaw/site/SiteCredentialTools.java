package com.javaclaw.site;

import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.agent.model.ToolResponse;
import com.javaclaw.api.interaction.SecretRequest;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.util.SensitiveDataRedactor;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.IDN;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Arrays;

/**
 * 站点凭据配置工具 —— 让编排器通过对话直接登记、查询和删除“站点管理”条目。
 *
 * <p>浏览器当前已经登录时仍应优先调用 {@code site_save_session} 保存会话；本工具用于
 * 显式创建传统账号/密码条目或预先登记站点元数据。所有返回内容均不包含密码，密码只会
 * 交给 {@link SiteCredentialManager} 加密落盘。</p>
 */
public final class SiteCredentialTools {

    private static final Logger log = LoggerFactory.getLogger(SiteCredentialTools.class);

    interface Store {
        List<SiteCredential> all();
        SiteCredential get(String id);
        SiteCredential save(SiteCredential credential);
        boolean delete(String id);
    }

    private final ToolCallOrigin origin;
    private final Store store;

    public SiteCredentialTools(ToolCallOrigin origin) {
        this(origin, new Store() {
            private final SiteCredentialManager manager = SiteCredentialManager.getInstance();

            @Override public List<SiteCredential> all() { return manager.all(); }
            @Override public SiteCredential get(String id) { return manager.get(id); }
            @Override public SiteCredential save(SiteCredential credential) {
                return manager.putChecked(credential);
            }
            @Override public boolean delete(String id) { return manager.removeChecked(id); }
        });
    }

    SiteCredentialTools(ToolCallOrigin origin, Store store) {
        this.origin = origin == null ? ToolCallOrigin.UNKNOWN : origin;
        this.store = Objects.requireNonNull(store, "store");
    }

    @Tool(name = "site_credential_list",
            description = "列出“站点管理”中已登记的凭据条目。只返回名称、匹配域名、登录页、脱敏用户名和会话状态，绝不返回密码。")
    public String listCredentials() {
        List<SiteCredential> credentials = store.all();
        if (credentials.isEmpty()) {
            return ToolResponse.success("site_credential_list", "当前工作区尚未登记站点凭据。");
        }
        StringBuilder out = new StringBuilder("共 ").append(credentials.size()).append(" 个站点凭据：\n");
        for (SiteCredential credential : credentials) {
            out.append("· [").append(credential.getId()).append("] ")
                    .append(safe(credential.getName())).append(" — ")
                    .append(safe(credential.getHostPattern()));
            if (credential.getLoginUrl() != null && !credential.getLoginUrl().isBlank()) {
                out.append("，登录页 ").append(credential.getLoginUrl());
            }
            if (credential.getUsername() != null && !credential.getUsername().isBlank()) {
                out.append("，账号 ").append(maskUsername(credential.getUsername()));
            }
            out.append(credential.isHasSession() ? "，已保存会话" : "，无会话").append('\n');
        }
        return ToolResponse.success("site_credential_list", out.toString().trim());
    }

    @Tool(name = "site_credential_save",
            description = "在“站点管理”中直接创建或更新一个凭据条目，并确认写入数据库。"
                    + "site_url 可传完整 URL、域名或 *.example.com。更新时传 credential_id；不传则创建。"
                    + "本工具不接收明文密码；需要密码时先创建条目，再调用 site_credential_set_password_secure 弹出本地安全输入框。"
                    + "若当前浏览器已经登录，优先委派 web_expert 调 site_save_session 保存会话。"
                    + "严禁把账号密码改写到技能、AGENTS.md、MEMORY.md 或普通文件中。")
    public String saveCredential(
            @ToolParam(name = "name", description = "站点展示名，如 GitHub、内部 OA") String name,
            @ToolParam(name = "site_url", description = "站点 URL、域名或通配域名，如 https://example.com 或 *.example.com") String siteUrl,
            @ToolParam(name = "credential_id", description = "更新已有条目时传其 ID；创建时留空", required = false) String credentialId,
            @ToolParam(name = "login_url", description = "登录页 URL；省略时更新保留原值，空字符串表示清除", required = false) String loginUrl,
            @ToolParam(name = "username", description = "用户名/邮箱；省略时更新保留原值，空字符串表示清除", required = false) String username,
            @ToolParam(name = "password", description = "已停用的兼容参数；不得传入明文密码", required = false) String password,
            @ToolParam(name = "notes", description = "非敏感备注；不得包含密码、令牌或验证码", required = false) String notes) {
        String displayName = strip(name);
        if (displayName.isEmpty()) {
            return ToolResponse.error("site_credential_save", "name 不能为空。");
        }
        if (displayName.length() > 512) {
            return ToolResponse.error("site_credential_save", "name 不能超过 512 个字符。");
        }
        if (password != null && !password.isBlank()) {
            return ToolResponse.error("site_credential_save",
                    "已拒绝通过聊天或工具参数接收明文密码；请先保存站点条目，再调用安全输入工具 site_credential_set_password_secure。");
        }
        if (notes != null && SensitiveDataRedactor.containsLikelyCredential(notes)) {
            return ToolResponse.error("site_credential_save",
                    "备注中检测到疑似凭据，已拒绝保存；备注只能填写非敏感说明。");
        }

        String hostPattern;
        try {
            hostPattern = normalizeHostPattern(siteUrl);
        } catch (IllegalArgumentException e) {
            return ToolResponse.error("site_credential_save", e.getMessage());
        }

        SiteCredential existing = null;
        String requestedId = strip(credentialId);
        if (!requestedId.isEmpty()) {
            existing = store.get(requestedId);
            if (existing == null) {
                return ToolResponse.error("site_credential_save", "未找到凭据 ID: " + requestedId);
            }
        }

        String normalizedLoginUrl;
        try {
            normalizedLoginUrl = optionalHttpUrl(loginUrl, existing == null ? null : existing.getLoginUrl());
        } catch (IllegalArgumentException e) {
            return ToolResponse.error("site_credential_save", e.getMessage());
        }

        SiteCredential candidate = copyOf(existing);
        candidate.setName(displayName);
        candidate.setHostPattern(hostPattern);
        candidate.setLoginUrl(normalizedLoginUrl);
        if (username != null) candidate.setUsername(username.strip());
        if (notes != null) candidate.setNotes(notes.strip());

        boolean containsPassword = candidate.getPassword() != null && !candidate.getPassword().isBlank();
        String action = existing == null ? "创建" : "更新";
        String confirmation = action + "站点凭据「" + displayName + "」（" + hostPattern + "）"
                + (containsPassword ? "，密码将加密保存" : "，不保存密码");
        if (!ToolConfirmationManager.requestConfirmation(
                origin, "site_credential_save", confirmation)) {
            return ToolResponse.error("site_credential_save", "用户取消了站点凭据保存。");
        }

        try {
            SiteCredential saved = store.save(candidate);
            log.info("对话已{}站点凭据: {} ({})", action, saved.getName(), saved.getId());
            return ToolResponse.success("site_credential_save",
                    "已确认写入站点管理：id=" + saved.getId()
                            + "，名称=" + saved.getName()
                            + "，主机匹配=" + saved.getHostPattern()
                            + (containsPassword ? "，密码已加密保存。" : "，未保存密码。"));
        } catch (RuntimeException e) {
            log.error("对话保存站点凭据失败: {}", displayName, e);
            return ToolResponse.error("site_credential_save", e.getMessage());
        }
    }

    @Tool(name = "site_credential_set_password_secure",
            description = "为已存在的站点条目设置密码。系统会弹出本地安全密码框；密码不会进入模型消息、工具参数、日志或回复。")
    public String setPasswordSecure(
            @ToolParam(name = "credential_id", description = "site_credential_list 返回的凭据 ID") String credentialId) {
        String id = strip(credentialId);
        SiteCredential existing = store.get(id);
        if (existing == null) {
            return ToolResponse.error("site_credential_set_password_secure", "未找到凭据 ID: " + id);
        }
        UserInteractionPort port = ToolConfirmationManager.getPort();
        if (port == null || !port.isAvailable()) {
            return ToolResponse.error("site_credential_set_password_secure",
                    "安全输入界面未就绪，未修改密码。");
        }

        char[] secret = port.requestSecret(new SecretRequest(
                "设置站点密码",
                "请为站点「" + safe(existing.getName()) + "」输入密码。内容仅交给本地加密存储，不会发送给模型。",
                120,
                4096));
        if (secret == null || secret.length == 0) {
            if (secret != null) Arrays.fill(secret, '\0');
            return ToolResponse.error("site_credential_set_password_secure", "用户取消了安全输入或密码为空。");
        }

        try {
            SiteCredential candidate = copyOf(existing);
            candidate.setPassword(new String(secret));
            SiteCredential saved = store.save(candidate);
            return ToolResponse.success("site_credential_set_password_secure",
                    "已确认加密保存站点「" + safe(saved.getName()) + "」的密码；未在回复中显示密码。");
        } catch (RuntimeException e) {
            log.error("安全保存站点密码失败: {}", id, e);
            return ToolResponse.error("site_credential_set_password_secure",
                    "密码未保存，请检查数据库状态后重试。");
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    @Tool(name = "site_credential_delete",
            description = "从“站点管理”删除一个凭据条目，并连带删除其保存的浏览器会话和账号绑定。不可恢复，需二次确认。")
    public String deleteCredential(
            @ToolParam(name = "credential_id", description = "site_credential_list 返回的凭据 ID") String credentialId) {
        String id = strip(credentialId);
        SiteCredential existing = store.get(id);
        if (existing == null) {
            return ToolResponse.error("site_credential_delete", "未找到凭据 ID: " + id);
        }
        if (!ToolConfirmationManager.requestConfirmation(origin, "site_credential_delete",
                "删除站点凭据「" + existing.getName() + "」及其保存的浏览器会话")) {
            return ToolResponse.error("site_credential_delete", "用户取消了删除。");
        }
        try {
            return store.delete(id)
                    ? ToolResponse.success("site_credential_delete", "已删除站点凭据「" + existing.getName() + "」。")
                    : ToolResponse.error("site_credential_delete", "凭据已不存在: " + id);
        } catch (RuntimeException e) {
            log.error("删除站点凭据失败: {}", id, e);
            return ToolResponse.error("site_credential_delete", e.getMessage());
        }
    }

    static String normalizeHostPattern(String raw) {
        String value = strip(raw).toLowerCase(Locale.ROOT);
        if (value.isEmpty()) throw new IllegalArgumentException("site_url 不能为空。");
        boolean wildcard = value.startsWith("*.");
        String candidate = wildcard ? value.substring(2) : value;
        if (!candidate.contains("://")) candidate = "https://" + candidate;
        URI uri;
        try {
            uri = URI.create(candidate);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("site_url 格式不正确。", e);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("site_url 必须包含有效域名。");
        }
        String ascii = IDN.toASCII(host).toLowerCase(Locale.ROOT);
        return wildcard ? "*." + ascii : ascii;
    }

    private static String optionalHttpUrl(String raw, String existing) {
        if (raw == null) return existing;
        String value = raw.strip();
        if (value.isEmpty()) return null;
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null
                    || !("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException("login_url 必须是有效的 http:// 或 https:// URL。");
            }
            return uri.toString();
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("login_url")) throw e;
            throw new IllegalArgumentException("login_url 格式不正确。", e);
        }
    }

    private static SiteCredential copyOf(SiteCredential source) {
        SiteCredential copy = new SiteCredential();
        if (source == null) return copy;
        copy.setId(source.getId());
        copy.setName(source.getName());
        copy.setHostPattern(source.getHostPattern());
        copy.setLoginUrl(source.getLoginUrl());
        copy.setUsername(source.getUsername());
        copy.setPassword(source.getPassword());
        copy.setNotes(source.getNotes());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setLastUsedAt(source.getLastUsedAt());
        copy.setHasSession(source.isHasSession());
        return copy;
    }

    private static String maskUsername(String username) {
        String value = strip(username);
        if (value.isEmpty()) return "(未设置)";
        int at = value.indexOf('@');
        if (at > 0) {
            String local = value.substring(0, at);
            return maskSimple(local) + value.substring(at);
        }
        return maskSimple(value);
    }

    private static String maskSimple(String value) {
        if (value.length() <= 2) return value.charAt(0) + "***";
        return value.substring(0, 2) + "***" + value.charAt(value.length() - 1);
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String strip(String value) {
        return value == null ? "" : value.strip();
    }
}
