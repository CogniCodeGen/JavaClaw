package com.javaclaw.system;

import com.javaclaw.config.AppDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 命令行白名单管理器
 *
 * <p>管理已获批准的高风险命令，按（命令前缀、工作目录）配对存储。
 * 白名单中的命令在对应目录下执行时无需用户再次确认。</p>
 *
 * <p>白名单持久化到全局 H2 数据库的 {@code command_whitelist} 表，并按 {@code workspace_id} 隔离。</p>
 *
 * @author JavaClaw
 */
public class CommandWhitelistManager {

    private static final Logger log = LoggerFactory.getLogger(CommandWhitelistManager.class);
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static volatile CommandWhitelistManager instance;

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private List<WhitelistEntry> entries = new ArrayList<>();

    /**
     * 白名单条目
     */
    public record WhitelistEntry(
            String id,
            String commandPrefix,
            String workDir,
            String addedAt,
            int useCount
    ) {}

    private CommandWhitelistManager() {
        load();
    }

    public static CommandWhitelistManager getInstance() {
        if (instance == null) {
            synchronized (CommandWhitelistManager.class) {
                if (instance == null) {
                    instance = new CommandWhitelistManager();
                }
            }
        }
        return instance;
    }

    /**
     * 重新加载白名单（工作区切换时调用）
     */
    public void reload() {
        load();
    }

    /**
     * 检查命令是否在白名单中
     *
     * @param command 要执行的命令
     * @param workDir 执行目录
     * @return true 表示已白名单，可直接执行
     */
    public boolean isWhitelisted(String command, String workDir) {
        lock.readLock().lock();
        try {
            String normalizedCmd = command.trim();
            String normalizedDir = normalizeDir(workDir);

            for (WhitelistEntry entry : entries) {
                if (normalizedCmd.startsWith(entry.commandPrefix())
                        && isDirMatch(normalizedDir, entry.workDir())) {
                    return true;
                }
            }
            return false;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 将命令添加到白名单
     *
     * @param commandPrefix 命令前缀（通常为命令的前一两个词）
     * @param workDir       生效的工作目录
     * @return 条目 ID
     */
    public String addEntry(String commandPrefix, String workDir) {
        lock.writeLock().lock();
        try {
            String normalizedPrefix = commandPrefix.trim();
            String normalizedDir = normalizeDir(workDir);

            // 去重
            for (WhitelistEntry e : entries) {
                if (e.commandPrefix().equals(normalizedPrefix) && e.workDir().equals(normalizedDir)) {
                    log.info("白名单条目已存在: [{}] @ {}", normalizedPrefix, normalizedDir);
                    return e.id();
                }
            }

            String id = UUID.randomUUID().toString().substring(0, 8);
            WhitelistEntry entry = new WhitelistEntry(
                    id, normalizedPrefix, normalizedDir,
                    LocalDateTime.now().format(FORMATTER), 0);
            entries.add(entry);
            save();
            log.info("已添加白名单条目: [{}] @ {} (id={})", normalizedPrefix, normalizedDir, id);
            return id;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 从白名单移除条目
     */
    public boolean removeEntry(String id) {
        lock.writeLock().lock();
        try {
            boolean removed = entries.removeIf(e -> e.id().equals(id));
            if (removed) {
                save();
                log.info("已移除白名单条目: {}", id);
            }
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 获取所有白名单条目（只读副本）
     */
    public List<WhitelistEntry> listEntries() {
        lock.readLock().lock();
        try {
            return List.copyOf(entries);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 增加匹配条目的使用计数
     */
    public void incrementUseCount(String command, String workDir) {
        lock.writeLock().lock();
        try {
            String normalizedCmd = command.trim();
            String normalizedDir = normalizeDir(workDir);
            for (int i = 0; i < entries.size(); i++) {
                WhitelistEntry e = entries.get(i);
                if (normalizedCmd.startsWith(e.commandPrefix())
                        && isDirMatch(normalizedDir, e.workDir())) {
                    entries.set(i, new WhitelistEntry(
                            e.id(), e.commandPrefix(), e.workDir(),
                            e.addedAt(), e.useCount() + 1));
                    save();
                    return;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ==================== 持久化 ====================

    private void load() {
        lock.writeLock().lock();
        try {
            entries = new ArrayList<>();
            String sql = """
                    SELECT id, command_prefix, work_dir, added_at, use_count
                    FROM command_whitelist
                    WHERE workspace_id = ?
                    ORDER BY added_at, id
                    """;
            try (Connection c = AppDatabase.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, AppDatabase.currentWorkspaceId());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        entries.add(new WhitelistEntry(
                                rs.getString("id"),
                                rs.getString("command_prefix"),
                                rs.getString("work_dir"),
                                rs.getString("added_at"),
                                rs.getInt("use_count")));
                    }
                }
                log.info("命令白名单已从 H2 加载: {} 条", entries.size());
            }
        } catch (SQLException e) {
            log.error("加载命令白名单失败", e);
            entries = new ArrayList<>();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void save() {
        String insert = """
                INSERT INTO command_whitelist(
                    workspace_id, id, command_prefix, work_dir, added_at, use_count, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """;
        try (Connection c = AppDatabase.getConnection();
             PreparedStatement del = c.prepareStatement("DELETE FROM command_whitelist WHERE workspace_id = ?");
             PreparedStatement ps = c.prepareStatement(insert)) {
            c.setAutoCommit(false);
            String workspaceId = AppDatabase.currentWorkspaceId();
            del.setString(1, workspaceId);
            del.executeUpdate();
            for (WhitelistEntry entry : entries) {
                ps.setString(1, workspaceId);
                ps.setString(2, entry.id());
                ps.setString(3, entry.commandPrefix());
                ps.setString(4, entry.workDir());
                ps.setString(5, entry.addedAt());
                ps.setInt(6, entry.useCount());
                ps.addBatch();
            }
            ps.executeBatch();
            c.commit();
        } catch (SQLException e) {
            log.error("保存命令白名单到 H2 失败", e);
        }
    }

    // ==================== 工具方法 ====================

    private static String normalizeDir(String dir) {
        if (dir == null || dir.isBlank()) return "";
        String d = dir.trim();
        while (d.length() > 1 && (d.endsWith("/") || d.endsWith("\\"))) {
            d = d.substring(0, d.length() - 1);
        }
        return d;
    }

    private static boolean isDirMatch(String execDir, String whitelistDir) {
        if (whitelistDir == null || whitelistDir.isBlank()) return true;
        if (execDir == null || execDir.isBlank()) return false;
        return execDir.equals(whitelistDir)
                || execDir.startsWith(whitelistDir + "/")
                || execDir.startsWith(whitelistDir + "\\");
    }
}
