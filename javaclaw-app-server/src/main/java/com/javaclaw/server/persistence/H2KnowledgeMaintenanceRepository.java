package com.javaclaw.server.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.javaclaw.agent.knowledge.KnowledgeMaintenanceRepository;
import com.javaclaw.agent.knowledge.MemorySafety;
import com.javaclaw.core.api.ThreadItem;

/** 知识维护收件箱；任务来自完成状态而非易丢失的订阅，升级前历史不会自动产生模型费用。 */
public final class H2KnowledgeMaintenanceRepository implements KnowledgeMaintenanceRepository {
    private final H2Database database;
    private final ThreadJsonCodec codec = new ThreadJsonCodec();

    /** 共享唯一 H2 事务边界，不创建工作区数据库或独立执行器。 */
    public H2KnowledgeMaintenanceRepository(H2Database database) {
        this.database = java.util.Objects.requireNonNull(database);
    }

    @Override
    public void discover() {
        database.transaction(connection -> {
            try (var query = connection.prepareStatement("""
                    SELECT t.turn_id, th.workspace_id, t.config_json FROM turns t
                    JOIN threads th ON th.thread_id=t.thread_id
                    WHERE t.status='COMPLETED' AND t.profile_kind='CHAT' AND th.parent_thread_id IS NULL
                      AND t.started_at >= (SELECT applied_at FROM schema_migrations WHERE version=8)
                      AND NOT EXISTS(SELECT 1 FROM knowledge_maintenance_jobs j WHERE j.source_turn_id=t.turn_id)
                    ORDER BY t.completed_at, t.turn_id LIMIT 50
                    """);
                    var rows = query.executeQuery()) {
                while (rows.next()) {
                    var config = codec.config(rows.getString("config_json"));
                    boolean auxiliary = config.attributes().containsKey("invocationPurpose");
                    try (var insert = connection.prepareStatement("""
                            INSERT INTO knowledge_maintenance_jobs(source_turn_id, workspace_id, state, reason,
                                created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)
                            """)) {
                        insert.setString(1, rows.getString("turn_id"));
                        insert.setString(2, rows.getString("workspace_id"));
                        insert.setString(3, auxiliary ? "SKIPPED" : "PENDING");
                        insert.setString(4, auxiliary ? "辅助任务不触发递归维护" : "");
                        insert.setLong(5, System.currentTimeMillis());
                        insert.setLong(6, System.currentTimeMillis());
                        insert.executeUpdate();
                    }
                }
            }
            return null;
        });
    }

    @Override
    public List<Job> pending(int limit) {
        return database.query(connection -> {
            var result = new ArrayList<Job>();
            try (var query = connection.prepareStatement("""
                    SELECT * FROM knowledge_maintenance_jobs WHERE state IN ('PENDING','STARTED')
                    ORDER BY CASE WHEN state='STARTED' THEN 0 ELSE 1 END, created_at LIMIT ?
                    """)) {
                query.setInt(1, Math.max(1, Math.min(50, limit)));
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        result.add(new Job(
                                rows.getString("source_turn_id"),
                                rows.getString("workspace_id"),
                                rows.getString("state"),
                                rows.getString("maintenance_thread_id")));
                    }
                }
            }
            return List.copyOf(result);
        });
    }

    @Override
    public void started(String source, String threadId) {
        database.transaction(connection -> {
            try (var update = connection.prepareStatement("""
                    UPDATE knowledge_maintenance_jobs SET state='STARTED', maintenance_thread_id=?, updated_at=?
                    WHERE source_turn_id=? AND state IN ('PENDING','STARTED')
                      AND (maintenance_thread_id IS NULL OR maintenance_thread_id=?)
                    """)) {
                update.setString(1, threadId);
                update.setLong(2, System.currentTimeMillis());
                update.setString(3, source);
                update.setString(4, threadId);
                if (update.executeUpdate() != 1) {
                    throw new IllegalStateException("maintenance job changed");
                }
            }
            return null;
        });
    }

    @Override
    public void finish(String source, String state, String reason) {
        if (!Set.of("COMPLETED", "FAILED", "INTERRUPTED", "SKIPPED").contains(state) || reason.length() > 500) {
            throw new IllegalArgumentException("invalid maintenance outcome");
        }
        database.transaction(connection -> {
            try (var update = connection.prepareStatement("""
                    UPDATE knowledge_maintenance_jobs SET state=?, reason=?, updated_at=? WHERE source_turn_id=?
                    """)) {
                update.setString(1, state);
                update.setString(2, reason);
                update.setLong(3, System.currentTimeMillis());
                update.setString(4, source);
                update.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public boolean foregroundActive(String workspace) {
        return database.query(connection -> {
            try (var query = connection.prepareStatement("""
                    SELECT COUNT(*) FROM turns t JOIN threads th ON th.thread_id=t.thread_id
                    WHERE th.workspace_id=? AND t.status NOT IN ('COMPLETED','FAILED','INTERRUPTED')
                    """)) {
                query.setString(1, workspace);
                try (var rows = query.executeQuery()) {
                    rows.next();
                    return rows.getLong(1) > 0;
                }
            }
        });
    }

    @Override
    public List<Evidence> evidence(String source, String workspace) {
        return database.query(connection -> {
            var result = new ArrayList<Evidence>();
            int size = 0;
            try (var query = connection.prepareStatement("""
                    SELECT i.item_id, i.kind, i.payload_json FROM items i
                    JOIN turns t ON t.turn_id=i.turn_id JOIN threads th ON th.thread_id=t.thread_id
                    WHERE i.turn_id=? AND th.workspace_id=? AND t.status='COMPLETED' AND i.state='COMPLETED'
                      AND i.kind IN ('userMessage','commandExecution','evaluation','fileChange','mcpToolCall')
                    ORDER BY i.ordinal LIMIT 50
                    """)) {
                query.setString(1, source);
                query.setString(2, workspace);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        ThreadItem item = codec.item(rows.getString("payload_json"));
                        String content =
                                switch (item) {
                                    case ThreadItem.UserMessage value -> value.text();
                                    case ThreadItem.CommandExecution value
                                    when value.exitCode() == 0 && !value.timedOut() ->
                                        "command=" + value.argv() + "\n" + value.stdout();
                                    case ThreadItem.Evaluation value when value.passed() -> value.summary();
                                    case ThreadItem.McpToolCall value
                                    when "completed"
                                            .equalsIgnoreCase(value.result().get("status")) ->
                                        value.result().toString();
                                    case ThreadItem.FileChange value -> value.path() + "\n" + value.diff();
                                    default -> "";
                                };
                        if (content == null
                                || content.isBlank()
                                || content.length() > 4000
                                || size + content.length() > 16_000) {
                            continue;
                        }
                        try {
                            MemorySafety.requireNoSecrets(content);
                        } catch (IllegalArgumentException secret) {
                            continue;
                        }
                        boolean skill =
                                item instanceof ThreadItem.CommandExecution || item instanceof ThreadItem.Evaluation;
                        result.add(new Evidence(rows.getString("item_id"), rows.getString("kind"), content, skill));
                        size += content.length();
                    }
                }
            }
            return List.copyOf(result);
        });
    }
}
