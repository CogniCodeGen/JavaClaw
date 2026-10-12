package com.javaclaw.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 工作区隔离的高风险命令授权白名单。
 *
 * <p>实例由根 Spring Context 管理，内存快照受读写锁保护，完整快照在单一事务中替换。
 * 工作区切换方必须在发布新运行时前调用 {@link #reload()}。授权项是持久安全决策，只有
 * 调用方已经完成人工确认后才可调用 {@link #addEntry(String, String)}。</p>
 */
public final class CommandWhitelistManager {

    private static final Logger log = LoggerFactory.getLogger(CommandWhitelistManager.class);
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Supplier<String> workspaceId;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private List<WhitelistEntry> entries = new ArrayList<>();

    public record WhitelistEntry(
            String id,
            String commandPrefix,
            String workDir,
            String addedAt,
            int useCount) { }

    public CommandWhitelistManager(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            Supplier<String> workspaceId) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        reload();
    }

    public void reload() {
        String workspace = currentWorkspaceId();
        lock.writeLock().lock();
        try {
            entries = new ArrayList<>(jdbc.query("""
                    SELECT id, command_prefix, work_dir, added_at, use_count
                    FROM command_whitelist
                    WHERE workspace_id = ?
                    ORDER BY added_at, id
                    """, (row, index) -> new WhitelistEntry(
                            row.getString("id"),
                            row.getString("command_prefix"),
                            row.getString("work_dir"),
                            row.getString("added_at"),
                            row.getInt("use_count")), workspace));
            log.info("命令白名单已从 H2 加载: {} 条", entries.size());
        } catch (DataAccessException failure) {
            log.error("加载命令白名单失败: workspace={}", workspace, failure);
            entries = new ArrayList<>();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean isWhitelisted(String command, String workDir) {
        String normalizedCommand = requirePrefix(command);
        String normalizedDirectory = normalizeDir(workDir);
        lock.readLock().lock();
        try {
            return entries.stream().anyMatch(entry ->
                    normalizedCommand.startsWith(entry.commandPrefix())
                            && isDirMatch(normalizedDirectory, entry.workDir()));
        } finally {
            lock.readLock().unlock();
        }
    }

    public String addEntry(String commandPrefix, String workDir) {
        String normalizedPrefix = requirePrefix(commandPrefix);
        String normalizedDirectory = normalizeDir(workDir);
        lock.writeLock().lock();
        try {
            WhitelistEntry existing = entries.stream()
                    .filter(entry -> entry.commandPrefix().equals(normalizedPrefix)
                            && entry.workDir().equals(normalizedDirectory))
                    .findFirst().orElse(null);
            if (existing != null) {
                log.info("白名单条目已存在: [{}] @ {}",
                        normalizedPrefix, normalizedDirectory);
                return existing.id();
            }
            String id = UUID.randomUUID().toString().substring(0, 8);
            entries.add(new WhitelistEntry(
                    id, normalizedPrefix, normalizedDirectory,
                    LocalDateTime.now().format(FORMATTER), 0));
            saveSnapshot();
            log.info("已添加白名单条目: [{}] @ {} (id={})",
                    normalizedPrefix, normalizedDirectory, id);
            return id;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean removeEntry(String id) {
        if (id == null || id.isBlank()) return false;
        lock.writeLock().lock();
        try {
            boolean removed = entries.removeIf(entry -> entry.id().equals(id));
            if (removed) {
                saveSnapshot();
                log.info("已移除白名单条目: {}", id);
            }
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<WhitelistEntry> listEntries() {
        lock.readLock().lock();
        try {
            return List.copyOf(entries);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void incrementUseCount(String command, String workDir) {
        String normalizedCommand = requirePrefix(command);
        String normalizedDirectory = normalizeDir(workDir);
        lock.writeLock().lock();
        try {
            for (int index = 0; index < entries.size(); index++) {
                WhitelistEntry entry = entries.get(index);
                if (normalizedCommand.startsWith(entry.commandPrefix())
                        && isDirMatch(normalizedDirectory, entry.workDir())) {
                    entries.set(index, new WhitelistEntry(
                            entry.id(), entry.commandPrefix(), entry.workDir(),
                            entry.addedAt(), entry.useCount() + 1));
                    saveSnapshot();
                    return;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 调用方必须持有写锁，保证数据库与内存快照使用同一版本。 */
    private void saveSnapshot() {
        String workspace = currentWorkspaceId();
        List<WhitelistEntry> snapshot = List.copyOf(entries);
        try {
            transactions.executeWithoutResult(status -> {
                jdbc.update("DELETE FROM command_whitelist WHERE workspace_id = ?", workspace);
                if (snapshot.isEmpty()) return;
                jdbc.batchUpdate("""
                        INSERT INTO command_whitelist(
                            workspace_id, id, command_prefix, work_dir,
                            added_at, use_count, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                        """, snapshot, snapshot.size(), (statement, entry) -> {
                            statement.setString(1, workspace);
                            statement.setString(2, entry.id());
                            statement.setString(3, entry.commandPrefix());
                            statement.setString(4, entry.workDir());
                            statement.setString(5, entry.addedAt());
                            statement.setInt(6, entry.useCount());
                        });
            });
        } catch (DataAccessException failure) {
            log.error("保存命令白名单失败: workspace={}", workspace, failure);
        }
    }

    private String currentWorkspaceId() {
        String value = workspaceId.get();
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("当前工作区 ID 尚未初始化");
        }
        return value;
    }

    private static String requirePrefix(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("命令前缀不能为空");
        }
        return value.trim();
    }

    private static String normalizeDir(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.trim();
        while (normalized.length() > 1
                && (normalized.endsWith("/") || normalized.endsWith("\\"))) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean isDirMatch(String executionDirectory, String authorizedDirectory) {
        if (authorizedDirectory == null || authorizedDirectory.isBlank()) return true;
        if (executionDirectory == null || executionDirectory.isBlank()) return false;
        return executionDirectory.equals(authorizedDirectory)
                || executionDirectory.startsWith(authorizedDirectory + "/")
                || executionDirectory.startsWith(authorizedDirectory + "\\");
    }
}
