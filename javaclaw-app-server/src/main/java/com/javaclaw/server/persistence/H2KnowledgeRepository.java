package com.javaclaw.server.persistence;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.agent.knowledge.KnowledgeRepository;

/** H2 authority for Memory, Knowledge documents/chunks and Skill metadata. */
public final class H2KnowledgeRepository implements KnowledgeRepository {
    private static final Set<String> SOURCE_STATES =
            Set.of("PENDING", "READY", "READY_KEYWORD_ONLY", "DEGRADED_EMBEDDING_UNAVAILABLE", "FAILED");
    private final H2Database database;
    private final H2MemoryRepository memories;
    private final H2SkillLearningRepository learning;
    private final H2IdempotencyStore idempotency = new H2IdempotencyStore();
    private final ObjectMapper json = new ObjectMapper();

    /** 绑定 App Server 持有的共享 H2Database；不另建连接工厂，数据库生命周期由装配层统一管理。 */
    public H2KnowledgeRepository(H2Database database) {
        this.database = Objects.requireNonNull(database, "database");
        memories = new H2MemoryRepository(database);
        learning = new H2SkillLearningRepository(database, this);
    }

    @Override
    public MemoryDocument readMemory(String id) {
        return memories.readMemory(id);
    }

    @Override
    public LearningSettings learningSettings(String workspaceId) {
        return learning.learningSettings(workspaceId);
    }

    @Override
    public LearningSettings saveLearningSettings(
            String workspaceId, String mode, boolean memoryAutomatic, long revision, String key) {
        return learning.saveLearningSettings(workspaceId, mode, memoryAutomatic, revision, key);
    }

    @Override
    public SkillProposal proposeSkill(SkillDraft draft, boolean automatic, String reason, String key) {
        return learning.proposeSkill(draft, automatic, reason, key);
    }

    @Override
    public List<SkillProposal> skillProposals(String workspaceId) {
        return learning.skillProposals(workspaceId);
    }

    @Override
    public SkillProposal reviewSkillProposal(String id, boolean accept, long revision, String key) {
        return learning.reviewSkillProposal(id, accept, revision, key);
    }

    @Override
    public List<MemoryDocument> memoryHistory(String id) {
        return memories.memoryHistory(id);
    }

    @Override
    public MemoryDocument saveMemory(MemoryDraft draft, long revision, String key) {
        return memories.saveMemory(draft, revision, key);
    }

    @Override
    public MemoryDocument restoreMemory(String id, long sourceRevision, long revision, String key) {
        return memories.restoreMemory(id, sourceRevision, revision, key);
    }

    @Override
    public MemoryProposal proposeMemory(
            MemoryDraft draft, long revision, boolean automatic, String reason, String key) {
        return memories.proposeMemory(draft, revision, automatic, reason, key);
    }

    @Override
    public List<MemoryProposal> memoryProposals(String workspaceId) {
        return memories.memoryProposals(workspaceId);
    }

    @Override
    public MemoryProposal reviewMemoryProposal(String id, boolean accept, long revision, String key) {
        return memories.reviewMemoryProposal(id, accept, revision, key);
    }

    @Override
    public List<MemoryEntry> listMemories(String workspaceId) {
        String workspace = required(workspaceId, "workspaceId");
        return database.query(connection -> {
            requireWorkspace(connection, workspace);
            ArrayList<MemoryEntry> result = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT * FROM memories WHERE workspace_id = ?
                    ORDER BY updated_at DESC, memory_id
                    """)) {
                query.setString(1, workspace);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        result.add(readMemory(rows));
                    }
                }
            }
            return List.copyOf(result);
        });
    }

    @Override
    public MemoryEntry putMemory(
            String id, String workspaceId, String kind, String content, long expectedRevision, String idempotencyKey) {
        return memories.putTextMemory(id, workspaceId, kind, content, expectedRevision, idempotencyKey);
    }

    @Override
    public boolean deleteMemory(String id, long expectedRevision, String idempotencyKey) {
        return deleteVersioned(
                "memory/delete",
                "memories",
                "memory_id",
                "memory",
                bounded(id, "id", 80),
                expectedRevision,
                idempotencyKey);
    }

    @Override
    public List<KnowledgeSource> listSources(String workspaceId) {
        String workspace = required(workspaceId, "workspaceId");
        return database.query(connection -> {
            requireWorkspace(connection, workspace);
            ArrayList<KnowledgeSource> result = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT * FROM knowledge_sources WHERE workspace_id = ?
                    ORDER BY updated_at DESC, source_id
                    """)) {
                query.setString(1, workspace);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        result.add(readSource(rows));
                    }
                }
            }
            return List.copyOf(result);
        });
    }

    @Override
    public List<KnowledgeSourceStats> sourceStats(String workspaceId) {
        String workspace = required(workspaceId, "workspaceId");
        return database.query(connection -> {
            requireWorkspace(connection, workspace);
            ArrayList<KnowledgeSourceStats> result = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT s.source_id, s.status,
                      COALESCE((SELECT MAX(g.revision) FROM knowledge_index_generations g
                                WHERE g.source_id = s.source_id), 0) AS generation,
                      (SELECT COUNT(*) FROM knowledge_chunks c
                       JOIN knowledge_documents d ON d.document_id = c.document_id
                       WHERE d.source_id = s.source_id) AS chunk_count,
                      (SELECT MAX(g.created_at) FROM knowledge_index_generations g
                       WHERE g.source_id = s.source_id) AS indexed_at
                    FROM knowledge_sources s
                    WHERE s.workspace_id = ?
                    ORDER BY s.updated_at DESC, s.source_id
                    """)) {
                query.setString(1, workspace);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        long indexedAt = rows.getLong("indexed_at");
                        Instant indexed = rows.wasNull() ? null : instant(indexedAt);
                        String status = rows.getString("status");
                        result.add(new KnowledgeSourceStats(
                                rows.getString("source_id"),
                                rows.getLong("generation"),
                                rows.getLong("chunk_count"),
                                retrievalMode(status),
                                indexed,
                                "FAILED".equals(status) ? "索引失败；服务端没有可安全展示的详细原因。" : ""));
                    }
                }
            }
            return List.copyOf(result);
        });
    }

    private static String retrievalMode(String status) {
        return switch (status) {
            case "READY" -> "VECTOR";
            case "READY_KEYWORD_ONLY", "DEGRADED_EMBEDDING_UNAVAILABLE" -> "KEYWORD";
            default -> "UNAVAILABLE";
        };
    }

    @Override
    public Optional<KnowledgeSource> findSource(String sourceId) {
        String id = bounded(sourceId, "sourceId", 80);
        return database.query(connection -> findSource(connection, id, false));
    }

    @Override
    public List<KnowledgeGeneration> sourceHistory(String sourceId) {
        return database.query(connection -> {
            var result = new ArrayList<KnowledgeGeneration>();
            try (var statement = connection.prepareStatement(
                    "SELECT * FROM knowledge_index_generations WHERE source_id = ? ORDER BY revision")) {
                statement.setString(1, sourceId);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.add(new KnowledgeGeneration(
                                sourceId,
                                rows.getLong("revision"),
                                rows.getString("content_sha256"),
                                rows.getString("extractor_fingerprint"),
                                rows.getString("status"),
                                instant(rows.getLong("created_at"))));
                    }
                }
            }
            return List.copyOf(result);
        });
    }

    @Override
    public String sourceContent(String sourceId, long revision) {
        return database.query(connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT content FROM knowledge_index_generations WHERE source_id = ? AND revision = ?")) {
                statement.setString(1, sourceId);
                statement.setLong(2, revision);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw new NoSuchElementException("knowledge generation not found");
                    }
                    return rows.getString(1);
                }
            }
        });
    }

    @Override
    public KnowledgeSource createSource(
            String workspaceId, String attachmentSha256, String displayName, String mediaType, String idempotencyKey) {
        String workspace = required(workspaceId, "workspaceId");
        String hash = hash(attachmentSha256);
        String name = bounded(displayName, "displayName", 500);
        String type = bounded(mediaType, "mediaType", 300);
        String requestHash = H2IdempotencyStore.requestHash(workspace, hash, name, type);
        return database.transaction(connection -> {
            Optional<KnowledgeSource> replay = idempotency.replay(
                    connection, "knowledge/source/import", idempotencyKey, requestHash, KnowledgeSource.class);
            if (replay.isPresent()) {
                return replay.get();
            }
            requireWorkspace(connection, workspace);
            requireAttachment(connection, hash);
            String sourceId = id("src_");
            long now = System.currentTimeMillis();
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO knowledge_sources(source_id, workspace_id, attachment_sha256,
                        display_name, media_type, status, revision, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 'PENDING', 1, ?, ?)
                    """)) {
                insert.setString(1, sourceId);
                insert.setString(2, workspace);
                insert.setString(3, hash);
                insert.setString(4, name);
                insert.setString(5, type);
                insert.setLong(6, now);
                insert.setLong(7, now);
                insert.executeUpdate();
            }
            try (PreparedStatement reference = connection.prepareStatement("""
                    INSERT INTO attachment_references(owner_type, owner_id,
                        content_sha256, created_at) VALUES ('KNOWLEDGE_SOURCE', ?, ?, ?)
                    """)) {
                reference.setString(1, sourceId);
                reference.setString(2, hash);
                reference.setLong(3, now);
                reference.executeUpdate();
            }
            try (PreparedStatement retain = connection.prepareStatement("""
                    UPDATE attachments SET reference_count = reference_count + 1,
                        orphaned_at = NULL
                    WHERE content_sha256 = ?
                    """)) {
                retain.setString(1, hash);
                if (retain.executeUpdate() != 1) {
                    throw new NoSuchElementException("attachment not found: " + hash);
                }
            }
            KnowledgeSource result = new KnowledgeSource(
                    sourceId, workspace, hash, name, type, "PENDING", 1, instant(now), instant(now));
            idempotency.record(connection, "knowledge/source/import", idempotencyKey, requestHash, result, now);
            return result;
        });
    }

    @Override
    public KnowledgeSource replaceIndex(
            String sourceId,
            String content,
            String contentSha256,
            String extractorFingerprint,
            List<IndexedChunk> chunks,
            String status,
            long expectedRevision,
            String idempotencyKey) {
        String id = bounded(sourceId, "sourceId", 80);
        String documentContent = bounded(content, "content", 32 * 1024 * 1024);
        String contentHash = hash(contentSha256);
        String extractor = hash(extractorFingerprint);
        String state = required(status, "status");
        if (!SOURCE_STATES.contains(state)) {
            throw new IllegalArgumentException("unknown knowledge source status: " + state);
        }
        List<IndexedChunk> immutableChunks = List.copyOf(chunks);
        if (immutableChunks.isEmpty() || immutableChunks.size() > 100_000) {
            throw new IllegalArgumentException("knowledge index chunk count is invalid");
        }
        String requestHash = H2IdempotencyStore.requestHash(
                id, contentHash, extractor, state, expectedRevision, chunkFingerprint(immutableChunks));
        return database.transaction(connection -> {
            Optional<KnowledgeSource> replay = idempotency.replay(
                    connection, "knowledge/source/reindex", idempotencyKey, requestHash, KnowledgeSource.class);
            if (replay.isPresent()) {
                return replay.get();
            }
            KnowledgeSource current = findSource(connection, id, true)
                    .orElseThrow(() -> new NoSuchElementException("knowledge source not found: " + id));
            requireRevision("knowledge source", id, current.revision(), expectedRevision);
            try (PreparedStatement delete = connection.prepareStatement("""
                    DELETE FROM knowledge_documents WHERE source_id = ?
                    """)) {
                delete.setString(1, id);
                delete.executeUpdate();
            }
            long now = System.currentTimeMillis();
            String documentId = id("doc_");
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO knowledge_documents(document_id, source_id, content,
                        content_sha256, extractor_fingerprint, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
                insert.setString(1, documentId);
                insert.setString(2, id);
                insert.setString(3, documentContent);
                insert.setString(4, contentHash);
                insert.setString(5, extractor);
                insert.setLong(6, now);
                insert.executeUpdate();
            }
            int expectedOrdinal = 0;
            for (IndexedChunk chunk : immutableChunks) {
                Objects.requireNonNull(chunk, "chunk");
                if (chunk.ordinal() != expectedOrdinal++) {
                    throw new IllegalArgumentException("knowledge chunk ordinals must be contiguous");
                }
                String chunkContent = bounded(chunk.content(), "chunk.content", 100_000);
                String chunkId = id("chk_");
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO knowledge_chunks(chunk_id, document_id, ordinal,
                            content, content_sha256, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)) {
                    insert.setString(1, chunkId);
                    insert.setString(2, documentId);
                    insert.setInt(3, chunk.ordinal());
                    insert.setString(4, chunkContent);
                    insert.setString(5, sha256(chunkContent));
                    insert.setLong(6, now);
                    insert.executeUpdate();
                }
                if (chunk.embedding() != null) {
                    float[] vector = chunk.embedding();
                    if (vector.length < 1 || vector.length > 65_536) {
                        throw new IllegalArgumentException("embedding dimensions are invalid");
                    }
                    try (PreparedStatement insert = connection.prepareStatement("""
                            INSERT INTO knowledge_embeddings(chunk_id, provider, model,
                                dimensions, vector_blob, fingerprint, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """)) {
                        insert.setString(1, chunkId);
                        insert.setString(2, bounded(chunk.embeddingProvider(), "embeddingProvider", 200));
                        insert.setString(3, bounded(chunk.embeddingModel(), "embeddingModel", 500));
                        insert.setInt(4, vector.length);
                        insert.setBytes(5, encode(vector));
                        insert.setString(6, hash(chunk.embeddingFingerprint()));
                        insert.setLong(7, now);
                        insert.executeUpdate();
                    }
                }
            }
            long revision = current.revision() + 1;
            // 解析、Embedding 和校验先在事务外完成；只在整代数据可用时原子切换投影并归档，失败回滚保留旧索引。
            try (var generation = connection.prepareStatement("""
                    INSERT INTO knowledge_index_generations(source_id, revision, content_sha256,
                        extractor_fingerprint, status, content, chunks_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                generation.setString(1, id);
                generation.setLong(2, revision);
                generation.setString(3, contentHash);
                generation.setString(4, extractor);
                generation.setString(5, state);
                generation.setString(6, documentContent);
                generation.setString(7, encodedChunks(immutableChunks));
                generation.setLong(8, now);
                generation.executeUpdate();
            }
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE knowledge_sources SET status = ?, revision = ?, updated_at = ?
                    WHERE source_id = ? AND revision = ?
                    """)) {
                update.setString(1, state);
                update.setLong(2, revision);
                update.setLong(3, now);
                update.setString(4, id);
                update.setLong(5, current.revision());
                if (update.executeUpdate() != 1) {
                    conflict("knowledge source", id);
                }
            }
            KnowledgeSource result = new KnowledgeSource(
                    id,
                    current.workspaceId(),
                    current.attachmentSha256(),
                    current.displayName(),
                    current.mediaType(),
                    state,
                    revision,
                    current.createdAt(),
                    instant(now));
            idempotency.record(connection, "knowledge/source/reindex", idempotencyKey, requestHash, result, now);
            return result;
        });
    }

    @Override
    public boolean deleteSource(String sourceId, long expectedRevision, String idempotencyKey) {
        String id = bounded(sourceId, "sourceId", 80);
        String requestHash = H2IdempotencyStore.requestHash(id, expectedRevision);
        return database.transaction(connection -> {
            Optional<Boolean> replay = idempotency.replay(
                    connection, "knowledge/source/delete", idempotencyKey, requestHash, Boolean.class);
            if (replay.isPresent()) {
                return replay.get();
            }
            KnowledgeSource source = findSource(connection, id, true).orElse(null);
            if (source == null) {
                idempotency.record(
                        connection,
                        "knowledge/source/delete",
                        idempotencyKey,
                        requestHash,
                        false,
                        System.currentTimeMillis());
                return false;
            }
            requireRevision("knowledge source", id, source.revision(), expectedRevision);
            try (PreparedStatement delete =
                    connection.prepareStatement("DELETE FROM knowledge_sources WHERE source_id = ?")) {
                delete.setString(1, id);
                delete.executeUpdate();
            }
            try (PreparedStatement delete = connection.prepareStatement("""
                    DELETE FROM attachment_references
                    WHERE owner_type = 'KNOWLEDGE_SOURCE' AND owner_id = ?
                    """)) {
                delete.setString(1, id);
                delete.executeUpdate();
            }
            try (PreparedStatement release = connection.prepareStatement("""
                    UPDATE attachments SET reference_count = reference_count - 1,
                        orphaned_at = CASE WHEN reference_count <= 1 THEN ? ELSE NULL END
                    WHERE content_sha256 = ? AND reference_count > 0
                    """)) {
                release.setLong(1, System.currentTimeMillis());
                release.setString(2, source.attachmentSha256());
                release.executeUpdate();
            }
            long now = System.currentTimeMillis();
            idempotency.record(connection, "knowledge/source/delete", idempotencyKey, requestHash, true, now);
            return true;
        });
    }

    @Override
    public List<SearchHit> search(
            String workspaceId, String query, float[] queryVector, String embeddingFingerprint, int limit) {
        String workspace = required(workspaceId, "workspaceId");
        String searchText = bounded(query, "query", 20_000);
        int boundedLimit = Math.max(1, Math.min(100, limit));
        float[] vector = queryVector == null ? null : queryVector.clone();
        String fingerprint = vector == null ? null : hash(embeddingFingerprint);
        return database.query(connection -> {
            requireWorkspace(connection, workspace);
            ArrayList<SearchHit> hits = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT s.source_id, s.display_name, s.revision, c.chunk_id, c.content,
                           e.dimensions, e.vector_blob
                    FROM knowledge_sources s
                    JOIN knowledge_documents d ON d.source_id = s.source_id
                    JOIN knowledge_chunks c ON c.document_id = d.document_id
                    LEFT JOIN knowledge_embeddings e
                      ON e.chunk_id = c.chunk_id AND e.fingerprint = ?
                    WHERE s.workspace_id = ? AND s.status <> 'FAILED'
                    """)) {
                statement.setString(1, fingerprint == null ? "" : fingerprint);
                statement.setString(2, workspace);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String content = rows.getString("content");
                        double keyword = keywordScore(searchText, content);
                        byte[] encoded = rows.getBytes("vector_blob");
                        double semantic = 0;
                        boolean hasSemantic = vector != null && encoded != null;
                        if (hasSemantic) {
                            float[] stored = decode(encoded, rows.getInt("dimensions"));
                            if (stored.length == vector.length) {
                                semantic = Math.max(0, cosine(vector, stored));
                            } else {
                                hasSemantic = false;
                            }
                        }
                        double score = hasSemantic ? (0.70 * semantic + 0.30 * keyword) : keyword;
                        if (score > 0) {
                            hits.add(new SearchHit(
                                    rows.getString("source_id"),
                                    rows.getString("chunk_id"),
                                    rows.getString("display_name"),
                                    content,
                                    score,
                                    rows.getLong("revision")));
                        }
                    }
                }
            }
            hits.sort(Comparator.comparingDouble(SearchHit::score)
                    .reversed()
                    .thenComparing(SearchHit::sourceId)
                    .thenComparing(SearchHit::chunkId));
            return List.copyOf(hits.subList(0, Math.min(boundedLimit, hits.size())));
        });
    }

    @Override
    public List<SkillEntry> listSkills() {
        return database.query(connection -> {
            ArrayList<SkillEntry> result = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT * FROM skills ORDER BY name, skill_id
                    """);
                    ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    result.add(readSkill(rows));
                }
            }
            return List.copyOf(result);
        });
    }

    @Override
    public Optional<SkillEntry> findSkill(String id) {
        String skillId = bounded(id, "id", 160);
        return database.query(connection -> findSkill(connection, skillId, false));
    }

    @Override
    public List<SkillEntry> skillHistory(String id) {
        return database.query(connection -> {
            var values = new ArrayList<SkillEntry>();
            try (var statement =
                    connection.prepareStatement("SELECT * FROM skill_versions WHERE skill_id = ? ORDER BY revision")) {
                statement.setString(1, id);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        values.add(readSkill(rows));
                    }
                }
            }
            return List.copyOf(values);
        });
    }

    @Override
    public SkillEntry restoreSkill(String id, long sourceRevision, long expectedRevision, String key) {
        String requestHash = H2IdempotencyStore.requestHash(id, sourceRevision, expectedRevision);
        return database.transaction(connection -> {
            var replay = idempotency.replay(connection, "skill/restore", key, requestHash, SkillEntry.class);
            if (replay.isPresent()) {
                return replay.get();
            }
            var current = findSkill(connection, id, true).orElse(null);
            SkillEntry selected;
            try (var statement =
                    connection.prepareStatement("SELECT * FROM skill_versions WHERE skill_id = ? AND revision = ?")) {
                statement.setString(1, id);
                statement.setLong(2, sourceRevision);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw new NoSuchElementException("Skill revision not found");
                    }
                    selected = readSkill(rows);
                }
            }
            var result = saveSkill(
                    connection,
                    id,
                    selected.name(),
                    selected.version(),
                    selected.manifest(),
                    current != null && current.enabled(),
                    expectedRevision,
                    null);
            idempotency.record(connection, "skill/restore", key, requestHash, result, System.currentTimeMillis());
            return result;
        });
    }

    @Override
    public SkillEntry putSkill(
            String id,
            String name,
            String version,
            String manifest,
            boolean enabled,
            long expectedRevision,
            String idempotencyKey) {
        return database.transaction(connection ->
                saveSkill(connection, id, name, version, manifest, enabled, expectedRevision, idempotencyKey));
    }

    SkillEntry saveSkill(
            Connection connection,
            String id,
            String name,
            String version,
            String manifest,
            boolean enabled,
            long expectedRevision,
            String idempotencyKey)
            throws SQLException {
        String skillId = bounded(id, "id", 160);
        String skillName = bounded(name, "name", 500);
        String skillVersion = bounded(version, "version", 100);
        String skillManifest = bounded(manifest, "manifest", 2_000_000);
        try {
            json.readTree(skillManifest);
        } catch (Exception failure) {
            throw new IllegalArgumentException("skill manifest is not valid JSON", failure);
        }
        String requestHash = H2IdempotencyStore.requestHash(
                skillId, skillName, skillVersion, skillManifest, enabled, expectedRevision);
        Optional<SkillEntry> replay =
                idempotency.replay(connection, "skill/install", idempotencyKey, requestHash, SkillEntry.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        SkillEntry current = findSkill(connection, skillId, true).orElse(null);
        long now = System.currentTimeMillis();
        SkillEntry result;
        if (current == null) {
            if (expectedRevision != 0) {
                throw new NoSuchElementException("skill not found: " + skillId);
            }
            long initialRevision;
            try (var statement = connection.prepareStatement(
                    "SELECT COALESCE(MAX(revision), 0) + 1 FROM skill_versions WHERE skill_id = ?")) {
                statement.setString(1, skillId);
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    initialRevision = rows.getLong(1);
                }
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO skills(skill_id, name, version, manifest_json,
                            enabled, revision, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                insert.setString(1, skillId);
                insert.setString(2, skillName);
                insert.setString(3, skillVersion);
                insert.setString(4, skillManifest);
                insert.setBoolean(5, enabled);
                insert.setLong(6, initialRevision);
                insert.setLong(7, now);
                insert.setLong(8, now);
                insert.executeUpdate();
            }
            result = new SkillEntry(
                    skillId,
                    skillName,
                    skillVersion,
                    skillManifest,
                    enabled,
                    initialRevision,
                    instant(now),
                    instant(now));
        } else {
            requireRevision("skill", skillId, current.revision(), expectedRevision);
            long revision = current.revision() + 1;
            try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE skills SET name = ?, version = ?, manifest_json = ?,
                            enabled = ?, revision = ?, updated_at = ?
                        WHERE skill_id = ? AND revision = ?
                        """)) {
                update.setString(1, skillName);
                update.setString(2, skillVersion);
                update.setString(3, skillManifest);
                update.setBoolean(4, enabled);
                update.setLong(5, revision);
                update.setLong(6, now);
                update.setString(7, skillId);
                update.setLong(8, current.revision());
                if (update.executeUpdate() != 1) {
                    conflict("skill", skillId);
                }
            }
            result = new SkillEntry(
                    skillId,
                    skillName,
                    skillVersion,
                    skillManifest,
                    enabled,
                    revision,
                    current.createdAt(),
                    instant(now));
        }
        recordSkillVersion(connection, result);
        idempotency.record(connection, "skill/install", idempotencyKey, requestHash, result, now);
        return result;
    }

    @Override
    public boolean setSkillEnabled(String id, boolean enabled, long expectedRevision, String idempotencyKey) {
        String skillId = bounded(id, "id", 160);
        String requestHash = H2IdempotencyStore.requestHash(skillId, enabled, expectedRevision);
        return database.transaction(connection -> {
            Optional<Boolean> replay = idempotency.replay(
                    connection, enabled ? "skill/enable" : "skill/disable", idempotencyKey, requestHash, Boolean.class);
            if (replay.isPresent()) {
                return replay.get();
            }
            SkillEntry current = findSkill(connection, skillId, true).orElse(null);
            if (current == null) {
                return false;
            }
            requireRevision("skill", skillId, current.revision(), expectedRevision);
            long now = System.currentTimeMillis();
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE skills SET enabled = ?, revision = revision + 1, updated_at = ?
                    WHERE skill_id = ? AND revision = ?
                    """)) {
                update.setBoolean(1, enabled);
                update.setLong(2, now);
                update.setString(3, skillId);
                update.setLong(4, current.revision());
                if (update.executeUpdate() != 1) {
                    conflict("skill", skillId);
                }
            }
            String method = enabled ? "skill/enable" : "skill/disable";
            recordSkillVersion(
                    connection,
                    new SkillEntry(
                            current.id(),
                            current.name(),
                            current.version(),
                            current.manifest(),
                            enabled,
                            current.revision() + 1,
                            current.createdAt(),
                            instant(now)));
            idempotency.record(connection, method, idempotencyKey, requestHash, true, now);
            return true;
        });
    }

    private String encodedChunks(List<IndexedChunk> chunks) {
        try {
            return json.writeValueAsString(chunks);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalArgumentException("knowledge generation cannot be serialized", failure);
        }
    }

    private static void recordSkillVersion(Connection connection, SkillEntry value) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO skill_versions(skill_id, revision, name, version, manifest_json,
                    enabled, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, value.id());
            statement.setLong(2, value.revision());
            statement.setString(3, value.name());
            statement.setString(4, value.version());
            statement.setString(5, value.manifest());
            statement.setBoolean(6, value.enabled());
            statement.setLong(7, value.createdAt().toEpochMilli());
            statement.setLong(8, value.updatedAt().toEpochMilli());
            statement.executeUpdate();
        }
    }

    @Override
    public boolean deleteSkill(String id, long expectedRevision, String idempotencyKey) {
        return deleteVersioned(
                "skill/uninstall",
                "skills",
                "skill_id",
                "skill",
                bounded(id, "id", 160),
                expectedRevision,
                idempotencyKey);
    }

    private boolean deleteVersioned(
            String method,
            String table,
            String idColumn,
            String resourceName,
            String id,
            long expectedRevision,
            String idempotencyKey) {
        if (!Set.of("memories", "skills").contains(table)) {
            throw new AssertionError(table);
        }
        String requestHash = H2IdempotencyStore.requestHash(id, expectedRevision);
        return database.transaction(connection -> {
            Optional<Boolean> replay =
                    idempotency.replay(connection, method, idempotencyKey, requestHash, Boolean.class);
            if (replay.isPresent()) {
                return replay.get();
            }
            Long revision = null;
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT revision FROM " + table + " WHERE " + idColumn + " = ? FOR UPDATE")) {
                query.setString(1, id);
                try (ResultSet row = query.executeQuery()) {
                    if (row.next()) {
                        revision = row.getLong(1);
                    }
                }
            }
            long now = System.currentTimeMillis();
            boolean deleted = revision != null;
            if (deleted) {
                requireRevision(resourceName, id, revision, expectedRevision);
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM " + table + " WHERE " + idColumn + " = ? AND revision = ?")) {
                    delete.setString(1, id);
                    delete.setLong(2, revision);
                    if (delete.executeUpdate() != 1) {
                        conflict(resourceName, id);
                    }
                }
            }
            idempotency.record(connection, method, idempotencyKey, requestHash, deleted, now);
            return deleted;
        });
    }

    private Optional<MemoryEntry> findMemory(Connection connection, String id, boolean lock) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT * FROM memories WHERE memory_id = ?" + (lock ? " FOR UPDATE" : ""))) {
            query.setString(1, id);
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? Optional.of(readMemory(row)) : Optional.empty();
            }
        }
    }

    private Optional<KnowledgeSource> findSource(Connection connection, String id, boolean lock) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT * FROM knowledge_sources WHERE source_id = ?" + (lock ? " FOR UPDATE" : ""))) {
            query.setString(1, id);
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? Optional.of(readSource(row)) : Optional.empty();
            }
        }
    }

    Optional<SkillEntry> findSkill(Connection connection, String id, boolean lock) throws SQLException {
        try (PreparedStatement query =
                connection.prepareStatement("SELECT * FROM skills WHERE skill_id = ?" + (lock ? " FOR UPDATE" : ""))) {
            query.setString(1, id);
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? Optional.of(readSkill(row)) : Optional.empty();
            }
        }
    }

    private static MemoryEntry readMemory(ResultSet row) throws SQLException {
        return new MemoryEntry(
                row.getString("memory_id"),
                row.getString("workspace_id"),
                row.getString("kind"),
                row.getString("content"),
                row.getLong("revision"),
                instant(row.getLong("created_at")),
                instant(row.getLong("updated_at")));
    }

    private static KnowledgeSource readSource(ResultSet row) throws SQLException {
        return new KnowledgeSource(
                row.getString("source_id"),
                row.getString("workspace_id"),
                row.getString("attachment_sha256"),
                row.getString("display_name"),
                row.getString("media_type"),
                row.getString("status"),
                row.getLong("revision"),
                instant(row.getLong("created_at")),
                instant(row.getLong("updated_at")));
    }

    static SkillEntry readSkill(ResultSet row) throws SQLException {
        return new SkillEntry(
                row.getString("skill_id"),
                row.getString("name"),
                row.getString("version"),
                row.getString("manifest_json"),
                row.getBoolean("enabled"),
                row.getLong("revision"),
                instant(row.getLong("created_at")),
                instant(row.getLong("updated_at")));
    }

    private static void requireWorkspace(Connection connection, String id) throws SQLException {
        try (PreparedStatement query =
                connection.prepareStatement("SELECT locked, lock_reason FROM workspaces WHERE workspace_id = ?")) {
            query.setString(1, id);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) {
                    throw new NoSuchElementException("workspace not found: " + id);
                }
                if (row.getBoolean(1)) {
                    throw new IllegalStateException("workspace is locked: " + row.getString(2));
                }
            }
        }
    }

    private static void requireAttachment(Connection connection, String hash) throws SQLException {
        try (PreparedStatement query =
                connection.prepareStatement("SELECT 1 FROM attachments WHERE content_sha256 = ? FOR UPDATE")) {
            query.setString(1, hash);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) {
                    throw new NoSuchElementException("attachment not found: " + hash);
                }
            }
        }
    }

    private static void requireRevision(String kind, String id, long current, long expected) {
        if (expected < 1 || current != expected) {
            conflict(kind, id);
        }
    }

    private static void conflict(String kind, String id) {
        throw new IllegalStateException(kind + " revision conflict: " + id);
    }

    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }

    private static String bounded(String value, String name, int maximum) {
        String result = required(value, name);
        if (result.length() > maximum) {
            throw new IllegalArgumentException(name + " exceeds " + maximum + " characters");
        }
        return result;
    }

    private static String hash(String value) {
        String result = required(value, "SHA-256").toLowerCase(Locale.ROOT);
        if (result.length() != 64 || !result.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid SHA-256 value");
        }
        return result;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static byte[] encode(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(Math.multiplyExact(vector.length, Float.BYTES))
                .order(ByteOrder.BIG_ENDIAN);
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("embedding contains a non-finite value");
            }
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    private static String chunkFingerprint(List<IndexedChunk> chunks) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (IndexedChunk chunk : chunks) {
                digest.update(Integer.toString(chunk.ordinal()).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(chunk.content().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(String.valueOf(chunk.embeddingProvider()).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(String.valueOf(chunk.embeddingModel()).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(String.valueOf(chunk.embeddingFingerprint()).getBytes(StandardCharsets.UTF_8));
                if (chunk.embedding() != null) {
                    digest.update(encode(chunk.embedding()));
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static float[] decode(byte[] bytes, int dimensions) {
        if (dimensions < 1 || bytes.length != dimensions * Float.BYTES) {
            return new float[0];
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        float[] result = new float[dimensions];
        for (int index = 0; index < dimensions; index++) {
            result[index] = buffer.getFloat();
        }
        return result;
    }

    private static double cosine(float[] left, float[] right) {
        double dot = 0, leftNorm = 0, rightNorm = 0;
        for (int index = 0; index < left.length; index++) {
            dot += (double) left[index] * right[index];
            leftNorm += (double) left[index] * left[index];
            rightNorm += (double) right[index] * right[index];
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / Math.sqrt(leftNorm * rightNorm);
    }

    private static double keywordScore(String query, String content) {
        String normalized = content.toLowerCase(Locale.ROOT);
        String[] terms = query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_-]+");
        int meaningful = 0, matches = 0;
        for (String term : terms) {
            if (term.isBlank()) {
                continue;
            }
            meaningful++;
            if (normalized.contains(term)) {
                matches++;
            }
        }
        return meaningful == 0 ? 0 : (double) matches / meaningful;
    }

    private static Instant instant(long milliseconds) {
        return Instant.ofEpochMilli(milliseconds);
    }
}
