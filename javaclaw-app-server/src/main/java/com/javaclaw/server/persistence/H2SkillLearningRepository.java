package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.agent.knowledge.KnowledgeRepository;
import com.javaclaw.agent.knowledge.LearningRepository;
import com.javaclaw.agent.tool.SkillManifests;
import com.javaclaw.core.api.ThreadItem;

/** Skill 学习的事务实现；模式、真实证据、用户修改和目标 revision 均在接受时重新核验。 */
final class H2SkillLearningRepository implements LearningRepository {
    private final H2Database database;
    private final H2KnowledgeRepository skills;
    private final H2IdempotencyStore idempotency = new H2IdempotencyStore();
    private final ObjectMapper json = new ObjectMapper();
    private final ThreadJsonCodec codec = new ThreadJsonCodec();

    H2SkillLearningRepository(H2Database database, H2KnowledgeRepository skills) {
        this.database = database;
        this.skills = skills;
    }

    @Override
    public LearningSettings learningSettings(String workspaceId) {
        return database.query(connection -> settings(connection, workspaceId));
    }

    @Override
    public LearningSettings saveLearningSettings(
            String workspaceId, String skillMode, boolean memoryAutomatic, long revision, String key) {
        if (!Set.of("OFF", "SUGGEST", "AUTO").contains(skillMode)) {
            throw new IllegalArgumentException("unknown learning mode");
        }
        String hash = H2IdempotencyStore.requestHash(workspaceId, skillMode, memoryAutomatic, revision);
        return database.transaction(connection -> {
            var replay = idempotency.replay(connection, "learning/configure", key, hash, LearningSettings.class);
            if (replay.isPresent()) {
                return replay.get();
            }
            lockWorkspace(connection, workspaceId);
            var current = settings(connection, workspaceId);
            if (current.revision() != revision) {
                throw new IllegalStateException("learning settings revision conflict");
            }
            long now = System.currentTimeMillis();
            try (var statement = connection.prepareStatement(
                    "MERGE INTO learning_settings(workspace_id, skill_mode, memory_automatic, revision, updated_at) KEY(workspace_id) VALUES (?, ?, ?, ?, ?)")) {
                statement.setString(1, workspaceId);
                statement.setString(2, skillMode);
                statement.setBoolean(3, memoryAutomatic);
                statement.setLong(4, revision + 1);
                statement.setLong(5, now);
                statement.executeUpdate();
            }
            var result = new LearningSettings(
                    workspaceId, skillMode, memoryAutomatic, revision + 1, Instant.ofEpochMilli(now));
            idempotency.record(connection, "learning/configure", key, hash, result, now);
            return result;
        });
    }

    @Override
    public SkillProposal proposeSkill(SkillDraft draft, boolean automatic, String reason, String key) {
        String hash = H2IdempotencyStore.requestHash(draft, automatic, reason);
        return database.transaction(connection -> {
            var replay = idempotency.replay(connection, "skill/propose", key, hash, SkillProposal.class);
            if (replay.isPresent()) {
                return replay.get();
            }
            lockWorkspace(connection, draft.workspaceId());
            LearningSettings preferences = settings(connection, draft.workspaceId());
            if ("OFF".equals(preferences.skillMode())) {
                throw new IllegalStateException("Skill learning is disabled");
            }
            requireEvidence(connection, draft);
            KnowledgeRepository.SkillEntry target = target(connection, draft);
            if (draft.targetId() != null
                    && (target == null ? 0 : target.revision()) != draft.expectedTargetRevision()) {
                throw new IllegalStateException("Skill proposal target revision conflict");
            }
            String id = target == null
                    ? (draft.targetId() == null
                            ? "learned_" + UUID.randomUUID().toString().replace("-", "")
                            : draft.targetId())
                    : target.id();
            SkillDraft fixed = new SkillDraft(
                    draft.workspaceId(),
                    id,
                    draft.name(),
                    draft.version(),
                    draft.manifest(),
                    draft.sourceItemIds(),
                    target == null ? 0 : target.revision());
            // 自动模式只新增不带资源/权限的已验证说明；任何已有内容（可能由用户编辑）和脚本都必须确认。
            boolean lowRisk = automatic
                    && "AUTO".equals(preferences.skillMode())
                    && target == null
                    && SkillManifests.resources(draft.manifest()).isEmpty()
                    && SkillManifests.instructions(draft.manifest()).length() <= 8_000
                    && safeManifest(draft.manifest());
            String explanation = reason == null ? "从已验证的执行结果提炼" : reason;
            if (target != null) {
                explanation += "；匹配已有 Skill，需确认更新，不能自动覆盖用户内容。";
            }
            if (explanation.length() > 4_000) {
                throw new IllegalArgumentException("Skill proposal reason exceeds limit");
            }
            var duplicate = pendingDuplicate(connection, fixed);
            if (duplicate != null) {
                idempotency.record(connection, "skill/propose", key, hash, duplicate, System.currentTimeMillis());
                return duplicate;
            }
            if (lowRisk) {
                skills.saveSkill(connection, id, fixed.name(), fixed.version(), fixed.manifest(), true, 0, null);
            }
            long now = System.currentTimeMillis();
            var proposal = new SkillProposal(
                    "skillprop_" + UUID.randomUUID().toString().replace("-", ""),
                    fixed,
                    lowRisk ? "ACCEPTED" : "PENDING",
                    explanation,
                    1,
                    Instant.ofEpochMilli(now),
                    Instant.ofEpochMilli(now));
            try (var statement = connection.prepareStatement("""
                    INSERT INTO skill_proposals(proposal_id, workspace_id, target_id, name, version, manifest_json,
                        sources_json, expected_target_revision, state, reason, revision, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                    """)) {
                statement.setString(1, proposal.id());
                statement.setString(2, fixed.workspaceId());
                statement.setString(3, fixed.targetId());
                statement.setString(4, fixed.name());
                statement.setString(5, fixed.version());
                statement.setString(6, fixed.manifest());
                statement.setString(7, encode(fixed.sourceItemIds()));
                statement.setLong(8, fixed.expectedTargetRevision());
                statement.setString(9, proposal.state());
                statement.setString(10, explanation);
                statement.setLong(11, now);
                statement.setLong(12, now);
                statement.executeUpdate();
            }
            idempotency.record(connection, "skill/propose", key, hash, proposal, now);
            return proposal;
        });
    }

    @Override
    public List<SkillProposal> skillProposals(String workspaceId) {
        return database.query(connection -> {
            var result = new ArrayList<SkillProposal>();
            try (var statement = connection.prepareStatement(
                    "SELECT * FROM skill_proposals WHERE workspace_id = ? ORDER BY created_at DESC")) {
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
    public SkillProposal reviewSkillProposal(String id, boolean accept, long revision, String key) {
        String hash = H2IdempotencyStore.requestHash(id, accept, revision);
        return database.transaction(connection -> {
            var replay = idempotency.replay(connection, "skill/proposal/review", key, hash, SkillProposal.class);
            if (replay.isPresent()) {
                return replay.get();
            }
            SkillProposal current;
            try (var statement =
                    connection.prepareStatement("SELECT * FROM skill_proposals WHERE proposal_id = ? FOR UPDATE")) {
                statement.setString(1, id);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw new NoSuchElementException("Skill proposal not found");
                    }
                    current = proposal(rows);
                }
            }
            if (current.revision() != revision || !"PENDING".equals(current.state())) {
                throw new IllegalStateException("Skill proposal revision conflict or already reviewed");
            }
            if (accept) {
                requireEvidence(connection, current.draft());
                var target = skills.findSkill(connection, current.draft().targetId(), true)
                        .orElse(null);
                skills.saveSkill(
                        connection,
                        current.draft().targetId(),
                        current.draft().name(),
                        current.draft().version(),
                        current.draft().manifest(),
                        target == null || target.enabled(),
                        current.draft().expectedTargetRevision(),
                        null);
            }
            long now = System.currentTimeMillis();
            var result = new SkillProposal(
                    id,
                    current.draft(),
                    accept ? "ACCEPTED" : "REJECTED",
                    current.reason(),
                    revision + 1,
                    current.createdAt(),
                    Instant.ofEpochMilli(now));
            try (var statement = connection.prepareStatement(
                    "UPDATE skill_proposals SET state = ?, revision = ?, updated_at = ? WHERE proposal_id = ?")) {
                statement.setString(1, result.state());
                statement.setLong(2, result.revision());
                statement.setLong(3, now);
                statement.setString(4, id);
                statement.executeUpdate();
            }
            idempotency.record(connection, "skill/proposal/review", key, hash, result, now);
            return result;
        });
    }

    private KnowledgeRepository.SkillEntry target(Connection connection, SkillDraft draft) throws SQLException {
        if (draft.targetId() != null) {
            return skills.findSkill(connection, draft.targetId(), true).orElse(null);
        }
        String instructions = SkillManifests.instructions(draft.manifest());
        try (var statement = connection.prepareStatement("SELECT * FROM skills ORDER BY skill_id")) {
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    var skill = H2KnowledgeRepository.readSkill(rows);
                    if (skill.name().equalsIgnoreCase(draft.name())
                            || SkillManifests.instructions(skill.manifest()).equals(instructions)) {
                        return skills.findSkill(connection, skill.id(), true).orElseThrow();
                    }
                }
            }
        }
        return null;
    }

    private SkillProposal pendingDuplicate(Connection connection, SkillDraft draft) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT * FROM skill_proposals WHERE workspace_id = ? AND state = 'PENDING' AND name = ?")) {
            statement.setString(1, draft.workspaceId());
            statement.setString(2, draft.name());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    var proposal = proposal(rows);
                    if (proposal.draft().manifest().equals(draft.manifest())) {
                        return proposal;
                    }
                }
            }
        }
        return null;
    }

    private void requireEvidence(Connection connection, SkillDraft draft) throws SQLException {
        boolean verified = false;
        for (String id : draft.sourceItemIds()) {
            try (var statement = connection.prepareStatement(
                    "SELECT i.payload_json, i.state FROM items i JOIN threads t ON t.thread_id = i.thread_id WHERE i.item_id = ? AND t.workspace_id = ?")) {
                statement.setString(1, id);
                statement.setString(2, draft.workspaceId());
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || !"COMPLETED".equals(rows.getString("state"))) {
                        throw new IllegalArgumentException("Skill evidence is missing or outside workspace");
                    }
                    ThreadItem item = codec.item(rows.getString("payload_json"));
                    verified |= item instanceof ThreadItem.CommandExecution command
                                    && command.exitCode() == 0
                                    && !command.timedOut()
                            || item instanceof ThreadItem.Evaluation evaluation && evaluation.passed();
                }
            }
        }
        if (!verified) {
            throw new IllegalArgumentException("Skill learning requires successful execution or acceptance evidence");
        }
    }

    private LearningSettings settings(Connection connection, String workspace) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM learning_settings WHERE workspace_id = ?")) {
            statement.setString(1, workspace);
            try (var rows = statement.executeQuery()) {
                return rows.next()
                        ? new LearningSettings(
                                workspace,
                                rows.getString("skill_mode"),
                                rows.getBoolean("memory_automatic"),
                                rows.getLong("revision"),
                                Instant.ofEpochMilli(rows.getLong("updated_at")))
                        : new LearningSettings(workspace, "SUGGEST", true, 0, Instant.EPOCH);
            }
        }
    }

    private SkillProposal proposal(ResultSet row) throws SQLException {
        try {
            var sources = json.readValue(row.getString("sources_json"), String[].class);
            var draft = new SkillDraft(
                    row.getString("workspace_id"),
                    row.getString("target_id"),
                    row.getString("name"),
                    row.getString("version"),
                    row.getString("manifest_json"),
                    List.of(sources),
                    row.getLong("expected_target_revision"));
            return new SkillProposal(
                    row.getString("proposal_id"),
                    draft,
                    row.getString("state"),
                    row.getString("reason"),
                    row.getLong("revision"),
                    Instant.ofEpochMilli(row.getLong("created_at")),
                    Instant.ofEpochMilli(row.getLong("updated_at")));
        } catch (java.io.IOException invalid) {
            throw new IllegalStateException("invalid persisted Skill proposal", invalid);
        }
    }

    private boolean safeManifest(String manifest) {
        try {
            var fields = json.readTree(manifest).fieldNames();
            while (fields.hasNext()) {
                if (!Set.of("instructions", "description", "resources").contains(fields.next())) {
                    return false;
                }
            }
            return true;
        } catch (java.io.IOException invalid) {
            return false;
        }
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (java.io.IOException invalid) {
            throw new IllegalArgumentException("invalid Skill proposal value", invalid);
        }
    }

    private static void lockWorkspace(Connection connection, String workspace) throws SQLException {
        try (var statement =
                connection.prepareStatement("SELECT locked FROM workspaces WHERE workspace_id = ? FOR UPDATE")) {
            statement.setString(1, workspace);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getBoolean(1)) {
                    throw new IllegalStateException("workspace is unavailable or locked");
                }
            }
        }
    }
}
