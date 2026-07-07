package com.javaclaw.site;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.javaclaw.config.AppDatabase;
import com.javaclaw.config.CredentialEncryptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

/**
 * 站点凭据与会话管理（单例 / 工作区维度）
 *
 * <p>持久化到全局 H2 数据库的 {@code site_credentials} 与
 * {@code site_sessions} 表，并按 {@code workspace_id} 隔离；启动时只从 H2 读取。</p>
 *
 * <p>密码字段落盘时经 {@link CredentialEncryptor} 加密（ENC(...) 格式，与 API Key 一致），
 * 读取时解密。
 * 内存中的 {@link SiteCredential} 始终持有明文（供 Playwright 自动登录使用）。
 * 数据存储在 H2 中，请勿提交本地数据库文件。</p>
 *
 * @author JavaClaw
 */
public class SiteCredentialManager {

    private static final Logger log = LoggerFactory.getLogger(SiteCredentialManager.class);

    private static SiteCredentialManager INSTANCE;

    private final ObjectMapper objectMapper;
    private final Map<String, SiteCredential> credentials = new LinkedHashMap<>();

    private SiteCredentialManager() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        load();
    }

    public static synchronized SiteCredentialManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new SiteCredentialManager();
        }
        return INSTANCE;
    }

    public String getConfigFilePath() {
        return AppDatabase.databaseDisplayPath();
    }

    // ==================== 加载/保存 ====================

    public void load() {
        credentials.clear();
        String sql = """
                SELECT id, name, host_pattern, login_url, username, password_enc, notes,
                       created_at, last_used_at, has_session
                FROM site_credentials
                WHERE workspace_id = ?
                ORDER BY created_at, name
                """;
        try (Connection c = AppDatabase.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, AppDatabase.currentWorkspaceId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    SiteCredential cred = new SiteCredential();
                    cred.setId(rs.getString("id"));
                    cred.setName(rs.getString("name"));
                    cred.setHostPattern(rs.getString("host_pattern"));
                    cred.setLoginUrl(rs.getString("login_url"));
                    cred.setUsername(rs.getString("username"));
                    cred.setPassword(CredentialEncryptor.decrypt(rs.getString("password_enc")));
                    cred.setNotes(rs.getString("notes"));
                    cred.setCreatedAt(rs.getLong("created_at"));
                    cred.setLastUsedAt(rs.getLong("last_used_at"));
                    cred.setHasSession(sessionExists(c, cred.getId()));
                    credentials.put(cred.getId(), cred);
                }
            }
            log.info("已从 H2 加载 {} 条站点凭据", credentials.size());
        } catch (SQLException e) {
            log.warn("从 H2 加载站点凭据失败: {}", e.getMessage(), e);
        }

    }

    public void save() {
        String upsert = """
                MERGE INTO site_credentials(
                    workspace_id, id, name, host_pattern, login_url, username, password_enc, notes,
                    created_at, last_used_at, has_session, updated_at
                )
                KEY(workspace_id, id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """;
        try (Connection c = AppDatabase.getConnection();
             PreparedStatement ps = c.prepareStatement(upsert)) {
            c.setAutoCommit(false);
            deleteRemovedCredentials(c);
            String workspaceId = AppDatabase.currentWorkspaceId();
            for (SiteCredential cred : credentials.values()) {
                ps.setString(1, workspaceId);
                ps.setString(2, cred.getId());
                ps.setString(3, cred.getName());
                ps.setString(4, cred.getHostPattern());
                ps.setString(5, cred.getLoginUrl());
                ps.setString(6, cred.getUsername());
                ps.setString(7, CredentialEncryptor.encrypt(cred.getPassword()));
                ps.setString(8, cred.getNotes());
                ps.setLong(9, cred.getCreatedAt());
                ps.setLong(10, cred.getLastUsedAt());
                ps.setBoolean(11, cred.isHasSession());
                ps.addBatch();
            }
            ps.executeBatch();
            c.commit();
            log.info("已保存站点凭据到 H2: {}", AppDatabase.databaseDisplayPath());
        } catch (SQLException e) {
            log.error("保存站点凭据到 H2 失败", e);
        }
    }

    /** 工作区切换时调用 */
    public void reload() {
        load();
    }

    // ==================== CRUD ====================

    public List<SiteCredential> all() {
        return new ArrayList<>(credentials.values());
    }

    public SiteCredential get(String id) {
        return credentials.get(id);
    }

    /**
     * 添加或更新凭据。如果 id 为空会自动生成。
     */
    public SiteCredential put(SiteCredential cred) {
        if (cred.getId() == null || cred.getId().isBlank()) {
            cred.setId(UUID.randomUUID().toString());
        }
        if (cred.getCreatedAt() == 0) {
            cred.setCreatedAt(System.currentTimeMillis());
        }
        cred.setHasSession(readSession(cred.getId()) != null);
        credentials.put(cred.getId(), cred);
        save();
        return cred;
    }

    /**
     * 删除凭据，并连带删除其持久化的会话文件
     */
    public void remove(String id) {
        SiteCredential removed = credentials.remove(id);
        if (removed != null) {
            clearSession(id);
            save();
            log.info("已删除站点凭据: {} ({})", removed.getName(), id);
        }
    }

    // ==================== 匹配 ====================

    /**
     * 根据 URL 找到首条匹配的凭据。
     *
     * <p>匹配规则：解析 URL 的 host，按以下优先级查找：</p>
     * <ol>
     *   <li>精确匹配 hostPattern（不含通配符）</li>
     *   <li>{@code *.example.com} 形式：host 必须以 {@code example.com} 结尾且不等于 {@code example.com}</li>
     * </ol>
     *
     * @return 匹配的凭据；找不到返回 null
     */
    public SiteCredential findByUrl(String url) {
        if (url == null || url.isBlank()) return null;
        String host = extractHost(url);
        if (host == null) return null;
        host = host.toLowerCase(Locale.ROOT);

        // 1) 精确匹配优先
        for (SiteCredential c : credentials.values()) {
            String pat = normalizePattern(c.getHostPattern());
            if (pat == null || pat.startsWith("*.")) continue;
            if (pat.equals(host)) return c;
        }
        // 2) 通配符兜底（最长后缀优先）
        SiteCredential best = null;
        int bestLen = -1;
        for (SiteCredential c : credentials.values()) {
            String pat = normalizePattern(c.getHostPattern());
            if (pat == null || !pat.startsWith("*.")) continue;
            String suffix = pat.substring(2);
            if (host.endsWith("." + suffix) || host.equals(suffix)) {
                if (suffix.length() > bestLen) {
                    bestLen = suffix.length();
                    best = c;
                }
            }
        }
        return best;
    }

    private static String normalizePattern(String pattern) {
        if (pattern == null) return null;
        String p = pattern.trim().toLowerCase(Locale.ROOT);
        return p.isEmpty() ? null : p;
    }

    private static String extractHost(String url) {
        try {
            String u = url.trim();
            if (!u.contains("://")) u = "https://" + u;
            return URI.create(u).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 会话文件读写 ====================

    /**
     * 写入 Playwright storageState 文本。
     *
     * @param id              凭据 ID
     * @param storageStateJson Playwright {@code BrowserContext.storageState()} 返回的 JSON 文本
     */
    public void writeSession(String id, String storageStateJson) {
        if (id == null || storageStateJson == null) return;
        try (Connection conn = AppDatabase.getConnection()) {
            writeSessionToDb(conn, id, storageStateJson);
            SiteCredential cred = credentials.get(id);
            if (cred != null) {
                cred.setHasSession(true);
                cred.setLastUsedAt(System.currentTimeMillis());
            }
            save();
            log.info("已保存站点会话: {}", id);
        } catch (SQLException e) {
            log.error("保存站点会话失败: {}", id, e);
        }
    }

    /** 读取 storageState；不存在返回 null */
    public String readSession(String id) {
        String sql = "SELECT storage_state_json FROM site_sessions WHERE workspace_id = ? AND credential_id = ?";
        try (Connection c = AppDatabase.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, AppDatabase.currentWorkspaceId());
            ps.setString(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("storage_state_json") : null;
            }
        } catch (SQLException e) {
            log.warn("读取站点会话失败: {}", id, e);
            return null;
        }
    }

    /** 标记凭据被成功使用过（用于 UI 显示） */
    public void touchUsage(String id) {
        SiteCredential c = credentials.get(id);
        if (c != null) {
            c.setLastUsedAt(System.currentTimeMillis());
            save();
        }
    }

    /** 清除某条目的会话文件（凭据本身保留） */
    public void clearSession(String id) {
        String sql = "DELETE FROM site_sessions WHERE workspace_id = ? AND credential_id = ?";
        try (Connection conn = AppDatabase.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, AppDatabase.currentWorkspaceId());
            ps.setString(2, id);
            ps.executeUpdate();
            SiteCredential cred = credentials.get(id);
            if (cred != null) {
                cred.setHasSession(false);
                save();
            }
            log.info("已清除站点会话: {}", id);
        } catch (SQLException e) {
            log.warn("清除站点会话失败: {}", id, e);
        }
    }

    private void deleteRemovedCredentials(Connection c) throws SQLException {
        Set<String> existing = new HashSet<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM site_credentials WHERE workspace_id = ?")) {
            ps.setString(1, AppDatabase.currentWorkspaceId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) existing.add(rs.getString("id"));
            }
        }
        existing.removeAll(credentials.keySet());
        if (existing.isEmpty()) return;

        try (PreparedStatement sessionPs = c.prepareStatement(
                     "DELETE FROM site_sessions WHERE workspace_id = ? AND credential_id = ?");
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM site_credentials WHERE workspace_id = ? AND id = ?")) {
            String workspaceId = AppDatabase.currentWorkspaceId();
            for (String id : existing) {
                sessionPs.setString(1, workspaceId);
                sessionPs.setString(2, id);
                sessionPs.addBatch();

                ps.setString(1, workspaceId);
                ps.setString(2, id);
                ps.addBatch();
            }
            sessionPs.executeBatch();
            ps.executeBatch();
        }
    }

    private boolean sessionExists(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM site_sessions WHERE workspace_id = ? AND credential_id = ?")) {
            ps.setString(1, AppDatabase.currentWorkspaceId());
            ps.setString(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void writeSessionToDb(Connection c, String id, String storageStateJson) throws SQLException {
        String sql = """
                MERGE INTO site_sessions(workspace_id, credential_id, storage_state_json, updated_at)
                KEY(workspace_id, credential_id)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, AppDatabase.currentWorkspaceId());
            ps.setString(2, id);
            ps.setString(3, storageStateJson);
            ps.executeUpdate();
        }
    }
}
