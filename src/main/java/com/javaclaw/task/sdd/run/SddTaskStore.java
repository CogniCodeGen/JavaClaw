package com.javaclaw.task.sdd.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.javaclaw.platform.json.JsonCodec;
import com.javaclaw.task.sdd.spec.SpecPaths;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * SDD 任务索引与关联文档的工作区持久化边界。
 *
 * <p>实例只服务构造时绑定的工作区，线程安全。索引替换与关联文档删除均在
 * Spring 事务中执行；JSON 损坏或数据库失败会显式抛出，避免以空列表覆盖可恢复数据。</p>
 */
public final class SddTaskStore {

    private static final String INSERT_TASK = """
            INSERT INTO sdd_tasks(workspace_id, id, task_json, updated_at)
            VALUES (?, ?, ?, CURRENT_TIMESTAMP)
            """;

    private final String workspaceId;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JsonCodec json;

    public SddTaskStore(
            String workspaceId,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            JsonCodec json) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId 不能为空");
        }
        this.workspaceId = workspaceId;
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.json = Objects.requireNonNull(json, "json");
    }

    public List<SddManagedTask> loadAll() {
        return jdbc.query("""
                        SELECT task_json
                        FROM sdd_tasks
                        WHERE workspace_id = ?
                        ORDER BY updated_at, id
                        """,
                (row, index) -> decode(row.getString("task_json")), workspaceId);
    }

    /**
     * 原子替换当前工作区索引。序列化在开启事务前完成，任一任务无法编码时
     * 数据库保持原状。
     */
    public void replaceAll(List<SddManagedTask> tasks) {
        List<Object[]> rows = new ArrayList<>(tasks.size());
        for (SddManagedTask task : tasks) {
            rows.add(new Object[]{workspaceId, task.id, encode(task)});
        }
        transactions.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM sdd_tasks WHERE workspace_id = ?", workspaceId);
            if (!rows.isEmpty()) {
                jdbc.batchUpdate(INSERT_TASK, rows);
            }
        });
    }

    /** 原子删除任务的 OpenSpec 文档与验收缓存。 */
    public void deleteArtifacts(SddManagedTask task) {
        String workDir = normalizeWorkDir(task.workDir);
        if (workDir == null) {
            return;
        }
        String slug = SpecPaths.makeSlug(task.id, task.title);
        transactions.executeWithoutResult(status -> {
            jdbc.update("""
                            DELETE FROM sdd_spec_docs
                            WHERE workspace_id = ? AND work_dir = ? AND slug = ?
                            """,
                    workspaceId, workDir, slug);
            jdbc.update("""
                            DELETE FROM sdd_verify_cache
                            WHERE workspace_id = ? AND work_dir = ? AND slug = ?
                            """,
                    workspaceId, workDir, slug);
        });
    }

    private SddManagedTask decode(String value) {
        try {
            return json.decode(value, SddManagedTask.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("SDD 任务索引 JSON 损坏", failure);
        }
    }

    private String encode(SddManagedTask task) {
        try {
            return json.encodePretty(task);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("SDD 任务索引无法序列化: " + task.id, failure);
        }
    }

    private static String normalizeWorkDir(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Path.of(value).toAbsolutePath().normalize().toString();
    }
}
