package com.javaclaw.site;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.javaclaw.config.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 站点凭据与会话管理（单例 / 工作区维度）
 *
 * <p>持久化布局：</p>
 * <pre>
 *   {workspace}/data/site-credentials.json     ← 凭据列表
 *   {workspace}/data/site-sessions/{id}.json   ← 每条目的 Playwright storageState
 * </pre>
 *
 * <p>密码字段以明文写入 JSON，与本项目对 API Key 等敏感配置的约定一致；
 * 该文件位于工作区目录内，请勿提交到版本控制。</p>
 *
 * @author JavaClaw
 */
public class SiteCredentialManager {

    private static final Logger log = LoggerFactory.getLogger(SiteCredentialManager.class);

    private static final String CONFIG_FILE_NAME = "data/site-credentials.json";
    private static final String SESSION_DIR_NAME = "data/site-sessions";

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

    // ==================== 路径 ====================

    private Path configPath() {
        return WorkspaceManager.getInstance()
                .getCurrentWorkspacePath().resolve(CONFIG_FILE_NAME);
    }

    private Path sessionDir() {
        return WorkspaceManager.getInstance()
                .getCurrentWorkspacePath().resolve(SESSION_DIR_NAME);
    }

    /** 指定条目的会话文件路径（即使文件不存在也返回路径） */
    public Path sessionFile(String id) {
        return sessionDir().resolve(id + ".json");
    }

    public String getConfigFilePath() {
        return configPath().toString();
    }

    // ==================== 加载/保存 ====================

    public void load() {
        credentials.clear();
        Path path = configPath();
        if (!Files.exists(path)) {
            log.info("站点凭据文件不存在，使用空配置: {}", path);
            return;
        }
        try {
            List<SiteCredential> list = objectMapper.readValue(
                    path.toFile(), new TypeReference<List<SiteCredential>>() {});
            for (SiteCredential c : list) {
                if (c.getId() == null || c.getId().isBlank()) {
                    c.setId(UUID.randomUUID().toString());
                }
                c.setHasSession(Files.exists(sessionFile(c.getId())));
                credentials.put(c.getId(), c);
            }
            log.info("已加载 {} 条站点凭据", credentials.size());
        } catch (IOException e) {
            log.warn("加载站点凭据失败: {}", e.getMessage());
        }
    }

    public void save() {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            objectMapper.writeValue(path.toFile(), new ArrayList<>(credentials.values()));
            log.info("已保存站点凭据到: {}", path);
        } catch (IOException e) {
            log.error("保存站点凭据失败", e);
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
        cred.setHasSession(Files.exists(sessionFile(cred.getId())));
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
        try {
            Path dir = sessionDir();
            Files.createDirectories(dir);
            Files.writeString(sessionFile(id), storageStateJson);
            SiteCredential c = credentials.get(id);
            if (c != null) {
                c.setHasSession(true);
                c.setLastUsedAt(System.currentTimeMillis());
            }
            save();
            log.info("已保存站点会话: {}", id);
        } catch (IOException e) {
            log.error("保存站点会话失败: {}", id, e);
        }
    }

    /** 读取 storageState；不存在返回 null */
    public String readSession(String id) {
        Path file = sessionFile(id);
        if (!Files.exists(file)) return null;
        try {
            return Files.readString(file);
        } catch (IOException e) {
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
        Path file = sessionFile(id);
        try {
            Files.deleteIfExists(file);
            SiteCredential c = credentials.get(id);
            if (c != null) {
                c.setHasSession(false);
                save();
            }
            log.info("已清除站点会话: {}", id);
        } catch (IOException e) {
            log.warn("清除站点会话失败: {}", id, e);
        }
    }
}
