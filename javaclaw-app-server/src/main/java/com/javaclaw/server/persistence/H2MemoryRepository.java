package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.agent.knowledge.MemoryRepository;
import com.javaclaw.agent.knowledge.MemorySafety;
import com.javaclaw.core.api.ThreadItem;

/** Memory 当前值、历史、来源和审批的事务实现；提案接受与新修订使用同一个连接提交。 */
public final class H2MemoryRepository implements MemoryRepository {
    private final H2Database database;
    private final H2IdempotencyStore idempotency = new H2IdempotencyStore();
    private final ObjectMapper json = new ObjectMapper();
    private final ThreadJsonCodec items = new ThreadJsonCodec();

    /** 使用 App Server 的共享数据库，不创建第二个数据根或写连接所有者。 */
    public H2MemoryRepository(H2Database database) {
        this.database = Objects.requireNonNull(database);
    }

    com.javaclaw.agent.knowledge.KnowledgeRepository.MemoryEntry putTextMemory(
            String id, String workspace, String kind, String content, long revision, String key) {
        String hash = H2IdempotencyStore.requestHash(id, workspace, kind, content, revision);
        return database.transaction(connection -> {
            var replay = idempotency.replay(
                    connection,
                    "memory/put",
                    key,
                    hash,
                    com.javaclaw.agent.knowledge.KnowledgeRepository.MemoryEntry.class);
            if (replay.isPresent()) {
                return replay.get();
            }
            lockWorkspace(connection, workspace);
            var current = id == null ? null : find(connection, id, true);
            var draft = new MemoryDraft(
                    id,
                    workspace,
                    kind,
                    current == null ? "" : current.draft().subject(),
                    current == null ? "" : current.draft().attribute(),
                    content,
                    current != null && current.draft().pinned(),
                    current == null ? List.of() : current.draft().sourceItemIds());
            var saved = save(connection, draft, revision);
            var result = new com.javaclaw.agent.knowledge.KnowledgeRepository.MemoryEntry(
                    saved.draft().id(),
                    workspace,
                    kind,
                    content,
                    saved.revision(),
                    saved.createdAt(),
                    saved.updatedAt());
            idempotency.record(connection, "memory/put", key, hash, result, System.currentTimeMillis());
            return result;
        });
    }

    @Override
    public MemoryDocument readMemory(String id) {
        return database.query(connection -> require(connection, id, false));
    }

    @Override
    public List<MemoryDocument> memoryHistory(String id) {
        return database.query(connection -> {
            var result = new ArrayList<MemoryDocument>();
            try (var statement = connection.prepareStatement(
                    "SELECT * FROM memory_versions WHERE memory_id = ? ORDER BY revision")) {
                statement.setString(1, id);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.add(document(rows));
                    }
                }
            }
            return List.copyOf(result);
        });
    }

    @Override
    public MemoryDocument saveMemory(MemoryDraft draft, long expectedRevision, String key) {
        MemorySafety.requireNoSecrets(draft.content());
        String hash = H2IdempotencyStore.requestHash(draft, expectedRevision);
        return database.transaction(connection -> {
            var replay = idempotency.replay(connection, "memory/save", key, hash, MemoryDocument.class);
            if (replay.isPresent()) {
                return replay.get();
            }
            MemoryDocument result = save(connection, draft, expectedRevision);
            idempotency.record(connection, "memory/save", key, hash, result, System.currentTimeMillis());
            return result;
        });
    }

    @Override
    public MemoryDocument restoreMemory(String id, long sourceRevision, long expectedRevision, String key) {
        String hash = H2IdempotencyStore.requestHash(id, sourceRevision, expectedRevision);
        return database.transaction(connection -> {
            var replay = idempotency.replay(connection, "memory/restore", key, hash, MemoryDocument.class);
            if (replay.isPresent()) {
                return replay.get();
            }
            MemoryDocument source;
            try (var statement =
                    connection.prepareStatement("SELECT * FROM memory_versions WHERE memory_id = ? AND revision = ?")) {
                statement.setString(1, id);
                statement.setLong(2, sourceRevision);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw new NoSuchElementException("memory revision not found");
                    }
                    source = document(rows);
                }
            }
            MemoryDocument result = save(connection, source.draft(), expectedRevision);
            idempotency.record(connection, "memory/restore", key, hash, result, System.currentTimeMillis());
            return result;
        });
    }

    @Override
    public MemoryProposal proposeMemory(
            MemoryDraft draft, long expectedTargetRevision, boolean automatic, String reason, String key) {
        MemorySafety.requireNoSecrets(draft.content());
        String hash = H2IdempotencyStore.requestHash(draft, expectedTargetRevision, automatic, reason);
        return database.transaction(connection -> {
            var replay = idempotency.replay(connection, "memory/propose", key, hash, MemoryProposal.class);
            if (replay.isPresent()) {
                return replay.get();
            }
            lockWorkspace(connection, draft.workspaceId());
            List<String> evidence = evidence(connection, draft);
            if (evidence.isEmpty()) {
                throw new IllegalArgumentException("memory proposal requires user or successful tool evidence");
            }
            MemoryDocument current =
                    draft.id() == null ? conflicting(connection, draft) : find(connection, draft.id(), true);
            long revision = current == null ? 0 : current.revision();
            if (draft.id() != null && revision != expectedTargetRevision) {
                throw new IllegalStateException("memory proposal target revision conflict");
            }
            String target = current == null
                    ? Objects.requireNonNullElseGet(draft.id(), () -> id("mem_"))
                    : current.draft().id();
            MemoryDraft proposed = identified(draft, target);
            String state = "PENDING";
            if (automatic && MemorySafety.mayAutomaticallyAccept(proposed, current, evidence)) {
                save(connection, proposed, revision);
                state = "ACCEPTED";
            }
            long now = System.currentTimeMillis();
            String explanation = reason == null ? "待审阅记忆提案" : reason;
            if (current != null) {
                explanation += "；目标已有内容，必须显式确认覆盖。";
            }
            if (explanation.length() > 4_000) {
                throw new IllegalArgumentException("memory proposal reason exceeds limit");
            }
            var result = new MemoryProposal(
                    id("memprop_"),
                    proposed,
                    revision,
                    state,
                    explanation,
                    1,
                    Instant.ofEpochMilli(now),
                    Instant.ofEpochMilli(now));
            update(
                    connection,
                    """
                    INSERT INTO memory_proposals(proposal_id, workspace_id, target_id, expected_target_revision,
                        draft_json, state, reason, revision, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                    """,
                    result.id(),
                    draft.workspaceId(),
                    target,
                    revision,
                    encode(proposed),
                    state,
                    explanation,
                    now,
                    now);
            idempotency.record(connection, "memory/propose", key, hash, result, now);
            return result;
        });
    }

    @Override
    public List<MemoryProposal> memoryProposals(String workspaceId) {
        return database.query(connection -> {
            var result = new ArrayList<MemoryProposal>();
            try (var statement = connection.prepareStatement(
                    "SELECT * FROM memory_proposals WHERE workspace_id = ? ORDER BY created_at, proposal_id")) {
                statement.setString(1, workspaceId);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.add(proposal(rows));
                    }
                }
            }
            return List.copyOf(result);
        });
    }

    @Override
    public MemoryProposal reviewMemoryProposal(String id, boolean accept, long revision, String key) {
        String hash = H2IdempotencyStore.requestHash(id, accept, revision);
        return database.transaction(connection -> {
            var replay = idempotency.replay(connection, "memory/proposal/review", key, hash, MemoryProposal.class);
            if (replay.isPresent()) {
                return replay.get();
            }
            MemoryProposal current;
            try (var statement =
                    connection.prepareStatement("SELECT * FROM memory_proposals WHERE proposal_id = ? FOR UPDATE")) {
                statement.setString(1, id);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw new NoSuchElementException("memory proposal not found");
                    }
                    current = proposal(rows);
                }
            }
            if (current.revision() != revision || !"PENDING".equals(current.state())) {
                throw new IllegalStateException("memory proposal is stale or already resolved");
            }
            if (accept) {
                save(connection, current.draft(), current.expectedTargetRevision());
            }
            long now = System.currentTimeMillis();
            String state = accept ? "ACCEPTED" : "REJECTED";
            update(
                    connection,
                    "UPDATE memory_proposals SET state = ?, revision = revision + 1, updated_at = ? WHERE proposal_id = ?",
                    state,
                    now,
                    id);
            var result = new MemoryProposal(
                    id,
                    current.draft(),
                    current.expectedTargetRevision(),
                    state,
                    current.reason(),
                    revision + 1,
                    current.createdAt(),
                    Instant.ofEpochMilli(now));
            idempotency.record(connection, "memory/proposal/review", key, hash, result, now);
            return result;
        });
    }

    private MemoryDocument save(Connection connection, MemoryDraft draft, long expectedRevision) throws SQLException {
        lockWorkspace(connection, draft.workspaceId());
        MemorySafety.requireNoSecrets(draft.content());
        try (var statement = connection.prepareStatement("SELECT locked FROM workspaces WHERE workspace_id = ?")) {
            statement.setString(1, draft.workspaceId());
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getBoolean(1)) {
                    throw new IllegalStateException("memory workspace is unavailable or locked");
                }
            }
        }
        evidence(connection, draft);
        String id = draft.id() == null || draft.id().isBlank() ? id("mem_") : draft.id();
        if (id.length() > 80) {
            throw new IllegalArgumentException("memory id exceeds limit");
        }
        MemoryDocument current = find(connection, id, true);
        long actual = current == null ? 0 : current.revision();
        if (actual != expectedRevision
                || (current != null && !current.draft().workspaceId().equals(draft.workspaceId()))) {
            throw new IllegalStateException("memory revision or workspace conflict");
        }
        long now = System.currentTimeMillis();
        long revision = actual + 1;
        // 删除后恢复也不能重用既有 revision，否则历史引用会被悄悄改写。
        if (current == null) {
            try (var statement = connection.prepareStatement(
                    "SELECT COALESCE(MAX(revision), 0) FROM memory_versions WHERE memory_id = ?")) {
                statement.setString(1, id);
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    revision = rows.getLong(1) + 1;
                }
            }
            update(
                    connection,
                    "INSERT INTO memories(memory_id, workspace_id, kind, content, revision, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    id,
                    draft.workspaceId(),
                    draft.kind(),
                    draft.content(),
                    revision,
                    now,
                    now);
        } else {
            update(
                    connection,
                    "UPDATE memories SET kind = ?, content = ?, revision = ?, updated_at = ? WHERE memory_id = ?",
                    draft.kind(),
                    draft.content(),
                    revision,
                    now,
                    id);
        }
        String sources = encode(draft.sourceItemIds());
        update(
                connection,
                "MERGE INTO memory_metadata(memory_id, subject, attribute_name, pinned, sources_json) KEY(memory_id) VALUES (?, ?, ?, ?, ?)",
                id,
                draft.subject(),
                draft.attribute(),
                draft.pinned(),
                sources);
        long created = current == null ? now : current.createdAt().toEpochMilli();
        update(
                connection,
                """
                INSERT INTO memory_versions(memory_id, revision, workspace_id, kind, content, subject,
                    attribute_name, pinned, sources_json, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                revision,
                draft.workspaceId(),
                draft.kind(),
                draft.content(),
                draft.subject(),
                draft.attribute(),
                draft.pinned(),
                sources,
                created,
                now);
        return new MemoryDocument(
                identified(draft, id), revision, Instant.ofEpochMilli(created), Instant.ofEpochMilli(now));
    }

    private MemoryDocument conflicting(Connection connection, MemoryDraft draft) throws SQLException {
        if (draft.subject().isBlank() || draft.attribute().isBlank()) {
            return null;
        }
        try (var statement = connection.prepareStatement("""
                SELECT m.memory_id FROM memories m JOIN memory_metadata d ON m.memory_id = d.memory_id
                WHERE m.workspace_id = ? AND d.subject = ? AND d.attribute_name = ? ORDER BY m.memory_id
                """)) {
            statement.setString(1, draft.workspaceId());
            statement.setString(2, draft.subject());
            statement.setString(3, draft.attribute());
            try (var rows = statement.executeQuery()) {
                return rows.next() ? require(connection, rows.getString(1), true) : null;
            }
        }
    }

    private List<String> evidence(Connection connection, MemoryDraft draft) throws SQLException {
        var result = new ArrayList<String>();
        for (String source : draft.sourceItemIds()) {
            try (var statement = connection.prepareStatement("""
                    SELECT i.payload_json FROM items i JOIN threads t ON i.thread_id = t.thread_id
                    WHERE i.item_id = ? AND t.workspace_id = ? AND i.state = 'COMPLETED'
                    """)) {
                statement.setString(1, source);
                statement.setString(2, draft.workspaceId());
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw new IllegalArgumentException("memory evidence must exist in its workspace");
                    }
                    ThreadItem item = items.item(rows.getString(1));
                    String text =
                            switch (item) {
                                case ThreadItem.UserMessage message -> message.text();
                                case ThreadItem.CommandExecution command
                                when command.exitCode() == 0 && !command.timedOut() -> command.stdout();
                                case ThreadItem.McpToolCall tool ->
                                    tool.result().toString();
                                case ThreadItem.FileChange file -> file.diff();
                                default ->
                                    throw new IllegalArgumentException(
                                            "assistant suggestions and failed tools are not memory evidence");
                            };
                    result.add(text);
                }
            }
        }
        return List.copyOf(result);
    }

    private MemoryDocument require(Connection connection, String id, boolean lock) throws SQLException {
        MemoryDocument result = find(connection, id, lock);
        if (result == null) {
            throw new NoSuchElementException("memory not found: " + id);
        }
        return result;
    }

    private MemoryDocument find(Connection connection, String id, boolean lock) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT m.*, d.subject, d.attribute_name, d.pinned, d.sources_json FROM memories m
                LEFT JOIN memory_metadata d ON m.memory_id = d.memory_id WHERE m.memory_id = ?
                """ + (lock ? " FOR UPDATE" : ""))) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? document(rows) : null;
            }
        }
    }

    private MemoryDocument document(ResultSet row) throws SQLException {
        String sources = row.getString("sources_json");
        List<String> references;
        try {
            references = sources == null
                    ? List.of()
                    : json.readValue(sources, json.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException invalid) {
            throw new SQLException("invalid stored memory sources", invalid);
        }
        var draft = new MemoryDraft(
                row.getString("memory_id"),
                row.getString("workspace_id"),
                row.getString("kind"),
                row.getString("subject"),
                row.getString("attribute_name"),
                row.getString("content"),
                row.getBoolean("pinned"),
                references);
        return new MemoryDocument(
                draft,
                row.getLong("revision"),
                Instant.ofEpochMilli(row.getLong("created_at")),
                Instant.ofEpochMilli(row.getLong("updated_at")));
    }

    private MemoryProposal proposal(ResultSet row) throws SQLException {
        try {
            return new MemoryProposal(
                    row.getString("proposal_id"),
                    json.readValue(row.getString("draft_json"), MemoryDraft.class),
                    row.getLong("expected_target_revision"),
                    row.getString("state"),
                    row.getString("reason"),
                    row.getLong("revision"),
                    Instant.ofEpochMilli(row.getLong("created_at")),
                    Instant.ofEpochMilli(row.getLong("updated_at")));
        } catch (JsonProcessingException invalid) {
            throw new SQLException("invalid memory proposal", invalid);
        }
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("cannot encode memory record", invalid);
        }
    }

    private static MemoryDraft identified(MemoryDraft draft, String id) {
        return new MemoryDraft(
                id,
                draft.workspaceId(),
                draft.kind(),
                draft.subject(),
                draft.attribute(),
                draft.content(),
                draft.pinned(),
                draft.sourceItemIds());
    }

    private static void update(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            statement.executeUpdate();
        }
    }

    private static void lockWorkspace(Connection connection, String workspace) throws SQLException {
        // 新条目还没有可锁定的 memory 行；工作区行锁串行化同对象同属性的新建和冲突检测。
        try (var statement =
                connection.prepareStatement("SELECT workspace_id FROM workspaces WHERE workspace_id = ? FOR UPDATE")) {
            statement.setString(1, workspace);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new NoSuchElementException("workspace not found");
                }
            }
        }
    }

    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }
}
