package com.javaclaw.site;

import com.javaclaw.config.AppDatabase;
import com.javaclaw.config.AppDatabaseAccess;
import com.javaclaw.config.CredentialEncryptor;
import com.javaclaw.config.DatabaseAccess;
import com.javaclaw.util.SensitiveDataRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

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
    private static final String NEW_ACCOUNT_BINDING = "__new_account__";
    private static final int MAX_SESSION_STATE_CHARS = 16 * 1024 * 1024;

    private static SiteCredentialManager INSTANCE;

    private final DatabaseAccess databaseAccess;
    private final Supplier<String> workspaceIdSupplier;
    private final UnaryOperator<String> encryptor;
    private final UnaryOperator<String> decryptor;
    private final Map<String, SiteCredential> credentials = new LinkedHashMap<>();
    /** 内存凭据快照所属工作区，一次操作中不再重读可变的全局当前值。 */
    private String loadedWorkspaceId;

    private SiteCredentialManager() {
        this(new AppDatabaseAccess(), AppDatabase::currentWorkspaceId,
                CredentialEncryptor::encrypt, CredentialEncryptor::decrypt);
    }

    SiteCredentialManager(DatabaseAccess databaseAccess,
                          Supplier<String> workspaceIdSupplier,
                          UnaryOperator<String> encryptor,
                          UnaryOperator<String> decryptor) {
        this.databaseAccess = Objects.requireNonNull(databaseAccess, "databaseAccess");
        this.workspaceIdSupplier = Objects.requireNonNull(workspaceIdSupplier, "workspaceIdSupplier");
        this.encryptor = Objects.requireNonNull(encryptor, "encryptor");
        this.decryptor = Objects.requireNonNull(decryptor, "decryptor");
        load();
    }

    public static synchronized SiteCredentialManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new SiteCredentialManager();
        }
        return INSTANCE;
    }

    public String getConfigFilePath() {
        return databaseAccess.description();
    }

    // ==================== 加载/保存 ====================

    public synchronized void load() {
        String workspaceId = workspaceIdSupplier.get();
        credentials.clear();
        loadedWorkspaceId = null;
        Map<String, SiteCredential> loaded = new LinkedHashMap<>();
        String sql = """
                SELECT id, name, host_pattern, login_url, username, password_enc, notes,
                       created_at, last_used_at, has_session
                FROM site_credentials
                WHERE workspace_id = ?
                ORDER BY created_at, name
                """;
        try (Connection c = databaseAccess.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, workspaceId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    SiteCredential cred = new SiteCredential();
                    cred.setId(rs.getString("id"));
                    cred.setName(rs.getString("name"));
                    cred.setHostPattern(rs.getString("host_pattern"));
                    cred.setLoginUrl(rs.getString("login_url"));
                    cred.setUsername(rs.getString("username"));
                    cred.setPassword(decryptRequired(rs.getString("password_enc")));
                    cred.setNotes(rs.getString("notes"));
                    cred.setCreatedAt(rs.getLong("created_at"));
                    cred.setLastUsedAt(rs.getLong("last_used_at"));
                    cred.setHasSession(sessionExists(c, workspaceId, cred.getId()));
                    loaded.put(cred.getId(), cred);
                }
            }
            migrateSensitiveRows(c, workspaceId, loaded.values());
            credentials.clear();
            credentials.putAll(loaded);
            loadedWorkspaceId = workspaceId;
            log.info("已从 H2 加载 {} 条站点凭据", credentials.size());
        } catch (SQLException | RuntimeException e) {
            credentials.clear();
            loadedWorkspaceId = null;
            log.error("从 H2 加载站点凭据失败，已保持空快照", e);
            throw new IllegalStateException("站点凭据无法从当前工作区数据库加载", e);
        }

    }

    public synchronized void save() {
        String workspaceId = requireLoadedWorkspace();
        String upsert = """
                MERGE INTO site_credentials(
                    workspace_id, id, name, host_pattern, login_url, username, password_enc, notes,
                    created_at, last_used_at, has_session, updated_at
                )
                KEY(workspace_id, id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """;
        try (Connection c = databaseAccess.open();
            PreparedStatement ps = c.prepareStatement(upsert)) {
            c.setAutoCommit(false);
            deleteRemovedCredentials(c, workspaceId);
            for (SiteCredential cred : credentials.values()) {
                validateNonSecretFields(cred);
                bindCredential(ps, workspaceId, cred);
                ps.addBatch();
            }
            ps.executeBatch();
            c.commit();
            log.info("已保存站点凭据到 H2: {}", databaseAccess.description());
        } catch (SQLException | RuntimeException e) {
            log.error("保存站点凭据到 H2 失败", e);
            throw new IllegalStateException("站点凭据未能确认写入数据库", e);
        }
    }

    /** 工作区切换时调用 */
    public void reload() {
        load();
    }

    // ==================== CRUD ====================

    public synchronized List<SiteCredential> all() {
        return new ArrayList<>(credentials.values());
    }

    public synchronized SiteCredential get(String id) {
        return credentials.get(id);
    }

    /**
     * 添加或更新凭据。如果 id 为空会自动生成。
     */
    public synchronized SiteCredential put(SiteCredential cred) {
        return putChecked(cred);
    }

    /**
     * 添加或更新凭据，并且仅在 H2 已确认写入后才更新内存快照。
     *
     * <p>对话工具必须使用本入口：旧 {@link #put(SiteCredential)} 为兼容 UI 保留，
     * 本方法把单条 MERGE 放在事务内，失败时抛出异常，避免智能体向用户误报成功。</p>
     *
     * @throws IllegalStateException 当前工作区未加载或数据库写入失败
     */
    public synchronized SiteCredential putChecked(SiteCredential cred) {
        Objects.requireNonNull(cred, "cred");
        String workspaceId = requireLoadedWorkspace();
        validateNonSecretFields(cred);
        SiteCredential candidate = copyCredential(cred);
        if (candidate.getId() == null || candidate.getId().isBlank()) {
            candidate.setId(UUID.randomUUID().toString());
        }
        if (candidate.getCreatedAt() == 0) candidate.setCreatedAt(System.currentTimeMillis());

        String upsert = """
                MERGE INTO site_credentials(
                    workspace_id, id, name, host_pattern, login_url, username, password_enc, notes,
                    created_at, last_used_at, has_session, updated_at
                )
                KEY(workspace_id, id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """;
        try (Connection c = databaseAccess.open();
             PreparedStatement ps = c.prepareStatement(upsert)) {
            c.setAutoCommit(false);
            candidate.setHasSession(sessionExists(c, workspaceId, candidate.getId()));
            bindCredential(ps, workspaceId, candidate);
            ps.executeUpdate();
            c.commit();
            credentials.put(candidate.getId(), candidate);
            log.info("站点凭据已确认写入 H2: {} ({})", candidate.getName(), candidate.getId());
            return candidate;
        } catch (SQLException | RuntimeException e) {
            throw new IllegalStateException("站点凭据写入 H2 失败", e);
        }
    }

    /**
     * 把站点条目、加密浏览器会话和当前浏览器作用域绑定放在同一事务中提交。
     * 新条目只在事务成功后发布到内存；失败不会留下孤立凭据、孤立会话或假成功状态。
     */
    public synchronized SiteCredential saveSessionChecked(
            SiteCredential credential, String storageStateJson, String scopeId, String url) {
        Objects.requireNonNull(credential, "credential");
        if (storageStateJson == null || storageStateJson.isBlank()) {
            throw new IllegalArgumentException("浏览器会话内容不能为空");
        }
        if (storageStateJson.length() > MAX_SESSION_STATE_CHARS) {
            throw new IllegalArgumentException("浏览器会话内容过大，已拒绝保存");
        }
        validateNonSecretFields(credential);
        String workspaceId = requireLoadedWorkspace();
        SiteCredential candidate = copyCredential(credential);
        if (candidate.getId() == null || candidate.getId().isBlank()) {
            candidate.setId(UUID.randomUUID().toString());
        }
        long now = System.currentTimeMillis();
        if (candidate.getCreatedAt() == 0) candidate.setCreatedAt(now);
        candidate.setLastUsedAt(now);
        candidate.setHasSession(true);
        String host = extractHost(url);
        boolean bindScope = scopeId != null && !scopeId.isBlank() && host != null;

        String upsert = """
                MERGE INTO site_credentials(
                    workspace_id, id, name, host_pattern, login_url, username, password_enc, notes,
                    created_at, last_used_at, has_session, updated_at
                )
                KEY(workspace_id, id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """;
        try (Connection c = databaseAccess.open()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement credentialPs = c.prepareStatement(upsert)) {
                    bindCredential(credentialPs, workspaceId, candidate);
                    credentialPs.executeUpdate();
                }
                writeSessionToDb(c, workspaceId, candidate.getId(), storageStateJson);
                if (bindScope) {
                    writeAccountBindingToDb(c, workspaceId, scopeId,
                            host.toLowerCase(Locale.ROOT), candidate.getId());
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(c);
                throw e;
            }
            credentials.put(candidate.getId(), candidate);
            log.info("站点、加密会话与账号绑定已事务提交: {} ({})",
                    candidate.getName(), candidate.getId());
            return candidate;
        } catch (SQLException | RuntimeException e) {
            throw new IllegalStateException("站点会话事务提交失败", e);
        }
    }

    /**
     * 删除凭据，并连带删除其持久化的会话文件
     */
    public synchronized void remove(String id) {
        removeChecked(id);
    }

    /**
     * 事务式删除单条凭据、站点会话及账号绑定；返回 false 表示目标不存在。
     * 对话工具使用本入口，确保只有数据库提交成功后才报告删除成功。
     */
    public synchronized boolean removeChecked(String id) {
        SiteCredential existing = credentials.get(id);
        if (existing == null) return false;
        String workspaceId = requireLoadedWorkspace();
        try (Connection c = databaseAccess.open()) {
            c.setAutoCommit(false);
            try (PreparedStatement binding = c.prepareStatement(
                         "DELETE FROM site_account_bindings WHERE workspace_id = ? AND credential_id = ?");
                 PreparedStatement session = c.prepareStatement(
                         "DELETE FROM site_sessions WHERE workspace_id = ? AND credential_id = ?");
                 PreparedStatement credential = c.prepareStatement(
                         "DELETE FROM site_credentials WHERE workspace_id = ? AND id = ?")) {
                binding.setString(1, workspaceId);
                binding.setString(2, id);
                binding.executeUpdate();
                session.setString(1, workspaceId);
                session.setString(2, id);
                session.executeUpdate();
                credential.setString(1, workspaceId);
                credential.setString(2, id);
                if (credential.executeUpdate() != 1) {
                    c.rollback();
                    return false;
                }
            }
            c.commit();
            credentials.remove(id);
            log.info("站点凭据已确认从 H2 删除: {} ({})", existing.getName(), id);
            return true;
        } catch (SQLException e) {
            throw new IllegalStateException("删除站点凭据失败", e);
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
    public synchronized SiteCredential findByUrl(String url) {
        List<SiteCredential> matches = findAllByUrl(url);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    /**
     * 返回 URL 对应的全部账号配置。
     *
     * <p>存在精确 host 配置时只返回精确项；否则返回最长通配后缀对应的全部配置。
     * 这样同一站点可保存多个账号，同时不会让较宽的通配规则抢占更具体的配置。</p>
     */
    public synchronized List<SiteCredential> findAllByUrl(String url) {
        if (url == null || url.isBlank()) return List.of();
        String host = extractHost(url);
        if (host == null) return List.of();
        host = host.toLowerCase(Locale.ROOT);

        List<SiteCredential> exact = new ArrayList<>();
        for (SiteCredential c : credentials.values()) {
            String pat = normalizePattern(c.getHostPattern());
            if (pat == null || pat.startsWith("*.")) continue;
            if (pat.equals(host)) exact.add(c);
        }
        if (!exact.isEmpty()) return List.copyOf(exact);

        List<SiteCredential> best = new ArrayList<>();
        int bestLen = -1;
        for (SiteCredential c : credentials.values()) {
            String pat = normalizePattern(c.getHostPattern());
            if (pat == null || !pat.startsWith("*.")) continue;
            String suffix = pat.substring(2);
            if (wildcardMatches(host, suffix)) {
                if (suffix.length() > bestLen) {
                    bestLen = suffix.length();
                    best.clear();
                    best.add(c);
                } else if (suffix.length() == bestLen) {
                    best.add(c);
                }
            }
        }
        return List.copyOf(best);
    }

    // ==================== 会话 → 站点账号绑定 ====================

    /**
     * 读取当前浏览器作用域为该 URL 选定的账号 ID。
     *
     * <p>返回空表示尚未选择；返回 {@link #NEW_ACCOUNT_BINDING} 表示明确要求使用空白新账号。</p>
     */
    public synchronized String readAccountBinding(String scopeId, String url) {
        String workspaceId = loadedWorkspaceId;
        String host = extractHost(url);
        if (workspaceId == null || scopeId == null || scopeId.isBlank() || host == null) return null;
        String sql = """
                SELECT credential_id
                FROM site_account_bindings
                WHERE workspace_id = ? AND scope_id = ? AND site_host = ?
                """;
        try (Connection c = databaseAccess.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, workspaceId);
            ps.setString(2, scopeId);
            ps.setString(3, host.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("credential_id") : null;
            }
        } catch (SQLException e) {
            log.warn("读取会话站点账号绑定失败 scope={} host={}: {}",
                    scopeId, host, e.getMessage());
            return null;
        }
    }

    /** 返回已绑定且仍匹配当前 URL 的账号；“新账号”绑定或无绑定均返回 null。 */
    public synchronized SiteCredential findBoundByUrl(String scopeId, String url) {
        String id = readAccountBinding(scopeId, url);
        if (id == null || NEW_ACCOUNT_BINDING.equals(id)) return null;
        SiteCredential credential = credentials.get(id);
        if (credential == null || !findAllByUrl(url).stream()
                .anyMatch(match -> id.equals(match.getId()))) {
            clearAccountBinding(scopeId, url);
            return null;
        }
        return credential;
    }

    public synchronized boolean isNewAccountBound(String scopeId, String url) {
        return NEW_ACCOUNT_BINDING.equals(readAccountBinding(scopeId, url));
    }

    /** 把账号配置绑定到浏览器作用域；账号必须属于当前工作区且匹配目标 URL。 */
    public synchronized boolean bindAccount(String scopeId, String url, String credentialId) {
        if (credentialId == null || !credentials.containsKey(credentialId)) return false;
        boolean matches = findAllByUrl(url).stream()
                .anyMatch(candidate -> credentialId.equals(candidate.getId()));
        return matches && writeAccountBinding(scopeId, url, credentialId);
    }

    /** 明确要求该作用域在此站点使用空白身份，不恢复任何已保存账号。 */
    public synchronized boolean bindNewAccount(String scopeId, String url) {
        return writeAccountBinding(scopeId, url, NEW_ACCOUNT_BINDING);
    }

    private boolean writeAccountBinding(String scopeId, String url, String credentialId) {
        String workspaceId;
        try {
            workspaceId = requireLoadedWorkspace();
        } catch (IllegalStateException e) {
            return false;
        }
        String host = extractHost(url);
        if (scopeId == null || scopeId.isBlank()
                || host == null || credentialId == null) {
            return false;
        }
        try (Connection c = databaseAccess.open()) {
            writeAccountBindingToDb(c, workspaceId, scopeId,
                    host.toLowerCase(Locale.ROOT), credentialId);
            log.info("浏览器作用域已绑定站点账号: scope={} host={} credential={}",
                    scopeId, host, credentialId);
            return true;
        } catch (SQLException e) {
            log.warn("保存会话站点账号绑定失败 scope={} host={}: {}",
                    scopeId, host, e.getMessage());
            return false;
        }
    }

    public synchronized void clearAccountBinding(String scopeId, String url) {
        String workspaceId = loadedWorkspaceId;
        String host = extractHost(url);
        if (workspaceId == null || scopeId == null || scopeId.isBlank() || host == null) return;
        try (Connection c = databaseAccess.open();
             PreparedStatement ps = c.prepareStatement("""
                     DELETE FROM site_account_bindings
                     WHERE workspace_id = ? AND scope_id = ? AND site_host = ?
                     """)) {
            ps.setString(1, workspaceId);
            ps.setString(2, scopeId);
            ps.setString(3, host.toLowerCase(Locale.ROOT));
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("清除会话站点账号绑定失败: {}", e.getMessage());
        }
    }

    /** 删除聊天会话时一并删除其账号选择，不留下不可达绑定。 */
    public synchronized void clearScopeBindings(String scopeId) {
        String workspaceId = loadedWorkspaceId;
        if (workspaceId == null || scopeId == null || scopeId.isBlank()) return;
        try (Connection c = databaseAccess.open();
             PreparedStatement ps = c.prepareStatement("""
                     DELETE FROM site_account_bindings
                     WHERE workspace_id = ? AND scope_id = ?
                     """)) {
            ps.setString(1, workspaceId);
            ps.setString(2, scopeId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("清除浏览器作用域账号绑定失败 {}: {}", scopeId, e.getMessage());
        }
    }

    private void clearBindingsForCredential(String credentialId) {
        String workspaceId = loadedWorkspaceId;
        if (workspaceId == null || credentialId == null) return;
        try (Connection c = databaseAccess.open();
             PreparedStatement ps = c.prepareStatement("""
                     DELETE FROM site_account_bindings
                     WHERE workspace_id = ? AND credential_id = ?
                     """)) {
            ps.setString(1, workspaceId);
            ps.setString(2, credentialId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("清除站点账号关联绑定失败 {}: {}", credentialId, e.getMessage());
        }
    }

    /** {@code *.example.com} 只匹配真正的子域，不把子域凭据泄露给根域。 */
    static boolean wildcardMatches(String host, String suffix) {
        return host != null && suffix != null && host.endsWith("." + suffix);
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
    public synchronized void writeSession(String id, String storageStateJson) {
        tryWriteSession(id, storageStateJson);
    }

    /**
     * 写入 Playwright storageState，并把持久化结果返回给需要向用户准确反馈的交互流程。
     */
    public synchronized boolean tryWriteSession(String id, String storageStateJson) {
        if (id == null || storageStateJson == null) return false;
        SiteCredential credential = credentials.get(id);
        if (credential == null) {
            log.warn("忽略不属于当前凭据快照的会话写入: {}", id);
            return false;
        }
        try {
            saveSessionChecked(credential, storageStateJson, null, null);
            log.info("已保存站点会话: {}", id);
            return true;
        } catch (RuntimeException e) {
            log.error("保存站点会话失败: {}", id, e);
            return false;
        }
    }

    /** 读取 storageState；不存在返回 null */
    public synchronized String readSession(String id) {
        String workspaceId = loadedWorkspaceId;
        if (workspaceId == null) return null;
        String sql = "SELECT storage_state_json FROM site_sessions WHERE workspace_id = ? AND credential_id = ?";
        try (Connection c = databaseAccess.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, workspaceId);
            ps.setString(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? decryptRequired(rs.getString("storage_state_json")) : null;
            }
        } catch (SQLException e) {
            log.warn("读取站点会话失败: {}", id, e);
            return null;
        }
    }

    /** 标记凭据被成功使用过（用于 UI 显示） */
    public synchronized void touchUsage(String id) {
        SiteCredential c = credentials.get(id);
        if (c != null) {
            SiteCredential candidate = copyCredential(c);
            candidate.setLastUsedAt(System.currentTimeMillis());
            putChecked(candidate);
        }
    }

    /** 清除某条目的会话文件（凭据本身保留） */
    public synchronized void clearSession(String id) {
        SiteCredential existing = credentials.get(id);
        if (existing == null) return;
        String workspaceId = requireLoadedWorkspace();
        try (Connection conn = databaseAccess.open()) {
            conn.setAutoCommit(false);
            try (PreparedStatement session = conn.prepareStatement(
                         "DELETE FROM site_sessions WHERE workspace_id = ? AND credential_id = ?");
                 PreparedStatement credential = conn.prepareStatement("""
                         UPDATE site_credentials
                         SET has_session = FALSE, updated_at = CURRENT_TIMESTAMP
                         WHERE workspace_id = ? AND id = ?
                         """)) {
                session.setString(1, workspaceId);
                session.setString(2, id);
                session.executeUpdate();
                credential.setString(1, workspaceId);
                credential.setString(2, id);
                if (credential.executeUpdate() != 1) {
                    conn.rollback();
                    throw new IllegalStateException("待清除会话的站点凭据不存在");
                }
            }
            conn.commit();
            SiteCredential candidate = copyCredential(existing);
            candidate.setHasSession(false);
            credentials.put(id, candidate);
            log.info("已清除站点会话: {}", id);
        } catch (SQLException | RuntimeException e) {
            throw new IllegalStateException("清除站点会话失败", e);
        }
    }

    private void deleteRemovedCredentials(Connection c, String workspaceId) throws SQLException {
        Set<String> existing = new HashSet<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM site_credentials WHERE workspace_id = ?")) {
            ps.setString(1, workspaceId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) existing.add(rs.getString("id"));
            }
        }
        existing.removeAll(credentials.keySet());
        if (existing.isEmpty()) return;

        try (PreparedStatement sessionPs = c.prepareStatement(
                     "DELETE FROM site_sessions WHERE workspace_id = ? AND credential_id = ?");
             PreparedStatement bindingPs = c.prepareStatement(
                     "DELETE FROM site_account_bindings WHERE workspace_id = ? AND credential_id = ?");
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM site_credentials WHERE workspace_id = ? AND id = ?")) {
            for (String id : existing) {
                sessionPs.setString(1, workspaceId);
                sessionPs.setString(2, id);
                sessionPs.addBatch();

                bindingPs.setString(1, workspaceId);
                bindingPs.setString(2, id);
                bindingPs.addBatch();

                ps.setString(1, workspaceId);
                ps.setString(2, id);
                ps.addBatch();
            }
            sessionPs.executeBatch();
            bindingPs.executeBatch();
            ps.executeBatch();
        }
    }

    private boolean sessionExists(Connection c, String workspaceId, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM site_sessions WHERE workspace_id = ? AND credential_id = ?")) {
            ps.setString(1, workspaceId);
            ps.setString(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void writeSessionToDb(Connection c, String workspaceId, String id, String storageStateJson)
            throws SQLException {
        String sql = """
                MERGE INTO site_sessions(workspace_id, credential_id, storage_state_json, updated_at)
                KEY(workspace_id, credential_id)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, workspaceId);
            ps.setString(2, id);
            ps.setString(3, encryptRequired(storageStateJson));
            ps.executeUpdate();
        }
    }

    private void writeAccountBindingToDb(Connection c, String workspaceId, String scopeId,
                                         String siteHost, String credentialId) throws SQLException {
        String sql = """
                MERGE INTO site_account_bindings(
                    workspace_id, scope_id, site_host, credential_id, updated_at)
                KEY(workspace_id, scope_id, site_host)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, workspaceId);
            ps.setString(2, scopeId);
            ps.setString(3, siteHost);
            ps.setString(4, credentialId);
            ps.executeUpdate();
        }
    }

    private void bindCredential(PreparedStatement ps, String workspaceId, SiteCredential cred)
            throws SQLException {
        ps.setString(1, workspaceId);
        ps.setString(2, cred.getId());
        ps.setString(3, cred.getName());
        ps.setString(4, cred.getHostPattern());
        ps.setString(5, cred.getLoginUrl());
        ps.setString(6, cred.getUsername());
        ps.setString(7, encryptRequired(cred.getPassword()));
        ps.setString(8, cred.getNotes());
        ps.setLong(9, cred.getCreatedAt());
        ps.setLong(10, cred.getLastUsedAt());
        ps.setBoolean(11, cred.isHasSession());
    }

    /**
     * 把旧版本遗留的明文密码和浏览器 storageState 原地迁移为密文。
     * 所有改写在同一事务提交；任何一步失败都回滚并拒绝发布内存快照。
     */
    private void migrateSensitiveRows(Connection c, String workspaceId,
                                      Collection<SiteCredential> loadedCredentials)
            throws SQLException {
        for (SiteCredential credential : loadedCredentials) {
            validateNonSecretFields(credential);
        }

        Map<String, String> passwordUpdates = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, password_enc FROM site_credentials WHERE workspace_id = ?")) {
            ps.setString(1, workspaceId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String stored = rs.getString("password_enc");
                    if (stored != null && !stored.isBlank()
                            && !CredentialEncryptor.isEncrypted(stored)) {
                        passwordUpdates.put(rs.getString("id"), encryptRequired(stored));
                    }
                }
            }
        }

        Map<String, String> sessionUpdates = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT credential_id, storage_state_json FROM site_sessions WHERE workspace_id = ?")) {
            ps.setString(1, workspaceId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String stored = rs.getString("storage_state_json");
                    if (stored != null && !stored.isBlank()
                            && !CredentialEncryptor.isEncrypted(stored)) {
                        sessionUpdates.put(rs.getString("credential_id"), encryptRequired(stored));
                    }
                }
            }
        }

        if (passwordUpdates.isEmpty() && sessionUpdates.isEmpty()) return;

        boolean originalAutoCommit = c.getAutoCommit();
        c.setAutoCommit(false);
        try (PreparedStatement passwordPs = c.prepareStatement("""
                     UPDATE site_credentials SET password_enc = ?, updated_at = CURRENT_TIMESTAMP
                     WHERE workspace_id = ? AND id = ?
                     """);
             PreparedStatement sessionPs = c.prepareStatement("""
                     UPDATE site_sessions SET storage_state_json = ?, updated_at = CURRENT_TIMESTAMP
                     WHERE workspace_id = ? AND credential_id = ?
                     """)) {
            for (Map.Entry<String, String> entry : passwordUpdates.entrySet()) {
                passwordPs.setString(1, entry.getValue());
                passwordPs.setString(2, workspaceId);
                passwordPs.setString(3, entry.getKey());
                passwordPs.addBatch();
            }
            for (Map.Entry<String, String> entry : sessionUpdates.entrySet()) {
                sessionPs.setString(1, entry.getValue());
                sessionPs.setString(2, workspaceId);
                sessionPs.setString(3, entry.getKey());
                sessionPs.addBatch();
            }
            passwordPs.executeBatch();
            sessionPs.executeBatch();
            c.commit();
            log.info("已迁移站点敏感字段到加密存储: password={} session={}",
                    passwordUpdates.size(), sessionUpdates.size());
        } catch (SQLException | RuntimeException e) {
            rollbackQuietly(c);
            throw e;
        } finally {
            c.setAutoCommit(originalAutoCommit);
        }
    }

    private String requireLoadedWorkspace() {
        String loaded = loadedWorkspaceId;
        String current = workspaceIdSupplier.get();
        if (loaded == null || loaded.isBlank() || current == null || !loaded.equals(current)) {
            throw new IllegalStateException("站点凭据快照不属于当前工作区，请先重新加载");
        }
        return loaded;
    }

    private String encryptRequired(String plainText) {
        if (plainText == null || plainText.isBlank()) return plainText;
        if (CredentialEncryptor.isEncrypted(plainText)) {
            throw new IllegalStateException("拒绝把未验证密文作为站点明文凭据保存");
        }
        String encrypted = encryptor.apply(plainText);
        if (encrypted == null || encrypted.equals(plainText)
                || !CredentialEncryptor.isEncrypted(encrypted)) {
            throw new IllegalStateException("站点敏感字段加密失败，已拒绝写入");
        }
        return encrypted;
    }

    private String decryptRequired(String stored) {
        if (stored == null || stored.isBlank() || !CredentialEncryptor.isEncrypted(stored)) {
            return stored;
        }
        String decrypted = decryptor.apply(stored);
        if (decrypted == null || CredentialEncryptor.isEncrypted(decrypted)) {
            throw new IllegalStateException("站点敏感字段无法解密，已拒绝使用");
        }
        return decrypted;
    }

    private static void validateNonSecretFields(SiteCredential credential) {
        String[] fields = {
                credential.getName(), credential.getHostPattern(), credential.getLoginUrl(),
                credential.getUsername(), credential.getNotes()
        };
        for (String field : fields) {
            if (SensitiveDataRedactor.containsLikelyCredential(field)) {
                throw new IllegalArgumentException("站点非密码字段疑似包含密钥或密码，已拒绝保存");
            }
        }
    }

    private static SiteCredential copyCredential(SiteCredential source) {
        SiteCredential copy = new SiteCredential();
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

    private static void rollbackQuietly(Connection c) {
        try {
            c.rollback();
        } catch (SQLException rollbackFailure) {
            log.warn("站点凭据事务回滚失败");
        }
    }
}
